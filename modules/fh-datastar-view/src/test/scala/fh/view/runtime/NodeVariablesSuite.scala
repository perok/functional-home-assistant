package fh.view.runtime

import fh.view.model.*
import fh.view.history.ChartStyle
import fh.view.query.{Staged, QuerySnapshot}
import io.circe.parser.decode
import fh.view.testkit.TestIds.given

/** NODE VARIABLES (issue #209): a node declares a named choice, a descendant
  * reads it by name, and the chain decides which declaration wins.
  *
  * The property under all of these is that the reference is DECLARED. A render
  * resolves every query before the walk starts, and it can only do that while
  * the input set is enumerable from the tree — which a reference matched by
  * string convention at evaluation time would end. So "an undeclared reference
  * is a build error" is the load-bearing assertion here, not a nicety.
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

  /** A chart slot whose window comes from wherever `window` is declared. */
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

  /** A declarer with a NAME, so a test about ADDRESSING a choice does not
    * depend on where the node happens to sit.
    */
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

  // ---- resolution ----------------------------------------------------------

  test("a reference resolves to the nearest declaring ancestor's value") {
    val d = dash(box(Map("window" -> "7d"), chartNode()))
    assertEquals(d.validate(), Nil)
    assertEquals(windowOf(d.queriesIn(d.card)), List("7d"))
  }

  test("a node that declares nothing is TRANSPARENT to the chain") {
    // The `Row` case the issue describes: an intermediate container should not
    // have to know a variable exists to let one through. Nothing arranges it —
    // the scope is threaded, and a node with no declaration adds nothing.
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
    // One control over three charts, with one chart that differs — the case
    // that makes ancestor-chain resolution load-bearing rather than tidier.
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
    // The access property, as a fact about the types rather than a rule: a
    // literal has nowhere for a write to land, so `entity` is not substitutable
    // and nothing has to refuse the substitution.
    val t = chartSlot(Ref.Literal("24h")).query.get
    assertEquals(t.references, Nil)
    assertEquals(t.resolve(Map("window" -> "7d")).params("window"), "24h")
    assertEquals(t.resolve(Map.empty).params("entity"), "sensor.t")
  }

  // ---- what the build refuses ---------------------------------------------

  test("a reference with no declarer is a build error naming both") {
    val errs = dash(box(Map.empty, chartNode())).validate()
    assertEquals(errs.size, 1, clue = errs)
    assert(errs.head.contains("'window'"), clue = errs.head)
    assert(errs.head.contains("no ancestor declares"), clue = errs.head)
    // The node, so an author can find it — this is what replaces an
    // unresolved `@@NODE_ID@@` rendering literally into the DOM.
    assert(errs.head.startsWith("c_0:"), clue = errs.head)
  }

  test("a declaration is a NAME and a value, and nothing else is checked") {
    // What a variable may hold is its readers' question; the only thing a
    // declaration can get wrong on its own is its name.
    val d = dash(box(Map("window" -> "24h"), chartNode()))
    assertEquals(d.validate(), Nil)
    assertEquals(windowOf(d.queriesIn(d.card)), List("24h"))
  }

  test("a name that is not a plain token is refused where it is WRITTEN") {
    // Checked at the declaration and not at a reader, so declaring one ahead
    // of its reader is safe — a broken name would otherwise sit silent until
    // somebody referenced it. It has to be a token because it is spelled into
    // signal names and the URL mirror.
    val errs =
      dash(box(Map("my window" -> "24h"), chartNode(Ref.Literal("1h"))))
        .validate()
    assertEquals(errs.size, 1, clue = errs)
    assert(errs.head.contains("'my window'"), clue = errs.head)
    assert(errs.head.contains("plain name"), clue = errs.head)
  }

  test("a declared value the provider cannot parse is a build error") {
    // The build checks what the dashboard asks before anybody chooses — a real
    // build-time fact about this dashboard, and the whole of what the build can
    // honestly say. Every later value is untrusted input, refused at the write.
    val errs = dash(box(Map("window" -> "4h"), chartNode())).validate()
    assert(errs.exists(_.contains("unknown window '4h'")), clue = errs)
  }

  test("a variable read from inside a candidate set is refused, for now") {
    // A member's id is minted at run time, so it has no scope entry. The bound
    // is narrow: a query slot inside a set still works, it just cannot read a
    // variable. Stated as a test so lifting it is a deliberate act.
    val d = dash(
      box(
        Map("window" -> "24h"),
        LayoutNode.SetNode(
          candidates = List("sensor.t"),
          members = Map(
            "sensor.t" -> LayoutNode.SetMember(
              List(LayoutNode.SetClause(node = chartNode()))
            )
          )
        )
      )
    )
    assert(
      d.validate().exists(_.contains("inside a candidate set")),
      clue = d.validate()
    )
  }

  test("a SURFACE is its own scope root") {
    // A baked surface can be swapped into a host, so inheriting from wherever
    // it is shown would let one content resolve differently per host. It
    // declares what it needs, and the build says so rather than resolving
    // against a page the surface may not be under.
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

  // ---- what the build ENUMERATES -------------------------------------------

  test("the build asks what the dashboard asks: one read, at the default") {
    // Only the declared value: a viewer's later value is checked at the write.
    val d = dash(box(Map("window" -> "7d"), chartNode()))
    assertEquals(windowOf(d.queriesIn(d.card)), List("7d"))
    assertEquals(windowOf(d.allQueries), List("7d"))

    val v = d.validated().fold(e => fail(e.mkString("; ")), identity)
    assertEquals(v.queries.size, 1)
  }

  // ---- the render path -----------------------------------------------------

  private val states =
    Map("sensor.t" -> EntityState("sensor.t", "21.4", Map.empty))

  private def readAt(window: String) = SlotRead(
    SlotQuery("history", Map("entity" -> "sensor.t", "window" -> window)),
    Transform.Stage.Chart(ChartStyle(width = 600))
  )

  /** One viewer's render: the answers they were given, and the values those
    * answers were fetched FOR.
    */
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

  // ---- what a VIEWER chose -------------------------------------------------

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
    // Same node and dashboard, two viewers, two reads; only the renderer is
    // shared.
    val d = dash(named("panel", Map("window" -> "24h"), chartNode()))
    val r = Renderer.create(d)
    val a = r.varEnv(Map(("panel": NodeId, "window") -> "1h"))
    val b = r.varEnv(Map(("panel": NodeId, "window") -> "30d"))

    assertEquals(r.queriesForPage(Set.empty, Map.empty, a), List(readAt("1h")))
    assertEquals(r.queriesForPage(Set.empty, Map.empty, b), List(readAt("30d")))
    assert(paint(r, a, "1h" -> "<svg id='hour'/>").contains("hour"))
    assert(paint(r, b, "30d" -> "<svg id='month'/>").contains("month"))

    // And the render KEY moves with them, which is what stops one viewer's
    // bytes being served for the other's span. Asked of the CHART, not the
    // panel: the panel holds regions, so it is structure and has no key at
    // all.
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
    // Two `window`s in one tree from two declarations. Choosing on the outer
    // one must not move the chart that shadows it — the same fact shadowing
    // rests on, seen from the write side.
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
    // Untrusted input: a stale URL naming a node that was renamed or removed.
    // It matches no scope, so it is simply never read — the same shape
    // `SurfaceGraph.openPopup` uses for a surface id this dashboard lost.
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
    // The render key follows the declaration, which is what stops one viewer's
    // chart standing in for another span's.
    val d = dash(
      box(
        Map("window" -> "24h"),
        chartNode(),
        box(Map("window" -> "1h"), chartNode())
      )
    )
    assertEquals(d.queriesIn(d.card).size, 2)
  }

  // ---- the wire ------------------------------------------------------------

  test("a bare string decodes as a literal, an object as a reference") {
    // The same rule a slot's own decoder already uses, so nothing stamps a tag
    // onto the params that make no reference — which is what keeps the
    // byte-identity snapshots still.
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
