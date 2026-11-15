package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{CallDecl, IdKind, NodeId}
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

  /** `shade` as the root column `c` declares it. */
  private val shadeOfC = VarKey(NodeId.derived("c"), "shade")

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |local shade = c.variable("shade")
       |
       |card = (c.column) {
       |  vars { shade.defaultingTo("24h") }
       |  children {
       |    c.select(shade).options(List("24h", "7d"))
       |    c.select(shade).options(List("24h", "7d")).style("dropdown")
       |    c.select(shade).options(List("24h", "7d")).style("cycle")
       |    // A reader: the server refuses a choice nothing on the page reads.
       |    c.history.chart(dump.entities.${sensor.dumpKey}).over((p) -> p.history.chosen(shade, p.history.presets))
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
          html
            .sliding(s"$$${key.pendingSignal} = '7d'; @post".length)
            .count(_ == s"$$${key.pendingSignal} = '7d'; @post"),
          3,
          clue = html
        )
        // The theme expands `fh-tabs`, so only the select's own class is fixed.
        assert(html.contains("""fh-select" id="""), html)
        assert(
          html.contains("""<details class="fh-cell fh-select-menu""""),
          html
        )
        assert(html.contains("""class="fh-select-cycles""""), html)
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
          result <- ts.choose(conn, shadeOfC, Some("7d"))
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
       |  vars { shade.defaultingTo("$declared") }
       |  children {
       |    c.select(shade).options(List("24h", "7d", "30d")).style("dropdown")
       |    c.select(shade).options(List("24h", "7d", "30d")).style("cycle")
       |    // A value no option takes, which the write refuses.
       |    c.button("1y", shade.choose("1y"))
       |  }
       |}
       |""".stripMargin

  /** What the dropdown's summary and the cycle's face show. */
  private def shown(html: String): (List[String], List[String]) =
    (
      """<summary class="fh-select-summary"><span id="[^"]+"><span class="fh-cell" id="[^"]+">([^<]*)</span>""".r
        .findAllMatchIn(html)
        .map(_.group(1))
        .toList,
      // The cycle's face is a pill; nothing else here is one.
      """<button class="fh-cell fh-chip[^"]*".*?><span class="fh-text"><span class="fh-text-run">([^<]*)</span>""".r
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
          result <- ts.choose(conn, shadeOfC, Some("7d"))
          refused <- ts.choose(conn, shadeOfC, Some("1y"))
          queued <- session.fold(IO.pure(List.empty[SseFrame]))(_.takeBacklog)
        } yield {
          assertEquals(result._1, Status.NoContent, clue = result)
          val sent = queued.flatMap(_.data).mkString("\n")
          // Each panel's swap: the label, then the cycle's face.
          assert(
            """<span class="fh-cell" id="[^"]+">7d</span>""".r
              .findFirstIn(sent)
              .nonEmpty,
            clue = sent
          )
          assertEquals(shown(sent)._2, List("7d"), sent)
          // The cycle's 7d chooses the next, round.
          assert(
            sent.contains("$_var_c__shade__pending = '30d'; @post"),
            clue = sent
          )
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
      .use(ts => (ts.page(), ts.presses).tupled)
      .timeout(60.seconds)
      .map { (html, presses) =>
        // A call per option per style, each to select_option.
        assertEquals(
          SelectSuite
            .callsIn(html, presses, "select/select_option", robot.entityId),
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
        // Only `turbo`'s roots (an option is no cell, as a tab is not): lit in
        // the list, marked in the cycle, whose face is a pill that a lit look
        // would read as "on".
        assertEquals(
          """class="[^"]*(?:fh-cell|fh-select-opt)[^"]*fh-active""".r
            .findAllMatchIn(html)
            .size,
          1,
          clue = html
        )
        assertEquals(
          """class="[^"]*fh-cell[^"]*fh-chosen""".r.findAllMatchIn(html).size,
          1,
          clue = html
        )
      }
  }

  private val heater = FixtureEntity(
    "climate.heater",
    "heat",
    Map(
      "hvac_modes" -> Json.arr(
        Json.fromString("heat"),
        Json.fromString("auto"),
        Json.fromString("off")
      ),
      "preset_modes" -> Json.arr(
        Json.fromString("eco"),
        Json.fromString("comfort")
      ),
      "preset_mode" -> Json.fromString("comfort")
    )
  )

  private val vent = FixtureEntity(
    "fan.vent",
    "on",
    Map(
      "preset_modes" -> Json
        .arr(Json.fromString("low"), Json.fromString("high")),
      "preset_mode" -> Json.fromString("high")
    )
  )

  private val optionsEntry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children {
       |    c.select(dump.climates.heater.hvacModes)
       |    c.select(dump.climates.heater.presets).style("cycle")
       |    c.select(dump.fans.vent.presets).style("dropdown")
       |  }
       |}
       |""".stripMargin

  test(
    "every kind of options posts its own service and lights where it reads"
  ) {
    TestServer
      .fromWorkspace("select-options", optionsEntry, List(heater, vent))
      .use(ts => (ts.page(), ts.presses).tupled)
      .timeout(60.seconds)
      .map { (html, presses) =>
        def calls(service: String, entity: String): Int =
          SelectSuite.callsIn(html, presses, service, entity)
        assertEquals(calls("climate/set_hvac_mode", heater.entityId), 3, html)
        assertEquals(calls("climate/set_preset_mode", heater.entityId), 2, html)
        assertEquals(calls("fan/set_preset_mode", vent.entityId), 2, html)
        // The HVAC mode is the state; a preset is `preset_mode`.
        assert(
          """<summary class="fh-select-summary"><span [^>]*>high</span>""".r
            .findFirstIn(html)
            .isDefined,
          html
        )
        // `heat` in the buttons and `high` in the list are lit; `comfort` is
        // the cycle's marked face.
        assertEquals(
          """class="[^"]*(?:fh-cell|fh-select-opt)[^"]*fh-active""".r
            .findAllMatchIn(html)
            .size,
          2,
          clue = html
        )
        assertEquals(
          """class="[^"]*fh-cell[^"]*fh-chosen""".r.findAllMatchIn(html).size,
          1,
          clue = html
        )
      }
  }
}

object SelectSuite {

  /** How many presses `html` renders of calls to `service` on `entity`. */
  def callsIn(
      html: String,
      presses: List[(CallDecl.Service, String)],
      service: String,
      entity: String
  ): Int =
    presses
      .collect {
        case (d, url)
            if d.service.literal.contains(service) &&
              d.target
                .get(IdKind.Entity)
                .exists(_.flatMap(_.literal) == List(entity)) =>
          url.split('/').last.takeWhile(_ != '?')
      }
      .distinct
      .map(id => html.split(s"/$id\\?node=", -1).length - 1)
      .sum
}
