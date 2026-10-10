package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{HouseFixture, Scene, ServiceCall, SmokeDashboard}
import io.circe.{Decoder, Json}
import io.circe.parser.decode

/** Signal-driven UI with no server round trip: wiring with no wire-level
  * observable, which only a real DOM proves.
  */
class UiSmokeSuite extends SmokeSuite {

  private val scene = Scene.of(SmokeDashboard.dashboard)

  /** Not `page.url()`, which the client refreshes from
    * `navigatedWithinDocument` and which lags under load: with the CPU
    * throttled it still read the bare URL after the mirror (ADR 0005) had
    * replaced it twice.
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
        // Read off the first paint: a hardcoded path id shifts with layout
        // edits.
        gid <- IO
          .blocking(panel.getAttribute("id"))
          .map(_.stripSuffix("_panel"))
        deepLink = s"${page.url().takeWhile(_ != '?')}?v.$gid.tab=1"
        _ <- IO.blocking(page.navigate(deepLink))
        // The failure is late: the first paint is right, and the connect's
        // repaint puts the default tab back. An unrelated change is ordered
        // after everything the connect emitted, so seeing it means any repaint
        // has landed.
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
    // Pending signals (ADR 0025): the press says only what it asked for, and a
    // refusal ends the ask, so a failed POST cannot deep-link to a panel never
    // shown. A 404 exercises the client half: this server answers its own
    // refusals with 200 (ADR 0024), so `pendingFail` covers the non-200 a proxy
    // or a gone route produces, which Datastar reports as `error` from
    // `onopen`.
    withPage(scene) { (page, ts) =>
      val panel = page.locator(".tab-panel")
      val climateTab =
        page.locator(".tabs a", new Page.LocatorOptions().setHasText("Climate"))
      for {
        _ <- ts.awaitLive()
        // The mirror applies the seeded selection shortly after connect, so
        // read `before` once it has, or this races initialization.
        before <- eventually(href(page))(_.contains("v."))
        _ <- IO.blocking(
          page.route(
            "**/sse/var/**",
            route =>
              route.fulfill(
                new com.microsoft.playwright.Route.FulfillOptions()
                  .setStatus(404)
                  .setBody("no such surface")
              )
          )
        )
        // Wait for the refusal itself: `containsText("Living Room")` passes
        // before the POST is even sent, so the assertions below need this
        // starting point.
        _ <- IO.blocking(
          page.waitForResponse(
            "**/sse/var/**",
            () => climateTab.click()
          )
        )
        _ <- IO.blocking(assertThat(panel).containsText("Living Room"))
        stillBefore <- href(page)
        _ <- IO(assertEquals(stillBefore, before))
        _ <- IO.blocking(assertThat(climateTab).not().hasClass(active))
        // The refusal must say so: a reverted highlight alone is
        // indistinguishable from a press that never registered. The shell's
        // listener has died silently before (`closest` threw a SyntaxError on
        // an unescaped `data-on:click`).
        _ <- IO.blocking(
          assertThat(page.locator(".fh-toast")).containsText("404")
        )
        _ <- IO.blocking(page.unroute("**/sse/var/**"))
        _ <- IO.blocking(climateTab.click())
        _ <- IO.blocking(assertThat(panel).containsText("Hallway"))
        // The morph and the panel's `data-fh-url` mirror are
        // unordered.
        after <- eventually(href(page))(_ != before)
      } yield assert(after != before, clue = after)
    }
  }

  test("tabs: a tap with nothing left to answer it ends when the stream does") {
    // A down stream says no commit is coming, since the commit rides it. The
    // POST is aborted, so no status and no `error` event. The stream is also
    // cut server-side (`forgetConnections`): a `page.route` only meets new
    // requests, so the open stream would survive and the banner would assert a
    // healthy connection. The blocked reconnect keeps it failed.
    withPage(scene) { (page, ts) =>
      val panel = page.locator(".tab-panel")
      val climateTab =
        page.locator(".tabs a", new Page.LocatorOptions().setHasText("Climate"))
      for {
        _ <- ts.awaitLive()
        _ <- IO.blocking(page.route("**/sse/**", route => route.abort()))
        // Wait for the POST to be issued. An aborted request has no response,
        // and Playwright's Java client dispatches callbacks only while this
        // thread is in one of its calls, so polling a buffer from `IO` waits
        // forever.
        _ <- IO.blocking(
          page.waitForRequest("**/sse/var/**", () => climateTab.click())
        )
        _ <- IO.blocking(assertThat(climateTab).hasClass(active))
        _ <- ts.forgetConnections
        _ <- ts.awaitNoConnections
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
    // A reaped session's idle page tapped into a `conn` the server lacks, got
    // 204, and showed the popup only on the second tap (ADR 0024). The
    // reconnect is blocked across the tap, or Datastar may reconnect first and
    // the test passes exercising nothing.
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
        _ <- IO.blocking(assertThat(popup).hasCount(0))
        // Unroute inside the wait: the aborts fed Datastar's reconnect backoff,
        // so the next attempt can be a dozen seconds out, racing the
        // assertion's timeout.
        _ <- IO.blocking(
          page.waitForRequest(
            "**/sse/dashboard/**",
            new Page.WaitForRequestOptions().setTimeout(60000),
            () => page.unroute("**/sse/dashboard/**")
          )
        )
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
        // The mirror wrote ?ui.popups=<id>, so the reload restores it the way a
        // tab selection is restored.
        _ <- IO.blocking(page.reload())
        _ <- IO.blocking(assertThat(popup).containsText("Kitchen Detail"))
        // Baked into the chrome's popup hole, so there is no
        // dialog-a-moment-later flash.
        html <- ts.page("?ui.popups=detail")
      } yield assert(html.contains("Kitchen Detail"), clue = html)
    }
  }

  test("scroll: the offset comes back with the dashboard") {
    // Crossing dashboards is a document load (ADR 0002), and a page holding a
    // streaming fetch is not bfcache-eligible, so only `fhScroll` restores the
    // offset. Hence a real second navigation, not `reload()`.
    withPage(scene, viewport = Some((390, 360))) { (page, _) =>
      val dashboard = page.url()
      val offset = IO.blocking(page.evaluate("scrollY").toString.toDouble)
      for {
        // Fonts settle first: a font swap reflows and scroll anchoring moves
        // scrollY a couple of px, so a mid-swap baseline disagrees with what
        // `pagehide` saves.
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
        // A real unload fires `pagehide`; going forward makes a new history
        // entry, so no browser restore can be mistaken for ours.
        origin <- IO.blocking(page.evaluate("location.origin").toString)
        _ <- IO.blocking(page.navigate(s"$origin/not-a-dashboard"))
        _ <- IO.blocking(page.navigate(dashboard))
        _ <- IO.blocking(page.evaluate("document.fonts.ready"))
        after <- eventually(offset)(_ > 0d)
      } yield assert(
        // Not exact: the post-restore settle (font reflow, the connect's
        // repaint) costs a few px through scroll anchoring (CI shows 3px). A
        // broken restore lands at 0 or pages off.
        math.abs(after - before) <= 4d,
        clue = s"offset drifted: before=$before after=$after"
      )
    }
  }

  /** `--_end` is the distance from the right edge, so a drag right lowers it.
    * Read computed, since `beer.min.js` and Datastar's style plugin both write
    * the inline style.
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
        // Held down: `input` fires on every move but `change` only on release,
        // the window the fill sat still in.
        _ <- IO.blocking(page.mouse().move(box.x + box.width * 0.9, mid))
        // A failure here is a broken drag, not a broken binding.
        _ <- IO.blocking(assertThat(slider).not().hasValue(seeded))
        during <- eventually(fillEnd(page))(_ != before)
        _ <- IO.blocking(page.mouse().up())
      } yield assertNotEquals(during, before)
    }
  }

  test("slider: a percent readout moves with the drag too") {
    withPage(Scene.of(SmokeDashboard.percentSlider)) { (page, _) =>
      val slider = page.locator("input[type=range]")
      val readout = page.locator(".fh-reading")
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
    // A shrink-wrapped readout was a moving cap: dragging `9 %` to `100 %`
    // widened it and re-clipped the label every frame.
    withPage(Scene.of(SmokeDashboard.percentSlider)) { (page, _) =>
      val slider = page.locator("input[type=range]")
      val readout = page.locator(".fh-reading")
      for {
        box <- IO.blocking(slider.boundingBox())
        mid = box.y + box.height / 2
        _ <- IO.blocking(page.mouse().move(box.x + box.width * 0.05, mid))
        _ <- IO.blocking(page.mouse().down())
        narrow <- IO.blocking(readout.textContent())
        narrowEdge <- IO.blocking(readout.boundingBox().x)
        _ <- IO.blocking(page.mouse().move(box.x + box.width * 0.98, mid))
        // It must get wider, or the edge holding still proves nothing.
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

  /** One call: thirty locator round trips are thirty chances for the page to
    * move.
    */
  private def sliderRows(page: Page): IO[List[UiSmokeSuite.RowBox]] =
    IO.blocking(
      page
        .evaluate(
          """() => JSON.stringify([...document.querySelectorAll('.slider-head')].map(head => {
            |  const card = head.closest('article.slider-card').getBoundingClientRect();
            |  const readout = head.querySelector('.fh-reading').getBoundingClientRect();
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
    // #128. A member row's head is a grid item whose min-content width `nowrap`
    // makes the whole label, so the row outgrew the card and the reading was
    // cut off (451.8 against a card ending at 336, at 360px). A plain row's
    // head was never affected, so the fixture carries every row shape.
    withPage(
      Scene.of(SmokeDashboard.longLabelRows),
      viewport = Some(360 -> 740)
    ) { (page, _) =>
      for {
        _ <- IO.blocking(
          assertThat(page.locator(".fh-reading").first()).isVisible()
        )
        rows <- sliderRows(page)
      } yield {
        assertEquals(rows.size, 5, clue = "two plain rows, a head, two members")
        rows.foreach(r =>
          assert(
            r.readoutRight <= r.cardRight,
            clue = (r.readoutRight, r.cardRight)
          )
        )
        // The oversized head is the defect; clipping only hid the reading.
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
    // #128's other half: as a shrinkable flex item the badge gave width to a
    // long label (2rem down to 1.5rem), turning the circle into an ellipse.
    // Head and member badges differ in size on purpose, so rows compare
    // pairwise within a shape, short then long.
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
        // Rows 0/1 are the plain sliders, 3/4 the members.
        assertEqualsDouble(rows(1).badgeWidth, rows(0).badgeWidth, 0.5)
        assertEqualsDouble(rows(3).badgeWidth, rows(4).badgeWidth, 0.5)
      }
    }
  }

  test("a title too long for its card stays on it and ends in an ellipsis") {
    // A tile's title is a flex item that is not stretched, so it was sized to
    // its whole line: the box ran past the card and so never cut.
    withPage(
      Scene
        .of(SmokeDashboard.longTitleCards)
        .entity(SmokeDashboard.longNameLight),
      viewport = Some(360 -> 740)
    ) { (page, _) =>
      for {
        _ <- IO.blocking(
          assertThat(page.locator(".fh-text").first()).isVisible()
        )
        boxes <- IO
          .blocking(
            page
              .evaluate(
                """() => JSON.stringify([...document.querySelectorAll('.fh-text')].map(box => ({
                |  text: box.textContent.trim(),
                |  right: box.getBoundingClientRect().right,
                |  cardRight: box.closest('article, button, a').getBoundingClientRect().right,
                |  cut: box.scrollWidth > box.clientWidth,
                |  textOverflow: getComputedStyle(box).textOverflow
                |})))""".stripMargin
              )
              .toString
          )
          .flatMap(json =>
            IO.fromEither(decode[List[UiSmokeSuite.TextBox]](json))
          )
      } yield {
        val titles = boxes.filter(_.text == SmokeDashboard.longName)
        assertEquals(
          titles.size,
          3,
          clue = "the tile, the button's label and second line"
        )
        boxes.foreach(b => assert(b.right <= b.cardRight + 0.5, clue = b))
        titles.foreach(b =>
          assert(b.cut && b.textOverflow == "ellipsis", clue = b)
        )
      }
    }
  }

  /** Media query and cascade already applied. */
  private def rootTouchAction(page: Page): IO[String] =
    IO.blocking(
      page
        .evaluate(
          "() => getComputedStyle(document.documentElement).touchAction"
        )
        .toString
    )

  test("touch: the page does not zoom under a finger") {
    // #306. Asserted on the root because the effective value intersects down
    // the ancestor chain.
    withPage(scene, touch = true) { (page, _) =>
      for {
        // The rule is behind `(pointer:coarse)`, so a context that did not flip
        // the query would test nothing.
        coarse <- IO.blocking(
          page.evaluate("matchMedia('(pointer:coarse)').matches")
        )
        _ = assertEquals(coarse, true: Any)
        action <- rootTouchAction(page)
      } yield assertEquals(action, "pan-x pan-y")
    }
  }

  test("a mouse keeps every zoom it had") {
    // Browser zoom is an accessibility feature: a rule leaking out of
    // `(pointer:coarse)` would take it from desktop readers.
    withPage(scene) { (page, _) =>
      rootTouchAction(page).map(assertEquals(_, "auto"))
    }
  }

  test("slider: a REFUSED commit puts the thumb back where the device is") {
    // While the drag wrote the server's `value` slot, a failed commit produced
    // no correcting frame, and the slider showed a brightness the light never
    // took (ADR 0025).
    withPage(scene) { (page, ts) =>
      val slider = page.locator("input[type=range]")
      // The refusal must not land before the gesture is observed, or the
      // rollback races this test's read and the vacuity guard fires. Captured,
      // not answered: a blocked callback would stall Playwright's dispatch
      // thread.
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
          page.route("**/sse/call/**", route => { val _ = held.add(route) })
        )
        _ <- IO.blocking(slider.focus())
        _ <- IO.blocking(slider.press("End"))
        // Otherwise the assertion below passes vacuously.
        moved <- IO.blocking(slider.inputValue())
        _ <- IO(assert(moved != before, clue = s"$before -> $moved"))
        _ <- IO.blocking {
          val route = held.poll()
          if route != null then route.fulfill(refusal)
        }
        back <- eventually(IO.blocking(slider.inputValue()))(_ == before)
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
        // End jumps to `max` (255), deterministic unlike a synthetic drag.
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

  /** Measured together (see [[UiSmokeSuite.sliderRows]]). */
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

  /** One `.fh-text` box against the card it is on. */
  final case class TextBox(
      text: String,
      right: Double,
      cardRight: Double,
      cut: Boolean,
      textOverflow: String
  )

  object TextBox {
    given Decoder[TextBox] = Decoder.forProduct5(
      "text",
      "right",
      "cardRight",
      "cut",
      "textOverflow"
    )(TextBox.apply)
  }
}
