package fh.view.runtime

import cats.effect.IO
import fh.view.testkit.FixtureEntity
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.Status

import scala.concurrent.duration.*

/** `c.entitySlider(l)` on a colour light end to end (#547): one head at a time,
  * a mode button per capability, a press that is a node-variable write swapping
  * the panel, and a hue drag that reaches HA as `hs_color = [h, 100]`.
  */
class LightSliderSuite extends CatsEffectSuite {

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

  private val dimmer = FixtureEntity(
    "light.dimmer",
    "on",
    Map(
      "friendly_name" -> Json.fromString("Dimmer"),
      "supported_color_modes" -> Json.arr(Json.fromString("brightness")),
      "brightness" -> n(90)
    )
  )

  private def entry(light: FixtureEntity) =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) { children { c.entitySlider(dump.entities.${light.dumpKey}) } }
       |""".stripMargin

  private def served[A](light: FixtureEntity)(f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("lamp", entry(light), List(light))
      .use(f)
      .timeout(60.seconds)

  private val modeRoute = """sse/var/[^/]+/([A-Za-z0-9_]+)/mode/(\d)""".r

  test(
    "first paint is the brightness slider, its button stepping to the next"
  ) {
    served(lamp) { ts =>
      ts.page().map { html =>
        // Only the shown mode's button is in the page: no script picks it.
        assert(html.contains("""aria-label="Brightness""""), clue = html)
        List("Colour temperature", "Colour").foreach { l =>
          assert(!html.contains(s"""aria-label="$l""""), clue = l)
        }
        assertEquals(
          modeRoute.findAllMatchIn(html).map(_.group(2)).toList,
          List("1")
        )
        assert(html.contains("{'brightness': Number("), clue = html)
        // A panel not shown is not rendered.
        assert(!html.contains("{'hs_color': "), clue = html)
        assert(!html.contains("{'color_temp_kelvin': "), clue = html)
      }
    }
  }

  test("pressing the colour mode swaps in the hue slider at the light's hue") {
    served(lamp) { ts =>
      for {
        doc <- ts.load()
        declarer = modeRoute
          .findFirstMatchIn(doc.html)
          .fold(fail(s"no mode button on the page: ${doc.html}"))(_.group(1))
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
        assert(sent.contains("{'hs_color': [Number($"), clue = sent)
        assert(sent.contains(",100] } } }"), clue = sent)
        // `hs_color[0]`, rounded: the handle starts where the light is.
        assert(sent.contains("""value="31""""), clue = sent)
        assert(sent.contains(s"\"_var_${declarer}__mode\":\"2\""), clue = sent)
      }
    }
  }

  test("a hue commit reaches HA as an hs_color list, from any mode") {
    // Declared by the dashboard although its panel is not open: a panel's taps
    // are the dashboard's whether or not this viewer has it showing.
    served(lamp) { ts =>
      for {
        status <- ts.post(
          s"sse/call/${ts.slug}/light/turn_on/entity/light.lamp",
          body = """{"data":{"hs_color":[120,100]}}"""
        )
        calls <- ts.fake.recordedCalls
      } yield {
        assertEquals(status, Status.NoContent)
        assertEquals(
          calls.map(_.serviceData.hcursor.downField("hs_color").focus),
          Vector(Some(Json.arr(n(120), n(100))))
        )
      }
    }
  }

  test(
    "on syncRows, a group's rows switch with its head, greyed where they lack it"
  ) {
    val lamp2 = lamp.copy(entityId = "light.lamp2")
    val card =
      s"c.entitySlider(dump.entities.${lamp.dumpKey}).syncRows().withSubSliders(List(" +
        s"c.entitySlider(dump.entities.${lamp2.dumpKey}), " +
        s"c.entitySlider(dump.entities.${dimmer.dumpKey})))"
    val groupEntry =
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |card = (c.column) { children { $card } }
         |""".stripMargin
    TestServer
      .fromWorkspace("lamps", groupEntry, List(lamp, lamp2, dimmer))
      .use { ts =>
        for {
          doc <- ts.load()
          declarers = modeRoute.findAllMatchIn(doc.html).map(_.group(1)).toSet
          session <- ts.sessions.get(doc.conn)
          result <- ts.postResult(
            s"sse/var/${ts.slug}/${declarers.head}/mode/2",
            body = s"""{"${Server.ConnSignal}":"${doc.conn}"}"""
          )
          sent <- session
            .fold(IO.pure(List.empty[SseFrame]))(_.takeBacklog)
            .map(_.flatMap(_.data).mkString("\n"))
        } yield {
          // One declarer: only the head has a pill.
          assertEquals(declarers.size, 1, clue = declarers)
          assert(!doc.html.contains(" fh-slider-greyed\""), clue = doc.html)
          assertEquals(result._1, Status.NoContent, clue = result._2)
          // The head and the colour row both slide the hue now.
          assertEquals(
            "\\{'hs_color': \\[Number\\(\\$".r.findAllMatchIn(sent).size,
            2,
            clue = sent
          )
          // The dimmer stands in with its brightness, dimmed and disabled.
          assert(sent.contains(" fh-slider-greyed\""), clue = sent)
        }
      }
      .timeout(60.seconds)
  }

  test("a light that only dims is one slider and no buttons") {
    served(dimmer) { ts =>
      ts.page().map { html =>
        assert(html.contains("{'brightness': Number("), clue = html)
        assertEquals(modeRoute.findAllMatchIn(html).size, 0, clue = html)
      }
    }
  }
}
