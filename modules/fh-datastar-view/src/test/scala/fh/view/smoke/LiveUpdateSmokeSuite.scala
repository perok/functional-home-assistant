package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{FixtureDashboard, HouseFixture, Scene}

/** That a pushed patch is applied by Datastar to the live page, not just sent.
  * Also the only test catching a change between a browser connecting and the
  * recorder writing it (see `Server.openingPatches`); it failed on that every
  * time and was twice mistaken for flakiness.
  */
class LiveUpdateSmokeSuite extends SmokeSuite {

  test("a live state change morphs the DOM, no reload") {
    withPage(Scene.of(FixtureDashboard.dashboard)) { (page, ts) =>
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
