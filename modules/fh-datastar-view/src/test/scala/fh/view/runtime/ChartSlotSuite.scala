package fh.view.runtime

import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  QueryTemplate,
  Ref,
  SlotQuery,
  SlotRead,
  SlotSource,
  Transform
}
import fh.view.history.ChartStyle
import fh.view.query.{Staged, QuerySnapshot}

/** A series slot resolved into the walk: the bytes reach the page, and the
  * render key moves with them.
  */
class ChartSlotSuite extends munit.FunSuite {

  private val query = QueryTemplate(
    "history",
    Map("entity" -> Ref.Literal("sensor.t"), "window" -> Ref.Literal("24h"))
  )
  private val stage = Transform.Stage.Chart(ChartStyle(width = 600))
  // Every parameter is written down, so the ask resolves to this whatever the
  // environment — which is what keeps this suite about the chart slot.
  private val read = SlotRead(
    SlotQuery("history", Map("entity" -> "sensor.t", "window" -> "24h")),
    stage
  )
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
          "chart" -> SlotSource(query = Some(query), transform = stage),
          "name" -> SlotSource(transform = "state")
        )
      )
    )

  private val states =
    Map("sensor.t" -> EntityState("sensor.t", "21.4", Map.empty))

  private def fragments(bytes: String = svg, version: Long = 100L) =
    QuerySnapshot.of(Map(read -> Staged(version, bytes)))

  private def rootId(d: Dashboard) = LayoutNode.rootId("", d.card)

  test("a series slot renders its chart into the page") {
    val d = dashboardWith("{{{chart}}}")
    val html =
      Renderer.create(d).renderBodyTraced(states, Map.empty, fragments()).html
    assert(html.contains(svg), clue = html)
    assert(html.contains("21.4"), clue = html)
  }

  test("the chart hole must be the RAW one, and the escaped one shows it") {
    // `{{chart}}` escapes the markup, so the page shows SVG source as text:
    // visible, hence a card-author rule, but the first thing a chart card gets
    // wrong.
    val escaped =
      Renderer
        .create(dashboardWith("{{chart}}"))
        .renderBodyTraced(states, Map.empty, fragments())
        .html
    assert(!escaped.contains(svg), clue = escaped)
    assert(escaped.contains("&lt;svg"), clue = escaped)
  }

  test("rendering a chart nobody resolved is an error, not an empty slot") {
    // A blank chart with no way to fill it is what architecture §0 forbids, so
    // forgetting to resolve must fail, not render empty.
    val d = dashboardWith("{{{chart}}}")
    val e = intercept[fh.view.FHError](
      Renderer
        .create(d)
        .renderBodyTraced(states, Map.empty, QuerySnapshot.empty)
    )
    assertEquals(e.status, 500)
    assert(e.getMessage.contains("not resolved for this render"))

    // …and when it IS resolved the rest of the card still renders from state;
    // the slot's `default` is not what fills a query slot.
    val html =
      Renderer.create(d).renderBodyTraced(states, Map.empty, fragments()).html
    assert(html.contains(svg), clue = html)
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
    // Without its charts every series slot would compare equal, so the
    // pre-check would serve the first chart drawn for the dashboard's life.
    val d = dashboardWith("{{{chart}}}")
    val r = Renderer.create(d)
    val id = rootId(d)
    assertNotEquals(
      r.byteSlotValues(id, states, fragments()),
      r.byteSlotValues(id, states, fragments("<svg>other</svg>"))
    )
  }
}
