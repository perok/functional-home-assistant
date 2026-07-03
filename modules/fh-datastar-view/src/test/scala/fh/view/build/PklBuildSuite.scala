package fh.view.build

import fh.view.model.{Dashboard, LayoutNode}

class PklBuildSuite extends munit.FunSuite {

  test("PklBuild evaluates a pkl module to JSON via SourceEval dispatch") {
    val tmp = os.temp.dir()
    os.write(
      tmp / "test.pkl",
      """module test
        |
        |a = 1
        |
        |output {
        |  renderer = new JsonRenderer { omitNullProperties = true }
        |}
        |""".stripMargin
    )

    val result = SourceEval.eval(tmp, "test.pkl")
    assert(result.isRight, clue = result)
    val r = result.toOption.get
    assertEquals(r.value.hcursor.get[Int]("a").toOption, Some(1))
    assert(r.imports.contains(tmp / "test.pkl"))
  }

  test("PklBuild surfaces pkl errors as Left with file/line context") {
    val tmp = os.temp.dir()
    os.write(
      tmp / "bad.pkl",
      """module bad
        |
        |a: Int = "not an int"
        |
        |output { renderer = new JsonRenderer {} }
        |""".stripMargin
    )

    val result = SourceEval.eval(tmp, "bad.pkl")
    assert(result.isLeft, clue = result)
    assert(result.left.exists(_.contains("bad.pkl")), clue = result)
  }

  test("SourceEval rejects unknown extensions") {
    assert(SourceEval.eval(os.temp.dir(), "x.yaml").isLeft)
  }

  /** The real Pkl library modules, as shipped in the resources dir. */
  private val resourcesLib =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards" / "lib"

  /** Copy the given lib modules into `tmp/lib/` (entries import them by the
    * relative path `lib/<name>`, so tests must preserve the layout).
    */
  private def copyLib(tmp: os.Path, names: String*): Unit = {
    os.makeDir.all(tmp / "lib")
    names.foreach(n => os.copy.into(resourcesLib / n, tmp / "lib"))
  }

  test("hass.pkl types the dump's entity shapes with a generic fallback") {
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "lib/hass.pkl"
        |
        |light: hass.LightEntity = new {
        |  entity_id = "light.kitchen"
        |  friendly_name = "Kitchen"
        |  area_id = "kitchen"
        |  color_mode = "color_temp"
        |  // Assignment, not amend: the default is null, and amending null is
        |  // a type error when the value is forced.
        |  effect_list = new Listing { "colorloop" }
        |}
        |
        |tv: hass.GenericEntity = new {
        |  entity_id = "media_player.tv"
        |  domain = "media_player"
        |}
        |
        |output { renderer = new JsonRenderer { omitNullProperties = true } }
        |""".stripMargin
    )

    val result = SourceEval.eval(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    assertEquals(
      c.downField("light").get[String]("domain").toOption,
      Some("light")
    )
    assertEquals(
      c.downField("light").get[List[String]]("effect_list").toOption,
      Some(List("colorloop"))
    )
    assertEquals(
      c.downField("tv").get[String]("domain").toOption,
      Some("media_player")
    )
    // Constraint violations (a bad entity_id) fail the eval with a Pkl error.
    os.write.over(
      tmp / "probe.pkl",
      """module probe
        |import "lib/hass.pkl"
        |bad: hass.SensorEntity = new { entity_id = "NotAnId" }
        |output { renderer = new JsonRenderer {} }
        |""".stripMargin
    )
    assert(SourceEval.eval(tmp, "probe.pkl").isLeft)
  }

  /** A small transformed dump (the OUTPUT shape of DataDump.transform): one
    * floor, one area, a light (with attributes), a sensor in the same area, an
    * area-less switch, and a friendly_name that exercises string escaping.
    */
  private val fakeTransformedDump = io.circe.parser
    .parse("""
      {
        "areas": {
          "kjokken": { "area_id": "kitchen_1", "floor_id": "g", "area_name": "Kjøkken" }
        },
        "floors": {
          "ground_floor": {
            "floor_id": "g",
            "floor_name": "Ground floor",
            "areas": {
              "kjokken": { "area_id": "kitchen_1", "floor_id": "g", "area_name": "Kjøkken" }
            }
          }
        },
        "entities": {
          "light_kitchen": {
            "entity_id": "light.kitchen",
            "friendly_name": "Kitchen \"main\" light",
            "domain": "light",
            "area_id": "kitchen_1",
            "floor_id": "g",
            "attributes": { "color_mode": "color_temp", "effect_list": ["colorloop"] }
          },
          "sensor_temp": {
            "entity_id": "sensor.temp",
            "friendly_name": null,
            "domain": "sensor",
            "area_id": "kitchen_1",
            "attributes": {}
          },
          "switch_garage": {
            "entity_id": "switch.garage",
            "friendly_name": "Garage",
            "domain": "switch",
            "attributes": {}
          }
        }
      }
    """)
    .toOption
    .get

  test("PklDump.render emits typed, backticked declarations") {
    val src = PklDump.render(fakeTransformedDump)
    assert(src.contains("import \"hass.pkl\""), clue = src)
    assert(
      src.contains("const hidden `e_light_kitchen`: hass.LightEntity"),
      clue = src
    )
    assert(src.contains("class `Area_kjokken` extends hass.Area"), clue = src)
    assert(
      src.contains("class `Floor_ground_floor` extends hass.Floor"),
      clue = src
    )
    // Escaped quotes survive; null friendly_name emits no assignment.
    assert(
      src.contains("friendly_name = \"Kitchen \\\"main\\\" light\""),
      clue = src
    )
    assert(
      src.contains("effect_list = new Listing { \"colorloop\" }"),
      clue = src
    )
    assert(src.contains("lights = List(`light_kitchen`)"), clue = src)
  }

  test("generated dump.pkl evaluates against hass.pkl with dot-path access") {
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl")
    os.write(tmp / "lib" / "dump.pkl", PklDump.render(fakeTransformedDump))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "lib/dump.pkl" as dump
        |
        |flat = dump.entities.light_kitchen.entity_id
        |viaFloor = dump.ground_floor.kjokken.light_kitchen.entity_id
        |areaLightCount = dump.areas.kjokken.lights.length
        |noArea = dump.entities.switch_garage.entity_id
        |
        |output { renderer = new JsonRenderer { omitNullProperties = true } }
        |""".stripMargin
    )

    val result = SourceEval.eval(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    assertEquals(c.get[String]("flat").toOption, Some("light.kitchen"))
    assertEquals(c.get[String]("viaFloor").toOption, Some("light.kitchen"))
    assertEquals(c.get[Int]("areaLightCount").toOption, Some(1))
    assertEquals(c.get[String]("noArea").toOption, Some("switch.garage"))
  }

  test(
    "theme.pkl emits the {tokens, tokensDark, stylesheets, styles, chrome} shape"
  ) {
    // Evaluated in place (read-only) — theme.pkl imports tokens.pkl by
    // relative path, so the real lib dir works directly as an entry dir.
    val result = SourceEval.eval(resourcesLib, "theme.pkl")
    assert(result.isRight, clue = result)
    val theme = result.toOption.get.value.hcursor.downField("theme")
    assert(
      theme
        .get[String]("chrome")
        .toOption
        .exists(_.contains("id=\"dashboard\"")),
      clue = result
    )
    assertEquals(
      theme.downField("tokens").get[String]("primary-color").toOption,
      Some("#03a9f4")
    )
    assert(
      theme.downField("tokensDark").keys.exists(_.nonEmpty),
      clue = result
    )
    assert(
      theme
        .get[List[String]]("stylesheets")
        .toOption
        .exists(_.exists(_.contains("pico"))),
      clue = result
    )
    assert(
      theme.get[String]("styles").toOption.exists(_.contains(".fh-row")),
      clue = result
    )
  }

  test("components.pkl carries the six MVP card templates with their slots") {
    val result = SourceEval.eval(resourcesLib, "components.pkl")
    assert(result.isRight, clue = result)
    val cards = result.toOption.get.value.hcursor.downField("cards")

    val expectedSlots = Map(
      "fhrow" -> Nil,
      "fhcol" -> Nil,
      "sectionTitle" -> List("label"),
      "entityCard" -> List("label", "value", "entity_id"),
      "button" -> List("label", "onclick"),
      "slider" -> List(
        "label",
        "state",
        "value",
        "action",
        "min",
        "max",
        "key",
        "entity_id"
      )
    )
    expectedSlots.foreach { case (name, slots) =>
      val card = cards.downField(name)
      assert(
        card.get[String]("template").toOption.exists(_.nonEmpty),
        clue = name
      )
      assertEquals(
        card.get[List[String]]("slots").toOption,
        Some(slots),
        clue = name
      )
    }
  }

  test("pkl-demo evaluates through the full pipeline into a valid Dashboard") {
    val resources = resourcesLib / os.up
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl", "theme.pkl", "tokens.pkl")
    os.copy.into(resources / "pkl-demo.pkl", tmp)

    // Fake transformed dump defining exactly the entities the demo references.
    val fakeDump = io.circe.parser
      .parse("""
        {
          "areas": {},
          "floors": {},
          "entities": {
            "sensor_ams_1a4e_p": {
              "entity_id": "sensor.ams_1a4e_p", "friendly_name": "Power",
              "domain": "sensor", "attributes": {}
            },
            "sensor_ams_1a4e_u1": {
              "entity_id": "sensor.ams_1a4e_u1", "friendly_name": "L1 voltage",
              "domain": "sensor", "attributes": {}
            },
            "light_philips_lct001_light": {
              "entity_id": "light.philips_lct001_light", "friendly_name": "Demo light",
              "domain": "light", "attributes": { "color_mode": "brightness" }
            }
          }
        }
      """)
      .toOption
      .get
    os.write(tmp / "lib" / "dump.pkl", PklDump.render(fakeDump))

    val result = SourceEval.eval(tmp, "pkl-demo.pkl")
    assert(result.isRight, clue = result)
    val r = result.toOption.get

    // The conservative import superset covers every .pkl in the dir.
    val importNames = r.imports.map(_.last)
    assert(
      Set(
        "pkl-demo.pkl",
        "components.pkl",
        "theme.pkl",
        "tokens.pkl",
        "hass.pkl",
        "dump.pkl"
      )
        .subsetOf(importNames),
      clue = importNames
    )

    // The FULL build pipeline: normalize + hoist are no-ops on Pkl output
    // (real children arrays, no inlineSurfaces markers) — running them proves
    // the decode path is shared with jsonnet unchanged.
    val decoded = DashboardBuild
      .hoistInlineSurfaces(DashboardBuild.normalizeChildren(r.value))
      .as[Dashboard]
    assert(decoded.isRight, clue = decoded)
    val d = decoded.toOption.get

    assert(
      Set("fhrow", "fhcol", "sectionTitle", "entityCard", "button", "slider")
        .subsetOf(d.cards.keySet),
      clue = d.cards.keySet
    )
    assert(d.surfaces.isEmpty)
    assert(d.theme.tokens.nonEmpty)
    assert(d.theme.chrome.contains("id=\"dashboard\""))

    val root = d.card.asInstanceOf[LayoutNode.Component]
    assertEquals(root.card, "fhcol")
    val children = root.children.map(_.asInstanceOf[LayoutNode.Component])
    assertEquals(
      children.map(_.card),
      List("sectionTitle", "entityCard", "entityCard", "slider", "button")
    )

    // Slider config resolved at build time, as STRING literals (the slot
    // decoder rejects numbers — the highest-risk contract rule).
    val slider = children(3)
    assertEquals(slider.slots("min").literal, Some("1"))
    assertEquals(slider.slots("max").literal, Some("255"))
    assertEquals(slider.slots("action").literal, Some("light/turn_on"))
    assertEquals(slider.slots("key").literal, Some("brightness"))
    assertEquals(
      slider.slots("entity_id").literal,
      Some("light.philips_lct001_light")
    )

    // The tapped entity card: constant `tappable` marker + an identity-derived
    // (non-reactive) onclick expression.
    val tapped = children(2)
    assertEquals(tapped.slots("tappable").literal, Some("1"))
    val onclick = tapped.slots("onclick")
    assertEquals(onclick.literal, None)
    assertEquals(onclick.reactive, false)
    assert(onclick.transform.contains("/sse/action/"), clue = onclick)

    // Validation (card refs, required slots, JSONata compile) passes.
    assertEquals(d.validate(SourceEval.literalLocator(r.imports)), Nil)
  }
}
