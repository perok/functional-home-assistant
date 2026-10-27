package fh.view.runtime

import fh.view.history.Windows
import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}
import fh.view.model.*
import io.circe.parser.decode

/** A fetch as an expression value: a state slot's CEL reads a provider's answer
  * by name, fetched before it runs, on the render path and on the signal path
  * alike (#541 E3b).
  */
class FetchValueSuite extends munit.FunSuite {

  private val history = FetchTemplate("sensor.left", Choice.Fixed(Windows.day))
  private val read = SlotRead(
    FetchRequest.History("sensor.left", Windows.day),
    Transform.Stage.Passthrough
  )
  // A wash that started at 101 after an idle 0.
  private val answer = """{"points":[[0,0.0],[60000,101.0],[120000,90.0]]}"""
  private val peak =
    "cel.bind(zeros, past.transformList(i, p, p[1] <= 0.0, p[0]), " +
      "cel.bind(since, size(zeros) == 0 ? 0.0 : math.greatest(zeros), " +
      "math.greatest(past.transformList(i, p, p[0] > since, p[1]) + [num(state)])))"

  private def of(
      values: Map[String, ExprValue] = Map(
        "past" -> ExprValue.Fetched(history)
      ),
      reads: String = Reads.Live
  ) = SlotSource(
    transform = peak,
    signal = Some(SignalBind.Handler),
    values = values,
    reads = reads
  )

  private def dashboard(
      ofSlot: SlotSource = of(),
      nodeValues: Map[String, ExprValue] = Map.empty
  ) = Dashboard(
    cards = Map(
      "bar" -> CardDef(
        """<div data-of="{{of__signal}}">{{value}}</div>""",
        slots = List("value", "of")
      )
    ),
    card = LayoutNode.Component(
      card = "bar",
      slots = Map(
        "entity_id" -> SlotSource(literal = Some("sensor.left")),
        "value" -> SlotSource(transform = "state"),
        "of" -> ofSlot
      ),
      values = nodeValues
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
      .renderBodyTraced(states("47"), Selections.none, answered())
      .html
    assert(html.contains("101"), clue = html)
  }

  test(
    "the signal path reads the fetch too, and a missing answer is no points"
  ) {
    val d = dashboard()
    val r = Renderer.create(d)
    def ofNow(left: String, f: FetchSnapshot) =
      r.signalsFor(rootId(d), states(left), f).values.toSet
    assert(ofNow("46", answered()).contains("101"))
    // A failed read is `""`, so the current reading stands in.
    assert(ofNow("46", answered("")).contains("46"))
  }

  test("a value's fetch is the node's, so a render reads it first") {
    val d = dashboard()
    val fetches = d.card match
      case c: LayoutNode.Component => c.fetches
      case other                   => fail(s"not a component: $other")
    assertEquals(fetches, List(SlotAsk(history, Transform.Stage.Passthrough)))
    assertEquals(d.allFetches, List(read))
  }

  test("a node's fetch value reaches the slot that reads it") {
    val d = dashboard(
      ofSlot = of(values = Map.empty),
      nodeValues = Map("past" -> ExprValue.Fetched(history))
    )
    assertEquals(d.validate(), Nil)
    assert(
      Renderer
        .create(d)
        .signalsFor(rootId(d), states("46"), answered())
        .values
        .toSet
        .contains("101")
    )
  }

  test("a slot's values are checked as a node's are") {
    def errs(d: Dashboard) = d.validate().mkString("\n")
    assert(
      errs(dashboard(nodeValues = Map("past" -> ExprValue.Whole(1))))
        .contains("and so does its node")
    )
    assert(
      errs(
        dashboard(ofSlot =
          of(values =
            Map(
              "past" -> ExprValue.Fetched(history),
              "unread" -> ExprValue.Whole(1)
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
      history.copy(window = Choice.Chosen("window", Windows.presets))
    assert(
      errs(
        dashboard(ofSlot =
          of(values = Map("past" -> ExprValue.Fetched(undeclared)))
        )
      ).contains("reads the variable 'window', which no ancestor declares")
    )
  }

  test("the wire spells a fetch value by its provider") {
    val slot = decode[SlotSource](
      """{"transform": "size(past)", "values": {"past": {"provider": "history",
        |  "entity": "sensor.left", "window": {"kind": "Statistics",
        |  "span": 86400, "period": "5minute", "fallback": true}}}}""".stripMargin
    )
    assertEquals(
      slot.map(_.values),
      Right(Map("past" -> ExprValue.Fetched(history)))
    )
  }
}
