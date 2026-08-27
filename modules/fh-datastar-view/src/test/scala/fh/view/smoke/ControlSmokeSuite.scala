package fh.view.smoke

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

/** "Click -> HA -> back": the control->service->feed->browser loop, driven
  * through an actual mouse click rather than a raw `POST` (the Scala functional
  * suite's level) — proving the `data-on:click` wiring itself, not just the
  * route it targets.
  */
class ControlSmokeSuite extends SmokeSuite {

  private val scene = Scene.of(SmokeDashboard.dashboard)

  private def clickToggle(page: com.microsoft.playwright.Page): IO[Unit] =
    IO.blocking(page.getByText("Toggle Kitchen").click())

  test("clicking a control calls the service back into HA") {
    withPage(scene) { (page, ts) =>
      for {
        _ <- clickToggle(page)
        calls <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
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
        .locator(".state")
      for {
        _ <- ts.awaitLive()
        _ <- clickToggle(page)
        _ <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
        _ <- ts.fake.emit(HouseFixture.kitchenLight.entityId, "off", Map.empty)
        _ <- IO.blocking(assertThat(kitchenState).hasText("off"))
      } yield ()
    }
  }

  test(
    "a guarded control shows busy while its call is in flight and ignores a second click"
  ) {
    // The fake HOLDS the call_service response for 2s, so the fetch stays in
    // flight long enough to click inside the guard window and read the busy
    // state.
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
              .asInstanceOf[Boolean]
          )
        for {
          // Idle before anything was clicked...
          before <- busy
          _ <- IO(assert(!before))
          // First click: the call is in flight (the response is held)...
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
          // The held response lands, and busy clears.
          _ <- eventually(busy)(b => !b)
          after <- ts.fake.recordedCalls
        } yield assertEquals(after.size, 1)
    }
  }

  test(
    "the guard look is immediate: fh-disabled and fh-loading land with the tap and clear with the response"
  ) {
    // These two are the answer to the tap ("landed, and this is inert now"), so
    // they are NOT gated — both bind straight to the busy signal and flip in
    // the same Datastar frame. Only the spinner waits, on a derived signal.
    withPage(scene, fakeConfig = FakeConfig(callDelay = 2.seconds)) {
      (page, ts) =>
        val toggle = page.locator(
          "button",
          new com.microsoft.playwright.Page.LocatorOptions()
            .setHasText("Toggle Kitchen")
        )
        def disabled: IO[Boolean] =
          IO.blocking(
            toggle
              .evaluate("el => el.classList.contains('fh-disabled')")
              .asInstanceOf[Boolean]
          )
        def loading: IO[Boolean] =
          IO.blocking(
            toggle
              .evaluate("el => el.classList.contains('fh-loading')")
              .asInstanceOf[Boolean]
          )
        for {
          _ <- IO.blocking(toggle.click())
          // Both classes are there immediately (same signal).
          _ <- eventually(disabled)(identity)
          _ <- eventually(loading)(identity)
          // The response lands (2s), busy clears, and both classes drop.
          _ <- eventually(loading)(l => !l)
          _ <- eventually(disabled)(d => !d)
        } yield ()
    }
  }

  test(
    "a slider's commit is guarded: re-releasing while the POST is in flight is a no-op"
  ) {
    // A slider paints live on `input` but COMMITS on release (`change` → the
    // value POST). The fake holds that POST for 2s; while it is in flight the
    // input is disabled (`data-attr:disabled`) and a second `change` — here
    // dispatched programmatically, because a disabled input cannot fire one
    // natively — is swallowed by the guard, not turned into a second call.
    withPage(scene, fakeConfig = FakeConfig(callDelay = 2.seconds)) {
      (page, ts) =>
        val slider = page.locator("input[type=range]")
        val wrapper = page.locator(".slider.max")
        // The head badge is the slider's icon (`mdi-lightbulb`); the commit's
        // busy pieces land BeerCSS's `.shape.loading-indicator` on it, so its
        // glyph becomes a spinner for the whole in-flight window.
        val badge = page.locator(".slider-icon")
        def busy: IO[Boolean] =
          IO.blocking(
            wrapper
              .evaluate("el => el.classList.contains('fh-disabled')")
              .asInstanceOf[Boolean]
          )
        def disabled: IO[Boolean] = IO.blocking(slider.isDisabled())
        def badgeSpinning: IO[Boolean] =
          IO.blocking(
            badge
              .evaluate("el => el.classList.contains('loading-indicator')")
              .asInstanceOf[Boolean]
          )
        for {
          _ <- IO.blocking(assert(!slider.isDisabled()))
          // Commit once: End jumps the thumb to `max` (255) and releases.
          _ <- IO.blocking(slider.focus())
          _ <- IO.blocking(slider.press("End"))
          _ <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
          // While the POST is held, the slider says busy and is frozen...
          _ <- eventually(busy)(identity)
          _ <- eventually(disabled)(identity)
          // ...and the badge icon spins for the same window.
          _ <- eventually(badgeSpinning)(identity)
          // ...and a second commit is a no-op, not a second call.
          _ <- IO.blocking(
            slider.evaluate(
              "el => el.dispatchEvent(new Event('change', {bubbles: true}))"
            )
          )
          _ <- IO.sleep(300.millis)
          during <- ts.fake.recordedCalls
          _ <- IO(assertEquals(during.size, 1))
          // The held response lands, and the slider wakes back up.
          _ <- eventually(busy)(b => !b)
          _ <- eventually(disabled)(d => !d)
          _ <- eventually(badgeSpinning)(s => !s)
        } yield ()
    }
  }

  test(
    "a busy-guarded element's icon becomes a spinner while its call is in flight"
  ) {
    // The slider's power button is `c.iconButton` — an `i.mdi` glyph AND the
    // busy pieces — so while its POST is held it takes BeerCSS's
    // `.shape.loading-indicator` and paints a morphing shape around the glyph.
    // The GLYPH is what earns the shape: a labelled button dims instead. The class IS the assertion
    // now: it is bound to the delayed signal `tap.pkl` derives, so its presence
    // already means "we decided to show this".
    withPage(
      Scene.of(SmokeDashboard.busyIcon),
      fakeConfig = FakeConfig(callDelay = 2.seconds)
    ) { (page, ts) =>
      val icon = page.locator(".slider-actions button")
      def spinning: IO[Boolean] =
        IO.blocking(
          icon
            .evaluate("el => el.classList.contains('loading-indicator')")
            .asInstanceOf[Boolean]
        )
      for {
        idle <- spinning
        _ <- IO(assert(!idle))
        // Click the power button: the toggle POST is held, so the button
        // shows busy — and the icon should be a spinner for the whole window.
        _ <- IO.blocking(icon.click())
        _ <- eventually(spinning)(identity)
        // The held response lands and the glyph comes back.
        _ <- eventually(spinning)(s => !s)
      } yield ()
    }
  }

  test("a rejected action surfaces a toast and clears busy") {
    // The fake's call_service RAISES; the server answers the action POST with
    // 400, and the shell's datastar-fetch listener turns that error into a
    // toast (the click-filter keeps the SSE stream's own errors out of it).
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
            .asInstanceOf[Boolean]
        )
      for {
        _ <- IO.blocking(toggle.click())
        _ <- IO.blocking(
          assertThat(page.locator(".fh-toast")).hasText("Command failed (400)")
        )
        // `finished` fires even on a rejected fetch, so busy clears here too —
        // an error must not leave the button stuck in the guarded state.
        _ <- eventually(busy)(b => !b)
      } yield ()
    }
  }

  test("a light that only switches is pressable anywhere on its row") {
    // The property is REACH: that card has nothing to drag, so the whole row IS
    // the button and a press near its bottom edge must be a press. The click is
    // aimed by PAGE coordinates rather than at the locator, whose own click
    // aims at the centre — the centre stayed live throughout the bug this
    // covers (BeerCSS gives every `button` a fixed height, which
    // over-constrained the overlay's `inset:0` and left the target a strip
    // across the top of a taller card), so a centre click proves nothing.
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

  test("touch: a tap on a slider sets the value where the finger landed") {
    // A phone has no click to fall back on: the range input is
    // `pointer-events:none` on a coarse pointer (the CSS half of the
    // axis-intent gate), so the tap is the script's to interpret or nobody's.
    withPage(scene, touch = true) { (page, ts) =>
      for {
        // Both halves or neither — the CSS half is behind `(pointer:coarse)`,
        // and a touch event on a page still styled for a mouse would exercise a
        // combination no device has.
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
        // A quarter across a 1..255 axis is ~64. A WINDOW, not a number: the
        // value is a function of real pixels, so pinning it would fail on a
        // viewport change that broke nothing.
        assert(brightness.exists(b => b > 50 && b < 80), clue = calls)
      }
    }
  }
}
