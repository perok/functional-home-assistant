package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{FixtureDashboard, HouseFixture, Scene, SmokeDashboard}

/** '''How many times the browser OPENS the stream.'''
  *
  * The one thing no server-side test can see. A reconnect is the browser's
  * decision — `NS_BINDING_ABORTED` in Firefox is the client aborting its own
  * request — so from the server the third connection looks exactly like the
  * first. Reported from the running app: a new GET on the SSE url after ordinary
  * state changes, which if real means every live update costs a reconnect (and,
  * with it, a resume) instead of a patch.
  *
  * '''As of writing it does NOT reproduce here''', and that is a finding rather
  * than a gap: it places the cause outside what these fixtures exercise. What
  * they do not have is a proxy or an extension in the path, HTTP/2, a real HA
  * feed that can drop, a file watcher swapping renderers underneath, or Firefox
  * (Playwright drives Chromium here). Each is now a candidate that can be added
  * to this suite and ruled in or out, instead of being argued about.
  *
  * The point of keeping them green is that they are a REGRESSION guard on the
  * cheapest property the design has: a live update costs a patch, never a
  * reconnect — and a reconnect drags a resume behind it.
  */
class ConnectionSmokeSuite extends SmokeSuite {

  /** Count fetches from inside the page: all of them, and the SSE ones.
    *
    * Two instruments were tried and rejected first, which is worth recording so
    * they are not tried again. Resource Timing records an entry when a response
    * COMPLETES, and a live SSE stream never completes — the connection under
    * investigation is exactly the one that never appears there. Playwright's
    * `page.onRequest` did not fire at all under this setup.
    *
    * Wrapping `fetch` counts the request when it is ISSUED, which is the moment
    * that matters: a reconnect Datastar starts and then aborts still counts.
    * Installed after first paint, so what it measures is precisely "did the
    * browser open the stream AGAIN".
    */
  private def watchFetches(page: Page): IO[Unit] = IO.blocking {
    val _ = page.evaluate(
      """window.__fhAll = 0; window.__fhSse = 0;
        |const inner = window.fetch;
        |window.fetch = function (...args) {
        |  const url = String(args[0] && args[0].url ? args[0].url : args[0]);
        |  window.__fhAll++;
        |  if (url.includes('/patch')) window.__fhSse++;
        |  return inner.apply(this, args);
        |};""".stripMargin
    )
  }

  private def counts(page: Page): IO[(Int, Int)] =
    IO.blocking(
      (
        page.evaluate("window.__fhAll").asInstanceOf[Int],
        page.evaluate("window.__fhSse").asInstanceOf[Int]
      )
    )

  test("the fetch counter fires — so a zero elsewhere is a measurement") {
    // The tests below assert ZERO reopens, and a zero that can never be non-zero
    // is worth nothing. A control tap issues its own fetch, which proves the
    // wrapper is installed and counting before anything leans on it.
    withPage(Scene.of(SmokeDashboard.dashboard)) { (page, ts) =>
      for {
        _ <- ts.awaitLive()
        _ <- watchFetches(page)
        before <- counts(page)
        _ <- IO.blocking(page.getByText("Toggle Kitchen").click())
        after <- eventually(counts(page))(_._1 > before._1)
        // Fetches ARE counted — so a zero on the SSE counter below is a
        // measurement rather than a broken probe...
        _ = assert(after._1 > before._1, clue = (before, after))
        // ...and a control tap is not one of the things that reopens the stream.
        _ = assertEquals(after._2, 0, clue = "an action tap must not reconnect")
      } yield ()
    }
  }

  test("a live state change does not reopen the stream") {
    withPage(Scene.of(SmokeDashboard.dashboard)) { (page, ts) =>
      for {
        _ <- ts.awaitLive()
        _ <- watchFetches(page)
        _ <- ts.fake.emit(
          HouseFixture.outsideTemp.entityId,
          "13.1",
          HouseFixture.outsideTemp.attributes
        )
        _ <- IO.blocking(assertThat(page.locator("body")).containsText("13.1"))
        _ <- ts.fake.emit(
          HouseFixture.outsideTemp.entityId,
          "13.2",
          HouseFixture.outsideTemp.attributes
        )
        _ <- IO.blocking(assertThat(page.locator("body")).containsText("13.2"))
        sse <- counts(page).map(_._2)
        // A patched update must never cost a reconnect: the resume that follows
        // one is the expensive path this whole design exists to avoid taking
        // routinely.
        _ = assertEquals(sse, 0, clue = "stream reopened during live updates")
      } yield ()
    }
  }

  test("the connection survives a state-group flip, there and back") {
    // THE reported case: the reconnects were seen on `pkl-if`, when the If's
    // condition changed. A flip is the one update that patches a MOUNT rather
    // than morphing a node, so it gets its own test at the same granularity.
    val scene = Scene
      .of(
        FixtureDashboard.ifElse(
          condEntity = HouseFixture.kitchenLight.entityId,
          activeState = "on",
          thenBranch = FixtureDashboard.reading(HouseFixture.outsideTemp),
          elseBranch =
            FixtureDashboard.light("Kitchen", HouseFixture.kitchenLight)
        )
      )
      .entity(HouseFixture.kitchenLight)

    withPage(scene) { (page, ts) =>
      def flip(to: String) =
        ts.fake.emit(
          HouseFixture.kitchenLight.entityId,
          to,
          HouseFixture.kitchenLight.attributes
        )
      for {
        _ <- ts.awaitLive()
        _ <- watchFetches(page)
        _ <- flip("off")
        _ <- IO.blocking(
          assertThat(page.locator("body")).containsText("Kitchen")
        )
        afterFirst <- counts(page).map(_._2)
        _ = assertEquals(afterFirst, 0, clue = "flip away reopened the stream")
        _ <- flip("on")
        _ <- IO.blocking(
          assertThat(page.locator("body"))
            .containsText(HouseFixture.outsideTemp.state)
        )
        afterBack <- counts(page).map(_._2)
        _ = assertEquals(afterBack, 0, clue = "flip back reopened the stream")
      } yield ()
    }
  }
}
