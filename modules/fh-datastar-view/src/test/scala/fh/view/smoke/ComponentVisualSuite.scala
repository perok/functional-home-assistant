package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{HouseFixture, SmokeDashboard, VisualSnapshot}

/** "Verify looks": component-level screenshots of [[SmokeDashboard]] against
  * checked-in [[VisualSnapshot]] baselines — the piece a wire-format test
  * fundamentally can't cover (correct HTML + correct CSS selectors says nothing
  * about whether the CSS actually paints right). Deliberately at COMPONENT
  * granularity, not one full-page screenshot per test: a failure names the
  * exact card that regressed, and one card's incidental reflow doesn't fail
  * every other card's snapshot.
  */
class ComponentVisualSuite extends SmokeSuite {

  private val viewport = Some(900 -> 700)

  test("entityCard (on) looks right") {
    withPage(SmokeDashboard.dashboard, HouseFixture.all, viewport) {
      (page, _) =>
        IO.blocking {
          settle(page)
          VisualSnapshot.check("entity-card-on", kitchenCard(page).screenshot())
        }
    }
  }

  test("entityCard (off, inside the default-open tab panel) looks right") {
    withPage(SmokeDashboard.dashboard, HouseFixture.all, viewport) {
      (page, _) =>
        IO.blocking {
          settle(page)
          val livingRoomCard = page.locator(
            "article.entity",
            new Page.LocatorOptions().setHasText("Living Room")
          )
          VisualSnapshot.check("entity-card-off", livingRoomCard.screenshot())
        }
    }
  }

  test("button looks right") {
    withPage(SmokeDashboard.dashboard, HouseFixture.all, viewport) {
      (page, _) =>
        IO.blocking {
          settle(page)
          VisualSnapshot.check(
            "button",
            page.getByText("Toggle Kitchen").screenshot()
          )
        }
    }
  }

  test("slider looks right") {
    withPage(SmokeDashboard.dashboard, HouseFixture.all, viewport) {
      (page, _) =>
        IO.blocking {
          settle(page)
          val sliderCard = page.locator(
            "article.card",
            new Page.LocatorOptions().setHas(page.locator("input[type=range]"))
          )
          VisualSnapshot.check("slider", sliderCard.screenshot())
        }
    }
  }

  test("tabs bar + default panel look right") {
    withPage(SmokeDashboard.dashboard, HouseFixture.all, viewport) {
      (page, _) =>
        IO.blocking {
          settle(page)
          // `.tab-panel` is `display:contents` (theme-beer.pkl) — a boxless
          // wrapper, so it (and any ancestor sharing its box) can't be
          // screenshotted directly. The Tabs card's OWN `.fh-cell` (every node
          // gets one, ADR 0008) is the innermost real box containing both the
          // bar and the baked default panel — `.last()` picks it over the
          // page-root `.fh-cell`, which also (transitively) "has" `.tabs`.
          val tabsCell = page
            .locator(
              ".fh-cell",
              new Page.LocatorOptions().setHas(page.locator(".tabs"))
            )
            .last()
          VisualSnapshot.check("tabs", tabsCell.screenshot())
        }
    }
  }

  test("an open popup looks right") {
    withPage(SmokeDashboard.dashboard, HouseFixture.all, viewport) {
      (page, _) =>
        val popup = page.locator(".popup")
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

  test("the full dashboard looks right") {
    withPage(SmokeDashboard.dashboard, HouseFixture.all, viewport) {
      (page, _) =>
        IO.blocking {
          settle(page)
          VisualSnapshot.check("full-dashboard", page.screenshot())
        }
    }
  }

  private def kitchenCard(page: Page) =
    page.locator(
      "article.entity",
      new Page.LocatorOptions().setHasText("Kitchen")
    )
}
