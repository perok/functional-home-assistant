package fh.view.runtime

import fh.view.model.{CardDef, Dashboard, Region, SlotSource}
import fh.view.testkit.DashboardBuilders.{col, component, lit, st}

/** A card whose SUBJECT is itself resolved per paint — `entity_id` given as a
  * transform rather than a literal, so which entity the card is about can
  * change while the page is open.
  *
  * It is the one shape `Renderer`'s per-node plan cannot precompute: every
  * slot's inherited entity, every signal name and every binding hangs off the
  * subject. `resolveDirect` exists for it. Nothing exercised it end to end,
  * which is how it came to be reachable but unreached.
  */
class DynamicSubjectSuite extends munit.FunSuite {

  private val cards = Map(
    "col" -> CardDef(
      "<div>{{#children}}{{{html}}}{{/children}}</div>",
      regions = Map("children" -> Region())
    ),
    // Reads the subject's state through the ordinary inherited-entity path:
    // the slot names no entity of its own, so it must ground on whatever
    // `entity_id` resolved to THIS paint.
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
