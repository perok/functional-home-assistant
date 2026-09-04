package fh.view.smoke

import com.microsoft.playwright.{Browser, BrowserType, Playwright}
import com.microsoft.playwright.assertions.PlaywrightAssertions

import scala.compiletime.uninitialized
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Tags every test in a mixing-in suite `Slow`, so `build.sbt`'s
  * `--exclude-tags=Slow` default selects them by what a suite IS rather than by
  * a suite-name string it would have to be kept in sync with. `test` routes
  * both the `String` and `TestOptions` forms through this single munit overload
  * (the `String` overload converts to `TestOptions` and calls this one).
  *
  * Kept separate from [[BrowserSuite]], which mixes it in: driving a browser
  * implies slow, but slow does not imply a browser, and a future slow suite
  * that needs no browser should be able to say so.
  */
trait SlowSuite extends munit.FunSuite {
  val Slow: munit.Tag = new munit.Tag("Slow")

  override def test(options: munit.TestOptions)(
      body: => Any
  )(using loc: munit.Location): Unit =
    super.test(options.tag(Slow))(body)
}

/** One headless Chromium per suite, held for its lifetime — page and context
  * creation off a live browser is cheap, launching one is not.
  *
  * This is the whole browser lifecycle and nothing else: what to DO with the
  * browser differs per suite ([[SmokeSuite]] serves a dashboard and drives it;
  * `DatastarMorphContractSuite` deliberately serves a bare page it fully
  * controls), and that is exactly the part that does not belong here.
  */
trait BrowserSuite extends munit.CatsEffectSuite with SlowSuite {

  private var playwright: Playwright = uninitialized
  protected var browser: Browser = uninitialized

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
    browser = BrowserSuite.connectOrLaunch(playwright)
    // Playwright's own default is 5s, which is a budget for a fast machine
    // running one thing. CI runs this suite alongside three other build steps
    // on a 2-core runner, and the assertions that failed there were not
    // asserting anything false — they were asserting something that had not
    // happened YET. A passing run costs nothing extra (a locator assertion
    // returns as soon as it holds); only a genuine failure waits longer, and
    // `SmokeSuite.withPage`'s own timeout still bounds the test.
    PlaywrightAssertions.setDefaultAssertionTimeout(
      BrowserSuite.AssertionTimeout.toMillis.toDouble
    )
  }

  override def afterAll(): Unit = {
    if (browser != null) browser.close()
    if (playwright != null) playwright.close()
  }
}

object BrowserSuite {

  /** Playwright's `evaluate` returns `Object` — the JS value marshalled into
    * whatever Java type it maps to — so reading one is where a browser test has
    * to say what it expects.
    *
    * These say it once each, with a failure that names the value that arrived.
    * The alternative at every call site is a cast whose `ClassCastException`
    * says only "String cannot be cast to Boolean", which in a suite that
    * evaluates a dozen expressions is not enough to find WHICH one.
    */
  extension (evaluated: Object) {
    def asJsBoolean: Boolean = evaluated match {
      case b: java.lang.Boolean => b.booleanValue
      case other => throw new AssertionError(s"expected a boolean, got: $other")
    }

    def asJsInt: Int = evaluated match {
      case i: java.lang.Integer => i.intValue
      case other => throw new AssertionError(s"expected an int, got: $other")
    }

    def asJsStrings: List[String] = evaluated match {
      case l: java.util.List[?] => l.asScala.toList.map(String.valueOf)
      case other => throw new AssertionError(s"expected a list, got: $other")
    }
  }

  /** Set by the agentbox wrapper to the sidecar's Playwright server. Its
    * PRESENCE selects `connect` over `launch` — there is no second "am I in a
    * box" flag to keep consistent with it.
    */
  val WsEndpointVar = "FH_PLAYWRIGHT_WS"

  /** How long a retrying locator assertion waits for the DOM to agree with it,
    * replacing Playwright's 5s default — see [[BrowserSuite.beforeAll]].
    */
  val AssertionTimeout: FiniteDuration = 15.seconds

  /** Chromium flags for deterministic rendering, so [[ComponentVisualSuite]]
    * compares like with like.
    *
    * Mirrored by the sidecar's `launchServer` call in `flake.nix`: a client
    * that connects cannot send launch arguments, so in the agentbox these apply
    * only because the server was started with them. Change both or neither.
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
    * Every suite gets the same browser configuration on purpose. In the
    * agentbox they share one browser and could not differ anyway, so a
    * per-suite arg list would only mean a suite behaved differently there than
    * on the host.
    */
  def connectOrLaunch(playwright: Playwright): Browser =
    sys.env.get(WsEndpointVar) match {
      case Some(ws) => playwright.chromium().connect(ws)
      case None     =>
        playwright
          .chromium()
          .launch(
            new BrowserType.LaunchOptions()
              .setHeadless(true)
              .setArgs(browserArgs.asJava)
          )
    }
}
