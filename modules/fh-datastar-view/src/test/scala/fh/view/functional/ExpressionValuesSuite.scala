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

  private def entry(body: String, surfaces: String = ""): String =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/core/slot.pkl" as slotMod
       |import "@fh-dashboard/core/predicate.pkl" as pred
       |import "@fh-dashboard/core/simple.pkl" as simpleMod
       |import "@fh-dashboard/query.pkl" as q
       |import "@fh-home/dump.pkl" as dump
       |
       |local lights = List(
       |  dump.entities.${kitchen.dumpKey},
       |  dump.entities.${living.dumpKey}
       |)
       |
       |$surfaces
       |
       |card = (c.column) {
       |  children {
       |$body
       |  }
       |}
       |""".stripMargin

  private def withServer[A](body: String, surfaces: String = "")(
      f: TestServer => IO[A]
  ): IO[A] =
    TestServer
      .fromWorkspace(
        "expression-values",
        entry(body, surfaces),
        List(kitchen, living)
      )
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

  test("inside a popup, the count is live while the popup is open") {
    // The floors-popup shape this was built for: a surface is its own index,
    // so the tally's entities must reach that index too.
    val popup =
      s"""surfaces {
         |  ["floors"] {
         |    body {
         |${button("Stue")}
         |    }
         |  }
         |}""".stripMargin
    val open = "?ui.popups=floors"
    withServer("    c.button(\"Floors\", c.tap.openPopup(\"floors\"))", popup) {
      ts =>
        for {
          html <- ts.page(open)
          sig = onlySignal(html)
          client <- ts.connect(open)
          _ <- client.drain
          _ <- ts.change(living.entityId, "on")
          sent <- client.drain.map(_.flatMap(_.data).mkString("\n"))
        } yield {
          assert(html.contains(">1 / 2 on</span>"), clue = html)
          assert(
            sent.contains(sig.split('.').last + "\":\"2 / 2 on\""),
            clue = sent
          )
          assert(!sent.contains("fh-sub"), clue = sent)
        }
    }
  }

  test("on a candidate set's member, the count is live") {
    val body =
      s"""    q.from(List(dump.entities.${kitchen.dumpKey})).render((e) ->
         |${button("Stue")}
         |    ).build()""".stripMargin
    withServer(body) { ts =>
      for {
        html <- ts.page()
        sig = onlySignal(html)
        sent <- ts.sentAfter(ts.change(living.entityId, "on"))
      } yield {
        assert(html.contains(">1 / 2 on</span>"), clue = html)
        assert(
          sent.contains(sig.split('.').last + "\":\"2 / 2 on\""),
          clue = sent
        )
      }
    }
  }

  private def valueErrors(value: String, expr: String): List[String] =
    errorsOf(
      s"""    ((c.button("Stue", c.tap.closePopup())) {
         |      expressionValues { ["v"] = $value }
         |    }).secondary(c.expr(#"$expr"#))""".stripMargin
    )

  test("a Float is a double even when whole, and an Int an int") {
    assertEquals(valueErrors("2.0", "string(v + 0.5)"), Nil)
    assert(
      valueErrors("2", "string(v + 0.5)").exists(
        _.contains("no matching overload")
      ),
      clue = "an Int must not take a double operand"
    )
  }

  test("a slot read once cannot read a live count") {
    val body =
      """    ((c.button("Stue", c.tap.closePopup())) {
        |      expressionValues { ["n"] = q.from(lights).where(q.eq(q.stateProp, "on")).count() }
        |    }).secondary(new slotMod.Slot { transform = "string(n)"; reads = "once" })""".stripMargin
    val errs = errorsOf(body)
    assert(errs.exists(_.contains("would show the first count forever")), errs)
  }

  private def errorsOf(body: String): List[String] =
    PklFixture.buildDashboard("expression-values", entry(body)).validate()

  private def anyOn(value: String): String =
    s"""    ((c.button("Stue", c.tap.closePopup())) {
       |      expressionValues { ["any_on"] = $value }
       |    }).secondary(c.expr("any_on ? 'Some on' : 'All off'"))""".stripMargin

  test(
    "a condition is a bool value, and its flip sends a frame, not the line"
  ) {
    val body = anyOn("q.from(lights).where(q.eq(q.stateProp, \"on\")).any()")
    withServer(body) { ts =>
      for {
        html <- ts.page()
        sig = onlySignal(html)
        sent <- ts.sentAfter(ts.change(kitchen.entityId, "off"))
      } yield {
        assert(html.contains(">Some on</span>"), clue = html)
        assert(
          sent.contains(sig.split('.').last + "\":\"All off\""),
          clue = sent
        )
        assert(!sent.contains("fh-sub"), clue = sent)
      }
    }
  }

  test("a condition on one named entity is a value too") {
    val body =
      anyOn(s"""q.entity(dump.entities.${living.dumpKey}).stateIs("on")""")
    withServer(body) { ts =>
      for {
        html <- ts.page()
        sent <- ts.sentAfter(ts.change(living.entityId, "on"))
      } yield {
        assert(html.contains(">All off</span>"), clue = html)
        assert(sent.contains("\"Some on\""), clue = sent)
      }
    }
  }

  test("a condition comparing an entity it does not name fails the build") {
    val errs = errorsOf(
      anyOn("""new pred.Cmp { property = "state"; op = "eq"; value = "on" }""")
    )
    assert(errs.exists(_.contains("does not name")), errs)
  }

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

  // A yes/no input reading `reading`, beside a second line reading the count
  // so the value is read by something either way.
  private def yesNo(input: String, reading: String): List[String] =
    errorsOf(
      s"""    ((c.button("Stue", c.tap.closePopup())) {
         |      expressionValues { ["n"] = q.from(lights).where(q.eq(q.stateProp, "on")).count() }
         |      $input
         |    }).secondary(c.expr("string(n)"))""".stripMargin
    )

  private val notBool = "does not produce a bool"

  test("a yes/no input whose CEL is not a bool fails the build") {
    val int = """new slotMod.Slot { transform = "n" }"""
    assert(
      yesNo(
        s"""liveClasses { ["x"] = ($int) { signal = slotMod.asClass("x") } }""",
        int
      )
        .exists(_.contains(notBool))
    )
    assert(yesNo(s"disabled = $int", int).exists(_.contains(notBool)))
  }

  test("a yes/no input whose Simple shape is text fails the build") {
    val text =
      s"""new slotMod.Slot { entityId = "${kitchen.entityId}"; transform = simpleMod.attr("friendly_name") }"""
    assert(yesNo(s"disabled = $text", text).exists(_.contains(notBool)))
  }

  test("a comparison, a boolean match and a condition are all yes/no") {
    val cmp = """new slotMod.Slot { transform = "n > 0" }"""
    assertEquals(
      yesNo(s"disabled = $cmp", cmp).filter(_.contains(notBool)),
      Nil
    )
    val isOn = s"c.isOn(dump.entities.${kitchen.dumpKey})"
    assertEquals(
      yesNo(s"disabled = $isOn", isOn).filter(_.contains(notBool)),
      Nil
    )
  }
}
