package fh.view.smoke

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}
import com.microsoft.playwright.options.ViewportSize
import fh.view.model.Dashboard
import fh.view.runtime.TestServer
import fh.view.testkit.FixtureEntity

import scala.compiletime.uninitialized
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Base for the browser smoke suites (`docs/plan-playwright-smoke-tests.md`):
  * one Playwright + headless Chromium per suite (cheap page creation off the
  * shared browser), a fresh bound [[TestServer]] + `BrowserContext`/[[Page]]
  * per test — so recorded calls, seeded state, and cookies (the tabs restore
  * cookie) never bleed between tests — navigated to the dashboard under test.
  * Every [[withPage]] call fails the test on any browser console `error`: a
  * silent JS exception (a wrong `data-on:click` selector, a dropped SSE
  * continuation line) is exactly the class of bug a wire-level test can't see —
  * that's the whole reason this suite exists.
  */
abstract class SmokeSuite extends munit.FunSuite {

  private var playwright: Playwright = uninitialized
  private var browser: Browser = uninitialized

  override def beforeAll(): Unit = {
    // The sbt server's own env predates this session's `PLAYWRIGHT_*` vars
    // (sbt 2.0's persistent server keeps its start-time env), and the Java
    // driver only skips its own browser install when it sees
    // `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` — so it's passed explicitly here
    // rather than relied on from the process environment. The browser is
    // preinstalled at this Playwright version's pinned revision (see the GHA
    // `playwright install` step / `PLAYWRIGHT_BROWSERS_PATH`), so the driver
    // resolves its own executable under that path — no explicit
    // `executablePath` needed (`docs/plan-playwright-smoke-tests.md`).
    playwright = Playwright.create(
      new Playwright.CreateOptions().setEnv(
        (sys.env ++ Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1")).asJava
      )
    )
    browser = playwright
      .chromium()
      .launch(new BrowserType.LaunchOptions().setHeadless(true))
  }

  override def afterAll(): Unit = {
    if (browser != null) browser.close()
    if (playwright != null) playwright.close()
  }

  /** Serve `dashboard`/`entities` on a freshly bound [[TestServer]], open a
    * fresh `BrowserContext`/[[Page]] against it, navigate to the dashboard, and
    * run `f` with the [[Page]] and the [[TestServer]] (for `fake.emit` / the
    * SSE-subscriber readiness gates — the browser opens its OWN SSE connection,
    * so a test that emits a change still must await it, exactly as
    * [[TestServer.observePatch]] does for the HTTP-body-stream suites).
    * Everything is released after; a global timeout so a missed assertion fails
    * fast rather than hanging the suite.
    *
    * Fails on any uncaught JS exception ([[Page.onPageError]]) — a wrong
    * `data-on:click` selector or a dropped SSE continuation line surfaces
    * exactly there. NOT gated on console "error"-level messages: those also
    * cover benign failed-resource-load logs (e.g. a decorative BeerCSS
    * sub-resource the CDN 404s, or the browser's own favicon probe), which
    * would make this suite noisy rather than meaningful.
    */
  def withPage[A](
      dashboard: Dashboard,
      entities: List[FixtureEntity],
      viewport: Option[(Int, Int)] = None
  )(
      f: (Page, TestServer) => IO[A]
  ): A = {
    val pageErrors = collection.mutable.Buffer.empty[String]
    val contextOptions = new Browser.NewContextOptions()
    viewport.foreach { case (w, h) =>
      contextOptions.setViewportSize(new ViewportSize(w, h))
    }
    val resource = for {
      served <- TestServer.served(dashboard, entities)
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

    val result =
      resource
        .use { case (p, ts) => f(p, ts) }
        .timeout(45.seconds)
        .unsafeRunSync()
    assert(pageErrors.isEmpty, clue = pageErrors.toList)
    result
  }

  /** Poll `io` until `cond` holds, or fail after `timeout`. The
    * [[fh.view.testkit.FakeHomeAssistant.recordedCalls]] equivalent of a
    * retrying Playwright locator assertion — for asserting on something that
    * isn't itself a DOM state (a control click's resulting service call), so
    * there's no `assertThat(locator)` to lean on.
    */
  def eventually[A](
      io: IO[A],
      timeout: FiniteDuration = 5.seconds,
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
