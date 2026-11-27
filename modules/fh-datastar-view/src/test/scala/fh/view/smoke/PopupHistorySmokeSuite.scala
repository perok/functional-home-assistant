package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.runtime.TestServer
import fh.view.testkit.HouseFixture

/** Popups and the browser's history (ADR 0005): the server shows one popup at a
  * time, and each one opened is a history entry, so Back steps to the popup it
  * was opened from and every close, a tap on the backdrop included, is going
  * back. The tab is a second variable in history beside `popups`.
  */
class PopupHistorySmokeSuite extends SmokeSuite {

  private val entry =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/core/group.pkl" as groupMod
      |import "@fh-dashboard/core/node.pkl" as nodeMod
      |
      |access = c.access.public
      |
      |local tab = c.variable("tab")
      |
      |local second = new c.Column {
      |  children {
      |    c.title("Second popup")
      |    c.button("Close", c.view.closePopup())
      |  }
      |}
      |
      |local first = new c.Column {
      |  children {
      |    c.title("First popup")
      |    c.button("Open second", c.view.popup("second", second))
      |  }
      |}
      |
      |card = (c.column) {
      |  vars { tab.defaultingTo("a").inHistory() }
      |  children {
      |    c.title("Popup history")
      |    c.button("Open first", c.view.popup("first", first))
      |    c.select(tab).options(List("a", "b")).style("cycle")
      |    groupMod.group("div", "tabbed", nodeMod.match(tab.value, new Mapping {
      |      ["a"] = c.title("shown a")
      |      ["b"] = c.title("shown b")
      |    }))
      |  }
      |}
      |""".stripMargin

  private def served =
    TestServer.servedWorkspace(
      "popup-history",
      entry,
      List(HouseFixture.kitchenLight)
    )

  private def dialog(page: Page) = page.locator("dialog.fh-popup")

  private def click(page: Page, text: String): IO[Unit] =
    IO.blocking(page.getByText(text, exactText).click())

  private val exactText = new Page.GetByTextOptions().setExact(true)

  private def showing(page: Page, title: String): IO[Unit] =
    IO.blocking(assertThat(dialog(page)).containsText(title))

  private def closed(page: Page): IO[Unit] =
    IO.blocking(assertThat(dialog(page)).hasCount(0))

  private def href(page: Page): IO[String] =
    IO.blocking(page.evaluate("() => location.href").toString)

  private val popupParam = """.*[?&]v\.popups=.*"""

  private def tabShows(page: Page, value: String): IO[Unit] =
    IO.blocking(
      assertThat(page.getByText(s"shown $value", exactText)).isVisible()
    )

  /** Waits for the choice's post, so the assertion after it is not answered by
    * a page that has not acted yet.
    */
  private def nextTab(page: Page): IO[Unit] =
    IO.blocking {
      val _ = page.waitForResponse(
        "**/sse/call/**",
        () => page.locator(".fh-select-cycles button:visible").click()
      )
    }

  /** Whether a tap at a point would land in the dashboard behind the popup. */
  private def reachesDashboard(page: Page, x: Double, y: Double): IO[Boolean] =
    IO.blocking(
      page
        .evaluate(
          s"() => !!document.elementFromPoint($x, $y)?.closest('main')"
        )
        .toString
        .toBoolean
    )

  test("a tap beside the popup steps back, and the page behind never gets it") {
    withPageOn(served) { (page, _) =>
      for {
        start <- href(page)
        opener <- IO.blocking(
          page.getByText("Open first", exactText).boundingBox()
        )
        (ox, oy) = (opener.x + opener.width / 2, opener.y + opener.height / 2)
        control <- reachesDashboard(page, ox, oy)
        _ = assert(
          control,
          "control: with no popup, the opener is the dashboard"
        )
        _ <- click(page, "Open first")
        _ <- showing(page, "First popup")
        // Asked rather than tapped, since a tap here may land in the dialog:
        // whatever is on top, it is not the dashboard's own button.
        reached <- reachesDashboard(page, ox, oy)
        _ = assert(!reached, "a tap on the opener reaches the dashboard")
        // A corner of the viewport is outside the dialog whatever its size.
        corner <- IO.blocking(page.evaluate("() => innerHeight - 4").toString)
        _ <- IO.blocking(page.mouse().click(4, corner.toDouble))
        _ <- closed(page)
        _ <- eventually(href(page))(_ == start)
      } yield ()
    }
  }

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

  test("Back and Forward step through a tab and a popup, each its own entry") {
    withPageOn(served) { (page, _) =>
      for {
        start <- href(page)
        _ <- nextTab(page)
        _ <- tabShows(page, "b")
        _ <- eventually(href(page))(_.contains("v.c.tab=b"))
        _ <- click(page, "Open first")
        _ <- showing(page, "First popup")
        _ <- IO.blocking(page.goBack())
        _ <- closed(page)
        _ <- tabShows(page, "b")
        _ <- IO.blocking(page.goBack())
        _ <- tabShows(page, "a")
        _ <- eventually(href(page))(_ == start)
        _ <- IO.blocking(page.goForward())
        _ <- tabShows(page, "b")
        _ <- closed(page)
        _ <- IO.blocking(page.goForward())
        _ <- showing(page, "First popup")
        _ <- tabShows(page, "b")
      } yield ()
    }
  }

  test("a link naming the tab and a popup is one entry above the dashboard") {
    withPageOn(served) { (page, _) =>
      for {
        start <- href(page)
        sep = if (start.contains("?")) "&" else "?"
        _ <- IO.blocking(
          page.navigate(s"$start${sep}v.c.tab=b&v.popups=first")
        )
        _ <- showing(page, "First popup")
        _ <- tabShows(page, "b")
        _ <- IO.blocking(page.locator(".fh-popup-close").click())
        _ <- closed(page)
        _ <- tabShows(page, "a")
        _ <- eventually(href(page))(_ == start)
        _ <- IO.blocking(page.goForward())
        _ <- showing(page, "First popup")
        _ <- tabShows(page, "b")
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
        _ <- IO.blocking(page.locator(".fh-popup-close").click())
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
        _ <- IO.blocking(page.navigate(s"$start${sep}v.popups=first"))
        _ <- showing(page, "First popup")
        _ <- IO.blocking(page.locator(".fh-popup-close").click())
        _ <- closed(page)
        after <- eventually(href(page))(!_.matches(popupParam))
        _ <- IO.blocking(
          assertThat(page.getByText("Popup history")).isVisible()
        )
      } yield assertEquals(after, start)
    }
  }
}
