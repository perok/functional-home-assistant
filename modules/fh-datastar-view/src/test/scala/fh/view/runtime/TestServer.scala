// In `fh.view.runtime` (not `testkit`) so it can reach the `private[runtime]`
// readiness seams (`StateStore.changeSubscribers`, `Server.sharedSubscribers`)
// the deterministic SSE gating needs — the same access `ServerSuite` relies on.
package fh.view.runtime

import cats.effect.{IO, Resource}
import fh.view.model.Dashboard
import fh.view.testkit.{FakeHomeAssistant, FixtureEntity}
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

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

  private val app = server.routes.orNotFound

  private def run(req: Request[IO]): IO[Response[IO]] = app.run(req)

  private def bodyOf(resp: Response[IO]): IO[String] =
    resp.body.through(fs2.text.utf8.decode).compile.string

  /** The rendered page shell for this dashboard (`GET /d/<slug>`). */
  def page: IO[String] =
    run(Request[IO](Method.GET, Uri.unsafeFromString(s"/d/$slug"))).flatMap(bodyOf)

  /** POST an action route (e.g. `sse/action/light/toggle/light.kitchen`) and
    * return its status. The body is a fire-and-forget SSE ack the test ignores;
    * the observable effect is the recorded [[ServiceCall]].
    */
  def post(path: String): IO[Status] =
    run(Request[IO](Method.POST, Uri.unsafeFromString("/" + path.stripPrefix("/"))))
      .map(_.status)

  private val patchUri: Uri =
    Uri.unsafeFromString(s"/sse/dashboard/$slug/patch")

  /** Open one live SSE connection, wait until the store's change publishers are
    * attached, run `trigger` (typically a `fake.emit`), and succeed once a
    * pushed fragment contains `marker`. If the marker never arrives the returned
    * `IO` fails via `timeout` — so this is the positive "a change reaches the
    * browser" assertion.
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
        _ <- server.sharedSubscribers(slug).filter(_ >= 1).head.compile.drain
        _ <- trigger
        _ <- fiber.joinWithNever.timeout(timeout)
      } yield ()
    }
}

object TestServer {

  /** Wire a [[TestServer]] for `dashboard`, seeded with `entities`. The returned
    * resource owns the store's live feed and the server's shared-patch
    * publishers for its lifetime (via [[Server.resource]]).
    */
  def resource(
      dashboard: Dashboard,
      entities: List[FixtureEntity]
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
        sessions
      )
    } yield new TestServer(fake, store, server, dashboard.slug)
}
