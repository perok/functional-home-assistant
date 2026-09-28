package fh.view.runtime

import fh.view.query.QuerySnapshot
import fh.view.runtime.RendererTestOps.*

import fh.view.model.{
  Activation,
  CardDef,
  Cell,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  Reads,
  Region,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.DashboardBuilders.{asComponent, col, lit, row, st}
import fh.view.testkit.TestIds.{setId, given}
import io.circe.Json

class RendererSuite extends munit.FunSuite {

  extension (r: Renderer)
    private def affectedSetIds(change: StateChange): List[String] =
      r.members.affectedSets(List(change))

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
      """<div class="fh-col">{{#children}}{{{html}}}{{/children}}</div>""",
      regions = Map("children" -> Region())
    ),
    "row" -> CardDef(
      """<div class="fh-row">{{#children}}{{{html}}}{{/children}}</div>""",
      regions = Map("children" -> Region())
    ),
    // Shaped like the shipped `Tabs`, structure with a bar region and a baked
    // panel region, because `CardDef.isStructure` is what the engine dispatches
    // on: a fixture whose shape drifted would be a different kind of card.
    "tabs" -> CardDef(
      template = """<div class="fh-col"><div class="fh-row tabbar">""" +
        """{{#children}}{{{html}}}{{/children}}</div>""" +
        """<div id="{{hostId}}" class="tab-panel" data-signals="{ tab_{{id}}: {{bakeIndex}} }">{{#panel}}{{{html}}}{{/panel}}</div></div>""",
      regions = Map("children" -> Region(), "panel" -> Region(Region.Baked))
    ),
    // The live header is a node in the bar region, so a title tick patches that
    // node and cannot reach the panel.
    "tabsLive" -> CardDef(
      template = """<div><div class="tabs">{{#bar}}{{{html}}}{{/bar}}</div>""" +
        """<div id="{{hostId}}" data-signals="{ tab_{{id}}: {{bakeIndex}} }">{{#panel}}{{{html}}}{{/panel}}</div></div>""",
      regions = Map("bar" -> Region(), "panel" -> Region(Region.Baked))
    ),
    "tabsBar" -> CardDef("""<span>{{title}}</span>""", slots = List("title"))
  )

  // As `c.tabs` plus the hoist produce it: the surfaces carry `bakeInto:"c"`,
  // `bakeAs:"panel"`, so `hostId` derives to `c_panel`, the hoist invariant.
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
        regions = LayoutNode.kids(
          LayoutNode.Component("btn", Map("label" -> lit("A"))),
          LayoutNode.Component("btn", Map("label" -> lit("B")))
        )
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
  }

  private def renderer(layout: LayoutNode): Renderer = {
    val d = Dashboard(cards, layout)
    Renderer.create(d)
  }

  /** Each clause is `(extra guard, card, slots, cell)`. The node names its own
    * entity, because the build knows the candidate.
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

  private val card = LayoutNode.Component(
    card = "card",
    slots = Map(
      "state" -> SlotSource(Some("sensor.t")),
      "unit" -> SlotSource(
        Some("sensor.t"),
        "'unit_of_measurement' in attr ? attr['unit_of_measurement'] : ''"
      )
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
    assertEquals(r.componentsFor("sensor.t"), Set("c_0"))
    assertEquals(r.componentsFor("sensor.other"), Set.empty[String])
  }

  test(
    "entity-bound component is wrapped in the id'd morph target; slots escaped"
  ) {
    val html = renderer(card)
      .renderNodeById("c", states, fragments = QuerySnapshot.empty)
      .get
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
    // Bypassing is the default.
    val node = LayoutNode.Component(
      card = "card",
      slots = Map(
        "state" -> SlotSource(
          Some("sensor.t"),
          transform = "str(math.round(num(state) * 10.0) / 10.0)"
        )
      )
    )
    val r = renderer(node)
    assert(
      r.renderNodeById(
        "c",
        Map("sensor.t" -> st("sensor.t", "21.46")),
        fragments = QuerySnapshot.empty
      ).get
        .contains("<span>21.5</span>")
    )
    // "unavailable" never enters CEL, which would error.
    assert(
      r.renderNodeById(
        "c",
        Map("sensor.t" -> st("sensor.t", "unavailable")),
        fragments = QuerySnapshot.empty
      ).get
        .contains("<span>unavailable</span>")
    )
  }

  test("bypassUnavailable=false runs the transform even when unavailable") {
    // Opted out so its transform still runs: a label keeps the name, an action
    // stays resolvable.
    val node = LayoutNode.Component(
      card = "card",
      slots = Map(
        "state" -> SlotSource(
          Some("sensor.t"),
          transform = "state + '!'",
          bypassUnavailable = false
        )
      )
    )
    val r = renderer(node)
    assert(
      r.renderNodeById(
        "c",
        Map("sensor.t" -> st("sensor.t", "unavailable")),
        fragments = QuerySnapshot.empty
      ).get
        .contains("<span>unavailable!</span>"),
      clue = "transform should run, not be bypassed"
    )
  }

  // ADR 0016 bakes actions; a hand-written map action is the fallback, and must
  // resolve from `domain` with no state.
  test("a fallback map action resolves from the entity id with no state") {
    val expr =
      """cel.bind(m, {'scene': 'scene/turn_on'}, """ +
        """domain in m ? m[domain] : 'homeassistant/toggle')"""
    def actionNode(entity: String): LayoutNode =
      LayoutNode.Component(
        card = "act",
        slots = Map("action" -> SlotSource(Some(entity), transform = expr))
      )
    assert(
      renderer(actionNode("scene.movie"))
        .renderNodeById("c", Map.empty, fragments = QuerySnapshot.empty)
        .get
        .contains("""href="scene/turn_on""""),
      clue = "scene domain -> scene/turn_on"
    )
    assert(
      renderer(actionNode("light.x"))
        .renderNodeById("c", Map.empty, fragments = QuerySnapshot.empty)
        .get
        .contains("""href="homeassistant/toggle""""),
      clue = "other domain -> homeassistant/toggle"
    )
  }

  /** `onRender` is re-read but never a reason to render: the mode an author
    * wants for a friendly name or a unit.
    */
  test(
    "reads: live re-resolves and tracks, onRender re-resolves, once neither"
  ) {
    // `Reads.Once` resolves once per (entity, transform), which keeps the set
    // render path cheap. A state-reading transform, a deliberate misuse,
    // exposes the memo: its value freezes at the first render.
    def node(reads: String): LayoutNode =
      LayoutNode.Component(
        card = "act",
        slots = Map(
          "action" -> SlotSource(
            Some("sensor.t"),
            transform = "state",
            reads = reads
          )
        )
      )

    val frozen = renderer(node(Reads.Once))
    val a =
      frozen
        .renderNodeById(
          "c",
          Map("sensor.t" -> st("sensor.t", "one")),
          fragments = QuerySnapshot.empty
        )
        .get
    val b =
      frozen
        .renderNodeById(
          "c",
          Map("sensor.t" -> st("sensor.t", "two")),
          fragments = QuerySnapshot.empty
        )
        .get
    assert(a.contains("""href="one""""), clue = a)
    assertEquals(b, a) // memoized: the changed state is ignored

    val live = renderer(node(Reads.Live))
    val c1 =
      live
        .renderNodeById(
          "c",
          Map("sensor.t" -> st("sensor.t", "one")),
          fragments = QuerySnapshot.empty
        )
        .get
    val c2 =
      live
        .renderNodeById(
          "c",
          Map("sensor.t" -> st("sensor.t", "two")),
          fragments = QuerySnapshot.empty
        )
        .get
    assert(c1.contains("""href="one""""), clue = c1)
    assert(c2.contains("""href="two""""), clue = c2) // re-resolved

    val onRender = renderer(node(Reads.OnRender))
    val d1 =
      onRender
        .renderNodeById(
          "c",
          Map("sensor.t" -> st("sensor.t", "one")),
          fragments = QuerySnapshot.empty
        )
        .get
    val d2 =
      onRender
        .renderNodeById(
          "c",
          Map("sensor.t" -> st("sensor.t", "two")),
          fragments = QuerySnapshot.empty
        )
        .get
    assert(d1.contains("""href="one""""), clue = d1)
    assert(d2.contains("""href="two""""), clue = d2)

    // ...and subscribes like `once`, which is not at all: the one place the two
    // halves come apart.
    assertEquals(live.componentsFor("sensor.t"), Set[NodeId]("c"))
    assertEquals(onRender.componentsFor("sensor.t"), Set.empty[NodeId])
    assertEquals(frozen.componentsFor("sensor.t"), Set.empty[NodeId])
  }

  test("missing entity renders empty slots rather than throwing") {
    val html = renderer(card)
      .renderNodeById("c", Map.empty, fragments = QuerySnapshot.empty)
      .get
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
    // With no theme.chrome, renderPage falls back to the minimal `#dashboard`
    // frame, with no popup host.
    assertEquals(
      page,
      """<style id="fh-theme"></style><main class="container" id="dashboard"><div class="fh-cell" id="c"><div class="fh-col"><div class="fh-cell" id="c_0"><div class="fh-row"><div class="fh-cell" id="c_0_0"><button>Go</button></div></div></div></div></div></main>"""
    )
    // A container is structure: addressable as an element for remove/insert,
    // but not rendered by id, since its bytes hold its children.
    assertEquals(
      r.renderNodeById("c_0", Map.empty, fragments = QuerySnapshot.empty),
      None
    )
    assertEquals(
      r.renderNodeById("c_0_0", Map.empty, fragments = QuerySnapshot.empty).get,
      """<div class="fh-cell" id="c_0_0"><button>Go</button></div>"""
    )
  }

  test(
    "a wrapAsCell=false card renders bare: no fh-cell wrapper, no injected id wrapper"
  ) {
    // Opted out of the wrapper, so its root stays a direct child of a
    // framework-structural parent. Such a card may only carry literal or
    // identity slots (rejection test below).
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
      r.renderNodeById("c", Map.empty, fragments = QuerySnapshot.empty).get,
      """<a class="tab" data-tab="c"><span>42</span></a>"""
    )
  }

  test(
    "validate rejects the wrapper-dependent shapes on a wrapAsCell=false card"
  ) {
    // All of these fail silently at render time without the wrapper, so each is
    // a build error.
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
    val sized = LayoutNode.Component(
      "btn",
      Map("label" -> lit("Go")),
      cell = Some(Cell(classes = List("fh-cols-3", "hero")))
    )
    assertEquals(
      renderer(sized)
        .renderNodeById("c", Map.empty, fragments = QuerySnapshot.empty)
        .get,
      """<div class="fh-cell fh-cols-3 hero" id="c"><button>Go</button></div>"""
    )

    val dyn = onSet(
      List("light.a"),
      List(
        (None, "btn", Map("label" -> lit("L")), Some(Cell(List("fh-cols-4"))))
      )
    ).copy(cell = Some(Cell(classes = List("fh-cols-full"))))
    // A member container composes its members, so it has no rendering of its
    // own.
    val html = renderer(dyn).renderBody(Map("light.a" -> st("light.a", "on")))
    assertEquals(
      html,
      """<div class="fh-cell fh-group fh-cols-full" id="c">""" +
        """<div class="fh-cell fh-cols-4" id="c_light_a"><button>L</button></div></div>"""
    )
    assertEquals(
      renderer(dyn)
        .renderMemberById(
          setId("c"),
          "light.a",
          Map("light.a" -> st("light.a", "on")),
          fragments = QuerySnapshot.empty
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
    // Baked equals what the connect would patch in, so the patch that follows
    // is a no-op morph rather than a second paint.
    val baked = r.renderPage(Map.empty, popup = Some("det"))
    assert(
      baked.contains(
        s"""<div id="popups">${r
            .renderSurfaceTraced(
              "det",
              Map.empty,
              fragments = QuerySnapshot.empty
            )
            .map(_.html)
            .get}</div>"""
      ),
      clue = baked
    )
    assert(r.renderPage(Map.empty).contains("""<div id="popups"></div>"""))
    assert(
      r.renderPage(Map.empty, popup = Some("nope"))
        .contains("""<div id="popups"></div>""")
    )
  }

  test("a popup surface with nowhere to host is a warning, not an error") {
    // Both fail silently in the browser (a tap that does nothing, a dialog that
    // pops in late), so build time is the only place to attribute them.
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

    // The fallback frame has no host either.
    assert(clue(warningsOf("")).exists(_.contains("never be shown")))
    assert(
      clue(warningsOf("""<main id="dashboard">{{{body}}}</main>"""))
        .exists(_.contains("det"))
    )
    // A host but no hole works, and flashes on a refresh.
    assert(
      clue(
        warningsOf(
          """<main id="dashboard">{{{body}}}</main><div id="popups"></div>"""
        )
      ).exists(_.contains("{{{popups}}}"))
    )
    assertEquals(
      warningsOf(
        """<main id="dashboard">{{{body}}}</main><div id="popups">{{{popups}}}</div>"""
      ),
      Nil
    )
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
          transform = "'brightness' in attr ? attr['brightness'] : null",
          default = Some("0")
        )
      )
    )
    val r = renderer(g)
    val wrap =
      (inner: String) => s"""<div class="fh-cell" id="c">$inner</div>"""
    assertEquals(
      r.renderNodeById("c", Map.empty, fragments = QuerySnapshot.empty).get,
      wrap("""<i>0</i>""")
    )
    val off = Map("light.x" -> st("light.x", "off", "brightness" -> Json.Null))
    assertEquals(
      r.renderNodeById("c", off, fragments = QuerySnapshot.empty).get,
      wrap("""<i>0</i>""")
    )
    val on =
      Map("light.x" -> st("light.x", "on", "brightness" -> Json.fromInt(200)))
    assertEquals(
      r.renderNodeById("c", on, fragments = QuerySnapshot.empty).get,
      wrap("""<i>200</i>""")
    )
  }

  test("a set dispatches per clause and wraps each member on its own") {
    // The first clause whose guard holds decides the rendering.
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
                  "label" -> SlotSource(
                    transform =
                      "'friendly_name' in attr ? attr['friendly_name'] : entity_id"
                  )
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
    // Each present member is also wrapped in its own `fh-cell`, the
    // per-candidate patch target `<groupId>_<sanitized entity>`.
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
    // Absent, not hidden or blank.
    assert(!html.contains("cold"), clue = html)
    assert(!html.contains("c_sensor_c"), clue = html)
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
    // Which way presence moved is the frame's question (`syncMembers`), not one
    // change's.
    assertEquals(r.affectedSetIds(ch("light.a")), List("c"))
    // The cost claim: a frame is O(changed), not O(candidates).
    assertEquals(r.affectedSetIds(ch("sensor.z")), Nil)
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
    val html = renderer(node)
      .renderNodeById("c", Map.empty, fragments = QuerySnapshot.empty)
      .get
    assert(html.contains("<button>Hi</button>"), clue = html)
  }

  test("EntityState.javaAttributes is converted once and reused") {
    val es =
      EntityState("light.x", "on", Map("brightness" -> Json.fromInt(200)))
    // Numbers stay numeric for `attr['brightness']` reads: a Long, which
    // `double()` coerces.
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
    assert(
      // Outside #dashboard, so a body repaint need not re-send it (ADR 0011).
      page.startsWith(
        """<style id="fh-theme">:root{color-scheme:light dark;--accent-color:#000;--primary-color:#bada55;}.card{color:red}</style><main class="container" id="dashboard">"""
      ),
      clue = page
    )
    assert(!page.contains("prefers-color-scheme"), clue = page)
  }

  test("the style block layers base CSS, then cards, then the theme") {
    // Cascade order is the layering (ADR 0020): each override wins only by
    // arriving later in one <style>. The content is `pkl test`'s to assert.
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
    // The style tag is re-sent only when the styleHash moved
    // (`Server.headPatches`), so card CSS the hash missed would leave a stale
    // stylesheet.
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
    // Always emitted, as a navigate's morph target.
    assert(page.startsWith("""<style id="fh-theme"></style>"""), clue = page)
    assert(!page.contains(":root"), clue = page)
    assertEquals(Renderer.create(d).stylesheets, Nil)
  }

  test(
    "renderSurface returns bare content — no per-surface chrome (Surface's final 5 fields)"
  ) {
    // Surfaces are chrome-less: the frame a surface swaps into lives in
    // theme.chrome or the tabs card's panel host.
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
    val html = r
      .renderSurfaceTraced("detail", states, fragments = QuerySnapshot.empty)
      .map(_.html)
      .get
    assert(!html.contains("<dialog"), clue = html)
    assert(!html.contains("surface/close"), clue = html)
    assert(html.contains("<span>42</span>"), clue = html)
    assert(html.contains("""id="s_detail__c""""), clue = html)
    assert(
      r.renderNodeById("s_detail__c", states, fragments = QuerySnapshot.empty)
        .get
        .contains("<span>42</span>")
    )
    assert(
      r.componentsFor("sensor.t").isEmpty,
      clue = r.componentsFor("sensor.t")
    )
    assertEquals(
      r.surfaceComponentsFor("detail", "sensor.t"),
      Set("s_detail__c")
    )
    assertEquals(
      r.renderSurfaceTraced("nope", states, fragments = QuerySnapshot.empty)
        .map(_.html),
      None
    )
  }

  test(
    "tabs: default panel is baked into the tabs card; a panel renders without dialog chrome"
  ) {
    val rr = Renderer.create(tabsDashboard)
    val states = Map(
      "sensor.a" -> EntityState("sensor.a", "AA", Map.empty),
      "sensor.b" -> EntityState("sensor.b", "BB", Map.empty)
    )
    assertEquals(rr.surfaces.selectedSurfaces(), Set("c_t0"))

    // Surface-namespaced ids, so a later switch-back is byte-identical.
    val body = rr.renderBody(states)
    assert(
      body.contains(
        """<div id="c_panel" class="tab-panel" data-signals="{ tab_c: 0 }">"""
      ),
      clue = body
    )
    assert(body.contains("""id="s_c_t0__c""""), clue = body)
    assert(body.contains("<span>AA</span>"), clue = body)
    assert(!body.contains("<span>BB</span>"), clue = body)

    val panelB = rr
      .renderSurfaceTraced("c_t1", states, fragments = QuerySnapshot.empty)
      .map(_.html)
      .get
    assert(
      panelB.startsWith("""<div class="fh-cell" id="s_c_t1__c">"""),
      clue = panelB
    )
    assert(!panelB.contains("<dialog"), clue = panelB)
    assert(!panelB.contains("surface/close"), clue = panelB)
    assert(panelB.contains("<span>BB</span>"), clue = panelB)

    assertEquals(rr.surfaceComponentsFor("c_t0", "sensor.a"), Set("s_c_t0__c"))
    assertEquals(rr.surfaceComponentsFor("c_t1", "sensor.b"), Set("s_c_t1__c"))
  }

  test(
    "selectedSurfaces picks the uiState-indexed member; empty map == the old default"
  ) {
    val rr = Renderer.create(tabsDashboard)
    assertEquals(rr.surfaces.selectedSurfaces(Map("c" -> "1")), Set("c_t1"))
    // No selection picks index 0.
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
    val body = rr.renderBody(states, Map("c" -> "1"))
    assert(
      body.contains(
        """<div id="c_panel" class="tab-panel" data-signals="{ tab_c: 1 }">"""
      ),
      clue = body
    )
    assert(body.contains("""id="s_c_t1__c""""), clue = body)
    assert(body.contains("<span>BB</span>"), clue = body)
    assert(!body.contains("<span>AA</span>"), clue = body)
  }

  test("resolveActive parses, clamps, and warns on an off ui-state value") {
    val rr = Renderer.create(tabsDashboard)
    val outOfRange = rr.surfaces.resolveActive("c", Map("c" -> "99"))
    assertEquals(outOfRange._1, 0)
    assert(outOfRange._2.isDefined, clue = outOfRange)
    val unparseable = rr.surfaces.resolveActive("c", Map("c" -> "abc"))
    assertEquals(unparseable._1, 0)
    assert(unparseable._2.isDefined, clue = unparseable)
    assertEquals(rr.surfaces.resolveActive("c", Map("c" -> "1")), (1, None))
    assertEquals(rr.surfaces.resolveActive("c", Map.empty), (0, None))
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

  /** A region and its hole are one fact written twice, and a disagreement is
    * silent both ways: the region renders nowhere, or the hole renders empty
    * forever.
    */
  test(
    "validate: a declared region whose template places no hole is rejected"
  ) {
    def dash(card: CardDef) =
      Dashboard(Map("box" -> card), LayoutNode.Component("box"))

    // The hole is the same section spelling for both fills, so the check is
    // presence.
    val eagerMissing = dash(
      CardDef("<div>nothing</div>", regions = Map("kids" -> Region()))
    )
    assert(
      eagerMissing
        .validate()
        .exists(e => e.contains("kids") && e.contains("{{#kids}}")),
      clue = eagerMissing.validate()
    )

    val bakedWrongHole = dash(
      CardDef(
        // A raw var where the baked region needs a section: the likeliest real
        // mistake, and it renders nothing.
        "<div>{{{panel}}}</div>",
        regions = Map("panel" -> Region(Region.Baked))
      )
    )
    assert(
      bakedWrongHole
        .validate()
        .exists(e =>
          e.contains("panel") && e.contains("{{#panel}}{{{html}}}{{/panel}}")
        ),
      clue = bakedWrongHole.validate()
    )

    // Non-vacuous: the section spelling, placed for both fills, is accepted.
    assertEquals(
      dash(
        CardDef(
          """<div>{{#kids}}{{{html}}}{{/kids}}<i id="{{hostId}}">{{#panel}}{{{html}}}{{/panel}}</i></div>""",
          regions = Map("kids" -> Region(), "panel" -> Region(Region.Baked))
        )
      ).validate(),
      Nil
    )
  }

  /** Children take one wire shape, an object keyed by region name;
    * `core/node.pkl` names the default region before it emits.
    *
    * Asserted through `Dashboard`'s own decoder, the path a real
    * `dashboard.json` takes. The array form must fail: while both were legal,
    * the inline-surface hoist read only the array one and silently hoisted
    * nothing below a region-keyed node.
    */
  test("regions decode from a region object, and from nothing else") {
    def decode(regionsField: String) = io.circe.parser
      .decode[Dashboard](
        s"""{"cards":{"col":{"template":"<div>{{#children}}{{{html}}}{{/children}}</div>"},
           | "card":{"template":"<span>{{state}}</span>","slots":["state"]}},
           | "card":{"kind":"component","card":"col"$regionsField}}""".stripMargin
      )

    val leaf = """{"kind":"component","card":"card","slots":{"state":"x"}}"""

    val fromObject = decode(s""","regions":{"children":[$leaf]}""")
      .fold(e => fail(s"decode failed: $e"), identity)
    assertEquals(
      fromObject.card.asComponent.regions.keySet,
      Set(LayoutNode.DefaultRegion)
    )

    // A leaf carries no field (Pkl drops an empty one) and reads as no regions,
    // not as a region holding nothing.
    assertEquals(
      decode("")
        .fold(e => fail(s"decode failed: $e"), identity)
        .card
        .asComponent
        .regions,
      Map.empty[String, List[LayoutNode]]
    )

    assert(
      decode(s""","regions":[$leaf]""").isLeft,
      "a bare array is no longer a wire form — it must not decode silently"
    )
  }

  /** One card, two eager regions. The two claims are separable (a renderer
    * splicing both into the first hole still gives distinct ids), so both are
    * asserted.
    */
  test("two eager regions splice separately and address separately") {
    val two = CardDef(
      template = """<div><b>{{#children}}{{{html}}}{{/children}}</b>""" +
        """<i>{{#extra}}{{{html}}}{{/extra}}</i></div>""",
      regions = Map("children" -> Region(), "extra" -> Region())
    )
    def leaf(v: String) =
      LayoutNode.Component("card", slots = Map("state" -> lit(v)))

    val d = Dashboard(
      cards + ("two" -> two),
      LayoutNode.Component(
        "two",
        regions = Map(
          "children" -> List(leaf("IN-B")),
          "extra" -> List(leaf("IN-I"))
        )
      )
    )
    assertEquals(d.validate(), Nil)
    val html = Renderer.create(d).renderBody(Map.empty)

    assert(html.contains("<b><div class=\"fh-cell\""), clue = html)
    assert(
      html.matches("""(?s).*<b>.*IN-B.*</b>.*<i>.*IN-I.*</i>.*"""),
      clue = html
    )

    // The default region contributes only its index; a named one adds its name.
    assert(html.contains("""id="c_0""""), clue = html)
    assert(html.contains("""id="c_extra_0""""), clue = html)
  }

  /** A node id reaches users (`ui.<id>` tab state) and names every signal a
    * card owns, so an author can pin one: otherwise inserting a card above a
    * tab bar breaks every bookmark to a tab.
    */
  test("an authored node id replaces the derived one, descendants included") {
    val d = Dashboard(
      cards,
      col(
        LayoutNode.Component("card", slots = Map("state" -> lit("first"))),
        LayoutNode.Component(
          "col",
          regions = LayoutNode
            .kids(LayoutNode.Component("card", Map("state" -> lit("inner")))),
          id = Some("panel")
        )
      )
    )
    assertEquals(d.validate(), Nil)
    val html = Renderer.create(d).renderBody(Map.empty)

    assert(html.contains("""id="panel""""), clue = html)
    assert(html.contains("""id="panel_0""""), clue = html)
    assert(!html.contains("""id="c_1""""), clue = "c_1 was replaced by 'panel'")
    // Naming is per node, not a mode.
    assert(html.contains("""id="c_0""""), clue = html)
  }

  /** An authored id need not avoid looking like another id's child: ancestry
    * comes from `NodeAncestry`, not the id string.
    */
  test("validate: an authored id must be a plain token, used once") {
    def two(a: String, b: String) = Dashboard(
      cards,
      col(
        LayoutNode.Component("col", id = Some(a)),
        LayoutNode.Component("col", id = Some(b))
      )
    )
    assertEquals(two("detail", "detail_0").validate(), Nil)
    assertEquals(two("detail", "summary").validate(), Nil)

    assert(two("same", "same").validate().exists(_.contains("more than once")))
    assert(
      two("ok", "no-dashes").validate().exists(_.contains("plain token")),
      clue = two("ok", "no-dashes").validate()
    )
  }

  /** Asserted on the relation the runtime consults. A string-prefix test
    * answers this wrongly, and the symptom is a fragment silently dropped from
    * one client's record.
    */
  test("ancestry: a sibling whose id looks like a child is not one") {
    val d = Dashboard(
      cards,
      col(
        LayoutNode.Component(
          "col",
          regions = LayoutNode.kids(
            LayoutNode.Component("card", Map("state" -> lit("under")))
          ),
          id = Some("detail")
        ),
        // Not `detail_0`: that is what `detail`'s own child derives to, so it
        // would be a genuine duplicate for the uniqueness rule.
        LayoutNode.Component("col", id = Some("detail_x"))
      )
    )
    assertEquals(d.validate(), Nil)
    val a = Renderer.create(d).ancestry

    assert(a.under("detail_0", Set[NodeId]("detail")), clue = "real child")
    // `startsWith("detail_")` would say otherwise.
    assert(
      !a.under("detail_x", Set[NodeId]("detail")),
      clue = "a sibling must not be swallowed by its neighbour's name"
    )
    assertEquals(a.descendantsOf("detail"), Set[NodeId]("detail_0"))
  }

  /** A structural card may not bind a live entity, since its patch would carry
    * everything it holds, but it may read one through a non-reactive slot. The
    * rule keys on [[LayoutNode.Component.liveEntities]], so the cases separate
    * on the slot's own declaration.
    */
  test("structure may READ an entity, as long as the slot is not reactive") {
    def dash(mode: String) = Dashboard(
      cards + ("box" -> CardDef(
        """<div title="{{name}}">{{#children}}{{{html}}}{{/children}}</div>""",
        regions = Map("children" -> Region()),
        slots = List("name")
      )),
      LayoutNode.Component(
        "box",
        slots = Map(
          "name" -> SlotSource(
            Some("sensor.a"),
            "'friendly_name' in attr ? attr['friendly_name'] : entity_id",
            reads = mode
          )
        ),
        regions = LayoutNode.kids(
          LayoutNode.Component("card", Map("state" -> lit("x")))
        )
      )
    )

    assert(
      dash(Reads.Live).validate().exists(_.contains("never a patch target")),
      clue = dash(Reads.Live).validate()
    )

    // `once` would be accepted too, and wrong for a name: it memoizes, so a
    // rename would not show.
    val ok = dash(Reads.OnRender)
    assertEquals(ok.validate(), Nil)
    val html = Renderer
      .create(ok)
      .renderBody(
        Map(
          "sensor.a" -> st("sensor.a", "on")
            .copy(attributes =
              Map("friendly_name" -> io.circe.Json.fromString("Hall"))
            )
        )
      )
    assert(html.contains("""title="Hall""""), clue = html)
  }

  test("validate: a region name that could be read as an index is rejected") {
    def card(region: String) = Dashboard(
      Map(
        "box" -> CardDef(
          s"<div>{{#$region}}{{{html}}}{{/$region}}</div>",
          regions = Map(region -> Region())
        )
      ),
      LayoutNode.Component("box")
    )
    // `c_0_0` would be ambiguous: region "0" index 0, or index 0 then index 0?
    assert(
      card("0").validate().exists(_.contains("all digits")),
      clue = card("0").validate()
    )
    // Non-vacuous: a name merely containing digits can never be a whole index.
    assertEquals(card("tab2").validate(), Nil)
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

    val ok = Dashboard(
      cards,
      col(),
      theme = Theme(chrome = """<main id="dashboard">{{{body}}}</main>""")
    )
    assertEquals(ok.validate(), Nil)

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
    // A live SSE patch re-renders the node by id; it must bake the session's
    // selected tab, not the default.
    def panel(name: String): LayoutNode.Component =
      LayoutNode.Component(
        "card",
        slots = Map("state" -> SlotSource(Some(s"sensor.$name")))
      )
    val d = Dashboard(
      cards,
      LayoutNode.Component(
        "tabsLive",
        regions = Map(
          "bar" -> List(
            LayoutNode.Component(
              "tabsBar",
              slots = Map("title" -> SlotSource(Some("sensor.title"), "state"))
            )
          )
        )
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
    // The header is where the entity is read; the host holds regions.
    assertEquals(rr.componentsFor("sensor.title"), Set("c_bar_0"))
    assertEquals(
      rr.renderNodeById("c", states, fragments = QuerySnapshot.empty),
      None
    )

    // The contract: a live tick patches the header and carries nothing of the
    // panel.
    val patch =
      rr.renderNodeById("c_bar_0", states, fragments = QuerySnapshot.empty).get
    assertEquals(
      patch,
      """<div class="fh-cell" id="c_bar_0"><span>Live</span></div>"""
    )

    // Which member is baked lives on the document path alone, where the
    // selection still decides.
    val dflt = rr.renderBody(states)
    assert(dflt.contains("tab_c: 0"), clue = dflt)
    assert(dflt.contains("<span>AA</span>"), clue = dflt)
    assert(!dflt.contains("<span>BB</span>"), clue = dflt)

    val sel = rr.renderBody(states, Map("c" -> "1"))
    assert(sel.contains("tab_c: 1"), clue = sel)
    assert(sel.contains("<span>BB</span>"), clue = sel)
    assert(!sel.contains("<span>AA</span>"), clue = sel)
  }

  // `light.z` is a candidate no test turns on: the id-slugging case.
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
      r.renderMemberById(
        setId("c"),
        "light.a",
        states,
        fragments = QuerySnapshot.empty
      ).get,
      """<div class="fh-cell" id="c_light_a"><div><span>on</span> </div></div>"""
    )
    assertEquals(
      r.renderMemberById(
        setId("c"),
        "light.b",
        states,
        fragments = QuerySnapshot.empty
      ),
      None
    )
    assertEquals(
      r.renderMemberById(
        setId("c"),
        "light.z",
        states,
        fragments = QuerySnapshot.empty
      ),
      None
    )
    assertEquals(
      r.renderMemberById(
        setId("zzz"),
        "light.a",
        states,
        fragments = QuerySnapshot.empty
      ),
      None
    )
  }

  // An empty conjunction is vacuously true and reads no entity, so an `else`
  // needs no special casing.
  private val always: Predicate = Predicate.And(Nil)

  private def entityIs(id: String, state: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString(state), entity = Some(id))

  private val ifCards =
    // Mirrors lib/components.pkl's `If`: one baked region and no markup of its
    // own. The region's id is `Surface.hostId`.
    cards + ("ifhost" -> CardDef(
      template =
        """<div id="{{hostId}}">{{#branch}}{{{html}}}{{/branch}}</div>""",
      regions = Map("branch" -> Region(Region.Baked))
    ))

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
    // The first holding member wins, though the always-true else holds too.
    assertEquals(
      r.surfaces.resolveActiveByState("c", armedStates("armed")),
      Some(0)
    )
    assertEquals(
      r.surfaces.resolveActiveByState("c", armedStates("disarmed")),
      Some(1)
    )
  }

  test("resolveActiveByState: no member holds -> None; the host bakes empty") {
    val r = Renderer.create(ifDashboard(withElse = false))
    val states = armedStates("disarmed")
    assertEquals(r.surfaces.resolveActiveByState("c", states), None)
    // The host still renders, empty, so a branch matching later has its patch
    // target in the DOM. Through the document path: an If is structure, so
    // `renderNodeById` refuses it.
    assertEquals(
      r.renderBody(states),
      """<div class="fh-cell" id="c"><div id="c_branch"></div></div>"""
    )
    assertEquals(
      r.renderNodeById("c", states, fragments = QuerySnapshot.empty),
      None
    )
  }

  // `any`/`none`/`all` as counts over a named set, not every entity in the
  // house.
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

    // A lone entity is a lookup, so an unrelated entity's state cannot decide
    // it.
    val oneR = Renderer.create(dash(entityIs("l.a", "on")))
    assertEquals(oneR.surfaces.resolveActiveByState("c", mixed), Some(0))
    assertEquals(oneR.surfaces.resolveActiveByState("c", allOff), None)
  }

  test("state members bake by condition and never enter selectedSurfaces") {
    val r = Renderer.create(ifDashboard())
    val bodyArmed = r.renderBody(armedStates("armed"))
    assert(bodyArmed.contains("""id="s_c_then__c""""), clue = bodyArmed)
    assert(bodyArmed.contains("<span>A</span>"), clue = bodyArmed)
    assert(!bodyArmed.contains("<span>B</span>"), clue = bodyArmed)
    val bodyElse = r.renderBody(armedStates("disarmed"))
    assert(bodyElse.contains("<span>B</span>"), clue = bodyElse)
    assert(!bodyElse.contains("<span>A</span>"), clue = bodyElse)
    // State members never seed a session's open set; their liveness is the
    // shared pass's job.
    assertEquals(r.surfaces.selectedSurfaces(), Set.empty[String])
    assertEquals(r.surfaces.stateBakeOwnerIds, Set("c"))
    assertEquals(r.surfaces.userBakeOwnerIds, Set.empty[String])
    val tabs = Renderer.create(tabsDashboard)
    assertEquals(tabs.surfaces.userBakeOwnerIds, Set("c"))
    assertEquals(tabs.surfaces.stateBakeOwnerIds, Set.empty[String])
  }

  /** Without declaring the region, a template splicing `{{#children}}` reads as
    * a leaf, so it would be cached and patched while its bytes carry its
    * children. A fact about the card, so a build error.
    */
  test(
    "a card that splices children without declaring the region is rejected"
  ) {
    def dash(container: CardDef) = Dashboard(
      cards = Map(
        "box" -> container,
        "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
      ),
      card = LayoutNode.Component(
        "box",
        regions = LayoutNode.kids(
          LayoutNode.Component(
            "card",
            slots = Map("state" -> SlotSource(Some("sensor.a")))
          )
        )
      )
    )

    val undeclared =
      dash(CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"))
    assert(
      undeclared
        .validate()
        .exists(e => e.contains("box") && e.contains("children")),
      clue = undeclared.validate()
    )

    val declared = dash(
      CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      )
    )
    assertEquals(declared.validate(), Nil)
    val states = Map("sensor.a" -> st("sensor.a", "A0"))
    assertEquals(
      Renderer
        .create(declared)
        .renderNodeById("c", states, fragments = QuerySnapshot.empty),
      None
    )
    assert(
      Renderer
        .create(declared)
        .renderNodeById("c_0", states, fragments = QuerySnapshot.empty)
        .exists(_.contains("A0"))
    )
  }

  /** A node in one region carries nothing of a sibling region. A slider holding
    * member sliders is the real shape: get it wrong and the head is
    * unaddressable, so dragging a row stops updating the master until a reload.
    */
  test("a live head beside its members carries none of them") {
    val cards = Map(
      "host" -> CardDef(
        template = """{{#head}}{{{html}}}{{/head}}""" +
          """<div>{{#children}}{{{html}}}{{/children}}</div>""",
        regions = Map("head" -> Region(), "children" -> Region())
      ),
      "head" -> CardDef("""<span>{{state}}</span>""", slots = List("state")),
      // A member that is itself a container broke this when the answer was a
      // walk over what the children carried.
      "member" -> CardDef(
        template = """<div>m{{#children}}{{{html}}}{{/children}}</div>""",
        regions = Map("children" -> Region())
      )
    )
    val r = Renderer.create(
      Dashboard(
        cards,
        LayoutNode.Component(
          "host",
          regions = Map(
            "head" -> List(
              LayoutNode.Component(
                "head",
                slots = Map("state" -> SlotSource(Some("sensor.a")))
              )
            ),
            "children" -> List(LayoutNode.Component("member"))
          )
        )
      )
    )
    val states = Map("sensor.a" -> st("sensor.a", "A0"))
    val head =
      r.renderNodeById("c_head_0", states, fragments = QuerySnapshot.empty)
    assert(head.exists(_.contains("A0")), clue = head)
    assert(!head.exists(_.contains("m")), clue = head)
    assertEquals(
      r.renderNodeById("c", states, fragments = QuerySnapshot.empty),
      None
    )
  }

  test("state surfaces are transparent to visibility, user surfaces are not") {
    // Two chains off the main page, each two surfaces deep:
    //   main -> t0 (user)  -> if host -> b0 (state)
    //   main -> sx (state) -> tabs    -> u0 (user)
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
    def shown(id: String, open: Set[String]) =
      r.surfaces.visibleNode(NodeId.derived(id), open, Map.empty)

    // A state surface hides nothing, so b0 is shown exactly when the user tab
    // above it is open, which `s_b0__c` cannot say.
    assert(shown("s_b0__c", Set("t0")))
    assert(!shown("s_b0__c", Set.empty))
    assert(shown("s_u0__c", Set("u0")))
    assert(!shown("s_u0__c", Set.empty))
    assert(shown("c", Set.empty))
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
    // A member that merely ticked is found through the reverse index, not here.
    assertEquals(
      r.members.affectedSets(
        List(StateChange("s.b", Some(low("s.b")), low("s.b")))
      ),
      List("c")
    )
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
    assertEquals(
      r.members.affectedSets(
        List(StateChange("s.b", Some(low("s.b")), high("s.b")))
      ),
      List("c")
    )
    assertEquals(
      r.members.affectedSets(
        List(StateChange("s.z", Some(high("s.z")), high("s.z")))
      ),
      Nil
    )
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

  /** A live node beside the children (ADR 0012): `bar` holds the live header,
    * `children` the rest.
    */
  private val splitCards = cards + ("split" -> CardDef(
    // No `{{hostId}}`: a region needs an id only where `bakeAs` names it for
    // filling.
    template = """<div class="fh-col">{{#bar}}{{{html}}}{{/bar}}""" +
      """<div class="panel">{{#children}}{{{html}}}{{/children}}</div></div>""",
    regions = Map("bar" -> Region(), "children" -> Region())
  )) + ("bar" -> CardDef(
    """<div class="bar">{{state}}</div>""",
    slots = List("state")
  ))

  private def splitRenderer: Renderer =
    Renderer.create(
      Dashboard(
        splitCards,
        LayoutNode.Component(
          "split",
          regions = Map(
            "bar" -> List(
              LayoutNode.Component(
                "bar",
                slots = Map("state" -> SlotSource(Some("sensor.t")))
              )
            ),
            "children" -> List(
              LayoutNode.Component("btn", Map("label" -> lit("inside")))
            )
          )
        )
      )
    )

  test("the document path renders every region, each child in its own") {
    val html = splitRenderer.renderBody(Map("sensor.t" -> st("sensor.t", "21")))
    assert(html.contains("""class="fh-cell" id="c""""), clue = html)
    assert(html.contains("""class="fh-cell" id="c_bar_0""""), clue = html)
    assert(html.contains("21"), clue = html)
    assert(html.contains("inside"), clue = html)
  }

  test("a node's patch carries its own rendering alone — statement (1)") {
    val r = splitRenderer
    val states = Map("sensor.t" -> st("sensor.t", "21"))
    assertEquals(
      r.renderNodeById("c", states, fragments = QuerySnapshot.empty),
      None
    )
    val patch =
      r.renderNodeById("c_bar_0", states, fragments = QuerySnapshot.empty).get
    assert(patch.contains("""id="c_bar_0""""), clue = patch)
    assert(patch.contains("21"), clue = patch)
    assert(!patch.contains("panel"), clue = patch)
    assert(!patch.contains("inside"), clue = patch)
  }

  test("one element per node: what a patch targets is what the node IS") {
    val r = splitRenderer
    assertEquals(r.elementId("c"), "c")
    assertEquals(r.elementId("c_bar_0"), "c_bar_0")
  }

  test(
    "hostId IS Surface.hostId — the template stops deriving it separately"
  ) {
    val r = Renderer.create(tabsDashboard)
    assertEquals(r.hostId("c"), "c_panel")
    assertEquals(r.hostId("c"), r.surface("c_t0").get.hostId)
  }
}
