package fh.view.runtime

import fh.view.model.{
  CardDef,
  Dashboard,
  DynamicCase,
  LayoutNode,
  Op,
  Predicate,
  SlotSource,
  Theme
}
import io.circe.Json

class RendererSuite extends munit.FunSuite {

  private def st(
      entityId: String,
      state: String,
      attrs: (String, Json)*
  ): EntityState =
    EntityState(entityId, state, attrs.toMap)

  // Card templates are pure content; the backend wraps entity-bound components
  // in the id'd morph target.
  private val cards = Map(
    "card" -> CardDef(
      """<div><span>{{state}}</span> {{unit}}</div>""",
      slots = List("state")
    ),
    "btn" -> CardDef("""<button>{{label}}</button>""", params = List("label")),
    "gauge" -> CardDef("""<i>{{bri}}</i>""", slots = List("bri")),
    "act" -> CardDef(
      """<a href="{{{action}}}">go</a>""",
      slots = List("action")
    ),
    "col" -> CardDef(
      """<div class="fh-col">{{#children}}{{{html}}}{{/children}}</div>"""
    ),
    "row" -> CardDef(
      """<div class="fh-row">{{#children}}{{{html}}}{{/children}}</div>"""
    )
  )

  private def col(kids: LayoutNode*): LayoutNode =
    LayoutNode.Component("col", children = kids.toList)
  private def row(kids: LayoutNode*): LayoutNode =
    LayoutNode.Component("row", children = kids.toList)

  private def renderer(layout: LayoutNode): Renderer = {
    val d = Dashboard(cards, layout)
    Renderer.create(d)
  }

  // A single component as the layout root gets the path id "c".
  private val card = LayoutNode.Component(
    card = "card",
    entities = List("sensor.t"),
    slots = Map(
      "state" -> SlotSource("sensor.t"),
      "unit" -> SlotSource("sensor.t", "$attr.unit_of_measurement")
    )
  )

  private val states = Map(
    "sensor.t" -> st(
      "sensor.t",
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

  test(
    "entity-bound component is wrapped in the id'd morph target; slots escaped"
  ) {
    val html = renderer(card).renderNodeById("c", states).get
    // backend-owned morph target wraps the pure-content template
    assert(
      html.startsWith("""<div class="fh-cell" id="c"><div>"""),
      clue = html
    )
    assert(html.contains("&lt;"), clue = html)
    assert(html.contains("&amp;"), clue = html)
    assert(html.contains("°C"), clue = html)
    assert(!html.contains("2 < 3"), clue = html)
  }

  test("unavailable entity bypasses the transform and shows its raw state") {
    val node = LayoutNode.Component(
      card = "card",
      entities = List("sensor.t"),
      slots = Map(
        "state" -> SlotSource(
          "sensor.t",
          transform = "$round($number($state), 1)",
          bypassUnavailable = true
        )
      )
    )
    val r = renderer(node)
    // A real value is transformed...
    assert(
      r.renderNodeById("c", Map("sensor.t" -> st("sensor.t", "21.46")))
        .get
        .contains("<span>21.5</span>")
    )
    // ...but "unavailable" never enters JSONata (which would error) — shown raw.
    assert(
      r.renderNodeById("c", Map("sensor.t" -> st("sensor.t", "unavailable")))
        .get
        .contains("<span>unavailable</span>")
    )
  }

  test("an identity (action) slot resolves from the entity id with no state") {
    val expr =
      """($a := $lookup({"scene": "scene/turn_on"}, $domain); """ +
        """$a ? $a : "homeassistant/toggle")"""
    def actionNode(entity: String): LayoutNode =
      LayoutNode.Component(
        card = "act",
        entities = List(entity),
        slots = Map("action" -> SlotSource(entity, transform = expr))
      )
    // No state at all: the action still resolves from the entity's domain.
    assert(
      renderer(actionNode("scene.movie"))
        .renderNodeById("c", Map.empty)
        .get
        .contains("""href="scene/turn_on""""),
      clue = "scene domain -> scene/turn_on"
    )
    assert(
      renderer(actionNode("light.x"))
        .renderNodeById("c", Map.empty)
        .get
        .contains("""href="homeassistant/toggle""""),
      clue = "other domain -> homeassistant/toggle"
    )
  }

  test("missing entity renders empty slots rather than throwing") {
    val html = renderer(card).renderNodeById("c", Map.empty).get
    assertEquals(
      html,
      """<div class="fh-cell" id="c"><div><span></span> </div></div>"""
    )
  }

  test(
    "container templates splice children; entity-less nodes are not wrapped"
  ) {
    val layout = col(row(LayoutNode.Component("btn", Map("label" -> "Go"))))
    val r = renderer(layout)
    val page = r.renderPage(Map.empty)
    // no entities anywhere -> no morph wrappers, no ids in the markup
    assertEquals(
      page,
      """<main class="container" id="dashboard"><div class="fh-col"><div class="fh-row"><button>Go</button></div></div></main>"""
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
        "bri" -> SlotSource(
          "light.x",
          transform = "$attr.brightness",
          default = Some("0")
        )
      )
    )
    val r = renderer(g)
    val wrap =
      (inner: String) => s"""<div class="fh-cell" id="c">$inner</div>"""
    assertEquals(r.renderNodeById("c", Map.empty).get, wrap("""<i>0</i>"""))
    val off = Map("light.x" -> st("light.x", "off", "brightness" -> Json.Null))
    assertEquals(r.renderNodeById("c", off).get, wrap("""<i>0</i>"""))
    val on =
      Map("light.x" -> st("light.x", "on", "brightness" -> Json.fromInt(200)))
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
      "light.a" -> st(
        "light.a",
        "on",
        "battery" -> Json.fromInt(10),
        "friendly_name" -> Json.fromString("Lamp")
      ),
      "sensor.b" -> st("sensor.b", "hot", "battery" -> Json.fromInt(5)),
      "sensor.c" -> st("sensor.c", "cold", "battery" -> Json.fromInt(50))
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

  test("theme tokens + styles are injected as a <style> block") {
    val d = Dashboard(
      cards,
      col(),
      theme = Theme(
        tokens = Map("primary-color" -> "#bada55", "accent-color" -> "#000"),
        styles = ".card{color:red}"
      )
    )
    val page = Renderer.create(d).renderPage(Map.empty)
    // sorted token vars, then the theme's inline styles; no dark overrides
    assert(
      page.startsWith(
        """<main class="container" id="dashboard"><style>:root{color-scheme:light dark;--accent-color:#000;--primary-color:#bada55;}.card{color:red}</style>"""
      ),
      clue = page
    )
    assert(!page.contains("prefers-color-scheme"), clue = page)
  }

  test("dark token overrides go under prefers-color-scheme: dark") {
    val d = Dashboard(
      cards,
      col(),
      theme = Theme(
        tokens = Map("primary-text-color" -> "#212121"),
        tokensDark = Map("primary-text-color" -> "#e1e1e1")
      )
    )
    val page = Renderer.create(d).renderPage(Map.empty)
    assert(
      page.contains(
        "@media (prefers-color-scheme:dark){:root{--primary-text-color:#e1e1e1;}}"
      ),
      clue = page
    )
  }

  test("no theme -> no :root style block") {
    val d = Dashboard(cards, col())
    val page = Renderer.create(d).renderPage(Map.empty)
    assert(!page.contains("<style>"), clue = page)
    assertEquals(Renderer.create(d).stylesheets, Nil)
  }

  test("Predicate evaluation: comparisons and boolean combinators") {
    val s = st("sensor.x", "18", "battery" -> Json.fromInt(15))
    assert(
      Renderer.matches(
        Predicate.Cmp("domain", Op.Eq, Json.fromString("sensor")),
        s
      )
    )
    assert(
      !Renderer.matches(
        Predicate.Cmp("domain", Op.Eq, Json.fromString("light")),
        s
      )
    )
    assert(
      Renderer.matches(
        Predicate.Cmp("attr:battery", Op.Lt, Json.fromInt(20)),
        s
      )
    )
    assert(
      !Renderer.matches(
        Predicate.Cmp("attr:battery", Op.Gte, Json.fromInt(20)),
        s
      )
    )
    assert(
      Renderer.matches(
        Predicate.Cmp("state", Op.Lte, Json.fromInt(18)),
        s
      )
    )

    val both = Predicate.And(
      List(
        Predicate.Cmp("domain", Op.Eq, Json.fromString("sensor")),
        Predicate.Cmp("attr:battery", Op.Lt, Json.fromInt(20))
      )
    )
    assert(Renderer.matches(both, s))
    // A light-domain entity fails the `domain == sensor` arm, so `Not(both)`.
    val sLight = st("light.x", "18", "battery" -> Json.fromInt(15))
    assert(Renderer.matches(Predicate.Not(both), sLight))
    assert(
      Renderer.matches(
        Predicate.Or(
          List(Predicate.Cmp("domain", Op.Eq, Json.fromString("light")), both)
        ),
        s
      )
    )
  }
}
