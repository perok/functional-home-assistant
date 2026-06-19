package fh.view.runtime

import fh.view.model.{ComponentDef, Dashboard, SlotSource}
import io.circe.Json

class RendererSuite extends munit.FunSuite {

  private val dashboard = Dashboard(
    templates =
      Map("c1" -> """<div id="c1"><span>{{state}}</span> {{unit}}</div>"""),
    registry = Map(
      "c1" -> ComponentDef(
        entities = List("sensor.t"),
        slots = Map(
          "state" -> SlotSource("sensor.t", None),
          "unit" -> SlotSource("sensor.t", Some("unit_of_measurement"))
        )
      )
    ),
    layout = """<main>{{{c1}}}</main>"""
  )

  private val renderer = new Renderer(dashboard, Templates.from(dashboard))

  private val states = Map(
    "sensor.t" -> EntityState(
      state = """2 < 3 & "x"""",
      attributes = Map("unit_of_measurement" -> Json.fromString("°C"))
    )
  )

  test("reverse index maps entity to dependent components") {
    assertEquals(renderer.componentsFor("sensor.t"), Set("c1"))
    assertEquals(renderer.componentsFor("sensor.other"), Set.empty[String])
  }

  test("component slot values are filled and HTML-escaped") {
    val html = renderer.renderComponent("c1", states).get
    assert(html.contains("&lt;"), clue = html)
    assert(html.contains("&amp;"), clue = html)
    assert(html.contains("°C"), clue = html)
    // the injected value must not contain a raw unescaped '<'
    assert(!html.contains("2 < 3"), clue = html)
  }

  test("missing entity renders empty slots rather than throwing") {
    val html = renderer.renderComponent("c1", Map.empty).get
    assertEquals(html, """<div id="c1"><span></span> </div>""")
  }

  test("layout injects component HTML unescaped") {
    val page = renderer.renderPage(states)
    assert(page.startsWith("<main><div id=\"c1\">"), clue = page)
    assert(page.endsWith("</div></main>"), clue = page)
  }
}
