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

  private val windows = List("1h", "24h", "7d", "30d")

  private def window(start: String = "24h") =
    VarDecl(start, Some(windows))

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
      vars: Map[String, VarDecl],
      kids: LayoutNode*
  ): LayoutNode.Component =
    LayoutNode.Component("box", regions = LayoutNode.kids(kids*), vars = vars)

  private def dash(root: LayoutNode, surfaces: Map[String, Surface] = Map.empty) =
    Dashboard(
      cards = Map("chart" -> chartCard, "plain" -> plainCard, "box" -> boxCard),
      card = root,
      surfaces = surfaces
    )

  private def windowOf(reads: List[SlotRead]): List[String] =
    reads.flatMap(_.query.params.get("window"))

  // ---- resolution ----------------------------------------------------------

  test("a reference resolves to the nearest declaring ancestor's value") {
    val d = dash(box(Map("window" -> window("7d")), chartNode()))
    assertEquals(d.validate(), Nil)
    assertEquals(windowOf(d.queriesIn(d.card)), List("7d"))
  }

  test("a node that declares nothing is TRANSPARENT to the chain") {
    // The `Row` case the issue describes: an intermediate container should not
    // have to know a variable exists to let one through. Nothing arranges it —
    // the scope is threaded, and a node with no declaration adds nothing.
    val d = dash(
      box(
        Map("window" -> window("30d")),
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
        Map("window" -> window("24h")),
        chartNode(),
        box(Map("window" -> window("1h")), chartNode())
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
        vars = Map("window" -> window("7d"))
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

  test("a query parameter may not read a variable with an OPEN domain") {
    // Not a taste rule. `validated` parses every request a slot can make so a
    // bad one is a build error rather than a viewer's surprise, and it can only
    // enumerate a CLOSED set. An open domain stays fine everywhere else.
    val errs =
      dash(box(Map("window" -> VarDecl("24h")), chartNode())).validate()
    assertEquals(errs.size, 1, clue = errs)
    assert(errs.head.contains("declares no domain"), clue = errs.head)
  }

  test("a default outside its own domain is refused") {
    // The common case, not a corner: the default is what every viewer who has
    // chosen nothing gets, and every first paint.
    val errs =
      dash(box(Map("window" -> VarDecl("4h", Some(windows))), chartNode()))
        .validate()
    assertEquals(errs.size, 1, clue = errs)
    assert(errs.head.contains("'4h'"), clue = errs.head)
    assert(errs.head.contains("does not list"), clue = errs.head)
  }

  test("a declaration is checked where it is WRITTEN, reader or not") {
    // So declaring one ahead of its reader is safe — otherwise a broken
    // declaration would sit silent until somebody referenced it.
    val d = dash(
      box(Map("window" -> VarDecl("4h", Some(windows))), chartNode(Ref.Literal("1h")))
    )
    val errs = d.validate()
    assertEquals(errs.size, 1, clue = errs)
    assert(errs.head.contains("does not list"), clue = errs.head)
  }

  test("an empty domain is refused, and says what to do instead") {
    val errs =
      dash(box(Map("w" -> VarDecl("x", Some(Nil))), chartNode(Ref.Literal("1h"))))
        .validate()
    assert(errs.exists(_.contains("empty domain")), clue = errs)
  }

  test("a variable a provider cannot parse is caught for EVERY value") {
    // The proof the expansion buys. `4h` is not a window, and no viewer has
    // selected it yet — the build still refuses, because it checks what the
    // slot CAN ask rather than what it asks today.
    val errs = dash(
      box(
        Map("window" -> VarDecl("24h", Some(List("24h", "4h")))),
        chartNode()
      )
    ).validate()
    assert(errs.exists(_.contains("4h")), clue = errs)
  }

  test("a variable read from inside a candidate set is refused, for now") {
    // A member's id is minted at run time, so it has no scope entry. The bound
    // is narrow: a query slot inside a set still works, it just cannot read a
    // variable. Stated as a test so lifting it is a deliberate act.
    val d = dash(
      box(
        Map("window" -> window()),
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
      box(Map("window" -> window()), LayoutNode.Component("plain")),
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

  test("the possible reads cover the domain; this render's read is the default") {
    // Two different questions, and the split is what lets the prepared request
    // map stay total once a viewer can choose: `possibleQueriesIn` is what the
    // build proves parseable, `queriesIn` is what a render actually asks.
    val d = dash(box(Map("window" -> window("7d")), chartNode()))
    assertEquals(windowOf(d.possibleQueriesIn(d.card)).sorted, windows.sorted)
    assertEquals(windowOf(d.queriesIn(d.card)), List("7d"))
    assertEquals(windowOf(d.allQueries).sorted, windows.sorted)
  }

  test("the prepared request map holds every value a viewer could pick") {
    val d = dash(box(Map("window" -> window("7d")), chartNode()))
    val v = d.validated().fold(e => fail(e.mkString("; ")), identity)
    assertEquals(
      windowOf(v.dashboard.allQueries).sorted,
      windows.sorted
    )
    assertEquals(v.queries.size, windows.size)
  }

  // ---- the render path -----------------------------------------------------

  test("the renderer resolves a chart's window from the declaring ancestor") {
    val d = dash(box(Map("window" -> window("7d")), chartNode()))
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
        Map("window" -> window("24h")),
        chartNode(),
        box(Map("window" -> window("1h")), chartNode())
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
    assertEquals(d, Right(Map.empty[String, VarDecl]), clue = d)
  }
}
