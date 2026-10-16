package fh.view.runtime

import cats.effect.IO
import fh.view.testkit.FixtureEntity
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.Status

import scala.concurrent.duration.*

/** `c.view.colourWheel(l.colour)` on a slider end to end: the pill's wheel
  * button opens a popup with the wheel, which starts at the light's `hs_color`
  * and sets one the hue track never could, a pastel.
  */
class ColourWheelSuite extends CatsEffectSuite {

  private def n(i: Int) = Json.fromInt(i)

  private val lamp = FixtureEntity(
    "light.lamp",
    "on",
    Map(
      "friendly_name" -> Json.fromString("Lamp"),
      "supported_color_modes" -> Json.arr(
        Json.fromString("color_temp"),
        Json.fromString("hs")
      ),
      "min_color_temp_kelvin" -> n(2000),
      "max_color_temp_kelvin" -> n(6500),
      "brightness" -> n(180),
      "hs_color" -> Json.arr(Json.fromDoubleOrNull(30.5), n(80))
    )
  )

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  local l = dump.entities.${lamp.dumpKey}
       |  children {
       |    c.entitySlider(l).modes(List("brightness", "colourTemp")).buttons(List(c.view.colourWheel(l.colour)))
       |  }
       |}
       |""".stripMargin

  private def served[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("wheel", entry, List(lamp))
      .use(f)
      .timeout(60.seconds)

  private val opens = """sse/surface/[^/]+/open/([A-Za-z0-9_]+)""".r

  test("the wheel button opens the wheel, at the light's colour") {
    served { ts =>
      for {
        doc <- ts.load()
        surface = opens
          .findFirstMatchIn(doc.html)
          .map(_.group(1))
          .getOrElse(fail("no popup to open", clues(doc.html)))
        session <- ts.sessions.get(doc.conn)
        status <- ts.post(
          s"sse/surface/${ts.slug}/open/$surface",
          body = s"""{"${Server.ConnSignal}":"${doc.conn}"}"""
        )
        queued <- session.fold(IO.pure(List.empty[SseFrame]))(_.takeBacklog)
      } yield {
        assertEquals(status, Status.NoContent)
        val patch = queued.flatMap(_.data).mkString
        assert(patch.contains("""class="fh-wheel""""), clue = patch)
        assert(patch.contains("--fh-h:30.5deg;--fh-s:0.8"), clue = patch)
        assert(patch.contains("background:hsl(31,80%,50%)"), clue = patch)
      }
    }
  }

  test("a pastel from the wheel reaches HA as hs_color") {
    served { ts =>
      for {
        status <- ts.post(
          s"sse/call/${ts.slug}/light/turn_on/entity/light.lamp",
          body = """{"data":{"hs_color":[200,35]}}"""
        )
        calls <- ts.fake.recordedCalls
      } yield {
        assertEquals(status, Status.NoContent)
        assertEquals(
          calls.map(_.serviceData.hcursor.downField("hs_color").focus),
          Vector(Some(Json.arr(n(200), n(35))))
        )
      }
    }
  }
}
