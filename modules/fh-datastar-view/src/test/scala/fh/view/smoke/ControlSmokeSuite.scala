package fh.view.smoke

import fh.view.smoke.BrowserSuite.asJsBoolean

import cats.effect.IO
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{
  FakeConfig,
  HouseFixture,
  Scene,
  ServiceCall,
  SmokeDashboard
}
import io.circe.Json

import scala.concurrent.duration.*

/** "Click -> HA -> back" through a real mouse click, proving the
  * `data-on:click` wiring and not just the route.
  */
class ControlSmokeSuite extends SmokeSuite {

  private val scene = Scene.of(SmokeDashboard.dashboard)

  /** The button, not its text: `getByText` resolves to the text run, and the
    * busy/error classes ride the button.
    */
  private def toggleControl(
      page: com.microsoft.playwright.Page
  ): com.microsoft.playwright.Locator =
    page.locator(
      "button",
      new com.microsoft.playwright.Page.LocatorOptions()
        .setHasText("Toggle Kitchen")
    )

  private def clickToggle(page: com.microsoft.playwright.Page): IO[Unit] =
    IO.blocking(toggleControl(page).click())

  test("clicking a control calls the service back into HA") {
    withPage(scene) { (page, ts) =>
      for {
        _ <- clickToggle(page)
        calls <- awaitAction(toggleControl(page))(
          eventually(ts.fake.recordedCalls)(_.nonEmpty)
        )
      } yield assertEquals(
        calls,
        Vector(
          ServiceCall(
            "light",
            "toggle",
            HouseFixture.kitchenLight.entityId,
            Json.obj()
          )
        )
      )
    }
  }

  test("round-trip: a click's consequent state reaches the browser") {
    withPage(scene) { (page, ts) =>
      val kitchenState = page
        .locator(
          "article.entity",
          new com.microsoft.playwright.Page.LocatorOptions()
            .setHasText("Kitchen")
        )
        .locator(".fh-reading")
      for {
        _ <- ts.awaitLive()
        _ <- clickToggle(page)
        _ <- awaitAction(toggleControl(page))(
          eventually(ts.fake.recordedCalls)(_.nonEmpty)
        )
        _ <- ts.fake.emit(HouseFixture.kitchenLight.entityId, "off", Map.empty)
        _ <- IO.blocking(assertThat(kitchenState).hasText("off"))
      } yield ()
    }
  }

  test(
    "a guarded control shows busy while its call is in flight and ignores a second click"
  ) {
    withPage(scene, fakeConfig = FakeConfig(callDelay = 2.seconds)) {
      (page, ts) =>
        val toggle = page.locator(
          "button",
          new com.microsoft.playwright.Page.LocatorOptions()
            .setHasText("Toggle Kitchen")
        )
        def busy: IO[Boolean] =
          IO.blocking(
            toggle
              .evaluate("el => el.classList.contains('fh-disabled')")
              .asJsBoolean
          )
        for {
          before <- busy
          _ <- IO(assert(!before))
          _ <- IO.blocking(toggle.click())
          _ <- eventually(busy)(identity)
          // ...so a second click is a no-op, not a second call.
          _ <- IO.blocking(toggle.click())
          _ <- IO.sleep(300.millis)
          during <- ts.fake.recordedCalls
          _ <- IO(
            assertEquals(
              during,
              Vector(
                ServiceCall(
                  "light",
                  "toggle",
                  HouseFixture.kitchenLight.entityId,
                  Json.obj()
                )
              )
            )
          )
          _ <- eventually(busy)(b => !b)
          after <- ts.fake.recordedCalls
        } yield assertEquals(after.size, 1)
    }
  }

  test("a refusal ENDS the wait, instead of burning the timeout") {
    // Without the error state, a test waiting on a consequence that never comes
    // waits out its deadline and reports "never saw X", pointing nowhere near
    // the cause. The elapsed-time assertion is the real one.
    withPage(scene, fakeConfig = FakeConfig(failCalls = true)) { (page, ts) =>
      // Cannot arrive, so the only way out is the refusal.
      val neverHappens = eventually(IO.pure(false))(identity)
      for {
        _ <- ts.awaitLive()
        _ <- clickToggle(page)
        t0 <- IO.monotonic
        outcome <- awaitAction(toggleControl(page))(neverHappens).attempt
        t1 <- IO.monotonic
      } yield {
        val why = outcome.left.map(_.getMessage).left.getOrElse("")
        assert(outcome.isLeft, "a refused action must not report success")
        assert(why.contains("REFUSED"), clue = why)
        // HA's own words.
        assert(why.contains("call_service rejected by the fake"), clue = why)
        assert(
          t1 - t0 < BrowserSuite.AssertionTimeout,
          s"took ${t1 - t0}, which is the timeout it was meant to skip"
        )
      }
    }
  }

  test("a REFUSED call leaves the error state on the control that asked") {
    // `data-indicator` clears on either outcome, so a refusal looked like
    // success, and the global toast is gone in 4s. The error state makes an
    // outcome something to wait on. A refused action answers 200, so Datastar
    // dispatches no `error` and `failedOn` never runs: this can only pass on
    // the server's signal patch, keyed on the node id the tap sent.
    withPage(scene, fakeConfig = FakeConfig(failCalls = true)) { (page, ts) =>
      val toggle = page.locator(
        "button",
        new com.microsoft.playwright.Page.LocatorOptions()
          .setHasText("Toggle Kitchen")
      )
      def hasClass(c: String): IO[Boolean] =
        IO.blocking(
          toggle
            .evaluate(s"el => el.classList.contains('$c')")
            .asJsBoolean
        )
      for {
        _ <- ts.awaitLive()
        clean <- hasClass("fh-error")
        _ <- IO(assert(!clean, "idle before anything was asked"))
        _ <- IO.blocking(toggle.click())
        _ <- eventually(hasClass("fh-error"))(identity)
        // Not still claiming to be in flight: the two states are distinct.
        _ <- eventually(hasClass("fh-disabled"))(b => !b)
        // In addition to the toast, not a replacement.
        _ <- IO.blocking(assertThat(page.locator(".fh-toast")).isVisible())
      } yield ()
    }
  }

  test(
    "the guard look is immediate: fh-disabled and fh-loading land with the tap and clear with the response"
  ) {
    // The answer to the tap, so not gated: both bind to the busy signal and
    // flip in one frame. Only the spinner waits, on a derived signal.
    withPage(scene, fakeConfig = FakeConfig(callDelay = 2.seconds)) {
      (page, _) =>
        val toggle = page.locator(
          "button",
          new com.microsoft.playwright.Page.LocatorOptions()
            .setHasText("Toggle Kitchen")
        )
        def disabled: IO[Boolean] =
          IO.blocking(
            toggle
              .evaluate("el => el.classList.contains('fh-disabled')")
              .asJsBoolean
          )
        def loading: IO[Boolean] =
          IO.blocking(
            toggle
              .evaluate("el => el.classList.contains('fh-loading')")
              .asJsBoolean
          )
        for {
          _ <- IO.blocking(toggle.click())
          _ <- eventually(disabled)(identity)
          _ <- eventually(loading)(identity)
          _ <- eventually(loading)(l => !l)
          _ <- eventually(disabled)(d => !d)
        } yield ()
    }
  }

  test(
    "a slider's commit is guarded: re-releasing while the POST is in flight is a no-op"
  ) {
    // A slider commits on `change`. While the held POST is in flight the input
    // is disabled, and a programmatic second `change` (a disabled input cannot
    // fire one natively) is swallowed by the guard.
    withPage(scene, fakeConfig = FakeConfig(callDelay = 2.seconds)) {
      (page, ts) =>
        val slider = page.locator("input[type=range]")
        val wrapper = page.locator(".slider.max")
        // The commit's busy pieces put BeerCSS's `.shape.loading-indicator` on
        // the head badge, so its glyph spins while in flight.
        val badge = page.locator(".slider-icon")
        def busy: IO[Boolean] =
          IO.blocking(
            wrapper
              .evaluate("el => el.classList.contains('fh-disabled')")
              .asJsBoolean
          )
        def disabled: IO[Boolean] = IO.blocking(slider.isDisabled())
        def badgeSpinning: IO[Boolean] =
          IO.blocking(
            badge
              .evaluate("el => el.classList.contains('loading-indicator')")
              .asJsBoolean
          )
        for {
          _ <- IO.blocking(assert(!slider.isDisabled()))
          // End jumps the thumb to `max` (255) and releases.
          _ <- IO.blocking(slider.focus())
          _ <- IO.blocking(slider.press("End"))
          _ <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
          _ <- eventually(busy)(identity)
          _ <- eventually(disabled)(identity)
          _ <- eventually(badgeSpinning)(identity)
          // ...and a second commit is a no-op.
          _ <- IO.blocking(
            slider.evaluate(
              "el => el.dispatchEvent(new Event('change', {bubbles: true}))"
            )
          )
          _ <- IO.sleep(300.millis)
          during <- ts.fake.recordedCalls
          _ <- IO(assertEquals(during.size, 1))
          _ <- eventually(busy)(b => !b)
          _ <- eventually(disabled)(d => !d)
          _ <- eventually(badgeSpinning)(s => !s)
        } yield ()
    }
  }

  test(
    "a busy-guarded element's icon becomes a spinner while its call is in flight"
  ) {
    // `c.iconButton` is an `i.mdi` glyph plus the busy pieces, so while held it
    // takes `.shape.loading-indicator`; a labelled button dims instead. The
    // class is bound to `tap.pkl`'s delayed signal, so its presence is the
    // decision.
    withPage(
      Scene.of(SmokeDashboard.busyIcon),
      fakeConfig = FakeConfig(callDelay = 2.seconds)
    ) { (page, _) =>
      val icon = page.locator(".slider-actions button")
      def spinning: IO[Boolean] =
        IO.blocking(
          icon
            .evaluate("el => el.classList.contains('loading-indicator')")
            .asJsBoolean
        )
      for {
        idle <- spinning
        _ <- IO(assert(!idle))
        _ <- IO.blocking(icon.click())
        _ <- eventually(spinning)(identity)
        _ <- eventually(spinning)(s => !s)
      } yield ()
    }
  }

  test("an icon-only button is ROUND, not stretched to its cell") {
    // `.fh-cell>:is(.button,button)` sets `inline-size:100%` at (0,2,0), which
    // beat BeerCSS's `.circle` (0,1,0) and stretched the round button into a
    // wide, short pill. Geometry rather than a screenshot: a computed size is
    // assertable directly, with no font-dependent baseline, and no visual
    // baseline has a round button anyway.
    withPage(Scene.of(SmokeDashboard.busyIcon)) { (page, _) =>
      for {
        box <- IO.blocking(page.locator(".slider-actions button").boundingBox())
        _ <- IO(
          assert(
            box.width > 8 && box.height > 8,
            s"the button did not render: ${box.width}x${box.height}"
          )
        )
        // `2`, not `0`: a fractional size rounds per axis. An oval is off by
        // tens of pixels.
        _ <- IO(
          assert(
            math.abs(box.width - box.height) <= 2,
            s"an icon-only button must be round, but it is " +
              f"${box.width}%.1f x ${box.height}%.1f — something is stretching " +
              "it to its cell (check specificity against BeerCSS's .circle)"
          )
        )
      } yield ()
    }
  }

  test("a LABELLED button still fills its cell") {
    // `:not(.circle)` narrows a rule labelled buttons depend on, so a "fix"
    // that deleted it would pass the roundness test and shrink every button to
    // its text. Asserted relative to the cell, whose width is the dashboard's.
    withPage(scene) { (page, _) =>
      for {
        button <- IO.blocking(
          page
            .locator(
              "button",
              new com.microsoft.playwright.Page.LocatorOptions()
                .setHasText("Toggle Kitchen")
            )
            .boundingBox()
        )
        cell <- IO.blocking(
          page.locator(".fh-cell:has(> button)").first().boundingBox()
        )
        _ <- IO(
          assert(
            math.abs(button.width - cell.width) <= 2,
            f"a labelled button must fill its cell, but it is " +
              f"${button.width}%.1f wide in a ${cell.width}%.1f cell"
          )
        )
      } yield ()
    }
  }

  test("a rejected action toasts WHAT went wrong, and clears busy") {
    // The server answers 200 patching `_toast` with HA's message. The bundle
    // parses a body only on 200, so a 4xx could only ever show a status code.
    withPage(scene, fakeConfig = FakeConfig(failCalls = true)) { (page, _) =>
      val toggle = page.locator(
        "button",
        new com.microsoft.playwright.Page.LocatorOptions()
          .setHasText("Toggle Kitchen")
      )
      def busy: IO[Boolean] =
        IO.blocking(
          toggle
            .evaluate("el => el.classList.contains('fh-disabled')")
            .asJsBoolean
        )
      for {
        _ <- IO.blocking(toggle.click())
        _ <- IO.blocking(
          assertThat(page.locator(".fh-toast"))
            .hasText("call_service rejected by the fake")
        )
        // `finished` fires on a rejected fetch too, so an error cannot leave
        // the button guarded.
        _ <- eventually(busy)(b => !b)
      } yield ()
    }
  }

  test("a light that only switches is pressable anywhere on its row") {
    // The whole row is the button, so a press near its bottom edge must count.
    // Aimed by page coordinates: BeerCSS's fixed `button` height left the
    // overlay a strip across the top while the centre stayed live, so a centre
    // click proves nothing.
    val switchScene =
      Scene.of(SmokeDashboard.switchSlider).entity(SmokeDashboard.switchLight)
    withPage(switchScene) { (page, ts) =>
      for {
        card <- IO.blocking(
          page.locator("article.slider-card").boundingBox()
        )
        _ <- IO.blocking(
          page.mouse().click(card.x + card.width / 2, card.y + card.height - 4)
        )
        calls <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
      } yield assertEquals(
        calls,
        Vector(
          ServiceCall(
            "light",
            "toggle",
            SmokeDashboard.switchLight.entityId,
            Json.obj()
          )
        )
      )
    }
  }

  test("a switch looks like the tile beside it, and cuts a long title") {
    // #515. BeerCSS's `.row` squared the switch's corners and let its title
    // wrap; the tile is what the switch is held to.
    withPage(
      Scene.of(SmokeDashboard.switchCards).entity(SmokeDashboard.switchLight),
      viewport = Some(360 -> 740)
    ) { (page, _) =>
      for {
        // Both badges paint from bindings, so wait for those to land.
        _ <- IO.blocking(
          assertThat(page.locator("article.toggle[data-switched=on]"))
            .hasCount(2)
        )
        _ <- IO.blocking(
          assertThat(page.locator(".fh-cell.fh-active>article.entity"))
            .hasCount(1)
        )
        json <- IO.blocking(
          page
            .evaluate(
              """() => {
                |  const tile = document.querySelector('article.entity');
                |  const [plain, long] = document.querySelectorAll('article.toggle');
                |  const look = el => getComputedStyle(el);
                |  const title = long.querySelector('.fh-text');
                |  return JSON.stringify({
                |    tileRadius: look(tile).borderTopLeftRadius,
                |    switchRadius: look(plain).borderTopLeftRadius,
                |    tileBadge: look(tile.querySelector('.fh-badge')).backgroundColor,
                |    switchBadge: look(plain.querySelector('.fh-badge')).backgroundColor,
                |    cut: title.scrollWidth > title.clientWidth,
                |    textOverflow: look(title).textOverflow,
                |    switchRight: long.querySelector('.switch').getBoundingClientRect().right,
                |    cardRight: long.getBoundingClientRect().right
                |  });
                |}""".stripMargin
            )
            .toString
        )
        m <- IO.fromEither(io.circe.parser.decode[Map[String, Json]](json))
      } yield {
        def str(k: String) = m(k).asString.getOrElse("")
        def num(k: String) = m(k).asNumber.map(_.toDouble).getOrElse(0.0)
        assert(str("switchRadius") != "0px", clue = m)
        assertEquals(str("switchRadius"), str("tileRadius"), clue = m)
        // Both entities are on: the tile tints its badge with the accent.
        assertEquals(str("switchBadge"), str("tileBadge"), clue = m)
        assert(m("cut").asBoolean.contains(true), clue = m)
        assertEquals(str("textOverflow"), "ellipsis", clue = m)
        assert(num("switchRight") <= num("cardRight"), clue = m)
      }
    }
  }

  test("every badge wears one on look, one off look and one look with no off") {
    withPage(Scene.of(SmokeDashboard.badgeLooks)) { (page, _) =>
      for {
        // The tile, the switch and both slider heads on the one lit light.
        _ <- IO.blocking(
          assertThat(page.locator(".fh-cell.fh-active")).hasCount(4)
        )
        json <- IO.blocking(
          page
            .evaluate(
              """() => JSON.stringify([...document.querySelectorAll('.fh-badge')]
                |  .map(b => { const s = getComputedStyle(b);
                |    return [s.backgroundColor, s.color]; }))""".stripMargin
            )
            .toString
        )
        looks <- IO.fromEither(
          io.circe.parser.decode[List[(String, String)]](json)
        )
      } yield {
        assertEquals(looks.length, 8, clue = looks)
        val List(
          tileOn,
          switchOn,
          sliderOn,
          tileOff,
          switchOff,
          sensor,
          groupOn,
          memberOff
        ) =
          looks: @unchecked
        assertEquals(switchOn, tileOn, clue = looks)
        assertEquals(sliderOn, tileOn, clue = looks)
        assertEquals(groupOn, tileOn, clue = looks)
        assertEquals(switchOff, tileOff, clue = looks)
        // Inside an on slider, and still off: the look is its own cell's.
        assertEquals(memberOff, tileOff, clue = looks)
        assertNotEquals(tileOn._1, tileOff._1, clue = looks)
        assertNotEquals(tileOn._2, tileOff._2, clue = looks)
        // No off: the on glyph, on a seat of its own.
        assertEquals(sensor._2, tileOn._2, clue = looks)
        assertNotEquals(sensor._1, tileOn._1, clue = looks)
        assertNotEquals(sensor._1, tileOff._1, clue = looks)
      }
    }
  }

  test("an unavailable slider is disabled, and a stray change posts nothing") {
    withPage(scene) { (page, ts) =>
      val input = page.locator("input[type=range]")
      val track = page.locator(".slider.max")
      for {
        _ <- ts.awaitLive()
        _ <- IO.blocking(assertThat(input).isEnabled())
        _ <- ts.fake.emit(
          HouseFixture.kitchenLight.entityId,
          "unavailable",
          Map.empty
        )
        _ <- IO.blocking(assertThat(input).isDisabled())
        _ <- IO.blocking(
          assertThat(track).hasClass(
            java.util.regex.Pattern.compile("fh-disabled")
          )
        )
        // A disabled control fires no `change` of its own; a dispatched one
        // still reaches the handler, which is what the refusal guard is for.
        _ <- IO.blocking(
          input.evaluate("el => el.dispatchEvent(new Event('change'))")
        )
        _ <- IO.sleep(300.millis)
        calls <- ts.fake.recordedCalls
        _ <- IO(assertEquals(calls, Vector.empty))
        _ <- ts.fake.emit(HouseFixture.kitchenLight.entityId, "on", Map.empty)
        _ <- IO.blocking(assertThat(input).isEnabled())
        // The control: the same dispatch posts once the light is back, so the
        // silence above was the guard and not a harness that sees nothing.
        _ <- IO.blocking(
          input.evaluate("el => el.dispatchEvent(new Event('change'))")
        )
        after <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
        _ <- IO(assertEquals(after.map(_.service), Vector("turn_on")))
      } yield ()
    }
  }

  test("touch: a tap on a slider sets the value where the finger landed") {
    // On a coarse pointer the range input is `pointer-events:none`, so the tap
    // is the script's to interpret or nobody's.
    withPage(scene, touch = true) { (page, ts) =>
      for {
        // The CSS half is behind `(pointer:coarse)`; a touch on a page styled
        // for a mouse is a combination no device has.
        coarse <- IO.blocking(
          page.evaluate("matchMedia('(pointer:coarse)').matches")
        )
        _ = assertEquals(coarse, true: Any)
        box <- IO.blocking(page.locator(".slider.max").boundingBox())
        _ <- IO.blocking(
          page
            .touchscreen()
            .tap(box.x + box.width * 0.25, box.y + box.height / 2)
        )
        calls <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
      } yield {
        assertEquals(calls.size, 1)
        assertEquals(calls.head.domain, "light")
        assertEquals(calls.head.service, "turn_on")
        val brightness =
          calls.head.serviceData.hcursor.get[Int]("brightness").toOption
        // A quarter across 1..255 is ~64. A window, since the value depends on
        // real pixels.
        assert(brightness.exists(b => b > 50 && b < 80), clue = calls)
      }
    }
  }
}
