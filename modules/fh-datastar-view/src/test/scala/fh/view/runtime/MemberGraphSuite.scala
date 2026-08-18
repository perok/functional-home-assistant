package fh.view.runtime

import fh.view.model.{
  LayoutNode,
  MemberId,
  NodeId,
  Op,
  Predicate,
  SetId,
  SlotSource
}
import fh.view.testkit.DashboardBuilders.{lit, st}
import io.circe.Json

/** Presence and order, with no server anywhere.
  *
  * This suite is the reason [[MemberGraph]] was lifted out of `Renderer`.
  * Everything here — which candidates render, in what order, and what one frame
  * did to that — is a pure function of (candidates, state), but until the split
  * the only way to ask was to boot a `Server` through `ServerHarness` and read
  * the answer back off the wire. `SetMembershipSuite` still does that, and
  * should: it is about the PATCHES. This one is about the decision.
  */
class MemberGraphSuite extends munit.FunSuite {

  private val gid: SetId = SetId.of(NodeId.derived("c_0"))

  private def card(e: String, kind: String): LayoutNode.Component =
    LayoutNode.Component(kind, Map("entity_id" -> lit(e)))

  private def clause(
      e: String,
      when: Option[Predicate] = None,
      as: String = "entity"
  ): LayoutNode.SetClause = LayoutNode.SetClause(when, card(e, as))

  private def isOn(e: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString("on"), Some(e))

  private def set(
      candidates: List[String],
      clauses: String => List[LayoutNode.SetClause] = e => List(clause(e)),
      orderBy: List[LayoutNode.SortTerm] = Nil,
      limit: Option[Int] = None
  ): LayoutNode.SetNode =
    LayoutNode.SetNode(
      candidates = candidates,
      members =
        candidates.map(e => e -> LayoutNode.SetMember(clauses(e))).toMap,
      orderBy = orderBy,
      limit = limit
    )

  private def graphOf(
      s: LayoutNode.SetNode,
      root: String = ""
  ): MemberGraph = new MemberGraph(Map(gid -> s), Map(gid -> root))

  private def snapshot(states: EntityState*): Map[String, EntityState] =
    states.map(s => s.entityId -> s).toMap

  private def prop(name: String, dir: String = "asc") =
    LayoutNode.SortTerm(LayoutNode.SortKey.Prop(name), dir)

  // ---- presence -----------------------------------------------------------

  test("an unguarded clause is present whatever the state says") {
    val g = graphOf(set(List("light.a", "light.b")))
    assertEquals(
      g.memberEntities(gid, snapshot(st("light.a", "off"))),
      List("light.a", "light.b")
    )
  }

  test("falling off the end of the clauses means NOT RENDERED") {
    val g = graphOf(
      set(
        List("light.a", "light.b"),
        e => List(clause(e, Some(isOn(e))))
      )
    )
    assertEquals(
      g.memberEntities(
        gid,
        snapshot(st("light.a", "on"), st("light.b", "off"))
      ),
      List("light.a")
    )
  }

  test("a guard may name a DIFFERENT entity than the member") {
    val g = graphOf(
      set(
        List("light.a"),
        e => List(clause(e, Some(isOn("binary_sensor.hall"))))
      )
    )
    val shown = snapshot(st("light.a", "off"), st("binary_sensor.hall", "on"))
    val hidden = snapshot(st("light.a", "on"), st("binary_sensor.hall", "off"))
    assertEquals(g.memberEntities(gid, shown), List("light.a"))
    assertEquals(g.memberEntities(gid, hidden), Nil)
  }

  test("a candidate HA has never heard of still gets its clauses evaluated") {
    // Evaluated against an empty state rather than dropped, so a clause guarded
    // only on ANOTHER entity still decides.
    val g = graphOf(
      set(List("light.ghost"), e => List(clause(e, Some(isOn("sensor.hall")))))
    )
    assertEquals(
      g.memberEntities(gid, snapshot(st("sensor.hall", "on"))),
      List("light.ghost")
    )
  }

  test("the FIRST matching clause decides which node a member is") {
    val g = graphOf(
      set(
        List("light.a"),
        e =>
          List(clause(e, Some(isOn(e)), as = "slider"), clause(e, as = "pill"))
      )
    )
    def cardOf(s: Map[String, EntityState]) =
      g.membersOf(gid, s).map(_.node.card).toList
    assertEquals(cardOf(snapshot(st("light.a", "on"))), List("slider"))
    assertEquals(cardOf(snapshot(st("light.a", "off"))), List("pill"))
  }

  // ---- order --------------------------------------------------------------

  test("with no orderBy the AUTHORED candidate order is the order") {
    val g = graphOf(set(List("light.z", "light.a")))
    assertEquals(
      g.memberEntities(gid, snapshot(st("light.z", "on"), st("light.a", "on"))),
      List("light.z", "light.a")
    )
  }

  test("a numeric property sorts numerically, not lexicographically") {
    val g = graphOf(
      set(List("light.a", "light.b"), orderBy = List(prop("attr:brightness")))
    )
    val states = snapshot(
      st("light.a", "on", "brightness" -> Json.fromInt(10)),
      st("light.b", "on", "brightness" -> Json.fromInt(2))
    )
    assertEquals(g.memberEntities(gid, states), List("light.b", "light.a"))
  }

  test("desc reverses it") {
    val g =
      graphOf(
        set(
          List("light.a", "light.b"),
          orderBy = List(prop("attr:brightness", "desc"))
        )
      )
    val states = snapshot(
      st("light.a", "on", "brightness" -> Json.fromInt(10)),
      st("light.b", "on", "brightness" -> Json.fromInt(2))
    )
    assertEquals(g.memberEntities(gid, states), List("light.a", "light.b"))
  }

  test("a Holds key puts the TRUE ones first under asc") {
    val g = graphOf(
      set(
        List("light.a", "light.b"),
        orderBy = List(
          LayoutNode.SortTerm(LayoutNode.SortKey.Holds(isOn("light.b")), "asc")
        )
      )
    )
    // The predicate names light.b explicitly, so it is what separates them.
    val states = snapshot(st("light.a", "on"), st("light.b", "on"))
    assertEquals(g.memberEntities(gid, states), List("light.a", "light.b"))
  }

  test("equal sort keys keep the authored order — the tiebreak is mandatory") {
    // Without a stable sort a set ordered on a live value reshuffles its ties
    // on every tick.
    val g = graphOf(
      set(List("light.z", "light.a", "light.m"), orderBy = List(prop("state")))
    )
    val states =
      snapshot(st("light.z", "on"), st("light.a", "on"), st("light.m", "on"))
    assertEquals(
      g.memberEntities(gid, states),
      List("light.z", "light.a", "light.m")
    )
  }

  test("limit cuts AFTER ordering, and only the present members") {
    val g = graphOf(
      set(
        List("light.a", "light.b", "light.c"),
        e => List(clause(e, Some(isOn(e)))),
        orderBy = List(prop("attr:brightness")),
        limit = Some(2)
      )
    )
    val states = snapshot(
      st("light.a", "on", "brightness" -> Json.fromInt(30)),
      st("light.b", "off", "brightness" -> Json.fromInt(1)),
      st("light.c", "on", "brightness" -> Json.fromInt(20))
    )
    // b is absent (guard), so the cut falls on the two that are present.
    assertEquals(g.memberEntities(gid, states), List("light.c", "light.a"))
  }

  // ---- one frame ----------------------------------------------------------

  private def change(
      before: Map[String, EntityState],
      after: Map[String, EntityState],
      e: String
  ) = StateChange(e, before.get(e), after(e))

  test("an arrival lands where a full materialisation would have put it") {
    val g = graphOf(
      set(
        List("light.a", "light.b", "light.c"),
        e => List(clause(e, Some(isOn(e))))
      )
    )
    val before =
      snapshot(st("light.a", "on"), st("light.b", "off"), st("light.c", "on"))
    val after =
      snapshot(st("light.a", "on"), st("light.b", "on"), st("light.c", "on"))
    val delta =
      g.syncMembers(List(change(before, after, "light.b")), before, after)(gid)
    assertEquals(delta.was, List("light.a", "light.c"))
    assertEquals(delta.now, List("light.a", "light.b", "light.c"))
    assertEquals(delta.replaced, Set.empty[MemberId])
  }

  test("a clause switch is reported as REPLACED, not as a departure") {
    // The arriving card may bind nothing live, so no reverse-index edge would
    // ever name it. The member id is the sound handle.
    val g = graphOf(
      set(
        List("light.a"),
        e =>
          List(clause(e, Some(isOn(e)), as = "slider"), clause(e, as = "pill"))
      )
    )
    val before = snapshot(st("light.a", "on"))
    val after = snapshot(st("light.a", "off"))
    val delta =
      g.syncMembers(List(change(before, after, "light.a")), before, after)(gid)
    assertEquals(delta.was, List("light.a"))
    assertEquals(delta.now, List("light.a"))
    assertEquals(delta.replaced, Set(g.memberIdOf(gid, "light.a")))
  }

  test("a frame that only TICKS a member moves no membership") {
    val g = graphOf(set(List("light.a")))
    val before = snapshot(st("light.a", "on", "brightness" -> Json.fromInt(1)))
    val after = snapshot(st("light.a", "on", "brightness" -> Json.fromInt(2)))
    val delta =
      g.syncMembers(List(change(before, after, "light.a")), before, after)(gid)
    assertEquals(delta.was, delta.now)
    assertEquals(delta.replaced, Set.empty[MemberId])
  }

  test("affectedSets wakes a set for a GUARD's entity, not only a candidate") {
    val g = graphOf(
      set(
        List("light.a"),
        e => List(clause(e, Some(isOn("binary_sensor.hall"))))
      )
    )
    val s = snapshot(st("light.a", "on"), st("binary_sensor.hall", "on"))
    def touched(e: String) =
      g.affectedSets(List(StateChange(e, None, s(e))))
    assertEquals(touched("binary_sensor.hall"), List(gid))
    assertEquals(touched("light.a"), List(gid))
    assertEquals(
      g.affectedSets(List(StateChange("sensor.z", None, st("sensor.z", "1")))),
      Nil
    )
  }

  test("affectedSets is scoped to the layout tree the set lives in") {
    val g = graphOf(set(List("light.a")), root = "detail")
    val ch = List(StateChange("light.a", None, st("light.a", "on")))
    assertEquals(g.affectedSets(ch), Nil)
    assertEquals(g.affectedSurfaceSets("detail", ch), List(gid))
  }

  // ---- ids ----------------------------------------------------------------

  test("a member id is derived from its KEY, sanitized") {
    assertEquals(
      g0.memberIdOf(gid, "light.kitchen_1"): String,
      "c_0_light_kitchen_1"
    )
  }

  private val g0 = graphOf(set(List("light.kitchen_1")))

  test("a set nested in a member is enumerated, and inherits its tile's tree") {
    // The root is what decides who a member's patch may reach. A nested set is
    // not in the STATIC index, so reading the index directly answered "" — the
    // main page — and leaked a surface's patches to every client.
    val inner = set(List("light.b"))
    val outer = LayoutNode.SetNode(
      candidates = List("light.a"),
      members = Map(
        "light.a" -> LayoutNode.SetMember(
          List(
            LayoutNode.SetClause(
              None,
              LayoutNode.Component("tile", Map.empty, List(inner))
            )
          )
        )
      )
    )
    val g = graphOf(outer, root = "detail")
    val innerId = g.innerSetId(g.memberIdOf(gid, "light.a"), 0, List(0))
    assert(
      g.setContainer(innerId).isDefined,
      s"$innerId should be a set container"
    )

    val states = snapshot(st("light.a", "on"), st("light.b", "on"))
    val innerMember = g.membersOf(innerId, states).head
    assertEquals(innerMember.root, "detail")
  }

  test(
    "a member's entities are its own AND its children's, but stop at a nested set"
  ) {
    val nested = LayoutNode.Component(
      "tile",
      Map(
        "entity_id" -> lit("light.a"),
        // A literal slot binds nothing live; this one is what makes the tile
        // itself track light.a.
        "state" -> SlotSource(entityId = Some("light.a"))
      ),
      List(
        LayoutNode.Component(
          "row",
          Map("x" -> SlotSource(entityId = Some("sensor.k")))
        ),
        set(List("light.deep"))
      )
    )
    // light.deep is absent on purpose: descending into a nested set would wake
    // the whole tile on every bulb inside it.
    assertEquals(
      Member.entitiesOf(nested).sorted,
      List("light.a", "sensor.k")
    )
  }
}
