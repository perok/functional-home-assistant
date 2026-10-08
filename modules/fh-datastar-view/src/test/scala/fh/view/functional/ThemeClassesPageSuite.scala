package fh.view.functional

import cats.effect.IO
import fh.view.runtime.TestServer
import fh.view.testkit.{FixtureEntity, HouseFixture, PklFixture}

import scala.concurrent.duration.*

/** A theme's class rules (`Theme.classes`) on a real page: every place the
  * server emits a class, from a card's template to the wrapper it adds itself.
  */
class ThemeClassesPageSuite extends munit.CatsEffectSuite {

  private val kitchen = HouseFixture.kitchenLight
  private val entities: List[FixtureEntity] = List(kitchen)

  private def entry(themeRules: String): String =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/core/slot.pkl" as slotMod
       |import "@fh-dashboard/core/simple.pkl" as simpleMod
       |import "@fh-home/dump.pkl" as dump
       |
       |theme { classes { $themeRules } }
       |
       |local kitchenOn = new slotMod.Slot {
       |  entityId = dump.entities.${kitchen.dumpKey}.entity_id
       |  transform = simpleMod.stateIn(new Listing { "on" })
       |  bypassUnavailable = false
       |}
       |
       |card = (c.grid) {
       |  children {
       |    c.button("Warm", c.tap.call("light/toggle", dump.entities.${kitchen.dumpKey}))
       |      .classWhen("fh-warm", kitchenOn)
       |      .columns(3)
       |    c.entityButton(dump.entities.${kitchen.dumpKey})
       |    c.entityCard(dump.entities.${kitchen.dumpKey})
       |  }
       |}
       |""".stripMargin

  private val rules =
    """["fh-cell"] = "fh-cell s12"
      |["fh-cols-3"] = "s4"
      |["fh-warm"] = "fh-warm hot"
      |["fh-disabled"] = "fh-disabled disabled"""".stripMargin

  private def page(themeRules: String): IO[String] =
    TestServer
      .fromWorkspace("theme-classes", entry(themeRules), entities)
      .use(_.page())
      .timeout(60.seconds)

  private def bindings(html: String, cls: String): List[String] =
    s"""data-class:$cls="([^"]*)"""".r
      .findAllMatchIn(html)
      .map(_.group(1))
      .toList

  test("every wrapper carries an added class; a replaced cell class is gone") {
    page(rules).map { html =>
      val wrappers = """<div class="fh-cell[^"]*"""".r.findAllIn(html).toList
      assert(wrappers.nonEmpty, html)
      wrappers.foreach(w =>
        assert(w.startsWith("""<div class="fh-cell s12"""), w)
      )
      assert(!wrappers.exists(_.contains("fh-cols-3")), wrappers)
      assert(wrappers.exists(_.contains(" s4")), wrappers)
    }
  }

  test(
    "an added class follows a live class, inline and bound to the same signal"
  ) {
    page(rules).map { html =>
      val warm = bindings(html, "fh-warm")
      assert(warm.nonEmpty, html)
      assertEquals(bindings(html, "hot"), warm)
      assert(html.contains("fh-warm hot"), html)
    }
  }

  test("an added class follows every template binding of the class it names") {
    page(rules).map { html =>
      val dim = bindings(html, "fh-disabled")
      assert(dim.nonEmpty, html)
      assertEquals(bindings(html, "disabled"), dim)
    }
  }

  test(
    "the BeerCSS theme's spinner replaces the plain ring wherever it is bound"
  ) {
    page("").map { html =>
      assertEquals(bindings(html, "fh-busy-spin"), Nil)
      val shape = bindings(html, "shape")
      assert(shape.nonEmpty, html)
      assertEquals(bindings(html, "loading-indicator"), shape)
    }
  }

  test("replacing a class the runtime selects on fails validation") {
    val dashboard = PklFixture.buildDashboard(
      "theme-classes",
      entry("""["fh-cell"] = "s12"""")
    )
    val errs = dashboard.validate()
    assert(
      errs.exists(_.contains("'fh-cell' must stay in its own content")),
      errs
    )
  }
}
