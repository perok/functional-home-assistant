package fh.view.model

import fh.view.runtime.EntityState
import io.circe.Json

class TransformSuite extends munit.FunSuite {
  import Transform.Simple

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
    // The shipped strings guard with `'x' in attr`; unguarded, the card shows
    // the error.
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
    // The card shows the error rather than the raw value or a crashed render.
    val out = run("str(math.round(num(state) * 10.0) / 10.0)", "unavailable")
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
        "cel.bind(v, 'brightness' in attr ? attr['brightness'] : null, v)",
        "off"
      ),
      ""
    )
  }

  test("an optional renders like the null it means, never as its wrapper") {
    val attrs = Map("brightness" -> Json.fromInt(120))
    // An empty optional arrives as a plain `java.util.Optional`; without the
    // unwrap `String.valueOf` puts the literal `Optional.empty` in the DOM.
    assertEquals(run("attr[?'brightness']", "on", attrs), "120")
    assertEquals(run("attr[?'nope']", "on", attrs), "")
    assertEquals(run("optional.none()", "on"), "")
    assertEquals(run("optional.of('x')", "on"), "x")
    // `''` means absent, the rule `SlotSource.default` applies one layer up.
    assertEquals(run("optional.ofNonZeroValue('')", "on"), "")

    // The guarded read without a ternary, byte for byte the spelling every
    // shipped transform uses.
    assertEquals(
      run("attr[?'brightness'].orValue('none')", "on", attrs),
      run("'brightness' in attr ? attr['brightness'] : 'none'", "on", attrs)
    )
    assertEquals(
      run("attr[?'nope'].orValue('none')", "on", attrs),
      run("'nope' in attr ? attr['nope'] : 'none'", "on", attrs)
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

  // ADR 0016 bakes a tap's action at build time; a hand-written CEL map-index
  // over `domain` must still derive one.
  test("a map-indexed action over domain still resolves (fallback)") {
    val expr =
      """cel.bind(m, {'scene': 'scene/turn_on'}, """ +
        """domain in m ? m[domain] : 'homeassistant/toggle')"""
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
    assert(Transform.parse("'on' ? : 'x'").isLeft)
    assert(Transform.parse("   ").isLeft)
  }

  test("slider fill: --_end percent from the position attr, null-guarded") {
    // The static tier the slider bakes for a light: fill = 100 - value% of the
    // range, from the right (BeerCSS). `double(v)` is load-bearing: an attr
    // arrives as a Long, and bare `v - 1.0` compiles but throws at evaluation.
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

  // The simple tier (ADR 0028): each [[Transform.Simple]] case is defined by
  // its idiomatic CEL spelling, and the engine's output on that spelling over
  // the hostile sweep is what `runSimple` must render byte for byte. Where the
  // engine errors on a mistyped value and the tier renders its absent form, the
  // divergence is pinned here and documented on the case.

  private def agree(
      shape: Transform.Simple,
      cel: String,
      probes: List[EntityState]
  ): Unit = {
    val compiled = compile(cel)
    probes.foreach { e =>
      assertEquals(
        Transform.runSimple(shape, e),
        Transform.run(compiled, e, "dashboard"),
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
    "attr[?'brightness'].optMap(v, " +
      "str(math.round((double(v) - 1.0) * 100.0 / (255.0 - 1.0))) + ' %')" +
      ".orValue('0 %')"
  private val fillExpr =
    "attr[?'brightness'].optMap(v, " +
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
    agree(Simple.State, "state", probes)
    agree(Simple.Attr("brightness"), "attr[?'brightness']", probes)
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
      Simple.UnitSuffix("unit_of_measurement"),
      "state + attr[?'unit_of_measurement'].optMap(u, ' ' + u).orValue('')",
      probes
    )
    agree(Simple.Prefix("lit: "), "'lit: ' + state", probes)
    agree(Simple.Suffix(" W"), "state + ' W'", probes)
    agree(
      Simple.Match(Map("on" -> "Open"), "Closed"),
      "cel.bind(m, {'on': 'Open'}, state in m ? m[state] : 'Closed')",
      probes
    )
    agree(
      Simple.Match(Map("locked" -> "lock/unlock"), "lock/lock"),
      "cel.bind(m, {'locked': 'lock/unlock'}, state in m ? m[state] : 'lock/lock')",
      probes
    )
    // HA's `isWaiting`: a two-armed enum would need three transforms and three
    // signals.
    agree(
      Simple.Match(
        Map("locking" -> "true", "unlocking" -> "true", "opening" -> "true"),
        ""
      ),
      "cel.bind(m, {'locking': 'true', 'unlocking': 'true', 'opening': 'true'}, " +
        "state in m ? m[state] : '')",
      probes
    )
    agree(
      Simple.Match(
        Map("locked" -> "mdi-lock", "unlocked" -> "mdi-lock-open"),
        "mdi-lock-alert"
      ),
      "cel.bind(m, {'locked': 'mdi-lock', 'unlocked': 'mdi-lock-open'}, " +
        "state in m ? m[state] : 'mdi-lock-alert')",
      probes
    )
    agree(
      Simple.Match(Map.empty, "n/a"),
      "cel.bind(m, {}, state in m ? m[state] : 'n/a')",
      probes
    )
  }

  test("key: a Match key cannot be forged by a separator inside a value") {
    // A Match's arity is not fixed, so its key is length-prefixed: a collision
    // would put two transforms on one signal.
    val a = Simple.Match(Map("a" -> "b:c"), "z")
    val b = Simple.Match(Map("a:b" -> "c"), "z")
    assertNotEquals(Simple.key(a), Simple.key(b))
    assertEquals(
      Simple.key(Simple.Match(Map("x" -> "1", "y" -> "2"), "z")),
      Simple.key(Simple.Match(Map("y" -> "2", "x" -> "1"), "z"))
    )
  }

  test("divergence: the unit tier treats a non-string unit as absent") {
    // The engine errors on `' ' + 5`, and its error text is not the contract;
    // the tier renders the state alone.
    val e = es("on", "unit_of_measurement" -> Json.fromInt(5))
    assertEquals(
      Transform.runSimple(Simple.UnitSuffix("unit_of_measurement"), e),
      "on"
    )
    assert(
      Transform
        .run(
          compile(
            "state + ('unit_of_measurement' in attr ? ' ' + " +
              "attr['unit_of_measurement'] : '')"
          ),
          e,
          "dashboard"
        )
        .startsWith("cel error:")
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
    agree(Simple.Percent("brightness", 1.0, 255.0), percentExpr, probes)
    agree(Simple.Fill("brightness", 1.0, 255.0), fillExpr, probes)
  }

  private val durationExpr =
    "cel.bind(t, int(math.round(double(state) * 60.0)), " +
      "t <= 0 ? '0s' " +
      ": t >= 3600 ? str(t / 3600) + 'h ' + str(t % 3600 / 60) + 'm' " +
      ": t >= 60 ? str(t / 60) + 'm' " +
      ": str(t) + 's')"

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
    agree(Simple.Duration(60.0), durationExpr, readings.map(es(_)))
  }

  test("definition: a duration in SECONDS is the same shape, unscaled") {
    // The scale is the whole difference between two integrations reporting the
    // same wash.
    val secondsExpr = durationExpr.replace("* 60.0", "* 1.0")
    val readings = List("0", "1", "59", "60", "3599", "3600", "15180")
    agree(Simple.Duration(1.0), secondsExpr, readings.map(es(_)))
  }

  test("a duration reads as a person would say it") {
    // Agreeing with CEL does not make either right; these are the readings.
    def fmt(v: String) = Transform.runSimple(Simple.Duration(60.0), es(v))
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
    assertEquals(Transform.runSimple(Simple.Duration(60.0), es("unknown")), "")
    assertEquals(
      Transform.runSimple(Simple.Duration(60.0), es("unavailable")),
      ""
    )
    assertEquals(Transform.runSimple(Simple.Duration(60.0), es("")), "")
    assert(
      Transform
        .run(compile(durationExpr), es("unknown"), "dashboard")
        .startsWith("cel error:")
    )
  }

  test(
    "divergence: percent/fill render the absent form on unparseable values"
  ) {
    // The engine errors on `double("")` / `double("on")`; the tier renders the
    // absent form, as for an absent attribute.
    val empty = es("on", "brightness" -> Json.fromString(""))
    val text = es("on", "brightness" -> Json.fromString("on"))
    assertEquals(
      Transform.runSimple(Simple.Percent("brightness", 1.0, 255.0), empty),
      "0 %"
    )
    assertEquals(
      Transform.runSimple(Simple.Fill("brightness", 1.0, 255.0), text),
      "100%"
    )
    assert(
      Transform
        .run(compile(percentExpr), empty, "dashboard")
        .startsWith("cel error:")
    )
  }

  test("a duration decodes off the wire under the name Pkl writes") {
    // Written in two languages, the `Op` typealias in `core/simple.pkl` and
    // `toSimple` here, so only this checks they agree; a mismatch is a decode
    // failure at boot. `duration` carries its argument only in `params`: its
    // read is the state.
    assertEquals(
      io.circe.parser
        .decode[Transform.Simple](
          """{"kind":"value","op":"duration","params":{"scale":60}}"""
        ),
      Right(Simple.Duration(60.0))
    )
  }

  test("the simple key is injective across structures and stable") {
    assertEquals(Transform.Simple.key(Simple.State), "state")
    assertEquals(
      Transform.Simple.key(Simple.Attr("brightness")),
      "attr:brightness"
    )
    assertNotEquals(
      Transform.Simple.key(Simple.Attr("x")),
      Transform.Simple.key(Simple.UnitSuffix("x"))
    )
    assertNotEquals(
      Transform.Simple.key(Simple.Percent("x", 1.0, 2.0)),
      Transform.Simple.key(Simple.Fill("x", 1.0, 2.0))
    )
    assertEquals(
      Transform.Simple.key(Simple.Percent("x", 1.0, 255.0)),
      Transform.Simple.key(Simple.Percent("x", 1.0, 255.0))
    )
    // Otherwise one appliance's minutes would paint into the other's seconds.
    assertNotEquals(
      Transform.Simple.key(Simple.Duration(60.0)),
      Transform.Simple.key(Simple.Duration(1.0))
    )
  }

  // The wire form: the flat `{op, value, params}` Pkl emits is parsed into the
  // enum once, here. A structure missing an argument must fail loudly, not
  // render blank.

  /** A literal, not derived: a derivation from the Scala enum would agree with
    * itself.
    */
  private val PklOps =
    List(
      "state",
      "attr",
      "suffixUnit",
      "prefix",
      "suffix",
      "percent",
      "fill",
      "duration"
    )

  private def wire(
      op: String,
      value: Option[String] = None,
      params: Map[String, String | Double] = Map.empty
  ) = Transform.SimpleWire.Value(op, value, params).toSimple

  test("every op the Pkl module can spell parses into a runtime shape") {
    // Every param any operator takes; each ignores what it does not need.
    val args = Map(
      "min" -> (1.0: String | Double),
      "max" -> (255.0: String | Double),
      "scale" -> (60.0: String | Double)
    )
    PklOps.foreach { op =>
      assert(
        wire(op, Some("brightness"), args).isRight,
        s"op `$op` is spellable in Pkl but does not parse"
      )
    }
  }

  test("an op outside that set is refused, not silently dropped") {
    assert(wire("attrOrId", Some("x")).isLeft)
    assert(wire("", Some("x")).isLeft)
  }

  test("a missing argument fails the parse rather than defaulting") {
    // A hand-written `SimpleValue` forgetting a field. The typed Pkl
    // constructors cannot produce these, which is why nothing downstream would
    // catch them.
    assert(wire("attr").isLeft, "attr with no value")
    assert(wire("prefix").isLeft, "prefix with no literal")
    assert(wire("percent", Some("brightness")).isLeft, "percent with no range")
    assert(
      wire(
        "percent",
        Some("brightness"),
        Map("min" -> (1.0: String | Double))
      ).isLeft,
      "percent with only half a range"
    )
    assert(
      wire(
        "percent",
        Some("b"),
        Map("min" -> ("lo": String | Double), "max" -> (2.0: String | Double))
      ).isLeft,
      "percent whose min is not a number"
    )
  }

  test("state takes no argument, and one it does not need is ignored") {
    assertEquals(wire("state"), Right(Simple.State))
    assertEquals(wire("state", Some("brightness")), Right(Simple.State))
  }

  test("a param may arrive as a numeric string, as Pkl's JSON may render it") {
    assertEquals(
      wire(
        "percent",
        Some("b"),
        Map("min" -> ("1": String | Double), "max" -> ("255": String | Double))
      ),
      Right(Simple.Percent("b", 1.0, 255.0))
    )
  }

  test("the decoder reads both wire shapes off real JSON") {
    def decode(src: String) =
      io.circe.parser.decode[Transform.Simple](src)
    assertEquals(
      decode(
        """{"kind":"value","op":"percent","value":"brightness",""" +
          """"params":{"min":1,"max":255}}"""
      ),
      Right(Simple.Percent("brightness", 1.0, 255.0))
    )
    assertEquals(
      decode("""{"kind":"value","op":"state"}"""),
      Right(Simple.State)
    )
    assertEquals(
      decode("""{"kind":"match","cases":{"on":"Open"},"otherwise":false}"""),
      Right(Simple.Match(Map("on" -> "Open"), false))
    )
    // `"false"` would be truthy as a Mustache section.
    assert(
      decode("""{"kind":"match","cases":{"on":true},"otherwise":false}""")
        .exists {
          case Simple.Match(cases, _) => cases("on") == (true: SlotValue)
          case _                      => false
        }
    )
    assert(decode("""{"kind":"value","op":"nope"}""").isLeft)
    // An unknown `kind` fails on the discriminator, not as a confusing unknown
    // op in `Value`.
    assert(decode("""{"kind":"nope","op":"state"}""").isLeft)
    // Optional on the wire, so Pkl can omit both.
    assertEquals(
      decode("""{"kind":"value","op":"suffixUnit","value":"u"}"""),
      Right(Simple.UnitSuffix("u"))
    )
  }
}
