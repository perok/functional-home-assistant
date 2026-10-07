package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.runtime.TestServer
import fh.view.testkit.HouseFixture

/** Popups and the browser's history (ADR 0005): the server shows one popup at
  * a time, and each one opened is a history entry, so Back steps to the popup
  * it was opened from and every close is going back.
  */
class PopupHistorySmokeSuite extends SmokeSuite {

  private val entry =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |
      |access = c.access.public
      |
      |surfaces {
      |  ["first"] {
      |    body {
      |      c.title("First popup")
      |      c.button("Open second", c.tap.openPopup("second"))
      |    }
      |  }
      |  ["second"] {
      |    body {
      |      c.title("Second popup")
      |      c.button("Close", c.tap.closePopup())
      |    }
      |  }
      |}
      |
      |card = (c.column) {
      |  children {
      |    c.title("Popup history")
      |    c.button("Open first", c.tap.openPopup("first"))
      |  }
      |}
      |""".stripMargin

  private def served =
    TestServer.servedWorkspace(
      "popup-history",
      entry,
      List(HouseFixture.kitchenLight)
    )

  private def dialog(page: Page) = page.locator("dialog.popup")

  private def click(page: Page, text: String): IO[Unit] =
    IO.blocking(page.getByText(text, exactText).click())

  private val exactText = new Page.GetByTextOptions().setExact(true)

  private def showing(page: Page, title: String): IO[Unit] =
    IO.blocking(assertThat(dialog(page)).containsText(title))

  private def closed(page: Page): IO[Unit] =
    IO.blocking(assertThat(dialog(page)).hasCount(0))

  private def href(page: Page): IO[String] =
    IO.blocking(page.evaluate("() => location.href").toString)

  private val popupParam = """.*[?&]ui\.popups=.*"""

  test("Back steps through the popups opened, and Forward reopens") {
    withPageOn(served) { (page, _) =>
      for {
        start <- href(page)
        _ <- click(page, "Open first")
        _ <- showing(page, "First popup")
        _ <- click(page, "Open second")
        _ <- showing(page, "Second popup")
        _ <- IO.blocking(page.goBack())
        _ <- showing(page, "First popup")
        _ <- IO.blocking(page.goBack())
        _ <- closed(page)
        _ <- eventually(href(page))(_ == start)
        _ <- IO.blocking(page.goForward())
        _ <- showing(page, "First popup")
      } yield ()
    }
  }

  test("the close button, a close tap and Escape each step back") {
    withPageOn(served) { (page, _) =>
      for {
        start <- href(page)
        _ <- click(page, "Open first")
        _ <- showing(page, "First popup")
        _ <- click(page, "Open second")
        _ <- showing(page, "Second popup")
        _ <- click(page, "Close")
        _ <- showing(page, "First popup")
        _ <- click(page, "Open second")
        _ <- showing(page, "Second popup")
        _ <- IO.blocking(page.locator(".popup-close").click())
        _ <- showing(page, "First popup")
        _ <- IO.blocking(page.keyboard().press("Escape"))
        _ <- closed(page)
        _ <- eventually(href(page))(_ == start)
      } yield ()
    }
  }

  test("a popup the page loaded with closes onto the dashboard") {
    // No entry beneath it was ever pushed: a close that only went back would
    // leave the dashboard for whatever the tab showed before.
    withPageOn(served) { (page, _) =>
      for {
        start <- href(page)
        sep = if (start.contains("?")) "&" else "?"
        _ <- IO.blocking(page.navigate(s"$start${sep}ui.popups=first"))
        _ <- showing(page, "First popup")
        _ <- IO.blocking(page.locator(".popup-close").click())
        _ <- closed(page)
        after <- eventually(href(page))(!_.matches(popupParam))
        _ <- IO.blocking(assertThat(page.getByText("Popup history")).isVisible())
      } yield assertEquals(after, start)
    }
  }
}
