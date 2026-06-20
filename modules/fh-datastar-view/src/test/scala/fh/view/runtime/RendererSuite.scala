package fh.view.runtime

import fh.view.model.{
  Dashboard,
  DynamicCase,
  LayoutNode,
  Op,
  Predicate,
  SlotSource,
  TemplateDef
}
import io.circe.Json

class RendererSuite extends munit.FunSuite {

  private def st(state: String, attrs: (String, Json)*): EntityState =
    EntityState(state, attrs.toMap)

  private val templates = Map(
    "card" -> TemplateDef(
      """<div id="{{id}}"><span>{{state}}</span> {{unit}}</div>""",
      List("id", "state")
    ),
    "btn" -> TemplateDef(
      """<button id="{{id}}">{{label}}</button>""",
      List("id", "label")
    ),
    "gauge" -> TemplateDef("""<i id="{{id}}">{{bri}}</i>""", List("id", "bri"))
  )

  private def renderer(layout: LayoutNode): Renderer = {
    val d = Dashboard(templates, layout)
    new Renderer(d, Templates.from(d))
  }

  private val card1 = LayoutNode.Component(
    id = "c1",
    template = "card",
    params = Map("id" -> "c1"),
    entities = List("sensor.t"),
    slots = Map(
      "state" -> SlotSource("sensor.t", None),
      "unit" -> SlotSource("sensor.t", Some("unit_of_measurement"))
    )
  )

  private val states = Map(
    "sensor.t" -> st(
      """2 < 3 & "x"""",
      "unit_of_measurement" -> Json.fromString("°C")
    )
  )

  test("reverse index maps entity to dependent components") {
    val r = renderer(LayoutNode.Column(List(card1)))
    assertEquals(r.componentsFor("sensor.t"), Set("c1"))
    assertEquals(r.componentsFor("sensor.other"), Set.empty[String])
  }

  test("component slot values are filled and HTML-escaped") {
    val html = renderer(card1).renderNodeById("c1", states).get
    assert(html.contains("&lt;"), clue = html)
    assert(html.contains("&amp;"), clue = html)
    assert(html.contains("°C"), clue = html)
    assert(!html.contains("2 < 3"), clue = html)
  }

  test("missing entity renders empty slots rather than throwing") {
    val html = renderer(card1).renderNodeById("c1", Map.empty).get
    assertEquals(html, """<div id="c1"><span></span> </div>""")
  }

  test("layout tree nests rows and columns") {
    val layout = LayoutNode.Column(
      List(
        LayoutNode.Row(
          List(
            LayoutNode
              .Component("b1", "btn", Map("id" -> "b1", "label" -> "Go"))
          )
        )
      )
    )
    val page = renderer(layout).renderPage(Map.empty)
    assert(
      page.startsWith("""<main class="container"><div class="fh-col"><div class="fh-row">"""),
      clue = page
    )
    assert(page.contains("""<button id="b1">Go</button>"""), clue = page)
    assert(page.endsWith("</div></div></main>"), clue = page)
  }

  test("slot default applies when value is missing, empty, or JSON null") {
    val g = LayoutNode.Component(
      "g",
      "gauge",
      params = Map("id" -> "g"),
      entities = List("light.x"),
      slots =
        Map("bri" -> SlotSource("light.x", Some("brightness"), default = Some("0")))
    )
    val r = renderer(g)
    assertEquals(r.renderNodeById("g", Map.empty).get, """<i id="g">0</i>""")
    val off = Map("light.x" -> st("off", "brightness" -> Json.Null))
    assertEquals(r.renderNodeById("g", off).get, """<i id="g">0</i>""")
    val on = Map("light.x" -> st("on", "brightness" -> Json.fromInt(200)))
    assertEquals(r.renderNodeById("g", on).get, """<i id="g">200</i>""")
  }

  test("dynamic group filters by query and dispatches per matching case") {
    val dyn = LayoutNode.Dynamic(
      id = "grp",
      query = Some(Predicate.Cmp("attr:battery", Op.Lt, Json.fromInt(20))),
      cases = List(
        DynamicCase(
          Predicate.Cmp("domain", Op.Eq, Json.fromString("light")),
          "btn"
        ),
        DynamicCase(
          Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__")),
          "card",
          slots = Map("state" -> SlotSource("$self"))
        )
      )
    )
    val states = Map(
      "light.a" -> st("on", "battery" -> Json.fromInt(10), "friendly_name" -> Json.fromString("Lamp")),
      "sensor.b" -> st("hot", "battery" -> Json.fromInt(5)),
      "sensor.c" -> st("cold", "battery" -> Json.fromInt(50))
    )
    val html = renderer(dyn).renderNodeById("grp", states).get
    // light -> btn (auto label from friendly_name), sensor under threshold -> card
    assert(html.contains("""<button id="grp_light_a">Lamp</button>"""), clue = html)
    assert(html.contains("""<div id="grp_sensor_b"><span>hot</span>"""), clue = html)
    // sensor.c excluded by the membership query (battery 50)
    assert(!html.contains("grp_sensor_c"), clue = html)
    assert(html.startsWith("""<div id="grp">"""), clue = html)
    // dynamic container id is exposed for the live-update loop
    assertEquals(renderer(dyn).dynamicContainerIds, List("grp"))
  }

  test("Predicate evaluation: comparisons and boolean combinators") {
    val s = st("18", "battery" -> Json.fromInt(15))
    assert(Renderer.matches(Predicate.Cmp("domain", Op.Eq, Json.fromString("sensor")), "sensor.x", s))
    assert(!Renderer.matches(Predicate.Cmp("domain", Op.Eq, Json.fromString("light")), "sensor.x", s))
    assert(Renderer.matches(Predicate.Cmp("attr:battery", Op.Lt, Json.fromInt(20)), "sensor.x", s))
    assert(!Renderer.matches(Predicate.Cmp("attr:battery", Op.Gte, Json.fromInt(20)), "sensor.x", s))
    assert(Renderer.matches(Predicate.Cmp("state", Op.Lte, Json.fromInt(18)), "sensor.x", s))

    val both = Predicate.And(
      List(
        Predicate.Cmp("domain", Op.Eq, Json.fromString("sensor")),
        Predicate.Cmp("attr:battery", Op.Lt, Json.fromInt(20))
      )
    )
    assert(Renderer.matches(both, "sensor.x", s))
    assert(Renderer.matches(Predicate.Not(both), "light.x", s))
    assert(
      Renderer.matches(
        Predicate.Or(List(Predicate.Cmp("domain", Op.Eq, Json.fromString("light")), both)),
        "sensor.x",
        s
      )
    )
  }
}
