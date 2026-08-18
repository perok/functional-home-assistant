package fh.view.runtime

import fh.view.model.{Op, Predicate}
import fh.view.testkit.DashboardBuilders.st
import io.circe.Json

/** Evaluating the query wire AST against live state.
  *
  * Three unrelated callers ask this the same question — a set clause's guard, a
  * state surface's activation condition, and a `Count` nested inside either —
  * and none of them needs a renderer, which is why it is its own object. Its
  * tests followed it here: they lived in `RendererSuite` because `matches` used
  * to be on `Renderer`'s companion.
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

  // ---- comparisons --------------------------------------------------------

  test("comparisons read domain, state and attributes off the subject") {
    val s = st("sensor.x", "18", "battery" -> Json.fromInt(15))
    assert(Conditions.matches(cmp("domain", Op.Eq, Json.fromString("sensor")), s))
    assert(!Conditions.matches(cmp("domain", Op.Eq, Json.fromString("light")), s))
    assert(Conditions.matches(cmp("attr:battery", Op.Lt, Json.fromInt(20)), s))
    assert(!Conditions.matches(cmp("attr:battery", Op.Gte, Json.fromInt(20)), s))
    assert(Conditions.matches(cmp("state", Op.Lte, Json.fromInt(18)), s))
  }

  test("the entity_id property compares the entity's own id") {
    val s = st("light.a", "on")
    assert(Conditions.matches(cmp("entity_id", Op.Eq, Json.fromString("light.a")), s))
    assert(!Conditions.matches(cmp("entity_id", Op.Eq, Json.fromString("light.b")), s))
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
    // A light-domain entity fails the `domain == sensor` arm, so `Not(both)`.
    assert(Conditions.matches(Predicate.Not(both), st("light.x", "18")))
    assert(
      Conditions.matches(
        Predicate.Or(List(cmp("domain", Op.Eq, Json.fromString("light")), both)),
        s
      )
    )
  }

  test("ORDERING ops are false unless BOTH sides parse as numbers") {
    // Not a total order, deliberately: `Lt` and `Gt` are BOTH false here, which
    // is the property a caller relying on trichotomy would get wrong.
    val s = st("sensor.x", "warm")
    assert(!Conditions.matches(cmp("state", Op.Lt, Json.fromInt(20)), s))
    assert(!Conditions.matches(cmp("state", Op.Gt, Json.fromInt(20)), s))
    // Equality ops compare the RAW strings, so they still answer.
    assert(Conditions.matches(cmp("state", Op.Eq, Json.fromString("warm")), s))
    assert(Conditions.matches(cmp("state", Op.Ne, Json.fromInt(20)), s))
  }

  test("numbers compare numerically, not lexicographically") {
    val s = st("sensor.x", "9")
    assert(Conditions.matches(cmp("state", Op.Lt, Json.fromInt(10)), s))
  }

  // ---- naming another entity ----------------------------------------------

  test("a guard may name a DIFFERENT entity than its subject") {
    val states = snapshot(st("light.a", "off"), st("binary_sensor.hall", "on"))
    assert(
      Conditions.matchesIn(isOn("binary_sensor.hall"), states("light.a"), states)
    )
  }

  test("naming an entity the snapshot lacks is FALSE, not a read of the subject") {
    // Saying so beats silently answering about the subject, which is what an
    // absent `entity` means.
    val subject = st("light.a", "on")
    assertEquals(
      Conditions.matchesIn(isOn("light.ghost"), subject, snapshot(subject)),
      false
    )
  }

  test("matches without a snapshot treats a cross-entity guard as false") {
    // The single-state form cannot resolve one, so it declines rather than
    // falling through to the subject.
    assert(!Conditions.matches(isOn("binary_sensor.hall"), st("light.a", "on")))
  }

  // ---- Count --------------------------------------------------------------

  test("an UNGUARDED candidate counts unconditionally; a guarded one must hold") {
    // Same rule a set member with an unguarded clause follows — and note the
    // unguarded candidate counts even though the snapshot has never seen it.
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

  // ---- propertyOf ---------------------------------------------------------

  test("propertyOf reads the four kinds it knows, and empty for anything else") {
    val s = st("sensor.x", "18", "battery" -> Json.fromInt(15))
    assertEquals(Conditions.propertyOf("domain", s), "sensor")
    assertEquals(Conditions.propertyOf("state", s), "18")
    assertEquals(Conditions.propertyOf("entity_id", s), "sensor.x")
    assertEquals(Conditions.propertyOf("attr:battery", s), "15")
    // An attribute the entity does not carry reads empty rather than failing.
    assertEquals(Conditions.propertyOf("attr:nope", s), "")
  }

  test("a reg: property reads empty — seeing one here means the build leaked") {
    // Registry facts are BUILD-time data: a comparison on one folds away before
    // it reaches the runtime, and an ordering on one leaves the candidates
    // pre-sorted. This is the assertion that says so out loud.
    assertEquals(Conditions.propertyOf("reg:area_id", st("light.a", "on")), "")
  }
}
