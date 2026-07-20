package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{FixtureDashboard, HouseFixture, Scene}

/** "The page is alive": navigate a real browser to the dashboard and see the
  * seeded snapshot — server up, template rendered, Datastar loaded (no
  * `withPage` call ever passes without console-error-free JS). No interaction;
  * [[LiveUpdateSmokeSuite]]/[[ControlSmokeSuite]] cover that.
  */
class RenderSmokeSuite extends SmokeSuite {

  test("the seeded snapshot renders in a real browser") {
    withPage(Scene.of(FixtureDashboard.dashboard)) { (page, _) =>
      IO.blocking {
        assertThat(page.locator("body"))
          .containsText(HouseFixture.outsideTemp.state)
        assertThat(page.locator("body")).containsText("Kitchen")
        assertThat(page.locator("body")).containsText("on")
      }
    }
  }
}
