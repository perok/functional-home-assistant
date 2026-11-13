package fh.view.runtime

import fh.view.testkit.TestCard
import fh.view.history.Windows
import io.github.iltotore.iron.autoRefine
import fh.view.model.{
  SlotType,
  Part,
  CardDef,
  Dashboard,
  LayoutNode,
  FetchTemplate,
  Choice,
  SlotRead,
  Step,
  Value,
  ValueExpr,
  TemplateSlot,
  Theme,
  ThemeClasses
}
import fh.view.history.ChartStyle
import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}
import fh.view.testkit.{PklFixture, PklWorkspace}
import org.pkl.core.{EvaluatorBuilder, ModuleSource}

/** A series slot resolved into the walk: the bytes reach the page, and the
  * render key moves with them.
  */
class ChartSlotSuite extends munit.FunSuite {

  private val chartFetch = FetchTemplate("sensor.t", Choice.Fixed(Windows.day))
  private val stage = Step.Chart(ChartStyle(width = 600))
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
        "chart" -> TestCard(
          s"""<div>$hole<b>{{name}}</b></div>"""
        )
      ),
      card = LayoutNode.Component(
        card = "chart",
        templateSlots = Map(
          "entity_id" -> TemplateSlot(ValueExpr.literal("sensor.t")),
          "chart" -> TemplateSlot(
            ValueExpr(
              Value.Fetch(chartFetch),
              List(Step.Chart(ChartStyle(width = 600)))
            ),
            `type` = SlotType.TextRaw
          ),
          "name" -> TemplateSlot(
            ValueExpr(
              Value.Entity(Some("sensor.t"), Part.Whole),
              List(Step.Cel("value.state"))
            )
          )
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
        .renderBodyTraced(
          Shared.of(states),
          Viewing(Selections.none, fragments())
        )
        .html
    assert(html.contains(svg), clue = html)
    assert(html.contains("21.4"), clue = html)
  }

  test(
    "a failed chart's label takes the theme's class rules; a drawing does not"
  ) {
    val classes = ThemeClasses(
      Map(
        "fh-label" -> List("fh-label", "chip"),
        "fh-label-error" -> List("fh-label-error", "red")
      )
    )
    val d = dashboardWith("{{{chart}}}").copy(
      theme = Theme(classes = classes),
      failedLabel = """<span class="fh-label fh-label-error">{{text}}</span>"""
    )
    def body(f: FetchSnapshot) =
      Renderer
        .create(d)
        .renderBodyTraced(Shared.of(states), Viewing(Selections.none, f))
        .html
    val failed =
      body(FetchSnapshot.of(Map(read -> Staged.failed(stage))))
    assert(
      failed.contains("""<span class="fh-label chip fh-label-error red""""),
      failed
    )
    val drawn = body(fragments("""<svg class="fh-label"/>"""))
    assert(drawn.contains("""<svg class="fh-label"/>"""), drawn)
  }

  test("a failed chart shows the label the library writes, in the error tone") {
    val built = PklFixture.buildDashboard(
      "failed-label",
      """amends "@fh-dashboard/entry.pkl"
        |
        |import "@fh-dashboard/components.pkl" as c
        |
        |card = c.title("x")
        |""".stripMargin
    )
    val textPkl = (PklWorkspace.resourcesLib / "core" / "text.pkl").toNIO.toUri
    val ev = EvaluatorBuilder.preconfigured().build()
    val expected =
      try
        ev.evaluateExpressionString(
          ModuleSource.text(s"""import "$textPkl" as t"""),
          """t.label("error", "Chart unavailable")"""
        )
      finally ev.close()
    val html = Renderer
      .create(
        dashboardWith("{{{chart}}}").copy(failedLabel = built.failedLabel)
      )
      .renderBodyTraced(
        Shared.of(states),
        Viewing(
          Selections.none,
          FetchSnapshot.of(Map(read -> Staged.failed(stage)))
        )
      )
      .html
    assert(html.contains(expected), html)
  }

  test("rendering a chart nobody resolved is an error, not an empty slot") {
    // A blank chart with no way to fill it is what architecture §0 forbids, so
    // forgetting to resolve must fail, not render empty.
    val d = dashboardWith("{{{chart}}}")
    val e = intercept[fh.view.FHError](
      Renderer
        .create(d)
        .renderBodyTraced(
          Shared.of(states),
          Viewing(Selections.none, FetchSnapshot.empty)
        )
    )
    assertEquals(e.status, 500)
    assert(e.getMessage.contains("not resolved for this render"))

    // …and when it IS resolved the rest of the card still renders from state;
    // the slot's `default` is not what fills a fetch slot.
    val html =
      Renderer
        .create(d)
        .renderBodyTraced(
          Shared.of(states),
          Viewing(Selections.none, fragments())
        )
        .html
    assert(html.contains(svg), clue = html)
    assert(html.contains("21.4"), clue = html)
  }

  test("the render key moves when the version does, state standing still") {
    val r = Renderer.create(dashboardWith("{{{chart}}}"))
    val id = rootId(dashboardWith("{{{chart}}}"))
    assertNotEquals(
      r.renderInputs(id, Shared.of(states), fragments()),
      r.renderInputs(id, Shared.of(states), fragments(version = 200L))
    )
  }

  test("the byte-slot values see the chart, so a new one is sent") {
    // Without its charts every series slot would compare equal, so a session
    // would keep the first chart drawn for the dashboard's life.
    val d = dashboardWith("{{{chart}}}")
    val r = Renderer.create(d)
    val id = rootId(d)
    assertNotEquals(
      r.byteSlotValues(id, Shared.of(states), fragments()),
      r.byteSlotValues(id, Shared.of(states), fragments("<svg>other</svg>"))
    )
  }
}
