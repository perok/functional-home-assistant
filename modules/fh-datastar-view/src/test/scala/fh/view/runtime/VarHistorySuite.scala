package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.NodeId
import fh.view.testkit.HouseFixture
import org.http4s.Status

import scala.concurrent.duration.*

/** `.inHistory()`: a variable whose choices are history entries, and Back or
  * Forward, which post the URL they moved to for the server to read again.
  */
class VarHistorySuite extends munit.CatsEffectSuite {

  override def munitIOTimeout: Duration = 90.seconds

  private val entry =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/core/group.pkl" as groupMod
      |import "@fh-dashboard/core/node.pkl" as nodeMod
      |
      |local tab = c.variable("tab")
      |local window = c.variable("window")
      |local pick = c.variable("pick")
      |
      |card = (c.column) {
      |  vars {
      |    tab.defaultingTo("a").inHistory()
      |    window.defaultingTo("24h")
      |    pick.defaultingTo("x").notInUrl()
      |  }
      |  children {
      |    c.select(tab).options(List("a", "b"))
      |    c.select(window).options(List("24h", "7d"))
      |    c.select(pick).options(List("x", "y"))
      |    groupMod.group("div", "tabbed", nodeMod.match(tab.value, new Mapping {
      |      ["a"] = c.title("shown a")
      |      ["b"] = c.title("shown b")
      |    }))
      |    groupMod.group("div", "windowed", nodeMod.match(window.value, new Mapping {
      |      ["24h"] = c.title("a day")
      |      ["7d"] = c.title("a week")
      |    }))
      |    groupMod.group("div", "picked", nodeMod.match(pick.value, new Mapping {
      |      ["x"] = c.title("picked x")
      |      ["y"] = c.title("picked y")
      |    }))
      |  }
      |}
      |""".stripMargin

  private def key(name: String) = VarKey(NodeId.derived("c"), name)
  private val tab = key("tab")
  private val window = key("window")
  private val pick = key("pick")

  private def served[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("var-history", entry, List(HouseFixture.kitchenLight))
      .use(f)

  private def back(ts: TestServer, conn: String, query: String) =
    ts.postResult(
      s"sse/history/${ts.slug}$query",
      body = s"""{"${Server.ConnSignal}":"$conn"}"""
    )

  test("only a variable in history pushes, and Back posts the URL") {
    served(_.page().map { page =>
      assert(page.contains("data-fh-url:tab__history="), page)
      assert(page.contains("data-fh-url:window="), page)
      assert(page.contains("sse/history/"), page)
    })
  }

  test(
    "Back reads every URL variable again, a parameter gone being the default, and leaves one kept out of the URL"
  ) {
    served(ts =>
      for {
        v <- ts.viewer()
        conn = v.document.conn
        pressed <- List(tab -> "b", window -> "7d", pick -> "y").traverse(
          (k, value) => ts.choose(conn, k, Some(value))
        )
        before <- v.session.state
        _ <- v.session.takeBacklog
        (status, _) <- back(ts, conn, s"?${pick.urlParam}=x")
        frames <- v.session.takeBacklog.map(_.map(_.render).mkString("\n"))
        state <- v.session.state
      } yield {
        assertEquals(pressed.map(_._1), List.fill(3)(Status.NoContent))
        assertEquals(before.vars, Map(tab -> "b", window -> "7d", pick -> "y"))
        assertEquals(status, Status.NoContent)
        assertEquals(state.vars, Map(pick -> "y"))
        assert(frames.contains("shown a"), frames)
        assert(frames.contains(s""""${tab.committedSignal}":"a""""), frames)
        assert(frames.contains(s""""${window.committedSignal}":"24h""""), frames)
        assert(!frames.contains(pick.committedSignal), frames)
      }
    )
  }

  test("a value no reader takes is dropped, and an entry that moves nothing sends nothing") {
    served(ts =>
      for {
        v <- ts.viewer()
        conn = v.document.conn
        _ <- ts.choose(conn, tab, Some("b"))
        _ <- v.session.takeBacklog
        _ <- back(ts, conn, s"?${tab.urlParam}=b&${window.urlParam}=never")
        frames <- v.session.takeBacklog
        state <- v.session.state
      } yield {
        assertEquals(frames, Nil)
        assertEquals(state.vars, Map(tab -> "b"))
      }
    )
  }
}
