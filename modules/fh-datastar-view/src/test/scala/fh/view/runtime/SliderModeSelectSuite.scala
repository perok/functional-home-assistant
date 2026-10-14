package fh.view.runtime

import cats.effect.IO
import fh.view.testkit.FixtureEntity
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.Status

import scala.concurrent.duration.*

/** `c.entitySlider(l).modeStyle("dropdown")`: the slider's `mode` chosen from a
  * `c.select` on its pill, the label and the head both the server's.
  */
class SliderModeSelectSuite extends CatsEffectSuite {

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
      "color_temp_kelvin" -> n(3000),
      "brightness" -> n(180),
      "hs_color" -> Json.arr(n(30), n(80))
    )
  )

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children { c.entitySlider(dump.entities.${lamp.dumpKey}).modeStyle("dropdown") }
       |}
       |""".stripMargin

  private val modeRoute = """sse/var/[^/]+/([A-Za-z0-9_]+)/mode/(\d)""".r

  private val label =
    """<summary class="fh-select-summary"><span id="[^"]+"><div[^>]*><span>([^<]*)</span>""".r

  private def served[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("lamp", entry, List(lamp))
      .use(f)
      .timeout(60.seconds)

  test("the first HTML names the shown mode, and the list offers every one") {
    served { ts =>
      for {
        plain <- ts.page()
        declarer = modeRoute
          .findFirstMatchIn(plain)
          .fold(fail(s"no mode choice on the page: $plain"))(_.group(1))
        linked <- ts.page(s"?v.$declarer.mode=1")
      } yield {
        assertEquals(
          label.findAllMatchIn(plain).map(_.group(1)).toList,
          List("Brightness")
        )
        assertEquals(
          modeRoute.findAllMatchIn(plain).map(_.group(2)).toList,
          List("0", "1", "2"),
          plain
        )
        // No cycling button beside the select.
        assert(!plain.contains("fh-mode"), plain)
        assertEquals(
          label.findAllMatchIn(linked).map(_.group(1)).toList,
          List("Colour temperature"),
          linked
        )
        assert(linked.contains("max=\"6500\""), linked)
      }
    }
  }

  test("a choice swaps in that mode's head and label") {
    served { ts =>
      for {
        doc <- ts.load()
        declarer = modeRoute
          .findFirstMatchIn(doc.html)
          .fold(fail(s"no mode choice on the page: ${doc.html}"))(_.group(1))
        session <- ts.sessions.get(doc.conn)
        result <- ts.postResult(
          s"sse/var/${ts.slug}/$declarer/mode/2",
          body = s"""{"${Server.ConnSignal}":"${doc.conn}"}"""
        )
        sent <- session
          .fold(IO.pure(List.empty[SseFrame]))(_.takeBacklog)
          .map(_.flatMap(_.data).mkString("\n"))
      } yield {
        assertEquals(result._1, Status.NoContent, clue = result._2)
        assert(sent.contains("max=\"360\""), clue = sent)
        assert(sent.contains("><span>Colour</span></div>"), clue = sent)
        assert(sent.contains(s"\"_var_${declarer}__mode\":\"2\""), clue = sent)
      }
    }
  }
}
