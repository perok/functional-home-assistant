package fh.view.runtime

import fh.view.model.{Lit, Op, Part, Predicate, Step, Value, ValueExpr}
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
  ) = Predicate.Cmp(property, op, value, entity)

  private def isOn(entity: String) =
    cmp("state", Op.Eq, Json.fromString("on"), Some(entity))

  private def snapshot(states: EntityState*): Map[String, EntityState] =
    states.map(s => s.entityId -> s).toMap

  test("comparisons read domain, state and attributes off the subject") {
    val s = st("sensor.x", "18", "battery" -> Json.fromInt(15))
    assert(
      Conditions.matches(cmp("domain", Op.Eq, Json.fromString("sensor")), s)
    )
    assert(
      !Conditions.matches(cmp("domain", Op.Eq, Json.fromString("light")), s)
    )
    assert(Conditions.matches(cmp("attr:battery", Op.Lt, Json.fromInt(20)), s))
    assert(
      !Conditions.matches(cmp("attr:battery", Op.Gte, Json.fromInt(20)), s)
    )
    assert(Conditions.matches(cmp("state", Op.Lte, Json.fromInt(18)), s))
  }

  test("the entity_id property compares the entity's own id") {
    val s = st("light.a", "on")
    assert(
      Conditions.matches(cmp("entity_id", Op.Eq, Json.fromString("light.a")), s)
    )
    assert(
      !Conditions.matches(
        cmp("entity_id", Op.Eq, Json.fromString("light.b")),
        s
      )
    )
  }

  test("and / or / not combine") {
    val s = st("sensor.x", "18", "battery" -> Json.fromInt(15))
    val both = Predicate.And(
      List(
        cmp("domain", Op.Eq, Json.fromString("sensor")),
        cmp("attr:battery", Op.Lt, Json.fromInt(20))
      )
    )
    assert(Conditions.matches(both, s))
    assert(Conditions.matches(Predicate.Not(both), st("light.x", "18")))
    assert(
      Conditions.matches(
        Predicate.Or(
          List(cmp("domain", Op.Eq, Json.fromString("light")), both)
        ),
        s
      )
    )
  }

  test("ORDERING ops are false unless BOTH sides parse as numbers") {
    // Not a total order: `Lt` and `Gt` are both false here, which a caller
    // assuming trichotomy would get wrong.
    val s = st("sensor.x", "warm")
    assert(!Conditions.matches(cmp("state", Op.Lt, Json.fromInt(20)), s))
    assert(!Conditions.matches(cmp("state", Op.Gt, Json.fromInt(20)), s))
    // Equality compares the raw strings.
    assert(Conditions.matches(cmp("state", Op.Eq, Json.fromString("warm")), s))
    assert(Conditions.matches(cmp("state", Op.Ne, Json.fromInt(20)), s))
  }

  test("numbers compare numerically, not lexicographically") {
    val s = st("sensor.x", "9")
    assert(Conditions.matches(cmp("state", Op.Lt, Json.fromInt(10)), s))
  }

  test("a guard may name a DIFFERENT entity than its subject") {
    val states = snapshot(st("light.a", "off"), st("binary_sensor.hall", "on"))
    assert(
      Conditions.matchesIn(
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
      Conditions.matchesIn(isOn("light.ghost"), subject, snapshot(subject)),
      false
    )
  }

  test("matches without a snapshot treats a cross-entity guard as false") {
    // The single-state form cannot resolve one, so it declines rather than fall
    // through to the subject.
    assert(!Conditions.matches(isOn("binary_sensor.hall"), st("light.a", "on")))
  }

  test(
    "an UNGUARDED candidate counts unconditionally; a guarded one must hold"
  ) {
    // The unguarded candidate counts though the snapshot has never seen it, as
    // an unguarded set clause does.
    val states = snapshot(st("light.a", "on"), st("light.b", "off"))
    val count = Predicate.Count(
      candidates = List("light.a", "light.b", "light.unknown"),
      when = Map("light.a" -> isOn("light.a"), "light.b" -> isOn("light.b")),
      op = Op.Eq,
      value = Json.fromInt(2)
    )
    assert(Conditions.matchesIn(count, EntityState.none, states), clue = states)
  }

  test("a guarded candidate the snapshot lacks does not count") {
    val states = snapshot(st("light.a", "on"))
    val count = Predicate.Count(
      candidates = List("light.a", "light.gone"),
      when = Map(
        "light.a" -> isOn("light.a"),
        "light.gone" -> isOn("light.gone")
      ),
      op = Op.Eq,
      value = Json.fromInt(1)
    )
    assert(Conditions.matchesIn(count, EntityState.none, states))
  }

  test(
    "propertyOf reads the four kinds it knows, and empty for anything else"
  ) {
    val s = st("sensor.x", "18", "battery" -> Json.fromInt(15))
    assertEquals(Conditions.propertyOf("domain", s), "sensor")
    assertEquals(Conditions.propertyOf("state", s), "18")
    assertEquals(Conditions.propertyOf("entity_id", s), "sensor.x")
    assertEquals(Conditions.propertyOf("attr:battery", s), "15")
    assertEquals(Conditions.propertyOf("attr:nope", s), "")
  }

  // A bool `ValueExpr` (plan Part B), decided by `holds`.

  private def lit(j: Json): Lit = j.as[Lit].fold(e => fail(e.message), identity)

  /** What Part B's builders will emit for a predicate without a count. */
  private def asValue(p: Predicate): ValueExpr = p match
    case Predicate.Cmp(property, op, value, entity) =>
      val part =
        if (property == "state") Part.State
        else Part.Attr(property.stripPrefix("attr:"))
      ValueExpr(
        Value.Entity(entity, part),
        List(Step.Compare(op, Value.Literal(lit(value))))
      )
    case Predicate.And(xs) =>
      ValueExpr(Value.List(xs.map(asValue)), List(Step.AllOf))
    case Predicate.Or(xs) =>
      ValueExpr(Value.List(xs.map(asValue)), List(Step.AnyOf))
    case Predicate.Not(x) =>
      asValue(x).copy(steps = asValue(x).steps :+ Step.Not)
    case other => fail(s"no value form for $other")

  test("a bool value decides as the predicate it replaces, over a sweep") {
    // Equality as text (`21.0` is not `21`), ordering numeric or false, a
    // missing attribute empty, an entity the snapshot lacks false, and
    // and/or/not over all of them.
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
    } yield cmp(property, op, value, entity)
    val pairs = cmps.zip(cmps.drop(7) ++ cmps.take(7))
    val trees = cmps ++ cmps.map(Predicate.Not(_)) ++ pairs.flatMap((a, b) =>
      List(
        Predicate.And(List(a, b)),
        Predicate.Or(List(a, Predicate.Not(b)))
      )
    ) :+ Predicate.And(Nil) :+ Predicate.Or(Nil)
    trees.foreach(p =>
      assertEquals(
        Conditions.holds(asValue(p), subject, states),
        Conditions.matchesIn(p, subject, states),
        clue = p
      )
    )
  }

  test("a list's AllOf, AnyOf and Count, empty lists included") {
    val s = st("light.a", "on")
    val off = asValue(isOn("light.missing"))
    val on = asValue(isOn("light.a"))
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

  test("a reg: property reads empty — seeing one here means the build leaked") {
    // Registry facts are build-time data: a comparison on one folds away before
    // the runtime, and an ordering leaves the candidates pre-sorted.
    assertEquals(Conditions.propertyOf("reg:area_id", st("light.a", "on")), "")
  }
}
