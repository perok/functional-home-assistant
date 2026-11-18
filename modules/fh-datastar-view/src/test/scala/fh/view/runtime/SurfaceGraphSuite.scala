package fh.view.runtime

import cats.syntax.functor.*
import fh.view.testkit.Sets

import fh.view.testkit.Slots

import fh.view.testkit.Conds

import fh.view.model.{
  Activation,
  Case,
  Choice,
  Dashboard,
  DomId,
  LayoutNode,
  Lit,
  NodeId,
  Op,
  Ready,
  Step,
  Surface,
  Value,
  ValueExpr,
  ValueType
}
import fh.view.testkit.DashboardBuilders.st
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

  /** The choice at `host`: case `i` is the surface `<host>_t<i>`, the otherwise
    * `<host>_else`.
    */
  final case class P(host: String, choice: Choice.Chosen[List[LayoutNode]])

  /** A tab bar's panel over `tab` (ADR 0033): case `"<i>"` for each of `n`. */
  private def tabsAt(host: String, n: Int): P =
    P(
      host,
      Choice.Chosen(
        ValueExpr(Value.Var("tab")),
        (0 until n).toList.map(i => Case(Lit.Text(i.toString), List(col()))),
        None
      )
    )

  /** An If chain over state: the first condition holding, else `otherwise`. */
  private def chain(host: String, otherwise: Boolean, when: ValueExpr*): P =
    P(
      host,
      Choice.Chosen(
        ValueExpr(
          Value.List(ValueType.Bool, when.toList),
          List(Step.IndexOfTrue)
        ),
        when.indices.toList.map(i => Case(Lit.Whole(i.toLong), List(col()))),
        Option.when(otherwise)(List(col()))
      )
    )

  /** `SurfaceGraph` is handed each indexed id's root, so the map is stated
    * directly.
    */
  private def graphOf(
      picks: List[P] = Nil,
      popups: Map[String, Surface] = Map.empty,
      roots: Map[String, String] = Map.empty,
      members: MemberGraph = new MemberGraph(Map.empty, Map.empty)
  ): SurfaceGraph =
    val placed = picks.map { p =>
      val keys = p.choice.cases.indices.map(i => s"t$i") ++
        p.choice.otherwise.as("else")
      val sids = keys.map(k => s"${p.host}_$k").toList
      val cases = sids.zipWithIndex.map((sid, i) =>
        sid -> Surface(
          col(),
          pick = Some(Surface.InPick(NodeId.derived(p.host), "p", None, i))
        )
      )
      (Dashboard.Pick(NodeId.derived(p.host), "p", None, p.choice, sids), cases)
    }
    new SurfaceGraph(
      (popups ++ placed.flatMap(_._2)).view.mapValues(compile.surface).toMap,
      placed.map((p, _) => p.group -> compile.pick(p)).toMap,
      roots.map { case (id, root) => NodeId.derived(id) -> root },
      members
    )

  private object compile:
    private val walk = Ready.Compile(Evaluator.Scope(""))
    def surface(s: Surface): Ready.Surface = Conds.compiled(walk.surface(s))
    def pick(p: Dashboard.Pick): Ready.Pick = Conds.compiled(walk.pick(p))
    def set(s: LayoutNode.Component): Ready.SetFill =
      Conds.compiled(walk.node(s)).set.get

  private def snapshot(states: EntityState*): Map[String, EntityState] =
    states.map(s => s.entityId -> s).toMap

  private val gid: NodeId = "c"

  test("a pick's cases are in their order, the otherwise last") {
    val g = graphOf(List(chain("c", true, isOn("light.a"), isOn("light.b"))))
    assertEquals(g.cases(gid), Vector("c_t0", "c_t1", "c_else"))
  }

  test("cases is empty for anything that is not a pick's host") {
    assertEquals(graphOf().cases(gid), Vector.empty)
  }

  test("a pick over a variable is per viewer, one over state shared") {
    val u = graphOf(List(tabsAt("c", 1)))
    val s = graphOf(List(chain("c", false, isOn("light.a"))))
    assert(!u.isStatePick(gid))
    assert(s.isStatePick(gid))
    assertEquals(u.varPicks, Set("c"))
    assertEquals(u.statePicks, Set.empty[String])
    assertEquals(s.statePicks, Set("c"))
    assertEquals(s.varPicks, Set.empty[String])
  }

  private def tabs = graphOf(List(tabsAt("c", 3)))

  private def tabAt(raw: String): VarEnv = Map(gid -> Map("tab" -> raw))

  test("an absent selection shows nothing") {
    // The declared value fills every viewer's variables, so this is a guard.
    assertEquals(tabs.decideByViewer(gid, Selections.none), None)
    assertEquals(tabs.selections(None, Map.empty), Selections.none)
    assertEquals(tabs.selectionAnomalies(Map.empty), Nil)
  }

  test("a variable-selected group's index comes from the viewer's variables") {
    val chosen = tabs.selections(None, tabAt("2"))
    assertEquals(chosen.panels, Map(gid -> 2))
    assertEquals(tabs.decideByViewer(gid, chosen), Some(2))
    assertEquals(tabs.varSelecting(gid), Some("tab"))
    assertEquals(tabs.selectionAnomalies(tabAt("2")), Nil)
  }

  test("a value no case takes shows nothing AND warns") {
    // A warning only for a present but unusable value; absent is the normal
    // first paint.
    List("banana", "9", "-1").foreach { raw =>
      assertEquals(tabs.selections(None, tabAt(raw)), Selections.none, raw)
      assertEquals(
        tabs.decideByViewer(gid, tabs.selections(None, tabAt(raw))),
        None,
        raw
      )
      val warned = tabs.selectionAnomalies(tabAt(raw))
      assert(warned.sizeIs == 1 && warned.head.contains(raw), clue = warned)
    }
  }

  test("a state group's variable is not an anomaly — no choice exists") {
    val g = graphOf(List(chain("c", false, isOn("light.a"))))
    assertEquals(g.selectionAnomalies(tabAt("banana")), Nil)
  }

  test("a chain over state picks its FIRST holding case, else the otherwise") {
    val g = graphOf(List(chain("c", true, isOn("light.a"), isOn("light.b"))))
    val both = snapshot(st("light.a", "on"), st("light.b", "on"))
    val onlyB = snapshot(st("light.a", "off"), st("light.b", "on"))
    val neither = snapshot(st("light.a", "off"), st("light.b", "off"))
    assertEquals(g.decideByState(gid, Shared.of(both)), Some(0))
    assertEquals(g.decideByState(gid, Shared.of(onlyB)), Some(1))
    assertEquals(g.decideByState(gid, Shared.of(neither)), Some(2))
  }

  test("nothing holding and no otherwise picks NO case — the host is empty") {
    val g = graphOf(List(chain("c", false, isOn("light.a"))))
    assertEquals(
      g.decideByState(gid, Shared.of(snapshot(st("light.a", "off")))),
      None
    )
  }

  test("a flip is reported only when the SELECTION actually moved") {
    val g = graphOf(
      List(chain("c", true, isOn("light.a"))),
      roots = Map("c" -> "")
    )
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
    val g = graphOf(
      List(chain("c", false, isOn("light.a"))),
      roots = Map("c" -> "")
    )
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
    val g = graphOf(
      List(chain("c", true, isOn("light.a"))),
      roots = Map("c" -> "")
    )
    val on = snapshot(st("light.a", "on"))
    assertEquals(g.shownSurfaces(Set.empty, Shared.of(on)), Set("c_t0"))
    // A group already flipping renders its member wholesale; patching its parts
    // too would double-emit.
    assert(!g.visibleSurface("c_t0", Set.empty, Shared.of(on), cut = Set(gid)))
  }

  test("a user surface is visible only to a client that has it open") {
    val g = graphOf(List(tabsAt("c", 2)))
    assert(g.visibleSurface("c_t0", Set("c_t0"), Shared.of(Map.empty)))
    assert(!g.visibleSurface("c_t0", Set("c_t1"), Shared.of(Map.empty)))
  }

  test("a STATE surface is visible on state alone — `open` says nothing") {
    // Its liveness belongs to the shared pass, so it never enters an open set.
    val g = graphOf(List(chain("c", false, isOn("light.a"))))
    assert(
      g.visibleSurface(
        "c_t0",
        Set.empty,
        Shared.of(snapshot(st("light.a", "on")))
      )
    )
    assert(
      !g.visibleSurface(
        "c_t0",
        Set("c_t0"),
        Shared.of(snapshot(st("light.a", "off")))
      )
    )
  }

  test("a state surface is TRANSPARENT: the user tab above it decides") {
    // Visibility walks through a state branch to what encloses it: an If's
    // branch hides nothing.
    val g = graphOf(
      List(tabsAt("c", 1), chain("inner", false, isOn("light.a"))),
      roots = Map("c" -> "", "inner" -> "c_t0")
    )
    val on = snapshot(st("light.a", "on"))
    assert(g.visibleSurface("inner_t0", Set("c_t0"), Shared.of(on)))
    assert(!g.visibleSurface("inner_t0", Set.empty, Shared.of(on)))
  }

  test("a node inside a closed tab is not visible; on the main page it is") {
    val g = graphOf(
      List(tabsAt("c", 2)),
      roots = Map("c" -> "", "s_c_t1__c_0" -> "c_t1")
    )
    assert(
      g.visibleNode("c", Set.empty, Shared.of(Map.empty)),
      "main page is visible"
    )
    assert(g.visibleNode("s_c_t1__c_0", Set("c_t1"), Shared.of(Map.empty)))
    assert(!g.visibleNode("s_c_t1__c_0", Set("c_t0"), Shared.of(Map.empty)))
  }

  test("an id the graph cannot place counts as VISIBLE") {
    // The safe direction: over-sending costs bytes, under-sending loses an
    // update.
    val g = graphOf(List(tabsAt("c", 1)), roots = Map("c" -> ""))
    assert(g.visibleNode("who_knows", Set.empty, Shared.of(Map.empty)))
  }

  test("selectedSurfaces and selectionsIn are inverses over chosen groups") {
    val chosen = Selections(None, Map(gid -> 1))
    assertEquals(tabs.selectedSurfaces(chosen), Set("c_t1"))
    assertEquals(tabs.selectionsIn(Set("c_t1")), chosen)
    val none = tabs.selectedSurfaces(Selections.none)
    assertEquals(none, Set.empty[String])
    assertEquals(tabs.selectionsIn(none), Selections.none)
    val det = Selections(Some("detail"), Map.empty)
    assertEquals(popups.selectionsIn(popups.selectedSurfaces(det)), det)
  }

  test("state-selected branches never enter a session's open set") {
    // `Patches.Addressed` relies on this: tagging a patch with a state surface
    // would hide it from everybody.
    val g = graphOf(List(chain("c", true, isOn("light.a")), tabsAt("d", 1)))
    assertEquals(
      g.selectedSurfaces(Selections(None, Map(NodeId.derived("d") -> 0))),
      Set("d_t0")
    )
  }

  test("an unbaked surface joins the open set only when defaultOpen") {
    val g = graphOf(popups =
      Map(
        "shown" -> Surface(col(), activation = Activation.User(true)),
        "hidden" -> Surface(col(), activation = Activation.User(false))
      )
    )
    assertEquals(g.selectedSurfaces(Selections.none), Set("shown"))
  }

  private def popups = graphOf(popups =
    Map(
      "detail" -> Surface(col()),
      "other" -> Surface(col())
    )
  )

  test("a popup claim is honoured only for a surface this dashboard has") {
    // A stale URL or another dashboard's dialog would put the session in a
    // state its renderer cannot serve.
    assertEquals(popups.openPopup(Some("detail")), Some("detail"))
    assertEquals(popups.openPopup(Some("ghost")), None)
    assertEquals(popups.openPopup(Some("")), None)
    assertEquals(popups.openPopup(None), None)
    assertEquals(popups.selections(Some("ghost"), Map.empty), Selections.none)
  }

  test("an open popup is part of the selection") {
    assertEquals(
      popups.selectedSurfaces(popups.selections(Some("detail"), Map.empty)),
      Set("detail")
    )
  }

  test("surfacesAt names every surface sharing a host — the eviction group") {
    assertEquals(
      popups.surfacesAt(Dashboard.PopupHostId),
      Set("detail", "other")
    )
    assertEquals(popups.surfacesAt(DomId.derived("elsewhere")), Set.empty)
  }

  test("rootOf answers for the static index first, then the member graph") {
    // A materialised member and a nested set container, which the static index
    // cannot place, both leaked a surface's patches to every client before.
    val setNode = Sets.node(
      candidates = List("light.a"),
      members = Map(
        "light.a" -> Sets.Member(
          List(
            Sets.Clause(
              None,
              LayoutNode.Component(
                "tile",
                Slots.kids(nestedSet)
              )
            )
          )
        )
      )
    )
    val outer: NodeId = "s_det__c"
    val members =
      new MemberGraph(Map(outer -> compile.set(setNode)), Map(outer -> "det"))
    val g = graphOf(
      popups = Map("det" -> Surface(col())),
      roots = Map("c" -> ""),
      members = members
    )
    val setId = members.setContainer(outer).get
    val states = snapshot(st("light.a", "on"))
    val member = members.membersOf(setId, Shared.of(states)).head
    val innerId = members.innerSetId(
      member.id,
      0,
      List(LayoutNode.Step(LayoutNode.DefaultRegion, 0)),
      Sets.of(nestedSet).get
    )

    assertEquals(g.rootOf("c"), Some(""), "static index")
    assertEquals(g.rootOf(member.id), Some("det"), "materialised member")
    assertEquals(g.rootOf(innerId), Some("det"), "nested set container")
    assertEquals(g.rootOf("nothing_here"), None)
  }

  test("rootOf places a member that is not present") {
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
    val outer: NodeId = "s_det__c"
    val members =
      new MemberGraph(Map(outer -> compile.set(setNode)), Map(outer -> "det"))
    val g = graphOf(popups = Map("det" -> Surface(col())), members = members)
    val setId = members.setContainer(outer).get
    val off = snapshot(st("light.a", "off"))

    assertEquals(members.membersOf(setId, Shared.of(off)), Vector.empty)
    assertEquals(
      g.rootOf(members.memberIdOf(setId, "light.a")),
      Some("det")
    )
  }

  private def nestedSet = Sets.node(candidates = List("light.b"))

  test("a committed selection round-trips through the state that reads it") {
    // Whatever `committedSelection` says after a swap must be what `openPopup`
    // reads back. They were written apart, and a shape only one understands
    // would re-open the URL/DOM disagreement pending signals exist to remove.
    val g = graphOf(List(tabsAt("c", 2)), popups = Map("det" -> Surface(col())))
    // A tab's commit is its variable's, sent by the write (`Server.setVar`).
    assertEquals(
      g.committedSelection(DomId.derived("c_p"), Some("c_t1")),
      None
    )
    assertEquals(
      g.committedSelections(Set("c_t1")).keySet,
      Set[String](Dashboard.PopupHostId)
    )

    val popup = g.committedSelection(Dashboard.PopupHostId, Some("det"))
    assertEquals(popup, Some(Dashboard.PopupHostId -> "det"))
    assertEquals(g.openPopup(popup.map(_._2)), Some("det"))

    val closed = g.committedSelection(Dashboard.PopupHostId, None)
    assertEquals(closed, Some(Dashboard.PopupHostId -> ""))
    assertEquals(
      g.openPopup(closed.map(_._2)),
      None,
      "a close must commit a value that reads back as no popup"
    )
  }

  test("nothing is committed where the client has no say") {
    // A state branch is shared server truth, so asserting a selection would put
    // a `ui_*` on the wire nothing reads.
    val g = graphOf(List(chain("c", true, isOn("light.a"))))
    val host = DomId.derived("c_p")
    assertEquals(g.committedSelection(host, Some("c_else")), None)
    assertEquals(g.committedSelection(host, Some("stranger")), None)
    assertEquals(
      g.committedSelection(DomId.derived("nobody"), Some("t0")),
      None
    )
  }
}
