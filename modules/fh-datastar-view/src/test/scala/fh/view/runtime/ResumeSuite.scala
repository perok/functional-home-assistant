package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** Resume on reconnect (ADR 0011). A wrong resume is silent, stale values
  * forever, since the server believes the browser is current, so each test
  * asserts both what the client gets and whether the full-body repaint
  * (`selector #dashboard`) was used.
  */
class ResumeSuite extends ServerHarness {

  test("cursorOf reads the resume cursor off the datastar signal param") {
    def req(q: String): Request[IO] =
      Request[IO](
        Method.GET,
        uri"/sse/dashboard/d/patch".withQueryParam("datastar", q)
      )
    // `_`-prefixed, so the default filter keeps it off every request but the
    // SSE GET, which asks for it back.
    assertEquals(
      Server.cursorOf(
        req(
          """{"_cursor":{"headHash":"h1","styleHash":"s1",""" +
            """"logId":"L1","storeVersion":7}}"""
        )
      ),
      Some(Server.Cursor("h1", "s1", "L1", 7L))
    )
    assertEquals(Server.cursorOf(req("""{"conn":"c","haDown":false}""")), None)
    // A partial one is also reported, which is `CursorSuite`'s subject.
    assertEquals(Server.cursorOf(req("""{"_cursor":{"logId":"L1"}}""")), None)
    // Including the four at the top level.
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
      quiet <- h.stepRaw(es("sensor.unwatched", "x"))
    } yield {
      assertEquals(raw.size, 2, clue = raw)
      assert(raw.last.contains(s""""${Server.StoreVersionSignal}":1"""), raw)
      // The other three are constant for a renderer's life, and every signal is
      // serialised into every request, so a batch does not repeat them.
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
    val lights = List("light.a", "light.b", "light.c", "light.d")
    for {
      h <- SharedHarness.create(
        dynDash,
        lights.map(id => id -> on(id)).toMap + ("light.z" -> off("light.z"))
      )
      // The first membership change always repaints wholesale: there is no
      // per-entity base yet.
      _ <- h.step(on("light.z"))
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

  /** An unwatched slug records nothing, the normal state of a home instance.
    * The versions it passed over are described nowhere, so a cursor from before
    * the gap gets the repaint.
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
      _ <- h.closeViewer
      _ <- h.step(es("sensor.a", "warm"))
      unrecorded <- h.cacheNow
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      )
    } yield {
      assert(recorded.nonEmpty, clue = recorded)
      // No cursor below a gap is answered with a delta, so that history is
      // dropped too.
      assertEquals(
        unrecorded,
        Map.empty[NodeId, Long],
        clue = "a frame nobody was watching writes nothing, and forgets"
      )
      assert(opening.contains(BodyRepaint), clue = opening)
      assert(opening.contains(">warm<"), clue = opening)
    }
  }

  /** A `Gone` for a member that never returns has nothing else to remove it.
    * Below the floor, the lowest position any live session holds, a mutation
    * cannot appear in any resume.
    */

  test("recording prunes what no live session can still ask for") {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "cold")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(liveLeafDash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          for {
            session <- Session.create("dashboard")
            _ <- session.position.set(7L)
            _ <- sessions.register("conn", session)
            live <- server.liveSlug("dashboard")
            _ <- live.log.update(
              _.removed("c", "c_old", 2L).removed("c", "c_new", 9L)
            )
            renderer <- ref.get.map(_.rendererOf.get)
            _ <- server.recordFrame("dashboard", renderer, live.log, Nil)
            log <- live.log.get
          } yield log
        }
    } yield out)
      .timeout(30.seconds)
      .map { log =>
        assertEquals(log.mutations.keySet, Set[NodeId]("c_new"))
        // A client cursor is not bounded by the floor, so one below this gets
        // the host refilled.
        assertEquals(
          log.since(2L, TestAncestry.of(log)).refill,
          List[NodeId]("c")
        )
      }
  }

  /** '''A resume may only claim what the changelog covered''', not what the
    * store holds. The recorder runs on its own fiber, so `store.version` can
    * name a change `since` cannot see; claiming it skips the pull that would
    * carry it (`version <= position`), lost until the entity next moves.
    *
    * `LiveUpdateSmokeSuite` failed on this every time and was twice written off
    * as flaky. This harness never rings the doorbell, so its changelog is
    * permanently behind its store.
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
      assert(opening.contains(">hot<"), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
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
      none <- opening(_ => IO.pure(None))
      staleLog <- opening(h =>
        IO.pure(
          Some(Server.Cursor(h.headHash, h.styleHash, "gone-with-the-log", 1L))
        )
      )
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
    // The one thing a body patch cannot repair: the previous theme's
    // stylesheets.
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

  /** The panel bakes a client-selected member, so it is rendered per session
    * and never enters the slug's shared log.
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
      // v2 is inside the tab panel, so the shared pass emits nothing and
      // nothing records it.
      _ <- h.step(es("sensor.shared", "hot"))
      panelTick <- h.step(es("sensor.a", "new"))
      logId <- h.logId
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      )
    } yield {
      assertEquals(panelTick, Nil, clue = panelTick)
      // Reconciled on its own id with no entry at all (`fingerprint !=
      // stored`): otherwise the value never reaches the reconnected DOM.
      assert(opening.contains(">new<"), clue = opening)
      assert(opening.contains("""id="s_t0__c""""), clue = opening)
      assert(!opening.contains("""class="tabs""""), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
    }
  }

  test("a popup open across the disconnect is restored fresh, not closed") {
    // Backgrounding a phone tab must not dismiss the dialog. Its host sits
    // outside #dashboard, so it is re-rendered from the client's own claim.
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
      _ <- h.step(es("sensor.b", "B1")).assertEquals(Nil)
      logId <- h.logId
      cursor = Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      restored <- h.opening(cursor, popup = Some("det"))
      orphan <- h.opening(cursor, popup = Some("was-renamed"))
      quiet <- h.opening(cursor)
    } yield {
      // Its nodes are in `open`, so the one resume rule reconciles them on
      // their own ids and the dialog is never disturbed.
      assert(restored.contains(">B1<"), clue = restored)
      assert(!restored.contains(hostSelector), clue = restored)
      assert(!restored.contains(hostReset), clue = restored)
      // A claim the dashboard no longer serves belongs to nothing, so without
      // this the dialog would sit on screen forever.
      assert(orphan.contains(hostReset), clue = orphan)
      // The connect still commits `ui_popups: ""` (ADR 0025), so this asserts
      // no patch to the host, not the id's absence.
      assert(!quiet.contains(hostSelector), clue = quiet)
      assert(!quiet.contains(hostReset), clue = quiet)
      assert(
        quiet.contains(
          s""""${Server.UiSignalPrefix}${Dashboard.PopupHostId}":""""
        ),
        clue = quiet
      )
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
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(dash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
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
          } yield (emitted.map(_.render), open)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (emitted, open) =>
        // The tap already set `ui_popups` client-side, as for every selection.
        assertEquals(emitted.filter(_.contains("datastar-patch-signals")), Nil)
        assertEquals(open, Set.empty[String])
      }
  }

  test("headHash tracks <head>, and only <head>") {
    val base = Renderer.create(liveLeafDash).headHash
    // Stable across restarts, so an add-on restart does not refresh every
    // browser.
    assertEquals(Renderer.create(liveLeafDash).headHash, base)
    // The repaint re-sends a changed body in full, so no reload.
    val editedCard = liveLeafDash.copy(
      cards = liveLeafDash.cards
        .updated("card", CardDef("<b>{{state}}</b>", slots = List("state")))
    )
    assertEquals(Renderer.create(editedCard).headHash, base)
    // Nothing can un-apply a stylesheet.
    val editedTheme = liveLeafDash.copy(theme =
      Theme(stylesheets = List("https://example.test/other.css"))
    )
    assertNotEquals(Renderer.create(editedTheme).headHash, base)
    // Still a head `<link>` no patch can take back.
    val editedDeferred = liveLeafDash.copy(theme =
      Theme(deferredStylesheets = List("https://example.test/icons.css"))
    )
    assertNotEquals(Renderer.create(editedDeferred).headHash, base)
    val editedScript =
      liveLeafDash.copy(theme = Theme(inlineScripts = List("void 0;")))
    assertNotEquals(Renderer.create(editedScript).headHash, base)
    // The one token that reaches the head as markup: the `<meta
    // name="theme-color">` pair.
    val editedChrome = liveLeafDash.copy(theme =
      Theme(tokens = Map("primary-background-color" -> "#fafafa"))
    )
    assertNotEquals(Renderer.create(editedChrome).headHash, base)
  }

  test("styleHash tracks the patchable head, and headHash ignores it") {
    val base = Renderer.create(liveLeafDash)
    assertEquals(Renderer.create(liveLeafDash).styleHash, base.styleHash)
    // Inline CSS and the title are patched, so they move styleHash, not
    // headHash.
    val restyled =
      liveLeafDash.copy(theme = Theme(styles = ".card{color:red}"))
    val renamed = liveLeafDash.copy(title = Some("Renamed"))
    // Only the chrome background, which the theme-color pair is built from,
    // reloads.
    val retoned =
      liveLeafDash.copy(theme = Theme(tokens = Map("primary-color" -> "#0af")))
    List(restyled, renamed, retoned).foreach { d =>
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
      assert(!opening.contains(BodyRepaint), clue = opening)
      assert(opening.contains(">hot<"), clue = opening)
    }
  }

}
