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

  // A boolean slot filled as `input` says, beside a second line reading the count
  // so the value is read by something either way.
  private def yesNo(input: String): List[String] =
    errorsOf(
      s"""    ((c.button("Stue", c.tap.closePopup())) {
         |      expressionValues { ["n"] = q.from(lights).where(q.eq(q.stateProp, "on")).count() }
         |      $input
         |    }).secondary(c.expr("string(n)"))""".stripMargin
    )

  private val notBool = "does not produce a bool"

  test("a boolean slot whose CEL is not a bool fails the build") {
    val int = """new slotMod.Slot { transform = "n" }"""
    assert(
      yesNo(
        s"""liveClasses { ["x"] = ($int) { signal = slotMod.asClass("x") } }"""
      )
        .exists(_.contains(notBool))
    )
    assert(yesNo(s"disabled = $int").exists(_.contains(notBool)))
  }

  test("a boolean slot whose Simple shape is text fails the build") {
    val text =
      s"""new slotMod.Slot { entityId = "${kitchen.entityId}"; transform = simpleMod.attr("friendly_name") }"""
    assert(yesNo(s"disabled = $text").exists(_.contains(notBool)))
  }

  test(
    "a comparison, a boolean match and a condition all fill a boolean slot"
  ) {
    val cmp = """new slotMod.Slot { transform = "n > 0" }"""
    assertEquals(
      yesNo(s"disabled = $cmp").filter(_.contains(notBool)),
      Nil
    )
    val isOn = s"c.isOn(dump.entities.${kitchen.dumpKey})"
    assertEquals(
      yesNo(s"disabled = $isOn").filter(_.contains(notBool)),
      Nil
    )
  }

  // The floor button: tinted while any light is on, disabled while none is.
  private def floorButton(label: String, over: String = "lights"): String =
    s"""    c.button("$label", c.tap.lights.off(dump.entities.${kitchen.dumpKey}))
       |      .secondary(c.expr("string(n) + ' on'"))
       |      .active(q.from($over).where(q.eq(q.stateProp, "on")).any())
       |      .disabled(q.from($over).where(q.eq(q.stateProp, "on")).none())
       |      .expressionValues(new Mapping { ["n"] = q.from($over).where(q.eq(q.stateProp, "on")).count() })""".stripMargin

  private def classSignals(html: String): List[String] =
    """data-class:fh-active="\$([^"]+)"""".r
      .findAllMatchIn(html)
      .map(_.group(1))
      .toList

  private def disabledSignal(html: String): String =
    """data-attr:disabled="[^"]*\$(_e\._x\.[A-Za-z0-9_]+)""".r
      .findFirstMatchIn(html)
      .map(_.group(1))
      .getOrElse(fail("no condition-backed disabled binding", clues(html)))

  private def leaf(signal: String): String = signal.split('.').last

  // The bare attribute, as the document form writes it — not `data-attr:disabled`.
  private def staticallyDisabled(html: String): Boolean =
    """<button class="fh-press[^"]*"[^>]*\sdisabled[\s>]""".r
      .findFirstIn(html)
      .isDefined

  test("a condition tints and disables a button, and its flip is one frame") {
    withServer(floorButton("Stue")) { ts =>
      for {
        html <- ts.page()
        active = classSignals(html).headOption.getOrElse(
          fail("no class binding", clues(html))
        )
        disabled = disabledSignal(html)
        sent <- ts.sentAfter(ts.change(kitchen.entityId, "off"))
      } yield {
        assert(html.contains("""class="fh-cell fh-active""""), clue = html)
        assert(!staticallyDisabled(html), clue = html)
        assert(sent.contains(s"\"${leaf(active)}\":false"), clue = sent)
        assert(sent.contains(s"\"${leaf(disabled)}\":true"), clue = sent)
        assert(!sent.contains("fh-cell"), clue = sent)
      }
    }
  }

  test("two buttons tinted by the same condition share one signal") {
    withServer(floorButton("Stue") + "\n" + floorButton("Gang"))(_.page()).map {
      html =>
        val sigs = classSignals(html)
        assertEquals(sigs.size, 2, clue = html)
        assertEquals(sigs.distinct.size, 1, clue = sigs)
    }
  }

  test("a condition the build settles is no live slot at all") {
    // Nothing to count: `any()` is `false` and `none()` is `true` at build time.
    withServer(floorButton("Tom", over = "List()"))(_.page()).map { html =>
      assertEquals(classSignals(html), Nil, clue = html)
      assert(!html.contains("""class="fh-cell fh-active""""), clue = html)
      assert(html.contains("<button class=\"fh-press large\""), clue = html)
      assert(staticallyDisabled(html), clue = html)
    }
  }

  test("classWhen takes a condition and an expression over values") {
    val body =
      s"""    c.button("Stue", c.tap.closePopup())
         |      .secondary(c.expr("string(n) + ' on'"))
         |      .expressionValues(new Mapping { ["n"] = q.from(lights).where(q.eq(q.stateProp, "on")).count() })
         |      .classWhen("fh-some", q.from(lights).where(q.eq(q.stateProp, "on")).any())
         |      .classWhen("fh-many", c.expr("n > 1"))""".stripMargin
    withServer(body) { ts =>
      for {
        html <- ts.page()
        sent <- ts.sentAfter(ts.change(living.entityId, "on"))
      } yield {
        assert(html.contains("""class="fh-cell fh-some""""), clue = html)
        assert(html.contains("data-class:fh-some="), clue = html)
        assert(html.contains("data-class:fh-many="), clue = html)
        // Two of two on: `fh-many` turns on, `fh-some` was already.
        assert(sent.contains(":true"), clue = sent)
        assert(!sent.contains("fh-cell"), clue = sent)
      }
    }
  }

  test("an author's value may not take a name the boolean inputs use") {
    val body =
      """    ((c.button("Stue", c.tap.closePopup())) {
        |      expressionValues { ["__active"] = 1 }
        |    }).secondary(c.expr("string(__active)"))""".stripMargin
    val err =
      scala.util.Try(errorsOf(body)).failed.map(_.getMessage).getOrElse("")
    assert(err.contains("""!startsWith("__")"""), clue = err)
  }

  private val noneOn =
    """q.from(lights).where(q.eq(q.stateProp, "on")).none()"""

  // The card's own reason, ORed after the tap's in the refusal guard.
  private def conditionSignal(html: String): String =
    """\|\| \$([A-Za-z0-9_.]+) \? '' :""".r
      .findFirstMatchIn(html)
      .map(_.group(1))
      .getOrElse(fail("no second refusal reason", clues(html)))

  test("a disabled tile wears fh-disabled and refuses its tap while it holds") {
    val body =
      s"    c.entityCard(dump.entities.${kitchen.dumpKey}).disabled($noneOn)"
    withServer(body) { ts =>
      for {
        html <- ts.page()
        sig = conditionSignal(html)
        sent <- ts.sentAfter(ts.change(kitchen.entityId, "off"))
      } yield {
        assert(
          s"""data-class:fh-disabled="[^"]*\\$$${sig.replace(".", "\\.")}""".r
            .findFirstIn(html)
            .isDefined,
          clue = html
        )
        assert(
          s"""data-on:click="[^"]*\\$$${sig.replace(".", "\\.")} \\? '' :""".r
            .findFirstIn(html)
            .isDefined,
          clue = html
        )
        assert(sent.contains(s"\"${leaf(sig)}\":true"), clue = sent)
      }
    }
  }

  test("a disabled slider disables its range and guards its commit") {
    val body =
      s"    c.entitySlider(dump.entities.${kitchen.dumpKey}).disabled($noneOn)"
    withServer(body)(_.page()).map { html =>
      val sig = conditionSignal(html).replace(".", "\\.")
      assert(
        // Lazy, not `[^>]*`: the input's own `data-effect` holds a `>`.
        s"""(?s)<input type="range".*?data-attr:disabled="[^"]*\\$$$sig""".r
          .findFirstIn(html)
          .isDefined,
        clue = html
      )
      assert(
        s"""data-on:change="[^"]*\\$$$sig \\? '' :""".r
          .findFirstIn(html)
          .isDefined,
        clue = html
      )
    }
  }

  private def propertyButton(property: String): String =
    s"""    c.button("Stue", c.tap.closePopup())
       |      .secondary(c.expr("string(n) + ' on'"))
       |      .expressionValues(new Mapping { ["n"] = q.from(lights).where(q.eq(q.stateProp, "on")).count() })
       |      $property""".stripMargin

  test("a cssProperty is inline in the document and moves by one frame") {
    val body =
      propertyButton(
        """.cssProperty("--fh-fill", c.expr("string(n * 50) + '%'"))"""
      )
    withServer(body) { ts =>
      for {
        html <- ts.page()
        sent <- ts.sentAfter(ts.change(living.entityId, "on"))
      } yield {
        assert(
          html.contains("""<div class="fh-cell" style="--fh-fill:50%""""),
          clue = html
        )
        assert(html.contains("data-style:--fh-fill=\"$"), clue = html)
        assert(sent.contains("\"100%\""), clue = sent)
        assert(!sent.contains("fh-cell"), clue = sent)
      }
    }
  }

  test("a cssProperty takes a Simple reading, on the fast tier") {
    val body = propertyButton(
      s""".cssProperty("--fh-name", new slotMod.Slot { entityId = "${kitchen.entityId}"; transform = simpleMod.attr("friendly_name") })"""
    )
    withServer(body)(_.page()).map { html =>
      assert(html.contains("""style="--fh-name:Kitchen""""), clue = html)
    }
  }

  test("a cssProperty that reads a bool fails the build") {
    val isOn = propertyButton(
      s""".cssProperty("--fh-on", c.isOn(dump.entities.${kitchen.dumpKey}))"""
    )
    assert(errorsOf(isOn).exists(_.contains("reads a bool")), errorsOf(isOn))
    val cmp = propertyButton(""".cssProperty("--fh-on", c.expr("n > 0"))""")
    assert(errorsOf(cmp).exists(_.contains("reads a bool")), errorsOf(cmp))
  }

  test("a cssProperty must be a custom property") {
    val body = propertyButton(""".cssProperty("color", c.expr("'red'"))""")
    val err =
      scala.util.Try(errorsOf(body)).failed.map(_.getMessage).getOrElse("")
    assert(err.contains("""startsWith("--")"""), clue = err)
  }
}
