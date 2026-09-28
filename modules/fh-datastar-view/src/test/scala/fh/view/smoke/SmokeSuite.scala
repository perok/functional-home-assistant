package fh.view.smoke

import cats.effect.{IO, Resource}
import com.microsoft.playwright.{Browser, Page}
import com.microsoft.playwright.options.{ServiceWorkerPolicy, ViewportSize}
import fh.view.runtime.TestServer
import fh.view.testkit.{FakeConfig, Scene}
import org.http4s.Uri

import scala.concurrent.duration.*

/** Base for the browser smoke suites (ADR 0009): a freshly bound [[TestServer]]
  * and a fresh page per test, so nothing bleeds between tests. A silent JS
  * exception (a wrong `data-on:click` selector, a dropped SSE continuation
  * line) is the bug class a wire-level test cannot see, so any uncaught one
  * fails the test.
  */
abstract class SmokeSuite extends BrowserSuite {

  /** Serve `scene` (seeded with the entities it references, so the world cannot
    * drift from the dashboard), open a page on it and run `f`. The browser
    * opens its own SSE connection, so a test that emits must still await it.
    * The timeout is several [[BrowserSuite.AssertionTimeout]]s: it catches a
    * hang, it does not decide a failure.
    *
    * Fails on [[Page.onPageError]], not on console errors, which include benign
    * resource 404s such as a CDN sub-resource or the favicon probe.
    *
    * ADR 0009's known gap: no smoke suite has two browsers on a dashboard, so
    * anything that only goes wrong between two clients is invisible here.
    */
  def withPage[A](
      scene: Scene,
      viewport: Option[(Int, Int)] = None,
      fakeConfig: FakeConfig = FakeConfig(),
      // A phone: `page.touchscreen()` and the `(pointer:coarse)` query
      // together, since a touch on a page styled for a mouse is a combination
      // no device has.
      touch: Boolean = false
  )(
      f: (Page, TestServer) => IO[A]
  ): IO[A] =
    withPageOn(
      TestServer.served(scene.dashboard, scene.entities, fakeConfig),
      viewport,
      touch
    )(f)

  def withPageOn[A](
      served: Resource[IO, (TestServer, Uri)],
      viewport: Option[(Int, Int)] = None,
      touch: Boolean = false
  )(
      f: (Page, TestServer) => IO[A]
  ): IO[A] = {
    val pageErrors = collection.mutable.Buffer.empty[String]
    val contextOptions = new Browser.NewContextOptions()
    // `fhRegisterSw` runs on localhost, a secure context, so a live worker
    // would claim the page mid-test with a fetch path nobody set up.
    // `ServerRoutesSuite` covers `/sw.js` on the wire.
    contextOptions.setServiceWorkers(ServiceWorkerPolicy.BLOCK)
    viewport.foreach { case (w, h) =>
      contextOptions.setViewportSize(new ViewportSize(w, h))
    }
    if (touch) { val _ = contextOptions.setHasTouch(true) }
    val resource = for {
      bound <- served
      (ts, uri) = bound
      context <- Resource.make(IO.blocking(browser.newContext(contextOptions)))(
        c => IO.blocking(c.close())
      )
      page <- Resource.make(IO.blocking(context.newPage()))(p =>
        IO.blocking(p.close())
      )
      _ <- Resource.eval(IO.blocking(page.onPageError { err =>
        pageErrors += err
      }))
      _ <- Resource.eval(IO.blocking {
        if (sys.env.contains("FH_SMOKE_TRACE_URL")) {
          val _ = page.addInitScript(
            """window.__rs = [];
              |const o = history.replaceState.bind(history);
              |history.replaceState = (a,b,u) => { window.__rs.push(String(u)); return o(a,b,u); };
              |""".stripMargin
          )
        }
      })
      _ <- Resource.eval(IO.blocking {
        val rate = sys.env.getOrElse("FH_SMOKE_CPU_THROTTLE", "0").toDouble
        if (rate > 1.0) {
          val cdp = page.context().newCDPSession(page)
          val p = new com.google.gson.JsonObject()
          p.addProperty("rate", rate)
          val _ = cdp.send("Emulation.setCPUThrottlingRate", p)
        }
      })
      _ <- Resource.eval(IO.blocking(page.navigate(uri.renderString)))
    } yield (page, ts)

    resource
      .use { case (p, ts) => f(p, ts) }
      .timeout(90.seconds)
      .flatTap(_ => IO(assert(pageErrors.isEmpty, clue = pageErrors.toList)))
  }

  /** For what is not a DOM state, such as a recorded service call. */
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

  /** Wait for what a click should cause, and fail the moment the control says
    * it was refused. Otherwise a test burns its timeout and reports "never saw
    * X", with the cause layers away.
    *
    * A refusal lands on the pressed control with HA's message (ADR 0019/0024).
    * The refusal watcher has no timeout of its own, so the race is decided by
    * what actually happens. A test about refusal wants the plain `fh-error`
    * assertion instead.
    */
  def awaitAction[A](control: com.microsoft.playwright.Locator)(
      outcome: IO[A]
  ): IO[A] = {
    // The control holds the state and the toast the words, from one refusal
    // frame.
    val refused: IO[String] =
      fs2.Stream
        .repeatEval(
          IO.blocking(
            control.evaluate(
              """el => el.classList.contains('fh-error')
                |  ? ((el.dataset.fhNode || '?') + ': ' +
                |     (document.querySelector('.fh-toast')?.textContent || 'no message'))
                |  : null""".stripMargin
            )
          ).map(Option(_).map(_.toString)) <* IO.sleep(20.millis)
        )
        .unNone
        .head
        .compile
        .lastOrError

    IO.race(outcome, refused).flatMap {
      case Left(a)    => IO.pure(a)
      case Right(why) =>
        IO.raiseError(
          new AssertionError(
            s"the action was REFUSED, so what this test waited for was never " +
              s"going to happen — $why"
          )
        )
    }
  }

  /** Wait for every stylesheet and its fonts, and kill transitions, before a
    * screenshot ([[ComponentVisualSuite]]).
    *
    * `document.fonts.ready` alone is not enough: a deferred sheet
    * (`Theme.deferredStylesheets`) arrives as a preload swapped to a stylesheet
    * `onload`, and until then its `@font-face` is not in the font set.
    * `full-dashboard.png` failed CI once with the icons missing.
    */
  def settle(page: Page): Unit = {
    page.waitForFunction(
      """() => !document.querySelector('link[rel="preload"][as="style"]')"""
    )
    // Forces the recalc the swap queued, so its font load is pending when
    // `ready` is asked.
    page.evaluate(
      "() => { document.body.offsetHeight; return document.fonts.ready }"
    )
    page.addStyleTag(
      new Page.AddStyleTagOptions().setContent(
        "*,*::before,*::after{transition:none!important;animation:none!important;caret-color:transparent!important}"
      )
    )
    ()
  }
}
