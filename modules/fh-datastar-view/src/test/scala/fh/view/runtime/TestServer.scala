// In `fh.view.runtime` (not `testkit`) so it can reach the `private[runtime]`
// readiness seams (`StateStore.changeSubscribers`, `Server.connectedSessions`)
// the deterministic SSE gating needs — the same access `ServerSuite` relies on.
package fh.view.runtime

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{host, port}
import fh.view.auth.AuthSessions
import fh.view.build.{PklDump, Site, SystemPkl}
import fh.view.model.{Access, Dashboard}
import fh.view.testkit.{
  FakeConfig,
  FakeHomeAssistant,
  FixtureEntity,
  PklWorkspace,
  TestAuth
}
import fs2.concurrent.SignallingRef
import io.circe.Json
import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkHttpClient

import java.util.concurrent.TimeoutException

import scala.concurrent.duration.*

/** A running dashboard wired exactly as `ServerApp` assembles it — the real
  * [[HaFeed]] (supervisor + facade + health signal), [[StateStore]],
  * [[Renderer]] and [[Server]], coupled through [[Server.fromFeed]] just like
  * production — but against a [[FakeHomeAssistant]] seeded from a static
  * fixture, handed to the feed as a never-closing [[HaFeed.Connect]]
  * ([[TestServer.fakeConnect]]). So the reconnect/facade/`haDown` machinery
  * runs for real; only the HA socket is stubbed. Tests drive it at the HTTP
  * boundary and assert observable behaviour (rendered HTML, streamed SSE
  * fragments, recorded service calls); the fake supplies the timeline via
  * [[FakeHomeAssistant.emit]].
  */
final class TestServer(
    val fake: FakeHomeAssistant,
    val store: StateStore,
    val server: Server,
    val slug: String,
    val auth: TestAuth
) {

  /** Await `n` subscribers on the store's change topic — a readiness gate for
    * tests that consume `store.changes` directly (topics only reach current
    * subscribers).
    */
  def awaitChangeSubscribers(n: Int): IO[Unit] =
    store.changeSubscribers.filter(_ >= n).head.compile.drain

  /** Await `n` ADOPTED sessions — a stream has taken its document's session and
    * is live (`Tenure.Held`). There is no shared patch topic to count
    * subscribers on: nothing is pushed, so what a test has to wait for is a
    * connection that will PULL. Paired with [[awaitChangeSubscribers]] as the
    * readiness gate a browser test awaits before `fake.emit` (a browser
    * establishes its own SSE connection asynchronously on page load, so there
    * is no response body to read progress from the way [[observePatch]]'s
    * callers can).
    *
    * Counting sessions is necessary but NOT sufficient for a test that emits a
    * change and expects it in a live patch: a session is adopted BEFORE its
    * opening block runs, so a change emitted on this gate alone can still land
    * in the opening repaint and never appear as a patch. A test that needs the
    * distinction gates on the connection's own opening cursor instead — see
    * `ServerSuite`'s "rendered once between them".
    */
  def awaitSharedSubscribers(n: Int = 1): IO[Unit] =
    server.connectedSessions.filter(_ >= n).head.compile.drain

  /** The two readiness gates a live SSE connection needs before a change is
    * guaranteed to reach it — `subscribers` mirrors [[observePatch]]'s default
    * of 1: the per-slug recorder is the ONLY consumer of `changes`, however
    * many connections are open. The smoke suites' one gate to await before
    * `fake.emit`.
    */
  def awaitLive(subscribers: Int = 1): IO[Unit] =
    awaitChangeSubscribers(subscribers) *> awaitSharedSubscribers(1)

  /** Make the server forget every open connection — the state an idle page is
    * in once its stream has dropped and [[Server.LingerWindow]] has passed.
    *
    * The half of ADR 0009's "known gap" that hid this bug three times: no smoke
    * test could reach a reconnect, so nothing that only goes wrong on one was
    * visible. Reaping is what cuts the browser's stream (the stream watches its
    * own tenure), so Datastar reconnects for real afterwards.
    */
  def forgetConnections: IO[Int] = server.forgetSessions

  /** Wait until no connection is adopted — after [[forgetConnections]], that
    * the browser has actually noticed.
    */
  def awaitNoConnections: IO[Unit] =
    server.connectedSessions.filter(_ == 0).head.compile.drain

  /** The gate is ON here, behind the same backstop `ServerApp` uses — so a
    * harness request goes through the route's own declared requirement, and a
    * route that declared none fails the suite as a 500 rather than passing. See
    * [[TestAuth]] for why there is no bypass.
    */
  private val app = server.routes.orNotFound

  /** `as` is the session id to present, i.e. WHO is asking; `None` is an
    * anonymous browser. Defaulted to the harness admin so a suite that is not
    * about auth reads exactly as it did before the gate existed.
    */
  private def run(
      req: Request[IO],
      as: Option[String] = Some(auth.defaultSession)
  ): IO[Response[IO]] =
    app.run(as.fold(req)(id => req.addCookie(AuthSessions.CookieName, id)))

  /** The whole app — backstop included — with the harness admin's cookie added
    * to every request, for the suites that drive routes directly instead of
    * through [[page]] / [[post]]. Without the cookie every gated route answers
    * 303/401, which is correct and unhelpful.
    */
  val gatedApp: HttpApp[IO] = HttpApp[IO](req => run(req))

  private def bodyOf(resp: Response[IO]): IO[String] =
    resp.body.through(fs2.text.utf8.decode).compile.string

  /** The rendered page shell for this dashboard (`GET /d/<slug><query>`) — the
    * bytes a browser gets BEFORE any script runs, which is where first-paint
    * claims (a baked tab panel, a restored popup) have to be checked.
    */
  def page(
      query: String = "",
      as: Option[String] = Some(auth.defaultSession)
  ): IO[String] =
    pageResponse(query, as).flatMap(bodyOf)

  /** [[page]] without reading the body — for the auth suites, which assert on
    * the status and the `Location` of a denial rather than on rendered HTML.
    */
  def pageResponse(
      query: String = "",
      as: Option[String] = Some(auth.defaultSession)
  ): IO[Response[IO]] =
    run(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/d/$slug$query")),
      as
    )

  /** POST an action route (e.g. `sse/action/<slug>/light/toggle/light.kitchen`)
    * and return its status. An action that WORKS says so with 204 and nothing
    * else; the observable effect is the recorded [[ServiceCall]].
    */
  def post(
      path: String,
      as: Option[String] = Some(auth.defaultSession)
  ): IO[Status] =
    postResult(path, as).map(_._1)

  /** [[post]] keeping the body too, which is where a REFUSAL now lives: a
    * refused action answers 200 carrying the signals that report it (ADR 0024),
    * so a suite asserting on a refusal has to read what was said, not only that
    * something was.
    */
  def postResult(
      path: String,
      as: Option[String] = Some(auth.defaultSession)
  ): IO[(Status, String)] =
    run(
      Request[IO](
        Method.POST,
        Uri.unsafeFromString("/" + path.stripPrefix("/"))
      ),
      as
    ).flatMap(resp => bodyOf(resp).map(resp.status -> _))

  private val patchUri: Uri =
    Uri.unsafeFromString(s"/sse/dashboard/$slug/patch")

  /** The live patch stream, unread — for the auth suites, which assert on the
    * status of a refusal and on WHEN an admitted body ends, not on what it
    * carries.
    */
  def sse(as: Option[String] = Some(auth.defaultSession)): IO[Response[IO]] =
    run(Request[IO](Method.GET, patchUri), as)

  /** Open one live SSE connection, wait until the store's change publishers are
    * attached, run `trigger` (typically a `fake.emit`), and succeed once a
    * pushed fragment contains `marker`. If the marker never arrives the
    * returned `IO` fails via `timeout` — so this is the positive "a change
    * reaches the browser" assertion.
    *
    * `subscribers` is the number of `StateStore.changes` consumers to await
    * before triggering (topics only reach already-subscribed consumers). That
    * is the shared per-slug publisher, and only it — connections subscribe to
    * the patch topic, not to `changes` — so one open connection is 1.
    */
  /** Open one live SSE connection (optionally with a `query` — e.g. a
    * `ui.<host>` tab selection), wait until its OPENING block has been
    * delivered, then run `trigger` and return everything that arrives after it,
    * up to and including the fragment containing `marker`.
    *
    * The opening/live split is the whole point. A first paint legitimately
    * carries this client's selected tab, so an assertion about what a LIVE flip
    * sends has to start after it — otherwise the opening satisfies the marker
    * and the test passes without the flip happening at all. The opening ends
    * with the cursor signal ([[Server.cursorSignals]]), which is what is
    * watched for here.
    */
  private def LogIdSignalName = Server.LogIdSignal

  def observeLive(
      marker: String,
      trigger: IO[Unit],
      query: String = "",
      // Below munit's per-test timeout on purpose: whichever fires first owns
      // the error message, and this one can say what actually arrived.
      timeout: FiniteDuration = 10.seconds
  ): IO[String] =
    run(
      Request[IO](
        Method.GET,
        Uri.unsafeFromString(s"/sse/dashboard/$slug/patch$query")
      )
    ).flatMap { resp =>
      // Everything is accumulated and the split computed on the WHOLE text,
      // rather than routing chunk-by-chunk: the opening cursor and the first
      // patch can share a chunk, and a router that decides per chunk drops
      // whatever followed the cursor inside it.
      def liveOf(text: String): String = {
        val at = text.indexOf(Server.LogIdSignal)
        if (at < 0) "" else text.drop(at)
      }
      for {
        opened <- Deferred[IO, Unit]
        all <- Ref[IO].of("")
        fiber <- resp.body
          .through(fs2.text.utf8.decode)
          .evalMap(chunk => all.updateAndGet(_ + chunk))
          .evalTap(text =>
            IO.whenA(text.contains(Server.LogIdSignal))(
              opened.complete(()).void
            )
          )
          .exists(text => liveOf(text).contains(marker))
          .compile
          .drain
          .start
        _ <- opened.get.timeout(timeout).adaptError {
          case _: TimeoutException =>
            new AssertionError(
              s"the opening block never completed (no $LogIdSignalName signal)"
            )
        }
        // BOTH gates, and they are different questions. The connection being
        // subscribed to the patch topic says it can receive; the per-slug
        // publisher being subscribed to the store says the change will be
        // rendered at all. A topic delivers only to CURRENT subscribers, so a
        // change emitted before the publisher attaches is published to nobody
        // and simply lost — intermittently, under load, which is exactly how
        // this read as a flaky test rather than a missing gate.
        _ <- server.connectedSessions.filter(_ >= 1).head.compile.drain
        _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
        _ <- trigger
        // Report what DID arrive. A bare timeout here says only "the marker
        // never came", which is the least useful half of the story — whether
        // nothing arrived, or the wrong thing did, is the whole diagnosis.
        _ <- fiber.joinWithNever.timeout(timeout).recoverWith {
          case _: TimeoutException =>
            all.get
              .map(liveOf)
              .flatMap(seen =>
                IO.raiseError(
                  new AssertionError(
                    s"never saw '$marker' after the opening block; received:\n$seen"
                  )
                )
              )
        }
        text <- all.get.map(liveOf)
      } yield text
    }

  def observePatch(
      marker: String,
      trigger: IO[Unit],
      subscribers: Int = 1,
      timeout: FiniteDuration = 30.seconds
  ): IO[Unit] =
    run(Request[IO](Method.GET, patchUri)).flatMap { resp =>
      for {
        // THIS connection's opening block is finished when its cursor arrives.
        // Gating on a session COUNT instead is not enough: a previous
        // `observePatch`'s connection can still be registered, so the count is
        // already met while this one has not read the snapshot — and a change
        // triggered in that window lands in the opening repaint, which never
        // carries a `mode remove` or any other delta shape a test looks for.
        opened <- Deferred[IO, Unit]
        fiber <- resp.body
          .through(fs2.text.utf8.decode)
          .scan("")(_ + _)
          .evalTap(text =>
            IO.whenA(text.contains(Server.StoreVersionSignal))(
              opened.complete(()).void
            )
          )
          .exists(_.contains(marker))
          .compile
          .drain
          .start
        // The recorder must be on the store's changes before we emit.
        _ <- store.changeSubscribers.filter(_ >= subscribers).head.compile.drain
        _ <- opened.get.timeout(timeout)
        _ <- trigger
        _ <- fiber.joinWithNever.timeout(timeout)
      } yield ()
    }
}

object TestServer {

  /** Wire a [[TestServer]] for `dashboard`, seeded with `entities`. The
    * returned resource owns the store's live feed and the server's shared-patch
    * publishers for its lifetime (via [[Server.resource]]).
    */
  def resource(
      dashboard: Dashboard,
      entities: List[FixtureEntity],
      // The instance's Pkl artifacts, served over `/system/pkl/` — what a CLI
      // pull fetches (ADR 0010). Empty (serving nothing) for every test that
      // isn't about that endpoint.
      systemPkl: SystemPkl = SystemPkl.empty,
      // The dashboard's own access rule, which is what the gate reads
      // (`Server.accessFor` asks the renderer). Defaulted, so every suite that
      // is not about auth gets the production default.
      access: Access = Access.default
  ): Resource[IO, TestServer] =
    for {
      fake <- FakeHomeAssistant.create(entities).toResource
      feed <- HaFeed.resource(fakeConnect(fake))
      rendererRef <- SignallingRef[IO]
        .of(Server.RendererState.Ready(Renderer.create(dashboard, access)))
        .toResource
      // Delegate the whole live-Server assembly (health gate, sessions,
      // Server.fromFeed) to the SAME kernel production uses, so the harness
      // can't drift from the app. Only the renderer source (a fixed dashboard)
      // and the HA edge (a fake) are ours.
      site <- Server.LiveSite
        .of(
          Map(dashboard.slug -> rendererRef),
          Map(dashboard.slug -> Right(dashboard)),
          dashboard.slug
        )
        .toResource
      auth <- TestAuth.create(site.permissionFor).toResource
      server <- ServerApp.liveServer(
        feed,
        site,
        auth.gate,
        systemPkl = systemPkl
      )
    } yield new TestServer(fake, feed.store, server, dashboard.slug, auth)

  /** Wire a [[TestServer]] the way [[resource]] does — real feed, store, and
    * Server — but from a Pkl ENTRY SOURCE evaluated through the genuine build
    * path (`ServerApp.prepareRenderers`: discover -> `prepareDumps` ->
    * `buildEntry`), not a pre-built [[Dashboard]]. This is the Tier-A capstone
    * seam (ADR 0009): the Pkl authoring track and the runtime track meet with
    * NOTHING stubbed but the HA socket.
    *
    * A package-form workspace is staged ([[PklWorkspace.bootstrap]]) with the
    * `@fh-home` dump seeded from `entities`; `entrySource` is written as its
    * own module and the workspace's ONE entrypoint (ADR 0021) is generated to
    * name it under `slug` — the imported-dashboard authoring form, exercised
    * here on every call. The feed's own `prepareDumps` then RE-fetches that
    * dump from the fake's `render_template` — the same fixtures `get_states`
    * serves — so the dashboard is authored against, built from, and rendered
    * with one source of state. `entities` must cover every entity the entry
    * references (`dump.entities.<key>`); the whole set is also the fake's seed.
    */
  def fromWorkspace(
      slug: String,
      entrySource: String,
      entities: List[FixtureEntity]
  ): Resource[IO, TestServer] =
    for {
      tmp <- IO.blocking(os.temp.dir(prefix = "fh-workspace")).toResource
      _ <- IO.blocking {
        // Seed the workspace dump from the fixtures so `@fh-home` resolves;
        // `prepareDumps` re-seeds an identical dump (the fake's raw dump
        // transforms to exactly this), a clean no-op pin move.
        val dumpJson = Json.obj(
          "areas" -> Json.obj(),
          "floors" -> Json.obj(),
          "entities" -> Json.fromFields(entities.map(_.toDumpEntry))
        )
        val _ = PklWorkspace.bootstrap(tmp, PklDump.render(dumpJson))
        val module = s"$slug-entry.pkl"
        os.write.over(tmp / module, entrySource)
        // Exactly one dashboard, named by the entrypoint the bootstrap seeded
        // (overwritten: the starter site is not what this test is about).
        os.write.over(
          tmp / Site.EntryFile,
          s"""amends "@fh-dashboard/site.pkl"
             |
             |dashboards {
             |  ["$slug"] = import("$module")
             |}
             |""".stripMargin
        )
      }.toResource
      fake <- FakeHomeAssistant.create(entities).toResource
      feed <- HaFeed.resource(fakeConnect(fake))
      // The REAL source-to-renderer path — the same one production's `run` uses.
      prepared <- ServerApp.prepareRenderers(feed, tmp, None).toResource
      rendererRefs <- prepared.states.toList
        .traverse { case (s, state) => SignallingRef[IO].of(state).map(s -> _) }
        .map(_.toMap)
        .toResource
      site <- Server.LiveSite
        .of(rendererRefs, prepared.content, slug)
        .toResource
      auth <- TestAuth.create(site.permissionFor).toResource
      server <- ServerApp.liveServer(
        feed,
        site,
        auth.gate,
        systemPkl = SystemPkl.fromDisk(tmp)
      )
    } yield new TestServer(fake, feed.store, server, slug, auth)

  /** Same wiring as [[resource]], plus a real [[AssetCache]] built exactly as
    * `ServerApp` builds it — a JDK http client fetching the theme's CDN assets
    * into a temp dir — and a real ember bind on an OS-assigned loopback port,
    * so a browser (the Playwright smoke suite) can navigate to it. State is
    * still driven in-process through `TestServer.fake.emit`, exactly as
    * [[resource]].
    */
  def served(
      dashboard: Dashboard,
      entities: List[FixtureEntity],
      config: FakeConfig = FakeConfig()
  ): Resource[IO, (TestServer, Uri)] =
    for {
      fake <- FakeHomeAssistant.create(entities, config).toResource
      feed <- HaFeed.resource(fakeConnect(fake))
      // Public, so the browser needs no cookie: a real Playwright session
      // cannot be handed one before its first navigation, and driving the OAuth
      // flow against a fake HA would test the fake. The gate still runs on
      // every one of these requests — it is a `Public` dashboard passing, which
      // is the wall-tablet case and worth covering end to end.
      renderer = Renderer.create(dashboard, Access.Public)
      rendererRef <- SignallingRef[IO]
        .of(Server.RendererState.Ready(renderer))
        .toResource
      httpClient <- IO(java.net.http.HttpClient.newHttpClient()).toResource
      assetsDir <- IO
        .blocking(os.temp.dir(prefix = "fh-smoke-assets"))
        .toResource
      assets <- AssetCache
        .build(
          assetsDir,
          Server.DatastarCdn :: renderer.stylesheets ++ renderer.scripts,
          JdkHttpClient[IO](httpClient)
        )
        .toResource
      site <- Server.LiveSite
        .of(
          Map(dashboard.slug -> rendererRef),
          Map(dashboard.slug -> Right(dashboard)),
          dashboard.slug
        )
        .toResource
      auth <- TestAuth.create(site.permissionFor).toResource
      server <- ServerApp.liveServer(feed, site, auth.gate, assets)
      bound <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpApp(server.routes.orNotFound)
        .withShutdownTimeout(0.seconds)
        .build
    } yield (
      new TestServer(fake, feed.store, server, dashboard.slug, auth),
      bound.baseUri
    )

  /** A [[HaFeed.Connect]] that hands the supervisor the in-memory fake as the
    * low-level WS connection and never closes (`IO.never`) — so the real feed
    * runs (durable facade, background seed, health signal) against a scripted
    * HA and stays "connected" for the test's whole duration, no reconnect
    * churn. A reconnect/`haDown` test that WANTS a drop supplies its own
    * connect with a completable close instead.
    */
  private def fakeConnect(fake: FakeHomeAssistant): HaFeed.Connect =
    Resource.pure((fake, IO.never[Unit]))
}
