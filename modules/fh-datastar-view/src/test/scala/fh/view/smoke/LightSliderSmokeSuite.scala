package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{Scene, SmokeDashboard}
import io.circe.Json

/** `c.entitySlider(l)` on a colour light in a browser (#547): the pill at the
  * track's end, and a tap on its mode button stepping to the next head.
  */
class LightSliderSmokeSuite extends SmokeSuite {

  private val scene =
    Scene.of(SmokeDashboard.lightSlider).entity(SmokeDashboard.colourLamp)

  /** Only the shown mode's button is visible. */
  private def modeButton(page: Page) =
    page.locator(".fh-slider-pill button.fh-mode:visible")

  test("the pill sits over the track's end, with one mode button showing") {
    withPage(scene, Some(900 -> 700)) { (page, _) =>
      IO.blocking {
        assertThat(modeButton(page)).hasCount(1)
        assertThat(modeButton(page)).hasAttribute("aria-label", "Brightness")
        val track =
          page.locator(".fh-slider-modes-panel .fh-slider").boundingBox()
        val pill = page.locator(".fh-slider-pill").boundingBox()
        // Over the track's right end, as any slider's buttons are.
        assert(
          pill.x > track.x &&
            pill.x + pill.width <= track.x + track.width + 1,
          clue = (track.x, track.width, pill.x, pill.width)
        )
      }
    }
  }

  test("each tap on the mode button steps to the next head, and round") {
    withPage(scene) { (page, _) =>
      IO.blocking {
        assertThat(page.locator("input[type=range][max='255']")).hasCount(1)
        modeButton(page).click()
        assertThat(page.locator("input[type=range][max='6500']")).hasCount(1)
        assertThat(page.locator("input[type=range][max='255']")).hasCount(0)
        assertThat(modeButton(page))
          .hasAttribute("aria-label", "Colour temperature")
        modeButton(page).click()
        assertThat(page.locator("input[type=range][max='360']")).hasCount(1)
        modeButton(page).click()
        assertThat(page.locator("input[type=range][max='255']")).hasCount(1)
      }
    }
  }

  test("a hue release posts hs_color at full saturation") {
    withPage(scene) { (page, ts) =>
      val hue = page.locator("input[type=range][max='360']")
      for {
        _ <- IO.blocking(modeButton(page).click())
        _ <- IO.blocking(
          assertThat(page.locator("input[type=range][max='6500']")).hasCount(1)
        )
        _ <- IO.blocking(modeButton(page).click())
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
