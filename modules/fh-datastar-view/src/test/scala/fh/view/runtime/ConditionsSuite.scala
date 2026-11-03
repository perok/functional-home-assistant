package fh.view.runtime

import fh.view.testkit.Conds

import fh.view.model.{Op, Step, Value, ValueExpr}
import fh.view.testkit.DashboardBuilders.st
import io.circe.Json

/** Evaluating the query wire AST against live state, for a set clause's guard,
  * a state surface's condition and a `Count` inside either; none needs a
  * renderer.
  */
class ConditionsSuite extends munit.FunSuite {

  private def cmp(
      property: String,
      op: Op,
      value: Json,
      entity: Option[String] = None
  ) = Conds.cmp(property, op, value, entity)

  private def isOn(entity: String) =
    cmp("state", Op.Eq, Json.fromString("on"), Some(entity))

  private def snapshot(states: EntityState*): Map[String, EntityState] =
    states.map(s => s.entityId -> s).toMap

  private def matches(p: ValueExpr, st: EntityState) =
    Conditions.holds(p, st, Map.empty)

  test("comparisons read state and attributes off the subject") {
    val s = st("sensor.x", "18", "battery" -> Json.fromInt(15))
    assert(matches(cmp("attr:battery", Op.Lt, Json.fromInt(20)), s))
    assert(!matches(cmp("attr:battery", Op.Gte, Json.fromInt(20)), s))
    assert(matches(cmp("state", Op.Lte, Json.fromInt(18)), s))
  }

  test("and / or / not combine") {
    val s = st("sensor.x", "18", "battery" -> Json.fromInt(15))
    val both = Conds.and(
      List(
        cmp("state", Op.Eq, Json.fromString("18")),
        cmp("attr:battery", Op.Lt, Json.fromInt(20))
      )
    )
    assert(matches(both, s))
    assert(matches(Conds.not(both), st("light.x", "18")))
    assert(
      matches(
        Conds.or(List(cmp("state", Op.Eq, Json.fromString("19")), both)),
        s
      )
    )
  }

  test("ORDERING ops are false unless BOTH sides parse as numbers") {
    // Not a total order: `Lt` and `Gt` are both false here, which a caller
    // assuming trichotomy would get wrong.
    val s = st("sensor.x", "warm")
    assert(!matches(cmp("state", Op.Lt, Json.fromInt(20)), s))
    assert(!matches(cmp("state", Op.Gt, Json.fromInt(20)), s))
    // Equality compares the raw strings.
    assert(matches(cmp("state", Op.Eq, Json.fromString("warm")), s))
    assert(matches(cmp("state", Op.Ne, Json.fromInt(20)), s))
  }

  test("numbers compare numerically, not lexicographically") {
    val s = st("sensor.x", "9")
    assert(matches(cmp("state", Op.Lt, Json.fromInt(10)), s))
  }

  test("a guard may name a DIFFERENT entity than its subject") {
    val states = snapshot(st("light.a", "off"), st("binary_sensor.hall", "on"))
    assert(
      Conditions.holds(
        isOn("binary_sensor.hall"),
        states("light.a"),
        states
      )
    )
  }

  test(
    "naming an entity the snapshot lacks is FALSE, not a read of the subject"
  ) {
    // Absent `entity` means the subject, so saying so beats silently answering
    // about it.
    val subject = st("light.a", "on")
    assertEquals(
      Conditions.holds(isOn("light.ghost"), subject, snapshot(subject)),
      false
    )
  }

  test("matches without a snapshot treats a cross-entity guard as false") {
    // The single-state form cannot resolve one, so it declines rather than fall
    // through to the subject.
    assert(!matches(isOn("binary_sensor.hall"), st("light.a", "on")))
  }

  test(
    "an UNGUARDED candidate counts unconditionally; a guarded one must hold"
  ) {
    // The unguarded candidate counts though the snapshot has never seen it, as
    // an unguarded set clause does.
    val states = snapshot(st("light.a", "on"), st("light.b", "off"))
    val count = Conds.count(
      candidates = List("light.a", "light.b", "light.unknown"),
      when = Map("light.a" -> isOn("light.a"), "light.b" -> isOn("light.b")),
      op = Op.Eq,
      value = Json.fromInt(2)
    )
    assert(Conditions.holds(count, EntityState.none, states), clue = states)
  }

  test("a guarded candidate the snapshot lacks does not count") {
    val states = snapshot(st("light.a", "on"))
    val count = Conds.count(
      candidates = List("light.a", "light.gone"),
      when = Map(
        "light.a" -> isOn("light.a"),
        "light.gone" -> isOn("light.gone")
      ),
      op = Op.Eq,
      value = Json.fromInt(1)
    )
    assert(Conditions.holds(count, EntityState.none, states))
  }

  test("a condition decides as the rule says, over a sweep") {
    // Equality as text (`21.0` is not `21`), ordering numeric or false, a
    // missing attribute empty, an entity the snapshot lacks false, and
    // and/or/not over all of them. The oracle is the rule itself.
    val subject =
      st(
        "sensor.x",
        "18",
        "t" -> Json.fromDoubleOrNull(21.0),
        "u" -> Json.fromString("on")
      )
    val states = snapshot(subject, st("binary_sensor.hall", "on"))
    val values = List(
      Json.fromString("on"),
      Json.fromString("18"),
      Json.fromString(""),
      Json.fromInt(18),
      Json.fromInt(21),
      Json.fromDoubleOrNull(21.0),
      Json.True
    )
    val cmps = for {
      property <- List("state", "attr:t", "attr:u", "attr:nope")
      op <- Op.values.toList
      value <- values
      entity <- List(None, Some("binary_sensor.hall"), Some("light.ghost"))
    } yield cmp(property, op, value, entity) -> entity
      .fold(Some(subject))(states.get)
      .exists(at =>
        Conditions.compare(
          if (property == "state") at.state
          else
            at.attributes
              .get(property.stripPrefix("attr:"))
              .fold("")(StateStore.jsonToString),
          StateStore.jsonToString(value),
          op
        )
      )
    val pairs = cmps.zip(cmps.drop(7) ++ cmps.take(7))
    val trees = cmps ++ cmps.map((c, o) => Conds.not(c) -> !o) ++
      pairs.flatMap { case ((a, oa), (b, ob)) =>
        List(
          Conds.and(List(a, b)) -> (oa && ob),
          Conds.or(List(a, Conds.not(b))) -> (oa || !ob)
        )
      } :+ (Conds.and(Nil) -> true) :+ (Conds.or(Nil) -> false)
    trees.foreach((c, expected) =>
      assertEquals(Conditions.holds(c, subject, states), expected, clue = c)
    )
  }

  test(
    "a count stopped early answers as the full count, for every op and mix"
  ) {
    // `> 0` settles at the first present candidate, `== length` at the first
    // absent; every other op settles somewhere between.
    val on = st("light.on", "on")
    val states = snapshot(on)
    for {
      size <- 0 to 4
      mask <- 0 until (1 << size)
      ids = (0 until size).toList.map(i =>
        if ((mask >> i & 1) == 1) "light.on" else s"light.off$i"
      )
      guards = ids.map(id => id -> isOn(id)).toMap
      op <- Op.values.toList
      n <- -1 to size + 1
    } {
      val full = Conditions.present(Value.Present(ids, guards), states)
      assertEquals(
        Conditions.holds(
          Conds.count(ids, guards, op, Json.fromInt(n)),
          EntityState.none,
          states
        ),
        Conditions.compare(full.toString, n.toString, op),
        clue = (ids, op, n)
      )
    }
  }

  test("a list's AllOf, AnyOf and Count, empty lists included") {
    val s = st("light.a", "on")
    val off = (isOn("light.missing"))
    val on = (isOn("light.a"))
    def of(step: Step, items: ValueExpr*) =
      ValueExpr(Value.List(items.toList), List(step))
    assert(!Conditions.holds(of(Step.AllOf, off, on), s, snapshot(s)))
    assert(Conditions.holds(of(Step.AnyOf, on, off), s, snapshot(s)))
    assert(Conditions.holds(of(Step.AllOf), s, snapshot(s)))
    assert(!Conditions.holds(of(Step.AnyOf), s, snapshot(s)))
    assertEquals(
      Conditions.eval(of(Step.Count, on, off), s, snapshot(s)),
      2L
    )
  }

  test("a read of an entity the snapshot lacks evaluates empty, not a marker") {
    val ghost = ValueExpr(Value.Entity(Some("light.ghost")))
    assertEquals(Conditions.eval(ghost, EntityState.none, Map.empty), "")
  }
}
