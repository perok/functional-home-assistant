package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{Ix, LayoutNode, NodeId}
import fh.view.testkit.FixtureEntity
import fh.view.testkit.DashboardBuilders.declared
import fh.view.testkit.PklFixture
import org.http4s.*

import scala.concurrent.duration.*

/** A tab bar's selection as a node variable (issue #209, step 4): the bar
  * declares `tab`, its panel is a choice over it, and a press is a variable
  * write that swaps the panel.
  */
class TabVariableSuite extends ServerHarness {

  /** The shipped tab bar, named "bar": tab `0` shows "PanelOne" and a button
    * choosing a tab past the bar, `1` "PanelTwo".
    */
  private val dash = PklFixture.snippet(
    """card = (c.tabs) {
      |  id = "bar"
      |  tabs {
      |    ["A"] { t.label("PanelOne") c.button("Past", c.variable("tab").choose("5")) }
      |    ["B"] { t.label("PanelTwo") }
      |  }
      |}
      |""".stripMargin
  )

  private val bar = dash.card match
    case c: LayoutNode.Component => c

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
        state <- session.traverse(_.state)
        sent <- drain(session)
      } yield {
        assertEquals(result._1, Status.NoContent)
        // The choice is the session's values; the tab is never in its open set.
        assertEquals(
          state.map(_.vars),
          Some(Map(VarKey(NodeId.derived("bar"), "tab") -> "1"))
        )
        assertEquals(state.map(_.open), Some(Set.empty[String]))
        assert(sent.contains("PanelTwo"), clue = sent)
        assert(sent.contains("\"_var_bar__tab\":\"1\""), clue = sent)
        assert(!sent.contains("ui_bar"), clue = sent)
        // The commit rides after the panel, so the highlight agrees only once
        // the panel is in.
        assert(sent.indexOf("PanelTwo") < sent.indexOf("_var_bar__tab"), sent)
      }
    }
  }

  test("a tab switch leaves the host holding the new case, none of the old") {
    val r = Renderer.create(dash)
    val pick = NodeId.derived("bar_panel_0")
    val host = r.hostIx(pick, Shared.of(Map.empty))
    val one = r.index.ixsOf(NodeId.derived("bar_panel_0_0")).head
    val two = r.index.ixsOf(NodeId.derived("bar_panel_0_1")).head
    def under(root: Ix, holds: Map[Ix, Held]) =
      holds.keySet.filter(r.index.contains(root, _))
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        session <- ts.sessions.get(conn)
        _ <- session.traverse(_.takeBacklog)
        _ <- press(ts, conn, "0")
        before <- session.traverse(_.state.map(_.holds))
        _ <- press(ts, conn, "1")
        after <- session.traverse(_.state.map(_.holds))
      } yield {
        assertEquals(
          before.flatMap(_.get(host)).flatMap(_.shows).map(_.toList),
          Some(List(one))
        )
        assert(before.exists(under(one, _).nonEmpty), clue = before)
        assertEquals(
          after.flatMap(_.get(host)).flatMap(_.shows).map(_.toList),
          Some(List(two))
        )
        assertEquals(after.map(under(one, _)), Some(Set.empty[Ix]))
        assert(after.exists(under(two, _).nonEmpty), clue = after)
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
        result <- post(ts, conn, s"sse/surface/${ts.slug}/open/bar_panel_0_1")
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
          assert(
            plain.contains("PanelOne") && !plain.contains("PanelTwo"),
            plain
          )
          assert(
            linked.contains("PanelTwo") && !linked.contains("PanelOne"),
            linked
          )
          // An old `ui.` link lands on the declared tab.
          assert(old.contains("PanelOne") && !old.contains("PanelTwo"), old)
      }
    )
  }

  test("a bar's variable must be declared at the bar, at a value a tab takes") {
    val undeclared = dash.copy(card = bar.copy(vars = Nil))
    assert(
      undeclared
        .validate()
        .exists(_.contains("which no ancestor declares")),
      clue = undeclared.validate()
    )
    val outside = dash.copy(card = bar.copy(vars = declared("tab" -> "2")))
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
