package fh.view.smoke

import com.microsoft.playwright.{Browser, BrowserType, Playwright}
import com.microsoft.playwright.assertions.PlaywrightAssertions

import scala.compiletime.uninitialized
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Tags every test `Slow`, so `--exclude-tags=Slow` selects by what a suite is
  * rather than by a name list. Both `test` forms route through this overload.
  * Separate from [[BrowserSuite]]: a browser implies slow, not the reverse.
  */
trait SlowSuite extends munit.FunSuite {
  val Slow: munit.Tag = new munit.Tag("Slow")

  override def test(options: munit.TestOptions)(
      body: => Any
  )(using loc: munit.Location): Unit =
    super.test(options.tag(Slow))(body)
}

/** One headless Chromium per suite: contexts and pages are cheap, launching is
  * not. Only the lifecycle; what to do with the browser differs per suite.
  */
trait BrowserSuite extends munit.CatsEffectSuite with SlowSuite {

  private var playwright: Playwright = uninitialized
  protected var browser: Browser = uninitialized

  override def beforeAll(): Unit = {
    // sbt 2's persistent server keeps its start-time env, and the driver skips
    // its browser install only on `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD`, so it is
    // passed explicitly. The browser is preinstalled at this version's pinned
    // revision under `PLAYWRIGHT_BROWSERS_PATH` (ADR 0009).
    playwright = Playwright.create(
      new Playwright.CreateOptions().setEnv(
        (sys.env ++ Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1")).asJava
      )
    )
    browser = BrowserSuite.connectOrLaunch(playwright)
    // Playwright's 5s default: CI runs this beside three other steps on a
    // 2-core runner, and the failures there asserted things that had not
    // happened yet. A passing assertion returns as soon as it holds.
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

  /** `evaluate` returns `Object`. These fail naming the value that arrived,
    * where a cast says only "String cannot be cast to Boolean".
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

  /** Set by the agentbox wrapper to the sidecar's Playwright server; its
    * presence selects `connect` over `launch`.
    */
  val WsEndpointVar = "FH_PLAYWRIGHT_WS"

  /** Replaces Playwright's 5s default; see [[BrowserSuite.beforeAll]]. */
  val AssertionTimeout: FiniteDuration = 15.seconds

  /** For deterministic rendering in [[ComponentVisualSuite]]. Mirrored by the
    * sidecar's `launchServer` in `flake.nix`, since a connecting client cannot
    * send launch arguments: change both or neither.
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

  /** Every suite gets the same configuration: in the agentbox they share one
    * browser anyway.
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
