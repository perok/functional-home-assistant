package fh.view.smoke

import cats.effect.{IO, Resource}
import com.microsoft.playwright.{Browser, Page}
import com.microsoft.playwright.options.{ServiceWorkerPolicy, ViewportSize}
import fh.view.runtime.TestServer
import fh.view.testkit.{FakeConfig, Scene}

import scala.concurrent.duration.*

/** Base for the browser smoke suites (ADR 0009): a dashboard served by a
  * freshly bound [[TestServer]] and driven through a fresh
  * `BrowserContext`/[[Page]] per test — so recorded calls, seeded state, and ui
  * state (the tabs selection) never bleeds between tests.
  *
  * The browser itself belongs to [[BrowserSuite]]; what this adds is the served
  * dashboard. Every [[withPage]] call fails the test on any browser console
  * `error`: a silent JS exception (a wrong `data-on:click` selector, a dropped
  * SSE continuation line) is exactly the class of bug a wire-level test can't
  * see — that's the whole reason this suite exists.
  */
abstract class SmokeSuite extends BrowserSuite {

  /** ONE page, held open for the test — see ADR 0009 §4 "Known gap": no smoke
    * suite has two browsers on a dashboard at once, and none drops and reopens
    * an SSE stream, so anything that only goes wrong on a reconnect or between
    * two clients is invisible here (it has already hidden two real bugs).
    *
    * Serve `scene`'s dashboard — seeded with the entities it references (plus
    * any `.entity(...)` extras), auto-derived by the [[Scene]] builder so the
    * served world can't drift from the dashboard — on a freshly bound
    * [[TestServer]], open a fresh `BrowserContext`/[[Page]] against it,
    * navigate to the dashboard, and run `f` with the [[Page]] and the
    * [[TestServer]] (for `fake.emit` / the SSE-subscriber readiness gates — the
    * browser opens its OWN SSE connection, so a test that emits a change still
    * must await it, exactly as [[TestServer.observePatch]] does for the
    * HTTP-body-stream suites). Everything is released after; a global timeout
    * so a test that hangs outright still ends the suite. It is deliberately
    * several times [[BrowserSuite.AssertionTimeout]]: a test makes a handful of
    * retrying assertions in sequence, and this bound exists to catch a hang,
    * not to be the thing that decides a failure.
    *
    * Fails on any uncaught JS exception ([[Page.onPageError]]) — a wrong
    * `data-on:click` selector or a dropped SSE continuation line surfaces
    * exactly there. NOT gated on console "error"-level messages: those also
    * cover benign failed-resource-load logs (e.g. a decorative BeerCSS
    * sub-resource the CDN 404s, or the browser's own favicon probe), which
    * would make this suite noisy rather than meaningful.
    */
  def withPage[A](
      scene: Scene,
      viewport: Option[(Int, Int)] = None,
      // The [[FakeConfig]] knobs for THIS test's fake — a delayed or failing
      // `call_service`, for the guarded-action feedback tests.
      fakeConfig: FakeConfig = FakeConfig(),
      // A phone rather than a desktop: enables `page.touchscreen()` AND flips
      // the `(pointer:coarse)` media query, which is the half of the slider's
      // touch gate that lives in CSS. Both or neither — a touch event on a
      // page still styled for a mouse would exercise a combination no device
      // has.
      touch: Boolean = false
  )(
      f: (Page, TestServer) => IO[A]
  ): IO[A] = {
    val pageErrors = collection.mutable.Buffer.empty[String]
    val contextOptions = new Browser.NewContextOptions()
    // No service worker, because none of these suites is about the PWA and a
    // live worker is a second actor in every one of them: `fhRegisterSw` runs
    // on localhost (a secure context), the worker claims the page mid-test, and
    // from then on the page has a fetch path the test never set up. Playwright
    // offers this knob for exactly that reason. `ServerRoutesSuite` still
    // covers `/sw.js` at the wire level, so nothing is left untested.
    contextOptions.setServiceWorkers(ServiceWorkerPolicy.BLOCK)
    viewport.foreach { case (w, h) =>
      contextOptions.setViewportSize(new ViewportSize(w, h))
    }
    if (touch) { val _ = contextOptions.setHasTouch(true) }
    val resource = for {
      served <- TestServer.served(scene.dashboard, scene.entities, fakeConfig)
      (ts, uri) = served
      context <- Resource.make(IO.blocking(browser.newContext(contextOptions)))(
        c => IO.blocking(c.close())
      )
      page <- Resource.make(IO.blocking(context.newPage()))(p =>
        IO.blocking(p.close())
      )
      _ <- Resource.eval(IO.blocking(page.onPageError { err =>
        pageErrors += err
      }))
      _ <- Resource.eval(IO.blocking(page.navigate(uri.renderString)))
    } yield (page, ts)

    resource
      .use { case (p, ts) => f(p, ts) }
      .timeout(90.seconds)
      .flatTap(_ => IO(assert(pageErrors.isEmpty, clue = pageErrors.toList)))
  }

  /** Poll `io` until `cond` holds, or fail after `timeout`. The
    * [[fh.view.testkit.FakeHomeAssistant.recordedCalls]] equivalent of a
    * retrying Playwright locator assertion — for asserting on something that
    * isn't itself a DOM state (a control click's resulting service call), so
    * there's no `assertThat(locator)` to lean on.
    */
  def eventually[A](
      io: IO[A],
      timeout: FiniteDuration = BrowserSuite.AssertionTimeout,
      interval: FiniteDuration = 20.millis
  )(cond: A => Boolean): IO[A] =
    fs2.Stream
      .repeatEval(io <* IO.sleep(interval))
      .filter(cond)
      .head
      .compile
      .lastOrError
      .timeout(timeout)

  /** Quiesce a page before a screenshot ([[ComponentVisualSuite]]): wait for
    * web fonts (the vendored Material Symbols glyphs) to finish loading, and
    * kill CSS transitions/animations so a screenshot can never land
    * mid-transition — the two sources of screenshot-to-screenshot noise a
    * byte-identity snapshot can't tolerate.
    */
  def settle(page: Page): Unit = {
    page.evaluate("document.fonts.ready")
    page.addStyleTag(
      new Page.AddStyleTagOptions().setContent(
        "*,*::before,*::after{transition:none!important;animation:none!important;caret-color:transparent!important}"
      )
    )
    ()
  }
}
