package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.BoundingBox
import fh.view.testkit.{Scene, SmokeDashboard}

/** A header and a footer stuck to the screen's edges around a body that grows,
  * in a column filling the screen, measured in a browser: what the classes mean
  * is CSS, which the Pkl facts cannot see.
  */
class LayoutFillSmokeSuite extends SmokeSuite {

  private val height = 600

  private def box(page: Page, selector: String): BoundingBox =
    page.locator(selector).first().boundingBox()

  test("a short page puts the footer at the bottom of the screen") {
    withPage(Scene.of(SmokeDashboard.layoutFill(2)), Some(400 -> height)) {
      (page, _) =>
        IO.blocking {
          val foot = box(page, ".fh-stick-bottom")
          val bottom = foot.y + foot.height
          // Inside the theme's frame round the dashboard: BeerCSS's .5rem.
          assert(bottom > height - 16 && bottom <= height + 0.5, clue = bottom)
        }
    }
  }

  test("on a long page the header and footer stay on screen while it scrolls") {
    withPage(Scene.of(SmokeDashboard.layoutFill(60)), Some(400 -> height)) {
      (page, _) =>
        IO.blocking {
          val scrolls = page
            .evaluate("() => document.documentElement.scrollHeight")
            .toString
            .toDouble
          assert(scrolls > height * 1.5, clue = scrolls)
          val foot = box(page, ".fh-stick-bottom")
          assertEqualsDouble(foot.y + foot.height, height.toDouble, 1.0)
          page.evaluate(
            "() => window.scrollTo(0, document.documentElement.scrollHeight)"
          )
          assertEqualsDouble(box(page, ".fh-stick-top").y, 0.0, 1.0)
          val footAtEnd = box(page, ".fh-stick-bottom")
          assertEqualsDouble(footAtEnd.y + footAtEnd.height, height - 8.0, 2.0)
        }
    }
  }

  test("a spacer pushes what follows it to the row's end") {
    withPage(Scene.of(SmokeDashboard.layoutFill(2)), Some(400 -> height)) {
      (page, _) =>
        IO.blocking {
          val row = box(page, ".fh-stick-top .fh-row")
          val end = box(page, ".fh-stick-top .fh-row > :last-child")
          assertEqualsDouble(end.x + end.width, row.x + row.width, 1.0)
        }
    }
  }
}
