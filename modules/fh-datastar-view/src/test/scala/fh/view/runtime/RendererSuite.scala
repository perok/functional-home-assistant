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

  // Templates are pure content; the backend wraps entity-bound components in the
  // id'd morph target.
  private val templates = Map(
    "card" -> TemplateDef(
      """<div><span>{{state}}</span> {{unit}}</div>""",
      List("state")
    ),
    "btn" -> TemplateDef("""<button>{{label}}</button>""", List("label")),
    "gauge" -> TemplateDef("""<i>{{bri}}</i>""", List("bri")),
    "col" -> TemplateDef(
      """<div class="fh-col">{{#children}}{{{html}}}{{/children}}</div>""",
      Nil
    ),
    "row" -> TemplateDef(
      """<div class="fh-row">{{#children}}{{{html}}}{{/children}}</div>""",
      Nil
    )
  )

  private def col(kids: LayoutNode*): LayoutNode =
    LayoutNode.Component("col", children = kids.toList)
  private def row(kids: LayoutNode*): LayoutNode =
    LayoutNode.Component("row", children = kids.toList)

  private def renderer(layout: LayoutNode): Renderer = {
    val d = Dashboard(templates, layout)
    new Renderer(d, Templates.from(d))
  }

  // A single component as the layout root gets the path id "c".
  private val card = LayoutNode.Component(
    template = "card",
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

  test("reverse index maps entity to the generated component id") {
    val r = renderer(col(card))
    // root column -> child at index 0 -> "c_0"
    assertEquals(r.componentsFor("sensor.t"), Set("c_0"))
    assertEquals(r.componentsFor("sensor.other"), Set.empty[String])
  }

  test("entity-bound component is wrapped in the id'd morph target; slots escaped") {
    val html = renderer(card).renderNodeById("c", states).get
    // backend-owned morph target wraps the pure-content template
    assert(html.startsWith("""<div class="fh-cell" id="c"><div>"""), clue = html)
    assert(html.contains("&lt;"), clue = html)
    assert(html.contains("&amp;"), clue = html)
    assert(html.contains("°C"), clue = html)
    assert(!html.contains("2 < 3"), clue = html)
  }

  test("missing entity renders empty slots rather than throwing") {
    val html = renderer(card).renderNodeById("c", Map.empty).get
    assertEquals(
      html,
      """<div class="fh-cell" id="c"><div><span></span> </div></div>"""
    )
  }

  test("container templates splice children; entity-less nodes are not wrapped") {
    val layout = col(row(LayoutNode.Component("btn", Map("label" -> "Go"))))
    val r = renderer(layout)
    val page = r.renderPage(Map.empty)
    // no entities anywhere -> no morph wrappers, no ids in the markup
    assertEquals(
      page,
      """<main class="container"><div class="fh-col"><div class="fh-row"><button>Go</button></div></div></main>"""
    )
    // containers are still addressable and re-render their children by id
    assertEquals(
      r.renderNodeById("c_0", Map.empty).get,
      """<div class="fh-row"><button>Go</button></div>"""
    )
  }

  test("slot default applies when value is missing, empty, or JSON null") {
    val g = LayoutNode.Component(
      "gauge",
      entities = List("light.x"),
      slots = Map(
        "bri" -> SlotSource("light.x", Some("brightness"), default = Some("0"))
      )
    )
    val r = renderer(g)
    val wrap = (inner: String) => s"""<div class="fh-cell" id="c">$inner</div>"""
    assertEquals(r.renderNodeById("c", Map.empty).get, wrap("""<i>0</i>"""))
    val off = Map("light.x" -> st("off", "brightness" -> Json.Null))
    assertEquals(r.renderNodeById("c", off).get, wrap("""<i>0</i>"""))
    val on = Map("light.x" -> st("on", "brightness" -> Json.fromInt(200)))
    assertEquals(r.renderNodeById("c", on).get, wrap("""<i>200</i>"""))
  }

  test("dynamic group filters by query and dispatches per matching case") {
    val dyn = LayoutNode.Dynamic(
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
    val r = renderer(dyn)
    // dynamic as layout root -> the group's own id'd container "c" is the morph
    // target; children are rendered inside it (not individually wrapped).
    val html = r.renderNodeById("c", states).get
    assert(html.startsWith("""<div id="c">"""), clue = html)
    // light.a dispatched to the btn case, sensor.b to the card case
    assert(html.contains("""<button>Lamp</button>"""), clue = html)
    assert(html.contains("""<div><span>hot</span>"""), clue = html)
    // sensor.c excluded by the membership query (battery 50)
    assert(!html.contains("cold"), clue = html)
    assertEquals(r.dynamicContainerIds, List("c"))
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
