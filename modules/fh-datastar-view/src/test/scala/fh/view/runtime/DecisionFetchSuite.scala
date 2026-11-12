package fh.view.runtime

import fh.view.testkit.TestCard
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.FHError
import fh.view.fetch.{FetchSnapshot, Staged}
import fh.view.history.Windows
import fh.view.model.*
import fh.view.testkit.Conds

import scala.collection.mutable.ListBuffer

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

  /** Every read a run asks, answered with `json`; each batch noted. */
  private def answering(
      json: String,
      asked: ListBuffer[Set[SlotRead]] = ListBuffer.empty
  ): Set[SlotRead] => IO[FetchSnapshot] = answeringBy(_ => json, asked)

  private def answeringBy(
      json: SlotRead => String,
      asked: ListBuffer[Set[SlotRead]] = ListBuffer.empty
  ): Set[SlotRead] => IO[FetchSnapshot] = reads =>
    IO {
      asked += reads
      FetchSnapshot.of(reads.map(r => r -> Staged(1L, json(r))).toMap)
    }

  private def pastOf(entity: String) =
    FetchTemplate(entity, Choice.Fixed(Windows.day))
  private def readOf(entity: String) =
    SlotAsk(pastOf(entity), Step.Json).resolve(Map.empty)
  private def hasPastOf(entity: String) =
    ValueExpr(Value.Cel("size(past) > 0", past(pastOf(entity))))
      .copy(dependsOn = List(entity))

  private def col(kids: LayoutNode*) = LayoutNode.Component(
    "col",
    regions = Map(LayoutNode.DefaultRegion -> kids.toList)
  )

  /** `inside`, shown while `when` holds. */
  private def guarded(when: ValueExpr, inside: LayoutNode) =
    LayoutNode.SetNode(
      candidates = List("sensor.a"),
      members = Map(
        "sensor.a" -> LayoutNode.SetMember(
          List(LayoutNode.SetClause(Some(when), inside))
        )
      )
    )

  private val unasked: Set[SlotRead] => IO[FetchSnapshot] =
    reads => IO(fail(s"asked for $reads"))

  private def item(label: String) = LayoutNode.Component(
    "item",
    templateSlots = Map("label" -> TemplateSlot(ValueExpr.literal(label)))
  )

  private def dashboard(kids: LayoutNode*) = Dashboard(
    cards = Map(
      "item" -> TestCard("<i>{{label}}</i>"),
      "call" -> TestCard("<button>{{service}}</button>"),
      "col" -> TestCard(
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

  /** The page's run, then its body: what a viewer is sent. */
  private def body(
      r: Renderer,
      answer: Set[SlotRead] => IO[FetchSnapshot] = unasked,
      shared: Shared = Shared.of(states())
  ): String =
    r.decide(answer) { run =>
      val at = shared.asking(run)
      val _ = r.fetchesForPage(Set.empty, at, r.vars.env(Map.empty))
      at
    }.map(
      r.renderBodyTraced(
        _,
        Viewing(Selections.none, FetchSnapshot.empty)
      ).html
    ).unsafeRunSync()

  test("a set's clause reads a fetch, answered before it decides") {
    val d = dashboard(set(Some(hasPast)))
    assertEquals(d.validate(), Nil)
    val r = Renderer.create(d)
    val outside = intercept[FHError](
      r.renderBodyTraced(
        Shared.of(states()),
        Viewing(Selections.none, FetchSnapshot.empty)
      )
    )
    assert(outside.getMessage.contains("outside a run"))
    val asked = ListBuffer.empty[Set[SlotRead]]
    assert(body(r, answering(some, asked)).contains("member-b"))
    assertEquals(
      asked.toList,
      List(Set(SlotAsk(history, Step.Json).resolve(Map.empty)))
    )
    assert(!body(r, answering(empty)).contains("member-b"))
  }

  test(
    "a decision over a fetch is made again only when what it depends on moves"
  ) {
    // The recorder's chain: decided over `some`, then `sensor.left` moves
    // after the answer has gone `empty`.
    def afterMove(when: ValueExpr): String = {
      val r = Renderer.create(dashboard(set(Some(when))))
      val before = r.shared(StoreState(states(), 1L))
      assert(body(r, answering(some), before).contains("member-b"))
      val left = EntityState("sensor.left", "3", Map.empty)
      val moved = StoreState(states() + ("sensor.left" -> left), 2L)
      val after = r.advance(
        before,
        Frame(List(StateChange.Moved("sensor.left", None, left)), moved)
      )
      body(r, answering(empty), after)
    }
    assert(
      !afterMove(hasPast.copy(dependsOn = List("sensor.left")))
        .contains("member-b")
    )
    // Accepted: nothing the decision reads moved, so it is carried.
    assert(afterMove(hasPast).contains("member-b"))
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
    def order(json: String) = {
      val html = body(r, answering(json))
      html.indexOf("member-b") < html.indexOf("member-a")
    }
    assert(order(some))
    assert(!order(empty))
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
    val g = new SurfaceGraph(
      Map(
        "t0" -> Conds.compiled(
          Ready.Compile(Evaluator.Scope("")).surface(surface)
        )
      ),
      Map.empty,
      new MemberGraph(Map.empty, Map.empty)
    )
    val gid = NodeId.derived("c")
    val read = SlotAsk(history, Step.Json).resolve(Map.empty)
    def branch(have: FetchSnapshot) = {
      val run = Shared.Run(have)
      g.resolveActiveByState(gid, Shared.of(states()).asking(run)) -> run.needs
    }
    assert(
      intercept[FHError](
        g.resolveActiveByState(gid, Shared.of(states()))
      ).getMessage
        .contains("outside a run")
    )
    // Unanswered, it stands in as no branch and says what it needs.
    assertEquals(branch(FetchSnapshot.empty), None -> Set(read))
    assertEquals(
      branch(FetchSnapshot.of(Map(read -> Staged(1L, some)))),
      Some(0) -> Set.empty
    )
    assertEquals(
      branch(FetchSnapshot.of(Map(read -> Staged(1L, empty)))),
      None -> Set.empty
    )
  }

  test("a press on a set decides its members by a fetch") {
    // As `tapMod.tapRoute` writes it: the set's tally, as JSON.
    val tally =
      """{"kind":"Present","candidates":["sensor.a","sensor.b"],
        |"when":{"sensor.b":{"source":{"kind":"Cel","src":"size(past) > 0",
        |"wireInputs":{"past":{"source":{"kind":"Fetch","fetch":{
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
    val call = d
      .validated()
      .toOption
      .get
      .ready
      .calls
      .values
      .collectFirst { case s: Ready.SetCall =>
        s
      }
      .get
    val r = Renderer.create(d)
    def pressed(json: String) =
      r.decide(answering(json))(run =>
        r.pressed(call, Shared.of(states()).asking(run))
      ).unsafeRunSync()
    assertEquals(pressed(some), List("sensor.a", "sensor.b"))
    assertEquals(pressed(empty), List("sensor.a"))
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

  private val siblings = dashboard(
    guarded(hasPastOf("sensor.left"), item("left")),
    guarded(hasPastOf("sensor.right"), item("right"))
  )
  private val nested = dashboard(
    guarded(
      hasPastOf("sensor.left"),
      col(item("outer"), guarded(hasPastOf("sensor.right"), item("inner")))
    )
  )
  private val (left, right) = (readOf("sensor.left"), readOf("sensor.right"))

  test("siblings are asked in one round, a decision inside another in two") {
    for (d <- List(siblings, nested)) assertEquals(d.validate(), Nil)
    val once = ListBuffer.empty[Set[SlotRead]]
    val _ = body(Renderer.create(siblings), answering(some, once))
    assertEquals(once.toList, List(Set(left, right)))
    val twice = ListBuffer.empty[Set[SlotRead]]
    assert(
      body(Renderer.create(nested), answering(some, twice)).contains("inner")
    )
    assertEquals(twice.toList, List(Set(left), Set(right)))
  }

  test("a hidden decision's fetch is never asked") {
    val asked = ListBuffer.empty[Set[SlotRead]]
    val html = body(
      Renderer.create(nested),
      answeringBy(r => if (r == left) empty else some, asked)
    )
    assert(!html.contains("outer"))
    assertEquals(asked.toList, List(Set(left)))
  }

  test("a second viewer reuses the first one's decisions") {
    val r = Renderer.create(nested)
    val shared = Shared.of(states())
    assert(body(r, answering(some), shared).contains("inner"))
    assert(body(r, unasked, shared).contains("inner"))
  }

  /** As `Server.recordFrame` plans a frame: one run over "before" and "after".
    */
  private def record(
      r: Renderer,
      before: Shared,
      moved: String,
      answer: Set[SlotRead] => IO[FetchSnapshot]
  ): Unit = {
    val st = EntityState(moved, "3", Map.empty)
    val frame = Frame(
      List(StateChange.Moved(moved, None, st)),
      StoreState(states() + (moved -> st), before.version + 1)
    )
    val after = r.advance(before, frame)
    val _ = r
      .decide(answer)(run =>
        Patches.plan(
          r,
          before.asking(run),
          after.asking(run),
          frame.changes,
          Set.empty
        )
      )
      .unsafeRunSync()
  }

  test("the recorder asks only for the dirty decisions shown") {
    val r = Renderer.create(siblings)
    val before = r.shared(StoreState(states(), 1L))
    assert(body(r, answering(some), before).contains("right"))
    val asked = ListBuffer.empty[Set[SlotRead]]
    record(r, before, "sensor.left", answering(some, asked))
    assertEquals(asked.toList, List(Set(left)))
  }

  test("a hidden dirty decision asks nothing") {
    val r = Renderer.create(nested)
    val before = r.shared(StoreState(states(), 1L))
    val hidden = answeringBy(r => if (r == left) empty else some)
    assert(!body(r, hidden, before).contains("outer"))
    record(r, before, "sensor.right", unasked)
  }

  test("a dashboard whose decisions read no fetch answers nothing") {
    val r = Renderer.create(dashboard(set(None), item("plain")))
    assert(body(r).contains("member-a"))
  }
}
