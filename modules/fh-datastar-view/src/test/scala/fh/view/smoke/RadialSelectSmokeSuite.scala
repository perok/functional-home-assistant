package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.runtime.TestServer

/** `c.select(v).style("radial")` under a real pointer: a hold opens the ring, a
  * drag picks the option at the pointer's angle and the release chooses it; a
  * quick tap opens the ring for tapping instead.
  */
class RadialSelectSmokeSuite extends SmokeSuite {

  private val entry =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |
      |access = c.access.public
      |
      |local shade = c.variable("shade")
      |
      |card = (c.column) {
      |  vars = shade.declare("24h")
      |  children {
      |    // Room above, so the ring's top option is on the page.
      |    c.title("Window")
      |    c.label("Hold to choose")
      |    c.label("or tap")
      |    c.select(shade).options(List("24h", "7d", "30d", "1y")).style("radial")
      |  }
      |}
      |""".stripMargin

  private def served = TestServer.servedWorkspace("radial", entry, Nil)

  private def face(page: Page) = page.locator(".fh-radial-face")
  private def ring(page: Page) = page.locator(".fh-radial-ring")

  // The ring itself is a 0×0 origin, which Playwright never counts as
  // visible; its options are what shows.
  private def options(page: Page) = page.locator(".fh-radial-ring > *")

  private def choosing(page: Page, value: String)(act: => Unit): Unit = {
    val _ = page.waitForResponse(
      (r: com.microsoft.playwright.Response) =>
        r.url().contains(s"/shade/$value"),
      () => act
    )
  }

  test("hold, drag towards an option and release: that option is chosen") {
    withPageOn(served, Some(600 -> 600)) { (page, _) =>
      IO.blocking {
        assertThat(face(page)).hasText("24h")
        val box = face(page).boundingBox()
        val (cx, cy) = (box.x + box.width / 2, box.y + box.height / 2)
        page.mouse().move(cx, cy)
        page.mouse().down()
        page.waitForTimeout(400)
        assertThat(options(page).first()).isVisible()
        // Four options from the top, clockwise: 7d is the second. Wherever
        // the ring had to move to fit, the drag goes to where 7d is.
        val target = options(page).nth(1).boundingBox()
        page
          .mouse()
          .move(
            target.x + target.width / 2,
            target.y + target.height / 2,
            new com.microsoft.playwright.Mouse.MoveOptions().setSteps(5)
          )
        assertThat(ring(page)).hasAttribute("data-pick", "1")
        choosing(page, "7d")(page.mouse().up())
        assertThat(face(page)).hasText("7d")
        assertThat(options(page).first()).isHidden()
      }
    }
  }

  test("a ring by the screen's edge moves inward until every option shows") {
    // The face sits at the page's left edge, so a ring centred on it would put
    // its left options off the screen.
    withPageOn(served, Some(600 -> 600)) { (page, _) =>
      IO.blocking {
        face(page).click()
        assertThat(options(page).first()).isVisible()
        page.waitForTimeout(100)
        val all = options(page)
        (0 until all.count()).foreach { i =>
          val b = all.nth(i).boundingBox()
          assert(
            b.x >= 0 && b.y >= 0 && b.x + b.width <= 600 &&
              b.y + b.height <= 600,
            clue = s"option $i at $b"
          )
        }
      }
    }
  }

  test("a quick tap opens the ring, and a tap on an option chooses it") {
    withPageOn(served, Some(600 -> 600)) { (page, _) =>
      IO.blocking {
        face(page).click()
        assertThat(options(page).first()).isVisible()
        choosing(page, "30d")(
          ring(page)
            .getByText(
              "30d",
              new com.microsoft.playwright.Locator.GetByTextOptions()
                .setExact(true)
            )
            .click()
        )
        assertThat(face(page)).hasText("30d")
        assertThat(options(page).first()).isHidden()
      }
    }
  }
}
