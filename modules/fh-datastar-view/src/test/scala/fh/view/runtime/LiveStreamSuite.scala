package fh.view.runtime

import fh.view.query.QuerySnapshot
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Region,
  SlotSource,
  Surface
}
import fh.view.testkit.FixtureEntity
import fh.view.testkit.TestIds.given
import io.circe.Json

/** End to end over the real stream, with several clients: what one connection
  * is sent is half the contract, and what the others are not sent cannot be
  * observed from a single stream. Events are asserted in full, since
  * `!raw.contains(…)` also passes on a renamed selector or an event that never
  * arrived.
  */
class LiveStreamSuite extends ServerHarness {

  /** The property ADR 0002's collapse must preserve. Under-sending has no
    * symptom: a withheld patch is just a value that quietly stops updating.
    */
  test("two clients on different tabs: each sees only its own") {
    live(
      twoTabsDash,
      Map(
        "sensor.shared" -> es("sensor.shared", "s0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { world =>
      for {
        onT0 <- world.connect()
        onT1 <- world.connect(onTabs("c_1" -> 1))
        _ <- onT0.drain
        _ <- onT1.drain

        _ <- world.change("sensor.a", "A1")
        a0 <- onT0.drain
        a1 <- onT1.drain
        _ = assert(
          domEvents(a0).exists(_._3.exists(_.contains("A1"))),
          clue = ("viewer of tab 0 must get it", a0)
        )
        // No events at all, not even a cursor: that rides the keepalive, which
        // is why `TestServer.change` gates on the server.
        _ = assertEquals(
          a1,
          Nil,
          clue = ("viewer of tab 1 must get nothing", a1)
        )

        _ <- world.change("sensor.b", "B1")
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

        // A main-page change reaches both: the filter must not swallow what is
        // not surface-scoped.
        _ <- world.change("sensor.shared", "s1")
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

  /** Tabs inside a flipping branch, the shape that keeps
    * [[Renderer.sessionOnlyStateGroups]] on the per-session pass. Rendering the
    * tabs host on the shared pass would hand every client the default tab,
    * yanking viewers off the tab they picked.
    */

  private def tabsInBranchDash = Dashboard(
    cards = Map(
      "col" -> CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "ifhost" -> CardDef(
        template =
          """<div id="{{hostId}}">{{#branch}}{{{html}}}{{/branch}}</div>""",
        regions = Map("branch" -> Region(Region.Baked))
      ),
      "tabs" -> CardDef(
        template =
          """<div id="{{hostId}}" class="tabs">{{#panel}}{{{html}}}{{/panel}}</div>""",
        regions = Map("panel" -> Region(Region.Baked))
      )
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.shared")))
        ),
        LayoutNode.Component("ifhost")
      )
    ),
    surfaces = Map(
      "then" -> stateMember(
        LayoutNode.Component("tabs", vars = TabDeclared),
        "c_1",
        0,
        armedCond
      ),
      "else" -> stateMember(branchCard("sensor.z"), "c_1", 1, always),
      "t0" -> tabMember(
        branchCard("sensor.a"),
        "s_then__c",
        0
      ),
      "t1" -> tabMember(
        branchCard("sensor.b"),
        "s_then__c",
        1
      )
    )
  )

  /** Structure is refused by id: its rendering holds hosts, and the log is per
    * slug, so a digest for it is one viewer's bytes presented as everyone's.
    * The failure it caused: a client on tab 1 reconnected and was morphed onto
    * tab 0.
    */

  private def barePopupTabsDash = Dashboard(
    cards = Map(
      // Pure structure, like the shipped `Column`: no markup of its own to
      // fingerprint.
      "col" -> CardDef(
        template = "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template =
          """<div>bar</div><div id="{{hostId}}">{{#panel}}{{{html}}}{{/panel}}</div>""",
        regions = Map("panel" -> Region(Region.Baked))
      )
    ),
    card = LayoutNode.Component("col"),
    surfaces = Map(
      "det" -> Surface(
        LayoutNode.Component(
          "col",
          regions =
            LayoutNode.kids(LayoutNode.Component("tabs", vars = TabDeclared))
        )
      ),
      "t0" -> tabMember(
        branchCard("sensor.a"),
        "s_det__c_0",
        0
      ),
      "t1" -> tabMember(
        branchCard("sensor.b"),
        "s_det__c_0",
        1
      )
    )
  )

  test("a fill records what it put there, so the next tick suppresses") {
    // Opening a surface teaches the session each node's bytes from the same
    // render, so a tick to the same value is "unchanged", not "never told",
    // and sends nothing.
    val dash = Dashboard(
      cards = Map(
        "col" -> CardDef(
          template = "<div>{{#children}}{{{html}}}{{/children}}</div>",
          regions = Map("children" -> Region())
        ),
        "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
      ),
      card = LayoutNode.Component("col"),
      surfaces = Map(
        "det" -> Surface(
          LayoutNode.Component(
            "col",
            regions = LayoutNode.kids(branchCard("sensor.a"))
          )
        )
      )
    )
    val painted = Held.of(
      Renderer
        .create(dash)
        .renderNodeById(
          "s_det__c_0",
          Map("sensor.a" -> es("sensor.a", "cold")),
          fragments = QuerySnapshot.empty
        )
        .get
    )
    val node = NodeId.derived("s_det__c_0")
    live(dash, Map("sensor.a" -> es("sensor.a", "cold"))) { ts =>
      for {
        v <- ts.viewer()
        beforeFill <- v.session.state
          .map(_.holds)
          .map(_.get(node).contains(painted))
        _ <- ts.post(
          s"sse/surface/${ts.slug}/open/det",
          body = s"""{"${Server.ConnSignal}":"${v.document.conn}"}"""
        )
        afterFill <- v.session.state
          .map(_.holds)
          .map(_.get(node).contains(painted))
        // A frame the card renders identically: an attribute it does not read.
        same <- v.step(
          FixtureEntity("sensor.a", "cold", Map("noise" -> Json.fromInt(1)))
        )
      } yield {
        // Not vacuous: the claim did not exist until the fill made it.
        assert(!beforeFill, clue = "nothing claimed before the surface opened")
        assert(afterFill, clue = "the fill must claim the node it painted")
        assertEquals(same, Nil, clue = same.map(_.render))
      }
    }
  }

  private val armedAB = Map(
    "alarm.h" -> es("alarm.h", "armed"),
    "sensor.a" -> es("sensor.a", "A0"),
    "sensor.b" -> es("sensor.b", "B0")
  )

  test("a queued flip that a later one superseded is dropped, not sent") {
    // The first flip was planned against a selection that has since moved, so
    // its bytes would put the wrong branch on screen; the log recorded that
    // member as Gone. Both frames land before the one pull.
    live(ifDash(), armedAB) { ts =>
      for {
        v <- ts.viewer()
        _ <- ts.record(FixtureEntity("alarm.h", "disarmed"))
        _ <- ts.record(FixtureEntity("alarm.h", "armed"))
        out <- v.pull.map(elementPatches)
      } yield {
        assertEquals(out.size, 1, clue = out)
        assert(out.head.contains("""id="s_then__c""""), clue = out.head)
        assert(!out.head.contains("""id="s_else__c""""), clue = out.head)
      }
    }
  }

  test("a branch that empties removes its content, never the host") {
    // The host must survive: every later fill targets it by id, and a patch at
    // a missing id is a silent no-op, so the group would go dead for that
    // client.
    val d = ifDash().copy(surfaces =
      Map("then" -> stateMember(branchCard("sensor.a"), "c_0", 0, armedCond))
    )
    live(d, armedAB - "sensor.b") { ts =>
      for {
        v <- ts.viewer()
        emptied <- v.change(es("alarm.h", "disarmed")).map(_.map(_.render))
        refilled <- v.change(es("alarm.h", "armed")).map(elementPatches)
      } yield {
        val removes = emptied.filter(_.contains("mode remove"))
        assertEquals(removes.size, 1, clue = emptied)
        assert(
          removes.head.contains("selector #s_then__c"),
          clue = removes.head
        )
        assert(!removes.head.contains("#c_0_branch"), clue = removes.head)
        assertEquals(refilled.size, 1, clue = refilled)
        assert(refilled.head.contains("selector #c_0_branch"), clue = refilled)
      }
    }
  }

  test("a fill fingerprints the nodes it placed, not the blob") {
    // A fill re-supplies a whole subtree, so the session must know what it put
    // in each node. A claim under the branch root, a bare container, could
    // never be resolved.
    val d = Dashboard(
      cards = ifCards ++ Map(
        "col" -> CardDef(
          template = "<div>{{#children}}{{{html}}}{{/children}}</div>",
          regions = Map("children" -> Region())
        )
      ),
      card = LayoutNode
        .Component(
          "col",
          regions = LayoutNode.kids(LayoutNode.Component("ifhost"))
        ),
      surfaces = Map(
        "then" -> stateMember(
          LayoutNode.Component(
            "col",
            regions = LayoutNode.kids(branchCard("sensor.a"))
          ),
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
      Patches
        .hostFill(
          r,
          r.hostId("c_0"),
          Some("then"),
          armed,
          Selections.none,
          fragments = QuerySnapshot.empty
        )
        .get

    // Holding exactly what a patch for that node alone would carry, which makes
    // the two comparable.
    val leaf: NodeId = "s_then__c_0"
    assertEquals(
      patch.establishes.get(leaf),
      r.renderNodeById(leaf, armed, fragments = QuerySnapshot.empty)
        .map(Held.of)
    )
    val root = NodeId.derived("s_then__c")
    assertEquals(
      r.renderNodeById(root, armed, fragments = QuerySnapshot.empty),
      None
    )
    assert(!patch.establishes.contains(root), clue = patch.establishes.keySet)
  }

  test("a resume sends one viewer's bar bytes that fit every viewer") {
    // The selection lives on the structure (rendered where the viewer is known)
    // and in the client's own signal, so a resume's bytes fit every viewer.
    // Asserted because the failure it replaced, a tab-1 client handed tab 0's
    // bar, was silent.
    val r = Renderer.create(serverHighlightDash)
    val states = Map(
      "sensor.title" -> es("sensor.title", "T1"),
      "sensor.a" -> es("sensor.a", "A0"),
      "sensor.b" -> es("sensor.b", "B0")
    )
    val host: NodeId = "c_0_bar_0"
    val mine = Selections(None, Map(NodeId.derived("c_0") -> 1))
    // Recorded as holding what a tab-0 connect left behind.
    val log = FragmentLog("w23").touched(host, 5L)
    val holds: Map[NodeId, Held] =
      Map(
        host -> Held.of(
          r.renderNodeById(host, states, fragments = QuerySnapshot.empty).get
        )
      )
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
      !owed.exists(_.patch.toSse.render.contains(host: String)),
      clue = (
        "one rendering fits every viewer, so there is nothing to correct",
        owed.map(_.patch.toSse.render)
      )
    )
    // Owed per viewer because it is a different surface, not because one node
    // has two renderings.
    assert(
      owed.exists(_.patch.toSse.render.contains("s_t1__c")),
      clue = owed.map(_.patch.toSse.render)
    )
    assert(
      !owed.exists(_.patch.toSse.render.contains("active-")),
      clue = owed.map(_.patch.toSse.render)
    )
  }

  test("a resume cannot move a viewer onto a tab it did not choose") {
    val r = Renderer.create(barePopupTabsDash)
    val before =
      Map(
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    val open = Set("det", "t1")
    val mine = Selections(None, Map(NodeId.derived("s_det__c_0") -> 1))
    // Exactly as `pageResponse` records it.
    val ids =
      (r.surfaceNodeIds("det") ++ r.surfaceNodeIds("t1")).toList.sorted
    val seeded = FragmentLog("w18")
    val held = ids.flatMap { id =>
      r.renderNodeById(id, before, mine, fragments = QuerySnapshot.empty)
        .map(h => id -> Held.of(h))
    }.toMap

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
      !owed.exists(_.patch.toSse.render.contains("s_t0__c")),
      clue = owed.map(_.patch.toSse.render)
    )
    assert(!owed.exists(_.patch.toSse.render.contains("A1")), clue = owed)

    // Not vacuous: without this the test would pass by sending nothing, ever.
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
      mineOwed.exists(_.patch.toSse.render.contains("B1")),
      clue = mineOwed.map(_.patch.toSse.render)
    )
  }

  /** ADR 0007's "an inactive branch costs nothing", for a user surface nested
    * in one. `selectedSurfaces` reports a selection for every bake group
    * whether on screen or not, so a tab panel in a hidden `If` is in the open
    * set; pushing it is harmless and pure waste.
    */

  test("a tab panel inside a HIDDEN branch costs nothing") {
    live(
      tabsInBranchDash,
      Map(
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
        _ <- world.change("sensor.a", "A1")
        hidden <- c.drain
        _ = assertEquals(domEvents(hidden), Nil, clue = hidden)

        // Not vacuous: a change the client can see still arrives.
        _ <- world.change("sensor.shared", "s1")
        seen <- c.drain
        _ = assert(
          domEvents(seen).exists(_._3.exists(_.contains("s1"))),
          clue = seen
        )
      } yield ()
    }
  }

  test("a reconnect is not owed another client's tab") {
    // A change in tab 0's panel is logged, so the cursor names it; the tab-1
    // viewer's resume must not carry it. The cursor knows what changed, not who
    // is looking.
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
      Selections.none
    )
    assert(
      !owed.exists(_.patch.toSse.render.contains("A1")),
      clue = owed.map(_.patch.toSse.render)
    )
  }

  /** A bar rendering its active tab server-side via `{{bakeIndex}}`. It painted
    * correctly and then blanked on the first tick: the document path passed
    * `bakeIndex`, the patch path did not.
    */

  private def serverHighlightDash = Dashboard(
    cards = Map(
      "col" -> CardDef(
        template = "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template =
          """<div class="active-{{bakeIndex}}">{{#bar}}{{{html}}}{{/bar}}</div><div id="{{hostId}}">{{#panel}}{{{html}}}{{/panel}}</div>""",
        regions = Map("bar" -> Region(), "panel" -> Region(Region.Baked))
      ),
      "bar" -> CardDef("""<div>{{title}}</div>""", slots = List("title"))
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.Component(
          "tabs",
          regions = Map(
            "bar" -> List(
              LayoutNode.Component(
                "bar",
                slots = Map("title" -> SlotSource(Some("sensor.title")))
              )
            )
          ),
          vars = TabDeclared
        )
      )
    ),
    surfaces = Map(
      "t0" -> tabMember(
        branchCard("sensor.a"),
        "c_0",
        0
      ),
      "t1" -> tabMember(
        branchCard("sensor.b"),
        "c_0",
        1
      )
    )
  )

  test(
    "a variant keeps its own digest, so an unchanged tick is suppressed"
  ) {
    // Variants of a bake owner are static, one per member, so each has its own
    // digest: two viewers on different tabs get their own suppression.
    live(
      serverHighlightDash,
      Map(
        "sensor.title" -> es("sensor.title", "T0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { world =>
      for {
        onT0 <- world.connect()
        onT1 <- world.connect(onTabs("c_0" -> 1))
        _ <- onT0.drain
        _ <- onT1.drain
        _ <- world.change("sensor.title", "T1")
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
        // Only an attribute moved, so the title renders identically and nothing
        // goes out.
        _ <- world.frame(
          FixtureEntity(
            "sensor.title",
            "T1",
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

  test("a tick sends both viewers the same bar, carrying no selection") {
    live(
      serverHighlightDash,
      Map(
        "sensor.title" -> es("sensor.title", "T0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { world =>
      for {
        onT0 <- world.connect()
        onT1 <- world.connect(onTabs("c_0" -> 1))
        _ <- onT0.drain
        _ <- onT1.drain
        _ <- world.change("sensor.title", "T1")
        a <- onT0.drain
        b <- onT1.drain
        _ = assert(
          domEvents(a).exists(_._3.exists(_.contains("T1"))),
          clue = ("tab 0's viewer gets the new title", a)
        )
        _ = assert(
          domEvents(b).exists(_._3.exists(_.contains("T1"))),
          clue = ("tab 1's viewer gets the new title", b)
        )
        _ = assert(
          !domEvents(a).exists(_._3.exists(_.contains("active-"))),
          clue = a
        )
        _ = assert(
          !domEvents(b).exists(_._3.exists(_.contains("active-"))),
          clue = b
        )
      } yield ()
    }
  }

  test("a flip re-reveals each client's OWN tab, not the default one") {
    live(
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
        onT1 <- world.connect(onTabs("s_then__c" -> 1))
        open0 <- onT0.drain
        open1 <- onT1.drain
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

        _ <- world.change("alarm.h", "disarmed")
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

        _ <- world.change("alarm.h", "armed")
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

        _ <- world.change("sensor.a", "A1")
        a0 <- onT0.drain
        a1 <- onT1.drain
        _ = assert(
          domEvents(a0).exists(_._3.exists(_.contains("A1"))),
          clue = a0
        )
        _ = assertEquals(domEvents(a1), Nil, clue = a1)
        _ <- world.change("sensor.b", "B1")
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
    // A frame's diffs bump the store version once. Publishing per entity split
    // that instant into N passes, seen on the wire as `storeVersion: 150`
    // twice.
    val twoCards = Dashboard(
      cards = Map(
        "col" -> CardDef(
          "<div>{{#children}}{{{html}}}{{/children}}</div>",
          regions = Map("children" -> Region())
        ),
        "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
      ),
      card = LayoutNode.Component(
        "col",
        regions = LayoutNode.kids(
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
    liveOne(
      twoCards,
      Map(
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { (world, client) =>
      for {
        _ <- client.drain
        _ <- world.frame(
          FixtureEntity("sensor.a", "A1"),
          FixtureEntity("sensor.b", "B1")
        )
        seen <- client.drain
      } yield {
        // A morph names its target by the id inside its own HTML, so a run
        // shares an event.
        val elements = domEvents(seen)
        assertEquals(elements.size, 1, clue = seen)
        assert(elements.head._3.exists(_.contains("A1")), clue = seen)
        assert(elements.head._3.exists(_.contains("B1")), clue = seen)
        assertEquals(seen.count(isCursor), 1, clue = seen)
      }
    }
  }

  test(
    "viewers SHARING a selection each get the fill, not just the first"
  ) {
    // The verdict is memoised, not the render. Sharing the render let the first
    // viewer write the digest, so the second was told its branch was unchanged
    // and sat on an empty host. The other multi-client tests use different
    // selections, so none pinned this.
    live(
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
        firstOnT0 <- world.connect()
        secondOnT0 <- world.connect()
        // So the shared verdict is not simply "everyone".
        onT1 <- world.connect(onTabs("s_then__c" -> 1))
        _ <- firstOnT0.drain
        _ <- secondOnT0.drain
        _ <- onT1.drain

        _ <- world.change("alarm.h", "disarmed")
        _ <- firstOnT0.drain
        _ <- secondOnT0.drain
        _ <- onT1.drain
        _ <- world.change("alarm.h", "armed")
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

  test("a client joining late is caught up, and both stay live after") {
    live(liveLeafDash, Map("sensor.a" -> es("sensor.a", "cold"))) { world =>
      for {
        first <- world.connect()
        _ <- first.drain
        _ <- world.change("sensor.a", "warm")
        early <- first.drain
        _ = assert(
          domEvents(early).exists(_._3.exists(_.contains("warm"))),
          clue = early
        )
        // It never saw the patch and connects with no cursor, so its opening
        // block carries the current value from the document path.
        late <- world.connect()
        opening <- late.drain
        _ = assert(
          domEvents(opening).exists(_._3.exists(_.contains("warm"))),
          clue = opening
        )
        _ <- world.change("sensor.a", "hot")
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

  test(
    "end to end: flipping there and back, one host overwrite each time"
  ) {
    // The running app got this wrong twice, in the resume path and in replay
    // assembly; both only appear once events travel down a connection.
    liveOne(
      ifDash(),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { (world, client) =>
      // The fixture's branch content is one card, so the branch root is the
      // node (the shipped `If` wraps in a Row).
      def branch(sid: String, inner: String) =
        Some(
          s"""<div class="fh-cell" id="s_${sid}__c"><span>$inner</span></div>"""
        )
      for {
        // 1. No cursor, since it never loaded a document, so the whole body
        // once.
        opening <- client.drain
        _ = assertEquals(
          domEvents(opening).map { case (m, s, _) => (m, s) },
          List(("inner", Some("#dashboard"))),
          clue = opening
        )
        _ = assert(opening.exists(isCursor), clue = opening)

        tick <- world.change("sensor.a", "A1") *> client.drain
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

        // 3. A tick inside the hidden branch: its ids never enter the
        // selection.
        hidden <-
          world.change("sensor.b", "B1") *> client.drain
        // The cursor still moves: a pull reports where it got to even when it
        // owed this client nothing.
        _ = assertEquals(domEvents(hidden), Nil, clue = hidden)

        // 4. One overwrite of the host, at current state (B1, never seen here).
        // The browser reported three events here: two removals and an append.
        flip <- world.change("alarm.h", "disarmed") *> client.drain
        _ = assertEquals(
          domEvents(flip),
          List(("inner", Some("#c_0_branch"), branch("else", "B1"))),
          clue = flip
        )

        // 5. The then-branch returns at its current value.
        back <- world.change("alarm.h", "armed") *> client.drain
        _ = assertEquals(
          domEvents(back),
          List(("inner", Some("#c_0_branch"), branch("then", "A1"))),
          clue = back
        )
      } yield ()
    }
  }
}
