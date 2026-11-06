package fh.view.runtime

import fh.view.testkit.Conds

import fh.view.model.{
  Lit,
  Op,
  Part,
  SortTerm,
  Step,
  Value,
  ValueExpr,
  ValueType
}
import fh.view.testkit.DashboardBuilders.st
import io.circe.Json

/** A condition compiled and decided against live state, for a set clause's
  * guard, a state surface's condition and a `Count` inside either; none needs a
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

  private def run(
      p: ValueExpr,
      st: EntityState,
      states: Map[String, EntityState]
  ) =
    Conds.run(p)(
      st,
      states,
      Evaluator.noFetch
    )

  private def holds(
      p: ValueExpr,
      st: EntityState,
      states: Map[String, EntityState]
  ) =
    Evaluator.holds(run(p, st, states))

  private def matches(p: ValueExpr, st: EntityState) = holds(p, st, Map.empty)

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
    assert(
      matches(
        Conds.not(both),
        st("light.x", "18", "battery" -> Json.fromInt(50))
      )
    )
    // An attribute it does not report is no value, and so is its negation.
    assert(!matches(Conds.not(both), st("light.x", "18")))
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
      holds(
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
      holds(isOn("light.ghost"), subject, snapshot(subject)),
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
    assert(holds(count, EntityState.none, states), clue = states)
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
    assert(holds(count, EntityState.none, states))
  }

  test("a condition decides as the rule says, over a sweep") {
    // The literal's type picks the comparison: a number on the number line
    // (`21.0` is `21`), `ne` its negation; text as text, ordering numeric when
    // both parse; a bool as equality. A missing attribute or entity is no
    // value, which no comparison holds over, and and/or/not are Kleene's over
    // it. The oracle is the rule itself, `None` for no value.
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
    } yield
      val read: Option[Any] = entity
        .fold(Some(subject))(states.get)
        .map(at =>
          Steps.read(
            if (property == "state") Part.State
            else Part.Attr(property.stripPrefix("attr:")),
            at
          )
        )
        .filter(_ != null)
      cmp(property, op, value, entity) -> read.map(v =>
        value.as[Lit].toOption.get match
          case l @ (Lit.Whole(_) | Lit.Real(_)) =>
            val n = Steps.number(l.raw).get
            if (op == Op.Ne) !Steps.number(v).contains(n)
            else Steps.number(v).exists(x => onLine(x, n, op))
          case Lit.Bool(b) =>
            op match
              case Op.Eq => Steps.text(v) == b.toString
              case Op.Ne => Steps.text(v) != b.toString
              case _     => false
          case Lit.Text(t) =>
            op match
              case Op.Eq => Steps.text(v) == t
              case Op.Ne => Steps.text(v) != t
              case _     =>
                (Steps.text(v).toDoubleOption, t.toDoubleOption) match
                  case (Some(x), Some(y)) => onLine(x, y, op)
                  case _                  => false
      )
    def and(a: Option[Boolean], b: Option[Boolean]) =
      if (a.contains(false) || b.contains(false)) Some(false)
      else if (a.isEmpty || b.isEmpty) None
      else Some(true)
    def not(a: Option[Boolean]) = a.map(!_)
    val pairs = cmps.zip(cmps.drop(7) ++ cmps.take(7))
    val trees = cmps ++ cmps.map((c, o) => Conds.not(c) -> not(o)) ++
      pairs.flatMap { case ((a, oa), (b, ob)) =>
        List(
          Conds.and(List(a, b)) -> and(oa, ob),
          Conds.or(List(a, Conds.not(b))) -> not(and(not(oa), ob))
        )
      } :+ (Conds.and(Nil) -> Some(true)) :+ (Conds.or(Nil) -> Some(false))
    trees.foreach((c, expected) =>
      assertEquals(holds(c, subject, states), expected.contains(true), clue = c)
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
      val full = Evaluator
        .presentOf(Value.Present(ids, guards), Evaluator.Scope(""))
        .fold(m => fail(m.message), _(states))
        .size
      assertEquals(
        holds(
          Conds.count(ids, guards, op, Json.fromInt(n)),
          EntityState.none,
          states
        ),
        onLine(full.toDouble, n.toDouble, op),
        clue = (ids, op, n)
      )
    }
  }

  test("a list's AllOf, AnyOf and Count, empty lists included") {
    val s = st("light.a", "on")
    val off = (isOn("light.missing"))
    val on = (isOn("light.a"))
    def of(step: Step, items: ValueExpr*) =
      ValueExpr(Value.List(ValueType.Bool, items.toList), List(step))
    assert(!holds(of(Step.AllOf, off, on), s, snapshot(s)))
    assert(holds(of(Step.AnyOf, on, off), s, snapshot(s)))
    assert(holds(of(Step.AllOf), s, snapshot(s)))
    assert(!holds(of(Step.AnyOf), s, snapshot(s)))
    assertEquals(run(of(Step.Count, on, off), s, snapshot(s)), 2L)
  }

  test("CEL decides a guard, a list's item and an order term as steps do") {
    def celOn(entity: Option[String]) =
      ValueExpr(Value.Entity(entity), List(Step.Cel("value == 'on'")))
    def count(guard: String => ValueExpr) = Conds.count(
      candidates = List("light.a", "light.b"),
      when = Map("light.a" -> guard("light.a"), "light.b" -> guard("light.b")),
      op = Op.Eq,
      value = Json.fromInt(1)
    )
    def anyOf(item: String => ValueExpr) =
      ValueExpr(
        Value.List(ValueType.Bool, List(item("light.a"))),
        List(Step.AnyOf)
      )
    def lead(term: ValueExpr) = Conds.first(
      candidates = List("light.a", "light.b"),
      orderBy = List(SortTerm(term)),
      entity = "light.b"
    )
    val celTerm = ValueExpr(Value.Entity(), List(Step.Cel("value == 'on'")))
    for (
      a <- List("on", "off");
      b <- List("on", "off")
    ) {
      val states = snapshot(st("light.a", a), st("light.b", b))
      val s = EntityState.none
      def same(cel: ValueExpr, plain: ValueExpr) =
        assertEquals(holds(cel, s, states), holds(plain, s, states), (a, b))
      same(count(e => celOn(Some(e))), count(isOn))
      same(anyOf(e => celOn(Some(e))), anyOf(isOn))
      same(
        lead(celTerm),
        lead(Conds.cmp("state", Op.Eq, Json.fromString("on")))
      )
    }
    assertEquals(
      count(e => celOn(Some(e))).entities.toSet,
      Set("light.a", "light.b")
    )
  }

  test("a guard whose CEL fails leaves its member absent") {
    val failing =
      ValueExpr(Value.Entity(), List(Step.Cel("int(value) > 0")))
    val states = snapshot(st("light.a", "on"))
    val none = Conds.count(
      candidates = List("light.a"),
      when = Map("light.a" -> failing),
      op = Op.Eq,
      value = Json.fromInt(0)
    )
    assert(holds(none, EntityState.none, states))
  }

  test("one scope builds each CEL program once") {
    val scope = Evaluator.Scope("")
    val k = Cel.Key("value == 'on'", Nil, Some(ValueType.Text))
    assert(Conds.compiled(scope.program(k)) eq Conds.compiled(scope.program(k)))
  }

  test("a read of an entity the snapshot lacks is no value, not a stand-in") {
    val ghost = ValueExpr(Value.Entity(Some("light.ghost")))
    assertEquals(run(ghost, EntityState.none, Map.empty), null)
  }

  private def onLine(l: Double, r: Double, op: Op): Boolean = op match
    case Op.Eq  => l == r
    case Op.Ne  => l != r
    case Op.Lt  => l < r
    case Op.Lte => l <= r
    case Op.Gt  => l > r
    case Op.Gte => l >= r
}
