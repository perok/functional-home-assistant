package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{FixtureDashboard, HouseFixture, Scene}

import java.net.URLDecoder
import scala.concurrent.duration.*

/** SPIKE (docs/adr/0011-the-live-connection.md, proof point 1): does a SERVER-PUSHED signal
  * ride the reconnect URL? `conn` is already pushed via `patchSignals` on every
  * connect, so the question needs no new production code — if the retry request
  * carries the previous connection's `conn`, a cursor pushed the same way will
  * come back the same way.
  */
class ResumeSpikeSuite extends SmokeSuite {

  test("a server-pushed signal comes back on the retry URL") {
    withPage(Scene.of(FixtureDashboard.dashboard)) { (page, ts) =>
      val urls = collection.mutable.Buffer.empty[(Long, String)]
      def seen = IO(urls.synchronized(urls.toList))
      val t0 = System.currentTimeMillis()
      for {
        _ <- IO.blocking(page.onRequest { r =>
          if (r.url().contains("/patch"))
            urls.synchronized(
              urls += ((System.currentTimeMillis() - t0, r.url()))
            )
        })
        _ <- ts.awaitLive()
        // `awaitLive` gates on the SERVER's subscriber, which can be satisfied
        // before the browser has applied anything. Push a value and wait for it
        // in the DOM: that proves this page's stream is live, so the `conn`
        // signal pushed ahead of it has landed too.
        _ <- ts.fake.emit(
          HouseFixture.outsideTemp.entityId,
          "13.1",
          HouseFixture.outsideTemp.attributes
        )
        _ <- IO.blocking(
          assertThat(page.locator("body")).containsText("13.1")
        )
        _ <- seen.flatMap(s => IO.println(s"[spike] at load: ${s.size}"))
        // The case resume targets: backgrounding a phone tab. Datastar's
        // handler aborts the fetch on `visibilitychange` when `document.hidden`,
        // and refetches when it goes visible again — so drive exactly that.
        before <- seen.map(_.size)
        _ <- IO.println(
          s"[spike] --- hiding at ${System.currentTimeMillis() - t0}ms"
        )
        _ <- IO.blocking(page.evaluate(ResumeSpikeSuite.setHidden(true)))
        _ <- IO.sleep(1.second)
        _ <- IO.blocking(page.evaluate(ResumeSpikeSuite.setHidden(false)))
        _ <- eventually(seen, timeout = 20.seconds)(_.size > before).attempt
        got <- seen
        _ <- IO.println(
          got.zipWithIndex
            .map((r, i) =>
              s"[spike] request $i @${r._1}ms: ${URLDecoder.decode(r._2, "UTF-8")}"
            )
            .mkString("\n")
        )
        _ <- IO(
          assert(got.size > before, clue = s"no refetch observed (had $before)")
        )
      } yield ()
    }
  }
}

object ResumeSpikeSuite {

  /** Fake the document's visibility, since Playwright exposes no control for
    * it. Datastar reads `document.hidden` inside its `visibilitychange`
    * handler, so overriding the getter and dispatching the event drives the
    * real code path.
    */
  def setHidden(hidden: Boolean): String =
    s"""(() => {
       |  Object.defineProperty(document, 'hidden',
       |    { configurable: true, get: () => $hidden });
       |  Object.defineProperty(document, 'visibilityState',
       |    { configurable: true, get: () => '${
        if (hidden) "hidden"
        else "visible"
      }' });
       |  document.dispatchEvent(new Event('visibilitychange'));
       |})()""".stripMargin
}
