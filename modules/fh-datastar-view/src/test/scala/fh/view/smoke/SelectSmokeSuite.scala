package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.{Locator, Page}
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.runtime.TestServer
import fh.view.testkit.HouseFixture

import java.util.regex.Pattern

/** `c.select` in a browser, under a plain column declaring its variable: the
  * shell's ask, the server's commit and the cell's URL mirror end to end.
  */
class SelectSmokeSuite extends SmokeSuite {

  private val sensor = HouseFixture.outsideTemp

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |access = c.access.public
       |
       |local shade = c.variable("shade")
       |
       |card = (c.column) {
       |  vars { shade.defaultingTo("24h") }
       |  children {
       |    c.select(shade).options(List("24h", "7d", "30d")).style("dropdown")
       |    c.select(shade).options(List("24h", "7d", "30d")).style("cycle")
       |    // A reader: the server refuses a choice nothing on the page reads.
       |    c.history.chart(dump.entities.${sensor.dumpKey}).over((p) -> p.history.chosen(shade, p.history.presets))
       |  }
       |}
       |""".stripMargin

  private def served = TestServer.servedWorkspace("select", entry, List(sensor))

  private def summary(page: Page) = page.locator(".fh-select-summary")

  private def visibleCycle(page: Page) =
    page.locator(".fh-select-cycles button:visible")

  private def choosing[A](page: Page, value: String)(act: => A): Unit = {
    val _ = page.waitForResponse(
      (r: com.microsoft.playwright.Response) =>
        r.url().contains(s"/shade/$value"),
      () => { val _ = act }
    )
  }

  /** The highlight follows the ask at once, the URL only the commit, so this
    * waits for the param rather than reading it.
    */
  private def awaitParam(page: Page, value: String): Unit = {
    val _ = page.waitForFunction(
      "v => [...new URLSearchParams(location.search)].filter(([k]) => k.endsWith('.shade')).map(([, x]) => x).join() === v",
      value
    )
  }

  test("a dropdown opens, a choice closes it, and the commit reaches the URL") {
    withPageOn(served) { (page, _) =>
      IO.blocking {
        assertThat(summary(page)).hasText("24h")
        assertThat(visibleCycle(page)).hasText("24h")
        summary(page).click()
        assertThat(page.locator(".fh-select-menu")).hasAttribute("open", "")
        choosing(page, "7d")(
          page
            .locator(
              ".fh-select-list a",
              new Page.LocatorOptions().setHasText(Pattern.compile("^7d$"))
            )
            // Under the response wait's 30s, so a click that cannot land fails
            // with Playwright's reason (what intercepts it), not the test's timeout.
            .click(new Locator.ClickOptions().setTimeout(10000))
        )
        assertThat(page.locator(".fh-select-menu"))
          .not()
          .hasAttribute("open", "")
        assertThat(summary(page)).hasText("7d")
        // The other style follows the same variable.
        assertThat(visibleCycle(page)).hasText("7d")
        awaitParam(page, "7d")
      }
    }
  }

  test("a cycle steps round, and the declared value leaves no param") {
    withPageOn(served) { (page, _) =>
      IO.blocking {
        choosing(page, "7d")(visibleCycle(page).click())
        assertThat(visibleCycle(page)).hasText("7d")
        choosing(page, "30d")(visibleCycle(page).click())
        assertThat(visibleCycle(page)).hasText("30d")
        awaitParam(page, "30d")
        choosing(page, "24h")(visibleCycle(page).click())
        assertThat(visibleCycle(page)).hasText("24h")
        awaitParam(page, "")
      }
    }
  }
}
