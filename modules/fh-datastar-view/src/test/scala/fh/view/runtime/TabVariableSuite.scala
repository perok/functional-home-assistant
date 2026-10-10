package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Region,
  Surface
}
import fh.view.testkit.FixtureEntity
import fh.view.testkit.TestIds.given
import org.http4s.*

import scala.concurrent.duration.*

/** A tab bar's selection as a node variable (issue #209, step 4): the bar
  * declares `tab`, its panel's bake group is selected by it, and a press is a
  * variable write that swaps the panel.
  */
class TabVariableSuite extends ServerHarness {

  private val barCard = CardDef(
    """<div id="{{hostId}}">{{#panel}}{{{html}}}{{/panel}}</div>""",
    regions = Map("panel" -> Region(Region.Baked))
  )
  private val textCard = CardDef("<p>{{text}}</p>", slots = List("text"))

  private def panel(i: Int, text: String) =
    Surface(
      LayoutNode.Component("text", slots = Map("text" -> lit(text))),
      bakeInto = Some("bar"),
      bakeAs = Some("panel"),
      bakeIndex = Some(i),
      activation = Activation.Var("tab")
    )

  private def lit(s: String) = fh.view.model.SlotSource(literal = Some(s))

  private def barNode(vars: Map[String, String]) =
    LayoutNode.Component("bar", id = Some("bar"), vars = vars)

  private val dash = Dashboard(
    cards = Map("bar" -> barCard, "text" -> textCard),
    card = barNode(Map("tab" -> "0")),
    surfaces = Map("t0" -> panel(0, "first"), "t1" -> panel(1, "second"))
  )

  private def served[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .resource(dash, List(FixtureEntity("sensor.a", "1")))
      .use(f)
      .timeout(30.seconds)

  private def post(ts: TestServer, conn: String, path: String) =
    ts.postResult(path, body = s"""{"${Server.ConnSignal}":"$conn"}""")

  private def drain(session: Option[Session]): IO[String] =
    session
      .fold(IO.pure(List.empty[SseFrame]))(_.control.tryTakeN(None))
      .map(_.flatMap(_.data).mkString("\n"))

  test("a tab press swaps the panel and commits the variable, not ui_") {
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        session <- ts.sessions.get(conn)
        result <- post(ts, conn, s"sse/var/${ts.slug}/bar/tab/1")
        open <- session.traverse(_.open.get)
        sent <- drain(session)
      } yield {
        assertEquals(result._1, Status.NoContent)
        assertEquals(open.map(_.intersect(Set("t0", "t1"))), Some(Set("t1")))
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
        _ <- post(ts, conn, s"sse/var/${ts.slug}/bar/tab/0")
        sent <- drain(session)
      } yield assertEquals(sent, """signals {"_var_bar__tab":"0"}""")
    }
  }

  test("a tab index outside the bar is refused, and nothing moves") {
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        session <- ts.sessions.get(conn)
        result <- post(ts, conn, s"sse/var/${ts.slug}/bar/tab/5")
        chose <- session.traverse(_.vars.get)
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
        result <- post(ts, conn, s"sse/surface/${ts.slug}/open/t1")
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

  test("a bar's variable must be declared at the bar, as a member index") {
    val undeclared = dash.copy(card = barNode(Map.empty))
    assert(
      undeclared
        .validate()
        .exists(_.contains("which no node at or above it declares")),
      clue = undeclared.validate()
    )
    val outside = dash.copy(card = barNode(Map("tab" -> "2")))
    assert(
      outside.validate().exists(_.contains("not a member index (0..1)")),
      clue = outside.validate()
    )
    assertEquals(dash.validate(), Nil)
    assertEquals(
      Renderer
        .fromValidated(dash.validated().fold(e => fail(e.mkString), identity))
        .vars
        .panelsSelectedBy(VarKey(NodeId.derived("bar"), "tab")),
      List(NodeId.derived("bar"))
    )
  }
}
