package fh.view.runtime

import fh.view.testkit.DashboardBuilders

import fh.view.testkit.TestCard
import fh.view.history.Windows
import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}
import fh.view.model.*
import io.circe.parser.decode

/** A fetch as an input: a state slot's CEL reads a provider's answer by name,
  * fetched before it runs, on the render path and on the signal path alike
  * (#541 E3b).
  */
class FetchValueSuite extends munit.FunSuite {

  private val history = FetchTemplate("sensor.left", Choice.Fixed(Windows.day))
  private val read = SlotRead(
    FetchRequest.History("sensor.left", Windows.day),
    Step.Json
  )
  // A wash that started at 101 after an idle 0.
  private val answer = """{"points":[[0,0.0],[60000,101.0],[120000,90.0]]}"""
  private val peak =
    "cel.bind(zeros, past.transformList(i, p, p[1] <= 0.0, p[0]), " +
      "cel.bind(since, size(zeros) == 0 ? 0.0 : math.greatest(zeros), " +
      "math.greatest(past.transformList(i, p, p[0] > since, p[1]) + [num(value.state)])))"

  private def of(
      inputs: Map[String, ValueExpr] = Map(
        "past" -> ValueExpr(Value.Fetch(history))
      ),
      reads: Reads = Reads.Live
  ) = TemplateSlot(
    ValueExpr(
      Value.Entity(Some("sensor.left"), reads = reads, part = Part.Whole),
      List(Step.Cel(peak, inputs))
    ),
    signal = Some(SignalBind.Handler)
  )

  private def dashboard(
      ofSlot: TemplateSlot = of(),
      nodeInputs: Map[String, ValueExpr] = Map.empty
  ) = Dashboard(
    cards = Map(
      "bar" -> TestCard(
        """<div data-of="{{#of.signal}}{{of.signal}}{{/of.signal}}">{{value}}</div>"""
      )
    ),
    card = LayoutNode.Component(
      card = "bar",
      templateSlots = Map(
        "entity_id" -> TemplateSlot(ValueExpr.literal("sensor.left")),
        "value" -> TemplateSlot(
          ValueExpr(
            Value.Entity(Some("sensor.left"), Part.Whole),
            List(Step.Cel("value.state"))
          )
        ),
        "of" -> ofSlot
      ),
      inputs = nodeInputs
    )
  )

  private def states(left: String) =
    Map("sensor.left" -> EntityState("sensor.left", left, Map.empty))

  private def answered(json: String = answer) =
    FetchSnapshot.of(Map(read -> Staged(7L, json)))

  private def rootId(d: Dashboard) = LayoutNode.rootId("", d.card)

  test("a slot's CEL reads a fetch value as its points") {
    val d = dashboard()
    assertEquals(d.validate(), Nil)
    val html = Renderer
      .create(d)
      .renderBodyTraced(
        Shared.of(states("47")),
        Viewing(Selections.none, answered())
      )
      .html
    assert(html.contains("101"), clue = html)
  }

  test(
    "the signal path reads the fetch too, and a missing answer is no points"
  ) {
    val d = dashboard()
    val r = Renderer.create(d)
    def ofNow(left: String, f: FetchSnapshot) =
      r.signalsFor(rootId(d), Shared.of(states(left)), f).values.toSet
    assert(ofNow("46", answered()).contains("101"))
    // A failed read is `""`, so the current reading stands in.
    assert(ofNow("46", answered("")).contains("46"))
  }

  test("a value's fetch is the node's, so a render reads it first") {
    val d = dashboard()
    val fetches = d.validated().map(_.ready.card) match
      case Right(c: Ready.Component) => c.fetches
      case other                     => fail(s"not a component: $other")
    assertEquals(fetches, List(SlotAsk(history, Step.Json)))
    assertEquals(d.allFetches, List(read))
  }

  test("a node's fetch value reaches the slot that reads it") {
    val d = dashboard(
      ofSlot = of(inputs = Map.empty),
      nodeInputs = Map("past" -> ValueExpr(Value.Fetch(history)))
    )
    assertEquals(d.validate(), Nil)
    assert(
      Renderer
        .create(d)
        .signalsFor(rootId(d), Shared.of(states("46")), answered())
        .values
        .toSet
        .contains("101")
    )
  }

  test("a slot's inputs are checked as a node's are") {
    def errs(d: Dashboard) = d.validate().mkString("\n")
    assert(
      errs(
        dashboard(nodeInputs =
          Map("past" -> ValueExpr(Value.Literal(Lit.Whole(1))))
        )
      )
        // The slot's own wins, so the node's is read by nothing.
        .contains("input 'past' is read by no expression")
    )
    assert(
      errs(
        dashboard(ofSlot =
          of(inputs =
            Map(
              "past" -> ValueExpr(Value.Fetch(history)),
              "unread" -> ValueExpr(Value.Literal(Lit.Whole(1)))
            )
          )
        )
      ).contains("'unread' its expression does not read")
    )
    assert(
      errs(dashboard(ofSlot = of(reads = Reads.Once)))
        .contains("reads a live count, condition or fetch")
    )
    val undeclared =
      history.copy(window =
        DashboardBuilders.chosenBy("window", Windows.presets)
      )
    assert(
      errs(
        dashboard(ofSlot =
          of(inputs = Map("past" -> ValueExpr(Value.Fetch(undeclared))))
        )
      ).contains("reads the variable 'window', which no ancestor declares")
    )
  }

  // A node whose `value` slot is the one place its fetch is held.
  private def holding(
      value: ValueExpr,
      nodeInputs: Map[String, ValueExpr] = Map.empty
  ) = Dashboard(
    cards = Map("bar" -> TestCard("<div>{{value}}</div>")),
    card = LayoutNode.Component(
      card = "bar",
      templateSlots = Map(
        "entity_id" -> TemplateSlot(ValueExpr.literal("sensor.left")),
        "value" -> TemplateSlot(value)
      ),
      inputs = nodeInputs
    )
  )

  private val past = Map("past" -> ValueExpr(Value.Fetch(history)))
  private def over(src: String) = ValueExpr(Value.Cel(src, past))
  private val left = Value.Entity(Some("sensor.left"), Part.Whole)
  private val both = states("46") +
    ("sensor.right" -> EntityState("sensor.right", "1", Map.empty))

  // Each place below a slot's own inputs that holds a fetch, and what the slot
  // shows once it is answered: three points, so `size(past) > 2`.
  private val depths: List[(String, ValueExpr, String)] = List(
    (
      "an input's own input",
      ValueExpr(
        left,
        List(Step.Cel("'n=' + string(n)", Map("n" -> over("size(past)"))))
      ),
      "n=3"
    ),
    (
      "a list's item",
      ValueExpr(
        Value.List(ValueType.Bool, List(over("size(past) > 2"))),
        List(
          Step.AllOf,
          Step.Match(
            Map("true" -> Lit.Text("all-answered")),
            Value.Literal(Lit.Text("unanswered"))
          )
        )
      ),
      "all-answered"
    ),
    (
      "a guard",
      ValueExpr(
        Value.Present(
          List("sensor.left"),
          Map("sensor.left" -> over("size(past) > 2"))
        ),
        List(Step.Count, Step.Prefix(Value.Literal(Lit.Text("present="))))
      ),
      "present=1"
    ),
    (
      "an order term",
      ValueExpr(
        Value.List(
          ValueType.Text,
          List(
            ValueExpr.literal("sensor.left"),
            ValueExpr.literal("sensor.right")
          )
        ),
        List(
          Step.First(
            List(
              SortTerm(
                ValueExpr(
                  Value.Entity(None, Part.Whole),
                  List(
                    Step.Cel(
                      "value.entity_id == 'sensor.right' && size(past) > 2",
                      past
                    )
                  )
                )
              )
            )
          )
        )
      ),
      "sensor.right"
    )
  )

  test("a slot reads a fetch at any depth, answered before it runs") {
    depths.foreach { (where, value, shown) =>
      val d = holding(value)
      assertEquals(d.validate(), Nil, clue = where)
      val html = Renderer
        .create(d)
        .renderBodyTraced(Shared.of(both), Viewing(Selections.none, answered()))
        .html
      assert(html.contains(shown), clue = s"$where: $html")
    }
  }

  test("a fetch at any depth is the node's, so a render reads it first") {
    depths.foreach { (where, value, _) =>
      val d = holding(value)
      val asks = d.validated().map(_.ready.card) match
        case Right(c: Ready.Component) => c.fetches
        case other => fail(s"$where: not a component: $other")
      assertEquals(asks, List(SlotAsk(history, Step.Json)), clue = where)
      assertEquals(d.allFetches, List(read), clue = where)
    }
  }

  test("an input's own inputs are checked as its slot's are") {
    def errs(own: Map[String, ValueExpr]) =
      holding(
        ValueExpr(
          left,
          List(
            Step.Cel(
              "string(n)",
              Map("n" -> ValueExpr(Value.Cel("size(past)", own)))
            )
          )
        )
      ).validate().mkString("\n")
    assert(
      errs(past + ("unread" -> ValueExpr(Value.Literal(Lit.Whole(1)))))
        .contains("'unread' its expression does not read")
    )
    val undeclared =
      history.copy(window =
        DashboardBuilders.chosenBy("window", Windows.presets)
      )
    assert(
      errs(Map("past" -> ValueExpr(Value.Fetch(undeclared))))
        .contains("reads the variable 'window', which no ancestor declares")
    )
    assert(
      errs(
        Map(
          "past" -> ValueExpr(
            Value.Fetch(history),
            List(Step.Cel("size(value)"))
          )
        )
      ).contains("fetches, so it is read as the answer, with no steps")
    )
  }

  test("a node's value reaches a CEL inside an input") {
    val d = holding(
      ValueExpr(
        left,
        List(
          Step.Cel(
            "'k=' + string(n)",
            Map("n" -> ValueExpr(Value.Cel("k + 1", Map.empty)))
          )
        )
      ),
      nodeInputs = Map("k" -> ValueExpr(Value.Literal(Lit.Whole(2))))
    )
    assertEquals(d.validate(), Nil)
    val html = Renderer
      .create(d)
      .renderBodyTraced(Shared.of(both), Viewing(Selections.none, answered()))
      .html
    assert(html.contains("k=3"), clue = html)
  }

  test("the wire spells a fetch value as a fetch source") {
    val slot = decode[TemplateSlot](
      """{"source": {"kind": "Entity"}, "steps": [{"kind": "Cel", "src": "size(past)", "wireInputs": {"past": {
        |  "source": {"kind": "Fetch", "fetch": {
        |  "provider": "history", "entity": "sensor.left", "window": {
        |  "kind": "Statistics", "span": 86400, "period": "5minute",
        |  "fallback": true}}}}}}]}""".stripMargin
    )
    assertEquals(
      slot.map(_.expr.cels.flatMap(_._2).toMap),
      Right(Map("past" -> ValueExpr(Value.Fetch(history))))
    )
  }
}
