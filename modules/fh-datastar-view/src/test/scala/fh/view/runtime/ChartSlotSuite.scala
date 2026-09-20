package fh.view.runtime

import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  SlotQuery,
  SlotSource
}
import fh.view.query.{Fragment, Fragments}

/** A series slot resolved into the walk: the bytes reach the page, and the
  * render key moves with them.
  */
class ChartSlotSuite extends munit.FunSuite {

  private val query =
    SlotQuery("history", Map("entity" -> "sensor.t", "window" -> "24h"))
  private val svg = """<svg width="600"><path d="M0 0"/></svg>"""

  private def dashboardWith(hole: String) =
    Dashboard(
      cards = Map(
        "chart" -> CardDef(
          s"""<div>$hole<b>{{name}}</b></div>""",
          slots = List("chart", "name")
        )
      ),
      card = LayoutNode.Component(
        card = "chart",
        slots = Map(
          "entity_id" -> SlotSource(literal = Some("sensor.t")),
          "chart" -> SlotSource(query = Some(query)),
          "name" -> SlotSource(transform = "state")
        )
      )
    )

  private val states =
    Map("sensor.t" -> EntityState("sensor.t", "21.4", Map.empty))

  private def fragments(bytes: String = svg, version: Long = 100L) =
    Fragments(Map(query -> Fragment(version, bytes)))

  private def rootId(d: Dashboard) = LayoutNode.rootId("", d.card)

  test("a series slot renders its chart into the page") {
    val d = dashboardWith("{{{chart}}}")
    val html = Renderer.create(d).renderBodyTraced(states, Map.empty, fragments()).html
    assert(html.contains(svg), clue = html)
    assert(html.contains("21.4"), clue = html)
  }

  test("the chart hole must be the RAW one, and the escaped one shows it") {
    // Not a style preference: `{{chart}}` escapes the markup, so the page
    // shows the SVG source as text. The failure is visible rather than silent,
    // which is the only reason this is a card-author rule and not a validate
    // rule — but it is the first thing anyone writing a chart card gets wrong.
    val escaped =
      Renderer
        .create(dashboardWith("{{chart}}"))
        .renderBodyTraced(states, Map.empty, fragments())
        .html
    assert(!escaped.contains(svg), clue = escaped)
    assert(escaped.contains("&lt;svg"), clue = escaped)
  }

  test("no chart yet renders the slot empty, not the slot's default") {
    val d = dashboardWith("{{{chart}}}")
    val html =
      Renderer.create(d).renderBodyTraced(states, Map.empty, Fragments.none).html
    assert(!html.contains("<svg"), clue = html)
    assert(html.contains("21.4"), clue = html)
  }

  test("the render key moves when the version does, state standing still") {
    val r = Renderer.create(dashboardWith("{{{chart}}}"))
    val id = rootId(dashboardWith("{{{chart}}}"))
    assertNotEquals(
      r.renderInputs(id, states, fragments()),
      r.renderInputs(id, states, fragments(version = 200L))
    )
  }

  test("the byte-slot pre-check sees the chart, so new bytes are not held") {
    // The pre-check answers "equal values, equal bytes, so the cached entry
    // stands". Resolving a series slot without its charts would make every
    // chart compare equal to every other — the key would move, the pre-check
    // would say nothing changed, and the first chart drawn would be served for
    // the life of the dashboard.
    val d = dashboardWith("{{{chart}}}")
    val r = Renderer.create(d)
    val id = rootId(d)
    assertNotEquals(
      r.byteSlotValues(id, states, fragments()),
      r.byteSlotValues(id, states, fragments("<svg>other</svg>"))
    )
  }
}
