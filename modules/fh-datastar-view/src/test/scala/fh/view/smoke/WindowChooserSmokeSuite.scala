package fh.view.smoke

import cats.effect.IO
import cats.syntax.all.*
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.runtime.TestServer
import fh.view.testkit.HouseFixture

import java.util.regex.Pattern

/** `c.windowChooser` over a chart, as the Pkl library writes it, against the
  * real history provider and chart isolate. Only the recorder is the fake's.
  */
class WindowChooserSmokeSuite extends SmokeSuite {

  private val sensor = HouseFixture.outsideTemp

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |access = c.access.public
       |
       |card = (c.column) {
       |  children {
       |    (c.windowChooser) {
       |      children { c.historyChart(dump.entities.${sensor.dumpKey}).chosen() }
       |    }
       |  }
       |}
       |""".stripMargin

  private def served =
    TestServer.servedWorkspace("windows", entry, List(sensor))

  private def button(page: Page, w: String) =
    page.locator(
      ".tabs a",
      new Page.LocatorOptions().setHasText(Pattern.compile(s"^$w$$"))
    )

  private def chart(page: Page): IO[String] =
    IO.blocking(page.locator(".fh-chart svg").innerHTML())

  /** On screen first: a bar that lays its buttons out wider than the page hides
    * all but `1h`, and a click on the rest only times out.
    */
  private def press(page: Page, w: String): IO[Unit] =
    IO.blocking {
      assertThat(button(page, w)).isInViewport()
      val _ = page.waitForResponse(
        (r: com.microsoft.playwright.Response) =>
          r.url().contains(s"/window/$w"),
        () => button(page, w).click()
      )
    }

  private def href(page: Page): IO[String] =
    IO.blocking(page.evaluate("() => location.href").toString)

  private val active = Pattern.compile("\\bactive\\b")

  test("each window button redraws the chart and takes the highlight") {
    withPageOn(served) { (page, _) =>
      for {
        _ <- IO.blocking(assertThat(button(page, "24h")).hasClass(active))
        day <- chart(page)
        drawn <- List("1h", "7d", "30d", "24h").traverse { w =>
          press(page, w) *>
            IO.blocking(assertThat(button(page, w)).hasClass(active)) *>
            chart(page)
        }
      } yield assertEquals((day :: drawn.init).distinct.size, 4)
    }
  }

  test("passthrough readings re-run when a window press re-queries them") {
    // The client half of `c.historyReadings`: the JSON arrives in a
    // `data-signals` attribute, and a morph that changes it has to re-run the
    // expressions reading the signal. The fake recorder answers the same
    // values for every window, so the span is the reading that moves.
    val readings = TestServer.servedWorkspace(
      "readings",
      entry.replace("c.historyChart(", "c.historyReadings("),
      List(sensor)
    )
    def span(page: Page): IO[String] =
      IO.blocking(page.locator(".fh-readings-span").innerText())
    withPageOn(readings) { (page, _) =>
      for {
        _ <- IO.blocking(
          assertThat(page.locator(".fh-readings-span")).hasText("24 h")
        )
        _ <- press(page, "1h")
        _ <- IO.blocking(
          assertThat(page.locator(".fh-readings-span")).hasText("1 h")
        )
        low <- IO.blocking(page.locator(".fh-readings dd").nth(1).innerText())
        after <- span(page)
      } yield {
        assert(low.nonEmpty && low != "–", clue = low)
        assertEquals(after, "1 h")
      }
    }
  }

  test("a chosen window is on the URL and survives a reload") {
    withPageOn(served) { (page, _) =>
      for {
        _ <- IO.blocking(assertThat(button(page, "24h")).hasClass(active))
        day <- chart(page)
        _ <- press(page, "1h")
        _ <- IO.blocking(assertThat(button(page, "1h")).hasClass(active))
        url <- href(page)
        _ = assert(url.matches(""".*[?&]v\.[^=&]+\.window=1h.*"""), clue = url)
        _ <- IO.blocking(page.reload())
        _ <- IO.blocking(assertThat(button(page, "1h")).hasClass(active))
        reloaded <- chart(page)
      } yield assertNotEquals(reloaded, day)
    }
  }
}
