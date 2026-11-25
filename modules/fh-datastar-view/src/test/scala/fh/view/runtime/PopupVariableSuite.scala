package fh.view.runtime

import cats.effect.IO
import fh.view.model.Dashboard
import fh.view.testkit.HouseFixture
import org.http4s.Status

import scala.concurrent.duration.*

/** A popup is the page's `popups` variable choosing a case of its match
  * (`components/popup.pkl`): opened by a choice, closed by Back, restored by a
  * link, and patched as any pick is.
  */
class PopupVariableSuite extends munit.CatsEffectSuite {

  override def munitIOTimeout: Duration = 90.seconds

  private val kitchen = HouseFixture.kitchenLight

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children {
       |    c.button("Help", c.view.popup("help", new c.Column {
       |      children {
       |        c.title("Helpful")
       |        c.entityCard(dump.entities.${kitchen.dumpKey})
       |      }
       |    }))
       |  }
       |}
       |""".stripMargin

  private val popups = VarKey(Dashboard.PageRootId, "popups")

  private def served[A](f: TestServer => IO[A]): IO[A] =
    TestServer.fromWorkspace("popups", entry, List(kitchen)).use(f)

  private def back(ts: TestServer, conn: String, query: String = "") =
    ts.postResult(
      s"sse/history/${ts.slug}$query",
      body = s"""{"${Server.ConnSignal}":"$conn"}"""
    )

  test("a closed popup is not in the page, and a link opens it in the first HTML") {
    served(ts =>
      for {
        closed <- ts.page()
        linked <- ts.page("?v.popups=help")
      } yield {
        assert(!closed.contains("Helpful"), clue = closed)
        assert(closed.contains("""id="popups"></div>"""), clue = closed)
        assert(linked.contains("Helpful"), clue = linked)
        assert(linked.contains("""<dialog open class="fh-popup">"""), linked)
        // The variable's mirror pushes a history entry per choice.
        assert(linked.contains("data-fh-url:popups__history="), clue = linked)
      }
    )
  }

  test("an open fills #popups and commits the variable; Back clears both") {
    served(ts =>
      for {
        v <- ts.viewer()
        opened <- ts.choose(v.document.conn, popups, Some("help"))
        afterOpen <- v.session.takeBacklog.map(_.map(_.render).mkString("\n"))
        held <- v.session.state.map(_.vars.get(popups))
        closed <- back(ts, v.document.conn)
        afterClose <- v.session.takeBacklog.map(_.map(_.render).mkString("\n"))
        gone <- v.session.state.map(_.vars.get(popups))
      } yield {
        assertEquals(opened._1, Status.NoContent, clue = opened)
        assert(afterOpen.contains("selector #popups"), clue = afterOpen)
        assert(afterOpen.contains("Helpful"), clue = afterOpen)
        assert(
          afterOpen.contains(s""""${popups.committedSignal}":"help""""),
          clue = afterOpen
        )
        assertEquals(held, Some("help"))
        assertEquals(closed._1, Status.NoContent, clue = closed)
        // The pick's leave: the popup's root goes, the host stays.
        assert(
          afterClose.contains("mode remove\ndata: selector #help\n"),
          clue = afterClose
        )
        assert(!afterClose.contains("Helpful"), clue = afterClose)
        assertEquals(gone, None)
      }
    )
  }

  test("Forward opens it again, and a value no popup has is refused") {
    served(ts =>
      for {
        v <- ts.viewer()
        forward <- back(ts, v.document.conn, "?v.popups=help")
        state <- v.session.state.map(_.vars.get(popups))
        refused <- ts.choose(v.document.conn, popups, Some("help")).flatMap(
          _ =>
            ts.postResult(
              s"sse/history/${ts.slug}?v.popups=nope",
              body = s"""{"${Server.ConnSignal}":"${v.document.conn}"}"""
            )
        )
        after <- v.session.state.map(_.vars.get(popups))
      } yield {
        assertEquals(forward._1, Status.NoContent, clue = forward)
        assertEquals(state, Some("help"))
        // Read as a load reads it: the stale value is dropped, so no popup.
        assertEquals(refused._1, Status.NoContent, clue = refused)
        assertEquals(after, None)
      }
    )
  }

  test("an open popup's entity is live, and a closed one costs nothing") {
    served(ts =>
      for {
        open <- ts.viewer("?v.popups=help")
        shut <- ts.viewer()
        _ <- ts.record(kitchen.copy(state = "off"))
        seen <- open.pull.map(_.map(_.render).mkString("\n"))
        unseen <- shut.pull.map(_.map(_.render).mkString("\n"))
      } yield {
        assert(seen.contains("kitchen"), clue = seen)
        assert(!unseen.contains("kitchen"), clue = unseen)
      }
    )
  }
}
