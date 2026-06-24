package fh.view.runtime

import fh.view.model.{
  CardDef,
  Dashboard,
  DynamicCase,
  LayoutNode,
  Op,
  Predicate,
  SlotSource,
  Surface,
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
    ),
    "tabs" -> CardDef(
      """<div class="tabs"><div class="tabbar">{{#children}}{{{html}}}{{/children}}</div><div class="tab-panel" id="{{mount}}">{{{panel}}}</div></div>""",
      params = List("sig", "initial", "mount")
    )
  )

  // A tabs container as the hoist produces it: a `tabs` component (the bar
  // buttons as children, `initial` naming the default panel) plus one grouped,
  // inline-mounted surface per tab.
  private def tabsDashboard: Dashboard = {
    def panel(name: String): LayoutNode.Component =
      LayoutNode.Component(
        "card",
        slots = Map("state" -> SlotSource(Some(s"sensor.$name")))
      )
    Dashboard(
      cards,
      LayoutNode.Component(
        "tabs",
        params =
          Map("sig" -> "tab_c", "mount" -> "c_panel", "initial" -> "c_0"),
        children = List(
          LayoutNode.Component("btn", Map("label" -> "A")),
          LayoutNode.Component("btn", Map("label" -> "B"))
        )
      ),
      surfaces = Map(
        "c_0" -> Surface(panel("a"), Some("c_panel"), Some("c_panel")),
        "c_1" -> Surface(panel("b"), Some("c_panel"), Some("c_panel"))
      )
    )
  }

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
    slots = Map(
      "state" -> SlotSource(Some("sensor.t")),
      "unit" -> SlotSource(Some("sensor.t"), "$attr.unit_of_measurement")
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
    // No explicit bypassUnavailable — bypassing is the DEFAULT (true).
    val node = LayoutNode.Component(
      card = "card",
      slots = Map(
        "state" -> SlotSource(
          Some("sensor.t"),
          transform = "$round($number($state), 1)"
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

  test("bypassUnavailable=false runs the transform even when unavailable") {
    // A label/action/slider-position opts out so its transform still runs (a
    // label keeps the name, an action stays resolvable) instead of collapsing to
    // the literal "unavailable".
    val node = LayoutNode.Component(
      card = "card",
      slots = Map(
        "state" -> SlotSource(
          Some("sensor.t"),
          transform = "$state & \"!\"",
          bypassUnavailable = false
        )
      )
    )
    val r = renderer(node)
    assert(
      r.renderNodeById("c", Map("sensor.t" -> st("sensor.t", "unavailable")))
        .get
        .contains("<span>unavailable!</span>"),
      clue = "transform should run, not be bypassed"
    )
  }

  test("an identity (action) slot resolves from the entity id with no state") {
    val expr =
      """($a := $lookup({"scene": "scene/turn_on"}, $domain); """ +
        """$a ? $a : "homeassistant/toggle")"""
    def actionNode(entity: String): LayoutNode =
      LayoutNode.Component(
        card = "act",
        slots = Map("action" -> SlotSource(Some(entity), transform = expr))
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
    // no entities anywhere -> no morph wrappers, no ids in the markup; the
    // stable shell carries the `#popups` overlay mount after the body.
    assertEquals(
      page,
      """<main class="container" id="dashboard"><div class="fh-col"><div class="fh-row"><button>Go</button></div></div></main><div id="popups"></div>"""
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
      slots = Map(
        "bri" -> SlotSource(
          Some("light.x"),
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
          "btn",
          // label is a slot now (no entityId → inherits the matched entity, which
          // the renderer injects as the entity_id param), so it resolves to the
          // matched entity's live friendly_name.
          slots = Map(
            "label" -> SlotSource(transform = "$attr.friendly_name")
          )
        ),
        DynamicCase(
          Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__")),
          "card",
          slots = Map("state" -> SlotSource())
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

  test(
    "a constant slot (no entityId) resolves its literal against empty state"
  ) {
    val node = LayoutNode.Component(
      card = "btn",
      slots = Map("label" -> SlotSource(transform = "\"Hi\""))
    )
    val html = renderer(node).renderNodeById("c", Map.empty).get
    assert(html.contains("<button>Hi</button>"), clue = html)
  }

  test("a dynamic case's constant label slot is NOT rebound to the match") {
    // A per-case literal label (entityId = None) must survive: the matched
    // entity's friendly_name does not override an author-fixed label.
    val dyn = LayoutNode.Dynamic(
      query = None,
      cases = List(
        DynamicCase(
          Predicate.Cmp("domain", Op.Eq, Json.fromString("light")),
          "btn",
          slots = Map("label" -> SlotSource(transform = "\"Fixed\""))
        )
      )
    )
    val states =
      Map(
        "light.a" -> st(
          "light.a",
          "on",
          "friendly_name" -> Json.fromString("Lamp")
        )
      )
    val html = renderer(dyn).renderNodeById("c", states).get
    assert(html.contains("<button>Fixed</button>"), clue = html)
    assert(!html.contains("Lamp"), clue = html)
  }

  test("EntityState.javaAttributes is converted once and reused") {
    val es =
      EntityState("light.x", "on", Map("brightness" -> Json.fromInt(200)))
    // Same instance on every access (cached per state version), and numbers stay
    // numeric for `$attr.brightness` arithmetic.
    assert(
      es.javaAttributes eq es.javaAttributes,
      clue = "identity-stable cache"
    )
    assertEquals(es.javaAttributes.get("brightness"), 200L)
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

  test(
    "a surface renders in a dialog with namespaced ids + a close control, off the main update set"
  ) {
    val d = Dashboard(
      cards,
      col(),
      surfaces = Map(
        "detail" -> Surface(
          LayoutNode.Component(
            "card",
            slots = Map("state" -> SlotSource(Some("sensor.t")))
          )
        )
      )
    )
    val r = Renderer.create(d)
    val states = Map("sensor.t" -> EntityState("sensor.t", "42", Map.empty))
    val html = r.renderSurface("detail", states).get
    assert(
      html.startsWith("""<dialog id="s_detail" open class="popup">"""),
      clue = html
    )
    // wrapper-provided close, wired to the (backend-known) surface id
    assert(html.contains("/sse/surface/close/detail"), clue = html)
    // inner node ids are surface-namespaced and individually re-renderable
    assert(html.contains("""id="s_detail__c""""), clue = html)
    assert(
      r.renderNodeById("s_detail__c", states).get.contains("<span>42</span>")
    )
    // the surface's entity drives ONLY the surface index, not the main page
    assert(
      r.componentsFor("sensor.t").isEmpty,
      clue = r.componentsFor("sensor.t")
    )
    assertEquals(
      r.surfaceComponentsFor("detail", "sensor.t"),
      Set("s_detail__c")
    )
    // unknown surface -> None
    assertEquals(r.renderSurface("nope", states), None)
  }

  test(
    "tabs: default panel is baked inline, an inline surface renders without dialog chrome"
  ) {
    val r = tabsDashboard
    val rr = Renderer.create(r)
    val states = Map(
      "sensor.a" -> EntityState("sensor.a", "AA", Map.empty),
      "sensor.b" -> EntityState("sensor.b", "BB", Map.empty)
    )
    // The default (initial) tab is registered as the only default-open surface.
    assertEquals(rr.defaultOpenSurfaces, Set("c_0"))

    // renderBody bakes the first tab's content into the panel mount, with the
    // surface-namespaced ids it would carry after a later switch-back.
    val body = rr.renderBody(states)
    assert(
      body.contains("""<div class="tab-panel" id="c_panel">"""),
      clue = body
    )
    assert(body.contains("""id="s_c_0__c""""), clue = body)
    assert(body.contains("<span>AA</span>"), clue = body)
    // the second tab is NOT baked in
    assert(!body.contains("<span>BB</span>"), clue = body)

    // An inline-mounted surface renders as a plain div (no <dialog>, no ✕).
    val panelB = rr.renderSurface("c_1", states).get
    assert(
      panelB.startsWith("""<div id="s_c_1" class="tab-panel-content">"""),
      clue = panelB
    )
    assert(!panelB.contains("<dialog"), clue = panelB)
    assert(!panelB.contains("surface/close"), clue = panelB)
    assert(panelB.contains("<span>BB</span>"), clue = panelB)

    // each tab's entity drives only its own surface index
    assertEquals(rr.surfaceComponentsFor("c_0", "sensor.a"), Set("s_c_0__c"))
    assertEquals(rr.surfaceComponentsFor("c_1", "sensor.b"), Set("s_c_1__c"))
  }

  test(
    "renderBody is the shell-less body (what a navigate swap inner-patches)"
  ) {
    val r =
      renderer(col(row(LayoutNode.Component("btn", Map("label" -> "Go")))))
    val body = r.renderBody(Map.empty)
    assert(!body.contains("""id="dashboard""""), clue = body)
    assert(!body.contains("""id="popups""""), clue = body)
    assertEquals(
      body,
      """<div class="fh-col"><div class="fh-row"><button>Go</button></div></div>"""
    )
  }
}
