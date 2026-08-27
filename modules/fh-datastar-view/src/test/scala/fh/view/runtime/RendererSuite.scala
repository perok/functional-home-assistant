package fh.view.runtime

import fh.view.model.{
  Activation,
  CardDef,
  Cell,
  Dashboard,
  LayoutNode,
  Op,
  Predicate,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.DashboardBuilders.{col, lit, row, st}
import fh.view.testkit.TestIds.{setId, given}
import io.circe.Json

class RendererSuite extends munit.FunSuite {

  extension (r: Renderer)
    private def affectedSetIds(change: StateChange): List[String] =
      r.members.affectedSets(List(change))

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
    // Shaped like the shipped `Tabs`: the bar is STRUCTURE holding the buttons,
    // the panel is the mount. No `self` — a self may hold no hole, so a card
    // cannot both be a patch target and splice its children. Minimal markup,
    // real SHAPE — shape is what the engine dispatches on (`hasSelf` picks what
    // a patch renders and targets), so a fixture whose shape drifted from Tabs
    // would be a different KIND of card. See `tabsLive` below for the split
    // that survives: a self beside the mount, holding no hole.
    "tabs" -> CardDef(
      template = """<div class="fh-col"><div class="fh-row tabbar">""" +
        """{{#children}}{{{html}}}{{/children}}</div>{{{mount}}}</div>""",
      mount = Some(
        """<div id="{{mountId}}" class="tab-panel" data-signals="{ tab_{{id}}: {{bakeIndex}} }">{{{panel}}}</div>"""
      )
    ),
    // Like `tabs`, but the bake-owning component ALSO binds a live entity via a
    // `{{title}}` slot — so it is morph-wrapped and re-rendered on that entity's
    // state change. Exercises that a live node patch re-bakes the SELECTED tab.
    // THE shape the split exists for: a container with a mount AND a live slot
    // ("a tab bar with the current temperature in its header"). Its patch is the
    // `self` alone, so a title tick cannot re-render the panel.
    "tabsLive" -> CardDef(
      template = """<div>{{{self}}}{{{mount}}}</div>""",
      self = Some(
        """<div id="{{selfId}}" class="tabs"><span>{{title}}</span></div>"""
      ),
      mount = Some(
        """<div id="{{mountId}}" data-signals="{ tab_{{id}}: {{bakeIndex}} }">{{{panel}}}</div>"""
      ),
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

  /** A candidate set whose members are all present while their entity is on —
    * the shape these tests drove as a `state == on` query group. Each clause is
    * `(extra guard, card, slots, cell)`; the node names its own entity, because
    * the build knows the candidate.
    */
  private def onSet(
      candidates: List[String],
      clauses: List[
        (Option[Predicate], String, Map[String, SlotSource], Option[Cell])
      ],
      guardOn: Boolean = true
  ): LayoutNode.SetNode =
    LayoutNode.SetNode(
      candidates = candidates,
      members = candidates.map { id =>
        id -> LayoutNode.SetMember(clauses.map {
          case (extra, card, slots, cl) =>
            val on = Predicate.Cmp("state", Op.Eq, Json.fromString("on"))
            LayoutNode.SetClause(
              when = (if (guardOn) List(on) else Nil) ++ extra.toList match {
                case Nil      => None
                case g :: Nil => Some(g)
                case gs       => Some(Predicate.And(gs))
              },
              node = LayoutNode.Component(
                card,
                slots.updated("entity_id", SlotSource(literal = Some(id))),
                cell = cl
              )
            )
        })
      }.toMap
    )

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
    // keeps the set render path cheap (action/domain-config slots become a
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
      """<style id="fh-theme"></style><main class="container" id="dashboard"><div class="fh-cell" id="c"><div class="fh-col"><div class="fh-cell" id="c_0"><div class="fh-row"><div class="fh-cell" id="c_0_0"><button>Go</button></div></div></div></div></div></main>"""
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
    // A set clause: every member is a wrapped per-candidate patch target.
    val clause = Dashboard(
      bareCards,
      onSet(
        List("light.a"),
        List((None, "naked", Map("state" -> lit("x")), None))
      )
    )
    assert(
      clause.validate().exists(_.contains("cannot be a set clause")),
      clue = clause.validate()
    )
  }

  test("authored cell classes ride on every wrapper kind") {
    // Static component wrapper, candidate set root, and per-entity case
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

    val dyn = onSet(
      List("light.a"),
      List(
        (None, "btn", Map("label" -> lit("L")), Some(Cell(List("fh-cols-4"))))
      )
    ).copy(cell = Some(Cell(classes = List("fh-cols-full"))))
    // Rendered through the document path: a member container composes its
    // members, so it has no rendering of its OWN and is not addressable by id.
    val html = renderer(dyn).renderBody(Map("light.a" -> st("light.a", "on")))
    assertEquals(
      html,
      """<div class="fh-cell fh-group fh-cols-full" id="c">""" +
        """<div class="fh-cell fh-cols-4" id="c_light_a"><button>L</button></div></div>"""
    )
    // The per-member in-place path emits the identical wrapper classes.
    assertEquals(
      renderer(dyn)
        .renderMemberById(
          setId("c"),
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
      """<style id="fh-theme"></style><main id="dashboard"><div class="fh-cell" id="c"><div class="fh-col"><div class="fh-cell" id="c_0"><button>Go</button></div></div></div></main><dialog id="popups"><div id="popups-body"></div></dialog>"""
    )
  }

  test("renderPage bakes a restored popup into the chrome's popup hole") {
    val d = Dashboard(
      cards,
      col(LayoutNode.Component("btn", Map("label" -> lit("Go")))),
      surfaces = Map(
        "det" -> Surface(LayoutNode.Component("btn", Map("label" -> lit("D"))))
      ),
      theme = Theme(chrome =
        """<main id="dashboard">{{{body}}}</main><div id="popups">{{{popups}}}</div>"""
      )
    )
    val r = Renderer.create(d)
    // Baked === what the connect would patch in, so the patch that follows is a
    // no-op morph rather than a second, visible paint.
    val baked = r.renderPage(Map.empty, popup = Some("det"))
    assert(
      baked.contains(
        s"""<div id="popups">${r.renderSurface("det", Map.empty).get}</div>"""
      ),
      clue = baked
    )
    // No popup, or one this dashboard doesn't host: the hole renders empty.
    assert(r.renderPage(Map.empty).contains("""<div id="popups"></div>"""))
    assert(
      r.renderPage(Map.empty, popup = Some("nope"))
        .contains("""<div id="popups"></div>""")
    )
  }

  test("a popup surface with nowhere to mount is a warning, not an error") {
    // Both failures are silent in the browser — a tap that does nothing, or a
    // dialog that pops in late — so the only place they can be attributed is
    // here, at build time.
    val popup = Map(
      "det" -> Surface(LayoutNode.Component("btn", Map("label" -> lit("D"))))
    )
    val body = col(LayoutNode.Component("btn", Map("label" -> lit("Go"))))
    def warningsOf(chrome: String): List[String] =
      Renderer
        .create(
          Dashboard(
            cards,
            body,
            surfaces = popup,
            theme = Theme(chrome = chrome)
          )
        )
        .warnings

    // No host at all: the popup can never be shown. The empty chrome counts —
    // the fallback frame has no host either.
    assert(clue(warningsOf("")).exists(_.contains("never be shown")))
    assert(
      clue(warningsOf("""<main id="dashboard">{{{body}}}</main>"""))
        .exists(_.contains("det"))
    )
    // A host but no hole: works, flashes on a refresh.
    assert(
      clue(
        warningsOf(
          """<main id="dashboard">{{{body}}}</main><div id="popups"></div>"""
        )
      ).exists(_.contains("{{{popups}}}"))
    )
    // Both present: nothing to say.
    assertEquals(
      warningsOf(
        """<main id="dashboard">{{{body}}}</main><div id="popups">{{{popups}}}</div>"""
      ),
      Nil
    )
    // And a dashboard with no popup surfaces is never nagged about a host it
    // has no use for.
    assertEquals(
      Renderer
        .create(Dashboard(cards, body, theme = Theme(chrome = "")))
        .warnings,
      Nil
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

  test("a set dispatches per clause and wraps each member on its own") {
    // Presence is `battery < 20`; the FIRST clause whose guard holds decides
    // the rendering, so light.a takes the btn clause and sensor.b the card one.
    def low = Predicate.Cmp("attr:battery", Op.Lt, Json.fromInt(20))
    val set = LayoutNode.SetNode(
      candidates = List("light.a", "sensor.b", "sensor.c"),
      members = Map(
        "light.a" -> LayoutNode.SetMember(
          List(
            LayoutNode.SetClause(
              Some(low),
              LayoutNode.Component(
                "btn",
                Map(
                  "entity_id" -> lit("light.a"),
                  "label" -> SlotSource(transform = "$attr.friendly_name")
                )
              )
            )
          )
        ),
        "sensor.b" -> LayoutNode.SetMember(
          List(
            LayoutNode.SetClause(
              Some(low),
              LayoutNode.Component(
                "card",
                Map(
                  "entity_id" -> lit("sensor.b"),
                  "state" -> SlotSource()
                )
              )
            )
          )
        ),
        "sensor.c" -> LayoutNode.SetMember(
          List(
            LayoutNode.SetClause(
              Some(low),
              LayoutNode.Component(
                "card",
                Map(
                  "entity_id" -> lit("sensor.c"),
                  "state" -> SlotSource()
                )
              )
            )
          )
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
    val r = renderer(set)
    // A set as layout root -> its own id'd container "c" is the outer morph
    // target (itself a cell, plus `fh-group`); each present member is ALSO
    // wrapped in its own id'd `fh-cell` — the per-candidate patch target
    // `<groupId>_<sanitized entity>`.
    val html = r.renderBody(states)
    assert(
      html.startsWith("""<div class="fh-cell fh-group" id="c">"""),
      clue = html
    )
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
    // sensor.c's only clause does not hold (battery 50), so it is ABSENT — not
    // hidden, not rendered blank.
    assert(!html.contains("cold"), clue = html)
    assert(!html.contains("c_sensor_c"), clue = html)
    // The set is indexed under its own id, and a candidate's change selects it.
    assertEquals(
      r.affectedSetIds(
        StateChange("light.a", None, states("light.a"))
      ),
      List("c")
    )
  }

  test("affectedSetIds selects a set only for an entity it reads") {
    val r = renderer(
      onSet(
        List("light.a", "light.b"),
        List((None, "card", Map("state" -> SlotSource()), None))
      )
    )
    def ch(id: String) = StateChange(id, None, st(id, "on"))
    // A candidate selects it whichever way its presence moved — WHICH way is
    // the frame's question (`syncMembers`), not one change's.
    assertEquals(r.affectedSetIds(ch("light.a")), List("c"))
    // An entity the set neither holds nor names cannot move it. This is the
    // whole cost claim: a frame is O(changed), not O(candidates), and an
    // unrelated house-wide change reaches nothing.
    assertEquals(r.affectedSetIds(ch("sensor.z")), Nil)
    // One frame, several candidates: ONE entry.
    assertEquals(
      r.members.affectedSets(List(ch("light.a"), ch("light.b"))),
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
      // The theme element leads the page, OUTSIDE #dashboard: a repaint of the
      // body must not have to re-send it (docs/adr/0011-the-live-connection.md).
      page.startsWith(
        """<style id="fh-theme">:root{color-scheme:light dark;--accent-color:#000;--primary-color:#bada55;}.card{color:red}</style><main class="container" id="dashboard">"""
      ),
      clue = page
    )
    assert(!page.contains("prefers-color-scheme"), clue = page)
  }

  test("the style block layers base CSS, then cards, then the theme") {
    // Cascade order IS the layering (ADR 0020): a theme overrides a card and a
    // card overrides the base only because each arrives later in one <style>.
    // Get this backwards and every override silently inverts, which is why the
    // ORDER is asserted here rather than the content (that is `pkl test`'s).
    val d = Dashboard(
      Map(
        "b" -> CardDef("<b></b>", css = ".b{color:blue}"),
        "a" -> CardDef("<i></i>", css = ".a{color:green}")
      ),
      LayoutNode.Component(card = "a"),
      theme = Theme(styles = ".card{color:red}"),
      css = ":root{--fh-accent:teal}"
    )
    val style = Renderer.create(d).themeStyleTag
    assertEquals(
      style,
      """<style id="fh-theme">:root{--fh-accent:teal}.a{color:green}""" +
        "\n" + """.b{color:blue}.card{color:red}</style>"""
    )
  }

  test("a card's CSS is part of the patchable style hash") {
    // The style tag is re-sent on a reconnect whose styleHash moved
    // (`Server.headPatches`). A card's CSS rides in that tag, so a change to it
    // that did not move the hash would leave a stale stylesheet in place.
    val base = Dashboard(
      Map("a" -> CardDef("<i></i>")),
      LayoutNode.Component(card = "a")
    )
    val styled =
      base.copy(cards = Map("a" -> CardDef("<i></i>", css = ".a{color:green}")))
    assertNotEquals(
      Renderer.styleFingerprint(base),
      Renderer.styleFingerprint(styled)
    )
    assertNotEquals(
      Renderer.styleFingerprint(base),
      Renderer.styleFingerprint(base.copy(css = ":root{--fh-accent:teal}"))
    )
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
    // The element is always emitted (a navigate needs it as a morph target),
    // but it is empty.
    assert(page.startsWith("""<style id="fh-theme"></style>"""), clue = page)
    assert(!page.contains(":root"), clue = page)
    assertEquals(Renderer.create(d).stylesheets, Nil)
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
    assertEquals(rr.surfaces.selectedSurfaces(), Set("c_t0"))

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
    // A ui-state index selects that member of the bake group...
    assertEquals(rr.surfaces.selectedSurfaces(Map("c" -> "1")), Set("c_t1"))
    // ...and no selection picks index 0 (parity with the old defaultOpenSurfaces).
    assertEquals(rr.surfaces.selectedSurfaces(Map.empty), Set("c_t0"))
    assertEquals(rr.surfaces.selectedSurfaces(), Set("c_t0"))
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

  test("resolveActive parses, clamps, and warns on an off ui-state value") {
    val rr = Renderer.create(tabsDashboard)
    // out of range and unparseable both fall back to index 0 AND yield a warning
    val outOfRange = rr.surfaces.resolveActive("c", Map("c" -> "99"))
    assertEquals(outOfRange._1, 0)
    assert(outOfRange._2.isDefined, clue = outOfRange)
    val unparseable = rr.surfaces.resolveActive("c", Map("c" -> "abc"))
    assertEquals(unparseable._1, 0)
    assert(unparseable._2.isDefined, clue = unparseable)
    // a valid index and an absent key both select without a warning
    assertEquals(rr.surfaces.resolveActive("c", Map("c" -> "1")), (1, None))
    assertEquals(rr.surfaces.resolveActive("c", Map.empty), (0, None))
    // uiStateAnomalies surfaces exactly the malformed entries
    assertEquals(rr.surfaces.uiStateAnomalies(Map("c" -> "1")), Nil)
    assertEquals(rr.surfaces.uiStateAnomalies(Map.empty), Nil)
    assertEquals(rr.surfaces.uiStateAnomalies(Map("c" -> "99")).size, 1)
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
    "a live bake owner patches its header alone; the bake is document-path only"
  ) {
    // A `tabsLive` component (id "c") owns a bake group AND binds a live entity
    // (`sensor.title`). On a live SSE patch the node is re-rendered by id — it
    // must bake the SESSION's selected tab, not the default one.
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

    // THE contract, and the reason the whole design exists: a live tick on the
    // host patches its `self` — the header — and carries NOTHING of the panel.
    // A change to the title cannot re-render what the tabs host holds.
    val patch = rr.renderNodeById("c", states).get
    assertEquals(
      patch,
      """<div id="c-self" class="tabs"><span>Live</span></div>"""
    )

    // Which member is baked was the ONLY thing that made this per-client, and it
    // lives on the document path alone now. There the selection still decides:
    // no uiState bakes tab 0, `c -> 1` bakes tab 1, signal seed included.
    val dflt = rr.renderBody(states)
    assert(dflt.contains("tab_c: 0"), clue = dflt)
    assert(dflt.contains("<span>AA</span>"), clue = dflt)
    assert(!dflt.contains("<span>BB</span>"), clue = dflt)

    val sel = rr.renderBody(states, Map("c" -> "1"))
    assert(sel.contains("tab_c: 1"), clue = sel)
    assert(sel.contains("<span>BB</span>"), clue = sel)
    assert(!sel.contains("<span>AA</span>"), clue = sel)
  }

  // ---------------------------------------------------------------------------
  // Per-entity candidate-set patches (Tier 1 + Tier 2)
  // ---------------------------------------------------------------------------

  // A set (as the layout root, so group id "c") shown while each candidate is
  // on. `light.z` is a candidate no test turns on — the id-slugging case.
  private val onGroup = onSet(
    List("light.a", "light.b", "light.c", "light-b.x", "light.z"),
    List((None, "card", Map("state" -> SlotSource()), None))
  )

  test("memberIdOf slugs the entity id under the group id") {
    val r = renderer(onGroup)
    assertEquals(r.members.memberIdOf(setId("c"), "light.a"), "c_light_a")
    assertEquals(r.members.memberIdOf(setId("c"), "light-b.x"), "c_light_b_x")
  }

  test("memberEntities: query + case matches, in DOM (entity-id) order") {
    val r = renderer(onGroup)
    val states = Map(
      "light.b" -> st("light.b", "on"),
      "light.a" -> st("light.a", "on"),
      "light.c" -> st("light.c", "off") // fails the query
    )
    assertEquals(
      r.members.memberEntities(setId("c"), states),
      List("light.a", "light.b")
    )
    // unknown / non-set id -> no members
    assertEquals(r.members.memberEntities(setId("zzz"), states), Nil)
  }

  test(
    "renderMemberById renders ONE wrapped card, or None for a non-member"
  ) {
    val r = renderer(onGroup)
    val states = Map(
      "light.a" -> st("light.a", "on"),
      "light.b" -> st("light.b", "off")
    )
    assertEquals(
      r.renderMemberById(setId("c"), "light.a", states).get,
      """<div class="fh-cell" id="c_light_a"><div><span>on</span> </div></div>"""
    )
    // fails the query -> not a member
    assertEquals(r.renderMemberById(setId("c"), "light.b", states), None)
    // unknown entity / unknown group -> None
    assertEquals(r.renderMemberById(setId("c"), "light.z", states), None)
    assertEquals(r.renderMemberById(setId("zzz"), "light.a", states), None)
  }

  // ---------------------------------------------------------------------------
  // State-activated surfaces (If/else as bake groups — Activation.State)
  // ---------------------------------------------------------------------------

  // The always-true predicate an authoring layer uses for an `else` member —
  // an empty conjunction is vacuously true and reads no entity, so the else
  // needs no special casing and no subject.
  private val always: Predicate = Predicate.And(Nil)

  // "Entity X is in state Y" — the condition names its entity, so evaluating it
  // is one lookup.
  private def entityIs(id: String, state: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString(state), entity = Some(id))

  // The If host: a plain component card with one {{{branch}}} bake hole — no
  // tab bar, no signal, no ui state; the backend never required them.
  private val ifCards =
    // Mirrors lib/components.pkl's `If`: a pure mount, no `self` — an If has no
    // presentation of its own. The cell wrapper (which the backend owns) is the
    // node's id'd element; the mount's id is `Surface.hostId`.
    cards + ("ifhost" -> CardDef(
      template = "{{{self}}}{{{mount}}}",
      mount = Some("""<div id="{{mountId}}">{{{branch}}}</div>""")
    ))

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

  test(
    "resolveActiveByState picks the FIRST holding member in bakeIndex order"
  ) {
    val r = Renderer.create(ifDashboard())
    // then holds -> index 0 even though the always-true else would too.
    assertEquals(
      r.surfaces.resolveActiveByState("c", armedStates("armed")),
      Some(0)
    )
    // then fails -> the condition-less-equivalent else (always predicate).
    assertEquals(
      r.surfaces.resolveActiveByState("c", armedStates("disarmed")),
      Some(1)
    )
  }

  test("resolveActiveByState: no member holds -> None; the host bakes empty") {
    val r = Renderer.create(ifDashboard(withElse = false))
    val states = armedStates("disarmed")
    assertEquals(r.surfaces.resolveActiveByState("c", states), None)
    // The host still renders its wrapper — with empty branch content, so a
    // matching branch appearing later has its patch target in the DOM. Both
    // boxes: the cell (the node's own element) and the mount inside it. Through
    // the document path: an If is a pure mount, so it has no rendering of its
    // own and `renderNodeById` refuses it.
    assertEquals(
      r.renderBody(states),
      """<div class="fh-cell" id="c"><div id="c_branch"></div></div>"""
    )
    assertEquals(r.renderNodeById("c", states), None)
  }

  // What the quantifiers became: a comparison on how many of a NAMED set are
  // present. `any` is count > 0, `none` is count == 0, `all` is count == length
  // — the same three answers, over the set the author meant rather than every
  // entity in the house.
  test("a state condition counts a named set: any/none/all as comparisons") {
    val on = Predicate.Cmp("state", Op.Eq, Json.fromString("on"))
    def dash(cond: Predicate) = Dashboard(
      ifCards,
      LayoutNode.Component("ifhost"),
      surfaces = Map(
        "c_t" -> Surface(
          LayoutNode.Component("btn", Map("label" -> lit("x"))),
          bakeInto = Some("c"),
          bakeAs = Some("branch"),
          bakeIndex = Some(0),
          activation = Activation.State(cond)
        )
      )
    )
    def count(op: Op, n: Int) = Predicate.Count(
      candidates = List("l.a", "l.b"),
      when = Map("l.a" -> on, "l.b" -> on),
      op = op,
      value = Json.fromInt(n)
    )
    val mixed = Map("l.a" -> st("l.a", "on"), "l.b" -> st("l.b", "off"))
    val allOn = Map("l.a" -> st("l.a", "on"), "l.b" -> st("l.b", "on"))
    val allOff = Map("l.a" -> st("l.a", "off"), "l.b" -> st("l.b", "off"))

    val anyR = Renderer.create(dash(count(Op.Gt, 0)))
    assertEquals(anyR.surfaces.resolveActiveByState("c", mixed), Some(0))
    assertEquals(anyR.surfaces.resolveActiveByState("c", allOff), None)

    val noneR = Renderer.create(dash(count(Op.Eq, 0)))
    assertEquals(noneR.surfaces.resolveActiveByState("c", allOff), Some(0))
    assertEquals(noneR.surfaces.resolveActiveByState("c", mixed), None)

    val allR = Renderer.create(dash(count(Op.Eq, 2)))
    assertEquals(allR.surfaces.resolveActiveByState("c", allOn), Some(0))
    assertEquals(allR.surfaces.resolveActiveByState("c", mixed), None)

    // A lone entity needs no set at all: the condition names it, so the answer
    // is a lookup and an unrelated entity's state cannot decide it.
    val oneR = Renderer.create(dash(entityIs("l.a", "on")))
    assertEquals(oneR.surfaces.resolveActiveByState("c", mixed), Some(0))
    assertEquals(oneR.surfaces.resolveActiveByState("c", allOff), None)
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
    assertEquals(r.surfaces.selectedSurfaces(), Set.empty[String])
    assertEquals(r.surfaces.stateBakeOwnerIds, Set("c"))
    assertEquals(r.surfaces.userBakeOwnerIds, Set.empty[String])
    // Tabs keep the exact opposite split (regression guard on the mode split).
    val tabs = Renderer.create(tabsDashboard)
    assertEquals(tabs.surfaces.userBakeOwnerIds, Set("c"))
    assertEquals(tabs.surfaces.stateBakeOwnerIds, Set.empty[String])
  }

  /** The shape W18's card-shape test could not see: a container that splices
    * `{{#children}}` into its `template` with no mount at all — the pre-split
    * container. It passes "has no mount", so it looked like a node with its own
    * rendering, while its rendering carries whatever its children's mounts
    * hold.
    *
    * Found by accident: W18's first test fixture was exactly this, and the test
    * still failed after the fix.
    */
  test("a node whose CHILDREN carry a mount has no rendering of its own") {
    def dash(container: CardDef) = Dashboard(
      cards =
        Map(
          "box" -> container,
          "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
          "tabs" -> CardDef(
            template = "{{{self}}}{{{mount}}}",
            self = Some("""<div id="{{selfId}}">bar</div>"""),
            mount = Some("""<div id="{{mountId}}">{{{panel}}}</div>""")
          )
        ),
      card = LayoutNode
        .Component("box", children = List(LayoutNode.Component("tabs"))),
      surfaces = Map(
        "t0" -> Surface(
          LayoutNode.Component(
            "card",
            slots = Map("state" -> SlotSource(Some("sensor.a")))
          ),
          bakeInto = Some("c_0"),
          bakeAs = Some("panel"),
          bakeIndex = Some(0),
          activation = Activation.User(defaultOpen = true)
        )
      )
    )
    val states = Map("sensor.a" -> st("sensor.a", "A0"))

    // The PRE-SPLIT shape: children in `template`, no mount anywhere on the
    // card itself.
    val preSplit = Renderer.create(
      dash(CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"))
    )
    assertEquals(preSplit.renderNodeById("c", states), None)
    // ...and a card with a `self` that splices its children, whose CHILD owns
    // the bake group. This one is no longer the renderer's to exclude: a self
    // may hold no hole, so the dashboard is REJECTED rather than rendered with
    // one node quietly denied live updates. Asserting the stronger thing —
    // nothing reaches the renderer at all.
    val selfWithBakingChild = dash(
      CardDef(
        template = "{{{self}}}",
        self = Some(
          """<div id="{{selfId}}">{{#children}}{{{html}}}{{/children}}</div>"""
        )
      )
    )
    assert(
      selfWithBakingChild
        .validate()
        .exists(e => e.contains("box") && e.contains("{{#children}}")),
      s"expected a self-hole error, got ${selfWithBakingChild.validate()}"
    )

    // Non-vacuous: the same container with a LEAF child keeps its own
    // rendering, because nothing under it holds a mount.
    val plain = Renderer.create(
      Dashboard(
        cards = Map(
          "box" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
          "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
        ),
        card = LayoutNode
          .Component(
            "box",
            children = List(
              LayoutNode.Component(
                "card",
                slots = Map("state" -> SlotSource(Some("sensor.a")))
              )
            )
          )
      )
    )
    assert(plain.renderNodeById("c", states).exists(_.contains("A0")))
  }

  /** The other half of the rule above, and the one that cost a live update: a
    * `self` that leaves its children ENTIRELY to the mount cannot carry what
    * they hold, so what they hold is none of its business.
    *
    * Real shape: a slider holding member sliders. The head is the `self`, the
    * rows are the mount — and the moment the member card gained a mount of its
    * own (one card for both, ADR 0006), asking `children.exists(carriesMount)`
    * unconditionally made the HEAD unaddressable, so dragging a row stopped
    * updating the master until a reload.
    */
  test("a self that does not splice its children stays addressable") {
    val cards = Map(
      "host" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        self = Some("""<div id="{{selfId}}"><span>{{state}}</span></div>"""),
        mount = Some("""<div>{{#children}}{{{html}}}{{/children}}</div>"""),
        slots = List("state")
      ),
      // A member that is itself a container — the change that broke this.
      "member" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        self = Some("""<div id="{{selfId}}">m</div>"""),
        mount = Some("""<div>{{#children}}{{{html}}}{{/children}}</div>""")
      )
    )
    val r = Renderer.create(
      Dashboard(
        cards,
        LayoutNode.Component(
          "host",
          slots = Map("state" -> SlotSource(Some("sensor.a"))),
          children = List(LayoutNode.Component("member"))
        )
      )
    )
    val html = r.renderNodeById("c", Map("sensor.a" -> st("sensor.a", "A0")))
    assert(html.exists(_.contains("A0")), clue = html)
    // Its patch still targets the self alone, so the rows are not in it.
    assert(!html.exists(_.contains("m")), clue = html)
  }

  test("userSurfaceOf: state surfaces are transparent, user surfaces are not") {
    // Two chains hanging off the main page, each two surfaces deep:
    //   main -> t0 (user)  -> if host -> b0 (state)
    //   main -> sx (state) -> tabs    -> u0 (user)
    // The containing surface is where a surface's HOST node sits, so t0's If
    // host is `s_t0__c` and sx's tabs host is `s_sx__c`.
    val d = Dashboard(
      ifCards,
      col(
        LayoutNode.Component("tabs"), // c_0 — hosts the user surface t0
        LayoutNode.Component("ifhost") // c_1 — hosts the state surface sx
      ),
      surfaces = Map(
        "t0" -> Surface(
          LayoutNode.Component("ifhost"),
          bakeInto = Some("c_0"),
          bakeAs = Some("panel"),
          bakeIndex = Some(0),
          activation = Activation.User(defaultOpen = true)
        ),
        "b0" -> Surface(
          LayoutNode.Component("card", Map("state" -> SlotSource(Some("s.a")))),
          bakeInto = Some("s_t0__c"),
          bakeAs = Some("branch"),
          bakeIndex = Some(0),
          activation = Activation.State(always)
        ),
        "sx" -> Surface(
          LayoutNode.Component("tabs"),
          bakeInto = Some("c_1"),
          bakeAs = Some("branch"),
          bakeIndex = Some(0),
          activation = Activation.State(always)
        ),
        "u0" -> Surface(
          LayoutNode.Component("card", Map("state" -> SlotSource(Some("s.b")))),
          bakeInto = Some("s_sx__c"),
          bakeAs = Some("panel"),
          bakeIndex = Some(0),
          activation = Activation.User(defaultOpen = true)
        )
      )
    )
    val r = Renderer.create(d)

    // A user surface is its own tag — it is exactly what hides content.
    assertEquals(r.surfaces.userSurfaceOf("t0"), Some("t0"))
    assertEquals(r.surfaces.userSurfaceOf("u0"), Some("u0"))
    // A state surface hides nothing (every client sees the same branch), so the
    // walk passes THROUGH it to whatever encloses it...
    assertEquals(r.surfaces.userSurfaceOf("b0"), Some("t0"))
    // ...and reaching the main page means "no user surface above me".
    assertEquals(r.surfaces.userSurfaceOf("sx"), None)

    // The same, entered by node: a node is tagged by the tree it was indexed
    // from, which is NOT derivable from its id (`s_b0__c` names only b0).
    assertEquals(r.surfaces.userSurfaceOfNode("s_b0__c"), Some("t0"))
    assertEquals(r.surfaces.userSurfaceOfNode("s_u0__c"), Some("u0"))
    assertEquals(r.surfaces.userSurfaceOfNode("s_sx__c"), None)
    assertEquals(r.surfaces.userSurfaceOfNode("c"), None)
    // An id no tree owns has no tag to give.
    assertEquals(r.surfaces.userSurfaceOfNode("c_nope"), None)
  }

  test("affectedSets surfaces the membership delta per group") {
    val r = renderer(
      onSet(
        List("s.b", "s.c"),
        List(
          (
            Some(Predicate.Cmp("attr:battery", Op.Lt, Json.fromInt(20))),
            "card",
            Map("state" -> SlotSource()),
            None
          )
        ),
        guardOn = false
      )
    )
    def low(id: String) = st(id, "x", "battery" -> Json.fromInt(5)) // matches
    def high(id: String) =
      st(id, "x", "battery" -> Json.fromInt(50)) // no match
    // Matching either side selects the group. WHICH way it moved is the
    // frame's question (`syncMembers`), not one change's — and WHICH members
    // ticked is no longer asked here at all: a member that merely ticked is
    // found through the reverse index, like any other node.
    assertEquals(
      r.members.affectedSets(
        List(StateChange("s.b", Some(low("s.b")), low("s.b")))
      ),
      List("c")
    )
    // ¬prev ∧ cur (both a high->low flip and a newly-seen match)
    assertEquals(
      r.members.affectedSets(
        List(StateChange("s.b", Some(high("s.b")), low("s.b")))
      ),
      List("c")
    )
    assertEquals(
      r.members.affectedSets(List(StateChange("s.b", None, low("s.b")))),
      List("c")
    )
    // prev ∧ ¬cur
    assertEquals(
      r.members.affectedSets(
        List(StateChange("s.b", Some(low("s.b")), high("s.b")))
      ),
      List("c")
    )
    // matches neither side -> untouched (no entry)
    assertEquals(
      r.members.affectedSets(
        List(StateChange("s.z", Some(high("s.z")), high("s.z")))
      ),
      Nil
    )
    // One frame, several entities: ONE entry.
    assertEquals(
      r.members.affectedSets(
        List(
          StateChange("s.b", Some(high("s.b")), low("s.b")),
          StateChange("s.c", Some(low("s.c")), high("s.c"))
        )
      ),
      List("c")
    )
  }

  // ---- the self/mount split (docs/adr/0012-each-session-renders-what-it-is-owed.md) ----

  /** A container that declares both parts AND binds a live entity — the shape
    * the split exists for ("a tab bar with the current temperature in its
    * header"). The mount holds the child; the self holds the live header.
    */
  private val splitCards = cards + ("split" -> CardDef(
    template = """<div class="fh-col">{{{self}}}{{{mount}}}</div>""",
    self = Some("""<div id="{{selfId}}" class="bar">{{state}}</div>"""),
    // No `{{mountId}}`: a mount needs an id only where something FILLS it, which
    // is where `bakeAs` names it. This card has no bake group, like Grid/Row.
    mount = Some(
      """<div class="panel">{{#children}}{{{html}}}{{/children}}</div>"""
    ),
    slots = List("state")
  ))

  private def splitRenderer: Renderer =
    Renderer.create(
      Dashboard(
        splitCards,
        LayoutNode.Component(
          "split",
          slots = Map("state" -> SlotSource(Some("sensor.t"))),
          children = List(
            LayoutNode.Component("btn", Map("label" -> lit("inside")))
          )
        )
      )
    )

  test("the document path renders both parts, the child inside the mount") {
    val html = splitRenderer.renderBody(Map("sensor.t" -> st("sensor.t", "21")))
    // The cell wrapper owns the node id, the self part owns its own — disjoint,
    // one owner each.
    assert(html.contains("""class="fh-cell" id="c""""), clue = html)
    assert(html.contains("""id="c-self""""), clue = html)
    assert(html.contains("21"), clue = html)
    assert(html.contains("inside"), clue = html)
  }

  test("a container's patch render is its self element alone — statement (1)") {
    val r = splitRenderer
    val patch =
      r.renderNodeById("c", Map("sensor.t" -> st("sensor.t", "21"))).get
    // What it IS: the self element, with the live value.
    assert(patch.startsWith("""<div id="c-self""""), clue = patch)
    assert(patch.contains("21"), clue = patch)
    // What it is NOT — the point of the whole design. The mount's id does not
    // appear at all, so this fragment cannot disturb what the mount holds, and
    // neither does the cell wrapper (which contains the mount).
    assert(!patch.contains("panel"), clue = patch)
    assert(!patch.contains("inside"), clue = patch)
    assert(!patch.contains("fh-cell"), clue = patch)
  }

  test(
    "patchTargetId aims at the self element for a container, the node for a leaf"
  ) {
    val r = splitRenderer
    assertEquals(r.patchTargetId("c"), "c-self")
    // The child is a leaf: its whole rendering IS its patch.
    assertEquals(r.patchTargetId("c_0"), "c_0")
    // Nothing maps back — the log key stays the node id.
    assertEquals(r.elementId("c"), "c")
  }

  test(
    "mountId IS Surface.hostId — the template stops deriving it separately"
  ) {
    val r = Renderer.create(tabsDashboard)
    assertEquals(r.mountId("c"), "c_panel")
    assertEquals(r.mountId("c"), r.surface("c_t0").get.hostId)
  }
}
