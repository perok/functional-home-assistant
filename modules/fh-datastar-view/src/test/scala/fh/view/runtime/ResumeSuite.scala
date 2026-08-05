package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  DynamicCase,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.headers.{`Cache-Control`, `If-None-Match`, ETag}
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** Resume on reconnect (ADR 0011).
  *
  * The failure mode is SILENT — the server believes the browser is current and
  * suppresses the patch, so a wrong resume shows stale values indefinitely.
  * Each test therefore asserts BOTH what the client gets and whether the
  * full-body repaint (`selector #dashboard`) was used.
  */
class ResumeSuite extends ServerHarness {

  // ---------------------------------------------------------------------------
  // Resume on reconnect (ADR 0011)
  //
  // The failure mode is SILENT — the server believes the browser is current and
  // suppresses the patch, so a wrong resume shows stale values indefinitely. Each
  // test below therefore asserts BOTH what the client gets and that the full-body
  // repaint (`selector #dashboard`) was or was not used.
  // ---------------------------------------------------------------------------

  test("cursorOf reads the resume cursor off the datastar signal param") {
    def req(q: String): Request[IO] =
      Request[IO](
        Method.GET,
        uri"/sse/dashboard/d/patch".withQueryParam("datastar", q)
      )
    // Nested under `_cursor`: `_`-prefixed so Datastar's default request filter
    // keeps it off every request but the SSE GET, which asks for it back.
    assertEquals(
      Server.cursorOf(
        req(
          """{"_cursor":{"headHash":"h1","styleHash":"s1",""" +
            """"logId":"L1","storeVersion":7}}"""
        )
      ),
      Some(Server.Cursor("h1", "s1", "L1", 7L))
    )
    // A first load carries only the signals the page declared — no cursor.
    assertEquals(Server.cursorOf(req("""{"conn":"c","haDown":false}""")), None)
    // Partial, garbled, and absent are all the same answer. (That a PARTIAL one
    // is also reported rather than merely dropped is `CursorSuite`'s subject.)
    assertEquals(Server.cursorOf(req("""{"_cursor":{"logId":"L1"}}""")), None)
    // ...including the four at the TOP level, which is where they used to live.
    assertEquals(
      Server.cursorOf(
        req(
          """{"headHash":"h1","styleHash":"s1","logId":"L1","storeVersion":7}"""
        )
      ),
      None
    )
    assertEquals(Server.cursorOf(req("not json")), None)
    assertEquals(
      Server.cursorOf(Request[IO](Method.GET, uri"/sse/dashboard/d/patch")),
      None
    )
  }

  test("a non-empty shared batch advances the VERSION, and nothing else") {
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      raw <- h.stepRaw(es("sensor.a", "hot"))
      // An entity no card binds: nothing to render, so the cursor moves alone.
      quiet <- h.stepRaw(es("sensor.unwatched", "x"))
    } yield {
      assertEquals(raw.size, 2, clue = raw)
      assert(raw.last.contains(s""""${Server.StoreVersionSignal}":1"""), raw)
      // The other three are constant for the life of a renderer, so a batch does
      // not repeat them — that is bytes on every patch of every connection, and
      // every signal a client holds is serialised back into every request it
      // makes. They are (re)established on connect and on a renderer swap.
      assert(!raw.last.contains(Server.LogIdSignal), clue = raw)
      assert(!raw.last.contains(Server.HeadHashSignal), clue = raw)
      assert(!raw.last.contains(Server.StyleHashSignal), clue = raw)
      assertEquals(quiet.size, 1, clue = quiet)
      assert(
        quiet.head.contains(s""""${Server.StoreVersionSignal}":2"""),
        clue = quiet
      )
    }
  }

  test("a valid cursor resumes with the changed fragment, no body repaint") {
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      _ <- h.step(es("sensor.a", "hot"))
      logId <- h.logId
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      )
    } yield {
      assert(opening.contains(">hot<"), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
    }
  }

  test("a member that LEFT across the disconnect resumes as a remove patch") {
    // The saving resume exists for: one small patch, not a group morph.
    val lights = List("light.a", "light.b", "light.c", "light.d")
    for {
      h <- SharedHarness.create(
        dynDash,
        lights.map(id => id -> on(id)).toMap + ("light.e" -> off("light.e"))
      )
      // Establish the group in the shared log first (the very first membership
      // change always repaints wholesale — there is no per-entity base yet).
      _ <- h.step(on("light.e"))
      // ...then b leaves: 1 of 5 shown, under the churn fraction, so per-entity.
      left <- h.step(off("light.b"))
      logId <- h.logId
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, h.styleHash, logId, 2L))
      )
    } yield {
      assertEquals(left.size, 1, clue = left)
      assert(opening.contains("selector #c_light_b"), clue = opening)
      assert(opening.contains("mode remove"), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
    }
  }

  /** A dashboard with no browser on it is the NORMAL state of a home instance,
    * so a slug nobody is watching records nothing at all. The cost is exactness
    * for whoever comes back across that stretch: the versions it passed over
    * are described nowhere, so the only honest answer to a cursor from before
    * one is the repaint.
    */

  test(
    "a stretch nobody watched records nothing, and repaints whoever returns"
  ) {
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      _ <- h.step(es("sensor.a", "hot"))
      logId <- h.logId
      recorded <- h.cacheNow
      // The tab closes...
      _ <- h.closeViewer
      _ <- h.step(es("sensor.a", "warm"))
      unrecorded <- h.cacheNow
      // ...and the client comes back holding the version it left on.
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      )
    } yield {
      assert(recorded.nonEmpty, clue = recorded)
      // Nothing written — and the history that frame made unreachable dropped
      // with it, since no cursor below a gap is ever answered with a delta.
      assertEquals(
        unrecorded,
        Map.empty[NodeId, Long],
        clue = "a frame nobody was watching writes nothing, and forgets"
      )
      assert(opening.contains(BodyRepaint), clue = opening)
      // And it is a repaint that carries the value it missed, not a stale one.
      assert(opening.contains(">warm<"), clue = opening)
    }
  }

  /** The changelog's only unbounded part is its mutations — a `Gone` for a
    * member that never returns has nothing to remove it. What removes them is
    * the FLOOR: the lowest position any live session holds, so a mutation below
    * it cannot appear in any resume any session will ever run. Exact, where the
    * rule it replaced was a one-hour wall clock.
    */

  test("recording prunes what no live session can still ask for") {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "cold")))
      ref <- SignallingRef[IO].of(Renderer.create(liveLeafDash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          for {
            // One viewer, served through version 7.
            session <- Session.create("dashboard")
            _ <- session.position.set(7L)
            _ <- sessions.register("conn", session)
            live <- server.liveSlug("dashboard")
            // Two members left the group, one long before that viewer's
            // position and one after it.
            _ <- live.log.update(
              _.removed("c", "c_old", 2L).removed("c", "c_new", 9L)
            )
            renderer <- ref.get
            _ <- server.recordFrame("dashboard", renderer, live.log, Nil)
            log <- live.log.get
          } yield log
        }
    } yield out)
      .timeout(30.seconds)
      .map { log =>
        assertEquals(log.mutations.keySet, Set[NodeId]("c_new"))
        // Dropped, not silently lost: a CLIENT cursor is not bounded by the
        // floor, so one below this gets that mount refilled.
        assertEquals(log.since(2L).refill, List[NodeId]("c"))
      }
  }

  /** '''A resume may only claim what the CHANGELOG covered''', never what the
    * store holds. The recorder writes the log on its own fiber, so between a
    * change landing in the store and being recorded there is a window in which
    * `store.version` names a change `since` cannot see. Claiming it tells the
    * client it is current through a change it was never sent — and the pull
    * that would have carried it is then skipped (`version <= position`), so it
    * is lost until that entity next moves.
    *
    * Found by `LiveUpdateSmokeSuite`, which failed on exactly this every time
    * and was twice written off as browser flakiness. This harness makes the
    * window deterministic: it drives the recorder by hand and never rings the
    * doorbell, so its changelog is permanently behind its store.
    */

  test("an opening resume claims the changelog's version, not the store's") {
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      _ <- h.step(es("sensor.a", "hot"))
      logId <- h.logId
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      )
    } yield {
      // It resumed (the change is in there)...
      assert(opening.contains(">hot<"), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
      // ...and told the client where the CHANGELOG reaches. The doorbell has
      // never rung here, so a claim of the store's version would be a promise
      // about a frame this connection cannot prove it sent.
      assert(
        opening.contains("\"" + Server.StoreVersionSignal + "\":0"),
        clue = opening
      )
    }
  }

  test("every doubt about the cursor falls back to the full body repaint") {
    val cold = Map("sensor.a" -> es("sensor.a", "cold"))
    def opening(
        cursor: SharedHarness => IO[Option[Server.Cursor]]
    ): IO[String] =
      for {
        h <- SharedHarness.create(liveLeafDash, cold)
        _ <- h.step(es("sensor.a", "hot"))
        c <- cursor(h)
        out <- h.opening(c)
      } yield out
    for {
      // No cursor at all — a fresh page load, whose body is server-rendered.
      none <- opening(_ => IO.pure(None))
      // A log this server no longer has (a restart, or a renderer swap).
      staleLog <- opening(h =>
        IO.pure(
          Some(Server.Cursor(h.headHash, h.styleHash, "gone-with-the-log", 1L))
        )
      )
      // A version this store never reached.
      future <- opening(h =>
        h.logId.map(id => Some(Server.Cursor(h.headHash, h.styleHash, id, 99L)))
      )
    } yield {
      assert(none.contains(BodyRepaint), clue = none)
      assert(staleLog.contains(BodyRepaint), clue = staleLog)
      assert(future.contains(BodyRepaint), clue = future)
    }
  }

  test("a client whose <head> has changed is reloaded, not patched") {
    // The one thing a body patch cannot repair: the browser is holding the
    // previous theme's stylesheets. Nothing else is sent — the page is about to
    // render itself from scratch.
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      _ <- h.step(es("sensor.a", "hot"))
      logId <- h.logId
      opening <- h.opening(
        Some(Server.Cursor("0000deadbeef", h.styleHash, logId, 1L))
      )
    } yield {
      assert(opening.contains(s""""${Server.ReloadSignal}":true"""), opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
      assert(!opening.contains(">hot<"), clue = opening)
    }
  }

  /** A shared leaf (`sensor.shared`) beside a tabs host at "c_1", whose default
    * panel shows `sensor.a`. The panel's HTML bakes a client-selected member,
    * so it is rendered PER SESSION and never enters the slug's shared log —
    * which is the whole point of step 5.
    */

  test("a resume reconciles an OPEN surface's nodes, and only what differs") {
    for {
      h <- SharedHarness.create(
        mixedTabsDash,
        Map(
          "sensor.shared" -> es("sensor.shared", "cold"),
          "sensor.a" -> es("sensor.a", "old")
        )
      )
      // v1: shared, logged. v2: inside the tab panel, so the shared pass emits
      // nothing and nothing records it — exactly what the previous connection's
      // (now dead) per-session cache used to cover.
      _ <- h.step(es("sensor.shared", "hot"))
      panelTick <- h.step(es("sensor.a", "new"))
      logId <- h.logId
      // The client's cursor is at v1: it already has ">hot<".
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      )
    } yield {
      assertEquals(panelTick, Nil, clue = panelTick)
      // The panel node is reconciled on its OWN id — the untracked case, caught
      // by `fingerprint != stored` with no entry at all. Without it this value
      // would never reach the reconnected DOM: the silent staleness the whole
      // resume path risks.
      assert(opening.contains(">new<"), clue = opening)
      assert(opening.contains("""id="s_t0__c""""), clue = opening)
      // And the tabs HOST is not re-sent, because nothing about it changed. The
      // old mechanism painted every per-session root fresh on every resume; one
      // rule over one candidate set sends only what actually differs.
      assert(!opening.contains("""class="tabs""""), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
    }
  }

  test("a popup open across the disconnect is restored fresh, not closed") {
    // Backgrounding a phone tab must not dismiss the dialog you were reading.
    // The popup's content is per-session and its host sits outside #dashboard,
    // so neither the resume nor the repaint reaches it: it is re-rendered from
    // the client's own claim.
    val hostSelector = s"selector #${Dashboard.PopupHostId}"
    val hostReset = s"""<div id="${Dashboard.PopupHostId}"></div>"""
    val withPopup = liveLeafDash.copy(
      surfaces = Map(
        "det" -> Surface(
          LayoutNode.Component(
            "card",
            slots = Map("state" -> SlotSource(Some("sensor.b")))
          )
        )
      )
    )
    for {
      h <- SharedHarness.create(
        withPopup,
        Map(
          "sensor.a" -> es("sensor.a", "cold"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      _ <- h.step(es("sensor.a", "hot"))
      // Inside the popup, so the shared pass is silent about it and the previous
      // connection's (now dead) per-session cache was its only record.
      _ <- h.step(es("sensor.b", "B1")).assertEquals(Nil)
      logId <- h.logId
      cursor = Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      restored <- h.opening(cursor, popup = Some("det"))
      // A claim this dashboard cannot serve is the one case that clears the host.
      orphan <- h.opening(cursor, popup = Some("was-renamed"))
      // Claiming nothing leaves the host alone — nothing is open to keep.
      quiet <- h.opening(cursor)
    } yield {
      // No popup-shaped branch: its nodes are in `open`, so the ONE resume rule
      // reconciles them on their own ids and the dialog is never disturbed.
      assert(restored.contains(">B1<"), clue = restored)
      assert(!restored.contains(hostSelector), clue = restored)
      assert(!restored.contains(hostReset), clue = restored)
      // The one thing still worth a branch: a claim this dashboard no longer
      // serves. That dialog belongs to nothing and is in nobody's open set, so
      // without this it would sit on screen forever.
      assert(orphan.contains(hostReset), clue = orphan)
      assert(!quiet.contains(Dashboard.PopupHostId), clue = quiet)
    }
  }

  test("the popup signal follows the host: open, switch, close") {
    val dash = liveLeafDash.copy(
      surfaces = Map(
        "det" -> Surface(LayoutNode.Component("col")),
        "other" -> Surface(LayoutNode.Component("col"))
      )
    )
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "cold")))
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          val conn = "c1"
          val post = (p: String) =>
            server.routes.orNotFound.run(
              Request[IO](Method.POST, Uri.unsafeFromString(p))
                .withEntity(s"""{"${Server.ConnSignal}":"$conn"}""")
            )
          for {
            session <- Session.create("dashboard")
            _ <- sessions.register(conn, session)
            _ <- post("/sse/surface/open/det")
            _ <- post("/sse/surface/open/other")
            _ <- post("/sse/popup/close")
            emitted <- session.control.tryTakeN(None)
            open <- session.open.get
          } yield (emitted.map(_.renderString), open)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (emitted, open) =>
        // The server pushes NO signal for the swap. The tap that asked for it
        // already set `ui_popups` client-side, the way a tab button sets its
        // own — one mechanism for every selection.
        assertEquals(emitted.filter(_.contains("datastar-patch-signals")), Nil)
        // One host, one occupant: a popup replaces the previous one, and a close
        // leaves nothing behind.
        assertEquals(open, Set.empty[String])
      }
  }

  test("headHash tracks <head>, and only <head>") {
    val base = Renderer.create(liveLeafDash).headHash
    // Stable across restarts (a fresh Renderer over an equal dashboard), so an
    // add-on restart does not refresh every browser.
    assertEquals(Renderer.create(liveLeafDash).headHash, base)
    // A card edit changes the BODY, which the repaint re-sends in full — so it
    // must NOT force a reload.
    val editedCard = liveLeafDash.copy(
      cards = liveLeafDash.cards
        .updated("card", CardDef("<b>{{state}}</b>", slots = List("state")))
    )
    assertEquals(Renderer.create(editedCard).headHash, base)
    // A new stylesheet does: nothing can un-apply the old one.
    val editedTheme = liveLeafDash.copy(theme =
      Theme(stylesheets = List("https://example.test/other.css"))
    )
    assertNotEquals(Renderer.create(editedTheme).headHash, base)
  }

  test("styleHash tracks the patchable head, and headHash ignores it") {
    val base = Renderer.create(liveLeafDash)
    assertEquals(Renderer.create(liveLeafDash).styleHash, base.styleHash)
    // Inline CSS and the title patch, so they move styleHash and leave
    // headHash — the reload trigger — alone.
    val restyled =
      liveLeafDash.copy(theme = Theme(styles = ".card{color:red}"))
    val renamed = liveLeafDash.copy(title = Some("Renamed"))
    List(restyled, renamed).foreach { d =>
      assertNotEquals(Renderer.create(d).styleHash, base.styleHash)
      assertEquals(Renderer.create(d).headHash, base.headHash)
    }
  }

  test("a stale theme is patched into the head, not reloaded") {
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      _ <- h.step(es("sensor.a", "hot"))
      logId <- h.logId
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, "0000deadbeef", logId, 1L))
      )
    } yield {
      assert(
        opening.contains(s"""<style id="${Renderer.ThemeStyleId}">"""),
        opening
      )
      assert(opening.contains(s"""<title id="${Server.TitleId}">"""), opening)
      assert(!opening.contains(s""""${Server.ReloadSignal}":true"""), opening)
      // And the resume still happens: the head is repaired alongside it.
      assert(!opening.contains(BodyRepaint), clue = opening)
      assert(opening.contains(">hot<"), clue = opening)
    }
  }

}
