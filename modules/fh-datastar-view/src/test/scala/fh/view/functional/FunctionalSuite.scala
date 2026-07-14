package fh.view.functional

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.model.Dashboard
import fh.view.runtime.TestServer
import fh.view.testkit.FixtureEntity

import scala.concurrent.duration.*

/** Shared base for the end-to-end functional suites
  * (`plan-functional-e2e-tests.md`): the whole loop — seed snapshot ->
  * `StateStore` -> `Server` -> HTTP/SSE, and control -> `callService` — against
  * a stubbed Home Assistant with a scripted timeline.
  *
  * [[withServer]] takes the two fakes a test varies — the `dashboard` under
  * test and the `entities` the [[fh.view.testkit.FakeHomeAssistant]] is seeded
  * from — as parameters, so each test declares only the world it exercises.
  * Most tests start from an empty seed and add just the entity (and the card
  * that binds it, via `FixtureDashboard`/`DashboardBuilders`) whose behaviour
  * they assert; that keeps a failure's world small and readable.
  */
abstract class FunctionalSuite extends munit.FunSuite {

  /** Run `f` against a freshly-wired [[TestServer]] for `dashboard`, seeded with
    * `entities`, with a global timeout so a missed SSE fragment fails fast
    * rather than hanging.
    */
  def withServer[A](dashboard: Dashboard, entities: List[FixtureEntity])(
      f: TestServer => IO[A]
  ): A =
    TestServer
      .resource(dashboard, entities)
      .use(f)
      .timeout(45.seconds)
      .unsafeRunSync()
}
