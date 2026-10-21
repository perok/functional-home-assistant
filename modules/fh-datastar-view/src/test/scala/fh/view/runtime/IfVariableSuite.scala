package fh.view.runtime

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.Status

import scala.concurrent.duration.*

/** `c.iff(v.valueIs(x))`: an If chosen by a node variable, per viewer. */
class IfVariableSuite extends CatsEffectSuite {

  private val entry =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |
      |local window = c.variable("window")
      |
      |card = (c.column) {
      |  vars { window.defaultingTo("24h") }
      |  children {
      |    c.iff(window.valueIs("30d")).then(c.title("Month")).`else`(c.title("Other"))
      |    // No else: still takes every value, showing nothing at the others.
      |    c.iff(window.valueIs("7d")).then(c.title("Week"))
      |  }
      |}
      |""".stripMargin

  test("the branch at the declared value, at a write, and at any other value") {
    TestServer
      .fromWorkspace("if-variable", entry, Nil)
      .use { ts =>
        def write(value: String, conn: String) =
          ts.postResult(
            s"sse/var/${ts.slug}/c/window/$value?group=var_c__window",
            body = s"""{"${Server.ConnSignal}":"$conn"}"""
          )
        for {
          page <- ts.page()
          conn <- ts.load().map(_.conn)
          session <- ts.sessions.get(conn)
          month <- write("30d", conn)
          afterMonth <- session.fold(IO.pure(List.empty[SseFrame]))(
            _.takeBacklog
          )
          week <- write("7d", conn)
          afterWeek <- session.fold(IO.pure(List.empty[SseFrame]))(
            _.takeBacklog
          )
          other <- write("1h", conn)
          afterOther <- session.fold(IO.pure(List.empty[SseFrame]))(
            _.takeBacklog
          )
        } yield {
          def text(frames: List[SseFrame]) =
            frames.flatMap(_.data).mkString("\n")
          assert(page.contains("Other") && !page.contains("Month"), page)
          assert(!page.contains("Week"), page)
          // None of the three is refused: each If has a member for every value.
          assertEquals(
            List(month, week, other).map(_._1),
            List.fill(3)(Status.NoContent),
            clue = List(month, week, other)
          )
          assert(text(afterMonth).contains("Month"), text(afterMonth))
          assert(text(afterWeek).contains("Week"), text(afterWeek))
          assert(text(afterWeek).contains("Other"), text(afterWeek))
          assert(!text(afterOther).contains("Week"), text(afterOther))
        }
      }
      .timeout(60.seconds)
  }
}
