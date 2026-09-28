// In `fh.view.runtime` so it can reach the `private[runtime]` readiness seams
// (`StateStore.changeSubscribers`, `Server.connectedSessions`, `Sessions`).
package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.std.Supervisor
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{host, port}
import fh.view.auth.{AuthSessions, HaOAuth}
import fh.view.build.{PklDump, Site}
import fh.view.model.{Access, Dashboard}
import fh.view.telemetry.{Logging, Meters, Telemetry}
import fh.view.testkit.{
  FakeConfig,
  FakeHomeAssistant,
  FixtureEntity,
  PklWorkspace,
  TestAuth
}
import io.circe.Json
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.server.websocket.WebSocketBuilder2

import java.util.concurrent.TimeoutException

import scala.concurrent.duration.*

/** [[ServerApp.assemble]] with only its edges stubbed: the HA socket is a
  * [[FakeHomeAssistant]] handed over as a never-closing connection, and HA's
  * token endpoint and the asset CDN are in-process stubs. Everything else — the
  * feed's reconnect and narrowing, the auth routes, the error boundary — is the
  * production wiring, and requests go through the app production binds.
  */
final class TestServer(
    val fake: FakeHomeAssistant,
    val store: StateStore,
    val server: Server,
    val slug: String,
    val auth: TestAuth,
    app: HttpApp[IO],
    sessions: Sessions,
    supervisor: Supervisor[IO],
    clients: Ref[IO, List[TestServer.LiveClient]]
) {

  /** '''`n` counts the recorder too.''' A test adding its own subscriber must
    * wait for two: waiting for one is answered by the recorder, and an emit can
    * land before the test's stream attaches, failing as a `take(1)` timeout
    * rather than a wrong value.
    */
  def awaitChangeSubscribers(n: Int): IO[Unit] =
    store.changeSubscribers.filter(_ >= n).head.compile.drain

  /** Await `n` adopted sessions (`Tenure.Held`). Nothing is pushed, so a test
    * waits for a connection that will pull.
    *
    * Necessary but not sufficient for a test expecting a change as a live
    * patch: a session is adopted before its opening block runs, so the change
    * can land in the opening repaint. Such a test gates on the connection's own
    * opening cursor instead (see `SharedPassSuite`'s "rendered once between
    * them").
    */
  def awaitSharedSubscribers(n: Int = 1): IO[Unit] =
    server.connectedSessions.filter(_ >= n).head.compile.drain

  /** The smoke suites' one gate before `fake.emit`. `subscribers` defaults to
    * 1: the per-slug recorder is the only consumer of `changes`, however many
    * connections are open.
    */
  def awaitLive(subscribers: Int = 1): IO[Unit] =
    awaitChangeSubscribers(subscribers) *> awaitSharedSubscribers(1)

  /** The state of an idle page once its stream has dropped and
    * [[Server.LingerWindow]] has passed. Reaping cuts the browser's stream, so
    * Datastar reconnects for real: the path ADR 0009's known gap hid three
    * times.
    */
  def forgetConnections: IO[Int] = server.forgetSessions

  /** After [[forgetConnections]]: the browser has noticed. */
  def awaitNoConnections: IO[Unit] =
    server.connectedSessions.filter(_ == 0).head.compile.drain

  /** `as` is the session id presented; `None` is an anonymous browser.
    * Defaulted to the harness admin.
    */
  private def run(
      req: Request[IO],
      as: Option[String] = Some(auth.defaultSession)
  ): IO[Response[IO]] =
    app.run(as.fold(req)(id => req.addCookie(AuthSessions.CookieName, id)))

  /** With the harness admin's cookie on every request, for suites driving
    * routes directly; without it every gated route answers 303/401.
    */
  val gatedApp: HttpApp[IO] = HttpApp[IO](req => run(req))

  private def bodyOf(resp: Response[IO]): IO[String] =
    resp.body.through(fs2.text.utf8.decode).compile.string

  /** The bytes a browser gets before any script runs, where first-paint claims
    * have to be checked.
    */
  def page(
      query: String = "",
      as: Option[String] = Some(auth.defaultSession)
  ): IO[String] =
    pageResponse(query, as).flatMap(bodyOf)

  def pageResponse(
      query: String = "",
      as: Option[String] = Some(auth.defaultSession)
  ): IO[Response[IO]] =
    run(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/d/$slug$query")),
      as
    )

  /** An action that works answers 204 and nothing else; the effect is the
    * recorded [[ServiceCall]].
    */
  def post(
      path: String,
      as: Option[String] = Some(auth.defaultSession)
  ): IO[Status] =
    postResult(path, as).map(_._1)

  /** A refused action answers 200 carrying the signals that report it (ADR
    * 0024), so a refusal is read from the body.
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

  def sse(as: Option[String] = Some(auth.defaultSession)): IO[Response[IO]] =
    run(Request[IO](Method.GET, patchUri), as)

  private def LogIdSignalName = Server.LogIdSignal

  /** A live connection collecting its events for [[TestServer.LiveClient]],
    * returned once its opening block is in. `query` carries what a document
    * would hand back, such as `?ui.<id>=<n>` (see [[Server.Restore]]).
    */
  def connect(query: String = ""): IO[TestServer.LiveClient] =
    for {
      seen <- Ref[IO].of(Vector.empty[ServerSentEvent])
      resp <- run(
        Request[IO](
          Method.GET,
          Uri.unsafeFromString(s"/sse/dashboard/$slug/patch$query")
        )
      )
      // Supervised: the body carries the connection's `keepAlive` stream, and
      // with no socket to close it a bare fiber outlives the test.
      _ <- supervisor.supervise(
        resp.body
          .through(ServerSentEvent.decoder[IO])
          .evalMap(e => seen.update(_ :+ e))
          .compile
          .drain
      )
      client = new TestServer.LiveClient(seen)
      // `_haDown` rides a branch merged into the connection stream, so it can
      // land either side of the cursor; waiting for the cursor alone left it
      // for the next drain.
      _ <- fs2.Stream
        .repeatEval(seen.get <* IO.sleep(10.millis))
        .find(es =>
          es.exists(carries(Server.StoreVersionSignal)) &&
            es.exists(carries(Server.HaDownSignal))
        )
        .compile
        .drain
        .timeout(15.seconds)
      _ <- clients.update(_ :+ client)
    } yield client

  private def carries(signal: String)(e: ServerSentEvent): Boolean =
    e.eventType.contains("datastar-patch-signals") &&
      e.data.exists(_.contains(signal))

  /** One HA frame through the fake, returned once every [[connect]]ed client
    * has what it is owed. Two gates: [[served]] proves every session pulled
    * this version, then [[TestServer.LiveClient.arrived]] that the bytes
    * landed. A client owed nothing receives nothing, so "the frame is done" has
    * to be asked of the server.
    */
  def change(entityId: String, state: String): IO[Unit] =
    frame(FixtureEntity(entityId, state))

  def frame(entities: FixtureEntity*): IO[Unit] =
    for {
      // A topic delivers only to current subscribers, and the recorder starts
      // asynchronously, so a frame that beats it is never recorded and every
      // later gate times out. Connecting a client does not imply it.
      _ <- awaitChangeSubscribers(1).timeout(15.seconds)
      before <- store.version
      _ <- fake.emitFrame(entities.toList)
      _ <- fs2.Stream
        .repeatEval(store.version <* IO.sleep(5.millis))
        .find(_ > before)
        .compile
        .drain
        .timeout(15.seconds)
      _ <- served
      _ <- clients.get.flatMap(_.traverse_(_.arrived))
    } yield ()

  /** Not `Sessions.floor`, which includes `Lingering` and `Fresh` sessions: one
    * with no stream never pulls, so waiting on it times out, and the test that
    * left it fails elsewhere, intermittently.
    */
  private def served: IO[Unit] =
    fs2.Stream
      .repeatEval(
        (store.version, sessions.forSlug(slug)).flatMapN { (now, all) =>
          all
            .traverse(s => (s.tenure.get, s.position.get).tupled)
            .map(_.collect { case (_: Tenure.Held, at) => at })
            .map(live => live.nonEmpty && live.forall(_ >= now))
        } <* IO.sleep(5.millis)
      )
      .find(identity)
      .compile
      .drain
      .timeout(15.seconds)

  /** Open one live connection (`query` e.g. a `ui.<host>` tab selection), wait
    * until its opening block is delivered, then run `trigger` and return
    * everything after it up to the fragment containing `marker`. A first paint
    * carries this client's selected tab, so without the split the opening
    * satisfies the marker and the test passes without the flip.
    */
  def observeLive(
      marker: String,
      trigger: IO[Unit],
      query: String = "",
      // Below munit's per-test timeout: whichever fires first owns the message,
      // and this one says what arrived.
      timeout: FiniteDuration = 10.seconds
  ): IO[String] =
    run(
      Request[IO](
        Method.GET,
        Uri.unsafeFromString(s"/sse/dashboard/$slug/patch$query")
      )
    ).flatMap { resp =>
      // Split on the whole text: the opening cursor and the first patch can
      // share a chunk, and a per-chunk router drops whatever followed the
      // cursor.
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
        // Both gates: a connected session can receive, and the recorder on the
        // store means the change is rendered at all. A topic delivers only to
        // current subscribers, so an emit before it attaches is lost,
        // intermittently.
        _ <- server.connectedSessions.filter(_ >= 1).head.compile.drain
        _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
        _ <- trigger
        // Report what did arrive: nothing, or the wrong thing, is the
        // diagnosis.
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

  /** Open one live connection, wait for the recorder, run `trigger` (typically
    * a `fake.emit`), and succeed once a fragment contains `marker`; fails by
    * `timeout` otherwise. `subscribers` counts `StateStore.changes` consumers,
    * which is the per-slug recorder alone, so 1.
    */
  def observePatch(
      marker: String,
      trigger: IO[Unit],
      subscribers: Int = 1,
      timeout: FiniteDuration = 30.seconds
  ): IO[Unit] =
    run(Request[IO](Method.GET, patchUri)).flatMap { resp =>
      for {
        // This connection's cursor ends its opening block. A session count is
        // not enough: an earlier `observePatch`'s connection can still be
        // registered, and a change in that window lands in the opening repaint,
        // which carries no delta shape.
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
        _ <- store.changeSubscribers.filter(_ >= subscribers).head.compile.drain
        _ <- opened.get.timeout(timeout)
        _ <- trigger
        _ <- fiber.joinWithNever.timeout(timeout)
      } yield ()
    }
}

object TestServer {

  final class LiveClient(seen: Ref[IO, Vector[ServerSentEvent]]) {

    def drain: IO[List[ServerSentEvent]] =
      seen.getAndSet(Vector.empty).map(_.toList)

    /** Quiet alone cannot tell "nothing was produced" from "nothing has arrived
      * yet", so it follows [[TestServer.change]]'s server-side proof that every
      * session pulled the frame. A pull that owes a client nothing sends
      * nothing, not even a cursor, so waiting for one would hang on exactly the
      * clients this checks are left alone.
      */
    def arrived: IO[Unit] =
      fs2.Stream
        .repeatEval(seen.get.map(_.size) <* IO.sleep(25.millis))
        .drop(6)
        // Four equal readings, not two: a batch's events can arrive more than a
        // sample apart under load, and two samples returned mid-batch in CI.
        .sliding(4)
        .find(w => w.toList.distinct.sizeIs == 1)
        .compile
        .drain
  }

  /** A model dashboard as the site's starting content, so no Pkl is evaluated.
    */
  def resource(
      dashboard: Dashboard,
      entities: List[FixtureEntity],
      // What a CLI pull fetches over `/system/pkl/` (ADR 0010); an empty one
      // when not named.
      workspace: Option[os.Path] = None,
      // The site default the gate falls back to when the dashboard sets none.
      access: Access = Access.default
  ): Resource[IO, TestServer] =
    for {
      validated <- IO
        .fromEither(
          dashboard
            .validated()
            .leftMap(errs =>
              IllegalArgumentException(
                s"'${dashboard.slug}' does not validate: ${errs.mkString("; ")}"
              )
            )
        )
        .toResource
      fake <- FakeHomeAssistant.create(entities).toResource
      dir <- workspace.fold(tempDir("fh-workspace"))(Resource.pure)
      assembled <- assemble(
        fake,
        dir,
        startingWith(validated.withAccess(access))
      )
      ts <- inProcess(fake, dashboard.slug, assembled)
    } yield ts

  /** [[resource]], but from a Pkl entry source through the real build path
    * (`ServerApp.prepareRenderers`): the Tier-A seam (ADR 0009) where authoring
    * and runtime meet with only the HA socket stubbed.
    *
    * The entry is its own module, named under `slug` by the one entrypoint (ADR
    * 0021). `prepareDumps` re-fetches the dump from the fake's
    * `render_template`, so the dashboard is authored against, built from and
    * rendered with one source of state; `entities` must cover every
    * `dump.entities.<key>` it references.
    */
  def fromWorkspace(
      slug: String,
      entrySource: String,
      entities: List[FixtureEntity]
  ): Resource[IO, TestServer] =
    for {
      tmp <- stageWorkspace(slug, entrySource, entities)
      fake <- FakeHomeAssistant.create(entities).toResource
      assembled <- assemble(fake, tmp, ServerApp.prepareRenderers(_, tmp, None))
      ts <- inProcess(fake, slug, assembled)
    } yield ts

  /** [[fromWorkspace]] on a real port with the theme's assets. The entry must
    * set `access = c.access.public`: a Playwright page cannot be handed a
    * cookie first.
    */
  def servedWorkspace(
      slug: String,
      entrySource: String,
      entities: List[FixtureEntity]
  ): Resource[IO, (TestServer, Uri)] =
    for {
      tmp <- stageWorkspace(slug, entrySource, entities)
      fake <- FakeHomeAssistant.create(entities).toResource
      client <- cdnClient
      assembled <- assemble(
        fake,
        tmp,
        ServerApp.prepareRenderers(_, tmp, None),
        client
      )
      _ <- IO
        .fromOption(assembled.prepared.states.get(slug).flatMap(_.rendererOf))(
          RuntimeException(
            s"'$slug' did not build: ${assembled.prepared.states}"
          )
        )
        .toResource
      bound <- bind(fake, slug, assembled)
    } yield bound

  /** A workspace booted as it stands, whatever its `site.pkl` says, a broken
    * one included; the server's slug is the site's default.
    */
  def ofWorkspace(
      workspace: os.Path,
      entities: List[FixtureEntity] = Nil
  ): Resource[IO, (TestServer, ServerApp.Prepared)] =
    for {
      fake <- FakeHomeAssistant.create(entities).toResource
      assembled <- assemble(
        fake,
        workspace,
        ServerApp.prepareRenderers(_, workspace, None)
      )
      slug <- assembled.site.defaultSlug.toResource
      ts <- inProcess(fake, slug, assembled)
    } yield (ts, assembled.prepared)

  private def stageWorkspace(
      slug: String,
      entrySource: String,
      entities: List[FixtureEntity]
  ): Resource[IO, os.Path] =
    for {
      tmp <- IO.blocking(os.temp.dir(prefix = "fh-workspace")).toResource
      _ <- IO.blocking {
        // `prepareDumps` re-seeds an identical dump, a no-op pin move.
        val dumpJson = Json.obj(
          "areas" -> Json.obj(),
          "floors" -> Json.obj(),
          "entities" -> Json.fromFields(entities.map(_.toDumpEntry))
        )
        val _ = PklWorkspace.bootstrap(tmp, PklDump.render(dumpJson))
        val module = s"$slug-entry.pkl"
        os.write.over(tmp / module, entrySource)
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
    } yield tmp

  /** [[resource]] with the real CDN behind the asset cache and an ember bind on
    * a loopback port, for the Playwright suites. State is still driven through
    * `fake.emit`.
    */
  def served(
      dashboard: Dashboard,
      entities: List[FixtureEntity],
      config: FakeConfig = FakeConfig()
  ): Resource[IO, (TestServer, Uri)] =
    for {
      validated <- IO
        .fromEither(
          dashboard
            .validated()
            .leftMap(errs => IllegalArgumentException(errs.mkString("; ")))
        )
        .toResource
      fake <- FakeHomeAssistant.create(entities, config).toResource
      dir <- tempDir("fh-workspace")
      client <- cdnClient
      // Public: a Playwright session cannot be handed a cookie before its first
      // navigation, and driving OAuth against a fake HA would test the fake.
      // The gate still runs, the wall-tablet case.
      assembled <- assemble(
        fake,
        dir,
        startingWith(validated.withAccess(Access.Public)),
        client
      )
      bound <- bind(fake, dashboard.slug, assembled)
    } yield bound

  private def assemble(
      fake: FakeHomeAssistant,
      workspace: os.Path,
      prepare: HaFeed => IO[ServerApp.Prepared],
      assetsClient: Client[IO] = NoCdn
  ): Resource[IO, ServerApp.Assembled] =
    tempDir("fh-assets").flatMap(assetsDir =>
      ServerApp.assemble(
        ServerApp.Edges(
          workspace = workspace,
          haConnect = fakeConnect(fake),
          login = _ => IO.pure(fakeLogin(fake)),
          assetsClient = assetsClient,
          assetsDir = assetsDir,
          prepare = prepare,
          trustedProxy = None,
          pklLspJar = None,
          watchRegistry = false,
          otel = Telemetry.Otel.noop,
          loggerFactory = Logging.console,
          meters = Meters.noop
        )
      )
    )

  private def inProcess(
      fake: FakeHomeAssistant,
      slug: String,
      assembled: ServerApp.Assembled
  ): Resource[IO, TestServer] =
    for {
      wsb <- WebSocketBuilder2[IO].toResource
      auth <- TestAuth.admitted(assembled.authSessions).toResource
      // Acquired after `assemble`, so the connect fibers are gone before the
      // server releases.
      supervisor <- Supervisor[IO]
      clients <- Ref[IO].of(List.empty[LiveClient]).toResource
    } yield new TestServer(
      fake,
      assembled.feed.store,
      assembled.server,
      slug,
      auth,
      assembled.app(wsb),
      assembled.sessions,
      supervisor,
      clients
    )

  private def bind(
      fake: FakeHomeAssistant,
      slug: String,
      assembled: ServerApp.Assembled
  ): Resource[IO, (TestServer, Uri)] =
    for {
      ts <- inProcess(fake, slug, assembled)
      bound <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpWebSocketApp(assembled.app)
        .withShutdownTimeout(0.seconds)
        .build
    } yield (ts, bound.baseUri)

  private def startingWith(
      validated: Dashboard.Validated
  ): HaFeed => IO[ServerApp.Prepared] =
    _ =>
      IO.pure(
        ServerApp.Prepared(
          Map(validated.dashboard.slug -> Right(validated)),
          Some(validated.dashboard.slug),
          Set.empty
        )
      )

  private def tempDir(prefix: String): Resource[IO, os.Path] =
    IO.blocking(os.temp.dir(prefix = prefix)).toResource

  /** Never closes, so the feed stays connected for the whole test. A reconnect
    * test supplies its own connect with a completable close.
    */
  private def fakeConnect(fake: FakeHomeAssistant): HaFeed.Connect =
    Resource.pure((fake, IO.never[Unit]))

  /** HA's token endpoint mints for any grant, and a user's connection is the
    * same fake, so a tap made as a user lands where one made as the instance
    * does.
    */
  private def fakeLogin(fake: FakeHomeAssistant): ServerApp.HaLogin =
    ServerApp.HaLogin(
      new HaOAuth(
        uri"http://ha.test",
        uri"http://ha.test",
        Client.fromHttpApp(HttpApp[IO] { req =>
          if req.uri.path.renderString.endsWith("/auth/token") then
            Ok(
              """{"access_token":"minted","refresh_token":"r2","expires_in":1800}"""
            )
          else NotFound()
        })
      ),
      _ => Resource.pure(HomeAssistantApi.fromWs(fake))
    )

  /** Every asset answers empty, so no in-process test reaches the network. */
  private val NoCdn: Client[IO] =
    Client.fromHttpApp(HttpApp.pure(Response[IO](Status.Ok)))

  private def cdnClient: Resource[IO, Client[IO]] =
    IO(java.net.http.HttpClient.newHttpClient())
      .map(JdkHttpClient[IO](_))
      .toResource
}
