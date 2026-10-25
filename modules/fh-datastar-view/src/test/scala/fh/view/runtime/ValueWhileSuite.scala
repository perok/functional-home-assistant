package fh.view.runtime

import fh.view.fetch.{FetchSnapshot, Staged}
import fh.view.model.*
import io.circe.Json
import io.circe.parser.decode

/** A slot's `valueWhile`: while it does not hold, the slot is `""`, its
  * transform does not run and its fetches are not read (#541).
  */
class ValueWhileSuite extends munit.FunSuite {

  private val history = FetchTemplate(
    "history",
    Map("entity" -> Ref.Literal("sensor.left"), "window" -> Ref.Literal("24h"))
  )
  private val read = SlotRead(
    SlotFetch("history", Map("entity" -> "sensor.left", "window" -> "24h")),
    Transform.Stage.Passthrough
  )
  private val answered = FetchSnapshot.of(
    Map(read -> Staged(7L, """{"points":[[0,0.0],[60000,101.0]]}"""))
  )

  private def above0(entity: String) = SlotSource(
    transform = "__while",
    bypassUnavailable = false,
    `type` = SlotType.Bool,
    values = Map(
      "__while" -> ExprValue.Holds(
        Predicate.Cmp("state", Op.Gt, Json.fromInt(0), Some(entity))
      )
    )
  )

  // The peak of the history: what the bar's whole reads.
  private def peak(valueWhile: Option[SlotSource]) = SlotSource(
    transform = "math.greatest(past.transformList(i, p, p[1]))",
    signal = Some(SignalBind.Text),
    values = Map("past" -> ExprValue.Fetched(history)),
    valueWhileSlot = valueWhile
  )

  private val bar = CardDef(
    """<b {{{of__bind}}}>{{of}}</b>""",
    slots = List("of")
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
        """<div>{{#children}}{{{html}}}{{/children}}</div>""",
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
    val d = dashboard(node(peak(Some(above0("sensor.left")))))
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

  test("a condition on another entity wakes the slot") {
    val d = dashboard(node(peak(Some(above0("binary_sensor.door")))))
    assert(Renderer.create(d).componentsFor("binary_sensor.door").nonEmpty)
  }

  test("a slot without one keeps its own signal beside one that has it") {
    val d = dashboard(
      node(peak(None)),
      node(peak(Some(above0("sensor.left"))))
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
          valueWhileSlot = Some(above0("sensor.left"))
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
