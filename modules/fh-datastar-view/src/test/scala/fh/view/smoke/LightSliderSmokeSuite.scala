package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{Scene, SmokeDashboard}
import io.circe.Json

/** `c.light.slider` in a browser (#547): the bar beside the control, and a
  * press that swaps the panel the page shows.
  */
class LightSliderSmokeSuite extends SmokeSuite {

  private val scene =
    Scene.of(SmokeDashboard.lightSlider).entity(SmokeDashboard.colourLamp)

  private def mode(page: Page, label: String) =
    page.locator(s".fh-modes-bar button[aria-label='$label']")

  test("the mode buttons sit on one line, right of the slider") {
    withPage(scene, Some(900 -> 700)) { (page, _) =>
      IO.blocking {
        val buttons = page.locator(".fh-modes-bar button")
        assertThat(buttons).hasCount(3)
        val slider =
          page.locator(".fh-modes-panel article.fh-card").boundingBox()
        val boxes = (0 until 3).map(i => buttons.nth(i).boundingBox())
        assertEquals(boxes.map(_.y).distinct.size, 1, clue = boxes.map(_.y))
        assert(boxes.forall(_.x >= slider.x + slider.width), clue = slider.x)
      }
    }
  }

  test("pressing Colour swaps the brightness track for the hue") {
    withPage(scene) { (page, _) =>
      IO.blocking {
        assertThat(page.locator("input[type=range][max='255']")).hasCount(1)
        mode(page, "Colour").click()
        assertThat(page.locator("input[type=range][max='360']")).hasCount(1)
        assertThat(page.locator("input[type=range][max='255']")).hasCount(0)
        assertThat(mode(page, "Colour")).hasAttribute("aria-pressed", "true")
        assertThat(mode(page, "Brightness"))
          .hasAttribute("aria-pressed", "false")
      }
    }
  }

  test("a hue release posts hs_color at full saturation") {
    withPage(scene) { (page, ts) =>
      val hue = page.locator("input[type=range][max='360']")
      for {
        _ <- IO.blocking(mode(page, "Colour").click())
        _ <- IO.blocking(assertThat(hue).hasCount(1))
        // End jumps the thumb to `max` and releases.
        _ <- IO.blocking(hue.focus())
        _ <- IO.blocking(hue.press("End"))
        calls <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
      } yield assertEquals(
        calls.map(_.serviceData.hcursor.downField("hs_color").focus),
        Vector(Some(Json.arr(Json.fromInt(360), Json.fromInt(100))))
      )
    }
  }
}
