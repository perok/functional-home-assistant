package fh.view.model

import io.circe.parser.decode

/** The condition kinds (plan Part B): a list and the steps that decide over it,
  * typed, keyed and walked for the entities that wake them.
  */
class ValueExprSuite extends munit.FunSuite {

  private def state(of: String) = Value.Entity(Some(of))
  private val on = Value.Literal(Lit.Text("on"))

  private def isOn(of: String) =
    ValueExpr(state(of), List(Step.Compare(Op.Eq, on)))

  private def list(step: Step, items: ValueExpr*) =
    ValueExpr(Value.List(items.toList), List(step))

  private def typeOf(e: ValueExpr): Either[String, ValueType] =
    e.typed(Nil).left.map(_.message).map(_.out)

  test("each new step types what it takes, and refuses what it does not") {
    assertEquals(typeOf(isOn("light.a")), Right(ValueType.Bool))
    assertEquals(
      typeOf(
        ValueExpr(
          Value.Entity(Some("light.a"), Part.Attr("b")),
          List(Step.Compare(Op.Gt, on))
        )
      ),
      Right(ValueType.Bool)
    )
    assertEquals(
      typeOf(list(Step.AllOf, isOn("a.a"), isOn("a.b"))),
      Right(ValueType.Bool)
    )
    assertEquals(typeOf(list(Step.AnyOf, isOn("a.a"))), Right(ValueType.Bool))
    assertEquals(typeOf(list(Step.Count, isOn("a.a"))), Right(ValueType.Int))
    assertEquals(
      typeOf(isOn("a.a").copy(steps = isOn("a.a").steps :+ Step.Not)),
      Right(ValueType.Bool)
    )
    assertEquals(
      typeOf(ValueExpr(state("a.a"), List(Step.Not))),
      Left("has a not step, which takes a bool, after a text")
    )
    assertEquals(
      typeOf(list(Step.AllOf, ValueExpr(state("a.a")))),
      Left(
        "has a all-of step, which takes a list of bools, after a list of text"
      )
    )
    assertEquals(
      typeOf(
        ValueExpr(Value.Literal(Lit.Bool(true)), List(Step.Compare(Op.Eq, on)))
      ),
      Left("has a compare step, which takes a number or text, after a bool")
    )
  }

  test("a list's items are one type, at least one, and read without CEL") {
    assertEquals(
      typeOf(list(Step.AllOf, isOn("a.a"), ValueExpr(state("a.b")))),
      Left("a list's items are one type, not bool and text")
    )
    assertEquals(typeOf(list(Step.AllOf)), Left("a list has no items"))
    assertEquals(
      typeOf(list(Step.AnyOf, ValueExpr(Value.Cel("true")))),
      Left("a list's items are read without CEL")
    )
  }

  test("a list's key names its items, in order, unforgeably") {
    val exprs = List(
      list(Step.AllOf, isOn("a.a"), isOn("a.b")),
      list(Step.AllOf, isOn("a.b"), isOn("a.a")),
      list(Step.AnyOf, isOn("a.a"), isOn("a.b")),
      list(Step.AllOf, list(Step.AllOf, isOn("a.a"), isOn("a.b"))),
      list(Step.AllOf, isOn("a.a")),
      isOn("a.a"),
      ValueExpr(state("a.a"), List(Step.Compare(Op.Ne, on))),
      isOn("a.a").copy(steps = isOn("a.a").steps :+ Step.Not)
    )
    assertEquals(exprs.map(_.inputKey).distinct.size, exprs.size)
  }

  test(
    "the entities a list reads wake it, and an unnamed read takes the subject"
  ) {
    val operand = ValueExpr(
      Value.Entity(),
      List(Step.Compare(Op.Eq, Value.Entity(Some("sensor.mode"))))
    )
    val e = list(Step.AnyOf, isOn("light.a"), operand)
    assert(e.readsMissingSubject)
    val about = e.about(Some("light.subject"))
    assert(!about.readsMissingSubject)
    assertEquals(
      about.entities.toSet,
      Set("light.a", "light.subject", "sensor.mode")
    )
  }

  test("a list and the new steps decode from the wire") {
    val json =
      """{"source": {"kind": "List", "items": [
        |  {"source": {"kind": "Entity", "of": "light.a"},
        |   "steps": [{"kind": "Compare", "op": "eq", "to": "on"}]}]},
        | "steps": [{"kind": "AnyOf"}, {"kind": "Not"}]}""".stripMargin
    assertEquals(
      decode[ValueExpr](json),
      Right(
        list(Step.AnyOf, isOn("light.a"))
          .copy(steps = List(Step.AnyOf, Step.Not))
      )
    )
  }

  test("a set's guards and order terms must decide: a bool, without CEL") {
    def present(guard: ValueExpr, steps: Step*) = ValueExpr(
      Value.Present(List("a.a"), Map("a.a" -> guard)),
      steps.toList
    )
    assertEquals(
      typeOf(present(isOn("a.a"), Step.Count)),
      Right(ValueType.Int)
    )
    assertEquals(
      typeOf(present(ValueExpr(state("a.a")), Step.Count)),
      Left("the guard on 'a.a': a condition is a bool, not text")
    )
    assertEquals(
      typeOf(present(ValueExpr(Value.Cel("true")), Step.Count)),
      Left("the guard on 'a.a': a condition is read without CEL")
    )
    val byCel = LayoutNode.SortTerm(ValueExpr(Value.Cel("true")))
    assertEquals(
      typeOf(present(isOn("a.a"), Step.First(List(byCel)))),
      Left("an order term is read without CEL")
    )
  }

  test("a first step comes straight after a set's ids, and gives one") {
    val ids = Value.Present(List("a.a", "a.b"))
    assertEquals(
      typeOf(ValueExpr(ids, List(Step.First()))),
      Right(ValueType.Text)
    )
    val misplaced =
      "a first step comes straight after a set's present candidates"
    assertEquals(
      typeOf(ValueExpr(Value.List(List(isOn("a.a"))), List(Step.First()))),
      Left(misplaced)
    )
    assertEquals(
      typeOf(ValueExpr(ids, List(Step.Count, Step.First()))),
      Left(misplaced)
    )
  }

  test("the renderer's fold runs the new steps as the condition reader does") {
    def run(s: Step.Simple, v: Any, a: Any = null) =
      Transform.formatWith(s, v, a, null)
    assertEquals(run(Step.AllOf, List(true, false)), false)
    assertEquals(run(Step.AnyOf, List(false, true)), true)
    assertEquals(run(Step.Not, true), false)
    assertEquals(run(Step.Compare(Op.Lt, on), "9", "10"), true)
    assertEquals(run(Step.Compare(Op.Lt, on), "warm", "10"), false)
    assertEquals(run(Step.Compare(Op.Eq, on), "on", "on"), true)
  }
}
