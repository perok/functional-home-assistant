package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{FixtureDashboard, HouseFixture, Scene}

/** "SSE morphs the DOM": the check only a real browser can make — that a pushed
  * `datastar-patch-elements` frame is not just sent on the wire (the Scala
  * functional suite already proves that end-to-end), but actually APPLIED by
  * Datastar to the live page.
  */
class LiveUpdateSmokeSuite extends SmokeSuite {

  test("a live state change morphs the DOM, no reload") {
    withPage(Scene.of(FixtureDashboard.dashboard)) { (page, ts) =>
      def emit(state: String): IO[Unit] =
        ts.fake.emit(
          HouseFixture.outsideTemp.entityId,
          state,
          HouseFixture.outsideTemp.attributes
        )
      for {
        _ <- ts.awaitLive()
        // Server-side liveness is not client-side readiness — see
        // [[SmokeSuite.awaitApplying]]. Once it holds, ONE change has to be
        // enough, which is what this test is actually about.
        _ <- awaitApplying(page)(emit)
        _ <- emit("13.1")
        _ <- IO.blocking(assertThat(page.locator("body")).containsText("13.1"))
      } yield ()
    }
  }
}
