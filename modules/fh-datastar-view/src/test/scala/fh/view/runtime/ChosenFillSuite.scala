package fh.view.runtime

import fh.view.model.{
  Case,
  Choice,
  Dashboard,
  Fill,
  LayoutNode,
  Lit,
  NodeId,
  Op,
  Slot,
  Step,
  TemplateSlot,
  Value,
  ValueExpr,
  ValueType
}
import fh.view.runtime.RendererTestOps.*
import fh.view.testkit.{Conds, Slots, TestCard}
import fh.view.testkit.DashboardBuilders.{declared, st}
import io.circe.Json

/** A nodes slot filled with a `Choice.Chosen`: one pick decides it, each case a
  * node at the slot position a fixed node would have, the otherwise last.
  */
class ChosenFillSuite extends munit.FunSuite {

  private val cards = Map(
    "host" -> TestCard(
      """{{{branch}}}""",
      regions = Set("branch")
    ),
    "leaf" -> TestCard("<span>{{text}}</span>")
  )

  private def leaf(text: String) = LayoutNode.Component(
    "leaf",
    slots = Slots.values(Map("text" -> TemplateSlot(ValueExpr.literal(text))))
  )

  private def host(
      by: ValueExpr,
      cases: List[(Lit, List[LayoutNode])],
      otherwise: Option[List[LayoutNode]],
      vars: List[Dashboard.Declaration] = Nil
  ) = LayoutNode.Component(
    "host",
    slots = Map(
      "branch" -> Slot.Nodes(
        Fill.Nodes(
          Choice.Chosen(
            by,
            cases.map((is, ns) => Case(is, ns)),
            otherwise
          )
        )
      )
    ),
    vars = vars
  )

  private def dash(node: LayoutNode) = Dashboard(cards, node)

  private def isOn(id: String): ValueExpr =
    Conds.cmp("state", Op.Eq, Json.fromString("on"), entity = Some(id))

  private val chain = ValueExpr(
    Value.List(ValueType.Bool, List(isOn("l.a"), isOn("l.b"), isOn("l.c"))),
    List(Step.IndexOfTrue)
  )

  private def lights(a: String, b: String, c: String) =
    Map("l.a" -> st("l.a", a), "l.b" -> st("l.b", b), "l.c" -> st("l.c", c))

  private val stateHost = host(
    chain,
    List(
      Lit.Whole(0) -> List(leaf("A")),
      Lit.Whole(1) -> List(leaf("B")),
      Lit.Whole(2) -> List(leaf("C"))
    ),
    Some(List(leaf("none")))
  )

  private def shown(r: Renderer, states: Map[String, EntityState]) =
    List("A", "B", "C", "none").filter(t =>
      r.renderBody(states).contains(s"<span>$t</span>")
    )

  test("a chain over state shows the case of the first condition that holds") {
    val r = Renderer.create(dash(stateHost))
    assertEquals(shown(r, lights("off", "off", "off")), List("none"))
    assertEquals(shown(r, lights("off", "on", "off")), List("B"))
    assertEquals(shown(r, lights("on", "on", "on")), List("A"))
    assertEquals(shown(r, lights("off", "off", "on")), List("C"))
    assertEquals(shown(r, lights("off", "on", "on")), List("B"))
  }

  test(
    "a chain's cases are nodes at their slot positions, the otherwise last"
  ) {
    val d = dash(stateHost)
    assertEquals(d.surfaces, Map.empty)
    assertEquals(
      d.picks.values.map(_.cases).toList,
      List(List("c_branch_0", "c_branch_1", "c_branch_2", "c_branch_3"))
    )
    assertEquals(d.validate(), Nil)
  }

  private val mode = ValueExpr(Value.Var("mode"))

  private val matchHost = host(
    mode,
    List(
      Lit.Text("heat") -> List(leaf("hot")),
      Lit.Text("cool") -> List(leaf("cold"))
    ),
    Some(List(leaf("off"))),
    vars = declared("mode" -> "heat")
  )

  test("a match over a variable shows each viewer its own case") {
    val r = Renderer.create(dash(matchHost))
    def viewing(choice: Option[String]) =
      val env = r.vars.env(
        choice.map(c => VarKey(NodeId.derived("c"), "mode") -> c).toMap
      )
      r.renderBody(Map.empty, vars = env)
    val first = viewing(None)
    val second = viewing(Some("cool"))
    assert(first.contains("<span>hot</span>"), clue = first)
    assert(!first.contains("<span>cold</span>"), clue = first)
    assert(second.contains("<span>cold</span>"), clue = second)
    assert(!second.contains("<span>hot</span>"), clue = second)
  }

  test("the otherwise takes the value no case names") {
    val r = Renderer.create(dash(matchHost))
    assertEquals(
      r.refusals(Map(VarKey(NodeId.derived("c"), "mode") -> "away")),
      Nil
    )
    val env =
      r.vars.env(Map(VarKey(NodeId.derived("c"), "mode") -> "away"))
    val body = r.renderBody(Map.empty, vars = env)
    assert(body.contains("<span>off</span>"), clue = body)
  }

  private def errors(n: LayoutNode) = dash(n).validate()

  test("validate refuses a key of the wrong type") {
    val bad = host(
      mode,
      List(Lit.Bool(true) -> List(leaf("x"))),
      Some(List(leaf("y"))),
      vars = declared("mode" -> "a")
    )
    assert(
      errors(bad).exists(_.contains("has the case 'true', a bool")),
      clue = errors(bad)
    )
  }

  test("validate takes one decision reading a variable and state") {
    val mixed = ValueExpr(
      Value.List(
        ValueType.Bool,
        List(
          ValueExpr(
            Value.Var("mode"),
            List(Step.Compare(Op.Eq, Value.Literal(Lit.Text("a"))))
          ),
          isOn("l.a")
        )
      ),
      List(Step.IndexOfTrue)
    )
    val n = host(
      mixed,
      List(Lit.Whole(0) -> List(leaf("x")), Lit.Whole(1) -> List(leaf("y"))),
      None,
      vars = declared("mode" -> "a")
    )
    assertEquals(errors(n), Nil)
  }

  test("validate refuses a case that is not exactly one node") {
    val two = host(
      isOn("l.a"),
      List(Lit.Bool(true) -> List(leaf("x"), leaf("y"))),
      None
    )
    assert(
      errors(two).exists(_.contains("not exactly one node")),
      clue = errors(two)
    )
  }

  test("validate refuses two choices on one node: each is not the whole") {
    val one = host(isOn("l.a"), List(Lit.Bool(true) -> List(leaf("x"))), None)
    val Slot.Nodes(fill) = one.slots("branch"): @unchecked
    val twice = one.copy(slots = one.slots + ("other" -> Slot.Nodes(fill)))
    assert(
      errors(twice).exists(_.contains("not its card's whole template")),
      clue = errors(twice)
    )
  }
}
