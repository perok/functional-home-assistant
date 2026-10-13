package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.runtime.TestServer

import java.util.regex.Pattern

/** `c.select` in a browser, under a plain column declaring its variable: the
  * shell's ask, the server's commit and the cell's URL mirror end to end.
  */
class SelectSmokeSuite extends SmokeSuite {

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |
       |access = c.access.public
       |
       |local shade = c.variable("shade")
       |
       |card = (c.column) {
       |  vars = shade.declare("pale")
       |  children {
       |    c.select(shade, List("pale", "deep", "dusk")).style("dropdown")
       |    c.select(shade, List("pale", "deep", "dusk")).style("cycle")
       |  }
       |}
       |""".stripMargin

  private def served = TestServer.servedWorkspace("select", entry, Nil)

  private def summary(page: Page) = page.locator(".fh-select-summary")

  private def visibleCycle(page: Page) =
    page.locator(".fh-select-cycle:visible")

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
        assertThat(summary(page)).hasText("pale")
        assertThat(visibleCycle(page)).hasText("pale")
        summary(page).click()
        assertThat(page.locator(".fh-select-menu")).hasAttribute("open", "")
        choosing(page, "deep")(
          page
            .locator(
              ".fh-select-list a",
              new Page.LocatorOptions().setHasText(Pattern.compile("^deep$"))
            )
            .click()
        )
        assertThat(page.locator(".fh-select-menu"))
          .not()
          .hasAttribute("open", "")
        assertThat(summary(page)).hasText("deep")
        // The other style follows the same variable.
        assertThat(visibleCycle(page)).hasText("deep")
        awaitParam(page, "deep")
      }
    }
  }

  test("a cycle steps round, and the declared value leaves no param") {
    withPageOn(served) { (page, _) =>
      IO.blocking {
        choosing(page, "deep")(visibleCycle(page).click())
        assertThat(visibleCycle(page)).hasText("deep")
        choosing(page, "dusk")(visibleCycle(page).click())
        assertThat(visibleCycle(page)).hasText("dusk")
        awaitParam(page, "dusk")
        choosing(page, "pale")(visibleCycle(page).click())
        assertThat(visibleCycle(page)).hasText("pale")
        awaitParam(page, "")
      }
    }
  }
}
