package fh.view.runtime

import fh.view.model.*
import fh.view.query.{Fragment, Fragments}
import io.circe.parser.decode

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
      transform = Transform.Stage.Chart(Map("width" -> "600")),
      reads = Reads.OnRender
    )

  private def chartNode(param: Ref = Ref.Var("window")) =
    LayoutNode.Component("chart", slots = Map("chart" -> chartSlot(param)))

  private def box(
      vars: Map[String, String],
      kids: LayoutNode*
  ): LayoutNode.Component =
    LayoutNode.Component("box", regions = LayoutNode.kids(kids*), vars = vars)

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
    // An earlier cut carried a `domain` — the values the variable may hold —
    // with three rules policing the field itself (empty, duplicated, and a
    // default it did not list). All of it is gone: what a variable may hold is
    // decided by whoever READS it, and a list on the wire was a second, weaker
    // copy of that, free to disagree with the control the author rendered.
    // What survives is the one thing a declaration can be wrong about on its
    // own.
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
    // NOT every value the domain admits. An earlier cut enumerated the domain
    // so the prepared request map would already hold whatever a viewer later
    // picked; what keeps that map total is the write boundary refusing a value
    // that cannot render, which is where an untrusted value belongs.
    val d = dash(box(Map("window" -> "7d"), chartNode()))
    assertEquals(windowOf(d.queriesIn(d.card)), List("7d"))
    assertEquals(windowOf(d.allQueries), List("7d"))

    val v = d.validated().fold(e => fail(e.mkString("; ")), identity)
    assertEquals(v.queries.size, 1)
  }

  // ---- the render path -----------------------------------------------------

  test("the renderer resolves a chart's window from the declaring ancestor") {
    val d = dash(box(Map("window" -> "7d"), chartNode()))
    val read = SlotRead(
      SlotQuery("history", Map("entity" -> "sensor.t", "window" -> "7d")),
      Transform.Stage.Chart(Map("width" -> "600"))
    )
    val html = Renderer
      .create(d)
      .renderBodyTraced(
        Map("sensor.t" -> EntityState("sensor.t", "21.4", Map.empty)),
        Map.empty,
        Fragments.of(Map(read -> Fragment(100L, "<svg id='seven'/>")))
      )
      .html
    assert(html.contains("<svg id='seven'/>"), clue = html)
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
