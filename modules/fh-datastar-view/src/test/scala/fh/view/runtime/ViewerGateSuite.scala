package fh.view.runtime

import cats.effect.IO
import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}
import fh.view.history.Windows
import fh.view.model.*
import fh.view.testkit.DashboardBuilders.{choices, declared}
import fh.view.testkit.{FixtureEntity, Sets, Slots, TestCard}

import scala.concurrent.duration.*

/** A `valueWhile` reading a node variable is decided at the viewer's values: a
  * slot shown while `mode` is `b` is empty for a viewer on `a`, its history is
  * asked for only the viewer it holds for, and a write moves its writer alone.
  */
class ViewerGateSuite extends ServerHarness {

  private val history = FetchTemplate("sensor.left", Choice.Fixed(Windows.day))
  private val read = SlotRead(
    FetchRequest.History("sensor.left", Windows.day),
    Step.Json
  )
  private val answers =
    Map(read -> Staged(7L, """{"points":[[0,0.0],[60000,101.0]]}"""))

  private val isB = ValueExpr(
    Value.Var("mode"),
    List(Step.Compare(Op.Eq, Value.Literal(Lit.Text("b"))))
  )

  /** The peak of the history, as `ValueWhileSuite` reads it. */
  private val peak = ValueExpr(
    Value.Entity(Some("sensor.left"), Part.Whole),
    List(
      Step.Cel(
        "math.greatest(past.transformList(i, p, p[1]))",
        Map("past" -> ValueExpr(Value.Fetch(history)))
      )
    )
  )

  private def gated(of: ValueExpr, signal: Option[SignalBind] = None) =
    LayoutNode.Component(
      "gated",
      slots = Slots.values(
        Map(
          "entity_id" -> TemplateSlot(ValueExpr.literal("sensor.left")),
          "of" -> TemplateSlot(of, valueWhile = Some(isB), signal = signal)
        )
      )
    )

  private val cards = Sets.cards ++ Map(
    "gated" -> TestCard("<b>{{of}}</b>"),
    "box" -> TestCard("<div>{{{children}}}</div>", regions = Set("children")),
    "choice" -> fh.view.testkit.DashboardBuilders.choiceCard
  )

  private def dash(
      kids: List[LayoutNode],
      vars: List[Dashboard.Declaration] = declared("mode" -> "a")
  ) = Dashboard(
    cards = cards,
    card = LayoutNode.Component(
      "box",
      slots = Slots.kids(kids*),
      vars = vars,
      id = Some("panel")
    )
  )

  private val mode = VarKey(NodeId.derived("panel"), "mode")
  private val withGate = dash(choices("mode", "a", "b") :+ gated(peak))

  private val states = Map(
    "sensor.left" -> EntityState("sensor.left", "46", Map.empty)
  )

  private def page(r: Renderer, on: String): String = {
    val env = r.vars.env(Map(mode -> on))
    r.renderBodyTraced(
      Shared.of(states).viewing(env),
      Viewing(FetchSnapshot.of(answers, env))
    ).html
  }

  test("a valueWhile reading a variable is no build error") {
    assertEquals(withGate.validate(), Nil)
  }

  test("it still needs a declarer, and no signal") {
    val undeclared = dash(List(gated(peak)), vars = Nil).validate()
    assert(
      undeclared.exists(_.contains("which no ancestor declares")),
      clue = undeclared
    )
    val signalled =
      dash(choices("mode", "a", "b") :+ gated(peak, Some(SignalBind.Text)))
        .validate()
    assert(
      signalled.exists(_.contains("signal, which every viewer shares")),
      clue = signalled
    )
  }

  test(
    "two viewers at one version: the slot is empty for one, read for the other"
  ) {
    val r = Renderer.create(withGate)
    assert(!page(r, "a").contains("101"), clue = page(r, "a"))
    assert(page(r, "b").contains("<b>101</b>"), clue = page(r, "b"))
  }

  test("its fetch is asked only for the viewer it holds for") {
    val r = Renderer.create(withGate)
    def asked(on: String) =
      r.fetchesForPage(
        Shared.of(states).viewing(r.vars.env(Map(mode -> on))),
        r.vars.env(Map(mode -> on))
      )
    assertEquals(asked("a"), Nil)
    assertEquals(asked("b"), List(read))
  }

  private def drain(session: Option[Session]): IO[String] =
    session
      .fold(IO.pure(List.empty[SseFrame]))(_.takeBacklog)
      .map(_.flatMap(_.data).mkString("\n"))

  test("a write re-renders the slot for its writer alone") {
    val live = dash(
      choices("mode", "a", "b") :+ LayoutNode.Component(
        "gated",
        slots = Slots.values(
          Map(
            "of" -> TemplateSlot(
              ValueExpr(Value.Entity(Some("sensor.left"), Part.State)),
              valueWhile = Some(isB)
            )
          )
        )
      )
    )
    TestServer
      .resource(live, List(FixtureEntity("sensor.left", "46")))
      .use { ts =>
        for {
          a <- ts.load().map(_.conn)
          b <- ts.load().map(_.conn)
          sa <- ts.sessions.get(a)
          sb <- ts.sessions.get(b)
          _ <- drain(sa)
          _ <- drain(sb)
          _ <- ts.choose(a, mode, Some("b"))
          sentA <- drain(sa)
          sentB <- drain(sb)
        } yield {
          assert(sentA.contains("46"), clue = sentA)
          assertEquals(sentB, "")
        }
      }
      .timeout(30.seconds)
  }
}
