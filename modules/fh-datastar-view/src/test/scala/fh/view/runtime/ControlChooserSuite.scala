package fh.view.runtime

import fh.view.model.NodeId
import fh.view.testkit.FixtureEntity
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** One main-control switch over sliders that are `.fromChooser()`: a
  * `c.controlChooser`'s, or a group head's.
  */
class ControlChooserSuite extends CatsEffectSuite {

  private def light(id: String, modes: List[String]): FixtureEntity =
    FixtureEntity(
      id,
      "on",
      Map(
        "supported_color_modes" -> Json.arr(modes.map(Json.fromString)*),
        "color_mode" -> Json.fromString(modes.head),
        "min_color_temp_kelvin" -> Json.fromInt(2000),
        "max_color_temp_kelvin" -> Json.fromInt(6500),
        "brightness" -> Json.fromInt(128)
      )
    )

  // light.b has no colour, so following onto colour greys its head.
  private val lights = List(
    light("light.a", List("color_temp", "hs")),
    light("light.b", List("color_temp"))
  )

  private def entry(card: String) =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
       |import "@fh-home/dump.pkl" as dump
       |
       |card = $card
       |""".stripMargin

  private def declarers(page: String): List[String] =
    """data-fh-url:control="\['v\.([A-Za-z0-9_]+)\.control'""".r
      .findAllMatchIn(page)
      .map(_.group(1))
      .toList

  private def count(s: String, in: String) = s.r.findAllMatchIn(in).size

  /** The page, and what a write of `value` on `declarer` sends. */
  private def choose(card: String, declarer: String, value: String) =
    TestServer
      .fromWorkspace("control-chooser", entry(card), lights)
      .use { ts =>
        for {
          viewer <- ts.viewer()
          key = VarKey(NodeId.derived(declarer), "control")
          wrote <- ts.postResult(
            s"sse/var/${ts.slug}/$declarer/control/$value?group=${key.group}",
            body = s"""{"${Server.ConnSignal}":"${viewer.document.conn}"}"""
          )
          frames <- viewer.session.takeBacklog
        } yield (
          viewer.document.html,
          wrote,
          frames.flatMap(_.data).mkString("\n")
        )
      }
      .timeout(60.seconds)

  test(
    "sliders following a chooser switch together, greyed where a light lacks the control"
  ) {
    choose(
      """(c.controlChooser) {
        |  children {
        |    c.entitySlider(dump.lights.a).fromChooser()
        |    c.entitySlider(dump.lights.b).fromChooser()
        |  }
        |}""".stripMargin,
      "c",
      "colour"
    ).map { (page, wrote, sent) =>
      // Only the chooser declares: the sliders have no switch of their own.
      assertEquals(declarers(page), List("c"), clue = page)
      assertEquals(wrote._1, org.http4s.Status.NoContent, clue = wrote)
      assertEquals(count("""max="360"""", sent), 1, clue = sent)
      assert(sent.contains("fh-slider-greyed"), sent)
    }
  }

  test("a set's sliders follow a chooser above the set") {
    choose(
      """(c.controlChooser) {
        |  children {
        |    q.from(List(dump.lights.a, dump.lights.b))
        |      .render((l) -> c.entitySlider(l).fromChooser())
        |      .build()
        |  }
        |}""".stripMargin,
      "c",
      "colour"
    ).map { (page, wrote, sent) =>
      assertEquals(declarers(page), List("c"), clue = page)
      assertEquals(wrote._1, org.http4s.Status.NoContent, clue = wrote)
      assertEquals(count("""max="360"""", sent), 1, clue = sent)
      assert(sent.contains("fh-slider-greyed"), sent)
    }
  }

  test("a set among a group's rows follows the head") {
    choose(
      """(c.entitySlider(dump.lights.a)) {
        |  members { q.from(List(dump.lights.b)).render((l) -> c.entitySlider(l).fromChooser()).build() }
        |}""".stripMargin,
      "c",
      "colour"
    ).map { (page, wrote, sent) =>
      // The set's row reads the head's control instead of declaring its own:
      // the head goes to colour, the row (no colour) to its brightness, greyed.
      assertEquals(declarers(page), List("c"), clue = page)
      assertEquals(wrote._1, org.http4s.Status.NoContent, clue = wrote)
      assertEquals(count("""max="360"""", sent), 1, clue = sent)
      assert(sent.contains("c_0_light_b"), sent)
      assert(sent.contains("fh-slider-greyed"), sent)
    }
  }
}
