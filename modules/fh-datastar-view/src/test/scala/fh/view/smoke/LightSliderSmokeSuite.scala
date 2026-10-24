package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{Scene, SmokeDashboard}
import io.circe.Json

/** `c.entitySlider(l)` on a colour light in a browser (#547): the pill at the
  * track's end, and a tap on its control button stepping to the next head.
  */
class LightSliderSmokeSuite extends SmokeSuite {

  private val scene =
    Scene.of(SmokeDashboard.lightSlider).entity(SmokeDashboard.colourLamp)

  /** Only the shown control's button is visible. */
  private def controlButton(page: Page) =
    page.locator(".fh-slider-pill .fh-select-cycles button:visible")

  test("the pill sits over the track's end, with one control button showing") {
    withPage(scene, Some(900 -> 700)) { (page, _) =>
      IO.blocking {
        assertThat(controlButton(page)).hasCount(1)
        assertThat(controlButton(page)).hasAttribute("aria-label", "Brightness")
        val track =
          page.locator(".fh-slider-controls-panel .fh-slider").boundingBox()
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

  test("each tap on the control button steps to the next head, and round") {
    withPage(scene) { (page, _) =>
      IO.blocking {
        assertThat(page.locator("input[type=range][max='255']")).hasCount(1)
        controlButton(page).click()
        assertThat(page.locator("input[type=range][max='6500']")).hasCount(1)
        assertThat(page.locator("input[type=range][max='255']")).hasCount(0)
        assertThat(controlButton(page))
          .hasAttribute("aria-label", "Colour temperature")
        controlButton(page).click()
        assertThat(page.locator("input[type=range][max='360']")).hasCount(1)
        controlButton(page).click()
        assertThat(page.locator("input[type=range][max='255']")).hasCount(1)
      }
    }
  }

  test("a mode dropdown on the pill switches the head, and closes") {
    withPage(
      Scene
        .of(SmokeDashboard.lightSliderDropdown)
        .entity(SmokeDashboard.colourLamp)
    ) { (page, _) =>
      IO.blocking {
        val summary = page.locator(".fh-select-summary")
        val face = summary.locator("i[title]")
        assertThat(face).hasAttribute("title", "Brightness")
        summary.click()
        page
          .locator(
            ".fh-select-list a",
            new Page.LocatorOptions().setHasText("Colour temperature")
          )
          .click()
        assertThat(page.locator("input[type=range][max='6500']")).hasCount(1)
        assertThat(face).hasAttribute("title", "Colour temperature")
        assertThat(page.locator(".fh-select-list")).isHidden()
      }
    }
  }

  test(
    "the first mode puts no param on the URL; another does, until it steps round"
  ) {
    val param = """.*[?&]v\.[^=&]+\.control=.*"""
    def href(page: Page) =
      IO.blocking(page.evaluate("() => location.href").toString)
    // A button carries the value after its own, so a tap before the swap
    // lands sends that value again and the cycle stalls a step short.
    def step(page: Page, toMax: Int) = IO.blocking {
      controlButton(page).click()
      assertThat(page.locator(s"input[type=range][max='$toMax']")).hasCount(1)
    }
    withPage(scene) { (page, _) =>
      for {
        _ <- IO.blocking(assertThat(controlButton(page)).hasCount(1))
        loaded <- href(page)
        _ = assert(!loaded.matches(param), clue = loaded)
        _ <- step(page, 6500)
        _ <- eventually(href(page))(_.matches(param))
        _ <- step(page, 360)
        _ <- step(page, 255)
        back <- eventually(href(page))(!_.matches(param))
      } yield assert(!back.matches(param), clue = back)
    }
  }

  test("a colour temperature marker hides while the light reports no kelvin") {
    // A light in a colour mode: HA sends `color_temp_kelvin: null`.
    val inColour = SmokeDashboard.colourLamp.copy(attributes =
      SmokeDashboard.colourLamp.attributes - "color_temp_kelvin"
    )
    val marker =
      (page: Page) => page.locator(".fh-track-kelvin > span:not([class])")
    def toKelvin(page: Page) = {
      controlButton(page).click()
      assertThat(page.locator("input[type=range][max='6500']")).hasCount(1)
    }
    withPage(scene) { (page, _) =>
      IO.blocking {
        toKelvin(page)
        assertThat(marker(page)).isVisible()
      }
    } *> withPage(Scene.of(SmokeDashboard.lightSlider).entity(inColour)) {
      (page, _) =>
        IO.blocking {
          toKelvin(page)
          assertThat(marker(page)).isHidden()
          // A drag shows it again, in the ramp's colour.
          val k = page.locator("input[type=range][max='6500']")
          k.focus()
          k.press("End")
          assertThat(marker(page)).isVisible()
          assertThat(marker(page))
            .hasCSS("background-color", "rgb(201, 226, 255)")
        }
    }
  }

  test(
    "a lamp on a colour opens on the hue; a choice survives a refresh, the start does not need one"
  ) {
    val onColour = SmokeDashboard.colourLamp.copy(attributes =
      SmokeDashboard.colourLamp.attributes + ("color_mode" -> Json.fromString(
        "hs"
      ))
    )
    val param = """.*[?&]v\.[^=&]+\.control=.*"""
    def href(page: Page) =
      IO.blocking(page.evaluate("() => location.href").toString)
    def showing(page: Page, max: Int) = IO.blocking(
      assertThat(page.locator(s"input[type=range][max='$max']")).hasCount(1)
    )
    withPage(Scene.of(SmokeDashboard.lightSliderByMode).entity(onColour)) {
      (page, _) =>
        for {
          _ <- showing(page, 360)
          // A start is not a choice: nothing on the URL.
          opened <- href(page)
          _ = assert(!opened.matches(param), clue = opened)
          // After the hue comes brightness, round the cycle.
          _ <- IO.blocking(controlButton(page).click())
          _ <- showing(page, 255)
          chosen <- eventually(href(page))(_.matches(param))
          _ <- IO.blocking(page.reload())
          _ <- showing(page, 255)
          _ <- IO.blocking(page.navigate(chosen.takeWhile(_ != '?')))
          _ <- showing(page, 360)
        } yield ()
    }
  }

  test("a hue release posts hs_color at full saturation") {
    withPage(scene) { (page, ts) =>
      val hue = page.locator("input[type=range][max='360']")
      for {
        _ <- IO.blocking(controlButton(page).click())
        _ <- IO.blocking(
          assertThat(page.locator("input[type=range][max='6500']")).hasCount(1)
        )
        _ <- IO.blocking(controlButton(page).click())
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
