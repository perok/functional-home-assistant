package fh.view.runtime

import fh.view.model.{CardDef, Dashboard, Region, SlotSource}
import fh.view.testkit.DashboardBuilders.{col, component, st}

/** A card whose subject is a transform, resolved per paint: the one shape
  * `Renderer`'s per-node plan cannot precompute, since every inherited entity,
  * signal name and binding hangs off the subject. `resolveDirect` exists for
  * it, and nothing else exercises it end to end.
  */
class DynamicSubjectSuite extends munit.FunSuite {

  private val cards = Map(
    "col" -> CardDef(
      "<div>{{#children}}{{{html}}}{{/children}}</div>",
      regions = Map("children" -> Region())
    ),
    // Names no entity of its own, so it grounds on this paint's `entity_id`.
    "card" -> CardDef(
      """<span>{{state}}</span>""",
      slots = List(Dashboard.SubjectSlot, "state")
    )
  )

  /** `entity_id` as a transform off a POINTER entity: the pointer's state names
    * the entity the card is really about.
    */
  private val dash = Dashboard(
    cards,
    col(
      component(
        "card",
        Dashboard.SubjectSlot -> SlotSource(Some("sensor.pointer")),
        "state" -> SlotSource(None)
      )
    )
  )

  private val renderer = Renderer.create(dash)

  private def states(points: String, a: String, b: String) = Map(
    "sensor.pointer" -> st("sensor.pointer", points),
    "sensor.a" -> st("sensor.a", a),
    "sensor.b" -> st("sensor.b", b)
  )

  test("the card reads the entity its subject currently names") {
    import RendererTestOps.*
    val first = renderer.renderBody(states("sensor.a", "warm", "cold"))
    assert(first.contains("warm"), clue = first)
    assert(!first.contains("cold"), clue = first)
  }

  test("moving the pointer moves what the card reads") {
    // The whole point of a dynamic subject: nothing about the NODE changed,
    // only which entity it is about.
    import RendererTestOps.*
    val moved = renderer.renderBody(states("sensor.b", "warm", "cold"))
    assert(moved.contains("cold"), clue = moved)
    assert(!moved.contains("warm"), clue = moved)
  }

  test("a subject that names nothing renders empty, not stale") {
    import RendererTestOps.*
    val gone = renderer.renderBody(states("sensor.missing", "warm", "cold"))
    assert(!gone.contains("warm"), clue = gone)
    assert(!gone.contains("cold"), clue = gone)
  }
}
