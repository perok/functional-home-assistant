package fh.view.runtime

import fh.view.testkit.Conds

import fh.view.model.*
import io.circe.Json

class StepsSuite extends munit.FunSuite {

  private def compile(src: String): Cel.Program =
    Cel.parse(src).fold(e => fail(e), identity)

  private def run(
      src: String,
      state: String,
      attributes: Map[String, Json] = Map.empty,
      entity: String = "sensor.x"
  ): String =
    Cel.run(
      compile(src),
      EntityState(entity, state, attributes),
      "dashboard"
    )

  test("round to n decimals") {
    assertEquals(
      run("str(math.round(num(value.state) * 10.0) / 10.0)", "21.44"),
      "21.4"
    )
    assertEquals(
      run("str(math.round(num(value.state) * 10.0) / 10.0)", "21.46"),
      "21.5"
    )
    assertEquals(run("str(math.round(num(value.state)))", "1499.6"), "1500")
  }

  test("whole-number results drop the decimal point") {
    assertEquals(
      run("str(math.round(num(value.state) * 1000.0)) + ' W'", "1.5"),
      "1500 W"
    )
    assertEquals(
      run("str(num(value.state) * 1.8 + 32.0)", "100"),
      "212"
    )
  }

  test("arithmetic keeps real decimals") {
    assertEquals(
      run(
        "str(math.round((num(value.state) * 1.8 + 32.0) * 10.0) / 10.0)",
        "37"
      ),
      "98.6"
    )
  }

  test("string concat appends a unit") {
    assertEquals(run("value.state + ' kWh'", "5"), "5 kWh")
  }

  test("conditional maps a state to display text") {
    assertEquals(run("value.state == 'on' ? 'Open' : 'Closed'", "on"), "Open")
    assertEquals(
      run("value.state == 'on' ? 'Open' : 'Closed'", "off"),
      "Closed"
    )
  }

  test("string library functions") {
    assertEquals(run("value.state.replace('o', '0')", "on"), "0n")
  }

  test("same-entity: attr reads a sibling attribute, guarded") {
    assertEquals(
      run(
        "value.state + ' ' + ('unit_of_measurement' in value.attr ? value.attr['unit_of_measurement'] : '')",
        "21.5",
        attributes = Map("unit_of_measurement" -> Json.fromString("°C"))
      ),
      "21.5 °C"
    )
    // The shipped strings guard with `'x' in attr`; unguarded, the card shows
    // the error.
    assert(
      run(
        "value.state + ' ' + value.attr['unit_of_measurement']",
        "21.5"
      ).startsWith("cel error:"),
      clue =
        run("value.state + ' ' + value.attr['unit_of_measurement']", "21.5")
    )
  }

  test("same-entity: numeric attributes stay numeric for arithmetic") {
    assertEquals(
      run(
        "str(math.round(double(value.attr['brightness']) * 100.0 / 255.0)) + '%'",
        "on",
        attributes = Map("brightness" -> Json.fromInt(128))
      ),
      "50%"
    )
  }

  test("auto-unit pattern: append the unit only when present") {
    val expr = "value.state + ('unit_of_measurement' in value.attr" +
      " ? ' ' + value.attr['unit_of_measurement'] : '')"
    assertEquals(
      run(expr, "21.5", Map("unit_of_measurement" -> Json.fromString("°C"))),
      "21.5 °C"
    )
    assertEquals(run(expr, "42"), "42")
  }

  test("evaluation error renders the CEL message on the card (no crash)") {
    // The card shows the error rather than the raw value or a crashed render.
    val out =
      run("str(math.round(num(value.state) * 10.0) / 10.0)", "unavailable")
    assert(out.nonEmpty, clue = out)
    assertNotEquals(out, "unavailable")
  }

  // Unavailable/unknown entities never reach a transform; the renderer shows
  // the raw state (see RendererSuite).

  test("null result becomes empty (so the slot default can take over)") {
    // CEL has no `? x : null` (both arms share a type); null arrives as the
    // shipped slider strings produce it, a guarded read falling back to null.
    assertEquals(run("cel.bind(v, null, v)", "z"), "")
    assertEquals(
      run(
        "cel.bind(v, 'brightness' in value.attr ? value.attr['brightness'] : null, v)",
        "off"
      ),
      ""
    )
  }

  test("an optional renders like the null it means, never as its wrapper") {
    val attrs = Map("brightness" -> Json.fromInt(120))
    // An empty optional arrives as a plain `java.util.Optional`; without the
    // unwrap `String.valueOf` puts the literal `Optional.empty` in the DOM.
    assertEquals(run("value.attr[?'brightness']", "on", attrs), "120")
    assertEquals(run("value.attr[?'nope']", "on", attrs), "")
    assertEquals(run("optional.none()", "on"), "")
    assertEquals(run("optional.of('x')", "on"), "x")
    // `''` means absent, the rule `ValueExpr.default` applies one layer up.
    assertEquals(run("optional.ofNonZeroValue('')", "on"), "")

    // The guarded read without a ternary, byte for byte the spelling every
    // shipped transform uses.
    assertEquals(
      run("value.attr[?'brightness'].orValue('none')", "on", attrs),
      run(
        "'brightness' in value.attr ? value.attr['brightness'] : 'none'",
        "on",
        attrs
      )
    )
    assertEquals(
      run("value.attr[?'nope'].orValue('none')", "on", attrs),
      run("'nope' in value.attr ? value.attr['nope'] : 'none'", "on", attrs)
    )
  }

  test("identity bindings: domain and entity_id come from the entity id") {
    assertEquals(run("value.domain", "on", entity = "light.kitchen"), "light")
    assertEquals(
      run("value.entity_id", "on", entity = "light.kitchen"),
      "light.kitchen"
    )
  }

  test("the dashboard slug binds independently of the entity") {
    assertEquals(
      Cel.run(
        compile("dashboard_slug"),
        EntityState("", "", Map.empty),
        "kitchen"
      ),
      "kitchen"
    )
  }

  // ADR 0016 bakes a tap's action at build time; a hand-written CEL map-index
  // over `domain` must still derive one.
  test("a map-indexed action over domain still resolves (fallback)") {
    val expr =
      """cel.bind(m, {'scene': 'scene/turn_on'}, """ +
        """value.domain in m ? m[value.domain] : 'homeassistant/toggle')"""
    assertEquals(run(expr, "on", entity = "scene.movie"), "scene/turn_on")
    assertEquals(
      run(expr, "on", entity = "light.kitchen"),
      "homeassistant/toggle"
    )
    // Identity-only: resolves with no usable state.
    assertEquals(
      run(expr, "unavailable", entity = "scene.movie"),
      "scene/turn_on"
    )
  }

  test("parse rejects malformed CEL and empty input") {
    assert(Cel.parse("'on' ? : 'x'").isLeft)
    assert(Cel.parse("   ").isLeft)
  }

  test("slider fill: --_end percent from the position attr, null-guarded") {
    // The static tier the slider bakes for a light: fill = 100 - value% of the
    // range, from the right (BeerCSS). `double(v)` is load-bearing: an attr
    // arrives as a Long, and bare `v - 1.0` compiles but throws at evaluation.
    val expr =
      "str(cel.bind(v, 'brightness' in value.attr ? value.attr['brightness'] : null, " +
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
    // What beer.min.js writes is 39.37007874015748%, not 39%; the 10-digit
    // stringifier's margin makes this "…02", not "…15".
    assertEquals(
      run(
        expr,
        "on",
        attributes = Map("brightness" -> Json.fromInt(155)),
        entity = "light.kitchen"
      ),
      "39.3700787402%"
    )
    // An off light has no brightness: empty fill, not an eval error in the
    // style attribute.
    assertEquals(run(expr, "off", entity = "light.kitchen"), "100%")
  }

  test("slider fill colour: rgb_color wins, else the kelvin ramp, else blank") {
    // `double(k)`, since kelvin arrives as Long; `''` for the kelvin-absent
    // arm, since CEL types both ternary arms; `size(rgb)` gated behind
    // presence; and `str(...)`, which strips a whole double's `.0` where CEL's
    // `string(math.round(x))` keeps it.
    val expr =
      """cel.bind(rgb, 'rgb_color' in value.attr ? value.attr['rgb_color'] : null,
        |  cel.bind(k, 'color_temp_kelvin' in value.attr ? value.attr['color_temp_kelvin'] : null,
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
    assertEquals(
      light("color_temp_kelvin" -> Json.fromInt(1800)),
      "rgb(255,166,87)"
    )
    // A cover, a fan or an off light: `''`, so the slot's `currentcolor`
    // default takes over.
    assertEquals(light("brightness" -> Json.fromInt(155)), "")
  }

  // Nothing stops an author writing a domain-keyed expression by hand, so the
  // fallback must still evaluate one correctly.
  test("slider fill: a hand-written domain-keyed expr still evaluates") {
    val expr =
      """cel.bind(v, {'light':'brightness','cover':'current_position'}[value.domain] in value.attr """ +
        """? value.attr[{'light':'brightness','cover':'current_position'}[value.domain]] : null, """ +
        """v != null ? str(math.round(100.0 - (double(v) - """ +
        """double({'light':1.0,'cover':0.0}[value.domain])) * 100.0 / """ +
        """(double({'light':255.0,'cover':100.0}[value.domain]) - double({'light':1.0,'cover':0.0}[value.domain])))) : '100')"""
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

  // The simple tier (ADR 0028): a source read and its format steps are defined
  // by their idiomatic CEL spelling, and the engine's output on that spelling
  // over the hostile sweep is what the tier must render byte for byte. Where
  // the engine errors on a mistyped value and the tier renders its absent
  // form, the divergence is pinned here and documented on the step.

  private def on(part: Part, steps: Step*): ValueExpr =
    ValueExpr(Value.Entity(None, part), steps.toList)
  private def state(steps: Step*) = on(Part.State, steps*)
  private def attr(name: String, steps: Step*) = on(Part.Attr(name), steps*)
  private def text(s: String): Value.Operand = Value.Literal(Lit.Text(s))
  private def real(d: Double): Value.Operand = Value.Literal(Lit.Real(d))
  private def unit(name: String) =
    Step.WithUnit(Value.Entity(None, Part.Attr(name)))
  private def matchOf(cases: Map[String, String], otherwise: String) =
    Step.Match(cases.map((k, v) => k -> Lit.Text(v)), text(otherwise))

  /** What the renderer's evaluator makes of `shape` over `e`. */
  private def runShape(shape: ValueExpr, e: EntityState): String =
    val out = Steps.text(
      Conds.run(shape)(
        e,
        Map(e.entityId -> e),
        Evaluator.unanswered
      )
    )
    // As a slot shows it: nothing to show takes the reading's default.
    if (out.nonEmpty) out
    else shape.default.fold("")(l => Steps.text(l.raw))

  private def agree(
      shape: ValueExpr,
      cel: String,
      probes: List[EntityState]
  ): Unit = {
    val compiled = compile(cel)
    probes.foreach { e =>
      assertEquals(
        runShape(shape, e),
        Cel.run(compiled, e, "dashboard"),
        clue = s"[$cel] state=${e.state} attrs=${e.attributes}"
      )
    }
  }

  private def es(
      state: String,
      attrs: (String, Json)*
  ): EntityState =
    EntityState("light.kitchen", state, attrs.toMap)

  private def d(v: Double): Json = Json.fromDouble(v).get

  private val percentExpr =
    "value.attr[?'brightness'].optMap(v, " +
      "str(math.round((double(v) - 1.0) * 100.0 / (255.0 - 1.0))) + ' %')" +
      ".orValue('0 %')"
  private val fillExpr =
    "value.attr[?'brightness'].optMap(v, " +
      "str(100.0 - ((double(v) - 1.0) * 100.0 / (255.0 - 1.0))))" +
      ".orValue('100') + '%'"

  test("definition: state and the guarded attr read") {
    val probes = List(
      es("on"),
      es("on", "friendly_name" -> Json.fromString("Hall")),
      es("on", "friendly_name" -> Json.fromString("")),
      es("on", "friendly_name" -> Json.fromInt(7)),
      es(
        "on",
        "friendly_name" -> Json.arr(d(3.5), d(4.0))
      ), // exotic value: both paths String.valueOf it
      es("off", "brightness" -> Json.fromInt(200))
    )
    agree(state(), "value.state", probes)
    agree(attr("brightness"), "value.attr[?'brightness']", probes)
  }

  test("definition: unit suffix, literal prefix/suffix, and the state match") {
    val probes = List(
      es("on"),
      es("on", "unit_of_measurement" -> Json.fromString("°C")),
      es("on", "unit_of_measurement" -> Json.fromString("")),
      es("21.44"),
      es("locked"),
      es("unlocking"),
      es("jammed"),
      es("")
    )
    agree(
      state(unit("unit_of_measurement")),
      "value.state + value.attr[?'unit_of_measurement'].optMap(u, ' ' + u).orValue('')",
      probes
    )
    agree(state(Step.Prefix(text("lit: "))), "'lit: ' + value.state", probes)
    agree(state(Step.Suffix(text(" W"))), "value.state + ' W'", probes)
    agree(
      state(matchOf(Map("on" -> "Open"), "Closed")),
      "cel.bind(m, {'on': 'Open'}, value.state in m ? m[value.state] : 'Closed')",
      probes
    )
    agree(
      state(matchOf(Map("locked" -> "lock/unlock"), "lock/lock")),
      "cel.bind(m, {'locked': 'lock/unlock'}, value.state in m ? m[value.state] : 'lock/lock')",
      probes
    )
    // HA's `isWaiting`: a two-armed enum would need three transforms and three
    // signals.
    agree(
      state(
        matchOf(
          Map("locking" -> "true", "unlocking" -> "true", "opening" -> "true"),
          ""
        )
      ),
      "cel.bind(m, {'locking': 'true', 'unlocking': 'true', 'opening': 'true'}, " +
        "value.state in m ? m[value.state] : '')",
      probes
    )
    agree(
      state(
        matchOf(
          Map("locked" -> "mdi-lock", "unlocked" -> "mdi-lock-open"),
          "mdi-lock-alert"
        )
      ),
      "cel.bind(m, {'locked': 'mdi-lock', 'unlocked': 'mdi-lock-open'}, " +
        "value.state in m ? m[value.state] : 'mdi-lock-alert')",
      probes
    )
    agree(
      state(matchOf(Map.empty, "n/a")),
      "cel.bind(m, {}, value.state in m ? m[value.state] : 'n/a')",
      probes
    )
  }

  test("key: a Match key cannot be forged by a separator inside a value") {
    // A Match's arity is not fixed, so its key is length-prefixed: a collision
    // would put two transforms on one signal.
    val a = state(matchOf(Map("a" -> "b:c"), "z"))
    val b = state(matchOf(Map("a:b" -> "c"), "z"))
    assertNotEquals(a.key, b.key)
    assertEquals(
      state(matchOf(Map("x" -> "1", "y" -> "2"), "z")).key,
      state(matchOf(Map("y" -> "2", "x" -> "1"), "z")).key
    )
  }

  test("divergence: the unit step treats a non-string unit as absent") {
    // The engine errors on `' ' + 5`, and its error text is not the contract;
    // the tier renders the state alone.
    val e = es("on", "unit_of_measurement" -> Json.fromInt(5))
    assertEquals(runShape(state(unit("unit_of_measurement")), e), "on")
    assert(
      Cel
        .run(
          compile(
            "value.state + ('unit_of_measurement' in value.attr ? ' ' + " +
              "value.attr['unit_of_measurement'] : '')"
          ),
          e,
          "dashboard"
        )
        .startsWith("cel error:")
    )
  }

  test("divergence: an empty value takes no unit, so the default applies") {
    // CEL's spelling prints the unit alone, ` W`, for an empty state.
    val e = es("", "unit_of_measurement" -> Json.fromString("W"))
    assertEquals(runShape(state(unit("unit_of_measurement")), e), "")
    assertEquals(
      Cel.run(
        compile(
          "value.state + value.attr[?'unit_of_measurement'].optMap(u, ' ' + u).orValue('')"
        ),
        e,
        "dashboard"
      ),
      " W"
    )
  }

  test(
    "definition: the slider's range percent and fill over the hostile sweep"
  ) {
    // Edges, knife edges, the full range and beyond, absent, and the string
    // form `double()` accepts: what the bench's Fill/Percent batteries swept.
    val brightnesses: List[Json] = List(
      d(-0.27), // raw = -0.5±ulp: the rounding mode's knife edge
      Json.fromInt(0), // below min: the negative arm
      Json.fromInt(1), // the min edge: exactly 0 %
      d(1.005),
      Json.fromInt(2),
      d(63.5),
      Json.fromInt(127),
      Json.fromInt(128), // exactly 50 %
      d(129.27),
      Json.fromInt(254),
      Json.fromInt(255), // the max edge: exactly 100 %
      Json.fromInt(256), // beyond max
      Json.fromString("128") // the engine's double() accepts string numbers
    )
    val probes: List[EntityState] =
      brightnesses.map(b => es("on", "brightness" -> b)) ++
        List(
          es("on"), // absent position: '0 %' / '100%'
          es("on", "brightness" -> Json.Null) // null attr is dropped as absent
        )
    // The builders' defaults (`simple.pkl`), which CEL spells as `orValue`.
    agree(
      attr("brightness", Step.Percent(real(1.0), real(255.0)))
        .copy(default = Some(Lit.Text("0 %"))),
      percentExpr,
      probes
    )
    agree(
      attr("brightness", Step.Fill(real(1.0), real(255.0)))
        .copy(default = Some(Lit.Text("100%"))),
      fillExpr,
      probes
    )
  }

  private val durationExpr =
    "cel.bind(t, int(math.round(double(value.state) * 60.0)), " +
      "t <= 0 ? '0s' " +
      ": t >= 3600 ? str(t / 3600) + 'h ' + str(t % 3600 / 60) + 'm' " +
      ": t >= 60 ? str(t / 60) + 'm' " +
      ": str(t) + 's')"

  private val minutes = state(Step.Duration(60.0))

  test("definition: a duration reading over the hostile sweep") {
    // Where a hand-rolled formatter and the engine part: each tier's first and
    // last value, the rounding knife edge, and the negative arm a stopped
    // countdown reaches.
    val readings = List(
      "-5", // overshot: clamps rather than showing a negative
      "0",
      "0.008", // 0.48s -> rounds to 0, so the clamp arm, not '0s' by luck
      "0.009", // 0.54s -> rounds to 1
      "0.5", // 30s
      "0.99", // 59.4 -> 59s, the last second-tier value
      "1", // exactly 60s -> the minute tier's first
      "1.5",
      "59", // the last minute-tier value
      "60", // exactly 3600s -> the hour tier's first, and 0 minutes
      "60.5",
      "253", // the live washer's own reading: 4h 13m
      "1439",
      "2322" // the live washer's lifetime total, in the same unit
    )
    agree(minutes, durationExpr, readings.map(es(_)))
  }

  test("definition: a duration in SECONDS is the same shape, unscaled") {
    // The scale is the whole difference between two integrations reporting the
    // same wash.
    val secondsExpr = durationExpr.replace("* 60.0", "* 1.0")
    val readings = List("0", "1", "59", "60", "3599", "3600", "15180")
    agree(state(Step.Duration(1.0)), secondsExpr, readings.map(es(_)))
  }

  test("a duration reads as a person would say it") {
    // Agreeing with CEL does not make either right; these are the readings.
    def fmt(v: String) = runShape(minutes, es(v))
    assertEquals(fmt("253"), "4h 13m")
    assertEquals(fmt("60"), "1h 0m")
    assertEquals(fmt("59"), "59m")
    assertEquals(fmt("1"), "1m")
    assertEquals(fmt("0.5"), "30s")
    assertEquals(fmt("0"), "0s")
    assertEquals(fmt("-5"), "0s")
  }

  test("divergence: a duration renders EMPTY on an unreadable state") {
    // A dishwasher between programmes reports `unknown`. `0s` would claim it
    // just finished, so the absent form is empty and the slot default takes
    // over.
    assertEquals(runShape(minutes, es("unknown")), "")
    assertEquals(runShape(minutes, es("unavailable")), "")
    assertEquals(runShape(minutes, es("")), "")
    assert(
      Cel
        .run(compile(durationExpr), es("unknown"), "dashboard")
        .startsWith("cel error:")
    )
  }

  test(
    "divergence: percent/fill give no value on unparseable values"
  ) {
    // The engine errors on `double("")` / `double("on")`; the tier gives no
    // value, as for an absent attribute, and the reading's default shows.
    val empty = es("on", "brightness" -> Json.fromString(""))
    val word = es("on", "brightness" -> Json.fromString("on"))
    assertEquals(
      runShape(attr("brightness", Step.Percent(real(1.0), real(255.0))), empty),
      ""
    )
    assertEquals(
      runShape(attr("brightness", Step.Fill(real(1.0), real(255.0))), word),
      ""
    )
    assert(
      Cel
        .run(compile(percentExpr), empty, "dashboard")
        .startsWith("cel error:")
    )
  }

  test("the key is injective across shapes and stable") {
    assertEquals(state().key, "state")
    assertEquals(attr("brightness").key, "attr:brightness")
    assertNotEquals(attr("x").key, state(unit("x")).key)
    assertNotEquals(
      attr("x", Step.Percent(real(1.0), real(2.0))).key,
      attr("x", Step.Fill(real(1.0), real(2.0))).key
    )
    assertEquals(
      attr("x", Step.Percent(real(1.0), real(255.0))).key,
      attr("x", Step.Percent(real(1.0), real(255.0))).key
    )
    // Otherwise one appliance's minutes would paint into the other's seconds.
    assertNotEquals(minutes.key, state(Step.Duration(1.0)).key)
  }

  // The wire form: every step and value is an object whose `kind` names its
  // case. A structure missing an argument, or carrying one it does not know,
  // must fail loudly, not render blank.

  private def decode(src: String) = io.circe.parser.decode[Step](src)

  test("every step the Pkl module can spell decodes into a runtime shape") {
    // Written in two languages, so only this checks they agree; a literal,
    // since a list derived from the Scala enum would agree with itself.
    List(
      """{"kind":"Prefix","text":"x"}""",
      """{"kind":"Suffix","text":"x"}""",
      """{"kind":"WithUnit","unit":{"kind":"Entity","part":{"kind":"Attr","name":"u"}}}""",
      """{"kind":"Percent","min":1,"max":255}""",
      """{"kind":"Fill","min":1,"max":255.0}""",
      """{"kind":"Duration","scale":60}""",
      """{"kind":"Match","cases":{"on":"Open"},"otherwise":"Closed"}""",
      """{"kind":"Cel","src":"value.state"}""",
      """{"kind":"Chart"}"""
    ).foreach { src =>
      assert(
        decode(src).isRight,
        s"$src is spellable in Pkl but does not decode"
      )
    }
  }

  test("a step outside that set, or a field it does not know, is refused") {
    assert(decode("""{"kind":"AttrOrId","attribute":"x"}""").isLeft)
    assert(decode("""{"kind":"Prefix","text":"x","literal":"y"}""").isLeft)
  }

  test("a missing argument fails the decode rather than defaulting") {
    // The typed Pkl constructors cannot produce these, which is why nothing
    // downstream would catch them.
    assert(decode("""{"kind":"Prefix"}""").isLeft, "prefix with no text")
    assert(decode("""{"kind":"Percent"}""").isLeft, "percent with no range")
    assert(
      decode("""{"kind":"Percent","min":1}""").isLeft,
      "percent with only half a range"
    )
    assert(
      decode("""{"kind":"Match","cases":{}}""").isLeft,
      "match with no otherwise"
    )
  }

  test("the decoder reads a format and a match off real JSON") {
    assertEquals(
      decode("""{"kind":"Percent","min":1,"max":255}"""),
      Right(
        Step.Percent(Value.Literal(Lit.Whole(1)), Value.Literal(Lit.Whole(255)))
      )
    )
    // `"false"` would be truthy as a Mustache section.
    assertEquals(
      decode("""{"kind":"Match","cases":{"on":true},"otherwise":false}"""),
      Right(
        Step.Match(Map("on" -> Lit.Bool(true)), Value.Literal(Lit.Bool(false)))
      )
    )
  }
}
