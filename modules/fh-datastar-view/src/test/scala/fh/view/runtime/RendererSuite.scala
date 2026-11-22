package fh.view.runtime

import fh.view.testkit.Sets

import fh.view.testkit.Slots

import fh.view.testkit.TestCard
import fh.view.testkit.Conds
import fh.view.testkit.{FixtureEntity, PklFixture}

import fh.view.fetch.FetchSnapshot
import fh.view.runtime.RendererTestOps.*

import fh.view.model.{
  SortTerm,
  Part,
  CardDef,
  Cell,
  Dashboard,
  Ix,
  LayoutNode,
  NodeId,
  Op,
  Reads,
  SetId,
  SlotType,
  Step,
  Value,
  ValueExpr,
  TemplateSlot,
  Surface,
  Theme
}
import fh.view.testkit.DashboardBuilders.{
  asComponent,
  classes,
  col,
  lit,
  row,
  st
}
import fh.view.testkit.TestIds.{setId, given}
import io.circe.Json

class RendererSuite extends munit.FunSuite {

  extension (r: Renderer)
    private def affectedSets(changes: List[StateChange]): List[SetId] =
      r.members.affectedSetsWithin(changes, _ == r.surfaces.pageRoot)
    private def affectedSetIds(change: StateChange): List[String] =
      r.affectedSets(List(change))

  private val cards = Sets.cards ++ Map(
    "card" -> TestCard(
      """<div><span>{{state}}</span> {{#unit}}{{unit}}{{/unit}}</div>"""
    ),
    "btn" -> TestCard("""<button>{{label}}</button>"""),
    "gauge" -> TestCard("""<i>{{bri}}</i>"""),
    "act" -> TestCard(
      """<a href="{{{action}}}">go</a>"""
    ),
    "col" -> TestCard(
      """<div class="fh-col">{{{children}}}</div>""",
      regions = Set("children")
    ),
    "row" -> TestCard(
      """<div class="fh-row">{{{children}}}</div>""",
      regions = Set("children")
    )
  )

  // The shipped tab bar: the panel slot's group, `c_panel_0`, hosts the choice,
  // and each tab is a Row case, `c_panel_0_<i>`.
  private def tabsDashboard: Dashboard = PklFixture.snippet(
    """card = (c.tabs) {
      |  tabs {
      |    ["A"] { t.state("sensor.a") }
      |    ["B"] { t.state("sensor.b") }
      |  }
      |}
      |""".stripMargin
  )

  /** The viewer's values with `tab` chosen on the shipped bar, `c`'s. */
  private def tabbedAt(r: Renderer, tab: String): VarEnv =
    choosing(r, "c", "tab", tab)

  extension (r: Renderer)
    private def renderTabbed(
        states: Map[String, EntityState],
        tab: String
    ): String = r.renderBody(states, vars = tabbedAt(r, tab))

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
        (Option[ValueExpr], String, Map[String, TemplateSlot], Option[Cell])
      ],
      guardOn: Boolean = true
  ): LayoutNode.Component =
    Sets.node(
      candidates = candidates,
      members = candidates.map { id =>
        id -> Sets.Member(clauses.map { case (extra, card, slots, cl) =>
          val on = Conds.cmp("state", Op.Eq, Json.fromString("on"))
          Sets.Clause(
            when = (if (guardOn) List(on) else Nil) ++ extra.toList match {
              case Nil      => None
              case g :: Nil => Some(g)
              case gs       => Some(Conds.and(gs))
            },
            node = LayoutNode.Component(
              card,
              Slots.values(
                slots.updated(
                  "entity_id",
                  TemplateSlot(ValueExpr.literal(id))
                )
              ),
              cell = cl
            )
          )
        })
      }.toMap
    )

  private val card = LayoutNode.Component(
    card = "card",
    slots = Slots.values(
      Map(
        "state" -> TemplateSlot(ValueExpr(Value.Entity(Some("sensor.t")))),
        "unit" -> TemplateSlot(
          ValueExpr(
            Value.Entity(Some("sensor.t"), Part.Whole),
            List(
              Step.Cel(
                "'unit_of_measurement' in value.attr ? value.attr['unit_of_measurement'] : ''"
              )
            )
          )
        )
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
      .renderNodeById(
        "c",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      )
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
    val node = LayoutNode.Component(
      card = "card",
      slots = Slots.values(
        Map(
          "state" -> TemplateSlot(
            ValueExpr(
              Value.Entity(
                Some("sensor.t"),
                Part.Whole,
                bypassUnavailable = true
              ),
              List(Step.Cel("str(math.round(num(value.state) * 10.0) / 10.0)"))
            )
          )
        )
      )
    )
    val r = renderer(node)
    assert(
      r.renderNodeById(
        "c",
        Shared.of(Map("sensor.t" -> st("sensor.t", "21.46"))),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get
        .contains("<span>21.5</span>")
    )
    // "unavailable" never enters CEL, which would error.
    assert(
      r.renderNodeById(
        "c",
        Shared.of(Map("sensor.t" -> st("sensor.t", "unavailable"))),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get
        .contains("<span>unavailable</span>")
    )
  }

  test("bypassUnavailable=false runs the transform even when unavailable") {
    // A label keeps the name, an action stays resolvable.
    val node = LayoutNode.Component(
      card = "card",
      slots = Slots.values(
        Map(
          "state" -> TemplateSlot(
            ValueExpr(
              Value.Entity(
                Some("sensor.t"),
                bypassUnavailable = false,
                part = Part.Whole
              ),
              List(Step.Cel("value.state + '!'"))
            )
          )
        )
      )
    )
    val r = renderer(node)
    assert(
      r.renderNodeById(
        "c",
        Shared.of(Map("sensor.t" -> st("sensor.t", "unavailable"))),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get
        .contains("<span>unavailable!</span>"),
      clue = "transform should run, not be bypassed"
    )
  }

  test("a bool slot over an entity with no value decides on the word") {
    // The comparison reads the state's text and makes a bool: over
    // `unavailable` it is false, never the word in a section.
    val off =
      Conds.cmp("state", Op.Eq, Json.fromString("off"), Some("switch.x"))
    val r = Renderer.create(
      Dashboard(
        Map(
          "flag" -> TestCard("<b>{{#off}}OFF{{/off}}{{^off}}lit{{/off}}</b>")
        ),
        LayoutNode.Component(
          card = "flag",
          slots = Slots.values(
            Map("off" -> TemplateSlot(off, `type` = SlotType.Bool))
          )
        )
      )
    )
    def shown(state: String) =
      r.renderNodeById(
        "c",
        Shared.of(Map("switch.x" -> st("switch.x", state))),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get
    assert(shown("off").contains("<b>OFF</b>"), clue = shown("off"))
    EntityState.unavailableStates.foreach { s =>
      assert(shown(s).contains("<b>lit</b>"), clue = shown(s))
    }
  }

  test("a slot shows the leader a first step picks, through a CEL step") {
    val brightest = SortTerm(
      ValueExpr(Value.Entity(None, Part.Attr("brightness"))),
      SortTerm.Dir.Desc
    )
    val leader = ValueExpr(
      Value.Present(List("light.a", "light.b")),
      List(Step.First(List(brightest)), Step.Cel("value + '!'"))
    )
    val r = Renderer.create(
      Dashboard(
        Map("lead" -> TestCard("<b>{{lead}}</b>")),
        LayoutNode.Component(
          card = "lead",
          slots = Slots.values(Map("lead" -> TemplateSlot(leader)))
        )
      )
    )
    def shown(a: Int, b: Int) =
      r.renderNodeById(
        "c",
        Shared.of(
          Map(
            "light.a" -> st("light.a", "on", "brightness" -> Json.fromInt(a)),
            "light.b" -> st("light.b", "on", "brightness" -> Json.fromInt(b))
          )
        ),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get
    assert(shown(10, 200).contains("<b>light.b!</b>"), clue = shown(10, 200))
    assert(shown(200, 10).contains("<b>light.a!</b>"), clue = shown(200, 10))
  }

  // ADR 0016 bakes actions; a hand-written map action is the fallback, and must
  // resolve from `domain` with no state: a `once` read, of which entity it is.
  test("a fallback map action resolves from the entity id with no state") {
    val expr =
      """cel.bind(m, {'scene': 'scene/turn_on'}, """ +
        """value.domain in m ? m[value.domain] : 'homeassistant/toggle')"""
    def actionNode(entity: String): LayoutNode =
      LayoutNode.Component(
        card = "act",
        slots = Slots.values(
          Map(
            "action" -> TemplateSlot(
              ValueExpr(
                Value.Entity(Some(entity), Part.Whole, Reads.Once),
                List(Step.Cel(expr))
              ),
              `type` = SlotType.TextRaw
            )
          )
        )
      )
    assert(
      renderer(actionNode("scene.movie"))
        .renderNodeById(
          "c",
          Shared.of(Map.empty),
          Viewing(Selections.none, FetchSnapshot.empty)
        )
        .get
        .contains("""href="scene/turn_on""""),
      clue = "scene domain -> scene/turn_on"
    )
    assert(
      renderer(actionNode("light.x"))
        .renderNodeById(
          "c",
          Shared.of(Map.empty),
          Viewing(Selections.none, FetchSnapshot.empty)
        )
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
    def node(reads: Reads): LayoutNode =
      LayoutNode.Component(
        card = "act",
        slots = Slots.values(
          Map(
            "action" -> TemplateSlot(
              ValueExpr(
                Value
                  .Entity(Some("sensor.t"), reads = reads, part = Part.Whole),
                List(Step.Cel("value.state"))
              ),
              `type` = SlotType.TextRaw
            )
          )
        )
      )

    val frozen = renderer(node(Reads.Once))
    val a =
      frozen
        .renderNodeById(
          "c",
          Shared.of(Map("sensor.t" -> st("sensor.t", "one"))),
          Viewing(Selections.none, FetchSnapshot.empty)
        )
        .get
    val b =
      frozen
        .renderNodeById(
          "c",
          Shared.of(Map("sensor.t" -> st("sensor.t", "two"))),
          Viewing(Selections.none, FetchSnapshot.empty)
        )
        .get
    assert(a.contains("""href="one""""), clue = a)
    assertEquals(b, a) // memoized: the changed state is ignored

    val live = renderer(node(Reads.Live))
    val c1 =
      live
        .renderNodeById(
          "c",
          Shared.of(Map("sensor.t" -> st("sensor.t", "one"))),
          Viewing(Selections.none, FetchSnapshot.empty)
        )
        .get
    val c2 =
      live
        .renderNodeById(
          "c",
          Shared.of(Map("sensor.t" -> st("sensor.t", "two"))),
          Viewing(Selections.none, FetchSnapshot.empty)
        )
        .get
    assert(c1.contains("""href="one""""), clue = c1)
    assert(c2.contains("""href="two""""), clue = c2) // re-resolved

    val onRender = renderer(node(Reads.OnRender))
    val d1 =
      onRender
        .renderNodeById(
          "c",
          Shared.of(Map("sensor.t" -> st("sensor.t", "one"))),
          Viewing(Selections.none, FetchSnapshot.empty)
        )
        .get
    val d2 =
      onRender
        .renderNodeById(
          "c",
          Shared.of(Map("sensor.t" -> st("sensor.t", "two"))),
          Viewing(Selections.none, FetchSnapshot.empty)
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
      .renderNodeById(
        "c",
        Shared.of(Map.empty),
        Viewing(Selections.none, FetchSnapshot.empty)
      )
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
      col(
        row(
          LayoutNode.Component("btn", Slots.values(Map("label" -> lit("Go"))))
        )
      )
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
      r.renderNodeById(
        "c_0",
        Shared.of(Map.empty),
        Viewing(Selections.none, FetchSnapshot.empty)
      ),
      None
    )
    assertEquals(
      r.renderNodeById(
        "c_0_0",
        Shared.of(Map.empty),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get,
      """<div class="fh-cell" id="c_0_0"><button>Go</button></div>"""
    )
  }

  // A tab anchor's root, as `core/node.pkl` writes `tag = "a"`, `layoutCell =
  // false`.
  private val anchor = CardDef(
    """<a class="tab{{{cellClasses}}}"{{{cell}}} data-tab="{{id}}"><span>{{state}}</span></a>""",
    layoutCell = false
  )

  test(
    "a layoutCell=false card's root is the card's own element, with its id"
  ) {
    val d = Dashboard(
      cards + ("naked" -> anchor),
      LayoutNode.Component(
        "naked",
        slots = Slots.values(Map("state" -> lit("42"))),
        cell = Some(classes("hero"))
      )
    )
    assertEquals(d.validate(), Nil)
    val r = Renderer.create(d)
    assertEquals(
      r.renderNodeById(
        "c",
        Shared.of(Map.empty),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get,
      """<a class="tab hero" id="c" data-tab="c"><span>42</span></a>"""
    )
  }

  test(
    "a layoutCell=false card refuses only layout classes: its root keeps its id"
  ) {
    val bareCards = cards + ("naked" -> anchor)
    val sized = Dashboard(
      bareCards,
      LayoutNode.Component(
        "naked",
        slots = Slots.values(Map("state" -> lit("42"))),
        cell = Some(Cell(layout = List("fh-cols-3")))
      )
    )
    assert(
      sized.validate().exists(_.contains("layoutCell=false")),
      clue = sized.validate()
    )
    // A live slot has its morph target and a set clause its patch target.
    val live = Dashboard(
      bareCards,
      LayoutNode.Component(
        "naked",
        slots = Slots.values(
          Map(
            "state" -> TemplateSlot(
              ValueExpr(Value.Entity(Some("sensor.t")))
            )
          )
        )
      )
    )
    assertEquals(live.validate(), Nil)
    val clause = Dashboard(
      bareCards,
      onSet(
        List("light.a"),
        List((None, "naked", Map("state" -> lit("x")), None))
      )
    )
    assertEquals(clause.validate(), Nil)
  }

  test("validate refuses a card whose template does not write the root") {
    val d = Dashboard(
      cards + ("bare" -> CardDef("<a>{{state}}</a>")),
      LayoutNode.Component(
        "bare",
        slots = Slots.values(Map("state" -> lit("42")))
      )
    )
    assert(
      d.validate().exists(_.contains("{{{cell}}} exactly once")),
      clue = d.validate()
    )
  }

  test("authored cell classes ride on every wrapper kind") {
    val sized = LayoutNode.Component(
      "btn",
      Slots.values(Map("label" -> lit("Go"))),
      cell = Some(classes("fh-cols-3", "hero"))
    )
    assertEquals(
      renderer(sized)
        .renderNodeById(
          "c",
          Shared.of(Map.empty),
          Viewing(Selections.none, FetchSnapshot.empty)
        )
        .get,
      """<div class="fh-cell fh-cols-3 hero" id="c"><button>Go</button></div>"""
    )

    val dyn = onSet(
      List("light.a"),
      List(
        (None, "btn", Map("label" -> lit("L")), Some(classes("fh-cols-4")))
      )
    ).copy(cell = Some(classes("fh-cols-full")))
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
          Shared.of(Map("light.a" -> st("light.a", "on"))),
          fragments = FetchSnapshot.empty
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
      col(LayoutNode.Component("btn", Slots.values(Map("label" -> lit("Go"))))),
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
      col(LayoutNode.Component("btn", Slots.values(Map("label" -> lit("Go"))))),
      surfaces = Map(
        "det" -> Surface(
          LayoutNode.Component("btn", Slots.values(Map("label" -> lit("D"))))
        )
      ),
      theme = Theme(chrome =
        """<main id="dashboard">{{{body}}}</main><div id="popups">{{{popups}}}</div>"""
      )
    )
    val r = Renderer.create(d)
    // Baked equals what the connect would patch in, so the patch that follows
    // is a no-op morph rather than a second paint.
    val baked = r.renderPage(Map.empty, popupOpen("det"))
    assert(
      baked.contains(
        s"""<div id="popups">${r
            .renderSurfaceTraced(
              "det",
              Shared.of(Map.empty),
              Viewing(Selections.none, FetchSnapshot.empty)
            )
            .map(_.html)
            .get}</div>"""
      ),
      clue = baked
    )
    assert(r.renderPage(Map.empty).contains("""<div id="popups"></div>"""))
    assert(
      r.renderPage(Map.empty, Selections(r.surfaces.openPopup(Some("nope"))))
        .contains("""<div id="popups"></div>""")
    )
  }

  test("a popup surface with nowhere to host is a warning, not an error") {
    // Both fail silently in the browser (a tap that does nothing, a dialog that
    // pops in late), so build time is the only place to attribute them.
    val popup = Map(
      "det" -> Surface(
        LayoutNode.Component("btn", Slots.values(Map("label" -> lit("D"))))
      )
    )
    val body =
      col(LayoutNode.Component("btn", Slots.values(Map("label" -> lit("Go")))))
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
      slots = Slots.values(
        Map(
          "bri" -> TemplateSlot(
            ValueExpr(
              Value.Entity(Some("light.x"), Part.Whole),
              List(
                Step.Cel(
                  "'brightness' in value.attr ? value.attr['brightness'] : null"
                )
              ),
              default = Some(fh.view.model.Lit.Text("0"))
            )
          )
        )
      )
    )
    val r = renderer(g)
    val wrap =
      (inner: String) => s"""<div class="fh-cell" id="c">$inner</div>"""
    assertEquals(
      r.renderNodeById(
        "c",
        Shared.of(Map.empty),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get,
      wrap("""<i>0</i>""")
    )
    val off = Map("light.x" -> st("light.x", "off", "brightness" -> Json.Null))
    assertEquals(
      r.renderNodeById(
        "c",
        Shared.of(off),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get,
      wrap("""<i>0</i>""")
    )
    val on =
      Map("light.x" -> st("light.x", "on", "brightness" -> Json.fromInt(200)))
    assertEquals(
      r.renderNodeById(
        "c",
        Shared.of(on),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get,
      wrap("""<i>200</i>""")
    )
  }

  test("a set dispatches per clause and wraps each member on its own") {
    // The first clause whose guard holds decides the rendering.
    def low = Conds.cmp("attr:battery", Op.Lt, Json.fromInt(20))
    val set = Sets.node(
      candidates = List("light.a", "sensor.b", "sensor.c"),
      members = Map(
        "light.a" -> Sets.Member(
          List(
            Sets.Clause(
              Some(low),
              LayoutNode.Component(
                "btn",
                Slots.values(
                  Map(
                    "entity_id" -> lit("light.a"),
                    "label" -> TemplateSlot(
                      ValueExpr(
                        Value.Entity(Some("light.a"), Part.Whole),
                        List(
                          Step.Cel(
                            "'friendly_name' in value.attr ? value.attr['friendly_name'] : value.entity_id"
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        ),
        "sensor.b" -> Sets.Member(
          List(
            Sets.Clause(
              Some(low),
              LayoutNode.Component(
                "card",
                Slots.values(
                  Map(
                    "entity_id" -> lit("sensor.b"),
                    "state" -> TemplateSlot(
                      ValueExpr(Value.Entity(Some("sensor.b")))
                    )
                  )
                )
              )
            )
          )
        ),
        "sensor.c" -> Sets.Member(
          List(
            Sets.Clause(
              Some(low),
              LayoutNode.Component(
                "card",
                Slots.values(
                  Map(
                    "entity_id" -> lit("sensor.c"),
                    "state" -> TemplateSlot(
                      ValueExpr(Value.Entity(Some("sensor.c")))
                    )
                  )
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
        StateChange.Moved("light.a", None, states("light.a"))
      ),
      List("c")
    )
  }

  test(
    "a member whose first clause reads an attribute its entity lacks shows the second"
  ) {
    // An attribute the entity does not report is no value, which does not
    // hold: the chain goes on to the next clause, not out of the set.
    def bright = Conds.cmp("attr:brightness", Op.Gt, Json.fromInt(10))
    val r = renderer(
      onSet(
        List("light.a", "light.b"),
        List(
          (
            Some(bright),
            "card",
            Map("state" -> TemplateSlot(ValueExpr(Value.Entity(None)))),
            None
          ),
          (
            None,
            "btn",
            Map("label" -> lit("plain")),
            None
          )
        )
      )
    )
    val html = r.renderBody(
      Map(
        "light.a" -> st("light.a", "on", "brightness" -> Json.fromInt(200)),
        "light.b" -> st("light.b", "on")
      )
    )
    assert(
      html.contains("""id="c_light_a"><div><span>on</span>"""),
      clue = html
    )
    assert(
      html.contains("""id="c_light_b"><button>plain</button>"""),
      clue = html
    )
  }

  test("affectedSetIds selects a set only for an entity it reads") {
    val r = renderer(
      onSet(
        List("light.a", "light.b"),
        List(
          (
            None,
            "card",
            Map("state" -> TemplateSlot(ValueExpr(Value.Entity(None)))),
            None
          )
        )
      )
    )
    def ch(id: String) = StateChange.Moved(id, None, st(id, "on"))
    // Which way presence moved is the frame's question (`deltas`), not one
    // change's.
    assertEquals(r.affectedSetIds(ch("light.a")), List("c"))
    // The cost claim: a frame is O(changed), not O(candidates).
    assertEquals(r.affectedSetIds(ch("sensor.z")), Nil)
    assertEquals(
      r.affectedSets(List(ch("light.a"), ch("light.b"))),
      List("c")
    )
  }

  test(
    "a constant slot (no entityId) resolves its literal against empty state"
  ) {
    val node = LayoutNode.Component(
      card = "btn",
      slots = Slots.values(
        Map("label" -> TemplateSlot(ValueExpr(Value.Cel("\"Hi\""))))
      )
    )
    val html = renderer(node)
      .renderNodeById(
        "c",
        Shared.of(Map.empty),
        Viewing(Selections.none, FetchSnapshot.empty)
      )
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
    assertEquals[Any, Any](es.javaAttributes.get("brightness"), 200L)
  }

  test("one attribute reads as the whole map holds it, built or not") {
    val attrs = Map(
      "n" -> Json.fromInt(200),
      "r" -> Json.fromDoubleOrNull(2.5),
      "s" -> Json.fromString("x"),
      "b" -> Json.True,
      "z" -> Json.Null,
      "l" -> Json.arr(Json.fromInt(1), Json.fromString("a")),
      "o" -> Json.obj("k" -> Json.fromInt(1))
    )
    val names = attrs.keys.toList :+ "missing"
    val fresh = EntityState("light.x", "on", attrs)
    val before = names.map(fresh.attribute)
    val built = EntityState("light.x", "on", attrs)
    val map = built.javaAttributes
    assertEquals[Any, Any](before, names.map(n => map.get(n)))
    assertEquals[Any, Any](names.map(built.attribute), before)
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
        "b" -> TestCard("<b></b>", css = ".b{color:blue}"),
        "a" -> TestCard("<i></i>", css = ".a{color:green}")
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
      Map("a" -> TestCard("<i></i>")),
      LayoutNode.Component(card = "a")
    )
    val styled =
      base.copy(cards =
        Map("a" -> TestCard("<i></i>", css = ".a{color:green}"))
      )
    assertNotEquals(
      Renderer.create(base).styleHash,
      Renderer.create(styled).styleHash
    )
    assertNotEquals(
      Renderer.create(base).styleHash,
      Renderer.create(base.copy(css = ":root{--fh-accent:teal}")).styleHash
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
            slots = Slots.values(
              Map(
                "state" -> TemplateSlot(
                  ValueExpr(Value.Entity(Some("sensor.t")))
                )
              )
            )
          )
        )
      )
    )
    val r = Renderer.create(d)
    val states = Map("sensor.t" -> EntityState("sensor.t", "42", Map.empty))
    val html = r
      .renderSurfaceTraced(
        "detail",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      )
      .map(_.html)
      .get
    assert(!html.contains("<dialog"), clue = html)
    assert(!html.contains("surface/close"), clue = html)
    assert(html.contains("<span>42</span>"), clue = html)
    assert(html.contains("""id="s_detail__c""""), clue = html)
    assert(
      r.renderNodeById(
        "s_detail__c",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get
        .contains("<span>42</span>")
    )
    assert(
      r.componentsFor("sensor.t").isEmpty,
      clue = r.componentsFor("sensor.t")
    )
    assertEquals(
      r.surfaceComponentsFor(
        r.surfaces
          .containersSpelled(r.surfaces.popupSpelling("detail").get)
          .head,
        "sensor.t"
      ),
      Set("s_detail__c")
    )
    assertEquals(
      r.renderSurfaceTraced(
        "nope",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).map(_.html),
      None
    )
  }

  test(
    "tabs: the chosen panel is rendered into the tabs card; a panel renders without dialog chrome"
  ) {
    val rr = Renderer.create(tabsDashboard)
    val states = Map(
      "sensor.a" -> EntityState("sensor.a", "AA", Map.empty),
      "sensor.b" -> EntityState("sensor.b", "BB", Map.empty)
    )
    assertEquals(
      rr.surfaces.chosen(Shared.of(states).viewing(tabbedAt(rr, "0"))),
      rr.surfaces.openOf(Set("c_panel_0_0"))
    )

    // Surface-namespaced ids, so a later switch-back is byte-identical.
    val body = rr.renderTabbed(states, "0")
    assert(
      body.contains("""<div class="fh-tab-panel" id="c_panel_0">"""),
      clue = body
    )
    assert(body.contains("""id="c_panel_0_0""""), clue = body)
    assert(body.contains("<span>AA</span>"), clue = body)
    assert(!body.contains("<span>BB</span>"), clue = body)

    val panelB = rr
      .renderCaseTraced(
        "c_panel_0",
        1,
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      )
      .map(_._2.html)
      .get
    assert(
      panelB.startsWith("""<div class="fh-cell fh-row" id="c_panel_0_1">"""),
      clue = panelB
    )
    assert(!panelB.contains("<dialog"), clue = panelB)
    assert(!panelB.contains("surface/close"), clue = panelB)
    assert(panelB.contains("<span>BB</span>"), clue = panelB)

    assertEquals(
      rr.surfaceComponentsFor(
        rr.surfaces.containersSpelled("c_panel_0_0").head,
        "sensor.a"
      ),
      Set("c_panel_0_0_0")
    )
    assertEquals(
      rr.surfaceComponentsFor(
        rr.surfaces.containersSpelled("c_panel_0_1").head,
        "sensor.b"
      ),
      Set("c_panel_0_1_0")
    )
  }

  test(
    "chosen picks the chosen member; none chosen is none"
  ) {
    val rr = Renderer.create(tabsDashboard)
    assertEquals(
      rr.surfaces.chosen(Shared.of(Map.empty).viewing(tabbedAt(rr, "1"))),
      rr.surfaces.openOf(Set("c_panel_0_1"))
    )
    // A session's values are filled by every declaration; no entry shows
    // nothing.
    assert(
      rr.surfaces.chosen(Shared.of(Map.empty).viewing(Map.empty)).isEmpty
    )
    // A tab is chosen, never opened: nothing joins a session's open set.
    assertEquals(
      rr.surfaces.selectedSurfaces(Selections.none),
      Set.empty[String]
    )
  }

  test(
    "render of a tabs component with a chosen member renders that tab"
  ) {
    val rr = Renderer.create(tabsDashboard)
    val states = Map(
      "sensor.a" -> EntityState("sensor.a", "AA", Map.empty),
      "sensor.b" -> EntityState("sensor.b", "BB", Map.empty)
    )
    val body = rr.renderTabbed(states, "1")
    assert(
      body.contains("""<div class="fh-tab-panel" id="c_panel_0">"""),
      clue = body
    )
    assert(body.contains("""id="c_panel_0_1""""), clue = body)
    assert(body.contains("<span>BB</span>"), clue = body)
    assert(!body.contains("<span>AA</span>"), clue = body)
  }

  test("decideByVariable reads the chosen member, and none when none") {
    val rr = Renderer.create(tabsDashboard)
    assertEquals(
      rr.surfaces.decideByVariable(
        "c_panel_0",
        Shared.of(Map.empty).viewing(tabbedAt(rr, "1"))
      ),
      Some(1)
    )
    assertEquals(
      rr.surfaces.decideByVariable(
        "c_panel_0",
        Shared.of(Map.empty).viewing(Map.empty)
      ),
      None
    )
  }

  test(
    "renderBody is the shell-less body (what a navigate swap inner-patches)"
  ) {
    val r =
      renderer(
        col(
          row(
            LayoutNode.Component("btn", Slots.values(Map("label" -> lit("Go"))))
          )
        )
      )
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

    // The hole is the same raw spelling for both fills, so the check is
    // presence.
    val eagerMissing = dash(
      TestCard("<div>nothing</div>", regions = Set("kids"))
    )
    assert(
      eagerMissing
        .validate()
        .exists(e => e.contains("kids") && e.contains("{{{kids}}}")),
      clue = eagerMissing.validate()
    )

    val bakedWrongHole = dash(
      TestCard(
        // A section where the hole goes: what an older workspace card has.
        "<div>{{#panel}}{{{html}}}{{/panel}}</div>",
        regions = Set("panel")
      )
    )
    assert(
      bakedWrongHole
        .validate()
        .exists(e => e.contains("panel") && e.contains("write {{{panel}}}")),
      clue = bakedWrongHole.validate()
    )

    // Non-vacuous: the raw hole, placed for both fills, is accepted.
    assertEquals(
      dash(
        TestCard(
          """<div>{{{kids}}}<i>{{{panel}}}</i></div>""",
          regions = Set("kids", "panel")
        )
      ).validate(),
      Nil
    )
  }

  /** Children take one wire shape, a nodes slot in the node's `wireSlots`
    * object; `core/node.pkl` names the default slot before it emits.
    *
    * Asserted through `Dashboard`'s own decoder, the path a real
    * `dashboard.json` takes. A bare array must fail: while both were legal, the
    * inline-surface hoist read only the array one and silently hoisted nothing
    * below a slot-keyed node.
    */
  test("nodes slots decode from a slot object, and from nothing else") {
    def decode(slotsField: String) = io.circe.parser
      .decode[Dashboard](
        s"""{"cards":{"col":{"template":"<div>{{{children}}}</div>"},
           | "card":{"template":"<span>{{state}}</span>","slots":["state"]}},
           | "card":{"kind":"Component","card":"col"$slotsField}}""".stripMargin
      )

    val leaf =
      """{"kind":"Component","card":"card","wireSlots":{"state":{"kind":"Value","source":"x"}}}"""

    val fromObject = decode(
      s""","wireSlots":{"children":{"kind":"Nodes","fill":{"kind":"Nodes","choice":[$leaf]}}}"""
    )
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
      decode(s""","wireSlots":[$leaf]""").isLeft,
      "a bare array is no longer a wire form — it must not decode silently"
    )
  }

  /** One card, two eager regions. The two claims are separable (a renderer
    * splicing both into the first hole still gives distinct ids), so both are
    * asserted.
    */
  test("two eager regions splice separately and address separately") {
    val two = TestCard(
      template = """<div><b>{{{children}}}</b>""" +
        """<i>{{{extra}}}</i></div>""",
      regions = Set("children", "extra")
    )
    def leaf(v: String) =
      LayoutNode.Component("card", slots = Slots.values(Map("state" -> lit(v))))

    val d = Dashboard(
      cards + ("two" -> two),
      LayoutNode.Component(
        "two",
        slots = Slots.nodes(
          Map(
            "children" -> List(leaf("IN-B")),
            "extra" -> List(leaf("IN-I"))
          )
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
        LayoutNode
          .Component(
            "card",
            slots = Slots.values(Map("state" -> lit("first")))
          ),
        LayoutNode.Component(
          "col",
          slots = Slots.kids(
            LayoutNode
              .Component("card", Slots.values(Map("state" -> lit("inner"))))
          ),
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

  /** An authored id need not avoid looking like another id's child: containment
    * comes from the tree index, not the id string.
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
  test("containment: a sibling whose id looks like a child is not one") {
    val d = Dashboard(
      cards,
      col(
        LayoutNode.Component(
          "col",
          slots = Slots.kids(
            LayoutNode
              .Component("card", Slots.values(Map("state" -> lit("under"))))
          ),
          id = Some("detail")
        ),
        // Not `detail_0`: that is what `detail`'s own child derives to, so it
        // would be a genuine duplicate for the uniqueness rule.
        LayoutNode.Component("col", id = Some("detail_x"))
      )
    )
    assertEquals(d.validate(), Nil)
    val index = Renderer.create(d).index
    def ix(id: NodeId): Ix = index.ixsOf(id).head

    assert(index.contains(ix("detail"), ix("detail_0")), clue = "real child")
    // `startsWith("detail_")` would say otherwise.
    assert(
      !index.contains(ix("detail"), ix("detail_x")),
      clue = "a sibling must not be swallowed by its neighbour's name"
    )
    assertEquals(
      index.all.filter(index.contains(ix("detail"), _)).map(index.id).toSet,
      Set[NodeId]("detail", "detail_0")
    )
  }

  /** A structural card may not bind a live entity, since its patch would carry
    * everything it holds, but it may read one through a non-reactive slot. The
    * rule keys on [[LayoutNode.Component.liveEntities]], so the cases separate
    * on the slot's own declaration.
    */
  test("structure may READ an entity, as long as the slot is not reactive") {
    def dash(mode: Reads) = Dashboard(
      cards + ("box" -> TestCard(
        """<div title="{{name}}">{{{children}}}</div>""",
        regions = Set("children")
      )),
      LayoutNode.Component(
        "box",
        slots = Slots.values(
          Map(
            "name" -> TemplateSlot(
              ValueExpr(
                Value.Entity(Some("sensor.a"), reads = mode, part = Part.Whole),
                List(
                  Step.Cel(
                    "'friendly_name' in value.attr ? value.attr['friendly_name'] : value.entity_id"
                  )
                )
              )
            )
          )
        ) ++ Slots.kids(
          LayoutNode.Component("card", Slots.values(Map("state" -> lit("x"))))
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
        "box" -> TestCard(
          s"<div>{{{$region}}}</div>",
          regions = Set(region)
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
    "a live pick host patches its header alone; the case is document-path only"
  ) {
    // A live SSE patch re-renders the node by id; it must not carry the
    // session's tab.
    val d = PklFixture.snippet(
      """card = (c.tabs) {
        |  tabs { ["A"] { t.state("sensor.a") } ["B"] { t.state("sensor.b") } }
        |  children { t.state("sensor.title") }
        |}
        |""".stripMargin
    )
    val rr = Renderer.create(d)
    val states = Map(
      "sensor.title" -> st("sensor.title", "Live"),
      "sensor.a" -> st("sensor.a", "AA"),
      "sensor.b" -> st("sensor.b", "BB")
    )
    // The header is where the entity is read; the host holds regions.
    assertEquals(rr.componentsFor("sensor.title"), Set("c_2"))
    assertEquals(
      rr.renderNodeById(
        "c",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      ),
      None
    )

    // The contract: a live tick patches the header and carries nothing of the
    // panel.
    val patch =
      rr.renderNodeById(
        "c_2",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get
    assertEquals(
      patch,
      """<div class="fh-cell" id="c_2"><span>Live</span></div>"""
    )

    // Which case shows lives on the document path alone, where the
    // selection still decides.
    val dflt = rr.renderTabbed(states, "0")
    assert(dflt.contains("<span>AA</span>"), clue = dflt)
    assert(!dflt.contains("<span>BB</span>"), clue = dflt)

    val sel = rr.renderTabbed(states, "1")
    assert(sel.contains("<span>BB</span>"), clue = sel)
    assert(!sel.contains("<span>AA</span>"), clue = sel)
  }

  // `light.z` is a candidate no test turns on: the id-slugging case.
  private val onGroup = onSet(
    List("light.a", "light.b", "light.c", "light-b.x", "light.z"),
    List(
      (
        None,
        "card",
        Map("state" -> TemplateSlot(ValueExpr(Value.Entity(None)))),
        None
      )
    )
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
      r.members.memberEntities(setId("c"), Shared.of(states)),
      List("light.a", "light.b")
    )
    assertEquals(r.members.memberEntities(setId("zzz"), Shared.of(states)), Nil)
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
        Shared.of(states),
        fragments = FetchSnapshot.empty
      ).get,
      """<div class="fh-cell" id="c_light_a"><div><span>on</span> </div></div>"""
    )
    assertEquals(
      r.renderMemberById(
        setId("c"),
        "light.b",
        Shared.of(states),
        fragments = FetchSnapshot.empty
      ),
      None
    )
    assertEquals(
      r.renderMemberById(
        setId("c"),
        "light.z",
        Shared.of(states),
        fragments = FetchSnapshot.empty
      ),
      None
    )
    assertEquals(
      r.renderMemberById(
        setId("zzz"),
        "light.a",
        Shared.of(states),
        fragments = FetchSnapshot.empty
      ),
      None
    )
  }

  /** The shipped `If` at "c" over `alarm.h`: `then` on `sensor.a`, and, when
    * asked, `else` on `sensor.b`.
    */
  private def ifDashboard(withElse: Boolean = true): Dashboard =
    PklFixture.snippet(
      s"""card = c.iff(q.entity(dump.entities.alarm_h).stateIs("armed")).then(t.state("sensor.a"))${
          if (withElse) """.`else`(t.state("sensor.b"))""" else ""
        }
         |""".stripMargin,
      FixtureEntity("alarm.h", "armed")
    )

  private def armedStates(alarm: String) = Map(
    "alarm.h" -> st("alarm.h", alarm),
    "sensor.a" -> st("sensor.a", "A"),
    "sensor.b" -> st("sensor.b", "B")
  )

  test(
    "decideByState picks the case `by` reads, else the otherwise"
  ) {
    val r = Renderer.create(ifDashboard())
    assertEquals(
      r.surfaces.decideByState("c", Shared.of(armedStates("armed"))),
      Some(0)
    )
    assertEquals(
      r.surfaces.decideByState("c", Shared.of(armedStates("disarmed"))),
      Some(1)
    )
  }

  test("decideByState: no member holds -> None; the host is empty") {
    val r = Renderer.create(ifDashboard(withElse = false))
    val states = armedStates("disarmed")
    assertEquals(r.surfaces.decideByState("c", Shared.of(states)), None)
    // The host still renders, empty, so a branch matching later has its patch
    // target in the DOM. Through the document path: an If is structure, so
    // `renderNodeById` refuses it.
    assertEquals(
      r.renderBody(states),
      """<div class="fh-cell fh-ifhost fh-cols-full" id="c"></div>"""
    )
    assertEquals(
      r.renderNodeById(
        "c",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      ),
      None
    )
  }

  // `any`/`none`/`all` as counts over a named set, not every entity in the
  // house.
  test("a state condition counts a named set: any/none/all as comparisons") {
    val lights = "q.from(List(dump.entities.l_a, dump.entities.l_b))"
    def dash(cond: String) = PklFixture.snippet(
      s"""card = c.iff($cond).then(t.label("x"))""",
      FixtureEntity("l.a", "on"),
      FixtureEntity("l.b", "on")
    )
    val onSet = s"""$lights.where(q.eq(q.stateProp, "on"))"""
    val mixed = Map("l.a" -> st("l.a", "on"), "l.b" -> st("l.b", "off"))
    val allOn = Map("l.a" -> st("l.a", "on"), "l.b" -> st("l.b", "on"))
    val allOff = Map("l.a" -> st("l.a", "off"), "l.b" -> st("l.b", "off"))

    val anyR = Renderer.create(dash(s"$onSet.any()"))
    assertEquals(
      anyR.surfaces.decideByState("c", Shared.of(mixed)),
      Some(0)
    )
    assertEquals(
      anyR.surfaces.decideByState("c", Shared.of(allOff)),
      None
    )

    val noneR = Renderer.create(dash(s"$onSet.none()"))
    assertEquals(
      noneR.surfaces.decideByState("c", Shared.of(allOff)),
      Some(0)
    )
    assertEquals(
      noneR.surfaces.decideByState("c", Shared.of(mixed)),
      None
    )

    val allR = Renderer.create(dash(s"$onSet.all()"))
    assertEquals(
      allR.surfaces.decideByState("c", Shared.of(allOn)),
      Some(0)
    )
    assertEquals(
      allR.surfaces.decideByState("c", Shared.of(mixed)),
      None
    )

    // A lone entity is a lookup, so an unrelated entity's state cannot decide
    // it.
    val oneR = Renderer.create(
      dash("""q.entity(dump.entities.l_a).stateIs("on")""")
    )
    assertEquals(
      oneR.surfaces.decideByState("c", Shared.of(mixed)),
      Some(0)
    )
    assertEquals(
      oneR.surfaces.decideByState("c", Shared.of(allOff)),
      None
    )
  }

  test(
    "a choice over state shows by condition and never enter selectedSurfaces"
  ) {
    val r = Renderer.create(ifDashboard())
    val bodyArmed = r.renderBody(armedStates("armed"))
    assert(bodyArmed.contains("""id="c_branch_0""""), clue = bodyArmed)
    assert(bodyArmed.contains("<span>A</span>"), clue = bodyArmed)
    assert(!bodyArmed.contains("<span>B</span>"), clue = bodyArmed)
    val bodyElse = r.renderBody(armedStates("disarmed"))
    assert(bodyElse.contains("<span>B</span>"), clue = bodyElse)
    assert(!bodyElse.contains("<span>A</span>"), clue = bodyElse)
    // State members never seed a session's open set; their liveness is the
    // shared pass's job.
    assertEquals(r.surfaces.selectedSurfaces(), Set.empty[String])
    assertEquals(r.surfaces.statePicks, Set("c"))
    assertEquals(r.surfaces.varPicks, Set.empty[String])
    val tabs = Renderer.create(tabsDashboard)
    assertEquals(tabs.surfaces.varPicks, Set("c_panel_0"))
    assertEquals(tabs.surfaces.statePicks, Set.empty[String])
  }

  /** Without declaring the region, a template splicing `{{#children}}` reads as
    * a leaf, so it would be cached and patched while its bytes carry its
    * children. A fact about the card, so a build error.
    */
  test(
    "a card that splices children without declaring the region is rejected"
  ) {
    def dash(container: CardDef) = Dashboard(
      cards = Sets.cards ++ Map(
        "box" -> container,
        "card" -> TestCard("<span>{{state}}</span>")
      ),
      card = LayoutNode.Component(
        "box",
        slots = Slots.kids(
          LayoutNode.Component(
            "card",
            slots = Slots.values(
              Map(
                "state" -> TemplateSlot(
                  ValueExpr(Value.Entity(Some("sensor.a")))
                )
              )
            )
          )
        )
      )
    )

    val undeclared =
      dash(TestCard("<div>{{{children}}}</div>"))
    assert(
      undeclared
        .validate()
        .exists(e => e.contains("box") && e.contains("children")),
      clue = undeclared.validate()
    )

    val declared = dash(
      TestCard(
        "<div>{{{children}}}</div>",
        regions = Set("children")
      )
    )
    assertEquals(declared.validate(), Nil)
    val states = Map("sensor.a" -> st("sensor.a", "A0"))
    assertEquals(
      Renderer
        .create(declared)
        .renderNodeById(
          "c",
          Shared.of(states),
          Viewing(Selections.none, FetchSnapshot.empty)
        ),
      None
    )
    assert(
      Renderer
        .create(declared)
        .renderNodeById(
          "c_0",
          Shared.of(states),
          Viewing(Selections.none, FetchSnapshot.empty)
        )
        .exists(_.contains("A0"))
    )
  }

  /** A node in one region carries nothing of a sibling region. A slider holding
    * member sliders is the real shape: get it wrong and the head is
    * unaddressable, so dragging a row stops updating the master until a reload.
    */
  test("a live head beside its members carries none of them") {
    val cards = Sets.cards ++ Map(
      "host" -> TestCard(
        template = """{{{head}}}""" +
          """<div>{{{children}}}</div>""",
        regions = Set("head", "children")
      ),
      "head" -> TestCard("""<span>{{state}}</span>"""),
      // A member that is itself a container broke this when the answer was a
      // walk over what the children carried.
      "member" -> TestCard(
        template = """<div>m{{{children}}}</div>""",
        regions = Set("children")
      )
    )
    val r = Renderer.create(
      Dashboard(
        cards,
        LayoutNode.Component(
          "host",
          slots = Slots.nodes(
            Map(
              "head" -> List(
                LayoutNode.Component(
                  "head",
                  slots = Slots.values(
                    Map(
                      "state" -> TemplateSlot(
                        ValueExpr(Value.Entity(Some("sensor.a")))
                      )
                    )
                  )
                )
              ),
              "children" -> List(LayoutNode.Component("member"))
            )
          )
        )
      )
    )
    val states = Map("sensor.a" -> st("sensor.a", "A0"))
    val head =
      r.renderNodeById(
        "c_head_0",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      )
    assert(head.exists(_.contains("A0")), clue = head)
    assert(!head.exists(_.contains("m")), clue = head)
    assertEquals(
      r.renderNodeById(
        "c",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      ),
      None
    )
  }

  test("state surfaces are transparent to visibility, user surfaces are not") {
    // Two chains off the main page, each two surfaces deep:
    //   main -> c_0_panel_0_0 (tab) -> if host -> b0 (state)
    //   main -> c_1_branch_0 (state) -> tabs    -> u0 (tab)
    val d = PklFixture.snippet(
      """card = (c.column) {
        |  children {
        |    (c.tabs) { tabs { ["A"] { c.iff(true).then(t.state("s.a")) } } }
        |    c.iff(true).then((c.tabs) { tabs { ["A"] { t.state("s.b") } } })
        |  }
        |}
        |""".stripMargin
    )
    val r = Renderer.create(d)
    def shown(id: String, open: Set[String]) =
      r.surfaces.visibleNode(
        NodeId.derived(id),
        r.surfaces.openOf(open),
        Shared.of(Map.empty)
      )

    // A state case hides nothing, so b0 is shown exactly when the tab above it
    // is open, which its id cannot say.
    val b0 = "c_0_panel_0_0_0_branch_0"
    val u0 = "c_1_branch_0_0_panel_0_0"
    assert(shown(b0, Set("c_0_panel_0_0")))
    assert(!shown(b0, Set.empty))
    assert(shown(u0, Set("c_1_branch_0_0_panel_0_0")))
    assert(!shown(u0, Set.empty))
    assert(shown("c", Set.empty))
  }

  test("affectedSets surfaces the membership delta per group") {
    val r = renderer(
      onSet(
        List("s.b", "s.c"),
        List(
          (
            Some(Conds.cmp("attr:battery", Op.Lt, Json.fromInt(20))),
            "card",
            Map("state" -> TemplateSlot(ValueExpr(Value.Entity(None)))),
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
      r.affectedSets(
        List(StateChange.Moved("s.b", Some(low("s.b")), low("s.b")))
      ),
      List("c")
    )
    assertEquals(
      r.affectedSets(
        List(StateChange.Moved("s.b", Some(high("s.b")), low("s.b")))
      ),
      List("c")
    )
    assertEquals(
      r.affectedSets(List(StateChange.Moved("s.b", None, low("s.b")))),
      List("c")
    )
    assertEquals(
      r.affectedSets(
        List(StateChange.Moved("s.b", Some(low("s.b")), high("s.b")))
      ),
      List("c")
    )
    assertEquals(
      r.affectedSets(
        List(StateChange.Moved("s.z", Some(high("s.z")), high("s.z")))
      ),
      Nil
    )
    assertEquals(
      r.affectedSets(
        List(
          StateChange.Moved("s.b", Some(high("s.b")), low("s.b")),
          StateChange.Moved("s.c", Some(low("s.c")), high("s.c"))
        )
      ),
      List("c")
    )
  }

  /** A live node beside the children (ADR 0012): `bar` holds the live header,
    * `children` the rest.
    */
  private val splitCards = cards + ("split" -> TestCard(
    // No `{{hostId}}`: a region needs an id only where a choice fills it.
    template = """<div class="fh-col">{{{bar}}}""" +
      """<div class="panel">{{{children}}}</div></div>""",
    regions = Set("bar", "children")
  )) + ("bar" -> TestCard(
    """<div class="bar">{{state}}</div>"""
  ))

  private def splitRenderer: Renderer =
    Renderer.create(
      Dashboard(
        splitCards,
        LayoutNode.Component(
          "split",
          slots = Slots.nodes(
            Map(
              "bar" -> List(
                LayoutNode.Component(
                  "bar",
                  slots = Slots.values(
                    Map(
                      "state" -> TemplateSlot(
                        ValueExpr(Value.Entity(Some("sensor.t")))
                      )
                    )
                  )
                )
              ),
              "children" -> List(
                LayoutNode.Component(
                  "btn",
                  Slots.values(Map("label" -> lit("inside")))
                )
              )
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
      r.renderNodeById(
        "c",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      ),
      None
    )
    val patch =
      r.renderNodeById(
        "c_bar_0",
        Shared.of(states),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).get
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
    "a pick's host is its node's root, the one its cases name"
  ) {
    val r = Renderer.create(tabsDashboard)
    assertEquals(r.hostId("c_panel_0"), r.elementId("c_panel_0"))
    assertEquals(
      r.surfaces.surfacesAt(r.hostId("c_panel_0")),
      Set("c_panel_0_0", "c_panel_0_1")
    )
  }

  /** A card may name any injected var in `slots`, and each must arrive filled.
    */
  test("every var validate treats as injected is one the renderer fills") {
    val names = Dashboard.injectedStatic.toList.sorted
    val d = Dashboard(
      Map(
        "probe" -> TestCard(
          names.map(v => s"$v=[{{$v}}]").mkString("<p>", " ", "</p>")
        )
      ),
      LayoutNode.Component("probe"),
      slug = "kitchen"
    )
    assertEquals(d.validate(), Nil)
    val page = Renderer.create(d).renderPage(Map.empty)
    names.foreach(v => assert(!page.contains(s"$v=[]"), clue = page))
    assert(page.contains("dashboardSlug=[kitchen]"), clue = page)
  }
}
