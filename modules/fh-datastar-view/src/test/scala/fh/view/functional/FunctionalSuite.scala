package fh.view.functional

import cats.effect.IO
import fh.view.runtime.TestServer
import fh.view.testkit.Scene

import scala.concurrent.duration.*

/** Shared base for the end-to-end functional suites (ADR 0009): the whole loop
  * — seed snapshot -> `StateStore` -> `Server` -> HTTP/SSE, and control ->
  * `callService` — against a stubbed Home Assistant with a scripted timeline.
  *
  * [[withServer]] takes a [[Scene]] — the builder that assembles the dashboard
  * under test and derives the entities the
  * [[fh.view.testkit.FakeHomeAssistant]] is seeded from — so a test declares
  * only the world it exercises: it adds the cards it asserts on (whose entities
  * auto-seed) and any extra entities it drives directly, and the two can't fall
  * out of sync by hand.
  */
abstract class FunctionalSuite extends munit.CatsEffectSuite {

  /** A fresh empty [[Scene]] — sugar so tests read `withServer(scene.card(..))`
    * rather than naming the companion.
    */
  protected def scene: Scene = Scene.empty

  /** Run `f` against a freshly-wired [[TestServer]] for `scene`'s dashboard,
    * seeded with `scene`'s entities, with a global timeout so a missed SSE
    * fragment fails fast rather than hanging. Returns the `IO` for munit to run
    * (via [[munit.CatsEffectSuite]]) — no `unsafeRunSync`.
    */
  def withServer[A](scene: Scene)(f: TestServer => IO[A]): IO[A] =
    TestServer
      .resource(scene.dashboard, scene.entities)
      .use(f)
      .timeout(45.seconds)
}
