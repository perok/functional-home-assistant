package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  SlotSource,
  Surface
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** End to end over the REAL stream, with more than one client.
  *
  * These read the stream as EVENTS rather than as one string, and assert on
  * every one of them rather than on the absence of a substring. What one
  * connection is sent is only half the contract — the other half is what the
  * OTHERS are not sent, and that cannot be observed from a single stream.
  */
class LiveStreamSuite extends ServerHarness {

  // ---------------------------------------------------------------------------
  // End to end over the REAL stream
  // ---------------------------------------------------------------------------

  /** These tests read the stream as EVENTS, not as one string, and assert on
    * every one of them rather than on the absence of a substring.
    *
    * `assert(!raw.contains(…))` passes for every reason including the ones
    * nobody meant — a renamed selector, an event that never arrived at all, a
    * typo in the needle — so it pins almost nothing. Naming the exact sequence
    * pins everything, and reads like the wire dump you would see in the
    * browser.
    *
    * Decoding is http4s's own `ServerSentEvent.decoder`, so the tests parse the
    * wire with the same type the server writes it with.
    */

  /** The ready-made patches a shared batch produced, cursor signal dropped —
    * what a test asserting on BYTES wants. A `Reveal` is not bytes: it is the
    * instruction one connection finishes for itself, so it is not here.
    */

  /** An [[Addressed]] carries a [[Patch]] now — merging and encoding are the
    * connection's job ([[Patches.encode]]) — so the bytes this suite asserts on
    * are derived rather than stored. The resume cursor is an [[Encoded]], so
    * "not the cursor" is the type rather than a predicate.
    */

  /** What a pull puts on the wire. Merging is [[Patches.encode]]'s job and is
    * asserted where it matters; these contracts are about the patches.
    */

  /** The popup host's selection signal — `ui_` + the host id, exactly as the
    * shell composes it.
    */

  /** The cursor handshake the connect path emits last — the end of the opening
    * block, and the marker every one of these reads stops on.
    */

  /** Just the DOM events, as `(mode, selector, elements)`.
    *
    * The element stream is the contract worth pinning exactly. Signals are not:
    * `haDown` rides its own merged stream, so its POSITION among the others is
    * a scheduling detail, and asserting on it would buy a flaky test rather
    * than a stronger one. So these tests state the element sequence in full and
    * check for the signals they care about by presence.
    */

  /** ONE connected client, driven a step at a time.
    *
    * A test walks the interaction the way a browser experiences it — connect,
    * assert; change, assert; change again, assert — instead of collecting one
    * blob at the end and rummaging in it. Every assertion is then about a
    * specific moment, and an event arriving at the wrong TIME fails as loudly
    * as one that never arrives.
    */

  /** A booted server plus however many connected clients a test wants.
    *
    * MANY clients matter: what one connection is sent is only half the contract
    * — the other half is what the OTHERS are not sent, and that cannot be
    * observed from a single stream. `change` applies one entity update and
    * waits for every client to fall quiet, so each `drain` afterwards is
    * exactly what that client received for that change.
    *
    * In-process: `routes.run` on the `HttpApp`, no port and no socket, so this
    * is deterministic and as fast as a unit test. What it adds over
    * [[SharedHarness]] is the parts that harness deliberately skips — the
    * publisher fibers, the topic, the per-connection merge — which is exactly
    * where every bug the running app found had been hiding.
    */

  /** The `conn` a freshly-rendered document established, read off the
    * `data-init` URL it advertises — what a browser's sessionStorage would
    * keep.
    */

  /** How a document advertises its stream — the prefix of the `data-init`
    * attribute, up to the URL itself. A REGEX, because `String.split` takes
    * one: the `(` needs escaping or it reads as an unclosed group.
    */

  /** A main-page card plus a TWO-tab host (`c_1`), so two clients can be
    * looking at different panels of the same dashboard at the same time — the
    * shape the per-connection contract is actually about.
    */

  /** '''What one client is sent is only half the contract.'''
    *
    * The other half is what the OTHERS are not sent, and no single stream can
    * show it. This is the property ADR 0002's collapse must PRESERVE — today it
    * falls out of the per-session pass rendering only `open` surfaces; after
    * the collapse it has to be a deliberate per-connection filter — so it is
    * pinned here first, at the level the change will be judged on.
    *
    * Under-sending is the failure mode with no symptom: a patch withheld from a
    * client that needed it produces no error, just a value that quietly stops
    * updating.
    */

  test("two clients on different tabs: each sees only its own") {
    liveWorld(
      twoTabsDash,
      Map(
        "sensor.shared" -> es("sensor.shared", "s0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { world =>
      for {
        onT0 <- world.connect()
        onT1 <- world.connect("?ui.c_1=1")
        _ <- onT0.drain
        _ <- onT1.drain

        // A change inside TAB 0's panel.
        _ <- world.change(es("sensor.a", "A1"))
        a0 <- onT0.drain
        a1 <- onT1.drain
        _ = assert(
          domEvents(a0).exists(_._3.exists(_.contains("A1"))),
          clue = ("viewer of tab 0 must get it", a0)
        )
        _ = assertEquals(
          domEvents(a1),
          Nil,
          clue = ("viewer of tab 1 must get nothing", a1)
        )

        // ...and one inside TAB 1's, the mirror image.
        _ <- world.change(es("sensor.b", "B1"))
        b0 <- onT0.drain
        b1 <- onT1.drain
        _ = assertEquals(
          domEvents(b0),
          Nil,
          clue = ("viewer of tab 0 must get nothing", b0)
        )
        _ = assert(
          domEvents(b1).exists(_._3.exists(_.contains("B1"))),
          clue = ("viewer of tab 1 must get it", b1)
        )

        // A MAIN-PAGE change reaches both — the filter must not swallow what is
        // not surface-scoped at all.
        _ <- world.change(es("sensor.shared", "s1"))
        s0 <- onT0.drain
        s1 <- onT1.drain
        _ = assert(
          domEvents(s0).exists(_._3.exists(_.contains("s1"))),
          clue = s0
        )
        _ = assert(
          domEvents(s1).exists(_._3.exists(_.contains("s1"))),
          clue = s1
        )
      } yield ()
    }
  }

  /** Tabs INSIDE a flipping branch — the shape that still forces
    * [[Renderer.sessionOnlyStateGroups]] onto the per-session pass.
    *
    * A flip is decided by entity state, so the branch renders once for the slug
    * — but the tabs host inside it holds a mount whose contents each client
    * chose for itself. Rendering that mount on the shared pass would hand every
    * client the DEFAULT tab, silently yanking a viewer off the tab they picked.
    * Pinned here before the collapse deletes the pass that hides it.
    */

  private def tabsInBranchDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "ifhost" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        mount = Some("""<div id="{{mountId}}">{{{branch}}}</div>""")
      ),
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        mount = Some("""<div id="{{mountId}}" class="tabs">{{{panel}}}</div>""")
      )
    ),
    card = LayoutNode.Component(
      "col",
      children = List(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.shared")))
        ),
        LayoutNode.Component("ifhost")
      )
    ),
    surfaces = Map(
      // The armed branch IS a tabs host (node `s_then__c`).
      "then" -> stateMember(LayoutNode.Component("tabs"), "c_1", 0, armedCond),
      "else" -> stateMember(branchCard("sensor.z"), "c_1", 1, always),
      "t0" -> Surface(
        branchCard("sensor.a"),
        bakeInto = Some("s_then__c"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.User(defaultOpen = true)
      ),
      "t1" -> Surface(
        branchCard("sensor.b"),
        bakeInto = Some("s_then__c"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1)
      )
    )
  )

  /** A BARE container — a mount and no `self` — has no rendering of its own:
    * rendering it by id renders its whole subtree, mounts included. The log is
    * per SLUG, so a digest recorded for one is one viewer's bytes presented as
    * everyone's, and a resume re-rendering it hands that viewer's variant to
    * whoever asks.
    *
    * Concretely, and this is the failure it caused: a client on tab 1
    * reconnects and is morphed onto tab 0 — over a change inside tab 0's panel,
    * which it could not see and did not ask for.
    */

  private def barePopupTabsDash = Dashboard(
    cards = Map(
      // A PURE MOUNT, exactly as the shipped `Column` is: no `self`, children
      // in the mount. That shape is the whole point — it has no markup of its
      // own to fingerprint.
      "col" -> CardDef(
        template = "{{{mount}}}",
        mount = Some("<div>{{#children}}{{{html}}}{{/children}}</div>")
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        self = Some("""<div id="{{selfId}}">bar</div>"""),
        mount = Some("""<div id="{{mountId}}">{{{panel}}}</div>""")
      )
    ),
    card = LayoutNode.Component("col"),
    surfaces = Map(
      // The popup's content root is a bare `col` wrapping the tabs host.
      "det" -> Surface(
        LayoutNode.Component(
          "col",
          children = List(LayoutNode.Component("tabs"))
        )
      ),
      "t0" -> Surface(
        branchCard("sensor.a"),
        bakeInto = Some("s_det__c_0"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.User(defaultOpen = true)
      ),
      "t1" -> Surface(
        branchCard("sensor.b"),
        bakeInto = Some("s_det__c_0"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1)
      )
    )
  )

  test("a fill records what it put there, so the next tick suppresses") {
    // The point of the trace. Opening a surface renders it and patches it into
    // the host; the log now learns each node's bytes from that same render. So
    // when an entity inside it ticks to the SAME value, the diff can tell
    // "unchanged" from "never told" and sends nothing.
    //
    // Before, a fill dropped those entries, and the first tick after any
    // surface open re-sent every node in it once — the cost W10b named and
    // could not pay, because fingerprinting meant walking the subtree twice.
    val dash = Dashboard(
      cards = Map(
        "col" -> CardDef(
          template = "{{{mount}}}",
          mount = Some("<div>{{#children}}{{{html}}}{{/children}}</div>")
        ),
        "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
      ),
      card = LayoutNode.Component("col"),
      surfaces = Map(
        "det" -> Surface(
          LayoutNode.Component("col", children = List(branchCard("sensor.a")))
        )
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
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          val conn = "c1"
          for {
            session <- Session.create("dashboard")
            _ <- sessions.register(conn, session)
            renderer <- ref.get.map(_.rendererOf.get)
            live <- server.liveSlug("dashboard")
            painted = Held.of(
              renderer
                .renderNodeById(
                  "s_det__c_0",
                  Map("sensor.a" -> es("sensor.a", "cold"))
                )
                .get
            )
            node = NodeId.derived("s_det__c_0")
            // Nothing is claimed for a surface nobody has opened.
            beforeFill <- session.holds.get.map(_.get(node).contains(painted))
            // Open the popup the way a tap does.
            _ <- server.routes.orNotFound.run(
              Request[IO](Method.POST, uri"/sse/surface/dashboard/open/det")
                .withEntity(s"""{"${Server.ConnSignal}":"$conn"}""")
            )
            // The fill told the SESSION what it painted...
            afterFill <- session.holds.get.map(_.get(node).contains(painted))
            held <- session.holds.get
            // ...so a tick that renders identically produces nothing for it.
            //
            // A SYNTHETIC change against an unchanged store, deliberately: a
            // real `store.update` wakes the background recorder, and whichever
            // reaches the log first leaves the other seeing an empty frame. The
            // suppression under test is the session's, not a race's.
            same <- recordAndPull(
              server,
              sessions,
              store,
              renderer,
              live.log,
              List(
                StateChange(
                  "sensor.a",
                  Some(es("sensor.a", "cold")),
                  es("sensor.a", "cold")
                )
              ),
              open = Set("det"),
              holds = held
            )
          } yield (beforeFill, afterFill, same)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (beforeFill, afterFill, same) =>
        // Not vacuous: the claim did not exist until the fill made it.
        assert(!beforeFill, clue = "nothing claimed before the surface opened")
        assert(afterFill, clue = "the fill must claim the node it painted")
        assertEquals(same, Nil, clue = events(same).map(_.renderString))
      }
  }

  test("a queued flip that a later one superseded is dropped, not sent") {
    // Two flips reach one slow client's queue. The first was planned against a
    // selection that has since moved, so its bytes are not merely redundant —
    // they would put the wrong branch on screen until the item behind them
    // corrected it. The log already knows: the later flip recorded that
    // member as Gone.
    for {
      h <- SharedHarness.create(
        ifDash(),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "A0"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      out <- h.queued(
        List(es("alarm.h", "disarmed"), es("alarm.h", "armed"))
      )
    } yield {
      assertEquals(out.size, 1, clue = out)
      // The surviving branch, not the one that flashed past.
      assert(out.head.contains("""id="s_then__c""""), clue = out.head)
      assert(!out.head.contains("""id="s_else__c""""), clue = out.head)
    }
  }

  test("a branch that empties removes its content, never the mount") {
    // An `If` with no matching member: the mount must survive, because every
    // later fill targets it by id and a patch at a missing id is a silent
    // no-op — the group would go permanently dead for that client.
    val d = ifDash().copy(surfaces =
      Map("then" -> stateMember(branchCard("sensor.a"), "c_0", 0, armedCond))
    )
    for {
      h <- SharedHarness.create(
        d,
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "A0")
        )
      )
      emptied <- h.stepRaw(es("alarm.h", "disarmed"))
      refilled <- h.step(es("alarm.h", "armed"))
    } yield {
      val removes = emptied.filter(_.contains("mode remove"))
      assertEquals(removes.size, 1, clue = emptied)
      // The branch's CONTENT element, not the host it sat in.
      assert(removes.head.contains("selector #s_then__c"), clue = removes.head)
      assert(!removes.head.contains("#c_0_branch"), clue = removes.head)
      // And the mount is still there to be filled again.
      assertEquals(refilled.size, 1, clue = refilled)
      assert(refilled.head.contains("selector #c_0_branch"), clue = refilled)
    }
  }

  test("a fill fingerprints the nodes it placed, not the blob") {
    // The obligation every fill meets: it re-supplies a whole subtree, so the
    // session has to know what it put in EACH node. Claiming the composed
    // subtree under the branch's ROOT does not do that — that root is a bare
    // container, so it has no rendering of its own, nothing can ever resolve
    // the entry, and the members it placed stay unknown.
    val d = Dashboard(
      cards = ifCards ++ Map(
        "col" -> CardDef(
          template = "{{{mount}}}",
          mount = Some("<div>{{#children}}{{{html}}}{{/children}}</div>")
        )
      ),
      card = LayoutNode
        .Component("col", children = List(LayoutNode.Component("ifhost"))),
      surfaces = Map(
        // The branch's root is a `col` — a mount with no self.
        "then" -> stateMember(
          LayoutNode.Component("col", children = List(branchCard("sensor.a"))),
          "c_0",
          0,
          armedCond
        )
      )
    )
    val r = Renderer.create(d)
    val armed = Map(
      "alarm.h" -> es("alarm.h", "armed"),
      "sensor.a" -> es("sensor.a", "A0")
    )
    val (patch, _) =
      Patches.hostFill(r, r.mountId("c_0"), Some("then"), armed, Map.empty).get

    // The leaf the fill places is claimed, holding exactly what a patch for
    // that node alone would carry — which is what makes the two comparable.
    val leaf: NodeId = "s_then__c_0"
    assertEquals(
      patch.establishes.get(leaf),
      r.renderNodeById(leaf, armed).map(Held.of)
    )
    // And the branch ROOT gets nothing: it has no rendering of its own, so a
    // claim there could never be resolved.
    val root = NodeId.derived("s_then__c")
    assertEquals(r.renderNodeById(root, armed), None)
    assert(!patch.establishes.contains(root), clue = patch.establishes.keySet)
  }

  test("a resume renders a variant-bearing node for THIS viewer") {
    // The hole that kept `__ifmissing` out. A node whose own markup reads its
    // own selection has one rendering per member, and `resume` renders its
    // candidates BY ID — so without the viewer it hands every client the
    // default member's bar, flipping a tab-1 viewer's highlight to tab 0 on
    // reconnect.
    val r = Renderer.create(serverHighlightDash)
    val states = Map(
      "sensor.title" -> es("sensor.title", "T1"),
      "sensor.a" -> es("sensor.a", "A0"),
      "sensor.b" -> es("sensor.b", "B0")
    )
    val host: NodeId = "c_0"
    val mine = Map("c_0" -> "1")
    // The bar moved at v5. What this viewer is recorded as holding is the
    // DEFAULT variant's bytes — tab 0's bar, which is what a repaint or an
    // earlier connect on tab 0 would have left behind.
    val log = FragmentLog("w23").touched(host, 5L)
    val holds: Map[NodeId, Held] =
      Map(host -> Held.of(r.renderNodeById(host, states).get))
    val owed = resumeNow(
      r,
      log,
      holds,
      states,
      1L,
      Set("t1"),
      mine
    )

    assert(
      owed.exists(_.patch.toSse.renderString.contains("active-1")),
      clue = owed.map(_.patch.toSse.renderString)
    )
    assert(
      !owed.exists(_.patch.toSse.renderString.contains("active-0")),
      clue = (
        "a tab-1 viewer must not be sent tab 0's bar",
        owed.map(_.patch.toSse.renderString)
      )
    )
  }

  test("a resume cannot move a viewer onto a tab it did not choose") {
    val r = Renderer.create(barePopupTabsDash)
    val before =
      Map(
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    // This viewer holds tab 1.
    val open = Set("det", "t1")
    val mine = Map("s_det__c_0" -> "1")
    // What this viewer's DOCUMENT put on screen, exactly as `pageResponse`
    // records it: rendered at ITS ui state, straight into its own session.
    val ids =
      (r.surfaceNodeIds("det") ++ r.surfaceNodeIds("t1")).toList.sorted
    val seeded = FragmentLog("w18")
    val held = ids.flatMap { id =>
      r.renderLogged(id, before, mine).map(h => id -> Held.of(h))
    }.toMap

    // (1) A change inside TAB 0's panel. Invisible to this viewer, and its
    //     content must not reach it by ANY route.
    val tab0Moved = before.updated("sensor.a", es("sensor.a", "A1"))
    val owed = resumeNow(
      r,
      seeded,
      held,
      tab0Moved,
      2L,
      open,
      mine
    )
    assert(
      !owed.exists(_.patch.toSse.renderString.contains("s_t0__c")),
      clue = owed.map(_.patch.toSse.renderString)
    )
    assert(!owed.exists(_.patch.toSse.renderString.contains("A1")), clue = owed)

    // (2) ...and the guard is not vacuous: a change in ITS OWN panel does
    //     arrive. Without this the test would pass by sending nothing, ever.
    val tab1Moved = before.updated("sensor.b", es("sensor.b", "B1"))
    val mineOwed = resumeNow(
      r,
      seeded,
      held,
      tab1Moved,
      2L,
      open,
      mine
    )
    assert(
      mineOwed.exists(_.patch.toSse.renderString.contains("B1")),
      clue = mineOwed.map(_.patch.toSse.renderString)
    )
  }

  /** An inactive branch costs nothing — the guarantee ADR 0007 states — and it
    * has to hold for a USER surface nested inside one too.
    *
    * `selectedSurfaces` reports a selection for every bake group whether or not
    * that group is on screen, so a tab panel inside a hidden `If` is in its
    * client's open set while nothing of it exists in any DOM. Rendering and
    * pushing it is harmless (the morph targets an id the DOM lacks) and is pure
    * waste, per tick of every entity it binds.
    */

  test("a tab panel inside a HIDDEN branch costs nothing") {
    liveWorld(
      tabsInBranchDash,
      Map(
        // Disarmed: the `then` branch, which holds the tabs, is NOT active.
        "alarm.h" -> es("alarm.h", "disarmed"),
        "sensor.shared" -> es("sensor.shared", "s0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0"),
        "sensor.z" -> es("sensor.z", "Z0")
      )
    ) { world =>
      for {
        c <- world.connect()
        _ <- c.drain
        // sensor.a is bound ONLY inside tab 0's panel, inside the hidden
        // branch. Nothing on screen shows it.
        _ <- world.change(es("sensor.a", "A1"))
        hidden <- c.drain
        _ = assertEquals(domEvents(hidden), Nil, clue = hidden)

        // ...and the guard is not vacuous: a change the client CAN see still
        // arrives, through the same pass.
        _ <- world.change(es("sensor.shared", "s1"))
        seen <- c.drain
        _ = assert(
          domEvents(seen).exists(_._3.exists(_.contains("s1"))),
          clue = seen
        )
      } yield ()
    }
  }

  test("a reconnect is not owed another client's tab") {
    // Two viewers, different tabs, both inside the ACTIVE branch. A change in
    // tab 0's panel is rendered (its viewer needs it) and logged — so the
    // cursor names it. The tab-1 viewer's resume must not carry it: the cursor
    // knows what changed, not who is looking.
    val r = Renderer.create(tabsInBranchDash)
    val states = Map(
      "alarm.h" -> es("alarm.h", "armed"),
      "sensor.shared" -> es("sensor.shared", "s0"),
      "sensor.a" -> es("sensor.a", "A1"),
      "sensor.b" -> es("sensor.b", "B0"),
      "sensor.z" -> es("sensor.z", "Z0")
    )
    val tab0Node: NodeId = "s_t0__c"
    val log = FragmentLog("w13").touched(tab0Node, 5L)
    val owed = resumeNow(
      r,
      log,
      Map.empty,
      states,
      1L,
      Set("then", "t1"),
      Map.empty
    )
    assert(
      !owed.exists(_.patch.toSse.renderString.contains("A1")),
      clue = owed.map(_.patch.toSse.renderString)
    )
  }

  /** A bar that renders its ACTIVE tab server-side rather than through a
    * `$ui_<id>` expression — `{{bakeIndex}}` in the card's `self`.
    *
    * It used to paint correctly and then blank itself on the first tick: the
    * document path passed `bakeIndex`, the patch path did not. Now both do, and
    * the node is per-viewer as a result — one rendering per member of its own
    * group.
    */

  private def serverHighlightDash = Dashboard(
    cards = Map(
      "col" -> CardDef(
        template = "{{{mount}}}",
        mount = Some("<div>{{#children}}{{{html}}}{{/children}}</div>")
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        // The bar names the active tab AND binds a live entity, so it is
        // re-rendered on that entity's ticks — the shape the split exists for.
        self = Some(
          """<div id="{{selfId}}" class="active-{{bakeIndex}}">{{title}}</div>"""
        ),
        mount = Some("""<div id="{{mountId}}">{{{panel}}}</div>"""),
        slots = List("title")
      )
    ),
    card = LayoutNode.Component(
      "col",
      children = List(
        LayoutNode.Component(
          "tabs",
          slots = Map("title" -> SlotSource(Some("sensor.title")))
        )
      )
    ),
    surfaces = Map(
      "t0" -> Surface(
        branchCard("sensor.a"),
        bakeInto = Some("c_0"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.User(defaultOpen = true)
      ),
      "t1" -> Surface(
        branchCard("sensor.b"),
        bakeInto = Some("c_0"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1)
      )
    )
  )

  test("a variant keeps its own digest, so an unchanged tick is suppressed") {
    // The set of variants for a bake owner is STATIC — one per member — so each
    // is an entry of its own rather than one shared digest that could only ever
    // describe one viewer's bytes. Two viewers on different tabs each get their
    // own suppression, and neither consumes the other's patch.
    liveWorld(
      serverHighlightDash,
      Map(
        "sensor.title" -> es("sensor.title", "T0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { world =>
      for {
        onT0 <- world.connect()
        onT1 <- world.connect("?ui.c_0=1")
        _ <- onT0.drain
        _ <- onT1.drain
        // A real change: both viewers get their own bar.
        _ <- world.change(es("sensor.title", "T1"))
        a1 <- onT0.drain
        b1 <- onT1.drain
        _ = assert(
          domEvents(a1).exists(_._3.exists(_.contains("T1"))),
          clue = a1
        )
        _ = assert(
          domEvents(b1).exists(_._3.exists(_.contains("T1"))),
          clue = b1
        )
        // The SAME entity ticks to a value this node renders identically —
        // the title is unchanged, only an attribute moved. Each variant's own
        // digest says so, and nothing goes out to either viewer.
        _ <- world.change(
          es("sensor.title", "T1").copy(attributes =
            Map("unrelated" -> io.circe.Json.fromInt(7))
          )
        )
        a2 <- onT0.drain
        b2 <- onT1.drain
        _ = assertEquals(domEvents(a2), Nil, clue = a2)
        _ = assertEquals(domEvents(b2), Nil, clue = b2)
      } yield ()
    }
  }

  test("a server-rendered selection survives the first tick, per viewer") {
    liveWorld(
      serverHighlightDash,
      Map(
        "sensor.title" -> es("sensor.title", "T0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { world =>
      for {
        onT0 <- world.connect()
        onT1 <- world.connect("?ui.c_0=1")
        _ <- onT0.drain
        _ <- onT1.drain
        // A tick of the bar's OWN entity: the patch must keep each viewer's
        // index, not blank it and not swap it for the other's.
        _ <- world.change(es("sensor.title", "T1"))
        a <- onT0.drain
        b <- onT1.drain
        _ = assert(
          domEvents(a).exists(_._3.exists(_.contains("""class="active-0""""))),
          clue = ("tab 0's viewer keeps index 0", a)
        )
        _ = assert(
          domEvents(b).exists(_._3.exists(_.contains("""class="active-1""""))),
          clue = ("tab 1's viewer keeps index 1", b)
        )
        // ...and both got the new title, so the patch is real.
        _ = assert(domEvents(a).exists(_._3.exists(_.contains("T1"))), clue = a)
        _ = assert(domEvents(b).exists(_._3.exists(_.contains("T1"))), clue = b)
        // Neither is handed the other's bar.
        _ = assert(
          !domEvents(a).exists(_._3.exists(_.contains("active-1"))),
          clue = a
        )
        _ = assert(
          !domEvents(b).exists(_._3.exists(_.contains("active-0"))),
          clue = b
        )
      } yield ()
    }
  }

  test("a flip re-reveals each client's OWN tab, not the default one") {
    liveWorld(
      tabsInBranchDash,
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.shared" -> es("sensor.shared", "s0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0"),
        "sensor.z" -> es("sensor.z", "Z0")
      )
    ) { world =>
      for {
        onT0 <- world.connect()
        onT1 <- world.connect("?ui.s_then__c=1")
        open0 <- onT0.drain
        open1 <- onT1.drain
        // First paint already differs per client: the default tab vs the one
        // the second client asked for.
        _ = assert(
          open0.exists(_.renderString.contains("A0")),
          clue = ("tab 0's viewer opens on A", open0)
        )
        _ = assert(
          !open0.exists(_.renderString.contains("B0")),
          clue = open0
        )
        _ = assert(
          open1.exists(_.renderString.contains("B0")),
          clue = ("tab 1's viewer opens on B", open1)
        )
        _ = assert(
          !open1.exists(_.renderString.contains("A0")),
          clue = open1
        )

        // The branch goes away — for both, identically. Nothing tab-shaped is
        // left in the DOM.
        _ <- world.change(es("alarm.h", "disarmed"))
        off0 <- onT0.drain
        off1 <- onT1.drain
        _ = assert(
          domEvents(off0).exists(_._3.exists(_.contains("Z0"))),
          clue = off0
        )
        _ = assert(
          domEvents(off1).exists(_._3.exists(_.contains("Z0"))),
          clue = off1
        )

        // ...and comes back. THE assertion: each client is re-shown ITS tab.
        _ <- world.change(es("alarm.h", "armed"))
        on0 <- onT0.drain
        on1 <- onT1.drain
        _ = assert(
          on0.exists(_.renderString.contains("A0")),
          clue = ("tab 0's viewer must get A back", on0)
        )
        _ = assert(
          !on0.exists(_.renderString.contains("B0")),
          clue = ("...and never tab 1's content", on0)
        )
        _ = assert(
          on1.exists(_.renderString.contains("B0")),
          clue = ("tab 1's viewer must get B back, not the default", on1)
        )
        _ = assert(
          !on1.exists(_.renderString.contains("A0")),
          clue = ("...which is exactly the silent regression", on1)
        )

        // Both are still live inside their own tab afterwards.
        _ <- world.change(es("sensor.a", "A1"))
        a0 <- onT0.drain
        a1 <- onT1.drain
        _ = assert(
          domEvents(a0).exists(_._3.exists(_.contains("A1"))),
          clue = a0
        )
        _ = assertEquals(domEvents(a1), Nil, clue = a1)
        _ <- world.change(es("sensor.b", "B1"))
        b0 <- onT0.drain
        b1 <- onT1.drain
        _ = assertEquals(domEvents(b0), Nil, clue = b0)
        _ = assert(
          domEvents(b1).exists(_._3.exists(_.contains("B1"))),
          clue = b1
        )
      } yield ()
    }
  }

  test("one frame is ONE batch: both elements, one cursor") {
    // An HA frame carries many entity diffs and the store applies it in one
    // update, bumping the version once. Publishing per entity split that
    // instant into N passes, each ending with its own copy of the SAME cursor —
    // observable on the wire as `storeVersion: 150` twice.
    val twoCards = Dashboard(
      cards = Map(
        "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
        "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
      ),
      card = LayoutNode.Component(
        "col",
        children = List(
          LayoutNode.Component(
            "card",
            slots = Map("state" -> SlotSource(Some("sensor.a")))
          ),
          LayoutNode.Component(
            "card",
            slots = Map("state" -> SlotSource(Some("sensor.b")))
          )
        )
      )
    )
    liveClient(
      twoCards,
      Map(
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { (world, client) =>
      for {
        _ <- client.drain
        _ <- world.frame(List(es("sensor.a", "A1"), es("sensor.b", "B1")))
        seen <- client.drain
      } yield {
        // ONE element event carrying BOTH nodes. A morph names its target by
        // the id inside its own HTML, so a run of them shares an event.
        val elements = domEvents(seen)
        assertEquals(elements.size, 1, clue = seen)
        assert(elements.head._3.exists(_.contains("A1")), clue = seen)
        assert(elements.head._3.exists(_.contains("B1")), clue = seen)
        // ...and the frame ends ONCE. A second cursor here means the pass ran
        // twice over one instant.
        assertEquals(seen.count(isCursor), 1, clue = seen)
      }
    }
  }

  test("viewers SHARING a selection each get the fill, not just the first") {
    // What is memoised is the VERDICT, not the render. Share the render and the
    // first viewer to force it writes the digest, so the second is told its
    // branch is unchanged — and sits on an empty mount until something
    // unrelated moves. Every other multi-client test here puts its clients on
    // DIFFERENT selections, where one render each is the right answer anyway,
    // so nothing pinned the case where sharing is the whole point.
    //
    // It became reachable for fills in this design: a branch used to be
    // rendered once per connection, which cannot exhibit it.
    liveWorld(
      tabsInBranchDash,
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.shared" -> es("sensor.shared", "s0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0"),
        "sensor.z" -> es("sensor.z", "Z0")
      )
    ) { world =>
      for {
        // Two viewers on tab 0 — the same selection, so ONE render serves both.
        firstOnT0 <- world.connect()
        secondOnT0 <- world.connect()
        // ...and one elsewhere, so the shared verdict is not simply "everyone".
        onT1 <- world.connect("?ui.s_then__c=1")
        _ <- firstOnT0.drain
        _ <- secondOnT0.drain
        _ <- onT1.drain

        // Away and back: the return is the fill both tab-0 viewers must get.
        _ <- world.change(es("alarm.h", "disarmed"))
        _ <- firstOnT0.drain
        _ <- secondOnT0.drain
        _ <- onT1.drain
        _ <- world.change(es("alarm.h", "armed"))
        back1 <- firstOnT0.drain
        back2 <- secondOnT0.drain
        backT1 <- onT1.drain
      } yield {
        assert(
          back1.exists(_.renderString.contains("A0")),
          clue = ("the first viewer on tab 0 gets its branch", back1)
        )
        assert(
          back2.exists(_.renderString.contains("A0")),
          clue = (
            "the SECOND viewer on that selection must get it too — a shared " +
              "render would have let the first consume it",
            back2
          )
        )
        // Not vacuous by way of everyone getting everything: the other
        // selection still gets its own panel and neither of the others'.
        assert(
          backT1.exists(_.renderString.contains("B0")),
          clue = ("tab 1's viewer gets ITS panel", backT1)
        )
        assert(!backT1.exists(_.renderString.contains("A0")), clue = backT1)
        assert(!back1.exists(_.renderString.contains("B0")), clue = back1)
        assert(!back2.exists(_.renderString.contains("B0")), clue = back2)
      }
    }
  }

  /** A connection's LIFETIME: what a late arrival is owed, and that two clients
    * on one shared pass both stay live.
    */

  test("a client joining late is caught up, and both stay live after") {
    liveWorld(liveLeafDash, Map("sensor.a" -> es("sensor.a", "cold"))) {
      world =>
        for {
          first <- world.connect()
          _ <- first.drain
          _ <- world.change(es("sensor.a", "warm"))
          early <- first.drain
          _ = assert(
            domEvents(early).exists(_._3.exists(_.contains("warm"))),
            clue = early
          )
          // A SECOND client arrives after that change. It never saw the patch, so
          // its opening block must carry the current value — from the document
          // path, since it connects with no cursor.
          late <- world.connect()
          opening <- late.drain
          _ = assert(
            domEvents(opening).exists(_._3.exists(_.contains("warm"))),
            clue = opening
          )
          // Both are now live on the same shared pass.
          _ <- world.change(es("sensor.a", "hot"))
          e1 <- first.drain
          e2 <- late.drain
          _ = assert(
            domEvents(e1).exists(_._3.exists(_.contains("hot"))),
            clue = e1
          )
          _ = assert(
            domEvents(e2).exists(_._3.exists(_.contains("hot"))),
            clue = e2
          )
        } yield ()
    }
  }

  test("end to end: flipping there and back, one mount overwrite each time") {
    // The shape the running app showed wrong twice. Driving the diff core
    // directly could not see either: the first bug was in the resume path, the
    // second in how a replay was assembled, and both only appear once events
    // have actually travelled down a connection.
    liveClient(
      ifDash(),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { (world, client) =>
      // This fixture's branch content is a single card, so the branch root IS
      // the node — no Row wrapper (the shipped `If` wraps, the fixture does not).
      def branch(sid: String, inner: String) =
        Some(
          s"""<div class="fh-cell" id="s_${sid}__c"><span>$inner</span></div>"""
        )
      for {
        // 1. This client connects with NO cursor — it never loaded a document —
        //    so the honest answer is the whole body, once. (The document case is
        //    the separate first-load test, where the page hands its cursor back
        //    and the opening block carries no elements at all.)
        opening <- client.drain
        _ = assertEquals(
          domEvents(opening).map { case (m, s, _) => (m, s) },
          List(("inner", Some("#dashboard"))),
          clue = opening
        )
        _ = assert(opening.exists(isCursor), clue = opening)

        // 2. A tick inside the ACTIVE branch: one morph of that node alone.
        tick <- world.change(es("sensor.a", "A1")) *> client.drain
        _ = assertEquals(
          domEvents(tick),
          List(
            (
              "outer",
              None,
              Some(
                """<div class="fh-cell" id="s_then__c"><span>A1</span></div>"""
              )
            )
          ),
          clue = tick
        )

        // 3. A tick inside the HIDDEN branch: nothing at all, not even a cursor.
        //    Silence is structural — its ids never enter the selection.
        hidden <-
          world.change(es("sensor.b", "B1")) *> client.drain
        // No DOM patch. The cursor still moves — a pull reports where it got
        // to even when it owed this client nothing.
        _ = assertEquals(domEvents(hidden), Nil, clue = hidden)

        // 4. The flip: ONE overwrite of the host's mount, carrying the branch
        //    rendered at CURRENT state (B1, which this client never saw). The
        //    browser reported three events here — two removals and an append.
        flip <- world.change(es("alarm.h", "disarmed")) *> client.drain
        _ = assertEquals(
          domEvents(flip),
          List(("inner", Some("#c_0_branch"), branch("else", "B1"))),
          clue = flip
        )

        // 5. And back again — symmetric, and the then-branch returns at its
        //    CURRENT value rather than the one it had when it left.
        back <- world.change(es("alarm.h", "armed")) *> client.drain
        _ = assertEquals(
          domEvents(back),
          List(("inner", Some("#c_0_branch"), branch("then", "A1"))),
          clue = back
        )
      } yield ()
    }
  }

  /** '''A first page load must not send the body twice.'''
    *
    * Reported from the running app: loading `pkl-if` produced an
    * `Inner #dashboard` carrying the entire dashboard — every byte of which the
    * document already contained. The document knows what it is showing, so it
    * hands that back on connect (`Restore`) and the stream resumes from it
    * instead of taking the no-cursor branch.
    *
    * Follows the REAL wiring: the SSE url is read out of the rendered page's
    * `data-init`, so a mismatch between what the page advertises and what the
    * route accepts fails here rather than in a browser.
    */

}
