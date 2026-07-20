// In `fh.view.runtime` (not `testkit`) so it can reach the `private[runtime]`
// readiness seams (`StateStore.changeSubscribers`, `Server.sharedSubscribers`)
// the deterministic SSE gating needs — the same access `ServerSuite` relies on.
package fh.view.runtime

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{host, port}
import fh.view.build.SystemPkl
import fh.view.model.Dashboard
import fh.view.testkit.{FakeHomeAssistant, FixtureEntity}
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkHttpClient

import scala.concurrent.duration.*

/** A running dashboard wired exactly as `ServerApp` assembles it — the real
  * [[StateStore]], [[Renderer]] and [[Server]] — but against a
  * [[FakeHomeAssistant]] seeded from a static fixture. Tests drive it at the
  * HTTP boundary and assert observable behaviour (rendered HTML, streamed SSE
  * fragments, recorded service calls); the fake supplies the timeline via
  * [[FakeHomeAssistant.emit]].
  */
final class TestServer(
    val fake: FakeHomeAssistant,
    val store: StateStore,
    val server: Server,
    val slug: String
) {

  /** Await `n` subscribers on the store's change topic — a readiness gate for
    * tests that consume `store.changes` directly (topics only reach current
    * subscribers).
    */
  def awaitChangeSubscribers(n: Int): IO[Unit] =
    store.changeSubscribers.filter(_ >= n).head.compile.drain

  /** Await 1 subscriber on the shared-patch topic — the fan-out an open SSE
    * connection (browser or otherwise) subscribes to for main-page patches. The
    * topic is multiplexed across slugs, so this counts connections, not viewers
    * of this slug (every suite here drives a single dashboard). Paired with
    * [[awaitChangeSubscribers]] as the readiness gate a browser test awaits
    * before `fake.emit` (a browser establishes its own SSE connection
    * asynchronously on page load, so there is no response body to read progress
    * from the way [[observePatch]]'s callers can).
    */
  def awaitSharedSubscribers(n: Int = 1): IO[Unit] =
    server.sharedSubscribers.filter(_ >= n).head.compile.drain

  /** The two readiness gates a live SSE connection needs before a change is
    * guaranteed to reach it (topics only deliver to already-subscribed
    * consumers) — `subscribers` mirrors [[observePatch]]'s default of 2 for one
    * open connection (the shared publisher's own subscription plus this
    * connection's). The smoke suites' one gate to await before `fake.emit`.
    */
  def awaitLive(subscribers: Int = 2): IO[Unit] =
    awaitChangeSubscribers(subscribers) *> awaitSharedSubscribers(1)

  private val app = server.routes.orNotFound

  private def run(req: Request[IO]): IO[Response[IO]] = app.run(req)

  private def bodyOf(resp: Response[IO]): IO[String] =
    resp.body.through(fs2.text.utf8.decode).compile.string

  /** The rendered page shell for this dashboard (`GET /d/<slug>`). */
  def page: IO[String] =
    run(Request[IO](Method.GET, Uri.unsafeFromString(s"/d/$slug")))
      .flatMap(bodyOf)

  /** POST an action route (e.g. `sse/action/light/toggle/light.kitchen`) and
    * return its status. The body is a fire-and-forget SSE ack the test ignores;
    * the observable effect is the recorded [[ServiceCall]].
    */
  def post(path: String): IO[Status] =
    run(
      Request[IO](
        Method.POST,
        Uri.unsafeFromString("/" + path.stripPrefix("/"))
      )
    )
      .map(_.status)

  private val patchUri: Uri =
    Uri.unsafeFromString(s"/sse/dashboard/$slug/patch")

  /** Open one live SSE connection, wait until the store's change publishers are
    * attached, run `trigger` (typically a `fake.emit`), and succeed once a
    * pushed fragment contains `marker`. If the marker never arrives the
    * returned `IO` fails via `timeout` — so this is the positive "a change
    * reaches the browser" assertion.
    *
    * `subscribers` is the number of `StateStore.changes` consumers to await
    * before triggering (topics only reach already-subscribed consumers): the
    * shared per-slug publisher plus one per open SSE connection — so a single
    * connection is 2.
    */
  def observePatch(
      marker: String,
      trigger: IO[Unit],
      subscribers: Int = 2,
      timeout: FiniteDuration = 30.seconds
  ): IO[Unit] =
    run(Request[IO](Method.GET, patchUri)).flatMap { resp =>
      val seen = resp.body
        .through(fs2.text.utf8.decode)
        .scan("")(_ + _)
        .exists(_.contains(marker))
        .compile
        .drain
      for {
        fiber <- seen.start
        // Both the store's change publishers AND this connection's shared-topic
        // subscription must be live before we emit (topics only reach current
        // subscribers): the shared per-slug publisher + this session on
        // `changes`, and this connection on the slug's shared topic.
        _ <- store.changeSubscribers.filter(_ >= subscribers).head.compile.drain
        _ <- server.sharedSubscribers.filter(_ >= 1).head.compile.drain
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
      systemPkl: SystemPkl = SystemPkl.empty
  ): Resource[IO, TestServer] =
    for {
      fake <- FakeHomeAssistant.create(entities).toResource
      store <- StateStore.create(fake)
      rendererRef <- SignallingRef[IO]
        .of(Renderer.create(dashboard))
        .toResource
      sessions <- Sessions.create.toResource
      server <- Server.resource(
        fake,
        store,
        Map(dashboard.slug -> rendererRef),
        dashboard.slug,
        sessions,
        systemPkl = systemPkl
      )
    } yield new TestServer(fake, store, server, dashboard.slug)

  /** Same wiring as [[resource]], plus a real [[AssetCache]] built exactly as
    * `ServerApp` builds it — a JDK http client fetching the theme's CDN assets
    * into a temp dir — and a real ember bind on an OS-assigned loopback port,
    * so a browser (the Playwright smoke suite) can navigate to it. State is
    * still driven in-process through `TestServer.fake.emit`, exactly as
    * [[resource]].
    */
  def served(
      dashboard: Dashboard,
      entities: List[FixtureEntity]
  ): Resource[IO, (TestServer, Uri)] =
    for {
      fake <- FakeHomeAssistant.create(entities).toResource
      store <- StateStore.create(fake)
      renderer = Renderer.create(dashboard)
      rendererRef <- SignallingRef[IO].of(renderer).toResource
      sessions <- Sessions.create.toResource
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
      server <- Server.resource(
        fake,
        store,
        Map(dashboard.slug -> rendererRef),
        dashboard.slug,
        sessions,
        assets
      )
      bound <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpApp(server.routes.orNotFound)
        .withShutdownTimeout(0.seconds)
        .build
    } yield (new TestServer(fake, store, server, dashboard.slug), bound.baseUri)
}
