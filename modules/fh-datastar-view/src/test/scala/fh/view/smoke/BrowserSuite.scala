package fh.view.smoke

import com.microsoft.playwright.{Browser, BrowserType, Playwright}

import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*

/** Tags every test in a mixing-in suite `Slow`, so `build.sbt`'s
  * `--exclude-tags=Slow` default selects them by what a suite IS rather than by
  * a suite-name string it would have to be kept in sync with. `test` routes both
  * the `String` and `TestOptions` forms through this single munit overload (the
  * `String` overload converts to `TestOptions` and calls this one).
  *
  * Kept separate from [[BrowserSuite]], which mixes it in: driving a browser
  * implies slow, but slow does not imply a browser, and a future slow suite that
  * needs no browser should be able to say so.
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
  }

  override def afterAll(): Unit = {
    if (browser != null) browser.close()
    if (playwright != null) playwright.close()
  }
}

object BrowserSuite {

  /** Set by the agentbox wrapper to the sidecar's Playwright server. Its
    * PRESENCE selects `connect` over `launch` — there is no second "am I in a
    * box" flag to keep consistent with it.
    */
  val WsEndpointVar = "FH_PLAYWRIGHT_WS"

  /** Chromium flags for deterministic rendering, so [[ComponentVisualSuite]]
    * compares like with like.
    *
    * Mirrored by the sidecar's `launchServer` call in `flake.nix`: a client that
    * connects cannot send launch arguments, so in the agentbox these apply only
    * because the server was started with them. Change both or neither.
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
    * Every suite gets the same browser configuration on purpose. In the agentbox
    * they share one browser and could not differ anyway, so a per-suite arg list
    * would only mean a suite behaved differently there than on the host.
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
