package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.runtime.TestServer
import fh.view.testkit.FixtureEntity
import io.circe.Json

/** The colour wheel under a real pointer: the pill's button opens it, a drag
  * moves the knob, and the release sets the colour under the pointer.
  */
class ColourWheelSmokeSuite extends SmokeSuite {

  private def n(i: Int) = Json.fromInt(i)

  private val lamp = FixtureEntity(
    "light.lamp",
    "on",
    Map(
      "friendly_name" -> Json.fromString("Lamp"),
      "supported_color_modes" -> Json.arr(Json.fromString("hs")),
      "brightness" -> n(180),
      "hs_color" -> Json.arr(n(0), n(100))
    )
  )

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |access = c.access.public
       |
       |card = (c.column) {
       |  children { c.entitySlider(dump.entities.${lamp.dumpKey}).colour("popup") }
       |}
       |""".stripMargin

  private def served = TestServer.servedWorkspace("wheel", entry, List(lamp))

  test("a drag on the wheel sets the colour under the pointer") {
    withPageOn(served) { (page, ts) =>
      for {
        box <- IO.blocking {
          page.locator("i.mdi-palette-swatch").click()
          val wheel = page.locator(".fh-wheel")
          assertThat(wheel).isVisible()
          wheel.boundingBox()
        }
        (cx, cy, r) = (
          box.x + box.width / 2,
          box.y + box.height / 2,
          box.width / 2
        )
        _ <- IO.blocking {
          // Start at the top (red), end right of centre at half the radius:
          // hue 90, saturation 50.
          page.mouse().move(cx, cy - r * 0.9)
          page.mouse().down()
          page
            .mouse()
            .move(
              cx + r * 0.25,
              cy,
              new com.microsoft.playwright.Mouse.MoveOptions().setSteps(5)
            )
          page
            .mouse()
            .move(
              cx + r * 0.5,
              cy,
              new com.microsoft.playwright.Mouse.MoveOptions().setSteps(5)
            )
          page.mouse().up()
        }
        calls <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
      } yield {
        val hs = calls.head.serviceData.hcursor
          .downField("hs_color")
          .as[List[Int]]
          .getOrElse(fail(s"no hs_color in ${calls.head}"))
        assertEquals(calls.head.service, "turn_on")
        assert(math.abs(hs(0) - 90) <= 2, clue = hs)
        assert(math.abs(hs(1) - 50) <= 3, clue = hs)
      }
    }
  }
}
