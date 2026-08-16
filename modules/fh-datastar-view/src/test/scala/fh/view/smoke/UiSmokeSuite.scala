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

  test("tabs: a selection on the URL survives the SSE connect") {
    withPage(scene) { (page, ts) =>
      val panel = page.locator(".tab-panel")
      for {
        // The group's id is backend-derived, so read it off the first paint
        // rather than hardcoding a path id that layout edits would shift.
        gid <- IO
          .blocking(panel.getAttribute("id"))
          .map(_.stripSuffix("_panel"))
        // …and drop the mirror the effect has already written on this load.
        deepLink = s"${page.url().takeWhile(_ != '?')}?ui.$gid=1"
        _ <- IO.blocking(page.navigate(deepLink))
        // The failure this guards is a LATE one: the first paint is correct,
        // and only the connect's repaint puts the default tab back — dragging
        // the URL mirror down with it. So drive an unrelated change through the
        // stream first: it is ordered AFTER everything the connect emitted, so
        // seeing it means the repaint (if any) has already been applied.
        _ <- ts.awaitLive()
        _ <- ts.fake.emit(
          HouseFixture.outsideTemp.entityId,
          "13.1",
          HouseFixture.outsideTemp.attributes
        )
        _ <- IO.blocking(
          assertThat(page.locator("article.entity").first())
            .containsText("13.1")
        )
        _ <- IO.blocking(assertThat(panel).containsText("Hallway"))
      } yield assertEquals(page.url(), deepLink)
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

  test("popup: it survives a refresh, and is there in the first paint") {
    withPage(scene) { (page, ts) =>
      val kitchenCard = page
        .locator(
          "article.entity",
          new Page.LocatorOptions().setHasText("Kitchen")
        )
      val popup = page.locator(".popup")
      for {
        _ <- IO.blocking(kitchenCard.click())
        _ <- IO.blocking(assertThat(popup).containsText("Kitchen Detail"))
        // The tap set `$ui_popups` and the mirror wrote ?ui.popups=<id>, so the
        // reload has something to restore — the same path a tab selection
        // takes, which is the point of the popup no longer having its own.
        _ <- IO.blocking(page.reload())
        _ <- IO.blocking(assertThat(popup).containsText("Kitchen Detail"))
        // …and it is in the served HTML, not patched in afterwards: baked into
        // the theme chrome's popup hole, so there is no dashboard-first,
        // dialog-a-moment-later flash.
        html <- ts.page("?ui.popups=detail")
      } yield assert(html.contains("Kitchen Detail"), clue = html)
    }
  }

  test("scroll: the offset comes back with the dashboard") {
    // The phone case: a viewport the dashboard overflows, scrolled down, left,
    // and returned to. Crossing dashboards is a document load (ADR 0002) and a
    // page holding a streaming fetch is not bfcache-eligible, so nothing but
    // the shell's own `fhScroll` puts the offset back — which is why the
    // journey here is a real second navigation and not a `reload()`.
    withPage(scene, viewport = Some((390, 360))) { (page, _) =>
      val dashboard = page.url()
      val offset = IO.blocking(page.evaluate("scrollY").toString.toDouble)
      for {
        // Settle the theme's web fonts (the test-pinned Inter + the vendored
        // MDI glyphs) before taking the baseline: a font swap reflows line
        // heights and the browser compensates the anchored scrollY by a couple
        // of px, so an offset read mid-swap disagrees with the one `pagehide`
        // saves a beat later — and `fhScroll` faithfully restores THAT one.
        // The forward load re-uses this context's font cache; the wait after
        // it is insurance that `after` is also read font-settled.
        _ <- IO.blocking(page.evaluate("document.fonts.ready"))
        room <- IO.blocking(
          page
            .evaluate("document.documentElement.scrollHeight - innerHeight")
            .toString
            .toDouble
        )
        _ = assert(room >= 200d, clue = s"nothing to scroll: $room")
        _ <- IO.blocking(page.evaluate("scrollTo(0, 200)"))
        before <- eventually(offset)(_ == 200d)
        // Away to somewhere else on this origin — a real unload, which is what
        // fires `pagehide` — and then FORWARD to the dashboard again, the way a
        // nav link goes. A new history entry, so no browser restore can be
        // mistaken for ours.
        origin <- IO.blocking(page.evaluate("location.origin").toString)
        _ <- IO.blocking(page.navigate(s"$origin/not-a-dashboard"))
        _ <- IO.blocking(page.navigate(dashboard))
        _ <- IO.blocking(page.evaluate("document.fonts.ready"))
        after <- eventually(offset)(_ > 0d)
      } yield assert(
        // Not an exact compare: on a live page the restored offset is whatever
        // the post-restore settle leaves — a web-font reflow or the SSE
        // connect's opening repaint growing content above the viewport, and
        // the browser's scroll anchoring pays that in a couple of px (CI shows
        // a deterministic 3px drift; the bound leaves it a little margin).
        // What the test guards is that `fhScroll` brings the offset back — a
        // broken restore lands at the top (0) or pages off, not 3px off.
        math.abs(after - before) <= 4d,
        clue = s"offset drifted: before=$before after=$after"
      )
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
