package fh.view.model

class TransformSuite extends munit.FunSuite {

  private def run(src: String, value: String): String =
    Transform.run(Transform.parse(src).fold(e => fail(e), identity), value)

  test("round to n decimals") {
    assertEquals(run("$round($number($), 1)", "21.44"), "21.4")
    assertEquals(run("$round($number($), 1)", "21.46"), "21.5")
    assertEquals(run("$round($number($))", "1499.6"), "1500")
  }

  test("whole-number results drop the decimal point") {
    assertEquals(run("$round($number($) * 1000) & \" W\"", "1.5"), "1500 W")
    assertEquals(run("$number($) * 1.8 + 32", "100"), "212")
  }

  test("arithmetic keeps real decimals") {
    assertEquals(run("$round($number($) * 1.8 + 32, 1)", "37"), "98.6")
  }

  test("string concat appends a unit") {
    assertEquals(run("$ & \" kWh\"", "5"), "5 kWh")
  }

  test("conditional maps a state to display text") {
    assertEquals(run("$ = \"on\" ? \"Open\" : \"Closed\"", "on"), "Open")
    assertEquals(run("$ = \"on\" ? \"Open\" : \"Closed\"", "off"), "Closed")
  }

  test("string functions") {
    assertEquals(run("$uppercase($)", "on"), "ON")
  }

  test("evaluation error on a non-numeric value falls back to the raw value") {
    // $number("unavailable") throws; the card shows the raw state instead.
    assertEquals(run("$round($number($), 1)", "unavailable"), "unavailable")
  }

  test("null result becomes empty (so the slot default can take over)") {
    assertEquals(run("$ = \"x\" ? \"y\" : null", "z"), "")
  }

  test("parse rejects malformed JSONata and empty input") {
    assert(Transform.parse("$round($number($),").isLeft)
    assert(Transform.parse("   ").isLeft)
  }
}
