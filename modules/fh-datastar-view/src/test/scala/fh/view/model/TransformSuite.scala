package fh.view.model

import io.circe.Json

class TransformSuite extends munit.FunSuite {

  // The slot value is the entity's state here, so `value` (the error fallback)
  // and `$state` are the same; attributes default to none.
  private def run(
      src: String,
      state: String,
      attributes: Map[String, Json] = Map.empty
  ): String =
    Transform.run(
      Transform.parse(src).fold(e => fail(e), identity),
      Transform.Context(state, state, attributes)
    )

  test("round to n decimals") {
    assertEquals(run("$round($number($state), 1)", "21.44"), "21.4")
    assertEquals(run("$round($number($state), 1)", "21.46"), "21.5")
    assertEquals(run("$round($number($state))", "1499.6"), "1500")
  }

  test("whole-number results drop the decimal point") {
    assertEquals(
      run("$round($number($state) * 1000) & \" W\"", "1.5"),
      "1500 W"
    )
    assertEquals(run("$number($state) * 1.8 + 32", "100"), "212")
  }

  test("arithmetic keeps real decimals") {
    assertEquals(run("$round($number($state) * 1.8 + 32, 1)", "37"), "98.6")
  }

  test("string concat appends a unit") {
    assertEquals(run("$state & \" kWh\"", "5"), "5 kWh")
  }

  test("conditional maps a state to display text") {
    assertEquals(run("$state = \"on\" ? \"Open\" : \"Closed\"", "on"), "Open")
    assertEquals(
      run("$state = \"on\" ? \"Open\" : \"Closed\"", "off"),
      "Closed"
    )
  }

  test("string functions") {
    assertEquals(run("$uppercase($state)", "on"), "ON")
  }

  test("same-entity: $attr reads a sibling attribute") {
    assertEquals(
      run(
        "$state & \" \" & $attr.unit_of_measurement",
        "21.5",
        attributes = Map("unit_of_measurement" -> Json.fromString("°C"))
      ),
      "21.5 °C"
    )
  }

  test("same-entity: numeric attributes stay numeric for arithmetic") {
    assertEquals(
      run(
        "$round($attr.brightness / 255 * 100) & \"%\"",
        "on",
        attributes = Map("brightness" -> Json.fromInt(128))
      ),
      "50%"
    )
  }

  test("auto-unit pattern: append the unit only when present") {
    val expr = "$state & ($attr.unit_of_measurement" +
      " ? \" \" & $attr.unit_of_measurement : \"\")"
    assertEquals(
      run(expr, "21.5", Map("unit_of_measurement" -> Json.fromString("°C"))),
      "21.5 °C"
    )
    assertEquals(run(expr, "42"), "42")
  }

  test("evaluation error on a non-numeric value falls back to the raw value") {
    // $number("unavailable") throws; the card shows the raw state instead.
    assertEquals(
      run("$round($number($state), 1)", "unavailable"),
      "unavailable"
    )
  }

  test("null result becomes empty (so the slot default can take over)") {
    assertEquals(run("$state = \"x\" ? \"y\" : null", "z"), "")
  }

  test("parse rejects malformed JSONata and empty input") {
    assert(Transform.parse("$round($number($state),").isLeft)
    assert(Transform.parse("   ").isLeft)
  }
}
