package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{FixtureDashboard, HouseFixture, Scene, SmokeDashboard}

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

  test("a row of pills inside a column lies on one line") {
    withPage(Scene.of(SmokeDashboard.pillRowInColumn)) { (page, _) =>
      IO.blocking {
        val pills = page.locator(".fh-chip")
        assertThat(pills).hasCount(3)
        val tops = (0 until 3).map(i => pills.nth(i).boundingBox().y)
        assert(tops.distinct.size == 1, clue = tops)
      }
    }
  }
}
