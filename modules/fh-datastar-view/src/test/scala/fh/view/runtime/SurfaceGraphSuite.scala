package fh.view.runtime

import fh.view.model.{
  Activation,
  Dashboard,
  DomId,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  Surface
}
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.TestIds.given
import io.circe.Json

/** Selection and visibility, with no server: which branch of a bake group
  * shows, and which clients a patch at a node may reach, are pure functions of
  * (dashboard, uiState, entity state). A user group's selection is per viewer,
  * so clients disagree legitimately; a state group's is the same for everyone,
  * so a state surface hides nothing.
  */
class SurfaceGraphSuite extends munit.FunSuite {

  private def col(kids: LayoutNode*) =
    LayoutNode.Component("col", regions = LayoutNode.kids(kids*))

  private def isOn(e: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString("on"), Some(e))

  private def user(
      into: String,
      as: String,
      idx: Int,
      defaultOpen: Boolean = false
  ): Surface =
    Surface(
      col(),
      bakeInto = Some(NodeId.derived(into)),
      bakeAs = Some(as),
      bakeIndex = Some(idx),
      activation = Activation.User(defaultOpen)
    )

  private def state(
      into: String,
      as: String,
      idx: Int,
      when: Predicate
  ): Surface =
    Surface(
      col(),
      bakeInto = Some(NodeId.derived(into)),
      bakeAs = Some(as),
      bakeIndex = Some(idx),
      activation = Activation.State(when)
    )

  /** `SurfaceGraph` is handed each indexed id's root, so the map is stated
    * directly.
    */
  private def graphOf(
      surfaces: Map[String, Surface],
      roots: Map[String, String] = Map.empty,
      members: MemberGraph = new MemberGraph(Map.empty, Map.empty)
  ): SurfaceGraph =
    new SurfaceGraph(
      surfaces,
      roots.map { case (id, root) => NodeId.derived(id) -> root },
      members
    )

  private def snapshot(states: EntityState*): Map[String, EntityState] =
    states.map(s => s.entityId -> s).toMap

  private val gid: NodeId = "c"

  test("branches are ordered by bakeIndex, with the surface id as tiebreak") {
    val g = graphOf(
      Map(
        "zulu" -> user("c", "t1", 1),
        "alpha" -> user("c", "t0", 0),
        // Same index: the id decides, so the order is total.
        "bravo" -> user("c", "t2", 1)
      )
    )
    assertEquals(g.bakeGroup(gid), List("alpha", "bravo", "zulu"))
  }

  test("a surface with no bakeIndex sorts last") {
    val g = graphOf(
      Map(
        "late" -> Surface(
          col(),
          bakeInto = Some(gid),
          bakeAs = Some("t9")
        ),
        "first" -> user("c", "t0", 0)
      )
    )
    assertEquals(g.bakeGroup(gid), List("first", "late"))
  }

  test("bakeGroup is empty for anything that is not a bake host") {
    assertEquals(graphOf(Map.empty).bakeGroup(gid), Nil)
  }

  test("the FIRST branch decides whether a group is state- or user-selected") {
    val u = graphOf(Map("t0" -> user("c", "t0", 0)))
    val s = graphOf(Map("t0" -> state("c", "t0", 0, isOn("light.a"))))
    assert(!u.isStateGroup(gid))
    assert(s.isStateGroup(gid))
    assertEquals(u.userBakeOwnerIds, Set("c"))
    assertEquals(u.stateBakeOwnerIds, Set.empty[String])
    assertEquals(s.stateBakeOwnerIds, Set("c"))
    assertEquals(s.userBakeOwnerIds, Set.empty[String])
  }

  private def tabs = graphOf(
    Map(
      "t0" -> user("c", "t0", 0),
      "t1" -> user("c", "t1", 1),
      "t2" -> user("c", "t2", 2, defaultOpen = true)
    )
  )

  test("an absent selection falls back to the defaultOpen branch") {
    assertEquals(tabs.resolveActive(gid, Map.empty), (2, None))
  }

  test("with no defaultOpen anywhere the fallback is the first branch") {
    val g = graphOf(Map("t0" -> user("c", "t0", 0), "t1" -> user("c", "t1", 1)))
    assertEquals(g.resolveActive(gid, Map.empty), (0, None))
  }

  test("a valid index is taken as given, and warns about nothing") {
    assertEquals(tabs.resolveActive(gid, Map("c" -> "1")), (1, None))
  }

  test("a malformed or out-of-range index falls back AND warns") {
    // `Some` only for a present but unusable value; absent is the normal first
    // paint.
    val (garbage, gWarn) = tabs.resolveActive(gid, Map("c" -> "banana"))
    val (high, hWarn) = tabs.resolveActive(gid, Map("c" -> "9"))
    val (negative, nWarn) = tabs.resolveActive(gid, Map("c" -> "-1"))
    assertEquals(garbage, 2)
    assertEquals(high, 2)
    assertEquals(negative, 2)
    assert(gWarn.exists(_.contains("banana")), clue = gWarn)
    assert(hWarn.isDefined && nWarn.isDefined)
  }

  test(
    "uiStateAnomalies reports exactly the branches resolveActive warned on"
  ) {
    assertEquals(tabs.uiStateAnomalies(Map("c" -> "1")), Nil)
    assertEquals(tabs.uiStateAnomalies(Map.empty), Nil)
    assertEquals(tabs.uiStateAnomalies(Map("c" -> "nope")).size, 1)
  }

  test("a state group's uiState value is not an anomaly — no choice exists") {
    val g = graphOf(Map("t0" -> state("c", "t0", 0, isOn("light.a"))))
    assertEquals(g.uiStateAnomalies(Map("c" -> "banana")), Nil)
  }

  test("state selection is FIRST match in bakeIndex order") {
    // So `else` is an ordinary last branch with an always-true condition.
    val g = graphOf(
      Map(
        "hot" -> state("c", "t0", 0, isOn("light.a")),
        "warm" -> state("c", "t1", 1, isOn("light.b")),
        "else" -> state("c", "t2", 2, Predicate.And(Nil))
      )
    )
    val both = snapshot(st("light.a", "on"), st("light.b", "on"))
    val onlyB = snapshot(st("light.a", "off"), st("light.b", "on"))
    val neither = snapshot(st("light.a", "off"), st("light.b", "off"))
    assertEquals(g.resolveActiveByState(gid, both), Some(0))
    assertEquals(g.resolveActiveByState(gid, onlyB), Some(1))
    assertEquals(g.resolveActiveByState(gid, neither), Some(2))
  }

  test("nothing holding selects NO branch — the host bakes empty") {
    val g = graphOf(Map("hot" -> state("c", "t0", 0, isOn("light.a"))))
    assertEquals(
      g.resolveActiveByState(gid, snapshot(st("light.a", "off"))),
      None
    )
  }

  test("a flip is reported only when the SELECTION actually moved") {
    val g = graphOf(
      Map(
        "hot" -> state("c", "t0", 0, isOn("light.a")),
        "else" -> state("c", "t1", 1, Predicate.And(Nil))
      ),
      roots = Map("c" -> "")
    )
    val on = snapshot(st("light.a", "on"))
    val off = snapshot(st("light.a", "off"))
    def flips(before: Map[String, EntityState], now: Map[String, EntityState]) =
      g.affectedStateGroups(
        List(StateChange("light.a", before.get("light.a"), now("light.a"))),
        before,
        now
      )
    assertEquals(flips(on, off), List(gid))
    assertEquals(flips(on, on), Nil)
  }

  test("a change to an entity no condition READS cannot flip anything") {
    // The changed entities decide, not the surfaces.
    val g = graphOf(
      Map("hot" -> state("c", "t0", 0, isOn("light.a"))),
      roots = Map("c" -> "")
    )
    val before = snapshot(st("light.a", "on"), st("sensor.z", "1"))
    val now = snapshot(st("light.a", "on"), st("sensor.z", "2"))
    assertEquals(
      g.affectedStateGroups(
        List(StateChange("sensor.z", before.get("sensor.z"), now("sensor.z"))),
        before,
        now
      ),
      Nil
    )
  }

  test("activeStateSurfaces names the selected branch, and excluding prunes") {
    val g = graphOf(
      Map(
        "hot" -> state("c", "t0", 0, isOn("light.a")),
        "else" -> state("c", "t1", 1, Predicate.And(Nil))
      ),
      roots = Map("c" -> "")
    )
    val on = snapshot(st("light.a", "on"))
    assertEquals(g.activeStateSurfaces(on), Set("hot"))
    // A group already flipping renders its member wholesale; patching its parts
    // too would double-emit.
    assertEquals(g.activeStateSurfaces(on, excluding = Set(gid)), Set.empty)
  }

  test("a user surface is visible only to a client that has it open") {
    val g = graphOf(Map("t0" -> user("c", "t0", 0), "t1" -> user("c", "t1", 1)))
    assert(g.visibleSurface("t0", Set("t0"), Map.empty))
    assert(!g.visibleSurface("t0", Set("t1"), Map.empty))
  }

  test("a STATE surface is visible on state alone — `open` says nothing") {
    // Its liveness belongs to the shared pass, so it never enters an open set.
    val g = graphOf(Map("hot" -> state("c", "t0", 0, isOn("light.a"))))
    assert(g.visibleSurface("hot", Set.empty, snapshot(st("light.a", "on"))))
    assert(!g.visibleSurface("hot", Set("hot"), snapshot(st("light.a", "off"))))
  }

  test("a state surface is TRANSPARENT: the user tab above it decides") {
    // Visibility walks through a state branch to what encloses it: an If's
    // branch hides nothing.
    val g = graphOf(
      Map(
        "tab" -> user("c", "t0", 0),
        "branch" -> state("inner", "b0", 0, isOn("light.a"))
      ),
      roots = Map("c" -> "", "inner" -> "tab")
    )
    val on = snapshot(st("light.a", "on"))
    assert(g.visibleSurface("branch", Set("tab"), on))
    assert(!g.visibleSurface("branch", Set.empty, on))
  }

  test("a node inside a closed tab is not visible; on the main page it is") {
    val g = graphOf(
      Map("t0" -> user("c", "t0", 0), "t1" -> user("c", "t1", 1)),
      roots = Map("c" -> "", "s_t1__c_0" -> "t1")
    )
    assert(g.visibleNode("c", Set.empty, Map.empty), "main page is visible")
    assert(g.visibleNode("s_t1__c_0", Set("t1"), Map.empty))
    assert(!g.visibleNode("s_t1__c_0", Set("t0"), Map.empty))
  }

  test("an id the graph cannot place counts as VISIBLE") {
    // The safe direction: over-sending costs bytes, under-sending loses an
    // update.
    val g = graphOf(Map("t0" -> user("c", "t0", 0)), roots = Map("c" -> ""))
    assert(g.visibleNode("who_knows", Set.empty, Map.empty))
  }

  test("selectedSurfaces and uiStateFrom are inverses over user groups") {
    assertEquals(tabs.selectedSurfaces(Map("c" -> "1")), Set("t1"))
    assertEquals(tabs.uiStateFrom(Set("t1")), Map("c" -> "1"))
    val defaulted = tabs.selectedSurfaces(Map.empty)
    assertEquals(defaulted, Set("t2"))
    assertEquals(tabs.uiStateFrom(defaulted), Map("c" -> "2"))
  }

  test("state-selected branches never enter a session's open set") {
    // `Patches.Addressed` relies on this: tagging a patch with a state surface
    // would hide it from everybody.
    val g = graphOf(
      Map(
        "hot" -> state("c", "t0", 0, Predicate.And(Nil)),
        "tab" -> user("d", "t0", 0, defaultOpen = true)
      )
    )
    assertEquals(g.selectedSurfaces(Map.empty), Set("tab"))
  }

  test("an unbaked surface joins the open set only when defaultOpen") {
    val g = graphOf(
      Map(
        "shown" -> Surface(col(), activation = Activation.User(true)),
        "hidden" -> Surface(col(), activation = Activation.User(false))
      )
    )
    assertEquals(g.selectedSurfaces(Map.empty), Set("shown"))
  }

  private def popups = graphOf(
    Map(
      "detail" -> Surface(col()),
      "other" -> Surface(col())
    )
  )

  test("a popup claim is honoured only for a surface this dashboard has") {
    // A stale URL or another dashboard's dialog would put the session in a
    // state its renderer cannot serve.
    val host: String = Dashboard.PopupHostId
    assertEquals(popups.openPopup(Map(host -> "detail")), Some("detail"))
    assertEquals(popups.openPopup(Map(host -> "ghost")), None)
    assertEquals(popups.openPopup(Map(host -> "")), None)
    assertEquals(popups.openPopup(Map.empty), None)
  }

  test("an open popup is part of the selection") {
    assertEquals(
      popups.selectedSurfaces(Map(Dashboard.PopupHostId -> "detail")),
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
    val setNode = LayoutNode.SetNode(
      candidates = List("light.a"),
      members = Map(
        "light.a" -> LayoutNode.SetMember(
          List(
            LayoutNode.SetClause(
              None,
              LayoutNode.Component(
                "tile",
                Map.empty,
                LayoutNode.kids(nestedSet)
              )
            )
          )
        )
      )
    )
    val outer: NodeId = "s_det__c"
    val members =
      new MemberGraph(Map(outer -> setNode), Map(outer -> "det"))
    val g = graphOf(
      Map("det" -> user("c", "t0", 0)),
      roots = Map("c" -> ""),
      members = members
    )
    val setId = members.setContainer(outer).get
    val states = snapshot(st("light.a", "on"))
    val member = members.membersOf(setId, states).head
    val innerId = members.innerSetId(
      member.id,
      0,
      List(LayoutNode.Step(LayoutNode.DefaultRegion, 0)),
      nestedSet
    )

    assertEquals(g.rootOf("c"), Some(""), "static index")
    assertEquals(g.rootOf(member.id), Some("det"), "materialised member")
    assertEquals(g.rootOf(innerId), Some("det"), "nested set container")
    assertEquals(g.rootOf("nothing_here"), None)
  }

  test("rootOf places a member that is not present") {
    // A departing member's patch names it after the frame that removed it.
    val setNode = LayoutNode.SetNode(
      candidates = List("light.a"),
      members = Map(
        "light.a" -> LayoutNode.SetMember(
          List(
            LayoutNode.SetClause(
              Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
              LayoutNode.Component("tile")
            )
          )
        )
      )
    )
    val outer: NodeId = "s_det__c"
    val members = new MemberGraph(Map(outer -> setNode), Map(outer -> "det"))
    val g = graphOf(Map("det" -> user("c", "t0", 0)), members = members)
    val setId = members.setContainer(outer).get
    val off = snapshot(st("light.a", "off"))

    assertEquals(members.membersOf(setId, off), Vector.empty)
    assertEquals(
      g.rootOf(members.memberIdOf(setId, "light.a")),
      Some("det")
    )
  }

  private def nestedSet = LayoutNode.SetNode(candidates = List("light.b"))

  test("a committed selection round-trips through the state that reads it") {
    // Whatever `committedSelection` says after a swap must be what
    // `resolveActive`/`openPopup` read back. They were written apart, and a
    // shape only one understands would re-open the URL/DOM disagreement pending
    // signals exist to remove.
    val g = graphOf(
      Map(
        "t0" -> user("c", "panel", 0, defaultOpen = true),
        "t1" -> user("c", "panel", 1),
        "det" -> Surface(col())
      )
    )
    val tabHost = DomId.derived("c_panel")

    val tab = g.committedSelection(tabHost, Some("t1"))
    assertEquals(tab, Some("c" -> "1"))
    assertEquals(
      g.resolveActive(NodeId.derived("c"), tab.toMap)._1,
      1,
      "the committed index must read back as the member it named"
    )

    val popup = g.committedSelection(Dashboard.PopupHostId, Some("det"))
    assertEquals(popup, Some(Dashboard.PopupHostId -> "det"))
    assertEquals(g.openPopup(popup.toMap), Some("det"))

    val closed = g.committedSelection(Dashboard.PopupHostId, None)
    assertEquals(closed, Some(Dashboard.PopupHostId -> ""))
    assertEquals(
      g.openPopup(closed.toMap),
      None,
      "a close must commit a value that reads back as no popup"
    )
  }

  test("nothing is committed where the client has no say") {
    // A state branch is shared server truth, so asserting a selection would put
    // a `ui_*` on the wire nothing reads.
    val g = graphOf(
      Map(
        "then" -> state("c", "branch", 0, isOn("light.a")),
        "else" -> state("c", "branch", 1, Predicate.And(Nil))
      )
    )
    val host = DomId.derived("c_branch")
    assertEquals(g.committedSelection(host, Some("else")), None)
    assertEquals(g.committedSelection(host, Some("stranger")), None)
    assertEquals(
      g.committedSelection(DomId.derived("nobody"), Some("t0")),
      None
    )
  }
}
