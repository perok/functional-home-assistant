package fh.view.functional

import cats.effect.IO
import fh.view.runtime.TestServer
import fh.view.testkit.{FixtureEntity, HouseFixture, PklFixture}

import scala.concurrent.duration.*

/** A live cell class (`LayoutNode.classWhen`) and a button's own `disabled`,
  * from a real Pkl entry: what the document carries, and what a state change
  * sends.
  */
class LiveCellClassSuite extends munit.CatsEffectSuite {

  private val kitchen = HouseFixture.kitchenLight
  private val lock = HouseFixture.frontLock
  private val entities: List[FixtureEntity] = List(kitchen, lock)

  private def entry(body: String): String =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/core/slot.pkl" as slotMod
       |import "@fh-dashboard/core/simple.pkl" as simpleMod
       |import "@fh-home/dump.pkl" as dump
       |
       |local kitchenOn = new slotMod.Slot {
       |  entityId = dump.entities.${kitchen.dumpKey}.entity_id
       |  transform = simpleMod.stateIn(new Listing { "on" })
       |  bypassUnavailable = false
       |}
       |
       |card = (c.column) {
       |  children {
       |$body
       |  }
       |}
       |""".stripMargin

  private val warmButton =
    s"""    c.button("Warm", c.tap.call("light/toggle", dump.entities.${kitchen.dumpKey}))
       |      .classWhen("warm", kitchenOn)""".stripMargin

  private def withServer[A](body: String)(f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("cell-class", entry(body), entities)
      .use(f)
      .timeout(60.seconds)

  /** The one signal a `data-class:<cls>` names, read off the markup rather than
    * spelled here, since its name is the renderer's to choose.
    */
  private def classSignal(html: String, cls: String): String =
    s"""data-class:$cls="\\$$([^"]+)"""".r
      .findFirstMatchIn(html)
      .map(_.group(1))
      .getOrElse(fail(s"no data-class:$cls binding", clues(html)))

  /** Every wrapper opening tag that carries a live class binding. */
  private def boundWrappers(html: String): List[String] =
    """<div class="fh-cell[^>]*>""".r
      .findAllIn(html)
      .filter(_.contains("data-class:"))
      .toList

  test("a live cell class is on the wrapper, bound and inline while it holds") {
    withServer(warmButton)(_.page()).map { html =>
      val sig = classSignal(html, "warm")
      val wrapper = boundWrappers(html) match {
        case List(w) => w
        case other   => fail("expected one bound wrapper", clues(other, html))
      }
      assert(wrapper.startsWith("""<div class="fh-cell warm" """), wrapper)
      // Seeded with the node's other signals, so it holds before any frame.
      assert(wrapper.contains(sig.split('.').last + ": true"), wrapper)
    }
  }

  test("a wrapper's seed comes before its class bindings") {
    // The bundle applies an element's attributes in order: a binding ahead of
    // the seed on the same element reads a signal that does not exist yet.
    withServer(warmButton)(_.page()).map { html =>
      val wrappers = boundWrappers(html)
      assert(wrappers.nonEmpty, html)
      wrappers.foreach { w =>
        val seed = w.indexOf("data-signals=")
        assert(seed >= 0 && seed < w.indexOf("data-class:"), w)
      }
    }
  }

  test("the class is a display signal: a change sends a frame, not a patch") {
    withServer(warmButton) { ts =>
      for {
        html <- ts.page()
        sig = classSignal(html, "warm")
        sent <- ts.sentAfter(ts.change(kitchen.entityId, "off"))
      } yield {
        assert(sent.contains(sig.split('.').last + "\":false"), clue = sent)
        assert(!sent.contains("Warm"), clue = sent)
      }
    }
  }

  test("an off reading leaves the class out of the document, still bound") {
    TestServer
      .fromWorkspace(
        "cell-class",
        entry(warmButton),
        List(kitchen.copy(state = "off"), lock)
      )
      .use(_.page())
      .timeout(60.seconds)
      .map { html =>
        assert(
          boundWrappers(html).exists(_.startsWith("""<div class="fh-cell" """)),
          clue = html
        )
      }
  }

  test("a live cell class must be a plain class token") {
    val d = PklFixture.buildDashboard(
      "cell-class",
      entry(
        s"""    c.button("Warm", c.tap.call("light/toggle", dump.entities.${kitchen.dumpKey}))
           |      .classWhen("not-a\\"token", kitchenOn)""".stripMargin
      )
    )
    val errs = d.validate()
    assert(errs.exists(_.contains("not a plain CSS class token")), errs)
  }

  test("a button's own disabled is ORed with its tap's refusal") {
    // The lock's toggle refuses while it is mid-move (`hass/actions.pkl`); the
    // button adds a reason of its own, on a different entity.
    val body =
      s"""    (c.button("Lock", c.tap.toggle(dump.entities.${lock.dumpKey}))) {
         |      disabled = kitchenOn
         |    }""".stripMargin
    withServer(body)(_.page()).map { html =>
      val attr = """data-attr:disabled="(\$[^"|]+) \|\| (\$[^"]+)"""".r
        .findFirstMatchIn(html)
        .getOrElse(fail("no ORed data-attr:disabled", clues(html)))
      assertNotEquals(attr.group(1), attr.group(2))
      // The lock is `locked`, which its toggle does not refuse; the kitchen is
      // on, so the button's own reason holds and the document is disabled.
      assert(html.contains(""" disabled data-attr:disabled="""), clue = html)
    }
  }

  test("a button with only its own disabled binds that alone") {
    val body =
      """    (c.button("Close", c.tap.closePopup())) {
        |      disabled = kitchenOn
        |    }""".stripMargin
    withServer(body)(_.page()).map { html =>
      val attr = """data-attr:disabled="([^"]+)"""".r
        .findFirstMatchIn(html)
        .getOrElse(fail("no data-attr:disabled", clues(html)))
      assert("""\$[\w.]+""".r.matches(attr.group(1)), clue = html)
      assert(html.contains(""" disabled data-attr:disabled="""), clue = html)
    }
  }
}
