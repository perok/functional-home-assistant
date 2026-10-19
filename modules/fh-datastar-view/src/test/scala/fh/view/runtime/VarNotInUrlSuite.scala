package fh.view.runtime

import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** `c.variable(n).notInUrl()`: declared and chosen like any variable, never
  * mirrored into the page URL.
  */
class VarNotInUrlSuite extends CatsEffectSuite {

  private val entry =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |
      |local view = c.variable("view").notInUrl()
      |local shade = c.variable("shade")
      |
      |card = (c.column) {
      |  vars = new Mapping { ...view.declare("plain"); ...shade.declare("light") }
      |  children {
      |    c.select(view).options(List("plain", "detail"))
      |    c.select(shade).options(List("light", "dark"))
      |  }
      |}
      |""".stripMargin

  test("a variable kept out of the URL has no mirror, and is still declared") {
    TestServer
      .fromWorkspace("var-not-in-url", entry, Nil)
      .use(_.page().map { page =>
        assert(!page.contains("data-fh-url:view"), page)
        assert(page.contains("data-fh-url:shade"), page)
        // Seeded like any variable, so its control and a write work as ever.
        assert(page.contains("_var_c__view: 'plain'"), page)
        assert(page.contains("_var_c__shade: 'light'"), page)
      })
      .timeout(60.seconds)
  }
}
