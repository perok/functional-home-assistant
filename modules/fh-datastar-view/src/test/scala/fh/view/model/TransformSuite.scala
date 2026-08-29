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
    Transform.run(
      compile(src),
      EntityState(entity, state, attributes),
      "dashboard"
    )

  test("round to n decimals") {
    assertEquals(
      run("str(math.round(num(state) * 10.0) / 10.0)", "21.44"),
      "21.4"
    )
    assertEquals(
      run("str(math.round(num(state) * 10.0) / 10.0)", "21.46"),
      "21.5"
    )
    assertEquals(run("str(math.round(num(state)))", "1499.6"), "1500")
  }

  test("whole-number results drop the decimal point") {
    assertEquals(
      run("str(math.round(num(state) * 1000.0)) + ' W'", "1.5"),
      "1500 W"
    )
    assertEquals(
      run("str(num(state) * 1.8 + 32.0)", "100"),
      "212"
    )
  }

  test("arithmetic keeps real decimals") {
    assertEquals(
      run(
        "str(math.round((num(state) * 1.8 + 32.0) * 10.0) / 10.0)",
        "37"
      ),
      "98.6"
    )
  }

  test("string concat appends a unit") {
    assertEquals(run("state + ' kWh'", "5"), "5 kWh")
  }

  test("conditional maps a state to display text") {
    assertEquals(run("state == 'on' ? 'Open' : 'Closed'", "on"), "Open")
    assertEquals(
      run("state == 'on' ? 'Open' : 'Closed'", "off"),
      "Closed"
    )
  }

  test("string library functions") {
    assertEquals(run("state.replace('o', '0')", "on"), "0n")
  }

  test("same-entity: attr reads a sibling attribute, guarded") {
    assertEquals(
      run(
        "state + ' ' + ('unit_of_measurement' in attr ? attr['unit_of_measurement'] : '')",
        "21.5",
        attributes = Map("unit_of_measurement" -> Json.fromString("°C"))
      ),
      "21.5 °C"
    )
    // A raw read on an ABSENT key is an eval error in CEL — the shipped strings
    // guard with `'x' in attr` so this never happens; the card shows the error.
    assert(
      run(
        "state + ' ' + attr['unit_of_measurement']",
        "21.5"
      ).startsWith("cel error:"),
      clue = run("state + ' ' + attr['unit_of_measurement']", "21.5")
    )
  }

  test("same-entity: numeric attributes stay numeric for arithmetic") {
    assertEquals(
      run(
        "str(math.round(double(attr['brightness']) * 100.0 / 255.0)) + '%'",
        "on",
        attributes = Map("brightness" -> Json.fromInt(128))
      ),
      "50%"
    )
  }

  test("auto-unit pattern: append the unit only when present") {
    val expr = "state + ('unit_of_measurement' in attr" +
      " ? ' ' + attr['unit_of_measurement'] : '')"
    assertEquals(
      run(expr, "21.5", Map("unit_of_measurement" -> Json.fromString("°C"))),
      "21.5 °C"
    )
    assertEquals(run(expr, "42"), "42")
  }

  test("evaluation error renders the CEL message on the card (no crash)") {
    // num("unavailable") fails; the card shows the error rather than the raw
    // value or crashing the render.
    val out = run("str(math.round(num(state) * 10.0) / 10.0)", "unavailable")
    assert(out.nonEmpty, clue = out)
    assertNotEquals(out, "unavailable")
  }

  // Note: unavailable/unknown entities never reach a transform — the renderer
  // bypasses it and shows the raw state (see RendererSuite).

  test("null result becomes empty (so the slot default can take over)") {
    // CEL has no `? x : null` ternary (both arms must share a type); a null
    // reaches the result the same way it does in the shipped slider strings —
    // a guarded read whose fallback is the binding's null.
    assertEquals(run("cel.bind(v, null, v)", "z"), "")
    assertEquals(
      run(
        "cel.bind(v, 'brightness' in attr ? attr['brightness'] : null, v)",
        "off"
      ),
      ""
    )
  }

  test("identity bindings: domain and entity_id come from the entity id") {
    assertEquals(run("domain", "on", entity = "light.kitchen"), "light")
    assertEquals(
      run("entity_id", "on", entity = "light.kitchen"),
      "light.kitchen"
    )
  }

  test("the dashboard slug binds independently of the entity") {
    assertEquals(
      Transform.run(
        compile("dashboard_slug"),
        EntityState("", "", Map.empty),
        "kitchen"
      ),
      "kitchen"
    )
  }

  // ADR 0016 bakes a tap's action at build time; this pins that a hand-written
  // CEL map-index over `domain` can still derive one — the `$lookup` tier's
  // replacement.
  test("a map-indexed action over domain still resolves (fallback)") {
    val expr =
      """cel.bind(m, {'scene': 'scene/turn_on'}, """ +
        """domain in m ? m[domain] : 'homeassistant/toggle')"""
    assertEquals(run(expr, "on", entity = "scene.movie"), "scene/turn_on")
    assertEquals(
      run(expr, "on", entity = "light.kitchen"),
      "homeassistant/toggle"
    )
    // identity-only: resolves even with no usable state (never reads state)
    assertEquals(
      run(expr, "unavailable", entity = "scene.movie"),
      "scene/turn_on"
    )
  }

  test("parse rejects malformed CEL and empty input") {
    assert(Transform.parse("'on' ? : 'x'").isLeft)
    assert(Transform.parse("   ").isLeft)
  }

  test("slider fill: --_end percent from the position attr, null-guarded") {
    // The STATIC tier the slider card bakes for a light (min 1, max 255):
    // fill = 100 - value% of the range, from the RIGHT (BeerCSS convention).
    // `double(v)` is load-bearing: an attr value arrives as a Long and CEL's
    // double overloads have no (Long, double) operand, so bare `v - 1.0`
    // compiles but throws at evaluation.
    val expr =
      "str(cel.bind(v, 'brightness' in attr ? attr['brightness'] : null, " +
        "v != null ? 100.0 - ((double(v) - 1.0) * 100.0 / (255.0 - 1.0)) : 100.0)) + '%'"
    assertEquals(
      run(
        expr,
        "on",
        attributes = Map("brightness" -> Json.fromInt(255)),
        entity = "light.kitchen"
      ),
      "0%" // full brightness = zero distance from the right = full fill
    )
    assertEquals(
      run(
        expr,
        "on",
        attributes = Map("brightness" -> Json.fromInt(128)),
        entity = "light.kitchen"
      ),
      "50%"
    )
    // The value that showed the blip: what beer.min.js writes is
    // 39.37007874015748%, not 39% — and the 10-digit stringifier's margin,
    // which is why this is "…02" and not "…15".
    assertEquals(
      run(
        expr,
        "on",
        attributes = Map("brightness" -> Json.fromInt(155)),
        entity = "light.kitchen"
      ),
      "39.3700787402%"
    )
    // A light that is OFF has no brightness attribute: empty fill, NOT an eval
    // error leaking into the style attribute.
    assertEquals(run(expr, "off", entity = "light.kitchen"), "100%")
  }

  test("slider fill colour: rgb_color wins, else the kelvin ramp, else blank") {
    // The shipped expression, in the Phase-0-validated structure: `double(k)`
    // (a kelvin value arrives as Long), `''` for the kelvin-absent arm (CEL
    // needs both ternary arms typed — a deliberate, pinned divergence from
    // JSONata's null), `size(rgb)` gated behind presence so `rgb` is never read
    // when absent, and `str(...)` (which strips a whole double's `.0` — CEL's
    // native `string(math.round(x))` leaves it).
    val expr =
      """cel.bind(rgb, 'rgb_color' in attr ? attr['rgb_color'] : null,
        |  cel.bind(k, 'color_temp_kelvin' in attr ? attr['color_temp_kelvin'] : null,
        |    (rgb != null && size(rgb) == 3)
        |      ? 'rgb(' + str(rgb[0]) + ',' + str(rgb[1]) + ',' + str(rgb[2]) + ')'
        |      : (k != null
        |          ? cel.bind(t, (double(k) - 2000.0) < 0.0 ? 0.0 : ((double(k) - 2000.0) > 4500.0 ? 1.0 : (double(k) - 2000.0) / 4500.0),
        |              'rgb(' + str(math.round(255.0 - 54.0 * t)) + ',' + str(math.round(166.0 + 60.0 * t)) + ',' + str(math.round(87.0 + 168.0 * t)) + ')')
        |          : '')))""".stripMargin
    def light(attrs: (String, Json)*): String =
      run(expr, "on", attributes = attrs.toMap, entity = "light.kitchen")

    assertEquals(
      light("rgb_color" -> Json.arr(List(255, 10, 20).map(Json.fromInt)*)),
      "rgb(255,10,20)"
    )
    assertEquals(
      light("color_temp_kelvin" -> Json.fromInt(2700)),
      "rgb(247,175,113)"
    )
    assertEquals(
      light("color_temp_kelvin" -> Json.fromInt(6500)),
      "rgb(201,226,255)"
    )
    // Clamped below the warm end rather than extrapolated past it.
    assertEquals(
      light("color_temp_kelvin" -> Json.fromInt(1800)),
      "rgb(255,166,87)"
    )
    // Neither attribute (a cover, a fan, a light that is off): the kelvin arm's
    // `''` fallback, so the slot's `currentcolor` default takes over.
    assertEquals(light("brightness" -> Json.fromInt(155)), "")
  }

  // The shipped slider bakes its config (test above); this covers the OTHER
  // thing the engine must keep doing: a hand-written expression as hostile as
  // the retired `$lookup($domain)` tier, because nothing stops an author from
  // writing one. The fallback evaluates it and must resolve it correctly.
  test("slider fill: a hand-written domain-keyed expr still evaluates") {
    val expr =
      """cel.bind(v, {'light':'brightness','cover':'current_position'}[domain] in attr """ +
        """? attr[{'light':'brightness','cover':'current_position'}[domain]] : null, """ +
        """v != null ? str(math.round(100.0 - (double(v) - """ +
        """double({'light':1.0,'cover':0.0}[domain])) * 100.0 / """ +
        """(double({'light':255.0,'cover':100.0}[domain]) - double({'light':1.0,'cover':0.0}[domain])))) : '100')"""
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
