package fh.view.runtime

import fh.view.model.*
import fh.view.history.ChartStyle
import fh.view.query.{Staged, QuerySnapshot}
import io.circe.parser.decode
import fh.view.testkit.TestIds.given

/** Node variables (issue #209): a node declares a named choice, a descendant
  * reads it by name, and the chain decides which declaration wins. A render
  * resolves every query before the walk, which needs the input set enumerable
  * from the tree, so "an undeclared reference is a build error" is the
  * load-bearing assertion.
  */
class NodeVariablesSuite extends munit.FunSuite {

  private val chartCard = CardDef(
    "<div>{{{chart}}}</div>",
    slots = List("chart")
  )
  private val plainCard = CardDef("<span>{{name}}</span>", slots = List("name"))
  private val boxCard = CardDef(
    "<div>{{#children}}{{{html}}}{{/children}}</div>",
    regions = Map("children" -> Region())
  )

  private def chartSlot(param: Ref = Ref.Var("window")) =
    SlotSource(
      query = Some(
        QueryTemplate(
          "history",
          Map("entity" -> Ref.Literal("sensor.t"), "window" -> param)
        )
      ),
      transform = Transform.Stage.Chart(ChartStyle(width = 600)),
      reads = Reads.OnRender
    )

  private def chartNode(param: Ref = Ref.Var("window")) =
    LayoutNode.Component("chart", slots = Map("chart" -> chartSlot(param)))

  private def box(
      vars: Map[String, String],
      kids: LayoutNode*
  ): LayoutNode.Component =
    LayoutNode.Component(
      "box",
      regions = LayoutNode.kids(kids*),
      vars = vars
    )

  /** Named, so a test about addressing a choice does not depend on position. */
  private def named(
      id: String,
      vars: Map[String, String],
      kids: LayoutNode*
  ): LayoutNode.Component =
    box(vars, kids*).copy(id = Some(id))

  private def dash(
      root: LayoutNode,
      surfaces: Map[String, Surface] = Map.empty
  ) =
    Dashboard(
      cards = Map("chart" -> chartCard, "plain" -> plainCard, "box" -> boxCard),
      card = root,
      surfaces = surfaces
    )

  private def windowOf(reads: List[SlotRead]): List[String] =
    reads.flatMap(_.query.params.get("window"))

  test("a reference resolves to the nearest declaring ancestor's value") {
    val d = dash(box(Map("window" -> "7d"), chartNode()))
    assertEquals(d.validate(), Nil)
    assertEquals(windowOf(d.queriesIn(d.card)), List("7d"))
  }

  test("a node that declares nothing is TRANSPARENT to the chain") {
    // An intermediate container need not know a variable exists: the scope is
    // threaded, and a node with no declaration adds nothing.
    val d = dash(
      box(
        Map("window" -> "30d"),
        box(Map.empty, box(Map.empty, chartNode()))
      )
    )
    assertEquals(d.validate(), Nil)
    assertEquals(windowOf(d.queriesIn(d.card)), List("30d"))
  }

  test("a nested declaration SHADOWS for its own subtree, and only that") {
    // One control over three charts, one differing: what makes ancestor-chain
    // resolution load-bearing.
    val d = dash(
      box(
        Map("window" -> "24h"),
        chartNode(),
        box(Map("window" -> "1h"), chartNode())
      )
    )
    assertEquals(d.validate(), Nil)
    assertEquals(windowOf(d.queriesIn(d.card)).sorted, List("1h", "24h"))
  }

  test("a node's own declaration is in scope for its own slots") {
    val d = dash(
      LayoutNode.Component(
        "chart",
        slots = Map("chart" -> chartSlot()),
        vars = Map("window" -> "7d")
      )
    )
    assertEquals(d.validate(), Nil)
    assertEquals(windowOf(d.queriesIn(d.card)), List("7d"))
  }

  test("a written-down parameter reads no variable at all") {
    // A literal has nowhere for a write to land, so `entity` is not
    // substitutable and nothing has to refuse it.
    val t = chartSlot(Ref.Literal("24h")).query.get
    assertEquals(t.references, Nil)
    assertEquals(t.resolve(Map("window" -> "7d")).params("window"), "24h")
    assertEquals(t.resolve(Map.empty).params("entity"), "sensor.t")
  }

  test("a reference with no declarer is a build error naming both") {
    val errs = dash(box(Map.empty, chartNode())).validate()
    assertEquals(errs.size, 1, clue = errs)
    assert(errs.head.contains("'window'"), clue = errs.head)
    assert(errs.head.contains("no ancestor declares"), clue = errs.head)
    // The node, so an author can find it.
    assert(errs.head.startsWith("c_0:"), clue = errs.head)
  }

  test("a declaration is a NAME and a value, and nothing else is checked") {
    // What a variable may hold is its readers' question; a declaration alone
    // can only get its name wrong.
    val d = dash(box(Map("window" -> "24h"), chartNode()))
    assertEquals(d.validate(), Nil)
    assertEquals(windowOf(d.queriesIn(d.card)), List("24h"))
  }

  test("a name that is not a plain token is refused where it is WRITTEN") {
    // Checked at the declaration, so a broken name cannot sit silent until
    // referenced. A token, since it is spelled into signal names and the URL
    // mirror.
    val errs =
      dash(box(Map("my window" -> "24h"), chartNode(Ref.Literal("1h"))))
        .validate()
    assertEquals(errs.size, 1, clue = errs)
    assert(errs.head.contains("'my window'"), clue = errs.head)
    assert(errs.head.contains("plain name"), clue = errs.head)
  }

  test("a declared value the provider cannot parse is a build error") {
    // The build checks what the dashboard asks before anybody chooses; every
    // later value is untrusted input, refused at the write.
    val errs = dash(box(Map("window" -> "4h"), chartNode())).validate()
    assert(errs.exists(_.contains("unknown window '4h'")), clue = errs)
  }

  private def setOf(clause: LayoutNode) = LayoutNode.SetNode(
    candidates = List("sensor.t"),
    members = Map(
      "sensor.t" -> LayoutNode.SetMember(
        List(LayoutNode.SetClause(node = clause))
      )
    )
  )

  test(
    "a variable read inside a candidate set resolves through the set's scope"
  ) {
    // Candidates and member ids are fixed at build time, so a member reads the
    // scope at its set like any node (ADR 0033).
    val d = dash(box(Map("window" -> "7d"), setOf(chartNode())))
    assertEquals(d.validate(), Nil)
    assertEquals(windowOf(d.queriesIn(d.card)), List("7d"))
    val r = Renderer.fromValidated(
      d.validated().fold(e => fail(e.mkString), identity)
    )
    val set = r.members
      .setContainer(NodeId.derived("c_0"))
      .getOrElse(fail("no set at c_0"))
    val member: NodeId = r.members.memberIdOf(set, "sensor.t")
    assertEquals(r.readersOf(NodeId.derived("c"), "window"), List(member))
    val chosen = r.varEnv(Map((NodeId.derived("c"), "window") -> "1h"))
    assertEquals(windowOf(r.readsAt(member, chosen)), List("1h"))
  }

  test("a declaration inside a set's clause is refused") {
    // A clause renders once per member, so it would be every member's own
    // choice; members read the scope at the set.
    val errs = dash(
      box(Map.empty, setOf(box(Map("window" -> "24h"), chartNode())))
    ).validate()
    assert(
      errs.exists(e =>
        e.contains("declares the variable(s) window inside a candidate set")
      ),
      clue = errs
    )
  }

  test("a SURFACE is its own scope root") {
    // A baked surface can be swapped into different hosts, so inheriting would
    // resolve one content differently per host; it declares what it needs.
    val errs = dash(
      box(Map("window" -> "24h"), LayoutNode.Component("plain")),
      surfaces = Map("popup" -> Surface(chartNode()))
    ).validate()
    assert(
      errs.exists(e =>
        e.startsWith("surface 'popup'") && e.contains("no ancestor declares")
      ),
      clue = errs
    )
  }

  test("an OWNED surface starts with the scope of the node it bakes into") {
    // A tab panel or an `If` branch has exactly one host, so the reason a popup
    // is a scope root does not apply: a chooser above a tab bar reaches the
    // charts in its panels.
    val d = dash(
      box(Map("window" -> "7d"), box(Map("tab" -> "0"))),
      surfaces = Map(
        "t0" -> Surface(
          chartNode(),
          bakeInto = Some("c_0"),
          bakeAs = Some("children"),
          bakeIndex = Some(0),
          activation = Activation.Var("tab")
        )
      )
    )
    val errs = d.validate()
    assert(!errs.exists(_.contains("no ancestor declares")), clue = errs)
    assertEquals(windowOf(d.allQueries), List("7d"))
    assertEquals(
      d.varScopes
        .get(NodeId.derived(LayoutNode.surfacePrefix("t0") + "c"))
        .flatMap(_.get("window"))
        .map(_.declarer),
      Some(NodeId.derived("c"))
    )
  }

  test("the build asks what the dashboard asks: one read, at the default") {
    // A viewer's later value is checked at the write.
    val d = dash(box(Map("window" -> "7d"), chartNode()))
    assertEquals(windowOf(d.queriesIn(d.card)), List("7d"))
    assertEquals(windowOf(d.allQueries), List("7d"))

    val v = d.validated().fold(e => fail(e.mkString("; ")), identity)
    assertEquals(v.queries.size, 1)
  }

  private val states =
    Map("sensor.t" -> EntityState("sensor.t", "21.4", Map.empty))

  private def readAt(window: String) = SlotRead(
    SlotQuery("history", Map("entity" -> "sensor.t", "window" -> window)),
    Transform.Stage.Chart(ChartStyle(width = 600))
  )

  /** The answers a viewer was given, and the values they were fetched for. */
  private def paint(r: Renderer, env: VarEnv, drawn: (String, String)*) =
    r.renderBodyTraced(
      states,
      Map.empty,
      QuerySnapshot.of(
        drawn.map((w, svg) => readAt(w) -> Staged(100L, svg)).toMap,
        env
      )
    ).html

  test("the renderer resolves a chart's window from the declaring ancestor") {
    val d = dash(box(Map("window" -> "7d"), chartNode()))
    val r = Renderer.create(d)
    val html = paint(r, r.varEnv(Map.empty), "7d" -> "<svg id='seven'/>")
    assert(html.contains("<svg id='seven'/>"), clue = html)
  }

  test("a viewer's choice overrides the declared value") {
    val d = dash(named("panel", Map("window" -> "24h"), chartNode()))
    val r = Renderer.create(d)
    val chose7d = r.varEnv(Map(("panel": NodeId, "window") -> "7d"))
    assertEquals(
      r.queriesForPage(Set.empty, Map.empty, chose7d),
      List(readAt("7d"))
    )
    assert(
      paint(r, chose7d, "7d" -> "<svg id='chosen'/>").contains("chosen")
    )
  }

  test("two viewers on two windows are two reads, and neither sees the other") {
    val d = dash(named("panel", Map("window" -> "24h"), chartNode()))
    val r = Renderer.create(d)
    val a = r.varEnv(Map(("panel": NodeId, "window") -> "1h"))
    val b = r.varEnv(Map(("panel": NodeId, "window") -> "30d"))

    assertEquals(r.queriesForPage(Set.empty, Map.empty, a), List(readAt("1h")))
    assertEquals(r.queriesForPage(Set.empty, Map.empty, b), List(readAt("30d")))
    assert(paint(r, a, "1h" -> "<svg id='hour'/>").contains("hour"))
    assert(paint(r, b, "30d" -> "<svg id='month'/>").contains("month"))

    // The render key moves with them, so one viewer's bytes are never served
    // for another's span. Asked of the chart: the panel is structure and has no
    // key.
    val chartId = LayoutNode.childId(
      "",
      LayoutNode.rootId("", d.card),
      LayoutNode.Step(LayoutNode.DefaultRegion, 0),
      chartNode()
    )
    def keyFor(env: VarEnv, window: String) =
      r.renderInputs(
        chartId,
        states,
        QuerySnapshot.of(Map(readAt(window) -> Staged(100L, "<svg/>")), env)
      )
    assert(keyFor(a, "1h").isDefined)
    assertNotEquals(keyFor(a, "1h"), keyFor(b, "30d"))
  }

  test("a choice is addressed to the DECLARER, so a shadow is untouched") {
    // Choosing on the outer `window` must not move the chart that shadows it.
    val inner = named("inner", Map("window" -> "1h"), chartNode())
    val d = dash(named("panel", Map("window" -> "24h"), chartNode(), inner))
    val r = Renderer.create(d)
    val env = r.varEnv(Map(("panel": NodeId, "window") -> "30d"))
    assertEquals(
      r.queriesForPage(Set.empty, Map.empty, env).toSet,
      Set(readAt("30d"), readAt("1h"))
    )
  }

  test("a choice naming a variable nothing declares is inert, not an error") {
    // A stale URL naming a lost node matches no scope and is never read, as
    // `SurfaceGraph.openPopup` treats a lost surface id.
    val d = dash(named("panel", Map("window" -> "24h"), chartNode()))
    val r = Renderer.create(d)
    val env = r.varEnv(
      Map(
        ("gone": NodeId, "window") -> "7d",
        ("panel": NodeId, "nosuch") -> "7d"
      )
    )
    assertEquals(
      r.queriesForPage(Set.empty, Map.empty, env),
      List(readAt("24h"))
    )
  }

  test("two windows under two declarations are two reads, not one") {
    val d = dash(
      box(
        Map("window" -> "24h"),
        chartNode(),
        box(Map("window" -> "1h"), chartNode())
      )
    )
    assertEquals(d.queriesIn(d.card).size, 2)
  }

  test("a bare string decodes as a literal, an object as a reference") {
    // The slot decoder's own rule, so params making no reference carry no tag
    // and the byte-identity snapshots stay still.
    assertEquals(decode[Ref](""""24h""""), Right(Ref.Literal("24h")))
    assertEquals(
      decode[Ref]("""{"var":"window"}"""),
      Right(Ref.Var("window"))
    )
    assert(decode[Ref]("""{"nope":"window"}""").isLeft)
  }

  test("a node with no declaration carries none on the wire") {
    val d = decode[LayoutNode]("""{"kind":"component","card":"plain"}""")
      .map {
        case c: LayoutNode.Component => c.vars
        case other                   => fail(s"expected a component: $other")
      }
    assertEquals(d, Right(Map.empty[String, String]), clue = d)
  }
}
