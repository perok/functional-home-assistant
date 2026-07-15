package fh.view.build

import fh.view.model.{Dashboard, LayoutNode, Op, Predicate}
import fh.view.testkit.PklFixture
import io.circe.Json

class PklBuildSuite extends munit.FunSuite {

  /** Collect every [[LayoutNode.Dynamic]] reachable from a node (mirrors
    * BuildPhaseSuite's `dynamics` collector).
    */
  private def dynamics(node: LayoutNode): List[LayoutNode.Dynamic] =
    node match {
      case c: LayoutNode.Component => c.children.flatMap(dynamics)
      case d: LayoutNode.Dynamic   => List(d)
    }

  test("PklBuild evaluates a pkl module to JSON via SourceEval dispatch") {
    val tmp = os.temp.dir()
    os.write(
      tmp / "test.pkl",
      """module test
        |
        |a = 1
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
        |""".stripMargin
    )

    val result = SourceEval.eval(tmp, "bad.pkl")
    assert(result.isLeft, clue = result)
    assert(result.left.exists(_.contains("bad.pkl")), clue = result)
  }

  test("SourceEval rejects unknown extensions") {
    assert(SourceEval.eval(os.temp.dir(), "x.yaml").isLeft)
  }

  test("PklBuild.eval reports the entry's precise transitive imports only") {
    // A plain FILE import is tracked precisely: the entry + its transitive file
    // imports, and nothing else — an unrelated sibling that is never imported
    // is excluded (unlike the all-*.pkl superset). The `@fh-dashboard` alias
    // gets the same precision; that is pinned separately below.
    val tmp = os.temp.dir()
    os.makeDir.all(tmp / "lib")
    os.write(
      tmp / "lib" / "helper.pkl",
      """module helper
        |answer = 42
        |""".stripMargin
    )
    os.write(
      tmp / "unrelated.pkl",
      """module unrelated
        |orphan = 1
        |""".stripMargin
    )
    os.write(
      tmp / "entry.pkl",
      """module entry
        |
        |import "lib/helper.pkl" as h
        |
        |x = h.answer
        |""".stripMargin
    )

    val result = SourceEval.eval(tmp, "entry.pkl")
    assert(result.isRight, clue = result)
    val imports = result.toOption.get.imports

    assertEquals(
      imports,
      Set(
        tmp / "entry.pkl",
        tmp / "lib" / "helper.pkl"
      ),
      clue = imports
    )
    assert(!imports.contains(tmp / "unrelated.pkl"), clue = imports)
  }

  test("PklBuild.eval resolves @fh-dashboard imports back to their lib files") {
    // The guard behind `ServerApp.watchedSet` NOT bulk-adding `lib/`: because
    // `@fh-dashboard` is a LOCAL project dependency, the analyzer resolves its
    // `projectpackage:` imports back to real `lib/*.pkl` paths, so an aliased
    // library module lands in the watch set as an ordinary file. If that ever
    // regressed, `importSet` would silently fall back to the all-*.pkl
    // superset — which still contains `lib/hass.pkl`, so asserting only its
    // presence would pass vacuously. The `unrelated.pkl` exclusion is what
    // actually separates "precise" from "superset": the superset would sweep
    // it in.
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl")
    os.write(
      tmp / "unrelated.pkl",
      """module unrelated
        |orphan = 1
        |""".stripMargin
    )
    os.write(
      tmp / "probe.pkl",
      """import "@fh-dashboard/hass.pkl"
        |
        |light: hass.LightEntity = new { entity_id = "light.kitchen" }
        |id = light.entity_id
        |""".stripMargin
    )

    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val imports = result.toOption.get.imports

    assertEquals(
      imports,
      Set(tmp / "probe.pkl", tmp / "lib" / "hass.pkl"),
      clue = imports
    )
    assert(!imports.contains(tmp / "unrelated.pkl"), clue = imports)
  }

  test("the add-on seed layout evaluates from its manifests alone") {
    // Mirrors home-addon/Dockerfile's COPY steps exactly: lib/ (the
    // @fh-dashboard package), home/PklProject (the @fh-home manifest, with NO
    // dump beside it yet), the consumer PklProject, and the seeded entry.
    //
    // Two things this pins, neither visible to the rest of the suite because
    // the Dockerfile is never executed here:
    //   - the consumer project declares @fh-home, so shipping its manifest is
    //     mandatory: without it the dependency resolve fails and EVERY entry
    //     dies at startup, not just dump-importing ones;
    //   - a fresh add-on evaluates before `prepareDumps` has ever run, so the
    //     seed must build with the @fh-home package still empty.
    val tmp = os.temp.dir()
    val dashboards = resourcesLib / os.up
    os.copy(dashboards / "lib", tmp / "lib")
    os.makeDir.all(tmp / "home")
    os.copy.into(dashboards / "home" / "PklProject", tmp / "home")
    os.copy.into(dashboards / "PklProject", tmp)
    os.copy.into(
      os.pwd / "home-addon" / "dashboards-seed" / "dashboard.pkl",
      tmp
    )
    assert(!os.exists(tmp / "home" / "dump.pkl"), "seed must ship no dump")

    val result = SourceEval.eval(tmp, "dashboard.pkl")
    assert(result.isRight, clue = result)
  }

  /** The real Pkl library modules, as shipped in the resources dir. */
  private val resourcesLib =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards" / "lib"

  /** Copy the given lib modules into `tmp/lib/` and stage the Pkl projects, so
    * a probe resolves the aliases exactly as the live server does (ADR 0010,
    * Track B): the `@fh-dashboard` package manifest in `lib/`, the `@fh-home`
    * manifest in `home/`, and the consumer `PklProject` that maps both.
    *
    * Both manifests are staged unconditionally because the consumer project
    * declares both dependencies — a missing one fails the resolve for every
    * probe, not just the ones that import a dump. Staging is harmless for pure
    * file-import probes (which import by the relative `lib/<name>` path).
    */
  private def copyLib(tmp: os.Path, names: String*): Unit = {
    os.makeDir.all(tmp / "lib")
    names.foreach(n => os.copy.into(resourcesLib / n, tmp / "lib"))
    os.copy.into(resourcesLib / "PklProject", tmp / "lib")
    os.makeDir.all(tmp / "home")
    os.copy.into(resourcesLib / os.up / "home" / "PklProject", tmp / "home")
    os.copy.into(resourcesLib / os.up / "PklProject", tmp)
  }

  /** Write a generated dump into the staged `@fh-home` package, where
    * `DashboardBuild.prepareDumps` puts it in production — so a probe imports
    * it as `@fh-home/dump.pkl` and the emitted `import "@fh-dashboard/hass.pkl"`
    * resolves to the same `hass` identity `components.pkl` sees.
    */
  private def writeDump(tmp: os.Path, source: String): Unit = {
    os.makeDir.all(tmp / "home")
    os.write.over(tmp / "home" / "dump.pkl", source)
  }

  /** Evaluate a probe against the staged Pkl project (ADR 0010, Track B): a
    * probe that imports the library through the `@fh-dashboard` alias resolves
    * it from the copied `tmp/lib` (`PklBuild` resolves the network-free local
    * lockfile in-process). Pure file-import probes evaluate the same way — the
    * project is inert for them.
    */
  private def evalProj(tmp: os.Path, entry: String) =
    SourceEval.eval(tmp, entry)

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

  test("PklDump.render emits typed declarations, plain when legal") {
    val src = PklDump.render(fakeTransformedDump)
    // The schema comes in by ALIAS, not as a file sibling: the dump lives in
    // its own `@fh-home` package, and the alias is what lands its `hass` types
    // on the same URI `components.pkl` sees (ADR 0010, "Module identity").
    assert(src.contains("import \"@fh-dashboard/hass.pkl\""), clue = src)
    assert(
      src.contains("const hidden e_light_kitchen: hass.LightEntity"),
      clue = src
    )
    assert(src.contains("class Area_kjokken extends hass.Area"), clue = src)
    assert(
      src.contains("class Floor_ground_floor extends hass.Floor"),
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
    assert(src.contains("lights = List(light_kitchen)"), clue = src)
    // Every name in this dump is a legal plain identifier — no identifier is
    // backticked (the `///` doc header's markdown backticks don't count).
    val code = src.linesIterator.filterNot(_.trim.startsWith("///"))
    assert(!code.exists(_.contains("`")), clue = src)
  }

  test("PklDump.render backticks reserved-word and digit-leading names") {
    // Area "New" slugs to the Pkl keyword `new`; floor "3rd floor" slugs to
    // the digit-leading `3rd_floor` — both illegal as plain identifiers.
    val awkwardDump = io.circe.parser
      .parse("""
        {
          "areas": {
            "new": { "area_id": "new_1", "floor_id": "f3", "area_name": "New" }
          },
          "floors": {
            "3rd_floor": {
              "floor_id": "f3",
              "floor_name": "3rd floor",
              "areas": {
                "new": { "area_id": "new_1", "floor_id": "f3", "area_name": "New" }
              }
            }
          },
          "entities": {
            "light_lamp": {
              "entity_id": "light.lamp",
              "friendly_name": "Lamp",
              "domain": "light",
              "area_id": "new_1",
              "floor_id": "f3",
              "attributes": {}
            }
          }
        }
      """)
      .toOption
      .get
    val src = PklDump.render(awkwardDump)
    // The prefixed class names are legal plain identifiers (the `Area_`/
    // `Floor_` prefix guarantees a letter-leading, non-keyword name), so they
    // stay unquoted; only the bare slug used as a property key must be ticked.
    assert(src.contains("class Area_new extends hass.Area"), clue = src)
    assert(src.contains("class Floor_3rd_floor extends hass.Floor"), clue = src)
    assert(src.contains("`new`: Area_new = new {}"), clue = src)
    assert(src.contains("`3rd_floor`: Floor_3rd_floor = new {}"), clue = src)
    assert(src.contains("areas = List(`new`)"), clue = src)
    // The plain-safe entity name stays unquoted even in this dump.
    assert(
      src.contains("const hidden e_light_lamp: hass.LightEntity"),
      clue = src
    )

    // And the rendered module must actually evaluate, dot-paths included.
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl")
    writeDump(tmp, src)
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-home/dump.pkl" as dump
        |
        |areaId = dump.areas.`new`.area_id
        |floorName = dump.`3rd_floor`.floor_name
        |viaFloor = dump.`3rd_floor`.`new`.light_lamp.entity_id
        |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    assertEquals(c.get[String]("areaId").toOption, Some("new_1"))
    assertEquals(c.get[String]("floorName").toOption, Some("3rd floor"))
    assertEquals(c.get[String]("viaFloor").toOption, Some("light.lamp"))
  }

  test("generated dump.pkl evaluates against hass.pkl with dot-path access") {
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl")
    writeDump(tmp, PklDump.render(fakeTransformedDump))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-home/dump.pkl" as dump
        |
        |flat = dump.entities.light_kitchen.entity_id
        |viaFloor = dump.ground_floor.kjokken.light_kitchen.entity_id
        |areaLightCount = dump.areas.kjokken.lights.length
        |noArea = dump.entities.switch_garage.entity_id
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
    "theme-beer.pkl emits the {tokens, tokensDark, stylesheets, styles, chrome} shape"
  ) {
    // A probe entry re-exposes the theme so the assertions read a pinned
    // shape, independent of whatever else the lib module happens to export —
    // the Theme contract every implementation module must satisfy (the wire
    // snapshots additionally pin the beer theme's full JSON).
    val tmp = os.temp.dir()
    copyLib(tmp, "theme.pkl", "theme-beer.pkl", "tokens.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |import "lib/theme-beer.pkl" as themeMod
        |theme = themeMod.theme
        |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
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
        .exists(_.exists(_.contains("beercss"))),
      clue = result
    )
    assert(
      theme.get[String]("styles").toOption.exists(_.contains(".fh-row")),
      clue = result
    )
  }

  test("components.pkl derives the card registry from the card classes") {
    // `cards` is assembled via pkl:reflect over the module's concrete Node
    // subclasses (each class carries its template + declared slots as a hidden
    // `cardDef`). The key set must be EXACTLY the card classes — no strays
    // from non-card classes (Tab, Case, SliderSpec, ...), and nothing missing.
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |import "@fh-dashboard/components.pkl" as c
        |cards = c.cards
        |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val cards = result.toOption.get.value.hcursor.downField("cards")

    val expectedSlots = Map(
      "fhrow" -> Nil,
      "fhcol" -> Nil,
      "fhgrid" -> Nil,
      "sectionTitle" -> List("label"),
      "entityCard" -> List("label", "value", "entity_id"),
      "button" -> List("label", "onclick"),
      "tab" -> List("label", "onclick", "active"),
      "slider" -> List(
        "label",
        "state",
        "value",
        "action",
        "min",
        "max",
        "key",
        "entity_id"
      ),
      "popup" -> Nil,
      "tabs" -> Nil,
      "ifhost" -> Nil
    )
    assertEquals(
      cards.keys.map(_.toSet),
      Some(expectedSlots.keySet),
      clue = cards.keys
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
      // `tab` is the single wrapAsCell opt-out (the `.tabs > a` structural
      // selector); every other card omits the key (backend defaults TRUE).
      assertEquals(
        card.get[Option[Boolean]]("wrapAsCell").toOption.flatten,
        Option.when(name == "tab")(false),
        clue = name
      )
    }
    // Hidden cardDef never leaks into an emitted node.
    os.write.over(
      tmp / "probe.pkl",
      """module probe
        |import "@fh-dashboard/components.pkl" as c
        |node = new c.SectionTitle { text = "x" }
        |""".stripMargin
    )
    val nodeResult = evalProj(tmp, "probe.pkl")
    assert(nodeResult.isRight, clue = nodeResult)
    val nodeKeys =
      nodeResult.toOption.get.value.hcursor.downField("node").keys
    assert(
      nodeKeys.exists(ks => !ks.exists(_ == "cardDef")),
      clue = nodeKeys
    )
  }

  // ---------------------------------------------------------------------------
  // Full-pipeline tests over TEST-OWNED fixture entries.
  //
  // These evaluate small entries this suite owns (NOT the shipped dashboards,
  // which are free to evolve) through the real pipeline: Pkl -> hoist -> decode
  // -> validate. They set a DUMMY theme (PklFixture.dummyTheme), so the theme's
  // CSS is out of scope here — visual/theme coverage is the browser smoke plan's
  // job. Entities come from HouseFixture (via HouseFixture.transformedDump), the
  // same source the functional runtime tests serve.
  // ---------------------------------------------------------------------------

  /** A feature-rich fixture entry: containers, sectionTitle, entityCard
    * (default + tap), a domain-checked slider, a dynamic group, and both a
    * registered and an inline popup — enough composition to exercise the hoist
    * + decode path.
    */
  private val fixtureFeatures =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |import "@fh-dashboard/theme.pkl" as th
       |
       |theme = ${PklFixture.dummyTheme}
       |
       |surfaces {
       |  ["detail"] {
       |    body {
       |      c.title("Detail")
       |      c.entityCard(dump.entities.sensor_outside_temp)
       |      c.button("Close", c.closePopup())
       |    }
       |  }
       |}
       |
       |card = (c.column) {
       |  children {
       |    c.title("Features")
       |    c.entityCard(dump.entities.sensor_outside_temp)
       |    c.entityCard(dump.entities.light_kitchen).tap(c.toggleTap)
       |    c.slider(dump.entities.light_kitchen)
       |    new c.DynamicGroup {
       |      query = c.stateIs("on")
       |      render = (e) -> c.entityCard(e)
       |    }
       |    c.button("Detail…", c.openPopup("detail"))
       |    c.button("Inline…", c.openPopupInline(new c.Column {
       |      children {
       |        c.title("Inline")
       |        c.button("Close", c.closePopup())
       |      }
       |    }))
       |  }
       |}
       |""".stripMargin

  /** A surfaces fixture: a tabs group (two panels) + an If/else — the two
    * inline-surface hoist paths (tab panels and branches).
    */
  private val fixtureSurfaces =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |import "@fh-dashboard/theme.pkl" as th
       |
       |theme = ${PklFixture.dummyTheme}
       |
       |card = (c.column) {
       |  children {
       |    (c.tabs) {
       |      tabs {
       |        ["Temp"] { c.entityCard(dump.entities.sensor_outside_temp) }
       |        ["Light"] { c.entityCard(dump.entities.light_kitchen) }
       |      }
       |    }
       |    c.iff(c.entityIs("light.kitchen").and(c.stateIs("on")))
       |      .then(c.entityCard(dump.entities.light_kitchen))
       |      .`else`(c.entityCard(dump.entities.sensor_outside_temp))
       |  }
       |}
       |""".stripMargin

  test(
    "fixture-features builds through the full pipeline into a valid Dashboard"
  ) {
    val built = PklFixture.eval("fixture-features", fixtureFeatures)
    val hoisted = DashboardBuild.hoistInlineSurfaces(built.value)
    // Every @@NODE_ID@@ token was spliced with a real id — none survives.
    assert(
      !hoisted.noSpaces.contains(DashboardBuild.NodeIdToken),
      clue = "unspliced NODE_ID token remained in the hoisted JSON"
    )
    val d = hoisted.as[Dashboard].fold(e => fail(s"decode: $e"), identity)

    // The composed card set is present.
    assert(
      Set("fhcol", "sectionTitle", "entityCard", "slider", "button", "popup")
        .subsetOf(d.cards.keySet),
      clue = d.cards.keySet
    )
    // The registered popup plus a hoisted inline surface (keyed `<node-id>_self`).
    assert(d.surfaces.contains("detail"), clue = d.surfaces.keySet)
    assert(
      d.surfaces.keys.exists(_.endsWith("_self")),
      clue = d.surfaces.keySet
    )
    // One dynamic group in the layout.
    assertEquals(dynamics(d.card).size, 1, clue = d.card)
    // Validation (card refs, required slots, JSONata compile) passes.
    assertEquals(d.validate(SourceEval.literalLocator(built.imports)), Nil)
  }

  test(
    "fixture-surfaces builds tabs + If into hoisted surfaces that validate"
  ) {
    val built = PklFixture.eval("fixture-surfaces", fixtureSurfaces)
    val hoisted = DashboardBuild.hoistInlineSurfaces(built.value)
    assert(
      !hoisted.noSpaces.contains(DashboardBuild.NodeIdToken),
      clue = "unspliced NODE_ID token remained in the hoisted JSON"
    )
    val d = hoisted.as[Dashboard].fold(e => fail(s"decode: $e"), identity)

    // Two tab panels (…_t0/_t1) + an If's then/else branches, all hoisted.
    assert(
      d.cards.contains("tabs") && d.cards.contains("ifhost"),
      clue = d.cards.keySet
    )
    assert(
      d.surfaces.keys.exists(_.endsWith("_t0")) &&
        d.surfaces.keys.exists(_.endsWith("_t1")),
      clue = d.surfaces.keySet
    )
    assert(
      d.surfaces.keys.exists(_.endsWith("_then")) &&
        d.surfaces.keys.exists(_.endsWith("_else")),
      clue = d.surfaces.keySet
    )
    assertEquals(d.validate(SourceEval.literalLocator(built.imports)), Nil)
  }

  /** Evaluate a probe module that imports the real lib and decode its `node`
    * property as a Component.
    */
  private def probeComponent(body: String): LayoutNode.Component = {
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    os.write(
      tmp / "probe.pkl",
      s"""module probe
         |
         |import "@fh-dashboard/hass.pkl"
         |import "@fh-dashboard/components.pkl" as c
         |
         |$body
         |
         |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    result.toOption.get.value.hcursor
      .downField("node")
      .as[LayoutNode]
      .toOption
      .get
      .asInstanceOf[LayoutNode.Component]
  }

  /** Evaluate a probe module that imports the real lib and decode its `node`
    * property as a Dynamic group.
    */
  private def probeDynamic(body: String): LayoutNode.Dynamic = {
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    os.write(
      tmp / "probe.pkl",
      s"""module probe
         |
         |import "@fh-dashboard/hass.pkl"
         |import "@fh-dashboard/components.pkl" as c
         |
         |$body
         |
         |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    result.toOption.get.value.hcursor
      .downField("node")
      .as[LayoutNode]
      .toOption
      .get
      .asInstanceOf[LayoutNode.Dynamic]
  }

  test("caseOf drops the entity_id slot via the Mapping for-generator") {
    // Exercised EARLY (plan risk item 2): the `when (k != "entity_id")`
    // for-generator in `caseOf` that filters a card's slots into a dynamic case.
    // A `render` fallback lambda is applied to the internal SELF sentinel.
    val dyn = probeDynamic(
      """node = new c.DynamicGroup {
        |  query = c.stateIs("on")
        |  render = (e) -> c.entityCard(e)
        |}""".stripMargin
    )
    assertEquals(dyn.cases.size, 1)
    val slots = dyn.cases.head.slots
    assert(!slots.contains("entity_id"), clue = slots.keySet)
    // The other slots ride along: label (the $self live default) and value.
    assert(slots.contains("label"), clue = slots.keySet)
    assert(slots.contains("value"), clue = slots.keySet)
    // The $self label is the LIVE friendly_name default, not a baked literal.
    assertEquals(slots("label").literal, None)
    assertEquals(
      slots("label").transform,
      "$attr.friendly_name ? $attr.friendly_name : $entity_id"
    )
  }

  test("call-style entityCard emits the same node JSON as the `new` form") {
    // The call-style factory `(c.entityCard(x)) { ... }` is pure sugar for
    // `new c.EntityCard { entity = x; ... }`: same class, so the emitted node
    // JSON must be byte-identical. Evaluate both through the fake-dump pipeline
    // and compare the raw `card`/`ctor` node JSON (not just the decoded model).
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |
        |x: hass.LightEntity = new { entity_id = "light.kitchen" }
        |
        |call = (c.entityCard(x)) { tap = c.toggleTap }
        |ctor = new c.EntityCard { entity = x; tap = c.toggleTap }
        |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val cur = result.toOption.get.value.hcursor
    val call = cur.downField("call").focus
    val ctor = cur.downField("ctor").focus
    assert(call.isDefined && ctor.isDefined, clue = cur.keys)
    assertEquals(call, ctor, clue = (call, ctor))
  }

  test("builder methods emit the same node JSON as the amend form") {
    // The fluent config methods (`.tap(...).label(...)`) are pure sugar for the
    // paren-amend `(c.entityCard(x)) { tap = ...; label = ... }`: each amends
    // `this` and returns the same class, so late binding re-derives `slots` and
    // the emitted node JSON must be byte-identical across all three styles
    // (builder, amend, `new`). Covers EntityCard, Button, and Slider.
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |
        |x: hass.LightEntity = new { entity_id = "light.kitchen" }
        |
        |cardBuilder = c.entityCard(x).tap(c.toggleTap).label("Office")
        |cardAmend = (c.entityCard(x)) { tap = c.toggleTap; label = "Office" }
        |cardCtor = new c.EntityCard { entity = x; tap = c.toggleTap; label = "Office" }
        |
        |btnBuilder = c.button("Close", c.closePopup()).label("Dismiss")
        |btnAmend = new c.Button { label = "Dismiss"; action = c.closePopup() }
        |
        |sliderBuilder = c.slider(x).label("Lamp").min(10).max(200)
        |sliderAmend = new c.Slider { entity = x; label = "Lamp"; min = 10; max = 200 }
        |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val cur = result.toOption.get.value.hcursor
    def focus(k: String) = cur.downField(k).focus
    // EntityCard: builder == amend == new.
    assertEquals(focus("cardBuilder"), focus("cardAmend"))
    assertEquals(focus("cardBuilder"), focus("cardCtor"))
    // Button and Slider builder chains match their amend forms.
    assertEquals(focus("btnBuilder"), focus("btnAmend"))
    assertEquals(focus("sliderBuilder"), focus("sliderAmend"))
  }

  test("cell builders emit fh- classes, identical to the property form") {
    // The HA-grid_options-flavored layout builders (`columns`/`fullWidth`/
    // `cellClass`) append to the node-level `cell.classes`; the emitted JSON
    // must be byte-identical to assigning the `cell` property.
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |
        |x: hass.LightEntity = new { entity_id = "light.kitchen" }
        |
        |builder = c.entityCard(x).columns(3).cellClass("hero")
        |amend = (c.entityCard(x)) {
        |  cell = new c.Cell { classes { "fh-cols-3"; "hero" } }
        |}
        |full = c.entityCard(x).fullWidth()
        |custom = c.entityCard(x).cellClass("my-hero")
        |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val cur = result.toOption.get.value.hcursor
    assertEquals(cur.downField("builder").focus, cur.downField("amend").focus)
    def classes(k: String) =
      cur.downField(k).downField("cell").get[List[String]]("classes").toOption
    assertEquals(classes("builder"), Some(List("fh-cols-3", "hero")))
    assertEquals(classes("full"), Some(List("fh-cols-full")))
    assertEquals(classes("custom"), Some(List("my-hero")))
    // A node with no layout builders decodes with NO cell at all (the null
    // default is dropped from the wire JSON).
    val plain = probeComponent(
      """light: hass.LightEntity = new { entity_id = "light.kitchen" }
        |node = new c.EntityCard { entity = light }""".stripMargin
    )
    assertEquals(plain.cell, None)
  }

  test(
    "Grid group-centering: default emits no marker, centered(false) emits fh-start"
  ) {
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/components.pkl" as c
        |
        |base = (c.grid) {}
        |packed = c.grid.centered(false)
        |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val cur = result.toOption.get.value.hcursor
    def clazz(k: String) =
      cur.downField(k).downField("slots").get[String]("class").toOption
    // Centered is the default -> no `class` slot (the group-center CSS is the
    // grid's baseline); left-packing rides on the `fh-start` marker.
    assertEquals(clazz("base"), None)
    assertEquals(clazz("packed"), Some("fh-start"))
  }

  test("caseOf copies the render fn's cell onto the emitted Case") {
    val dyn = probeDynamic(
      """node = new c.DynamicGroup {
        |  query = c.stateIs("on")
        |  render = (e) -> c.entityCard(e).fullWidth()
        |}""".stripMargin
    )
    assertEquals(dyn.cases.size, 1)
    assertEquals(
      dyn.cases.head.cell.map(_.classes),
      Some(List("fh-cols-full"))
    )
    // The group's own cell (set as a property) rides on the Dynamic node.
    val sized = probeDynamic(
      """node = new c.DynamicGroup {
        |  query = c.stateIs("on")
        |  render = (e) -> c.entityCard(e)
        |  cell = new c.Cell { classes { "fh-cols-full" } }
        |}""".stripMargin
    )
    assertEquals(sized.cell.map(_.classes), Some(List("fh-cols-full")))
  }

  test("If builder and amend forms produce identical wire output") {
    // `.then(..)/.`else`(..)` builder calls amend the same hidden Listings the
    // amend form fills directly, and the derived inlineSurfaces re-derive
    // across chained calls (late binding) — so the two authoring forms must
    // emit byte-identical node JSON.
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/components.pkl" as c
        |
        |builder = c.iff(c.stateIs("on"))
        |  .then(c.title("a"))
        |  .then(c.title("b"))
        |  .`else`(c.title("q"))
        |
        |amend = (c.iff(c.stateIs("on"))) {
        |  `then` {
        |    c.title("a")
        |    c.title("b")
        |  }
        |  `else` {
        |    c.title("q")
        |  }
        |}
        |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val cur = result.toOption.get.value.hcursor
    val builder = cur.downField("builder").focus
    val amend = cur.downField("amend").focus
    assert(builder.isDefined && amend.isDefined, clue = cur.keys)
    assertEquals(builder, amend, clue = (builder, amend))
  }

  test("entityIs emits an entity_id property comparison") {
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |import "@fh-dashboard/components.pkl" as c
        |p = c.entityIs("light.kitchen")
        |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val p = result.toOption.get.value.hcursor.downField("p").as[Predicate]
    assertEquals(
      p,
      Right(
        Predicate.Cmp("entity_id", Op.Eq, Json.fromString("light.kitchen"))
      )
    )
  }

  test("exprOf threads an explicit entityId into the emitted slot") {
    val node = probeComponent(
      """light: hass.LightEntity = new { entity_id = "light.kitchen" }
        |power: hass.SensorEntity = new { entity_id = "sensor.power" }
        |
        |node = new c.EntityCard {
        |  entity = light
        |  value = c.exprOf(power, "$state")
        |}""".stripMargin
    )
    val value = node.slots("value")
    assertEquals(value.entityId, Some("sensor.power"))
    assertEquals(value.literal, None)
    assertEquals(value.transform, "$state")
    // A plain expr (no exprOf) still inherits the card's entity (no entityId).
    val plain = probeComponent(
      """light: hass.LightEntity = new { entity_id = "light.kitchen" }
        |node = new c.EntityCard { entity = light; value = c.expr("$state") }""".stripMargin
    )
    assertEquals(plain.slots("value").entityId, None)
  }

  test("Row cssClass emits a literal `class` slot") {
    val row = probeComponent(
      """node = new c.Row {
        |  cssClass = "tabbar"
        |  children { new c.SectionTitle { text = "x" } }
        |}""".stripMargin
    )
    assertEquals(row.card, "fhrow")
    assertEquals(row.slots("class").literal, Some("tabbar"))
    // Absent cssClass emits no `class` slot at all.
    val plain = probeComponent(
      """node = new c.Row { children { new c.SectionTitle { text = "x" } } }"""
    )
    assert(!plain.slots.contains("class"), clue = plain.slots)
  }

  test("Slider on a cover resolves the cover spec as string literals") {
    val slider = probeComponent(
      """cover: hass.GenericEntity = new { entity_id = "cover.blind"; domain = "cover" }
        |node = new c.Slider { entity = cover }""".stripMargin
    )
    assertEquals(slider.card, "slider")
    assertEquals(
      slider.slots("action").literal,
      Some("cover/set_cover_position")
    )
    assertEquals(slider.slots("key").literal, Some("position"))
    assertEquals(slider.slots("min").literal, Some("0"))
    assertEquals(slider.slots("max").literal, Some("100"))
    assertEquals(slider.slots("value").transform, "$attr.current_position")
  }

  test("dynamic Slider ($self) resolves config via runtime $lookup($domain)") {
    // A $self slider can't know its domain until a match, so action/key/min/max
    // and the live position fall back to the runtime $lookup over the
    // sliderSpec table (all three domains present, in insertion order).
    val dyn = probeDynamic(
      """node = new c.DynamicGroup {
        |  query = c.stateIs("on")
        |  render = (e) -> c.slider(e)
        |}""".stripMargin
    )
    assertEquals(dyn.cases.size, 1)
    val slots = dyn.cases.head.slots
    // entity_id is dropped (the renderer injects the matched entity per match).
    assert(!slots.contains("entity_id"), clue = slots.keySet)

    // action: the exact JSONata object literal over the whole sliderSpec table.
    val action = slots("action")
    assertEquals(action.literal, None)
    assertEquals(action.reactive, false)
    assertEquals(action.bypassUnavailable, false)
    assertEquals(
      action.transform,
      "$lookup({\"light\":\"light/turn_on\"," +
        "\"cover\":\"cover/set_cover_position\"," +
        "\"fan\":\"fan/set_percentage\"}, $domain)"
    )
    // key/min/max likewise resolve via $lookup($domain); min/max maps carry the
    // bare Int values (unquoted).
    assertEquals(
      slots("key").transform,
      "$lookup({\"light\":\"brightness\",\"cover\":\"position\"," +
        "\"fan\":\"percentage\"}, $domain)"
    )
    assertEquals(
      slots("min").transform,
      "$lookup({\"light\":1,\"cover\":0,\"fan\":0}, $domain)"
    )
    assertEquals(
      slots("max").transform,
      "$lookup({\"light\":255,\"cover\":100,\"fan\":100}, $domain)"
    )
    // The live position: read the domain's position attr off $attr.
    assertEquals(
      slots("value").transform,
      "$lookup($attr, $lookup({\"light\":\"brightness\"," +
        "\"cover\":\"current_position\",\"fan\":\"percentage\"}, $domain))"
    )
    assertEquals(slots("value").default, Some("0"))
    assertEquals(slots("value").bypassUnavailable, false)
  }

  test("a Slider on a non-slider domain (static sensor) fails the constraint") {
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |sensor: hass.GenericEntity = new { entity_id = "sensor.temp"; domain = "sensor" }
        |node = new c.Slider { entity = sensor }
        |""".stripMargin
    )
    assert(evalProj(tmp, "probe.pkl").isLeft)
  }

  test("floorView emits one section per area-with-lights (title + sliders)") {
    // A fake transformed dump: floor `over` with two areas — `stue` (two
    // lights) and `bad` (one sensor, no lights). floorView must emit ONE area
    // column (stue), skipping the light-less `bad`, each area column holding a
    // sectionTitle(area_name) + a slider per light.
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
    val fakeDump = io.circe.parser
      .parse("""
        {
          "areas": {
            "stue": { "area_id": "stue_area", "floor_id": "over", "area_name": "Stue" },
            "bad": { "area_id": "bad_area", "floor_id": "over", "area_name": "Bad" }
          },
          "floors": {
            "over": {
              "floor_id": "over",
              "floor_name": "Overetasje",
              "areas": {
                "stue": { "area_id": "stue_area", "floor_id": "over", "area_name": "Stue" },
                "bad": { "area_id": "bad_area", "floor_id": "over", "area_name": "Bad" }
              }
            }
          },
          "entities": {
            "light_stue_1": {
              "entity_id": "light.stue_1", "friendly_name": "Stue 1",
              "domain": "light", "area_id": "stue_area", "attributes": {}
            },
            "light_stue_2": {
              "entity_id": "light.stue_2", "friendly_name": "Stue 2",
              "domain": "light", "area_id": "stue_area", "attributes": {}
            },
            "sensor_bad_1": {
              "entity_id": "sensor.bad_1", "friendly_name": "Bad temp",
              "domain": "sensor", "area_id": "bad_area", "attributes": {}
            }
          }
        }
      """)
      .toOption
      .get
    writeDump(tmp, PklDump.render(fakeDump))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/components.pkl" as c
        |import "@fh-home/dump.pkl" as dump
        |
        |node = c.floorView(dump.over)
        |""".stripMargin
    )

    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val node = result.toOption.get.value.hcursor
      .downField("node")
      .as[LayoutNode]
      .toOption
      .get
      .asInstanceOf[LayoutNode.Component]

    // Outer container is a column; exactly one area column (bad is skipped).
    assertEquals(node.card, "fhcol")
    val areaCols = node.children.collect { case c: LayoutNode.Component => c }
    assertEquals(areaCols.map(_.card), List("fhcol"))

    // The area column: the area name, then a slider per light (key-sorted).
    val inner = areaCols.head.children.collect { case c: LayoutNode.Component =>
      c
    }
    assertEquals(inner.map(_.card), List("sectionTitle", "slider", "slider"))
    assertEquals(inner(0).slots("label").literal, Some("Stue"))
    assertEquals(inner(1).slots("entity_id").literal, Some("light.stue_1"))
    assertEquals(inner(2).slots("entity_id").literal, Some("light.stue_2"))
    // The sliders resolved the light spec at build time (string literals).
    assertEquals(inner(1).slots("action").literal, Some("light/turn_on"))
    assertEquals(inner(1).slots("min").literal, Some("1"))
    assertEquals(inner(1).slots("max").literal, Some("255"))
  }

  // ---------------------------------------------------------------------------
  // Wire-format snapshot tests.
  //
  // These byte-identity-check the evaluated `{cards, card, surfaces}` wire JSON
  // of the TEST-OWNED fixture entries (above) against checked-in resource files,
  // so authoring-layer / backend refactors are guarded by `sbt test` instead of
  // manual diffing. `theme` is STRIPPED before comparison: the fixtures set a
  // dummy theme and the theme's CSS is deliberately out of scope here (the
  // browser smoke plan covers design). The snapshot is the raw evaluated JSON
  // (BEFORE normalize/hoist/decode) minus `theme`, printed with the fixed
  // `spaces2SortKeys` printer so Pkl map-ordering can never make it
  // nondeterministic. No live HA — HouseFixture.transformedDump supplies the
  // entities.
  //
  // To regenerate after an intentional change: `FH_UPDATE_SNAPSHOTS=1 sbt
  // 'fh-datastar-view/testFull'` rewrites the resource files, then commit them.
  //
  // GOTCHA: the env var is read from the JVM the tests run IN — the persistent
  // sbt server. A server started (even once, long ago) from a shell exporting
  // FH_UPDATE_SNAPSHOTS=1 keeps it forever and silently REGENERATES on every
  // run instead of checking (the gate is off). Regenerate without poisoning
  // the server via the sys.props fallback instead:
  //   sbt 'eval sys.props.put("FH_UPDATE_SNAPSHOTS", "1")' \
  //       'fh-datastar-view/testFull' \
  //       'eval sys.props.remove("FH_UPDATE_SNAPSHOTS")'
  // ---------------------------------------------------------------------------

  /** Checked-in expected snapshots (repo-relative, mirroring `resourcesLib`).
    */
  private val snapshotDir =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "resources" / "snapshots"

  /** Evaluate a fixture entry through the pipeline and return the raw evaluated
    * wire JSON with `theme` stripped, printed with the fixed deterministic
    * printer — the authoring/composition contract, free of theme CSS.
    */
  private def fixtureWire(slug: String, entry: String): String =
    PklFixture
      .eval(slug, entry)
      .value
      .mapObject(_.remove("theme"))
      .spaces2SortKeys

  /** Compare `actual` against the checked-in snapshot `name.json`. With
    * `FH_UPDATE_SNAPSHOTS=1` it (re)writes the resource file and passes; else
    * it asserts byte identity, writing the actual output to a temp file and
    * pointing at the regenerate command on mismatch.
    */
  private def checkSnapshot(name: String, actual: String): Unit = {
    val file = snapshotDir / s"$name.json"
    val updating =
      sys.env.get("FH_UPDATE_SNAPSHOTS").contains("1") ||
        sys.props.get("FH_UPDATE_SNAPSHOTS").contains("1")
    if (updating) {
      os.makeDir.all(snapshotDir)
      os.write.over(file, actual)
    } else {
      val expected =
        if (os.exists(file)) os.read(file)
        else
          fail(
            s"missing snapshot $file — regenerate with " +
              "FH_UPDATE_SNAPSHOTS=1 sbt 'fh-datastar-view/testFull'"
          )
      if (expected != actual) {
        val actualFile = os.temp.dir() / s"$name.actual.json"
        os.write(actualFile, actual)
      }
      assertEquals(
        actual,
        expected,
        clue = s"wire-format snapshot for $name.json changed. If intended, " +
          "regenerate with FH_UPDATE_SNAPSHOTS=1 sbt 'fh-datastar-view/testFull' " +
          "(actual output also written to a temp *.actual.json next to the diff)."
      )
    }
  }

  test("fixture-features wire JSON matches the checked-in snapshot") {
    checkSnapshot(
      "fixture-features",
      fixtureWire("fixture-features", fixtureFeatures)
    )
  }

  test("fixture-surfaces wire JSON matches the checked-in snapshot") {
    checkSnapshot(
      "fixture-surfaces",
      fixtureWire("fixture-surfaces", fixtureSurfaces)
    )
  }
}
