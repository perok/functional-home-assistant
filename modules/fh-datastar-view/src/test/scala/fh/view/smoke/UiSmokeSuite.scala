package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{HouseFixture, Scene, ServiceCall, SmokeDashboard}
import io.circe.{Decoder, Json}
import io.circe.parser.decode

/** Signal-driven UI with no server round-trip — tabs, popup, slider — the
  * Datastar attribute wiring (`data-signals`/`data-bind`/`data-class`) that has
  * no wire-level observable at all; only a real DOM proves it.
  */
class UiSmokeSuite extends SmokeSuite {

  private val scene = Scene.of(SmokeDashboard.dashboard)

  /** The page's LIVE `location.href`, not `page.url()`.
    *
    * `page.url()` is a value cached in the Playwright client and refreshed from
    * navigation events. `history.replaceState` — which is the entire mechanism
    * of the URL mirror (`fhUrl`, ADR 0005) — updates it only via
    * `navigatedWithinDocument`, and under load that lags: measured with the
    * browser CPU throttled, `page.url()` still read the bare URL while the page
    * had already replaced it twice with `?ui.c_5=0`. Every URL assertion here
    * is about the mirror, so every one of them has to ask the PAGE.
    */
  private def href(page: Page): IO[String] =
    IO.blocking(page.evaluate("() => location.href").toString)

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
        live <- href(page)
      } yield assertEquals(live, deepLink)
    }
  }

  private val active = java.util.regex.Pattern.compile("active")

  test("tabs: a tap the server REFUSES commits nothing") {
    // What pending signals are for (ADR 0025). The tap used to set `ui_<gid>`
    // itself, so a POST that failed still moved the highlight AND the URL — a
    // deep link to a panel this page never showed. Now the press only says what
    // it ASKED for, and a refusal ends the ask.
    //
    // The failure is a 404 rather than a dropped connection, which exercises
    // the CLIENT half of the refusal path: this server answers its own
    // refusals with 200 carrying signals (ADR 0024), so what is left for
    // `pendingFail` is the non-200 nobody here produces — a proxy, an auth
    // redirect, a route that is gone. It is the case Datastar reports as
    // `error`, dispatched from `onopen`, so a response is what makes it fire.
    withPage(scene) { (page, ts) =>
      val panel = page.locator(".tab-panel")
      val climateTab =
        page.locator(".tabs a", new Page.LocatorOptions().setHasText("Climate"))
      for {
        _ <- ts.awaitLive()
        // The URL mirror is a data-effect: it applies the SEEDED selection
        // shortly after connect, independent of any tap. Read `before` only
        // once that first paint has landed, or this assertion races the
        // page's own initialization rather than the refused commit.
        before <- eventually(href(page))(_.contains("ui."))
        _ <- IO.blocking(
          page.route(
            "**/sse/surface/**",
            route =>
              route.fulfill(
                new com.microsoft.playwright.Route.FulfillOptions()
                  .setStatus(404)
                  .setBody("no such surface")
              )
          )
        )
        // Click and wait for the REFUSAL ITSELF, rather than clicking and
        // asserting. Everything below is a statement about what the page does
        // once the 404 has landed, and a retrying assertion supplies no such
        // starting point: `containsText("Living Room")` passes the instant it
        // is called — before the POST has even been sent — so without this the
        // three assertions that follow are satisfied by a page that has not yet
        // done anything at all.
        _ <- IO.blocking(
          page.waitForResponse(
            "**/sse/surface/**",
            () => climateTab.click()
          )
        )
        // The panel cannot have moved — nothing served the swap.
        _ <- IO.blocking(assertThat(panel).containsText("Living Room"))
        // …and the URL still names the tab that is actually on screen. This is
        // the assertion the old design failed.
        stillBefore <- href(page)
        _ <- IO(assertEquals(stillBefore, before))
        // The press is not left asserting a tab it never got.
        _ <- IO.blocking(assertThat(climateTab).not().hasClass(active))
        // …and the refusal SAYS so. "A tap that does nothing and says nothing
        // is the failure this whole route was fixed for" — but nothing
        // asserted it, and the shell's listener has been dead before: `closest`
        // throws a
        // SyntaxError on an unescaped `data-on:click`, which killed the toast
        // silently. A reverted highlight alone is indistinguishable from a
        // press that never registered.
        _ <- IO.blocking(
          assertThat(page.locator(".fh-toast")).containsText("404")
        )
        // Control: unblocked, the same tap does everything.
        _ <- IO.blocking(page.unroute("**/sse/surface/**"))
        _ <- IO.blocking(climateTab.click())
        _ <- IO.blocking(assertThat(panel).containsText("Hallway"))
        // Not a bare read: the panel's text and the URL are written by two
        // different things — the morph, then the panel's `data-effect` calling
        // `fhUrl` — and nothing orders them.
        after <- eventually(href(page))(_ != before)
      } yield assert(after != before, clue = after)
    }
  }

  test("tabs: a tap with nothing left to answer it ends when the stream does") {
    // The other way an ask ends, and the reason it is not a timeout: the commit
    // rides the SSE stream, so a stream that is DOWN is the exact statement
    // that no commit is coming. Here the POST is aborted outright — no status,
    // so no `error` event — which is only reachable at all because the
    // transport failed. The banner's `_sse` is what says so.
    //
    // The stream is cut SERVER-SIDE (`forgetConnections` reaps the session,
    // which ends the response) as well as blocked client-side, because a
    // `page.route` only ever meets a NEW request: the stream this page already
    // has open would survive it, and the banner would then be asserting the
    // health of a connection that is fine. Reaping makes the transport really
    // fail, and the blocked reconnect is what keeps it failed.
    withPage(scene) { (page, ts) =>
      val panel = page.locator(".tab-panel")
      val climateTab =
        page.locator(".tabs a", new Page.LocatorOptions().setHasText("Climate"))
      for {
        _ <- ts.awaitLive()
        _ <- IO.blocking(page.route("**/sse/**", route => route.abort()))
        // Wait for the tap's POST to be ISSUED, not just for the click to
        // return. An aborted request has no response to wait for, and a
        // listener would never be seen here — Playwright's Java client
        // dispatches event callbacks only while this thread is inside one of
        // its calls, so polling a buffer from a plain `IO` waits forever. This
        // is the one synchronization point the abort path offers, and it is
        // enough: the abort is immediate, so past this line the tap has been
        // sent and has failed.
        _ <- IO.blocking(
          page.waitForRequest("**/sse/surface/**", () => climateTab.click())
        )
        // It highlights while it is still an open question…
        _ <- IO.blocking(assertThat(climateTab).hasClass(active))
        _ <- ts.forgetConnections
        _ <- ts.awaitNoConnections
        // …and stops when the connection that would have answered is gone.
        _ <- IO.blocking(
          assertThat(page.locator(".fh-offline-sse")).isVisible()
        )
        _ <- IO.blocking(assertThat(climateTab).not().hasClass(active))
        _ <- IO.blocking(assertThat(panel).containsText("Living Room"))
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

  test("popup: a tap lands on a connection the server has forgotten") {
    // ADR 0009's known gap, closed: no smoke test could reach a RECONNECT, and
    // the bug ADR 0024 fixes lives only there — an idle page whose session was
    // reaped taps into a `conn` the server does not have. It used to answer 204
    // and do nothing, so the popup arrived on the SECOND tap or after a reload.
    //
    // The reconnect is BLOCKED across the tap on purpose. Without that this
    // races: Datastar may reconnect first, the tap finds a live session, and
    // the test passes while exercising nothing. With it, the dialog can only
    // appear if the tap's patch was queued for a session that did not exist
    // when the tap was made, and drained by the stream that came back after.
    withPage(scene) { (page, ts) =>
      val kitchenCard = page
        .locator(
          "article.entity",
          new Page.LocatorOptions().setHasText("Kitchen")
        )
      val popup = page.locator(".popup")
      for {
        _ <- ts.awaitLive()
        _ <- IO.blocking(
          page.route(
            "**/sse/dashboard/**",
            route => route.abort()
          )
        )
        forgotten <- ts.forgetConnections
        _ <- ts.awaitNoConnections
        _ <- IO.blocking(kitchenCard.click())
        // Nothing can have arrived yet: there is no stream to carry it.
        _ <- IO.blocking(assertThat(popup).hasCount(0))
        _ <- IO.blocking(page.unroute("**/sse/dashboard/**"))
        _ <- IO.blocking(assertThat(popup).containsText("Kitchen Detail"))
      } yield assertEquals(forgotten, 1)
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

  /** The track fill DURING a gesture, not after it. `--_end` is the distance
    * from the right edge, so a drag to the right LOWERS it — read off the
    * computed style rather than the inline attribute, since both `beer.min.js`
    * (`style.cssText = …`) and Datastar's style plugin write it and only the
    * resolved value says who won.
    */
  private def fillEnd(page: Page): IO[String] = IO.blocking(
    page
      .evaluate(
        "getComputedStyle(document.querySelector('.slider')).getPropertyValue('--_end')"
      )
      .toString
      .trim
  )

  test("slider: the fill follows the thumb mid-drag, not only on release") {
    withPage(scene) { (page, _) =>
      val slider = page.locator("input[type=range]")
      for {
        box <- IO.blocking(slider.boundingBox())
        mid = box.y + box.height / 2
        before <- fillEnd(page)
        seeded <- IO.blocking(slider.inputValue())
        _ <- IO.blocking(page.mouse().move(box.x + box.width * 0.1, mid))
        _ <- IO.blocking(page.mouse().down())
        // Held DOWN across the assertion: a range input fires `input` on every
        // move but `change` only on release, so this is exactly the window the
        // fill used to sit still in.
        _ <- IO.blocking(page.mouse().move(box.x + box.width * 0.9, mid))
        // Prove the gesture landed before blaming the fill for not following
        // it: a failure here is a broken drag, not a broken binding.
        _ <- IO.blocking(assertThat(slider).not().hasValue(seeded))
        during <- eventually(fillEnd(page))(_ != before)
        _ <- IO.blocking(page.mouse().up())
      } yield assertNotEquals(during, before)
    }
  }

  test("slider: a percent readout moves with the drag too") {
    withPage(Scene.of(SmokeDashboard.percentSlider)) { (page, _) =>
      val slider = page.locator("input[type=range]")
      val readout = page.locator(".state")
      for {
        box <- IO.blocking(slider.boundingBox())
        mid = box.y + box.height / 2
        before <- IO.blocking(readout.textContent())
        _ <- IO.blocking(page.mouse().move(box.x + box.width * 0.1, mid))
        _ <- IO.blocking(page.mouse().down())
        _ <- IO.blocking(page.mouse().move(box.x + box.width * 0.9, mid))
        _ <- IO.blocking(assertThat(readout).not().hasText(before))
        _ <- IO.blocking(page.mouse().up())
      } yield ()
    }
  }

  test("slider: the readout holds its place while its reading changes width") {
    // The label is capped by the readout's leading edge, so a shrink-wrapped
    // readout was a moving cap: dragging `9 %` to `100 %` widened the box, slid
    // that edge left, and re-clipped the label on every frame of the drag.
    withPage(Scene.of(SmokeDashboard.percentSlider)) { (page, _) =>
      val slider = page.locator("input[type=range]")
      val readout = page.locator(".state")
      for {
        box <- IO.blocking(slider.boundingBox())
        mid = box.y + box.height / 2
        _ <- IO.blocking(page.mouse().move(box.x + box.width * 0.05, mid))
        _ <- IO.blocking(page.mouse().down())
        narrow <- IO.blocking(readout.textContent())
        narrowEdge <- IO.blocking(readout.boundingBox().x)
        _ <- IO.blocking(page.mouse().move(box.x + box.width * 0.98, mid))
        // The reading has to actually get WIDER, or the edge holding still
        // proves nothing.
        _ <- IO.blocking(assertThat(readout).not().hasText(narrow))
        wide <- IO.blocking(readout.textContent())
        wideEdge <- IO.blocking(readout.boundingBox().x)
        _ <- IO.blocking(page.mouse().up())
      } yield {
        assert(wide.trim.length > narrow.trim.length, clue = (narrow, wide))
        assertEqualsDouble(wideEdge, narrowEdge, 0.5)
      }
    }
  }

  /** Every `.slider-head`'s own box, its readout's and its badge's, on the page
    * as laid out — one call, because thirty locator round trips to describe one
    * screen is thirty chances for the page to move between them.
    */
  private def sliderRows(page: Page): IO[List[UiSmokeSuite.RowBox]] =
    IO.blocking(
      page
        .evaluate(
          """() => JSON.stringify([...document.querySelectorAll('.slider-head')].map(head => {
            |  const card = head.closest('article.slider-card').getBoundingClientRect();
            |  const readout = head.querySelector('.state').getBoundingClientRect();
            |  const badge = head.querySelector('.slider-icon').getBoundingClientRect();
            |  return {
            |    cardRight: card.right, headRight: head.getBoundingClientRect().right,
            |    readoutRight: readout.right,
            |    badgeWidth: badge.width, badgeHeight: badge.height
            |  };
            |}))""".stripMargin
        )
        .toString
    ).flatMap(json => IO.fromEither(decode[List[UiSmokeSuite.RowBox]](json)))

  test(
    "slider: a label too long for a phone still leaves the readout on the row"
  ) {
    // #128. A member row is where this broke: its head is a GRID item, whose
    // automatic minimum is its min-content width — which `nowrap` makes the
    // whole label — so the row grew past the card, `overflow:hidden` cut it, and
    // the reading was not on screen at all (measured 451.8 against a card
    // ending at 336, at a 360px viewport). A plain row's head is an ordinary
    // block and was never affected, which is why one long label on its own
    // showed nothing.
    //
    // So the fixture carries every shape a row takes, and the assertion is the
    // one thing true of all of them: whatever the label, the reading is on the
    // card it belongs to.
    withPage(
      Scene.of(SmokeDashboard.longLabelRows),
      viewport = Some(360 -> 740)
    ) { (page, _) =>
      for {
        _ <- IO.blocking(assertThat(page.locator(".state").first()).isVisible())
        rows <- sliderRows(page)
      } yield {
        assertEquals(rows.size, 5, clue = "two plain rows, a head, two members")
        rows.foreach(r =>
          assert(
            r.readoutRight <= r.cardRight,
            clue = (r.readoutRight, r.cardRight)
          )
        )
        // The row itself, not only the reading: a head wider than its card is
        // the defect, and clipping it is what hid the reading rather than what
        // was wrong.
        rows.foreach(r =>
          assert(
            r.headRight <= r.cardRight,
            clue = (r.headRight, r.cardRight)
          )
        )
      }
    }
  }

  test("slider: a long label clips itself rather than squeezing the badge") {
    // #128, the other half. The badge is a circle with a fixed glyph in it, and
    // as a shrinkable flex item it gave its width to a long label — 2rem
    // measured down to 1.5rem — which makes the circle an ellipse and leaves
    // the glyph painting over its own edge.
    //
    // Asserted as "the label cannot change the badge": a badge stays square,
    // and a row whose text overflows has the same one as the row above it whose
    // text fits. Sizes differ by SHAPE on purpose (a group's head badge is
    // bigger than a member's), so rows are compared pairwise within a shape,
    // which the fixture orders as short-then-long.
    withPage(
      Scene.of(SmokeDashboard.longLabelRows),
      viewport = Some(360 -> 740)
    ) { (page, _) =>
      for {
        _ <- IO.blocking(
          assertThat(page.locator(".slider-icon").first()).isVisible()
        )
        rows <- sliderRows(page)
      } yield {
        rows.foreach(r =>
          assertEqualsDouble(
            r.badgeWidth,
            r.badgeHeight,
            0.5,
            clue = s"an oval badge: ${r.badgeWidth}x${r.badgeHeight}"
          )
        )
        // Rows 0/1 are the two plain sliders, 3/4 the two members — each pair a
        // fitting label beside an overflowing one.
        assertEqualsDouble(rows(1).badgeWidth, rows(0).badgeWidth, 0.5)
        assertEqualsDouble(rows(3).badgeWidth, rows(4).badgeWidth, 0.5)
      }
    }
  }

  test("slider: a REFUSED commit puts the thumb back where the device is") {
    // The bug the client/server split exists to fix (ADR 0025). While the drag
    // wrote the server's own `value` slot, a commit that failed produced no
    // correcting frame — the device never moved, so the server's diff said
    // "nothing owed" — and the slider kept showing a brightness the light
    // never took, indefinitely.
    withPage(scene) { (page, ts) =>
      val slider = page.locator("input[type=range]")
      // The refusal must not land before the gesture has been observed: the
      // rollback it triggers races this test's own read of the thumb, and on
      // the wrong interleaving the read sees the restored 180 and the
      // vacuity-guard below fires. The route is captured, not answered —
      // answering it later must not happen on Playwright's dispatch thread,
      // which a blocked callback would stall along with every other call.
      val held =
        new java.util.concurrent.LinkedTransferQueue[
          com.microsoft.playwright.Route
        ]
      val refusal =
        new com.microsoft.playwright.Route.FulfillOptions()
          .setStatus(404)
          .setBody("no")
      for {
        _ <- ts.awaitLive()
        before <- IO.blocking(slider.inputValue())
        _ <- IO.blocking(
          page.route("**/sse/action/**", route => { val _ = held.add(route) })
        )
        _ <- IO.blocking(slider.focus())
        _ <- IO.blocking(slider.press("End"))
        // It moved — otherwise the assertion below would pass vacuously.
        moved <- IO.blocking(slider.inputValue())
        _ <- IO(assert(moved != before, clue = s"$before -> $moved"))
        // …and comes back, because the refusal says nothing is coming.
        _ <- IO.blocking {
          val route = held.poll()
          if route != null then route.fulfill(refusal)
        }
        back <- eventually(IO.blocking(slider.inputValue()))(_ == before)
        // The fill is a function of the position, so it is restored with it
        // rather than shadowed separately.
        fill <- IO.blocking(
          page
            .locator("div.slider")
            .first()
            .evaluate("e => e.style.getPropertyValue('--_end')")
            .toString
        )
      } yield {
        assertEquals(back, before)
        assert(fill.nonEmpty && fill != "0%", clue = fill)
      }
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

object UiSmokeSuite {

  /** One slider row as the browser laid it out — the boxes the #128 layout
    * assertions are about, measured together (see [[UiSmokeSuite.sliderRows]]).
    */
  final case class RowBox(
      cardRight: Double,
      headRight: Double,
      readoutRight: Double,
      badgeWidth: Double,
      badgeHeight: Double
  )

  object RowBox {
    given Decoder[RowBox] = Decoder.forProduct5(
      "cardRight",
      "headRight",
      "readoutRight",
      "badgeWidth",
      "badgeHeight"
    )(RowBox.apply)
  }
}
