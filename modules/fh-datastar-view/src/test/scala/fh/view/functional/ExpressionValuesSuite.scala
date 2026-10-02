package fh.view.functional

import cats.effect.IO
import fh.view.runtime.TestServer
import fh.view.testkit.{HouseFixture, PklFixture}
import io.circe.Json

import scala.concurrent.duration.*

/** A node's expression values, from a real Pkl entry: a live count read by name
  * in a slot's CEL, carried as one display signal. The contract is partly
  * negative — a tick that leaves the count alone must send nothing — so the
  * flip test is the control that shows the harness would have seen it.
  */
class ExpressionValuesSuite extends munit.CatsEffectSuite {

  private val kitchen = HouseFixture.kitchenLight
  private val living = HouseFixture.livingRoomLight

  private val counted =
    """#"string(lights_on) + ' / ' + string(lights_total) + ' on'"#"""

  private def button(
      label: String,
      expr: String = counted,
      over: String = "lights"
  ): String =
    s"""    ((c.button("$label", c.tap.closePopup())) {
       |      expressionValues {
       |        ["lights_on"] = q.from($over).where(q.eq(q.stateProp, "on")).count()
       |        ["lights_total"] = $over.length
       |      }
       |    }).secondary(c.expr($expr))""".stripMargin

  private def entry(body: String): String =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
       |import "@fh-home/dump.pkl" as dump
       |
       |local lights = List(
       |  dump.entities.${kitchen.dumpKey},
       |  dump.entities.${living.dumpKey}
       |)
       |
       |card = (c.column) {
       |  children {
       |$body
       |  }
       |}
       |""".stripMargin

  private def withServer[A](body: String)(f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("expression-values", entry(body), List(kitchen, living))
      .use(f)
      .timeout(60.seconds)

  private def secondarySignals(html: String): List[String] =
    """<span class="fh-sub fh-text"><span class="fh-text-run" data-text="\$([^"]+)">""".r
      .findAllMatchIn(html)
      .map(_.group(1))
      .toList

  private def onlySignal(html: String): String =
    secondarySignals(html).distinct match
      case List(s) => s
      case other   => fail("expected one bound secondary", clues(other, html))

  test("the document carries the count, bound to its signal") {
    withServer(button("Stue"))(_.page()).map { html =>
      assert(onlySignal(html).nonEmpty, clue = html)
      assert(html.contains(">1 / 2 on</span>"), clue = html)
    }
  }

  test("a counted light turning on sends a frame, not the line") {
    withServer(button("Stue")) { ts =>
      for {
        html <- ts.page()
        sig = onlySignal(html)
        sent <- ts.sentAfter(ts.change(living.entityId, "on"))
      } yield {
        assert(
          sent.contains(sig.split('.').last + "\":\"2 / 2 on\""),
          clue = sent
        )
        assert(!sent.contains("fh-sub"), clue = sent)
      }
    }
  }

  test("a brightness tick on a counted light sends nothing for the count") {
    val dimmer =
      kitchen.copy(attributes =
        kitchen.attributes + ("brightness" -> Json.fromInt(40))
      )
    withServer(button("Stue")) { ts =>
      for {
        html <- ts.page()
        sig = onlySignal(html)
        sent <- ts.sentAfter(ts.frame(dimmer))
      } yield assert(!sent.contains(sig.split('.').last), clue = sent)
    }
  }

  test("two nodes saying the same thing over the same values share a signal") {
    withServer(button("Stue") + "\n" + button("Gang"))(_.page()).map { html =>
      val sigs = secondarySignals(html)
      assertEquals(sigs.size, 2, clue = html)
      assertEquals(sigs.distinct.size, 1, clue = sigs)
    }
  }

  test("the same expression over different values is two signals") {
    val body =
      button("Stue") + "\n" +
        button("Kjøkken", over = s"List(dump.entities.${kitchen.dumpKey})")
    withServer(body)(_.page()).map { html =>
      assertEquals(secondarySignals(html).distinct.size, 2, clue = html)
    }
  }

  private def errorsOf(body: String): List[String] =
    PklFixture.buildDashboard("expression-values", entry(body)).validate()

  test(
    "an expression naming a value the node does not declare fails the build"
  ) {
    val errs = errorsOf(button("Stue", "#\"string(lights_off)\"#"))
    assert(
      errs.exists(_.contains("undeclared reference to 'lights_off'")),
      errs
    )
  }

  test("a value the expression cannot use as typed fails the build") {
    val errs = errorsOf(button("Stue", "#\"lights_on + ' on'\"#"))
    assert(errs.exists(_.contains("no matching overload")), errs)
  }

  test("a value no expression on the node reads fails the build") {
    val errs = errorsOf(button("Stue", "#\"string(lights_on)\"#"))
    assert(
      errs.exists(e =>
        e.contains("'lights_total'") && e.contains("read by no")
      ),
      errs
    )
  }

  test("a value that would hide a name every expression has fails the build") {
    val body =
      """    ((c.button("Stue", c.tap.closePopup())) {
        |      expressionValues { ["state"] = "x" }
        |    }).secondary(c.expr("state"))""".stripMargin
    val errs = errorsOf(body)
    assert(errs.exists(_.contains("would hide the 'state'")), errs)
  }
}
