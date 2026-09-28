package fh.view.functional

import cats.effect.IO
import fh.view.model.Access
import fh.view.runtime.TestServer
import fh.view.testkit.Scene

import scala.concurrent.duration.*

/** Base for the end-to-end functional suites (ADR 0009) against a stubbed HA. A
  * test declares only its world through a [[Scene]], which derives the seeded
  * entities from the dashboard.
  */
abstract class FunctionalSuite extends munit.CatsEffectSuite {

  protected def scene: Scene = Scene.empty

  /** The timeout makes a missed SSE fragment fail fast rather than hang. */
  def withServer[A](
      scene: Scene,
      access: Access = Access.default
  )(f: TestServer => IO[A]): IO[A] =
    TestServer
      .resource(scene.dashboard, scene.entities, access = access)
      .use(f)
      .timeout(45.seconds)

  /** For [[TestServer.sentAfter]]'s result. */
  def carries(marker: String)(sent: String): Unit =
    assert(sent.contains(marker), clue = sent)
}
