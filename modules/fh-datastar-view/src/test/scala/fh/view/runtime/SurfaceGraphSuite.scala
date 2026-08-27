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

/** Selection and visibility, with no server anywhere.
  *
  * The counterpart to [[MemberGraphSuite]], and the reason `SurfaceGraph` was
  * lifted out of `Renderer`: which branch of a bake group is showing, and which
  * clients a patch at a given node may reach, are pure functions of (dashboard,
  * uiState, entity state). Until the split the only way to ask was to boot a
  * `Server`, which is why `StateSurfaceSuite` and `SetMembershipSuite` — both
  * about the resulting PATCHES — were also the only cover the decisions had.
  *
  * The distinction under nearly every test here is the two activation modes. A
  * USER group's selection is per-viewer (`uiState`), so two clients disagree
  * legitimately; a STATE group's is a pure function of entity state, the same
  * for everyone, which is why a state surface hides nothing from anybody.
  */
class SurfaceGraphSuite extends munit.FunSuite {

  private def col(kids: LayoutNode*) =
    LayoutNode.Component("col", children = LayoutNode.kids(kids*))

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

  /** `SurfaceGraph` never walks the layout tree — it is handed each indexed
    * id's root — so a suite states that map directly instead of building a
    * dashboard shaped to produce it.
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

  // ---- bake groups --------------------------------------------------------

  test("branches are ordered by bakeIndex, with the surface id as tiebreak") {
    val g = graphOf(
      Map(
        "zulu" -> user("c", "t1", 1),
        "alpha" -> user("c", "t0", 0),
        // Same index: the id decides, so the order is total and stable.
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

  // ---- user selection: per viewer, and uiState is untrusted ---------------

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
    // The warning is `Some` only when a value was present but unusable — the
    // absent case above is not an anomaly, it is the normal first paint.
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

  // ---- state selection: the same for every viewer -------------------------

  test("state selection is FIRST match in bakeIndex order") {
    // Which is what makes `else` an ordinary last branch with an always-true
    // condition, and `elseif` just one more branch.
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
    // The empty conjunction is vacuously true, so the else always catches.
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
    // A tick that does not cross the condition moves nothing.
    assertEquals(flips(on, on), Nil)
  }

  test("a change to an entity no condition READS cannot flip anything") {
    // The O(1) pre-test: the changed entities decide, not the surfaces.
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
    // A group this round already flips renders its member wholesale, so
    // patching its parts too would double-emit.
    assertEquals(g.activeStateSurfaces(on, excluding = Set(gid)), Set.empty)
  }

  // ---- visibility ---------------------------------------------------------

  test("a user surface is visible only to a client that has it open") {
    val g = graphOf(Map("t0" -> user("c", "t0", 0), "t1" -> user("c", "t1", 1)))
    assert(g.visibleSurface("t0", Set("t0"), Map.empty))
    assert(!g.visibleSurface("t0", Set("t1"), Map.empty))
  }

  test("a STATE surface is visible on state alone — `open` says nothing") {
    // Its liveness belongs to the shared per-slug pass, so it never enters a
    // session's open set at all.
    val g = graphOf(Map("hot" -> state("c", "t0", 0, isOn("light.a"))))
    assert(g.visibleSurface("hot", Set.empty, snapshot(st("light.a", "on"))))
    assert(!g.visibleSurface("hot", Set("hot"), snapshot(st("light.a", "off"))))
  }

  test("a state surface is TRANSPARENT: the user tab above it decides") {
    // `userSurfaceOf` walks through the state branch to whatever encloses it,
    // because a branch of an If hides nothing — every client selects it alike.
    val g = graphOf(
      Map(
        "tab" -> user("c", "t0", 0),
        // The state group is hosted by a node inside the tab's tree.
        "branch" -> state("inner", "b0", 0, isOn("light.a"))
      ),
      roots = Map("c" -> "", "inner" -> "tab")
    )
    assertEquals(g.userSurfaceOf("tab"), Some("tab"))
    assertEquals(g.userSurfaceOf("branch"), Some("tab"))
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
    // The safe direction on purpose: over-sending costs bytes, under-sending
    // loses an update.
    val g = graphOf(Map("t0" -> user("c", "t0", 0)), roots = Map("c" -> ""))
    assert(g.visibleNode("who_knows", Set.empty, Map.empty))
  }

  // ---- selection <-> open set ---------------------------------------------

  test("selectedSurfaces and uiStateFrom are inverses over user groups") {
    assertEquals(tabs.selectedSurfaces(Map("c" -> "1")), Set("t1"))
    assertEquals(tabs.uiStateFrom(Set("t1")), Map("c" -> "1"))
    // Round trip through the default, too.
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

  // ---- popups -------------------------------------------------------------

  private def popups = graphOf(
    Map(
      "detail" -> Surface(col()),
      "other" -> Surface(col())
    )
  )

  test("a popup claim is honoured only for a surface this dashboard has") {
    // A stale URL, or another dashboard's dialog, would otherwise put a session
    // in a state its renderer cannot serve.
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

  // ---- rootOf falls through to the member graph ---------------------------

  test("rootOf answers for the static index first, then the member graph") {
    // The two node kinds the static index cannot place are a materialised
    // member and a nested set container — both leaked a surface's patches to
    // every client before `rootOf` consulted the graph for them.
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
    // `syncMembers` and nothing else fills the id index a member's root is read
    // from — a plain `membersOf` READ materialises without installing, which is
    // what keeps the graph a function of the state stream rather than of
    // whoever looked first.
    val _ = members.syncMembers(
      List(StateChange("light.a", None, states("light.a"))),
      Map.empty,
      states
    )
    val member = members.membersOf(setId, states).head
    val innerId = members.innerSetId(member.id, 0, List(0), nestedSet)

    assertEquals(g.rootOf("c"), Some(""), "static index")
    assertEquals(g.rootOf(member.id), Some("det"), "materialised member")
    assertEquals(g.rootOf(innerId), Some("det"), "nested set container")
    assertEquals(g.rootOf("nothing_here"), None)
  }

  private def nestedSet = LayoutNode.SetNode(candidates = List("light.b"))

  // ---- what a swap is entitled to assert -----------------------------------

  test("a committed selection round-trips through the state that reads it") {
    // The property, not the spelling: whatever `committedSelection` says after
    // a swap must be exactly what `resolveActive`/`openPopup` read back out of
    // ui-state. The two ends were written apart — a value shape that only one
    // of them understood would put the URL and the DOM back into the
    // disagreement pending signals exist to remove.
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
    // A state group's branch is server truth every viewer shares, so there is
    // no per-client selection to assert — asserting one would put a `ui_*` on
    // the wire that `resolveActive` is never consulted about.
    val g = graphOf(
      Map(
        "then" -> state("c", "branch", 0, isOn("light.a")),
        "else" -> state("c", "branch", 1, Predicate.And(Nil))
      )
    )
    val host = DomId.derived("c_branch")
    assertEquals(g.committedSelection(host, Some("else")), None)
    // And a surface that is not a member of the host it arrived at names no
    // index, so there is nothing truthful to say.
    assertEquals(g.committedSelection(host, Some("stranger")), None)
    assertEquals(
      g.committedSelection(DomId.derived("nobody"), Some("t0")),
      None
    )
  }
}
