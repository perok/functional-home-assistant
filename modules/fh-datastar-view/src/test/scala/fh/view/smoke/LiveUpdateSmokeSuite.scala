package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{FixtureDashboard, HouseFixture}

/** "SSE morphs the DOM": the check only a real browser can make — that a pushed
  * `datastar-patch-elements` frame is not just sent on the wire (the Scala
  * functional suite already proves that end-to-end), but actually APPLIED by
  * Datastar to the live page.
  */
class LiveUpdateSmokeSuite extends SmokeSuite {

  test("a live state change morphs the DOM, no reload") {
    withPage(FixtureDashboard.dashboard, HouseFixture.all) { (page, ts) =>
      for {
        _ <- ts.awaitLive()
        _ <- ts.fake.emit(
          HouseFixture.outsideTemp.entityId,
          "13.1",
          HouseFixture.outsideTemp.attributes
        )
        _ <- IO.blocking(assertThat(page.locator("body")).containsText("13.1"))
      } yield ()
    }
  }
}
