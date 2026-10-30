package fh.view.runtime

import fh.view.history.Windows
import io.github.iltotore.iron.autoRefine
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  FetchTemplate,
  Reads,
  Choice,
  SlotRead,
  SlotSource,
  Theme,
  ThemeClasses,
  Transform
}
import fh.view.history.ChartStyle
import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}

/** A series slot resolved into the walk: the bytes reach the page, and the
  * render key moves with them.
  */
class ChartSlotSuite extends munit.FunSuite {

  private val chartFetch = FetchTemplate("sensor.t", Choice.Fixed(Windows.day))
  private val stage = Transform.Stage.Chart(ChartStyle(width = 600))
  // Every parameter is written down, so the ask resolves to this whatever the
  // environment — which is what keeps this suite about the chart slot.
  private val read = SlotRead(
    FetchRequest.History("sensor.t", Windows.day),
    stage
  )
  private val svg = """<svg width="600"><path d="M0 0"/></svg>"""

  private def dashboardWith(hole: String) =
    Dashboard(
      cards = Map(
        "chart" -> CardDef(
          s"""<div>$hole<b>{{name}}</b></div>"""
        )
      ),
      card = LayoutNode.Component(
        card = "chart",
        slots = Map(
          "entity_id" -> SlotSource(literal = Some("sensor.t")),
          "chart" -> SlotSource(
            fetch = Some(chartFetch),
            transform = stage,
            reads = Reads.OnRender
          ),
          "name" -> SlotSource(transform = "state")
        )
      )
    )

  private val states =
    Map("sensor.t" -> EntityState("sensor.t", "21.4", Map.empty))

  private def fragments(bytes: String = svg, version: Long = 100L) =
    FetchSnapshot.of(Map(read -> Staged(version, bytes)))

  private def rootId(d: Dashboard) = LayoutNode.rootId("", d.card)

  test("a series slot renders its chart into the page") {
    val d = dashboardWith("{{{chart}}}")
    val html =
      Renderer
        .create(d)
        .renderBodyTraced(states, Selections.none, fragments())
        .html
    assert(html.contains(svg), clue = html)
    assert(html.contains("21.4"), clue = html)
  }

  test(
    "a failed chart's label takes the theme's class rules; a drawing does not"
  ) {
    val classes = ThemeClasses(Map("fh-label" -> List("fh-label", "chip")))
    val d = dashboardWith("{{{chart}}}").copy(theme = Theme(classes = classes))
    def body(f: FetchSnapshot) =
      Renderer.create(d).renderBodyTraced(states, Selections.none, f).html
    val failed =
      body(FetchSnapshot.of(Map(read -> Staged.failed(stage))))
    assert(
      failed.contains("""<span class="fh-label chip fh-label-error"""),
      failed
    )
    val drawn = body(fragments("""<svg class="fh-label"/>"""))
    assert(drawn.contains("""<svg class="fh-label"/>"""), drawn)
  }

  test("rendering a chart nobody resolved is an error, not an empty slot") {
    // A blank chart with no way to fill it is what architecture §0 forbids, so
    // forgetting to resolve must fail, not render empty.
    val d = dashboardWith("{{{chart}}}")
    val e = intercept[fh.view.FHError](
      Renderer
        .create(d)
        .renderBodyTraced(states, Selections.none, FetchSnapshot.empty)
    )
    assertEquals(e.status, 500)
    assert(e.getMessage.contains("not resolved for this render"))

    // …and when it IS resolved the rest of the card still renders from state;
    // the slot's `default` is not what fills a fetch slot.
    val html =
      Renderer
        .create(d)
        .renderBodyTraced(states, Selections.none, fragments())
        .html
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
