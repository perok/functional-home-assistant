package fh.view.runtime

import fh.view.history.Windows
import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}
import fh.view.model.*
import io.circe.Json
import io.circe.parser.decode

/** A slot's `valueWhile`: while it does not hold, the slot is `""`, its
  * transform does not run and its fetches are not read (#541).
  */
class ValueWhileSuite extends munit.FunSuite {

  private val history = FetchTemplate("sensor.left", Choice.Fixed(Windows.day))
  private val read = SlotRead(
    FetchRequest.History("sensor.left", Windows.day),
    Transform.Stage.Passthrough
  )
  private val answered = FetchSnapshot.of(
    Map(read -> Staged(7L, """{"points":[[0,0.0],[60000,101.0]]}"""))
  )

  private def above0(entity: Option[String]) = SlotSource(
    transform = Predicate.Cmp("state", Op.Gt, Json.fromInt(0), entity),
    bypassUnavailable = false,
    `type` = SlotType.Bool
  )

  // The peak of the history: what the bar's whole reads.
  private def peak(valueWhile: Option[SlotSource]) = SlotSource(
    transform = "math.greatest(past.transformList(i, p, p[1]))",
    signal = Some(SignalBind.Text),
    values = Map("past" -> ExprValue.Fetched(history)),
    valueWhileSlot = valueWhile
  )

  private val bar = CardDef(
    """<b {{{of__bind}}}>{{of}}</b>"""
  )

  private def node(of: SlotSource) = LayoutNode.Component(
    card = "bar",
    slots = Map(
      "entity_id" -> SlotSource(literal = Some("sensor.left")),
      "of" -> of
    )
  )

  private def dashboard(nodes: LayoutNode.Component*) = Dashboard(
    cards = Map(
      "bar" -> bar,
      "col" -> CardDef(
        """<div>{{{children}}}</div>""",
        regions = Map(LayoutNode.DefaultRegion -> Region())
      )
    ),
    card = LayoutNode.Component(
      card = "col",
      regions = Map(LayoutNode.DefaultRegion -> nodes.toList)
    )
  )

  private def idOf(d: Dashboard, i: Int) = LayoutNode.childId(
    "",
    LayoutNode.rootId("", d.card),
    LayoutNode.Step(LayoutNode.DefaultRegion, i),
    node(peak(None))
  )

  private def states(left: String, door: String = "off") = Map(
    "sensor.left" -> EntityState("sensor.left", left, Map.empty),
    "binary_sensor.door" -> EntityState("binary_sensor.door", door, Map.empty)
  )

  test("while it does not hold, no fetch is read and the slot is empty") {
    val d = dashboard(node(peak(Some(above0(None)))))
    assertEquals(d.validate(), Nil)
    val r = Renderer.create(d)
    assertEquals(r.fetchesForPage(Set.empty, states("0"), Map.empty), Nil)
    // Unanswered, and still renders: the transform never ran.
    assertEquals(
      r.signalsFor(idOf(d, 0), states("0"), FetchSnapshot.empty).values.toList,
      List("")
    )
    assertEquals(
      r.fetchesForPage(Set.empty, states("46"), Map.empty),
      List(read)
    )
    assertEquals(
      r.signalsFor(idOf(d, 0), states("46"), answered).values.toList,
      List("101")
    )
  }

  test("one snapshot decides each thing once, for every reader of it") {
    val decided = Decided()
    val snapshot = states("46")
    var runs = 0
    def ask(at: Map[String, EntityState]) =
      decided.at(at).holds("k") { runs += 1; true }
    assert(ask(snapshot) && ask(snapshot))
    assertEquals(runs, 1)
    // Equal content, another map: decided afresh, never assumed.
    assert(ask(states("46")))
    assertEquals(runs, 2)
  }

  test("a set asks only for its present members, in the clause they are in") {
    def chart(e: String) = LayoutNode.Component(
      "chart",
      Map(
        "chart" -> SlotSource(
          fetch = Some(
            FetchTemplate(e, Choice.Fixed(Windows.day))
          ),
          transform = Transform.Stage.Passthrough,
          reads = Reads.OnRender
        )
      )
    )
    val on = Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on")))
    def clauses(cs: LayoutNode.SetClause*) = LayoutNode.SetMember(cs.toList)
    val d = Dashboard(
      cards = Map("chart" -> CardDef("<div>{{chart}}</div>")),
      card = LayoutNode.SetNode(
        candidates = List("light.a", "light.b"),
        members =
          Map(
            "light.a" -> clauses(
              LayoutNode.SetClause(on, chart("sensor.a_on")),
              LayoutNode.SetClause(None, chart("sensor.a_off"))
            ),
            // No clause matches while it is off: not a member.
            "light.b" -> clauses(LayoutNode.SetClause(on, chart("sensor.b_on")))
          )
      )
    )
    val r = Renderer.create(d)
    def asked(a: String, b: String) =
      r.fetchesForPage(
        Set.empty,
        Map(
          "light.a" -> EntityState("light.a", a, Map.empty),
          "light.b" -> EntityState("light.b", b, Map.empty)
        ),
        Map.empty
      ).map { case SlotRead(FetchRequest.History(e, _), _) => e }
    assertEquals(asked("on", "off"), List("sensor.a_on"))
    assertEquals(asked("off", "on"), List("sensor.a_off", "sensor.b_on"))
  }

  test("CEL decides it as well, when an author writes CEL") {
    val cel = SlotSource(
      transform = "double(state) > 0.0",
      bypassUnavailable = false,
      `type` = SlotType.Bool
    )
    val d = dashboard(node(peak(Some(cel))))
    assertEquals(d.validate(), Nil)
    val r = Renderer.create(d)
    assertEquals(r.fetchesForPage(Set.empty, states("0"), Map.empty), Nil)
    assertEquals(
      r.fetchesForPage(Set.empty, states("46"), Map.empty),
      List(read)
    )
  }

  test("a condition on another entity wakes the slot") {
    val d = dashboard(node(peak(Some(above0(Some("binary_sensor.door"))))))
    assert(Renderer.create(d).componentsFor("binary_sensor.door").nonEmpty)
  }

  test("a slot without one keeps its own signal beside one that has it") {
    val d = dashboard(
      node(peak(None)),
      node(peak(Some(above0(None))))
    )
    val r = Renderer.create(d)
    val plain = r.signalsFor(idOf(d, 0), states("0"), answered)
    val gated = r.signalsFor(idOf(d, 1), states("0"), answered)
    assert(plain.keySet.intersect(gated.keySet).isEmpty, clue = (plain, gated))
    assertEquals(plain.values.toList, List("101"))
    assertEquals(gated.values.toList, List(""))
  }

  test("the build refuses one that reads a fetch, is not a bool, or is once") {
    def errs(of: SlotSource) = dashboard(node(of)).validate().mkString("\n")
    val readsFetch = SlotSource(
      transform = "size(past) > 0",
      `type` = SlotType.Bool,
      values = Map("past" -> ExprValue.Fetched(history))
    )
    assert(
      errs(peak(Some(readsFetch))).contains("valueWhile that reads a fetch")
    )
    assert(
      errs(peak(Some(SlotSource(transform = "state", `type` = SlotType.Bool))))
        .contains("'of.valueWhile' is a boolean slot")
    )
    assert(
      errs(
        SlotSource(
          reads = Reads.Once,
          valueWhileSlot = Some(above0(None))
        )
      ).contains("has a valueWhile — it would keep its first answer")
    )
  }

  test("the wire nests it as a slot") {
    assertEquals(
      decode[SlotSource](
        """{"transform": "state", "valueWhileSlot": {"transform": "on", "type": "bool"}}"""
      ).map(_.valueWhileSlot.map(_.transform)),
      Right(Some("on"))
    )
  }
}
