package fh.view.runtime

import fh.view.model.{
  Activation,
  CardDef,
  Cell,
  Dashboard,
  DynamicCase,
  LayoutNode,
  Op,
  Predicate,
  Quantifier,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.DashboardBuilders.{col, lit, row, st}
import io.circe.Json

class RendererSuite extends munit.FunSuite {

  // Card templates are pure content; the backend wraps EVERY component in the
  // id'd `.fh-cell` morph target (unless the card opts out via
  // `wrapAsCell = false`).
  private val cards = Map(
    "card" -> CardDef(
      """<div><span>{{state}}</span> {{unit}}</div>""",
      slots = List("state")
    ),
    "btn" -> CardDef("""<button>{{label}}</button>""", slots = List("label")),
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
    // Tabs container: tabbar row of buttons (children) + panel host (baked via {{{panel}}}).
    // `data-signals` seeds the active-tab signal to the baked tab index ({{bakeIndex}}).
    "tabs" -> CardDef(
      """<div class="fh-col tabs"><div class="fh-row tabbar">{{#children}}{{{html}}}{{/children}}</div><div id="{{id}}_panel" class="tab-panel" data-signals="{ tab_{{id}}: {{bakeIndex}} }">{{{panel}}}</div></div>"""
    ),
    // Like `tabs`, but the bake-owning component ALSO binds a live entity via a
    // `{{title}}` slot — so it is morph-wrapped and re-rendered on that entity's
    // state change. Exercises that a live node patch re-bakes the SELECTED tab.
    "tabsLive" -> CardDef(
      """<div class="tabs"><span>{{title}}</span><div id="{{id}}_panel" data-signals="{ tab_{{id}}: {{bakeIndex}} }">{{{panel}}}</div></div>""",
      slots = List("title")
    )
  )

  // A tabs group as `c.tabs` + the hoist produce it: a `tabs` component whose
  // children are the tab buttons, and whose panel host (`{{id}}_panel`) is
  // filled via `{{{panel}}}` baked from the first default-open surface. The
  // surfaces carry `bakeInto:"c"`, `bakeAs:"panel"` (so `hostId` derives to
  // `c_panel` = idBase + '_panel', the hoist invariant) — every surface is
  // chrome-less.
  private def tabsDashboard: Dashboard = {
    def panel(name: String): LayoutNode.Component =
      LayoutNode.Component(
        "card",
        slots = Map("state" -> SlotSource(Some(s"sensor.$name")))
      )
    Dashboard(
      cards,
      // The `tabs` card: children are the tab buttons; the panel host is in the
      // template at `{{id}}_panel`; the default tab is injected via `{{{panel}}}`.
      LayoutNode.Component(
        "tabs",
        children = List(
          LayoutNode.Component("btn", Map("label" -> lit("A"))),
          LayoutNode.Component("btn", Map("label" -> lit("B")))
        )
      ),
      surfaces = Map(
        // c_t0 is the default-open panel: baked into the tabs component (id="c",
        // bakeInto="c", bakeAs="panel") + seeded open on connect.
        "c_t0" -> Surface(
          panel("a"),
          bakeInto = Some("c"),
          bakeAs = Some("panel"),
          bakeIndex = Some(0),
          activation = Activation.User(defaultOpen = true)
        ),
        "c_t1" -> Surface(
          panel("b"),
          bakeInto = Some("c"),
          bakeAs = Some("panel"),
          bakeIndex = Some(1)
        )
      )
    )
  }

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

  test(
    "a reactive:false slot is resolved once and memoized; a reactive:true slot re-resolves"
  ) {
    // `reactive = false` promises the value is identity-only, so the renderer
    // resolves it ONCE per (entity, transform) and reuses it — this is what
    // keeps the dynamic render path cheap (action/domain-config slots become a
    // cache lookup, not a JSONata eval, on every re-render). We expose the memo
    // with a state-reading transform (a deliberate misuse): its value freezes
    // at the first render and ignores a later state change. A `reactive = true`
    // slot, by contrast, re-resolves every render.
    def node(reactive: Boolean): LayoutNode =
      LayoutNode.Component(
        card = "act",
        slots = Map(
          "action" -> SlotSource(
            Some("sensor.t"),
            transform = "$state",
            reactive = reactive
          )
        )
      )

    val frozen = renderer(node(false))
    val a =
      frozen.renderNodeById("c", Map("sensor.t" -> st("sensor.t", "one"))).get
    val b =
      frozen.renderNodeById("c", Map("sensor.t" -> st("sensor.t", "two"))).get
    assert(a.contains("""href="one""""), clue = a)
    assertEquals(b, a) // memoized: the changed state is ignored

    val live = renderer(node(true))
    val c1 =
      live.renderNodeById("c", Map("sensor.t" -> st("sensor.t", "one"))).get
    val c2 =
      live.renderNodeById("c", Map("sensor.t" -> st("sensor.t", "two"))).get
    assert(c1.contains("""href="one""""), clue = c1)
    assert(c2.contains("""href="two""""), clue = c2) // re-resolved
  }

  test("missing entity renders empty slots rather than throwing") {
    val html = renderer(card).renderNodeById("c", Map.empty).get
    assertEquals(
      html,
      """<div class="fh-cell" id="c"><div><span></span> </div></div>"""
    )
  }

  test(
    "container templates splice children; EVERY node (containers and entity-less leaves included) is wrapped in its id'd fh-cell"
  ) {
    val layout =
      col(row(LayoutNode.Component("btn", Map("label" -> lit("Go")))))
    val r = renderer(layout)
    val page = r.renderPage(Map.empty)
    // every node — the root container, the nested container, and the static
    // leaf — gets the backend-owned `.fh-cell` morph wrapper with its path id;
    // with no theme.chrome, renderPage falls back to the minimal `#dashboard`
    // frame (no popup host — a popup-less dashboard ships no theme).
    assertEquals(
      page,
      """<main class="container" id="dashboard"><div class="fh-cell" id="c"><div class="fh-col"><div class="fh-cell" id="c_0"><div class="fh-row"><div class="fh-cell" id="c_0_0"><button>Go</button></div></div></div></div></div></main>"""
    )
    // containers are addressable and re-render (wrapped) by id
    assertEquals(
      r.renderNodeById("c_0", Map.empty).get,
      """<div class="fh-cell" id="c_0"><div class="fh-row"><div class="fh-cell" id="c_0_0"><button>Go</button></div></div></div>"""
    )
  }

  test(
    "a wrapAsCell=false card renders bare: no fh-cell wrapper, no injected id wrapper"
  ) {
    // The card opts out of the backend-owned wrapper (its root must stay a
    // direct child of a framework-structural parent). It may still read
    // `{{id}}` internally, but the renderer injects no wrapper element. Such
    // a card may only carry literal / identity slots — a live-entity slot on
    // an unwrapped node is a validate error (see the rejection test below).
    val bareCards = cards + ("naked" -> CardDef(
      """<a class="tab" data-tab="{{id}}"><span>{{state}}</span></a>""",
      slots = List("state"),
      wrapAsCell = false
    ))
    val d = Dashboard(
      bareCards,
      LayoutNode.Component("naked", slots = Map("state" -> lit("42")))
    )
    assertEquals(d.validate(), Nil)
    val r = Renderer.create(d)
    assertEquals(
      r.renderNodeById("c", Map.empty).get,
      """<a class="tab" data-tab="c"><span>42</span></a>"""
    )
  }

  test(
    "validate rejects the wrapper-dependent shapes on a wrapAsCell=false card"
  ) {
    // Everything that rides on the `.fh-cell` wrapper is unusable on a card
    // that opts out of it — and silently so at render time, which is why each
    // shape is a loud build error instead.
    val bareCards = cards + ("naked" -> CardDef(
      "<a>{{state}}</a>",
      slots = List("state"),
      wrapAsCell = false
    ))
    // A live-entity slot: the pushed morphs could never match an element.
    val live = Dashboard(
      bareCards,
      LayoutNode.Component(
        "naked",
        slots = Map("state" -> SlotSource(Some("sensor.t")))
      )
    )
    assert(
      live.validate().exists(_.contains("binds live entities")),
      clue = live.validate()
    )
    // Cell params: there is no wrapper to carry the classes.
    val sized = Dashboard(
      bareCards,
      LayoutNode.Component(
        "naked",
        slots = Map("state" -> lit("42")),
        cell = Some(Cell(classes = List("fh-cols-3")))
      )
    )
    assert(
      sized.validate().exists(_.contains("carries cell params")),
      clue = sized.validate()
    )
    // A dynamic case: every member is a wrapped per-entity patch target.
    val dynCase = Dashboard(
      bareCards,
      LayoutNode.Dynamic(
        query = None,
        cases = List(
          DynamicCase(
            Predicate.Cmp("domain", Op.Eq, Json.fromString("light")),
            "naked",
            slots = Map("state" -> lit("x"))
          )
        )
      )
    )
    assert(
      dynCase.validate().exists(_.contains("cannot be a dynamic-group case")),
      clue = dynCase.validate()
    )
  }

  test("authored cell classes ride on every wrapper kind") {
    // Static component wrapper, dynamic group root, and per-entity case
    // members: the node-level `cell.classes` (the fh- layout contract) are
    // appended to the backend-owned wrapper's class attribute.
    val sized = LayoutNode.Component(
      "btn",
      Map("label" -> lit("Go")),
      cell = Some(Cell(classes = List("fh-cols-3", "hero")))
    )
    assertEquals(
      renderer(sized).renderNodeById("c", Map.empty).get,
      """<div class="fh-cell fh-cols-3 hero" id="c"><button>Go</button></div>"""
    )

    val dyn = LayoutNode.Dynamic(
      query = Some(Predicate.Cmp("domain", Op.Eq, Json.fromString("light"))),
      cases = List(
        DynamicCase(
          Predicate.Cmp("domain", Op.Eq, Json.fromString("light")),
          "btn",
          slots = Map("label" -> lit("L")),
          cell = Some(Cell(classes = List("fh-cols-4")))
        )
      ),
      cell = Some(Cell(classes = List("fh-cols-full")))
    )
    val html = renderer(dyn)
      .renderNodeById("c", Map("light.a" -> st("light.a", "on")))
      .get
    assertEquals(
      html,
      """<div class="fh-cell fh-group fh-cols-full" id="c">""" +
        """<div class="fh-cell fh-cols-4" id="c_light_a"><button>L</button></div></div>"""
    )
    // The per-member in-place path emits the identical wrapper classes.
    assertEquals(
      renderer(dyn)
        .renderDynamicChild(
          "c",
          "light.a",
          Map("light.a" -> st("light.a", "on"))
        )
        .get,
      """<div class="fh-cell fh-cols-4" id="c_light_a"><button>L</button></div>"""
    )
  }

  test(
    "renderPage executes the theme's chrome around renderBody, popup host included"
  ) {
    val d = Dashboard(
      cards,
      col(LayoutNode.Component("btn", Map("label" -> lit("Go")))),
      theme = Theme(chrome =
        """<main id="dashboard">{{{body}}}</main><dialog id="popups"><div id="popups-body"></div></dialog>"""
      )
    )
    val page = Renderer.create(d).renderPage(Map.empty)
    assertEquals(
      page,
      """<main id="dashboard"><div class="fh-cell" id="c"><div class="fh-col"><div class="fh-cell" id="c_0"><button>Go</button></div></div></div></main><dialog id="popups"><div id="popups-body"></div></dialog>"""
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
    // dynamic as layout root -> the group's own id'd container "c" is the outer
    // morph target (itself a cell, plus `fh-group`); each child is ALSO wrapped
    // in its own id'd `fh-cell` (the per-entity patch target)
    // `<groupId>_<sanitized entity>`.
    val html = r.renderNodeById("c", states).get
    assert(
      html.startsWith("""<div class="fh-cell fh-group" id="c">"""),
      clue = html
    )
    // light.a dispatched to the btn case, sensor.b to the card case, each in its
    // own per-entity wrapper.
    assert(
      html.contains(
        """<div class="fh-cell" id="c_light_a"><button>Lamp</button></div>"""
      ),
      clue = html
    )
    assert(
      html.contains(
        """<div class="fh-cell" id="c_sensor_b"><div><span>hot</span>"""
      ),
      clue = html
    )
    // sensor.c excluded by the membership query (battery 50)
    assert(!html.contains("cold"), clue = html)
    // the group is indexed under its own id "c" and re-renders on a change that
    // touches its query.
    assertEquals(
      r.affectedDynamicIds(
        StateChange("light.a", None, states("light.a"))
      ),
      List("c")
    )
  }

  test(
    "affectedDynamicIds includes a group only when the change touches its query"
  ) {
    def group(query: Option[Predicate]): LayoutNode =
      LayoutNode.Dynamic(
        query = query,
        cases = List(
          DynamicCase(
            Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__")),
            "card",
            slots = Map("state" -> SlotSource())
          )
        )
      )
    val r = renderer(
      group(Some(Predicate.Cmp("attr:battery", Op.Lt, Json.fromInt(20))))
    )
    def low(id: String) = st(id, "x", "battery" -> Json.fromInt(5)) // matches
    def high(id: String) =
      st(id, "x", "battery" -> Json.fromInt(50)) // no match

    // in-place update of a member (prev ∧ cur), an add (¬prev ∧ cur), and a
    // remove (prev ∧ ¬cur) all re-render the group; a newly-seen match too.
    assertEquals(
      r.affectedDynamicIds(
        StateChange("sensor.b", Some(low("sensor.b")), low("sensor.b"))
      ),
      List("c")
    )
    assertEquals(
      r.affectedDynamicIds(
        StateChange("sensor.b", Some(high("sensor.b")), low("sensor.b"))
      ),
      List("c")
    )
    assertEquals(
      r.affectedDynamicIds(
        StateChange("sensor.b", Some(low("sensor.b")), high("sensor.b"))
      ),
      List("c")
    )
    assertEquals(
      r.affectedDynamicIds(StateChange("sensor.b", None, low("sensor.b"))),
      List("c")
    )
    // An entity that matches neither before nor after leaves the group's HTML
    // unchanged, so it is skipped — the whole point of the filter.
    assertEquals(
      r.affectedDynamicIds(
        StateChange("sensor.z", Some(high("sensor.z")), high("sensor.z"))
      ),
      Nil
    )
    // A query-less group matches everything, so any change affects it.
    val all = renderer(group(None))
    assertEquals(
      all.affectedDynamicIds(
        StateChange("sensor.z", Some(high("sensor.z")), high("sensor.z"))
      ),
      List("c")
    )
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
    "renderSurface returns bare content — no per-surface chrome (Surface's final 5 fields)"
  ) {
    // Every surface is chrome-less: the frame/host a surface swaps into lives
    // in theme.chrome (the inlined <dialog> for a popup, the tabs card's panel
    // host for a tab), not a per-surface wrapper. renderSurface just renders content,
    // namespaced under the surface-scoped id prefix.
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
    assert(!html.contains("<dialog"), clue = html)
    assert(!html.contains("surface/close"), clue = html)
    assert(html.contains("<span>42</span>"), clue = html)
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
    "tabs: default panel is baked into the tabs card; a panel renders without dialog chrome"
  ) {
    val rr = Renderer.create(tabsDashboard)
    val states = Map(
      "sensor.a" -> EntityState("sensor.a", "AA", Map.empty),
      "sensor.b" -> EntityState("sensor.b", "BB", Map.empty)
    )
    // The first tab is registered as the only default-open surface.
    assertEquals(rr.selectedSurfaces(), Set("c_t0"))

    // renderBody renders the `tabs` component (id "c") whose template contains a
    // panel host `<div id="c_panel" class="tab-panel" data-signals="{ tab_c: 0 }">`.
    // The first tab's content is baked in via {{{panel}}} (surface-namespaced ids,
    // matching a later switch-back — byte-identical HTML).
    val body = rr.renderBody(states)
    assert(
      body.contains(
        """<div id="c_panel" class="tab-panel" data-signals="{ tab_c: 0 }">"""
      ),
      clue = body
    )
    assert(body.contains("""id="s_c_t0__c""""), clue = body)
    assert(body.contains("<span>AA</span>"), clue = body)
    // the second tab is NOT baked in
    assert(!body.contains("<span>BB</span>"), clue = body)

    // An inline-mounted surface renders bare — no chrome wrapper, no <dialog>, no ✕.
    val panelB = rr.renderSurface("c_t1", states).get
    assert(
      panelB.startsWith("""<div class="fh-cell" id="s_c_t1__c">"""),
      clue = panelB
    )
    assert(!panelB.contains("<dialog"), clue = panelB)
    assert(!panelB.contains("surface/close"), clue = panelB)
    assert(panelB.contains("<span>BB</span>"), clue = panelB)

    // each tab's entity drives only its own surface index
    assertEquals(rr.surfaceComponentsFor("c_t0", "sensor.a"), Set("s_c_t0__c"))
    assertEquals(rr.surfaceComponentsFor("c_t1", "sensor.b"), Set("s_c_t1__c"))
  }

  test(
    "selectedSurfaces picks the uiState-indexed member; empty map == the old default"
  ) {
    val rr = Renderer.create(tabsDashboard)
    // A cookie index selects that member of the bake group...
    assertEquals(rr.selectedSurfaces(Map("c" -> "1")), Set("c_t1"))
    // ...and no cookie picks index 0 (parity with the old defaultOpenSurfaces).
    assertEquals(rr.selectedSurfaces(Map.empty), Set("c_t0"))
    assertEquals(rr.selectedSurfaces(), Set("c_t0"))
  }

  test(
    "render of a tabs component with a uiState index bakes that tab + seeds its signal"
  ) {
    val rr = Renderer.create(tabsDashboard)
    val states = Map(
      "sensor.a" -> EntityState("sensor.a", "AA", Map.empty),
      "sensor.b" -> EntityState("sensor.b", "BB", Map.empty)
    )
    // uiState maps the tabs component id ("c") to the active index "1".
    val body = rr.renderBody(states, Map("c" -> "1"))
    // the panel host seeds `tab_c: 1` (from the injected bakeIndex)...
    assert(
      body.contains(
        """<div id="c_panel" class="tab-panel" data-signals="{ tab_c: 1 }">"""
      ),
      clue = body
    )
    // ...and the SECOND tab's content is baked (surface c_t1), not the first.
    assert(body.contains("""id="s_c_t1__c""""), clue = body)
    assert(body.contains("<span>BB</span>"), clue = body)
    assert(!body.contains("<span>AA</span>"), clue = body)
  }

  test("resolveActive parses, clamps, and warns on an off cookie value") {
    val rr = Renderer.create(tabsDashboard)
    // out of range and unparseable both fall back to index 0 AND yield a warning
    val outOfRange = rr.resolveActive("c", Map("c" -> "99"))
    assertEquals(outOfRange._1, 0)
    assert(outOfRange._2.isDefined, clue = outOfRange)
    val unparseable = rr.resolveActive("c", Map("c" -> "abc"))
    assertEquals(unparseable._1, 0)
    assert(unparseable._2.isDefined, clue = unparseable)
    // a valid index and an absent key both select without a warning
    assertEquals(rr.resolveActive("c", Map("c" -> "1")), (1, None))
    assertEquals(rr.resolveActive("c", Map.empty), (0, None))
    // uiStateAnomalies surfaces exactly the malformed entries
    assertEquals(rr.uiStateAnomalies(Map("c" -> "1")), Nil)
    assertEquals(rr.uiStateAnomalies(Map.empty), Nil)
    assertEquals(rr.uiStateAnomalies(Map("c" -> "99")).size, 1)
  }

  test(
    "renderBody is the shell-less body (what a navigate swap inner-patches)"
  ) {
    val r =
      renderer(col(row(LayoutNode.Component("btn", Map("label" -> lit("Go"))))))
    val body = r.renderBody(Map.empty)
    assert(!body.contains("""id="dashboard""""), clue = body)
    assert(!body.contains("""id="popups""""), clue = body)
    assertEquals(
      body,
      """<div class="fh-cell" id="c"><div class="fh-col"><div class="fh-cell" id="c_0"><div class="fh-row"><div class="fh-cell" id="c_0_0"><button>Go</button></div></div></div></div></div>"""
    )
  }

  test(
    "validate: a non-empty theme.chrome lacking id=\"dashboard\" is a hard error"
  ) {
    val bad = Dashboard(
      cards,
      col(),
      theme = Theme(chrome = """<main>{{{body}}}</main>""")
    )
    assert(
      bad.validate().exists(_.contains("theme.chrome must contain")),
      clue = bad.validate()
    )

    // The contract-satisfying chrome (carries id="dashboard") produces no error.
    val ok = Dashboard(
      cards,
      col(),
      theme = Theme(chrome = """<main id="dashboard">{{{body}}}</main>""")
    )
    assertEquals(ok.validate(), Nil)

    // Empty chrome (the fallback) is never checked.
    assertEquals(Dashboard(cards, col()).validate(), Nil)
  }

  test(
    "Surface.hostId derives <bakeInto>_<bakeAs> for a baked surface, the popup overlay otherwise"
  ) {
    val baked = Surface(
      col(),
      bakeInto = Some("c_1"),
      bakeAs = Some("panel")
    )
    assertEquals(baked.hostId, "c_1_panel")

    val unbaked = Surface(col())
    assertEquals(unbaked.hostId, Dashboard.PopupHostId)
  }

  test(
    "renderNodeById re-bakes the uiState-selected tab of a live bake-owning node"
  ) {
    // A `tabsLive` component (id "c") owns a bake group AND binds a live entity
    // (`sensor.title`). On a live SSE patch the node is re-rendered by id — it
    // must bake the SESSION's cookie-selected tab, not the default one.
    def panel(name: String): LayoutNode.Component =
      LayoutNode.Component(
        "card",
        slots = Map("state" -> SlotSource(Some(s"sensor.$name")))
      )
    val d = Dashboard(
      cards,
      LayoutNode.Component(
        "tabsLive",
        slots = Map("title" -> SlotSource(Some("sensor.title"), "$state"))
      ),
      surfaces = Map(
        "c_t0" -> Surface(
          panel("a"),
          bakeInto = Some("c"),
          bakeAs = Some("panel"),
          bakeIndex = Some(0),
          activation = Activation.User(defaultOpen = true)
        ),
        "c_t1" -> Surface(
          panel("b"),
          bakeInto = Some("c"),
          bakeAs = Some("panel"),
          bakeIndex = Some(1)
        )
      )
    )
    val rr = Renderer.create(d)
    val states = Map(
      "sensor.title" -> st("sensor.title", "Live"),
      "sensor.a" -> st("sensor.a", "AA"),
      "sensor.b" -> st("sensor.b", "BB")
    )
    // The live entity binds "c" so the node is morph-wrapped and re-renderable.
    assertEquals(rr.componentsFor("sensor.title"), Set("c"))

    // Default (no cookie) bakes the FIRST tab (index 0 → sensor.a → AA).
    val dflt = rr.renderNodeById("c", states).get
    assert(dflt.startsWith("""<div class="fh-cell" id="c">"""), clue = dflt)
    assert(dflt.contains("tab_c: 0"), clue = dflt)
    assert(dflt.contains("<span>AA</span>"), clue = dflt)
    assert(!dflt.contains("<span>BB</span>"), clue = dflt)

    // The cookie selects tab 1 → the SECOND tab is baked (sensor.b → BB), and
    // the panel signal is seeded to 1. This is the bug the change fixes: without
    // threading uiState the live patch would re-bake the default tab.
    val sel = rr.renderNodeById("c", states, uiState = Map("c" -> "1")).get
    assert(sel.contains("tab_c: 1"), clue = sel)
    assert(sel.contains("<span>BB</span>"), clue = sel)
    assert(!sel.contains("<span>AA</span>"), clue = sel)
  }

  // ---------------------------------------------------------------------------
  // Per-entity dynamic-group patches (Tier 1 + Tier 2)
  // ---------------------------------------------------------------------------

  // A dynamic group (as the layout root, so group id "c") of on-state entities.
  private val onGroup = LayoutNode.Dynamic(
    query = Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
    cases = List(
      DynamicCase(
        Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__")),
        "card",
        slots = Map("state" -> SlotSource())
      )
    )
  )

  test("dynamicChildId slugs the entity id under the group id") {
    val r = renderer(onGroup)
    assertEquals(r.dynamicChildId("c", "light.a"), "c_light_a")
    assertEquals(r.dynamicChildId("c", "light-b.x"), "c_light_b_x")
  }

  test("dynamicMembers: query + case matches, in DOM (entity-id) order") {
    val r = renderer(onGroup)
    val states = Map(
      "light.b" -> st("light.b", "on"),
      "light.a" -> st("light.a", "on"),
      "light.c" -> st("light.c", "off") // fails the query
    )
    assertEquals(r.dynamicMembers("c", states), List("light.a", "light.b"))
    // unknown / non-dynamic id -> no members
    assertEquals(r.dynamicMembers("zzz", states), Nil)
  }

  test(
    "renderDynamicChild renders ONE wrapped card, or None for a non-member"
  ) {
    val r = renderer(onGroup)
    val states = Map(
      "light.a" -> st("light.a", "on"),
      "light.b" -> st("light.b", "off")
    )
    assertEquals(
      r.renderDynamicChild("c", "light.a", states).get,
      """<div class="fh-cell" id="c_light_a"><div><span>on</span> </div></div>"""
    )
    // fails the query -> not a member
    assertEquals(r.renderDynamicChild("c", "light.b", states), None)
    // unknown entity / unknown group -> None
    assertEquals(r.renderDynamicChild("c", "light.z", states), None)
    assertEquals(r.renderDynamicChild("zzz", "light.a", states), None)
  }

  // ---------------------------------------------------------------------------
  // State-activated surfaces (If/else as bake groups — Activation.State)
  // ---------------------------------------------------------------------------

  // The always-true predicate an authoring layer uses for an `else` member —
  // deliberately an ordinary condition, so the else needs no special casing.
  private val always: Predicate =
    Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__"))

  // "Entity X is in state Y" — the entity_id pin + Any quantifier idiom.
  private def entityIs(id: String, state: String): Predicate =
    Predicate.And(
      List(
        Predicate.Cmp("entity_id", Op.Eq, Json.fromString(id)),
        Predicate.Cmp("state", Op.Eq, Json.fromString(state))
      )
    )

  // The If host: a plain component card with one {{{branch}}} bake hole — no
  // tab bar, no signal, no cookie; the backend never required them.
  private val ifCards =
    cards + ("ifhost" -> CardDef("""<div id="{{id}}">{{{branch}}}</div>"""))

  /** An If/else dashboard: an `ifhost` root (id "c") whose `then` member (a
    * sensor.a card) is active while alarm.h == armed, with an always-true
    * `else` member (a sensor.b card) — or none, for the no-match case.
    */
  private def ifDashboard(withElse: Boolean = true): Dashboard = {
    def branch(name: String) = LayoutNode.Component(
      "card",
      slots = Map("state" -> SlotSource(Some(s"sensor.$name")))
    )
    val members = Map(
      "c_then" -> Surface(
        branch("a"),
        bakeInto = Some("c"),
        bakeAs = Some("branch"),
        bakeIndex = Some(0),
        activation = Activation.State(entityIs("alarm.h", "armed"))
      )
    ) ++ (if (withElse)
            Map(
              "c_else" -> Surface(
                branch("b"),
                bakeInto = Some("c"),
                bakeAs = Some("branch"),
                bakeIndex = Some(1),
                activation = Activation.State(always)
              )
            )
          else Map.empty)
    Dashboard(ifCards, LayoutNode.Component("ifhost"), surfaces = members)
  }

  private def armedStates(alarm: String) = Map(
    "alarm.h" -> st("alarm.h", alarm),
    "sensor.a" -> st("sensor.a", "A"),
    "sensor.b" -> st("sensor.b", "B")
  )

  test("matches: the entity_id property compares the entity's own id") {
    val s = st("light.a", "on")
    assert(
      Renderer.matches(
        Predicate.Cmp("entity_id", Op.Eq, Json.fromString("light.a")),
        s
      )
    )
    assert(
      !Renderer.matches(
        Predicate.Cmp("entity_id", Op.Eq, Json.fromString("light.b")),
        s
      )
    )
  }

  test(
    "resolveActiveByState picks the FIRST holding member in bakeIndex order"
  ) {
    val r = Renderer.create(ifDashboard())
    // then holds -> index 0 even though the always-true else would too.
    assertEquals(r.resolveActiveByState("c", armedStates("armed")), Some(0))
    // then fails -> the condition-less-equivalent else (always predicate).
    assertEquals(r.resolveActiveByState("c", armedStates("disarmed")), Some(1))
  }

  test("resolveActiveByState: no member holds -> None; the host bakes empty") {
    val r = Renderer.create(ifDashboard(withElse = false))
    val states = armedStates("disarmed")
    assertEquals(r.resolveActiveByState("c", states), None)
    // The host still renders its wrapper — with empty branch content, so a
    // matching branch appearing later has its patch target in the DOM.
    assertEquals(r.renderNodeById("c", states).get, """<div id="c"></div>""")
  }

  test("resolveActiveByState quantifiers: any = ∃, none = ∄, all = ∀") {
    val on = Predicate.Cmp("state", Op.Eq, Json.fromString("on"))
    def dash(q: Quantifier) = Dashboard(
      ifCards,
      LayoutNode.Component("ifhost"),
      surfaces = Map(
        "c_t" -> Surface(
          LayoutNode.Component("btn", Map("label" -> lit("x"))),
          bakeInto = Some("c"),
          bakeAs = Some("branch"),
          bakeIndex = Some(0),
          activation = Activation.State(on, q)
        )
      )
    )
    val mixed = Map("l.a" -> st("l.a", "on"), "l.b" -> st("l.b", "off"))
    val allOn = Map("l.a" -> st("l.a", "on"), "l.b" -> st("l.b", "on"))
    val allOff = Map("l.a" -> st("l.a", "off"), "l.b" -> st("l.b", "off"))

    val anyR = Renderer.create(dash(Quantifier.Any))
    assertEquals(anyR.resolveActiveByState("c", mixed), Some(0))
    assertEquals(anyR.resolveActiveByState("c", allOff), None)

    // none is ∄ — NOT a Not inside the condition (that would still be ∃).
    val noneR = Renderer.create(dash(Quantifier.None))
    assertEquals(noneR.resolveActiveByState("c", allOff), Some(0))
    assertEquals(noneR.resolveActiveByState("c", mixed), None)

    val allR = Renderer.create(dash(Quantifier.All))
    assertEquals(allR.resolveActiveByState("c", allOn), Some(0))
    assertEquals(allR.resolveActiveByState("c", mixed), None)
  }

  test("state members bake by condition and never enter selectedSurfaces") {
    val r = Renderer.create(ifDashboard())
    // The baked branch follows the condition, surface-namespaced like a tab.
    val bodyArmed = r.renderBody(armedStates("armed"))
    assert(bodyArmed.contains("""id="s_c_then__c""""), clue = bodyArmed)
    assert(bodyArmed.contains("<span>A</span>"), clue = bodyArmed)
    assert(!bodyArmed.contains("<span>B</span>"), clue = bodyArmed)
    val bodyElse = r.renderBody(armedStates("disarmed"))
    assert(bodyElse.contains("<span>B</span>"), clue = bodyElse)
    assert(!bodyElse.contains("<span>A</span>"), clue = bodyElse)
    // State members never seed a session's open set (their liveness is the
    // shared pass's job), and the owner splits to the state side.
    assertEquals(r.selectedSurfaces(), Set.empty[String])
    assertEquals(r.stateBakeOwnerIds, Set("c"))
    assertEquals(r.userBakeOwnerIds, Set.empty[String])
    // Tabs keep the exact opposite split (regression guard on the mode split).
    val tabs = Renderer.create(tabsDashboard)
    assertEquals(tabs.userBakeOwnerIds, Set("c"))
    assertEquals(tabs.stateBakeOwnerIds, Set.empty[String])
  }

  test(
    "sessionOnlyStateGroups: a user-selected owner inside a branch flags the group"
  ) {
    // The then-branch content is a `tabs` owner (a user-selected bake group
    // baked into the branch's content root `s_c_then__c`) — so the If's host
    // HTML embeds a cookie-selected member and cannot render shared.
    val d = Dashboard(
      ifCards,
      LayoutNode.Component("ifhost"),
      surfaces = Map(
        "c_then" -> Surface(
          LayoutNode.Component("tabs"),
          bakeInto = Some("c"),
          bakeAs = Some("branch"),
          bakeIndex = Some(0),
          activation = Activation.State(always)
        ),
        "t0" -> Surface(
          LayoutNode.Component("btn", Map("label" -> lit("t"))),
          bakeInto = Some("s_c_then__c"),
          bakeAs = Some("panel"),
          bakeIndex = Some(0),
          activation = Activation.User(defaultOpen = true)
        )
      )
    )
    assertEquals(Renderer.create(d).sessionOnlyStateGroups, Set("c"))
    // A branch with no user owner anywhere stays shared.
    assertEquals(
      Renderer.create(ifDashboard()).sessionOnlyStateGroups,
      Set.empty[String]
    )
  }

  test("affectedDynamics surfaces the membership delta per group") {
    val r = renderer(
      LayoutNode.Dynamic(
        query = Some(Predicate.Cmp("attr:battery", Op.Lt, Json.fromInt(20))),
        cases = List(
          DynamicCase(
            Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__")),
            "card",
            slots = Map("state" -> SlotSource())
          )
        )
      )
    )
    def low(id: String) = st(id, "x", "battery" -> Json.fromInt(5)) // matches
    def high(id: String) =
      st(id, "x", "battery" -> Json.fromInt(50)) // no match
    // prev ∧ cur -> InPlace
    assertEquals(
      r.affectedDynamics(StateChange("s.b", Some(low("s.b")), low("s.b"))),
      List("c" -> DynamicDelta.InPlace)
    )
    // ¬prev ∧ cur -> Added (both a high->low flip and a newly-seen match)
    assertEquals(
      r.affectedDynamics(StateChange("s.b", Some(high("s.b")), low("s.b"))),
      List("c" -> DynamicDelta.Added)
    )
    assertEquals(
      r.affectedDynamics(StateChange("s.b", None, low("s.b"))),
      List("c" -> DynamicDelta.Added)
    )
    // prev ∧ ¬cur -> Removed
    assertEquals(
      r.affectedDynamics(StateChange("s.b", Some(low("s.b")), high("s.b"))),
      List("c" -> DynamicDelta.Removed)
    )
    // matches neither side -> untouched (no entry)
    assertEquals(
      r.affectedDynamics(StateChange("s.z", Some(high("s.z")), high("s.z"))),
      Nil
    )
  }
}
