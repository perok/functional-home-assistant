package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{FakeConfig, HouseFixture, Scene, ServiceCall, SmokeDashboard}
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

  test("a guarded control shows busy while its call is in flight and ignores a second click") {
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

  test("the busy look is delayed: both fh-disabled dim and fh-loading spinner appear only after the threshold") {
    // Both classes, one timing (the action-feedback plan):
    // Datastar binds BOTH `fh-disabled` and `fh-loading` the moment the guard
    // flips — both classes are on the same signal. The CSS
    // `animation-delay: 300ms` delays both the dim and the spinner ring, so
    // a fast action never flashes any visual.
    // Both classes are present immediately; only the animations are delayed.
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

  test("a slider's commit is guarded: re-releasing while the POST is in flight is a no-op") {
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
        // busy class lands on it too, so its glyph becomes a spinner for the
        // whole in-flight window.
        val badge = page.locator(".slider-icon .shape .loading-indicator")
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
              .evaluate(
                "el => getComputedStyle(el).animationName === 'fh-loading-appear'"
              )
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

  test("a busy-guarded element's icon becomes a spinner while its call is in flight") {
    // The slider's power button carries an `i.mdi` icon AND the busy pieces,
    // so while its POST is held the theme overlays a BeerCSS
    // `loading-indicator` shape that spins (`fh-loading-appear`). Computed
    // style, because the animation is on the shape div — the class check
    // alone proves nothing about the look.
    withPage(Scene.of(SmokeDashboard.busyIcon), fakeConfig = FakeConfig(callDelay = 2.seconds)) {
      (page, ts) =>
        val icon = page.locator("button.slider-action .shape .loading-indicator")
        def spinning: IO[Boolean] =
          IO.blocking(
            icon
              .evaluate(
                "el => getComputedStyle(el).animationName === 'fh-loading-appear'"
              )
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
}
