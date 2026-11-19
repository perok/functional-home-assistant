package fh.view.runtime

import fh.view.testkit.Slots

import fh.view.testkit.TestCard
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{CardDef, Dashboard, LayoutNode, NodeId, Slot}
import fh.view.testkit.FixtureEntity
import fh.view.testkit.DashboardBuilders
import fh.view.testkit.DashboardBuilders.declared
import org.http4s.*

import scala.concurrent.duration.*

/** A tab bar's selection as a node variable (issue #209, step 4): the bar
  * declares `tab`, its panel is a choice over it, and a press is a variable
  * write that swaps the panel.
  */
class TabVariableSuite extends ServerHarness {

  private val barCard = TestCard(
    """<div>{{{buttons}}}{{{panel}}}</div>""",
    regions = Set("panel", "buttons")
  )
  private val textCard = TestCard("<p>{{text}}</p>")

  private def text(t: String) =
    LayoutNode.Component("text", slots = Slots.values(Map("text" -> lit(t))))

  private def lit(s: String) =
    fh.view.model.TemplateSlot(fh.view.model.ValueExpr.literal(s))

  /** With a button per tab, and one past them, where it declares `tab`. */
  private def barNode(vars: Map[String, String]) =
    LayoutNode.Component(
      "bar",
      id = Some("bar"),
      vars = declared(vars.toList*),
      slots = Slots.nodes(
        if (vars.contains("tab"))
          Map("buttons" -> DashboardBuilders.choices("tab", "0", "1", "5"))
        else Map.empty
      ) + ("panel" -> Slots.inGroup(
        Slots.tabs("tab", text("first"), text("second"))
      ))
    )

  private val dash = Dashboard(
    cards = Map(
      "bar" -> barCard,
      "text" -> textCard,
      Slots.GroupCard -> Slots.groupCard,
      DashboardBuilders.ChoiceCard -> DashboardBuilders.choiceCard
    ),
    card = barNode(Map("tab" -> "0"))
  )

  private def served[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .resource(dash, List(FixtureEntity("sensor.a", "1")))
      .use(f)
      .timeout(30.seconds)

  private def post(ts: TestServer, conn: String, path: String) =
    ts.postResult(path, body = s"""{"${Server.ConnSignal}":"$conn"}""")

  private def press(ts: TestServer, conn: String, tab: String) =
    ts.choose(conn, VarKey(NodeId.derived("bar"), "tab"), Some(tab))

  private def drain(session: Option[Session]): IO[String] =
    session
      .fold(IO.pure(List.empty[SseFrame]))(_.takeBacklog)
      .map(_.flatMap(_.data).mkString("\n"))

  test("a tab press swaps the panel and commits the variable, not ui_") {
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        session <- ts.sessions.get(conn)
        result <- press(ts, conn, "1")
        open <- session.traverse(_.state.map(_.open))
        sent <- drain(session)
      } yield {
        assertEquals(result._1, Status.NoContent)
        assertEquals(
          open.map(_.intersect(Set("bar_panel_0_t0", "bar_panel_0_t1"))),
          Some(Set("bar_panel_0_t1"))
        )
        assert(sent.contains("second"), clue = sent)
        assert(sent.contains("\"_var_bar__tab\":\"1\""), clue = sent)
        assert(!sent.contains("ui_bar"), clue = sent)
        // The commit rides after the panel, so the highlight agrees only once
        // the panel is in.
        assert(sent.indexOf("second") < sent.indexOf("_var_bar__tab"), sent)
      }
    }
  }

  test("pressing the tab already open re-sends no panel") {
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        session <- ts.sessions.get(conn)
        _ <- press(ts, conn, "0")
        sent <- drain(session)
      } yield assertEquals(sent, """signals {"_var_bar__tab":"0"}""")
    }
  }

  test("a tab index outside the bar is refused, and nothing moves") {
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        session <- ts.sessions.get(conn)
        result <- press(ts, conn, "5")
        chose <- session.traverse(_.state.map(_.vars))
        sent <- drain(session)
      } yield {
        assertEquals(result._1, Status.Ok)
        assert(result._2.contains("0..1"), clue = result._2)
        assertEquals(chose, Some(Map.empty))
        assertEquals(sent, "")
      }
    }
  }

  test("a variable-selected panel is chosen, never opened") {
    // An open would move the panel without the variable, and the next page
    // load would show another tab than the one on screen.
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        result <- post(ts, conn, s"sse/surface/${ts.slug}/open/bar_panel_0_t1")
      } yield {
        assertEquals(result._1, Status.Ok)
        assert(result._2.contains("node variable"), clue = result._2)
      }
    }
  }

  test("a link chooses the tab on the first paint") {
    served(ts =>
      (ts.page(), ts.page("?v.bar.tab=1"), ts.page("?ui.bar=1")).mapN {
        (plain, linked, old) =>
          assert(plain.contains("first") && !plain.contains("second"), plain)
          assert(linked.contains("second") && !linked.contains("first"), linked)
          // An old `ui.` link lands on the declared tab.
          assert(old.contains("first") && !old.contains("second"), old)
      }
    )
  }

  test("a bar's variable must be declared at the bar, at a value a tab takes") {
    val undeclared = dash.copy(card = barNode(Map.empty))
    assert(
      undeclared
        .validate()
        .exists(_.contains("which no ancestor declares")),
      clue = undeclared.validate()
    )
    val outside = dash.copy(card = barNode(Map("tab" -> "2")))
    assert(
      outside
        .validate()
        .exists(_.contains("declared '2', which no case takes")),
      clue = outside.validate()
    )
    assertEquals(dash.validate(), Nil)
    assertEquals(
      Renderer(dash.validated().fold(e => fail(e.mkString), identity)).vars
        .panelsSelectedBy(VarKey(NodeId.derived("bar"), "tab")),
      List(NodeId.derived("bar_panel_0"))
    )
  }
}
