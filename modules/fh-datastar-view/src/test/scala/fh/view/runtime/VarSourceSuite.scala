package fh.view.runtime

import fh.view.testkit.Sets

import fh.view.testkit.Slots

import fh.view.fetch.FetchSnapshot
import fh.view.model.*
import fh.view.testkit.DashboardBuilders.declared
import fh.view.testkit.TestCard

import scala.collection.immutable.ListMap

/** A node variable is a source (`Value.Var`): a leaf's value slot shows the
  * viewer's value, and a write re-renders it. Everything decided once for every
  * viewer, and every slot whose bytes are shared, refuses one.
  */
class VarSourceSuite extends munit.FunSuite {

  private val window = ValueExpr(Value.Var("window"))
  private def isWindow(v: String) =
    ValueExpr(
      Value.Var("window"),
      List(Step.Compare(Op.Eq, Value.Literal(Lit.Text(v))))
    )

  private val cards = Sets.cards ++ Map(
    "plain" -> TestCard("<span>{{name}}</span>"),
    "flag" -> TestCard("<i>{{#on}}ON{{/on}}{{^on}}off{{/on}}</i>"),
    "live" -> TestCard("<span {{{name.bind}}}>{{name}}</span>"),
    "box" -> TestCard(
      "<div>{{{children}}}</div>",
      regions = Map("children" -> Region())
    ),
    "titled" -> TestCard(
      "<div>{{name}}{{{children}}}</div>",
      regions = Map("children" -> Region())
    )
  )

  private def plain(e: ValueExpr) =
    LayoutNode.Component(
      "plain",
      slots = Slots.values(Map("name" -> TemplateSlot(e)))
    )

  private def panel(kids: LayoutNode*) =
    LayoutNode.Component(
      "box",
      slots = Slots.kids(kids*),
      vars = declared("window" -> "24h"),
      id = Some("panel")
    )

  private def dash(
      root: LayoutNode,
      surfaces: Map[String, Surface] = Map.empty
  ) =
    Dashboard(cards = cards, card = root, surfaces = surfaces)

  private val key = VarKey(NodeId.derived("panel"), "window")

  private def body(r: Renderer, chosen: Map[VarKey, String]) =
    r.renderBodyTraced(
      Shared.of(Map.empty),
      Viewing(Selections.none, FetchSnapshot.of(Map.empty, r.vars.env(chosen)))
    ).html

  test("a value slot shows the declared value, then the viewer's") {
    val d = dash(panel(plain(window)))
    assertEquals(d.validate(), Nil)
    val r = Renderer.create(d)
    assert(body(r, Map.empty).contains("<span>24h</span>"), body(r, Map.empty))
    val seven = body(r, Map(key -> "7d"))
    assert(seven.contains("<span>7d</span>"), seven)
  }

  test("valueIs is a bool reading, true only at its value") {
    val flag = LayoutNode.Component(
      "flag",
      slots = Slots.values(
        Map("on" -> TemplateSlot(isWindow("7d"), `type` = SlotType.Bool))
      )
    )
    val r = Renderer.create(dash(panel(flag)))
    assert(body(r, Map.empty).contains("<i>off</i>"), body(r, Map.empty))
    assert(body(r, Map(key -> "7d")).contains("<i>ON</i>"))
  }

  test("a write reaches the slot reading it, and takes any value") {
    val r = Renderer.create(dash(panel(plain(window))))
    val reader = LayoutNode.childId(
      "",
      NodeId.derived("panel"),
      LayoutNode.Step(LayoutNode.DefaultRegion, 0),
      plain(window)
    )
    assertEquals(r.vars.readersOf(key), List(reader))
    assertEquals(r.refusals(Map(key -> "anything")), Nil)
  }

  private val litCell =
    Some(Cell(classes = ListMap("fh-chosen" -> isWindow("7d"))))

  /** Lit while the viewer's `window` is 7d. */
  private val lit = plain(ValueExpr.literal("x")).copy(cell = litCell)

  private val classAttr = """class="[^"]*fh-chosen""".r

  // The ask while one is out, else the commit (ADR 0025).
  private val bind =
    s"""data-class:fh-chosen="String(($$${key.pendingSignal} || """ +
      s"""$$${key.committedSignal})) === '7d'""""

  test(
    "a class lit by valueIs: for the viewer in the document, never in a " +
      "patch, live off the variable's own signals"
  ) {
    val d = dash(panel(lit))
    assertEquals(d.validate(), Nil)
    val r = Renderer.create(d)
    val id = LayoutNode.childId(
      "",
      NodeId.derived("panel"),
      LayoutNode.Step(LayoutNode.DefaultRegion, 0),
      lit
    )
    val seven = FetchSnapshot.of(Map.empty, r.vars.env(Map(key -> "7d")))
    assert(classAttr.findFirstIn(body(r, Map.empty)).isEmpty)
    assert(classAttr.findFirstIn(body(r, Map(key -> "7d"))).isDefined)
    assert(body(r, Map.empty).contains(bind), body(r, Map.empty))
    val patched =
      r.renderNodeById(
        id,
        Shared.of(Map.empty),
        Viewing(Selections.none, seven)
      )
    assert(patched.exists(p => classAttr.findFirstIn(p).isEmpty), patched)
    assert(patched.exists(_.contains(bind)), patched)
    // No signal of its own to seed or send, not a reader a write re-renders,
    // and not in the patch's key.
    assertEquals(r.signalsFor(id, Shared.of(Map.empty), seven), Map.empty)
    assertEquals(r.vars.readersOf(key), Nil)
    assertEquals(
      r.byteSlotValues(id, Shared.of(Map.empty), seven),
      r.byteSlotValues(id, Shared.of(Map.empty), FetchSnapshot.empty)
    )
  }

  test("a class lit by valueIs on a card holding regions: lit for the viewer") {
    val held = LayoutNode.Component(
      "box",
      slots = Slots.kids(plain(ValueExpr.literal("y"))),
      cell = litCell
    )
    val d = dash(panel(held))
    assertEquals(d.validate(), Nil)
    val r = Renderer.create(d)
    assert(classAttr.findFirstIn(body(r, Map.empty)).isEmpty)
    assert(classAttr.findFirstIn(body(r, Map(key -> "7d"))).isDefined)
    assert(body(r, Map.empty).contains(bind), body(r, Map.empty))
  }

  test("a class lit by valueIs needs a declarer above") {
    refused(dash(lit), "which no ancestor declares")
  }

  private def refused(d: Dashboard, fragment: String): Unit =
    val errs = d.validate()
    assert(errs.exists(_.contains(fragment)), clue = errs)

  test("a read with no declarer is a build error") {
    refused(dash(plain(window)), "which no ancestor declares")
  }

  test("a signal slot refuses one: a signal is shared by every viewer") {
    val live = LayoutNode.Component(
      "live",
      slots = Slots.values(
        Map("name" -> TemplateSlot(window, signal = Some(SignalBind.Text)))
      )
    )
    refused(dash(panel(live)), "signal, which every viewer shares")
  }

  test("a live cell class refuses any other read: it is a signal too") {
    val notEquals = ValueExpr(
      Value.Var("window"),
      List(Step.Compare(Op.Ne, Value.Literal(Lit.Text("7d"))))
    )
    val classed = plain(ValueExpr.literal("x"))
      .copy(cell = Some(Cell(classes = ListMap("fh-chosen" -> notEquals))))
    refused(dash(panel(classed)), "signal, which every viewer shares")
  }

  test("a card holding regions refuses one in its own bytes") {
    val titled = LayoutNode.Component(
      "titled",
      slots = Slots.values(Map("name" -> TemplateSlot(window))) ++ Slots.kids(
        plain(ValueExpr.literal("x"))
      )
    )
    refused(dash(panel(titled)), "holds regions")
  }

  test("a valueWhile refuses one: it is decided for every viewer") {
    val gated = LayoutNode.Component(
      "plain",
      slots = Slots.values(
        Map(
          "name" -> TemplateSlot(
            ValueExpr(Value.Cel("'x'")),
            valueWhile = Some(isWindow("7d"))
          )
        )
      )
    )
    refused(dash(panel(gated)), "valueWhile reads the variable 'window'")
  }

  test("a set's clause and order refuse one") {
    val set = Sets.node(
      candidates = List("light.a"),
      members = Map(
        "light.a" -> Sets.Member(
          List(
            Sets.Clause(
              Some(isWindow("7d")),
              plain(ValueExpr.literal("x"))
            )
          )
        )
      ),
      orderBy = List(SortTerm(window))
    )
    refused(dash(panel(set)), "a set's clause reads the variable 'window'")
    refused(dash(panel(set)), "a set's order reads the variable 'window'")
  }

  test("a state surface's condition refuses one") {
    val d = dash(
      panel(plain(ValueExpr.literal("x"))),
      Map(
        "t0" -> Surface(
          plain(ValueExpr.literal("y")),
          activation = Activation.State(Some(isWindow("7d")))
        )
      )
    )
    refused(d, "surface 't0''s condition reads the variable 'window'")
  }
}
