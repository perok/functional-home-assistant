package fh.view.runtime

import fh.view.testkit.Sets

import fh.view.testkit.Slots

import fh.view.testkit.Conds

import fh.view.model.{
  CardDef,
  Case,
  Choice,
  Dashboard,
  Fill,
  Ix,
  LayoutNode,
  Lit,
  NodeId,
  Op,
  SetId,
  Slot,
  Step,
  Value,
  ValueExpr,
  ValueType
}
import fh.view.testkit.DashboardBuilders.{declared, st}
import fh.view.testkit.TestCard

import scala.collection.immutable.BitSet
import fh.view.testkit.TestIds.given
import io.circe.Json

/** Selection and visibility, with no server: which case of a pick shows, and
  * which clients a patch at a node may reach, are pure functions of (dashboard,
  * selections, entity state). A pick over a variable is per viewer, so clients
  * disagree legitimately; one over state is the same for everyone, so its cases
  * hide nothing.
  */
class SurfaceGraphSuite extends munit.FunSuite {

  private def col(kids: LayoutNode*) =
    LayoutNode.Component("col", slots = Slots.kids(kids*))

  private def isOn(e: String): ValueExpr =
    Conds.cmp("state", Op.Eq, Json.fromString("on"), Some(e))

  private val cards: Map[String, CardDef] = Sets.cards ++ Map(
    "host" -> TestCard("{{{branch}}}", regions = Set("branch")),
    "col" -> TestCard(
      "{{{children}}}",
      regions = Set(LayoutNode.DefaultRegion)
    ),
    "tile" -> TestCard(
      "{{{children}}}",
      regions = Set(LayoutNode.DefaultRegion)
    )
  )

  private def host(
      choice: Choice.Chosen[List[LayoutNode]],
      vars: List[Dashboard.Declaration] = Nil
  ) = LayoutNode.Component(
    "host",
    Map("branch" -> Slot.Nodes(Fill.Nodes(choice))),
    vars = vars
  )

  /** A tab bar's panel over `tab` (ADR 0033): case `"<i>"` for each of `n`,
    * `inCase` holding what a case holds.
    */
  private def tabsAt(
      n: Int,
      inCase: Map[Int, LayoutNode] = Map.empty
  ): LayoutNode.Component =
    host(
      Choice.Chosen(
        ValueExpr(Value.Var("tab")),
        (0 until n).toList.map(i =>
          Case(Lit.Text(i.toString), List(inCase.getOrElse(i, col())))
        ),
        None
      ),
      declared("tab" -> "0")
    )

  /** An If chain over state: the first condition holding, else `otherwise`. */
  private def chain(
      otherwise: Boolean,
      when: ValueExpr*
  ): LayoutNode.Component =
    host(
      Choice.Chosen(
        ValueExpr(
          Value.List(ValueType.Bool, when.toList),
          List(Step.IndexOfTrue)
        ),
        when.indices.toList.map(i => Case(Lit.Whole(i.toLong), List(col()))),
        Option.when(otherwise)(List(col()))
      )
    )

  private def rendererOf(card: LayoutNode): Renderer =
    Renderer.create(Dashboard(cards = cards, card = card))

  private def graphOf(card: LayoutNode = col()): SurfaceGraph =
    rendererOf(card).surfaces

  extension (g: SurfaceGraph)
    /** The one pick a graph built around a single host has. */
    private def gid: NodeId = (g.statePicks ++ g.varPicks).head

    /** The container a case id spells, the one clause a static case has. */
    private def ix(spelled: String): Ix = g.containersSpelled(spelled).head

  private def snapshot(states: EntityState*): Map[String, EntityState] =
    states.map(s => s.entityId -> s).toMap

  test("a pick's cases are in their order, the otherwise last") {
    val g = graphOf(chain(true, isOn("light.a"), isOn("light.b")))
    assertEquals(
      g.cases(g.gid),
      Vector("c_branch_0", "c_branch_1", "c_branch_2")
    )
  }

  test("cases is empty for anything that is not a pick's host") {
    assertEquals(graphOf().cases("c"), Vector.empty)
  }

  test("a pick over a variable is per viewer, one over state shared") {
    val u = graphOf(tabsAt(1))
    val s = graphOf(chain(false, isOn("light.a")))
    assert(!u.isStatePick(u.gid))
    assert(s.isStatePick(s.gid))
    assertEquals(u.varPicks, Set(u.gid))
    assertEquals(u.statePicks, Set.empty[NodeId])
    assertEquals(s.statePicks, Set(s.gid))
    assertEquals(s.varPicks, Set.empty[NodeId])
  }

  private lazy val tabs = graphOf(tabsAt(3))
  private lazy val gid: NodeId = tabs.gid

  private def tabAt(raw: String): VarEnv = Map(gid -> Map("tab" -> raw))

  private def viewedAt(raw: String): Shared =
    Shared.of(Map.empty).viewing(tabAt(raw))

  test("an absent selection shows nothing") {
    // The declared value fills every viewer's variables, so this is a guard.
    val absent = Shared.of(Map.empty).viewing(Map.empty)
    assertEquals(tabs.decideByVariable(gid, absent), None)
    assert(tabs.chosen(absent).isEmpty)
    assertEquals(tabs.selectionAnomalies(Map.empty), Nil)
  }

  test("a variable-selected group's index comes from the viewer's variables") {
    assertEquals(tabs.decideByVariable(gid, viewedAt("2")), Some(2))
    assertEquals(
      tabs.chosen(viewedAt("2")),
      tabs.openOf(Set(tabs.cases(gid)(2)))
    )
    assertEquals(tabs.varSelecting(gid), Some("tab"))
    assertEquals(tabs.selectionAnomalies(tabAt("2")), Nil)
  }

  test("a value no case takes shows nothing AND warns") {
    // A warning only for a present but unusable value; absent is the normal
    // first paint.
    List("banana", "9", "-1").foreach { raw =>
      assert(tabs.chosen(viewedAt(raw)).isEmpty, raw)
      assertEquals(tabs.decideByVariable(gid, viewedAt(raw)), None, raw)
      val warned = tabs.selectionAnomalies(tabAt(raw))
      assert(warned.sizeIs == 1 && warned.head.contains(raw), clue = warned)
    }
  }

  test("a state group's variable is not an anomaly — no choice exists") {
    val g = graphOf(chain(false, isOn("light.a")))
    assertEquals(
      g.selectionAnomalies(Map(g.gid -> Map("tab" -> "banana"))),
      Nil
    )
  }

  test("a chain over state picks its FIRST holding case, else the otherwise") {
    val g = graphOf(chain(true, isOn("light.a"), isOn("light.b")))
    val gid = g.gid
    val both = snapshot(st("light.a", "on"), st("light.b", "on"))
    val onlyB = snapshot(st("light.a", "off"), st("light.b", "on"))
    val neither = snapshot(st("light.a", "off"), st("light.b", "off"))
    assertEquals(g.decideByState(gid, Shared.of(both)), Some(0))
    assertEquals(g.decideByState(gid, Shared.of(onlyB)), Some(1))
    assertEquals(g.decideByState(gid, Shared.of(neither)), Some(2))
  }

  test("nothing holding and no otherwise picks NO case — the host is empty") {
    val g = graphOf(chain(false, isOn("light.a")))
    assertEquals(
      g.decideByState(g.gid, Shared.of(snapshot(st("light.a", "off")))),
      None
    )
  }

  test("a flip is reported only when the SELECTION actually moved") {
    val g = graphOf(chain(true, isOn("light.a")))
    val gid = g.gid
    val on = snapshot(st("light.a", "on"))
    val off = snapshot(st("light.a", "off"))
    def flips(before: Map[String, EntityState], now: Map[String, EntityState]) =
      g.affectedStateGroups(
        List(
          StateChange.Moved("light.a", before.get("light.a"), now("light.a"))
        ),
        Shared.of(before),
        Shared.of(now)
      )
    assertEquals(flips(on, off), List(gid))
    assertEquals(flips(on, on), Nil)
  }

  test("a change to an entity no condition READS cannot flip anything") {
    // The changed entities decide, not the surfaces.
    val g = graphOf(chain(false, isOn("light.a")))
    val before = snapshot(st("light.a", "on"), st("sensor.z", "1"))
    val now = snapshot(st("light.a", "on"), st("sensor.z", "2"))
    assertEquals(
      g.affectedStateGroups(
        List(
          StateChange.Moved("sensor.z", before.get("sensor.z"), now("sensor.z"))
        ),
        Shared.of(before),
        Shared.of(now)
      ),
      Nil
    )
  }

  test("the selected branch is shown, and a cut group's is not") {
    val g = graphOf(chain(true, isOn("light.a")))
    val t0 = g.cases(g.gid)(0)
    val on = snapshot(st("light.a", "on"))
    assertEquals(
      g.shownSurfaces(BitSet.empty, Shared.of(on)),
      g.openOf(Set(t0))
    )
    // A group already flipping renders its member wholesale; patching its parts
    // too would double-emit.
    assert(
      !g.visibleContainer(
        g.ix(t0),
        BitSet.empty,
        Shared.of(on),
        cut = Set(g.gid)
      )
    )
  }

  test("a user surface is visible only to a client that has it open") {
    val g = graphOf(tabsAt(2))
    val List(t0, t1) = g.cases(g.gid).toList: @unchecked
    val shared = Shared.of(Map.empty)
    assert(g.visibleContainer(g.ix(t0), g.openOf(Set(t0)), shared))
    assert(!g.visibleContainer(g.ix(t0), g.openOf(Set(t1)), shared))
  }

  test("a STATE surface is visible on state alone — `open` says nothing") {
    // Its liveness belongs to the shared pass, so it never enters an open set.
    val g = graphOf(chain(false, isOn("light.a")))
    val t0 = g.ix(g.cases(g.gid)(0))
    assert(
      g.visibleContainer(
        t0,
        BitSet.empty,
        Shared.of(snapshot(st("light.a", "on")))
      )
    )
    assert(
      !g.visibleContainer(
        t0,
        BitSet(t0.toInt),
        Shared.of(snapshot(st("light.a", "off")))
      )
    )
  }

  test("a state surface is TRANSPARENT: the user tab above it decides") {
    // Visibility walks through a state branch to what encloses it: an If's
    // branch hides nothing.
    val g = graphOf(tabsAt(1, Map(0 -> chain(false, isOn("light.a")))))
    val outer = g.cases(g.varPicks.head)(0)
    val inner = g.ix(g.cases(g.statePicks.head)(0))
    val on = snapshot(st("light.a", "on"))
    assert(g.visibleContainer(inner, g.openOf(Set(outer)), Shared.of(on)))
    assert(!g.visibleContainer(inner, BitSet.empty, Shared.of(on)))
  }

  test("a node inside a closed tab is not visible; on the main page it is") {
    val g = graphOf(tabsAt(2))
    val List(t0, t1) = g.cases(g.gid).toList: @unchecked
    val shared = Shared.of(Map.empty)
    assert(g.visibleNode(g.gid, BitSet.empty, shared), "main page is visible")
    assert(g.visibleNode(t1, g.openOf(Set(t1)), shared))
    assert(!g.visibleNode(t1, g.openOf(Set(t0)), shared))
  }

  test("an id the graph cannot place counts as VISIBLE") {
    // The safe direction: over-sending costs bytes, under-sending loses an
    // update.
    val g = graphOf(tabsAt(1))
    assert(g.visibleNode("who_knows", BitSet.empty, Shared.of(Map.empty)))
  }

  test("a state pick's cases never enter a viewer's screen, a tab's do") {
    // `Patches.Addressed` relies on this: tagging a patch with a state surface
    // would hide it from everybody.
    val g = graphOf(col(chain(true, isOn("light.a")), tabsAt(1)))
    val tabsGid = g.varPicks.head
    val viewed =
      Shared.of(Map.empty).viewing(Map(tabsGid -> Map("tab" -> "0")))
    assertEquals(g.chosen(viewed), g.openOf(Set(g.cases(tabsGid)(0))))
    assertEquals(g.screen(Set.empty, viewed), g.chosen(viewed))
  }

  test(
    "a member and a nested set in a case are visible only while it is shown"
  ) {
    // A materialised member and a nested set container, which the static index
    // cannot place, both leaked a case's patches to every client before.
    val setNode = Sets.node(
      candidates = List("light.a"),
      members = Map(
        "light.a" -> Sets.Member(
          List(
            Sets.Clause(
              None,
              LayoutNode.Component("tile", Slots.kids(nestedSet))
            )
          )
        )
      )
    )
    val r = rendererOf(tabsAt(2, Map(1 -> setNode)))
    val (g, members) = (r.surfaces, r.members)
    val spelled = g.cases(g.gid)(1)
    val outer = NodeId.derived(spelled)
    val setId = members.setContainer(outer).get
    val shared = Shared.of(snapshot(st("light.a", "on")))
    val member = members.membersOf(setId, shared).head
    val innerId = SetId.of(
      LayoutNode.innerSetId(
        member.id,
        0,
        List(LayoutNode.Step(LayoutNode.DefaultRegion, 0))
      ),
      Sets.of(nestedSet).get
    )

    val open = g.openOf(Set(spelled))
    List(
      "the case's set" -> outer,
      "materialised member" -> member.id,
      "nested set container" -> innerId
    ).foreach { (what, id) =>
      assert(!g.visibleNode(id, BitSet.empty, shared), what)
      assert(g.visibleNode(id, open, shared), what)
    }
    assert(g.visibleNode("nothing_here", BitSet.empty, shared))
  }

  test("a member that is not present is placed with its case") {
    // A departing member's patch names it after the frame that removed it.
    val setNode = Sets.node(
      candidates = List("light.a"),
      members = Map(
        "light.a" -> Sets.Member(
          List(
            Sets.Clause(
              Some(Conds.cmp("state", Op.Eq, Json.fromString("on"))),
              LayoutNode.Component("tile")
            )
          )
        )
      )
    )
    val r = rendererOf(tabsAt(2, Map(1 -> setNode)))
    val (g, members) = (r.surfaces, r.members)
    val spelled = g.cases(g.gid)(1)
    val setId = members.setContainer(NodeId.derived(spelled)).get
    val off = Shared.of(snapshot(st("light.a", "off")))

    assertEquals(members.membersOf(setId, off), Vector.empty)
    val gone = members.memberIdOf(setId, "light.a")
    assert(!g.visibleNode(gone, BitSet.empty, off))
    assert(g.visibleNode(gone, g.openOf(Set(spelled)), off))
  }

  private def nestedSet = Sets.node(
    candidates = List("light.b"),
    members = Map(
      "light.b" -> Sets.Member(
        List(Sets.Clause(None, LayoutNode.Component("tile")))
      )
    )
  )

}
