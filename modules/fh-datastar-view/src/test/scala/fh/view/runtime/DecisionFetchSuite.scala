package fh.view.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.FHError
import fh.view.fetch.{FetchSnapshot, Staged}
import fh.view.history.Windows
import fh.view.model.*
import fh.view.testkit.Conds

/** A decision made outside a render reads a fetch: a set's clause and order, a
  * state surface, a `valueWhile` and a press decide by its answer, which the
  * server prepares before any of them is made.
  */
class DecisionFetchSuite extends munit.FunSuite {

  private val history = FetchTemplate("sensor.left", Choice.Fixed(Windows.day))
  private def past(f: FetchTemplate = history) =
    Map("past" -> ValueExpr(Value.Fetch(f)))

  // Holds while the sensor has any history.
  private val hasPast = ValueExpr(Value.Cel("size(past) > 0", past()))

  private val some = """{"points":[[0,1.0],[60000,2.0]]}"""
  private val empty = """{"points":[]}"""

  /** Every read the renderer asks for, answered with `json`. */
  private def answer(r: Renderer, json: String): Unit =
    r.answerDecisions(reads =>
      IO.pure(FetchSnapshot.of(reads.map(_ -> Staged(1L, json)).toMap))
    ).unsafeRunSync()

  private def item(label: String) = LayoutNode.Component(
    "item",
    templateSlots = Map("label" -> TemplateSlot(ValueExpr.literal(label)))
  )

  private def dashboard(kids: LayoutNode*) = Dashboard(
    cards = Map(
      "item" -> CardDef("<i>{{label}}</i>"),
      "call" -> CardDef("<button>{{service}}</button>"),
      "col" -> CardDef(
        "<div>{{{children}}}</div>",
        regions = Map(LayoutNode.DefaultRegion -> Region())
      )
    ),
    card = LayoutNode.Component(
      "col",
      regions = Map(LayoutNode.DefaultRegion -> kids.toList)
    )
  )

  private def set(
      when: Option[ValueExpr],
      orderBy: List[SortTerm] = Nil
  ) = LayoutNode.SetNode(
    candidates = List("sensor.a", "sensor.b"),
    members = Map(
      "sensor.a" -> LayoutNode.SetMember(
        List(LayoutNode.SetClause(when, item("member-a")))
      ),
      "sensor.b" -> LayoutNode.SetMember(
        List(LayoutNode.SetClause(when, item("member-b")))
      )
    ),
    orderBy = orderBy
  )

  // A fresh map each call: decisions are kept by the snapshot's identity.
  private def states() = Map(
    "sensor.a" -> EntityState("sensor.a", "a", Map.empty),
    "sensor.b" -> EntityState("sensor.b", "b", Map.empty)
  )

  private def body(r: Renderer) =
    r.renderBodyTraced(states(), Selections.none, FetchSnapshot.empty).html

  test("a set's clause reads a fetch, answered before it decides") {
    val d = dashboard(set(Some(hasPast)))
    assertEquals(d.validate(), Nil)
    val r = Renderer.create(d)
    val unanswered = intercept[FHError](body(r))
    assert(unanswered.getMessage.contains("where none is answered"))
    assertEquals(
      d.validated().toOption.get.ready.decisionReads,
      List(SlotAsk(history, None).resolve(Map.empty))
    )
    answer(r, some)
    assert(body(r).contains("member-b"), clue = body(r))
    answer(r, empty)
    assert(!body(r).contains("member-b"), clue = body(r))
  }

  test("a set's order reads a fetch") {
    // `b` sorts by how many points the history has, `a` by one.
    val byPast = SortTerm(
      ValueExpr(
        Value.Entity(None, Part.Whole),
        List(Step.Cel("value.state == 'b' ? size(past) : 1", past()))
      ),
      SortTerm.Dir.Desc
    )
    val d = dashboard(set(None, List(byPast)))
    assertEquals(d.validate(), Nil)
    val r = Renderer.create(d)
    def order = {
      val html = body(r)
      html.indexOf("member-b") < html.indexOf("member-a")
    }
    answer(r, some)
    assert(order, clue = body(r))
    answer(r, empty)
    assert(!order, clue = body(r))
  }

  test("a state surface's condition reads a fetch") {
    val surface = Surface(
      item("shown"),
      bakeInto = Some(NodeId.derived("c")),
      bakeAs = Some("t0"),
      bakeIndex = Some(0),
      activation = Activation.State(Some(hasPast))
    )
    assertEquals(
      dashboard().copy(surfaces = Map("t0" -> surface)).decisionFetches,
      List("surface 't0''s condition" -> history)
    )
    val decided = Decided()
    val g = new SurfaceGraph(
      Map(
        "t0" -> Conds.compiled(
          Ready.Compile(Evaluator.Scope("")).surface(surface)
        )
      ),
      Map.empty,
      new MemberGraph(Map.empty, Map.empty),
      decided
    )
    val read = SlotAsk(history, None).resolve(Map.empty)
    def answered(json: String) = decided.answer(f =>
      fh.view.history.Series.celPoints(
        FetchSnapshot
          .of(Map(read -> Staged(1L, json)))
          .shared(SlotAsk(f, None))
      )
    )
    assert(
      intercept[FHError](g.resolveActiveByState(NodeId.derived("c"), states()))
        .getMessage
        .contains("where none is answered")
    )
    answered(some)
    assertEquals(g.resolveActiveByState(NodeId.derived("c"), states()), Some(0))
    answered(empty)
    assertEquals(g.resolveActiveByState(NodeId.derived("c"), states()), None)
  }

  test("a press on a set decides its members by a fetch") {
    // As `tapMod.tapRoute` writes it: the set's tally, as JSON.
    val tally =
      """{"kind":"Present","candidates":["sensor.a","sensor.b"],
        |"when":{"sensor.b":{"source":{"kind":"Cel","src":"size(past) > 0",
        |"inputs":{"past":{"source":{"kind":"Fetch","fetch":{
        |"provider":"history","entity":"sensor.left","window":{
        |"kind":"Statistics","period":"5minute","span":86400,
        |"fallback":true}}}}}}}}}""".stripMargin
    def lit(s: String) = TemplateSlot(ValueExpr.literal(s))
    val d = dashboard(
      LayoutNode.Component(
        "call",
        templateSlots = Map(
          Dashboard.ServiceSlot -> lit("light/turn_off"),
          Dashboard.TargetKindSlot -> lit("set"),
          Dashboard.TargetIdSlot -> lit("0123456789abcdef"),
          Dashboard.TargetSetSlot -> lit(tally)
        )
      )
    )
    assertEquals(d.validate(), Nil)
    assertEquals(
      d.decisionFetches.map((where, f) => where -> f.entity),
      List("a set a press acts on" -> "sensor.left")
    )
    val call = d.validated().toOption.get.ready.calls.values.collectFirst {
      case s: Ready.SetCall => s
    }.get
    val r = Renderer.create(d)
    answer(r, some)
    assertEquals(r.pressed(call, states()), List("sensor.a", "sensor.b"))
    answer(r, empty)
    assertEquals(r.pressed(call, states()), List("sensor.a"))
  }

  test("a decision's fetch cannot read a viewer's variable") {
    val chosen = FetchTemplate(
      "sensor.left",
      Choice.Chosen("window", Map("day" -> Windows.day))
    )
    val guard = ValueExpr(Value.Cel("size(past) > 0", past(chosen)))
    val errs = dashboard(set(Some(guard))).validate()
    assert(
      errs.exists(
        _.contains(
          "a set's clause reads the fetch '" + chosen.key + "': a decision " +
            "is made for every viewer at once, so its fetch cannot read the " +
            "variable 'window'"
        )
      ),
      clue = errs
    )
  }

  test("a dashboard whose decisions read no fetch answers nothing") {
    val r = Renderer.create(dashboard(set(None), item("plain")))
    r.answerDecisions(_ => IO(fail("asked for an answer"))).unsafeRunSync()
    assert(body(r).contains("member-a"))
  }
}
