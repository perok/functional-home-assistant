package fh.view.smoke

import cats.effect.{IO, Resource}
import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}
import com.microsoft.playwright.options.ViewportSize
import fh.view.runtime.TestServer
import fh.view.testkit.{FakeConfig, Scene}

import scala.compiletime.uninitialized
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Every browser-driven smoke suite is Playwright-driven — the slowest part of
  * the test suite (issue #109 item 3). Mixed into [[SmokeSuite]] AND standalone
  * suites like `DatastarMorphContractSuite` that don't extend it, so tagging is
  * a property of what a suite IS rather than a suite-name string `build.sbt`
  * has to be kept in sync with. `test` routes both the `String` and
  * `TestOptions` forms through this single munit overload (the `String`
  * overload converts to `TestOptions` and calls this one), so every test
  * declared in a mixing-in suite picks up the tag without the suite doing
  * anything.
  */
trait SlowSuite extends munit.FunSuite {
  val Slow: munit.Tag = new munit.Tag("Slow")

  override def test(options: munit.TestOptions)(
      body: => Any
  )(using loc: munit.Location): Unit =
    super.test(options.tag(Slow))(body)
}

/** Base for the browser smoke suites (ADR 0009): one Playwright + headless
  * Chromium per suite (cheap page creation off the shared browser), a fresh
  * bound [[TestServer]] + `BrowserContext`/[[Page]] per test — so recorded
  * calls, seeded state, and ui state (the tabs selection) never bleeds between
  * tests — navigated to the dashboard under test. Every [[withPage]] call fails
  * the test on any browser console `error`: a silent JS exception (a wrong
  * `data-on:click` selector, a dropped SSE continuation line) is exactly the
  * class of bug a wire-level test can't see — that's the whole reason this
  * suite exists.
  */
abstract class SmokeSuite extends munit.CatsEffectSuite with SlowSuite {

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
    // `executablePath` needed (ADR 0009).
    playwright = Playwright.create(
      new Playwright.CreateOptions().setEnv(
        (sys.env ++ Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1")).asJava
      )
    )
    browser = SmokeSuite.connectOrLaunch(playwright, SmokeSuite.browserArgs)
  }

  override def afterAll(): Unit = {
    if (browser != null) browser.close()
    if (playwright != null) playwright.close()
  }

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
    * so a missed assertion fails fast rather than hanging the suite.
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
      fakeConfig: FakeConfig = FakeConfig()
  )(
      f: (Page, TestServer) => IO[A]
  ): IO[A] = {
    val pageErrors = collection.mutable.Buffer.empty[String]
    val contextOptions = new Browser.NewContextOptions()
    viewport.foreach { case (w, h) =>
      contextOptions.setViewportSize(new ViewportSize(w, h))
    }
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
      .timeout(45.seconds)
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

object SmokeSuite {

  /** Set by the agentbox wrapper to the sidecar's Playwright server. Its
    * PRESENCE selects `connect` over `launch` — there is no second "am I in a
    * box" flag to keep consistent with it.
    */
  val WsEndpointVar = "FH_PLAYWRIGHT_WS"

  /** Chromium flags for deterministic rendering, so `ComponentVisualSuite`
    * compares like with like.
    *
    * Mirrored by the sidecar's `launchServer` call in `flake.nix`: `connect`
    * has no way to send launch arguments, so the box gets these only because
    * the server was started with them. Change both or neither.
    *
    * https://github.com/microsoft/playwright/issues/8161#issuecomment-3643962063
    */
  val browserArgs: List[String] = List(
    "--disable-gpu",
    "--disable-font-subpixel-positioning",
    "--disable-lcd-text",
    "--disable-threaded-animation",
    "--disable-threaded-scrolling",
    "--disable-in-process-stack-traces",
    "--disable-checker-imaging",
    "--force-color-profile=srgb"
  )

  /** Connect to the agentbox sidecar when [[WsEndpointVar]] is set, else launch
    * a browser locally — which is what CI and a plain checkout do.
    *
    * `launchArgs` reach only the launch path. On the connect path the server
    * already started the browser, so every suite in the box shares one that was
    * launched with [[browserArgs]] regardless of what it asks for here.
    */
  def connectOrLaunch(
      playwright: Playwright,
      launchArgs: List[String]
  ): Browser =
    sys.env.get(WsEndpointVar) match {
      case Some(ws) => playwright.chromium().connect(ws)
      case None     =>
        playwright
          .chromium()
          .launch(
            new BrowserType.LaunchOptions()
              .setHeadless(true)
              .setArgs(launchArgs.asJava)
          )
    }
}
