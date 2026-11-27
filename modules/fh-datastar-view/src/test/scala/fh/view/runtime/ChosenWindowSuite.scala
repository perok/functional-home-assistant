package fh.view.runtime

import fh.view.testkit.{Conds, Sets}

import fh.view.testkit.Slots

import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}
import fh.view.history.{ChartStyle, Window, Windows}
import fh.view.model.*
import fh.view.testkit.DashboardBuilders.{declared, st}
import fh.view.testkit.TestCard
import io.github.iltotore.iron.autoRefine

/** A chosen window is a [[Choice.Chosen]] over any `by`: the case whose `is`
  * equals what it reads, else `otherwise`, else no value. By state, it is
  * decided per version and wakes its chart as any entity read does.
  */
class ChosenWindowSuite extends munit.FunSuite {

  private val span = "input_select.span"
  private val style = Step.Chart(ChartStyle(width = 600))

  private def byState(otherwise: Option[Window] = None): Choice[Window] =
    Choice.Chosen(
      ValueExpr(Value.Entity(Some(span))),
      List(
        Case(Lit.Text("day"), Windows.day),
        Case(Lit.Text("hour"), Windows.hour)
      ),
      otherwise
    )

  private def chart(window: Choice[Window]) =
    LayoutNode.Component(
      "chart",
      slots = Slots.values(
        Map(
          "chart" -> TemplateSlot(
            ValueExpr(
              Value.Fetch(FetchTemplate("sensor.t", window)),
              List(style),
              default = Some(Lit.Text("no window"))
            ),
            `type` = SlotType.TextRaw
          )
        )
      )
    )

  private def dash(root: LayoutNode) =
    Dashboard(
      cards = Sets.cards ++ Map(
        "chart" -> TestCard("<div>{{{chart}}}</div>"),
        "box" -> TestCard(
          "<div>{{{children}}}</div>",
          regions = Set("children")
        )
      ),
      card = root
    )

  private def readOf(w: Window) =
    SlotRead(FetchRequest.History("sensor.t", w), style)

  private def at(state: String) =
    Shared.of(Map(span -> st(span, state), "sensor.t" -> st("sensor.t", "1")))

  /** The pre-read at `shared`, then the body over what it answered. */
  private def paint(r: Renderer, shared: Shared): (List[SlotRead], String) =
    val reads = r.fetchesForPage(shared, Map.empty)
    val snapshot =
      FetchSnapshot.of(reads.map(q => q -> Staged(1L, s"<svg>$q</svg>")).toMap)
    reads -> r
      .renderBodyTraced(shared, Viewing(snapshot))
      .html

  test("an entity's state picks the window, per version") {
    val r = Renderer.create(dash(chart(byState())))
    assertEquals(paint(r, at("day"))._1, List(readOf(Windows.day)))
    assertEquals(paint(r, at("hour"))._1, List(readOf(Windows.hour)))
  }

  test("what picks the window wakes the chart and keys its bytes") {
    val d = dash(chart(byState()))
    val node = Conds.compiledAlone(d.card)
    assertEquals(node.liveEntities, List(span))
    assertEquals(node.liveEntitiesAsBytes, List(span))
  }

  test("no case is no value: it asks nothing and shows the slot's default") {
    val r = Renderer.create(dash(chart(byState())))
    val (reads, html) = paint(r, at("week"))
    assertEquals(reads, Nil)
    assert(html.contains("no window") && !html.contains("<svg"), html)
  }

  test("otherwise takes what no case does") {
    val r = Renderer.create(dash(chart(byState(Some(Windows.month)))))
    assertEquals(paint(r, at("week"))._1, List(readOf(Windows.month)))
  }

  private def refused(d: Dashboard, fragment: String): Unit =
    val errs = d.validate()
    assert(errs.exists(_.contains(fragment)), clue = errs)

  private def chosenBy(by: ValueExpr, cases: Case[Window]*) =
    chart(Choice.Chosen(by, cases.toList))

  test("a key is text, a bool or a whole number, and each case spells it") {
    refused(
      dash(chosenBy(ValueExpr(Value.Literal(Lit.Real(1.5))))),
      "chooses its window by a real"
    )
    refused(
      dash(
        chosenBy(
          ValueExpr(Value.Entity(Some(span))),
          Case(Lit.Bool(true), Windows.day)
        )
      ),
      "has the case 'true', a bool, where its window is chosen by a text"
    )
    refused(
      dash(
        chosenBy(
          ValueExpr(Value.Entity(Some(span))),
          Case(Lit.Text("day"), Windows.day),
          Case(Lit.Text("day"), Windows.hour)
        )
      ),
      "has the case 'day' twice"
    )
  }

  test("a decision's window is written down, even one state would pick") {
    val past = ValueExpr(Value.Fetch(FetchTemplate("sensor.t", byState())))
    val guard = ValueExpr(Value.Cel("size(past) > 0", Map("past" -> past)))
    val set = Sets.node(
      candidates = List("sensor.t"),
      members = Map(
        "sensor.t" -> Sets.Member(
          List(
            Sets.Clause(Some(guard), chart(Choice.Fixed(Windows.day)))
          )
        )
      )
    )
    refused(
      dash(set),
      "whose window is chosen: a decision's window is written down"
    )
  }

  test("a declared value no case takes is a build error; a value read is not") {
    val window = ValueExpr(Value.Var("window"))
    val box = (default: String, kid: LayoutNode) =>
      LayoutNode.Component(
        "box",
        slots = Slots.kids(kid),
        vars = declared("window" -> default)
      )
    val one = chosenBy(window, Case(Lit.Text("24h"), Windows.day))
    assertEquals(dash(box("24h", one)).validate(), Nil)
    refused(dash(box("4h", one)), "'4h' is not one of 24h")
    // Over state the build cannot know: the choice decides at render.
    assertEquals(dash(chart(byState())).validate(), Nil)
  }

  test("a renderer checks a session's values together, the start beneath") {
    // `b` = 1 is taken only while `a` is not at its declared `y`.
    val both = ValueExpr(
      Value.Var("a"),
      List(Step.Cel("value + '_' + b", Map("b" -> ValueExpr(Value.Var("b")))))
    )
    val r = Renderer.create(
      dash(
        LayoutNode.Component(
          "box",
          slots = Slots.kids(
            chosenBy(
              both,
              Case(Lit.Text("y_2"), Windows.day),
              Case(Lit.Text("x_2"), Windows.hour),
              Case(Lit.Text("x_1"), Windows.month)
            )
          ),
          vars = declared("a" -> "y", "b" -> "2")
        )
      )
    )
    def key(name: String) =
      r.vars.defaults.keys.find(_.name == name).getOrElse(fail(name))
    val s = SessionState.initial.copy(
      initialValues = Map(key("a") -> "x"),
      vars = Map(key("b") -> "1")
    )
    val (checked, refusals) = r.recheck(s)
    assertEquals(refusals, Nil)
    assertEquals(checked.initialValues, s.initialValues)
    assertEquals(checked.vars, s.vars)
    // A start no longer taken takes the choice that leaned on it with it.
    val (gone, why) = r.recheck(s.copy(initialValues = Map(key("a") -> "z")))
    assertEquals((gone.initialValues, gone.vars), (Map.empty, Map.empty))
    assertEquals(why.size, 2, why)
  }

  test(
    "a `by` that reads a fetch is refused, not thrown at its declared value"
  ) {
    val past = ValueExpr(
      Value.Fetch(FetchTemplate("sensor.t", Choice.Fixed(Windows.day)))
    )
    val by = ValueExpr(
      Value.Cel("size(past) > 0 ? '24h' : '4h'", Map("past" -> past))
    )
    val errs = dash(chosenBy(by, Case(Lit.Text("24h"), Windows.day))).validate()
    // It types, so only the fetch rule stands between it and evaluating `by`.
    assert(!errs.exists(_.contains("does not type")), clue = errs)
    assert(
      errs.exists(_.contains("chooses its window by a fetch")),
      clue = errs
    )
  }

  test("a constant `by` no case takes is refused without naming a variable") {
    refused(
      dash(
        chosenBy(ValueExpr.literal("4h"), Case(Lit.Text("24h"), Windows.day))
      ),
      "chooses its window by a value no case takes: '4h' is not one of 24h"
    )
  }
}
