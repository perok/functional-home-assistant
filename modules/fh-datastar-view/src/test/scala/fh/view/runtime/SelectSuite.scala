package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.NodeId
import fh.view.testkit.{FixtureEntity, HouseFixture}
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.Status

import scala.concurrent.duration.*

/** `c.select` as the Pkl library writes it, built and validated by the real
  * pipeline: over a variable a plain node declares, and over an entity.
  */
class SelectSuite extends CatsEffectSuite {

  private val sensor = HouseFixture.outsideTemp

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |local shade = c.variable("shade")
       |
       |card = (c.column) {
       |  vars = shade.declare("24h")
       |  children {
       |    c.select(shade).options(List("24h", "7d"))
       |    c.select(shade).options(List("24h", "7d")).style("dropdown")
       |    c.select(shade).options(List("24h", "7d")).style("cycle")
       |    // A reader: the server refuses a choice nothing on the page reads.
       |    c.historyChart(dump.entities.${sensor.dumpKey}).over(shade.ref)
       |  }
       |}
       |""".stripMargin

  test("over a variable, every style chooses for the declarer above it") {
    TestServer
      .fromWorkspace("select", entry, List(sensor))
      .use(_.page())
      .timeout(60.seconds)
      .map { html =>
        val declarer =
          """data-fh-url:shade="\['v\.([A-Za-z0-9_]+)\.shade'""".r
            .findFirstMatchIn(html)
            .map(_.group(1))
            .getOrElse(fail("the declarer carries no mirror", clues(html)))
        val key = VarKey(NodeId.derived(declarer), "shade")
        // Buttons and dropdown offer both values; the cycle's two buttons
        // each choose the other.
        assertEquals(
          s"/$declarer/shade/7d\\?group=${key.group}".r
            .findAllMatchIn(html)
            .size,
          3,
          clue = html
        )
        // The theme expands `fh-tabs`, so only the select's own class is fixed.
        assert(html.contains("""fh-select">"""), html)
        assert(html.contains("""<details class="fh-select-menu""""), html)
        assert(html.contains("""class="fh-select-cycle""""), html)
        assert(
          html.contains(s"$$${key.pendingSignal} || $$${key.committedSignal}"),
          html
        )
      }
  }

  test("a choice commits, so every select of the variable moves") {
    TestServer
      .fromWorkspace("select", entry, List(sensor))
      .use { ts =>
        for {
          conn <- ts.load().map(_.conn)
          session <- ts.sessions.get(conn)
          result <- ts.postResult(
            s"sse/var/${ts.slug}/c/shade/7d?group=var_c__shade",
            body = s"""{"${Server.ConnSignal}":"$conn"}"""
          )
          chose <- session.traverse(_.state.map(_.vars))
          queued <- session.fold(IO.pure(List.empty[SseFrame]))(_.takeBacklog)
        } yield {
          assertEquals(result._1, Status.NoContent, clue = result)
          assertEquals(
            chose,
            Some(Map(VarKey(NodeId.derived("c"), "shade") -> "7d"))
          )
          assertEquals(
            queued.lastOption.flatMap(_.data),
            Some("""signals {"_var_c__shade":"7d"}""")
          )
        }
      }
      .timeout(60.seconds)
  }

  private def bakedEntry(declared: String) =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |
       |local shade = c.variable("shade")
       |
       |card = (c.column) {
       |  vars = shade.declare("$declared")
       |  children {
       |    c.select(shade).options(List("24h", "7d", "30d")).style("dropdown")
       |    c.select(shade).options(List("24h", "7d", "30d")).style("cycle")
       |  }
       |}
       |""".stripMargin

  /** What the dropdown's summary and the cycle's face show. */
  private def shown(html: String): (List[String], List[String]) =
    (
      """<summary class="fh-select-summary"><span id="[^"]+"><div[^>]*><span>([^<]*)</span>""".r
        .findAllMatchIn(html)
        .map(_.group(1))
        .toList,
      """class="fh-select-cycle" title="([^"]*)"""".r
        .findAllMatchIn(html)
        .map(_.group(1))
        .toList
    )

  test(
    "over a variable, the first HTML shows the viewer's option, from the declaration or the URL"
  ) {
    TestServer
      .fromWorkspace("select-baked", bakedEntry("7d"), Nil)
      .use(ts => (ts.page(), ts.page("?v.c.shade=30d")).tupled)
      .timeout(60.seconds)
      .map { case (declared, linked) =>
        assertEquals(shown(declared), (List("7d"), List("7d")), declared)
        assertEquals(shown(linked), (List("30d"), List("30d")), linked)
      }
  }

  test("a baked select is a reader: a choice swaps in the next option") {
    TestServer
      .fromWorkspace("select-baked", bakedEntry("24h"), Nil)
      .use { ts =>
        for {
          conn <- ts.load().map(_.conn)
          session <- ts.sessions.get(conn)
          result <- ts.postResult(
            s"sse/var/${ts.slug}/c/shade/7d?group=var_c__shade",
            body = s"""{"${Server.ConnSignal}":"$conn"}"""
          )
          refused <- ts.postResult(
            s"sse/var/${ts.slug}/c/shade/1y?group=var_c__shade",
            body = s"""{"${Server.ConnSignal}":"$conn"}"""
          )
          queued <- session.fold(IO.pure(List.empty[SseFrame]))(_.takeBacklog)
        } yield {
          assertEquals(result._1, Status.NoContent, clue = result)
          val sent = queued.flatMap(_.data).mkString("\n")
          // Each panel's swap: the label, then the cycle's face.
          assert(sent.contains("><span>7d</span></div>"), clue = sent)
          assertEquals(shown(sent)._2, List("7d"), sent)
          // The cycle's 7d chooses the next, round.
          assert(sent.contains("/c/shade/30d?group="), clue = sent)
          assert(
            refused._2.contains("one of its members' values (24h, 7d, 30d)"),
            clue = refused
          )
        }
      }
      .timeout(60.seconds)
  }

  test("a declared value no option has is a build error naming the options") {
    val errors =
      fh.view.testkit.PklFixture
        .buildDashboard("select-baked", bakedEntry("1y"))
        .validate()
    assert(
      errors.exists(e =>
        e.contains("declared '1y'") &&
          e.contains("not one of its members' values (24h, 7d, 30d)")
      ),
      clue = errors
    )
  }

  private val robot = FixtureEntity(
    "select.robot_mode",
    "turbo",
    Map(
      "options" -> Json.arr(Json.fromString("quiet"), Json.fromString("turbo"))
    )
  )

  private val entityEntry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children {
       |    c.select(dump.entities.${robot.dumpKey}).style("dropdown")
       |    c.select(dump.entities.${robot.dumpKey}).style("cycle")
       |  }
       |}
       |""".stripMargin

  test(
    "over an entity, the dump's options become calls, the state lights one"
  ) {
    TestServer
      .fromWorkspace("select-entity", entityEntry, List(robot))
      .use(_.page())
      .timeout(60.seconds)
      .map { html =>
        // A call per option per style, each to select_option.
        assertEquals(
          "select/select_option' \\+ '/entity/select\\.robot_mode".r
            .findAllMatchIn(html)
            .size,
          4,
          clue = html
        )
        // The dropdown names the state from the first HTML, not a script.
        assert(
          """<summary class="fh-select-summary"><span [^>]*>turbo</span>""".r
            .findFirstIn(html)
            .isDefined,
          html
        )
        // Only `turbo`'s cells are lit: one in the list, one in the cycle.
        assertEquals(
          """class="[^"]*fh-cell[^"]*fh-active""".r.findAllMatchIn(html).size,
          2,
          clue = html
        )
      }
  }
}
