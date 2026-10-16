package fh.view.runtime

import cats.effect.IO
import fh.view.testkit.FixtureEntity
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.Status

import scala.concurrent.duration.*

/** `c.its.mainControl.style("dropdown")`: the slider's main control chosen from
  * a `c.select` on its pill, the label and the head both the server's.
  */
class SliderControlSelectSuite extends CatsEffectSuite {

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
       |  children { c.entitySlider(dump.entities.${lamp.dumpKey}).buttons(List(c.its.mainControl.style("dropdown"))) }
       |}
       |""".stripMargin

  private val controlRoute =
    """sse/var/[^/]+/([A-Za-z0-9_]+)/control/([A-Za-z]+)""".r

  private val label =
    // A control's icon is the face; its label is the icon's title.
    """<summary class="fh-select-summary"><span id="[^"]+"><div[^>]*><i class="[^"]*" title="([^"]*)">""".r

  private def served[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("lamp", entry, List(lamp))
      .use(f)
      .timeout(60.seconds)

  test(
    "the first HTML names the shown control, and the list offers every one"
  ) {
    served { ts =>
      for {
        plain <- ts.page()
        declarer = controlRoute
          .findFirstMatchIn(plain)
          .fold(fail(s"no control choice on the page: $plain"))(_.group(1))
        linked <- ts.page(s"?v.$declarer.control=colourTemp")
      } yield {
        assertEquals(
          label.findAllMatchIn(plain).map(_.group(1)).toList,
          List("Brightness")
        )
        assertEquals(
          controlRoute.findAllMatchIn(plain).map(_.group(2)).toList,
          List("brightness", "colourTemp", "colour"),
          plain
        )
        // No cycle beside the dropdown.
        assert(!plain.contains("fh-select-cycles"), plain)
        assertEquals(
          label.findAllMatchIn(linked).map(_.group(1)).toList,
          List("Colour temperature"),
          linked
        )
        assert(linked.contains("max=\"6500\""), linked)
      }
    }
  }

  test("a choice swaps in that control's head and label") {
    served { ts =>
      for {
        doc <- ts.load()
        declarer = controlRoute
          .findFirstMatchIn(doc.html)
          .fold(fail(s"no control choice on the page: ${doc.html}"))(_.group(1))
        session <- ts.sessions.get(doc.conn)
        result <- ts.postResult(
          s"sse/var/${ts.slug}/$declarer/control/colour",
          body = s"""{"${Server.ConnSignal}":"${doc.conn}"}"""
        )
        sent <- session
          .fold(IO.pure(List.empty[SseFrame]))(_.takeBacklog)
          .map(_.flatMap(_.data).mkString("\n"))
      } yield {
        assertEquals(result._1, Status.NoContent, clue = result._2)
        assert(sent.contains("max=\"360\""), clue = sent)
        assert(sent.contains("title=\"Colour\"></i></div>"), clue = sent)
        assert(
          sent.contains(s"\"_var_${declarer}__control\":\"colour\""),
          clue = sent
        )
      }
    }
  }
}
