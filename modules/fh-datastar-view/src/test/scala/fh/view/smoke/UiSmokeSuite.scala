package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{HouseFixture, Scene, ServiceCall, SmokeDashboard}
import io.circe.Json

/** Signal-driven UI with no server round-trip — tabs, popup, slider — the
  * Datastar attribute wiring (`data-signals`/`data-bind`/`data-class`) that has
  * no wire-level observable at all; only a real DOM proves it.
  */
class UiSmokeSuite extends SmokeSuite {

  private val scene = Scene.of(SmokeDashboard.dashboard)

  test("tabs: the bar swaps the panel, no reload") {
    withPage(scene) { (page, _) =>
      val panel = page.locator(".tab-panel")
      val climateTab =
        page.locator(".tabs a", new Page.LocatorOptions().setHasText("Climate"))
      for {
        _ <- IO.blocking(assertThat(panel).containsText("Living Room"))
        _ <- IO.blocking(climateTab.click())
        _ <- IO.blocking(assertThat(panel).containsText("Hallway"))
      } yield ()
    }
  }

  test("popup: a tap opens it, the close button dismisses it") {
    withPage(scene) { (page, _) =>
      val kitchenCard = page
        .locator(
          "article.entity",
          new Page.LocatorOptions().setHasText("Kitchen")
        )
      val popup = page.locator(".popup")
      for {
        _ <- IO.blocking(assertThat(popup).hasCount(0))
        _ <- IO.blocking(kitchenCard.click())
        _ <- IO.blocking(assertThat(popup).containsText("Kitchen Detail"))
        _ <- IO.blocking(page.locator(".popup-close").click())
        _ <- IO.blocking(assertThat(popup).hasCount(0))
      } yield ()
    }
  }

  test("slider: a keyboard commit posts the value-carrying action") {
    withPage(scene) { (page, ts) =>
      val slider = page.locator("input[type=range]")
      for {
        _ <- IO.blocking(slider.focus())
        // Native <input type=range> keyboard behaviour: End jumps to `max`
        // (255, the light domain's brightness ceiling) — deterministic,
        // unlike a synthetic drag.
        _ <- IO.blocking(slider.press("End"))
        calls <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
      } yield assertEquals(
        calls,
        Vector(
          ServiceCall(
            "light",
            "turn_on",
            HouseFixture.kitchenLight.entityId,
            Json.obj("brightness" -> Json.fromInt(255))
          )
        )
      )
    }
  }
}
