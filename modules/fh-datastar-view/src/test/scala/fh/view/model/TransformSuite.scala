package fh.view.model

import fh.view.runtime.EntityState
import io.circe.Json

class TransformSuite extends munit.FunSuite {

  private def compile(src: String): Transform.Compiled =
    Transform.parse(src).fold(e => fail(e), identity)

  private def run(
      src: String,
      state: String,
      attributes: Map[String, Json] = Map.empty,
      entity: String = "sensor.x"
  ): String =
    Transform.run(compile(src), EntityState(entity, state, attributes))

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

  test("evaluation error renders the JSONata message on the card (no crash)") {
    // $number("unavailable") fails; the card shows the error rather than the raw
    // value or crashing the render.
    val out = run("$round($number($state), 1)", "unavailable")
    assert(out.nonEmpty, clue = out)
    assertNotEquals(out, "unavailable")
  }

  // Note: unavailable/unknown entities never reach a transform — the renderer
  // bypasses it and shows the raw state (see RendererSuite).

  test("null result becomes empty (so the slot default can take over)") {
    assertEquals(run("$state = \"x\" ? \"y\" : null", "z"), "")
  }

  test("identity bindings: $domain and $entity_id come from the entity id") {
    assertEquals(run("$domain", "on", entity = "light.kitchen"), "light")
    assertEquals(
      run("$entity_id", "on", entity = "light.kitchen"),
      "light.kitchen"
    )
  }

  test("identity-derived action: maps domain to a service, with a default") {
    val expr =
      """($a := $lookup({"scene": "scene/turn_on"}, $domain); """ +
        """$a ? $a : "homeassistant/toggle")"""
    assertEquals(run(expr, "on", entity = "scene.movie"), "scene/turn_on")
    assertEquals(
      run(expr, "on", entity = "light.kitchen"),
      "homeassistant/toggle"
    )
    // identity-only: resolves even with no usable state (never reads $state)
    assertEquals(
      run(expr, "unavailable", entity = "scene.movie"),
      "scene/turn_on"
    )
  }

  test("parse rejects malformed JSONata and empty input") {
    assert(Transform.parse("$round($number($state),").isLeft)
    assert(Transform.parse("   ").isLeft)
  }

  test("slider fill: --_end percent from the position attr, null-guarded") {
    // The STATIC tier the slider card bakes for a light (min 1, max 255):
    // fill = 100 - value% of the range, from the RIGHT (BeerCSS convention).
    val expr =
      "($v := $attr.brightness; " +
        "$v != null ? $round(100 - (($v - 1) * 100 / (255 - 1))) : 100)"
    assertEquals(
      run(
        expr,
        "on",
        attributes = Map("brightness" -> Json.fromInt(255)),
        entity = "light.kitchen"
      ),
      "0" // full brightness = zero distance from the right = full fill
    )
    assertEquals(
      run(
        expr,
        "on",
        attributes = Map("brightness" -> Json.fromInt(128)),
        entity = "light.kitchen"
      ),
      "50"
    )
    // A light that is OFF has no brightness attribute: empty fill, NOT a
    // JSONata type error leaking into the style attribute.
    assertEquals(run(expr, "off", entity = "light.kitchen"), "100")
  }

  test("slider fill: the dynamic $lookup($domain) tier resolves per match") {
    val expr =
      "($v := $lookup($attr, $lookup({\"light\":\"brightness\",\"cover\":\"current_position\"}, $domain)); " +
        "$v != null ? $round(100 - (($v - $lookup({\"light\":1,\"cover\":0}, $domain)) * 100 / " +
        "($lookup({\"light\":255,\"cover\":100}, $domain) - $lookup({\"light\":1,\"cover\":0}, $domain)))) : 100)"
    assertEquals(
      run(
        expr,
        "open",
        attributes = Map("current_position" -> Json.fromInt(75)),
        entity = "cover.blinds"
      ),
      "25"
    )
    assertEquals(run(expr, "off", entity = "light.kitchen"), "100")
  }
}
