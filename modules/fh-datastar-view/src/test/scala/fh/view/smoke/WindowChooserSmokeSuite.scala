package fh.view.smoke

import api.homeassistant.ws.domain.HistoryPoint
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.runtime.TestServer
import fh.view.testkit.{FakeConfig, HouseFixture}

import java.util.regex.Pattern
import scala.concurrent.duration.*

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
        // The highlight moves on the press (pending), the chart when its patch
        // lands, so each read waits for the chart to change: read at once, a
        // fast runner still saw the previous window's.
        drawn <- List("1h", "7d", "30d", "24h").foldLeft(IO.pure(List(day))) {
          (seen, w) =>
            seen.flatMap { charts =>
              press(page, w) *>
                IO.blocking(assertThat(button(page, w)).hasClass(active)) *>
                eventually(chart(page))(_ != charts.head).map(_ :: charts)
            }
        }
      } yield assertEquals(drawn.reverse.init.distinct.size, 4)
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

  /** A recorder that answers at once until `slow` is set: the page's own paint
    * resolves the chart, so only the waits a test provokes are long.
    */
  private def slowServed(slug: String, source: String, slow: Ref[IO, Boolean]) =
    TestServer.servedWorkspace(
      slug,
      source,
      List(sensor),
      FakeConfig(recorder =
        Some((from, _, _) =>
          slow.get.flatMap(IO.sleep(1500.millis).whenA(_)) *>
            IO.pure(
              List(
                HistoryPoint("12.0", from),
                HistoryPoint("13.0", from.plusSeconds(60))
              )
            )
        )
      )
    )

  test("a tab whose panel charts slowly spins until the panel lands") {
    val tabbed = entry.replace(
      s"""(c.windowChooser) {
         |      children { c.historyChart(dump.entities.${sensor.dumpKey}).chosen() }
         |    }""".stripMargin,
      s"""(c.tabs) {
         |      tabs {
         |        ["Now"] { c.title("now") }
         |        ["History"] { c.historyChart(dump.entities.${sensor.dumpKey}) }
         |      }
         |    }""".stripMargin
    )
    val spinning = Pattern.compile("\\bfh-busy-after\\b")
    Ref[IO].of(false).flatMap { slow =>
      withPageOn(slowServed("tabs-slow", tabbed, slow)) { (page, _) =>
        val history = button(page, "History")
        for {
          _ <- IO.blocking(assertThat(history).not().hasClass(spinning))
          _ <- slow.set(true)
          _ <- IO.blocking(history.click())
          _ <- IO.blocking(assertThat(history).hasClass(spinning))
          _ <- IO.blocking(
            assertThat(button(page, "Now")).not().hasClass(spinning)
          )
          _ <- IO.blocking(
            assertThat(page.locator(".fh-chart svg")).isVisible()
          )
          _ <- IO.blocking(assertThat(history).not().hasClass(spinning))
          _ <- IO.blocking(assertThat(history).hasClass(active))
        } yield ()
      }
    }
  }

  test("a window whose chart redraws slowly spins on the button pressed") {
    // Each window is a node with its own busy signal, so only the pressed one
    // rings; a shared signal would ring the whole bar (ADR 0019).
    val spinning = Pattern.compile("\\bfh-busy-after\\b")
    Ref[IO].of(false).flatMap { slow =>
      withPageOn(slowServed("windows-slow", entry, slow)) { (page, _) =>
        val week = button(page, "7d")
        for {
          _ <- IO.blocking(assertThat(week).not().hasClass(spinning))
          _ <- slow.set(true)
          _ <- IO.blocking(week.click())
          _ <- IO.blocking(assertThat(week).hasClass(spinning))
          _ <- IO.blocking(
            assertThat(button(page, "24h")).not().hasClass(spinning)
          )
          _ <- IO.blocking(assertThat(week).not().hasClass(spinning))
          _ <- IO.blocking(assertThat(week).hasClass(active))
        } yield ()
      }
    }
  }

  test("a more-info whose chart is slow spins on the card that opened it") {
    val moreInfo = entry.replace(
      s"""(c.windowChooser) {
         |      children { c.historyChart(dump.entities.${sensor.dumpKey}).chosen() }
         |    }""".stripMargin,
      s"c.entityCard(dump.entities.${sensor.dumpKey})"
    )
    Ref[IO].of(false).flatMap { slow =>
      withPageOn(slowServed("moreinfo-slow", moreInfo, slow)) { (page, _) =>
        // The tile's row: it, not the `<article>`, owns the tap and its look,
        // so a feature under it can have its own.
        val card = page.locator("article.entity .fh-tile").first()
        for {
          _ <- slow.set(true)
          _ <- IO.blocking(card.click())
          _ <- IO.blocking(
            assertThat(card).hasClass(Pattern.compile("\\bfh-loading\\b"))
          )
          _ <- IO.blocking(
            assertThat(card.locator(".fh-icon-shape"))
              .hasClass(Pattern.compile("\\bloading-indicator\\b"))
          )
          _ <- IO.blocking(assertThat(page.locator("dialog[open]")).isVisible())
          _ <- IO.blocking(
            assertThat(card).not().hasClass(Pattern.compile("\\bfh-loading\\b"))
          )
        } yield ()
      }
    }
  }

  test("a window chosen in a popup leaves the URL when the popup closes") {
    // More-info on a numeric sensor composes a chooser over its chart.
    val moreInfo = TestServer.servedWorkspace(
      "moreinfo-url",
      entry.replace(
        s"""(c.windowChooser) {
           |      children { c.historyChart(dump.entities.${sensor.dumpKey}).chosen() }
           |    }""".stripMargin,
        s"c.entityCard(dump.entities.${sensor.dumpKey})"
      ),
      List(sensor)
    )
    val param = """[?&]v\.[^=&]+\.window="""
    withPageOn(moreInfo) { (page, _) =>
      val dialog = page.locator("dialog[open]")
      for {
        _ <- IO.blocking(page.locator("article.entity").first().click())
        _ <- IO.blocking(assertThat(dialog).isVisible())
        _ <- press(page, "7d")
        _ <- IO.blocking(assertThat(button(page, "7d")).hasClass(active))
        chosen <- href(page)
        _ = assert(chosen.matches(s".*${param}7d.*"), clue = chosen)
        _ <- IO.blocking(dialog.getByText("Close").click())
        _ <- IO.blocking(assertThat(dialog).hasCount(0))
        _ <- eventually(href(page))(!_.matches(s".*$param.*"))
      } yield ()
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
