package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.{Locator, Page}
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import fh.view.testkit.{Scene, SmokeDashboard, VisualSnapshot}

/** Component screenshots of [[SmokeDashboard]] against [[VisualSnapshot]]
  * baselines: correct HTML and selectors say nothing about whether the CSS
  * paints right. Per component, so a failure names the card and one card's
  * reflow does not fail the others.
  */
class ComponentVisualSuite extends SmokeSuite {

  private val viewport = Some(900 -> 700)
  private val scene = Scene.of(SmokeDashboard.dashboard)
  private val applianceScene = Scene.of(SmokeDashboard.appliance)

  test("entityCard (on) looks right") {
    withPage(scene, viewport) { (page, _) =>
      IO.blocking {
        settle(page)
        VisualSnapshot.check("entity-card-on", kitchenCard(page).screenshot())
      }
    }
  }

  test("entityCard (off, inside the default-open tab panel) looks right") {
    withPage(scene, viewport) { (page, _) =>
      IO.blocking {
        settle(page)
        val livingRoomCard = page.locator(
          "article.fh-entity",
          new Page.LocatorOptions().setHasText("Living Room")
        )
        VisualSnapshot.check("entity-card-off", livingRoomCard.screenshot())
      }
    }
  }

  test("button looks right") {
    withPage(scene, viewport) { (page, _) =>
      IO.blocking {
        settle(page)
        // By role: a label is two nested spans (`core/text.pkl`), so a text
        // locator would shoot the words instead of the button.
        VisualSnapshot.check(
          "button",
          page
            .getByRole(
              AriaRole.BUTTON,
              new Page.GetByRoleOptions().setName("Toggle Kitchen")
            )
            .screenshot()
        )
      }
    }
  }

  test("slider looks right") {
    withPage(scene, viewport) { (page, _) =>
      IO.blocking {
        settle(page)
        val sliderCard = page.locator(
          "article.fh-card",
          new Page.LocatorOptions().setHas(page.locator("input[type=range]"))
        )
        VisualSnapshot.check("slider", sliderCard.screenshot())
      }
    }
  }

  test("tabs bar + default panel look right") {
    withPage(scene, viewport) { (page, _) =>
      IO.blocking {
        settle(page)
        // `.fh-tab-panel` is `display:contents`, a boxless wrapper, so the Tabs
        // card's own `.fh-cell` is the innermost real box holding bar and
        // panel; `.last()` picks it over the page root's.
        val tabsCell = page
          .locator(
            ".fh-cell",
            new Page.LocatorOptions().setHas(page.locator(".fh-tabs"))
          )
          .last()
        VisualSnapshot.check("tabs", tabsCell.screenshot())
      }
    }
  }

  test("an open popup looks right") {
    withPage(scene, viewport) { (page, _) =>
      val popup = page.locator(".fh-popup")
      for {
        _ <- IO.blocking(kitchenCard(page).click())
        _ <- IO.blocking(assertThat(popup).isVisible())
        _ <- IO.blocking {
          settle(page)
          VisualSnapshot.check("popup-open", popup.screenshot())
        }
      } yield ()
    }
  }

  test("lock controls look right") {
    withPage(scene, viewport) { (page, _) =>
      IO.blocking {
        settle(page)
        // Every node is a cell (ADR 0008), so a cell holding the button alone
        // matches too and `.last()` would take it; requiring the tile pins the
        // cell to `c.lock.controls`'s card. Two `filter` calls: `has` is one
        // field, so a second `setHas` replaces the first.
        val lockCard = page
          .locator(".fh-cell")
          .filter(
            new Locator.FilterOptions()
              .setHas(page.locator("article.fh-entity"))
          )
          .filter(
            new Locator.FilterOptions().setHas(
              page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Open")
              )
            )
          )
          .last()
        VisualSnapshot.check("lock-controls", lockCard.screenshot())
      }
    }
  }

  test("the progress card looks right mid-cycle") {
    withPage(applianceScene, viewport) { (page, _) =>
      IO.blocking {
        settle(page)
        // The fill's width comes from a client-side expression over two
        // signals, so no wire test can check it. 47 of 120 minutes is ~61%, far
        // enough from both ends that an off-by-one shows.
        VisualSnapshot.check(
          "progress-card",
          page.locator("article.fh-tile-card:has(.fh-bar)").screenshot()
        )
      }
    }
  }

  private val lightSliderScene =
    Scene.of(SmokeDashboard.lightSlider).entity(SmokeDashboard.colourLamp)

  private def lightSliderCell(page: Page) =
    page.locator(".fh-cell:has(> .fh-modes)")

  test("the light slider looks right on brightness") {
    withPage(lightSliderScene, viewport) { (page, _) =>
      IO.blocking {
        // There and back: the colour shot, taken after a round trip, held
        // still across runs and the first-paint one did not.
        page.locator(".fh-modes-bar button[aria-label='Colour']").click()
        assertThat(page.locator("input[type=range][max='360']")).hasCount(1)
        page.locator(".fh-modes-bar button[aria-label='Brightness']").click()
        assertThat(page.locator("input[type=range][max='255']")).hasCount(1)
        settle(page)
        VisualSnapshot.check(
          "light-slider-brightness",
          lightSliderCell(page).screenshot()
        )
      }
    }
  }

  test("the light slider looks right on colour") {
    withPage(lightSliderScene, viewport) { (page, _) =>
      IO.blocking {
        page.locator(".fh-modes-bar button[aria-label='Colour']").click()
        assertThat(page.locator("input[type=range][max='360']")).hasCount(1)
        settle(page)
        VisualSnapshot.check(
          "light-slider-colour",
          lightSliderCell(page).screenshot()
        )
      }
    }
  }

  test("the full dashboard looks right") {
    withPage(scene, viewport) { (page, _) =>
      IO.blocking {
        settle(page)
        VisualSnapshot.check("full-dashboard", page.screenshot())
      }
    }
  }

  private def kitchenCard(page: Page) =
    page.locator(
      "article.fh-entity",
      new Page.LocatorOptions().setHasText("Kitchen")
    )
}
