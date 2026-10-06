package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.effect.std.Supervisor
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.{FakeHomeAssistant, FixtureEntity, TestAuth}
import fh.view.testkit.TestIds.given
import fs2.concurrent.SignallingRef
import io.circe.Json
import org.http4s.*
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** Resume on reconnect (ADR 0011). A wrong resume is silent, stale values
  * forever, since the server believes the browser is current, so each test
  * asserts both what the client gets and whether the full-body repaint
  * (`selector #dashboard`) was used.
  */
class ResumeSuite extends ServerHarness {

  private val cold = Map("sensor.a" -> es("sensor.a", "cold"))

  test("cursorOf reads the resume cursor off the datastar signal param") {
    assertEquals(
      Server.cursorOf(Request[IO](Method.GET, uri"/sse/dashboard/d/patch")),
      None
    )
  }

  test("a non-empty batch advances the VERSION, and nothing else") {
    live(liveLeafDash, cold) { ts =>
      for {
        v <- ts.viewer()
        batch <- v.change(es("sensor.a", "hot"))
        at <- v.cursor
      } yield {
        val raw = batch.map(_.render)
        assertEquals(raw.size, 2, clue = raw)
        assert(
          raw.last.contains(
            s""""${Server.StoreVersionSignal}":${at.version}"""
          ),
          raw
        )
        // The other three are constant for a renderer's life, and every signal
        // is serialised into every request, so a batch does not repeat them.
        assert(!raw.last.contains(Server.LogIdSignal), clue = raw)
        assert(!raw.last.contains(Server.HeadHashSignal), clue = raw)
        assert(!raw.last.contains(Server.StyleHashSignal), clue = raw)
      }
    }
  }

  test("a valid cursor resumes with the changed fragment, no body repaint") {
    live(liveLeafDash, cold) { ts =>
      for {
        v <- ts.viewer()
        _ <- v.change(es("sensor.a", "hot"))
        opening <- v.cursor.flatMap(c => ts.reconnect(Some(c)))
      } yield {
        assert(opening.contains(">hot<"), clue = opening)
        assert(!opening.contains(BodyRepaint), clue = opening)
      }
    }
  }

  test("a member that LEFT across the disconnect resumes as a remove patch") {
    val lights = List("light.a", "light.b", "light.c", "light.d")
    live(
      dynDash,
      lights.map(id => id -> on(id)).toMap + ("light.z" -> off("light.z"))
    ) { ts =>
      for {
        v <- ts.viewer()
        // The first membership change always repaints wholesale: there is no
        // per-entity base yet.
        _ <- v.change(on("light.z"))
        left <- v.change(off("light.b"))
        opening <- v.cursor.flatMap(c => ts.reconnect(Some(c)))
      } yield {
        assertEquals(elementPatches(left).size, 1, clue = left)
        assert(opening.contains("selector #c_light_b"), clue = opening)
        assert(opening.contains("mode remove"), clue = opening)
        assert(!opening.contains(BodyRepaint), clue = opening)
      }
    }
  }

  /** An unwatched slug records nothing, the normal state of a home instance.
    * The versions it passed over are described nowhere, so a cursor from before
    * the gap gets the repaint.
    */
  test(
    "a stretch nobody watched records nothing, and repaints whoever returns"
  ) {
    live(liveLeafDash, cold) { ts =>
      for {
        v <- ts.viewer()
        _ <- v.change(es("sensor.a", "hot"))
        before <- v.cursor
        recorded <- ts.log.map(logged)
        _ <- v.leave
        _ <- ts.record(FixtureEntity("sensor.a", "warm"))
        unrecorded <- ts.log.map(logged)
        opening <- ts.reconnect(Some(before))
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
  }

  /** A `Gone` for a member that never returns has nothing else to remove it.
    * Below the floor, the lowest position any live session holds, a mutation
    * cannot appear in any resume.
    */
  test("recording prunes what no live session can still ask for") {
    live(liveLeafDash, cold) { ts =>
      for {
        v <- ts.viewer()
        _ <- v.change(es("sensor.a", "hot"))
        _ <- v.change(es("sensor.a", "warm"))
        floor <- v.session.position.get
        slug <- ts.server.liveSlug(ts.slug)
        _ <- slug.log.update(
          _.removed("c", "c_old", floor - 1).removed("c", "c_new", floor + 5)
        )
        _ <- ts.record(FixtureEntity("sensor.a", "cool"))
        log <- ts.log
      } yield {
        assertEquals(log.mutations.keySet, Set[NodeId]("c_new"))
        // A client cursor is not bounded by the floor, so one below this gets
        // the host refilled.
        assertEquals(
          log.since(floor - 1, TestAncestry.of(log)).refill,
          List[NodeId]("c")
        )
      }
    }
  }

  /** '''A resume may only claim what the changelog covered''', not what the
    * store holds. The recorder runs on its own fiber, so `store.version` can
    * name a change `since` cannot see; claiming it skips the pull that would
    * carry it (`version <= position`), lost until the entity next moves.
    *
    * `LiveUpdateSmokeSuite` failed on this every time and was twice written off
    * as flaky. The window is the recorder's fiber, which the assembled server
    * closes too fast to observe, so this server has no recorder: the change is
    * logged by hand and the doorbell never rings.
    */
  test("an opening resume claims the changelog's version, not the store's") {
    val hot = es("sensor.a", "hot")
    val renderer = Renderer.create(liveLeafDash)
    (for {
      store <- StateStore.inMemory(cold)
      ref <- SignallingRef[IO].of(Server.RendererState.Ready(renderer))
      site <- Server.LiveSite.of(
        Map("dashboard" -> ref),
        Map.empty,
        "dashboard"
      )
      live <- site.liveFor("dashboard").map(_.get)
      sessions <- Sessions.create
      // A slug nobody watches records nothing.
      _ <- Session.create("dashboard").flatMap(sessions.register("watching", _))
      fake <- FakeHomeAssistant.create(Nil)
      opening <- Supervisor[IO].use { supervisor =>
        val server = new Server(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          site,
          sessions,
          TestAuth.openGate,
          supervisor
        )
        for {
          _ <- store.update(hot)
          _ <- server.recordFrame(
            "dashboard",
            renderer,
            live.log,
            Membership.empty,
            List(StateChange("sensor.a", cold.get("sensor.a"), hot))
          )
          logId <- live.log.get.map(_.id)
          version <- store.version
          opening <- TestServer.reconnect(
            server.routes.orNotFound,
            "dashboard",
            Some(
              Server
                .Cursor(renderer.headHash, renderer.styleHash, logId, version)
            ),
            None
          )
        } yield opening
      }
    } yield opening).timeout(30.seconds).map { opening =>
      assert(opening.contains(">hot<"), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
      assert(
        opening.contains("\"" + Server.StoreVersionSignal + "\":0"),
        clue = opening
      )
    }
  }

  /** '''A page's members are those of the snapshot it renders''', not those the
    * recorder last held. The page claims `store.version`, so a membership
    * behind the store would be claimed and never sent: the recorder logs the
    * arrival at that same version, which the client skips. Same window as the
    * test above, so again no recorder: one frame is recorded by hand, the
    * arrival is not.
    */
  test("a page read ahead of the recorder shows the store's members") {
    val off = Map("light.a" -> es("light.a", "off"))
    val dash = Dashboard(
      cards = Map("pill" -> CardDef("<b>{{state}}</b>", slots = List("state"))),
      card = LayoutNode.SetNode(
        candidates = List("light.a"),
        members = Map(
          "light.a" -> LayoutNode.SetMember(
            List(
              LayoutNode.SetClause(
                Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
                LayoutNode.Component("pill", Map("state" -> SlotSource()))
              )
            )
          )
        )
      )
    )
    val renderer = Renderer.create(dash)
    (for {
      store <- StateStore.inMemory(off)
      ref <- SignallingRef[IO].of(Server.RendererState.Ready(renderer))
      site <- Server.LiveSite.of(
        Map("dashboard" -> ref),
        Map.empty,
        "dashboard"
      )
      live <- site.liveFor("dashboard").map(_.get)
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      page <- Supervisor[IO].use { supervisor =>
        val server = new Server(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          site,
          sessions,
          TestAuth.openGate,
          supervisor
        )
        for {
          _ <- server.recordFrame(
            "dashboard",
            renderer,
            live.log,
            Membership.empty,
            List(StateChange("light.a", None, off("light.a")))
          )
          _ <- store.update(es("light.a", "on"))
          resp <- server.routes.orNotFound.run(
            Request[IO](Method.GET, uri"/d/dashboard")
          )
          body <- resp.bodyText.compile.string
        } yield body
      }
    } yield page).timeout(30.seconds).map { page =>
      assert(page.contains("id=\"c_light_a\""), clue = page)
    }
  }

  test("every doubt about the cursor falls back to the full body repaint") {
    live(liveLeafDash, cold) { ts =>
      for {
        v <- ts.viewer()
        _ <- v.change(es("sensor.a", "hot"))
        c <- v.cursor
        none <- ts.reconnect(None)
        staleLog <- ts.reconnect(Some(c.copy(logId = "gone-with-the-log")))
        future <- ts.reconnect(Some(c.copy(version = c.version + 98)))
      } yield {
        assert(none.contains(BodyRepaint), clue = none)
        assert(staleLog.contains(BodyRepaint), clue = staleLog)
        assert(future.contains(BodyRepaint), clue = future)
      }
    }
  }

  test("a client whose <head> has changed is reloaded, not patched") {
    // The one thing a body patch cannot repair: the previous theme's
    // stylesheets.
    live(liveLeafDash, cold) { ts =>
      for {
        v <- ts.viewer()
        _ <- v.change(es("sensor.a", "hot"))
        opening <- v.cursor.flatMap(c =>
          ts.reconnect(Some(c.copy(headHash = "0000deadbeef")))
        )
      } yield {
        assert(opening.contains(s""""${Server.ReloadSignal}":true"""), opening)
        assert(!opening.contains(BodyRepaint), clue = opening)
        assert(!opening.contains(">hot<"), clue = opening)
      }
    }
  }

  /** The panel bakes a client-selected member, so it is rendered per session.
    * With every viewer on the other tab, nothing records a change inside it.
    */
  test("a resume reconciles an OPEN surface's nodes, and only what differs") {
    val twoTabs = mixedTabsDash.copy(surfaces =
      mixedTabsDash.surfaces + ("t1" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.b")))
        ),
        bakeInto = Some("c_1"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1)
      ))
    )
    live(
      twoTabs,
      Map(
        "sensor.shared" -> es("sensor.shared", "cold"),
        "sensor.a" -> es("sensor.a", "old"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer("?ui.c_1=1")
        _ <- v.change(es("sensor.shared", "hot"))
        before <- v.cursor
        panelTick <- v.change(es("sensor.a", "new"))
        // Back on the first tab, the default.
        opening <- ts.reconnect(Some(before))
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
    live(
      withPopup,
      Map(
        "sensor.a" -> es("sensor.a", "cold"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer()
        _ <- v.change(es("sensor.a", "hot"))
        cursor <- v.cursor.map(Some(_))
        popupTick <- v.change(es("sensor.b", "B1"))
        restored <- ts.reconnect(cursor, popup = Some("det"))
        orphan <- ts.reconnect(cursor, popup = Some("was-renamed"))
        quiet <- ts.reconnect(cursor)
      } yield {
        assertEquals(popupTick, Nil, clue = popupTick)
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
  }

  private def popupDash(template: String) = liveLeafDash.copy(
    cards = liveLeafDash.cards + ("detail" -> CardDef(
      template,
      slots = List("state")
    )),
    surfaces = Map(
      "det" -> Surface(
        LayoutNode.Component(
          "detail",
          slots = Map("state" -> SlotSource(Some("sensor.b")))
        )
      )
    )
  )

  /** Its host sits outside `#dashboard`, so a repaint that stopped there left
    * the dialog showing what it held before the disconnect, and `holds` without
    * its nodes, until the next pull happened to re-send them.
    */
  test("a repaint with a popup open repaints the popup too") {
    live(
      popupDash("<i>{{state}}</i>"),
      Map(
        "sensor.a" -> es("sensor.a", "cold"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer()
        _ <- v.change(es("sensor.a", "hot"))
        cursor <- v.cursor
        _ <- v.change(es("sensor.b", "B1"))
        repaint <- ts.reconnect(
          Some(cursor.copy(logId = "gone-with-the-log")),
          popup = Some("det")
        )
      } yield {
        assert(repaint.contains(BodyRepaint), clue = repaint)
        assert(
          repaint.contains(s"selector #${Dashboard.PopupHostId}"),
          clue = repaint
        )
        assert(repaint.contains("<i>B1</i>"), clue = repaint)
      }
    }
  }

  /** The stream's `uiState` is what it CONNECTED with; the popup may have been
    * closed, or another opened, since.
    */
  test("a dashboard edit repaints the popup open now, not the one at connect") {
    val hostSelector = s"selector #${Dashboard.PopupHostId}"
    live(
      popupDash("<i>{{state}}</i>"),
      Map(
        "sensor.a" -> es("sensor.a", "cold"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        kept <- ts.connect("?ui.popups=det")
        closed <- ts.connect("?ui.popups=det")
        conn <- closed.drain.map(
          _.flatMap(_.data)
            .flatMap(s =>
              s""""${Server.ConnSignal}":"([^"]+)"""".r.findFirstMatchIn(s)
            )
            .map(_.group(1))
            .head
        )
        _ <- ts.post(
          s"sse/popup/${ts.slug}/close",
          body = s"""{"${Server.ConnSignal}":"$conn"}"""
        )
        _ <- kept.arrived *> closed.arrived *> kept.drain *> closed.drain
        slug <- ts.server.liveSlug(ts.slug)
        _ <- slug.renderer.set(
          Server.RendererState.Ready(
            Renderer.create(popupDash("<em>{{state}}</em>"))
          )
        )
        _ <- kept.arrived *> closed.arrived
        keptSent <- kept.drain.map(_.flatMap(_.data).mkString("\n"))
        closedSent <- closed.drain.map(_.flatMap(_.data).mkString("\n"))
      } yield {
        assert(keptSent.contains(BodyRepaint), clue = keptSent)
        assert(keptSent.contains(hostSelector), clue = keptSent)
        assert(keptSent.contains("<em>B0</em>"), clue = keptSent)
        assert(closedSent.contains(BodyRepaint), clue = closedSent)
        assert(!closedSent.contains(hostSelector), clue = closedSent)
        assert(!closedSent.contains("<em>B0</em>"), clue = closedSent)
      }
    }
  }

  test("a dashboard edit repaints the tab selected now, not at connect") {
    def twoTabs(card: String) = mixedTabsDash.copy(
      cards = mixedTabsDash.cards.updated("card", CardDef(card, List("state"))),
      surfaces = mixedTabsDash.surfaces + ("t1" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.b")))
        ),
        bakeInto = Some("c_1"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1)
      ))
    )
    live(
      twoTabs("<span>{{state}}</span>"),
      Map(
        "sensor.shared" -> es("sensor.shared", "S0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        client <- ts.connect()
        conn <- client.drain.map(
          _.flatMap(_.data)
            .flatMap(s =>
              s""""${Server.ConnSignal}":"([^"]+)"""".r.findFirstMatchIn(s)
            )
            .map(_.group(1))
            .head
        )
        _ <- ts.post(
          s"sse/surface/${ts.slug}/open/t1",
          body = s"""{"${Server.ConnSignal}":"$conn"}"""
        )
        _ <- client.arrived *> client.drain
        slug <- ts.server.liveSlug(ts.slug)
        _ <- slug.renderer.set(
          Server.RendererState.Ready(
            Renderer.create(twoTabs("<em>{{state}}</em>"))
          )
        )
        _ <- client.arrived
        sent <- client.drain.map(_.flatMap(_.data).mkString("\n"))
      } yield {
        assert(sent.contains(BodyRepaint), clue = sent)
        assert(sent.contains("<em>B0</em>"), clue = sent)
        assert(!sent.contains("<em>A0</em>"), clue = sent)
      }
    }
  }

  test("the popup selection is committed as it moves: open, switch, close") {
    // The client asks; the swap commits `ui_popups` (ADR 0025), so each move
    // lands in order and the last leaves nothing open.
    val dash = liveLeafDash.copy(
      surfaces = Map(
        "det" -> Surface(LayoutNode.Component("col")),
        "other" -> Surface(LayoutNode.Component("col"))
      )
    )
    live(dash, cold) { ts =>
      for {
        v <- ts.viewer()
        body = s"""{"${Server.ConnSignal}":"${v.document.conn}"}"""
        _ <- ts.post(s"sse/surface/${ts.slug}/open/det", body = body)
        _ <- ts.post(s"sse/surface/${ts.slug}/open/other", body = body)
        _ <- ts.post(s"sse/popup/${ts.slug}/close", body = body)
        emitted <- v.session.control.tryTakeN(None)
        open <- v.session.open.get
      } yield {
        val commits = emitted
          .flatMap(_.signals)
          .flatMap(s => """"ui_popups":"([^"]*)"""".r.findAllMatchIn(s))
          .map(_.group(1))
        assertEquals(commits, List("det", "other", ""), clue = emitted)
        assertEquals(open, Set.empty[String])
      }
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
    live(liveLeafDash, cold) { ts =>
      for {
        v <- ts.viewer()
        _ <- v.change(es("sensor.a", "hot"))
        opening <- v.cursor.flatMap(c =>
          ts.reconnect(Some(c.copy(styleHash = "0000deadbeef")))
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

}
