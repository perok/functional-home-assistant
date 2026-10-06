package fh.view.build

import api.homeassistant.ServiceTarget
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  Op,
  Predicate,
  TapCall,
  Transform
}
import fh.view.testkit.DashboardBuilders.{asComponent, asSetNode}
import fh.view.testkit.{
  FixtureEntity,
  HouseFixture,
  PklFixture,
  PklWorkspace,
  SmokeDashboard
}
import io.circe.Json

class PklBuildSuite extends munit.FunSuite {

  /** Nested sets included. */
  private def sets(node: LayoutNode): List[LayoutNode.SetNode] =
    node match {
      case c: LayoutNode.Component => c.allChildren.flatMap(sets)
      case s: LayoutNode.SetNode   =>
        s :: s.members.values.toList
          .flatMap(_.clauses)
          .flatMap(cl => sets(cl.node))
    }

  /** A slider's row: the `sliderHead` node in its `head` region. */
  private def rowOf(node: LayoutNode.Component): LayoutNode.Component =
    node
      .regions("head")
      .collectFirst { case c: LayoutNode.Component => c }
      .getOrElse(fail(s"card '${node.card}' has no head node"))

  /** The `actions` region of a slider's head. */
  private def actionsOf(
      node: LayoutNode.Component
  ): List[LayoutNode.Component] =
    rowOf(node).regions.getOrElse("actions", Nil).collect {
      case c: LayoutNode.Component => c
    }

  private def cardNames(node: LayoutNode): List[String] =
    node match {
      case c: LayoutNode.Component =>
        c.card :: c.allChildren.flatMap(cardNames)
      case s: LayoutNode.SetNode =>
        s.members.values.toList
          .flatMap(_.clauses)
          .flatMap(cl => cardNames(cl.node))
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
    // An unrelated sibling that is never imported must stay out, unlike the
    // all-*.pkl superset fallback.
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

  test(
    "PklBuild.eval excludes cache-backed @fh-dashboard imports from the watch set"
  ) {
    // `@fh-dashboard` is a cache package (ADR 0010): its `hass.pkl` resolves to
    // a `package://` URI, not a workspace file, so the import set is the probe
    // alone. `unrelated.pkl` guards against the all-*.pkl superset fallback.
    val tmp = os.temp.dir()
    copyLib(tmp)
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

    assertEquals(imports, Set(tmp / "probe.pkl"), clue = imports)
    assert(!imports.contains(tmp / "unrelated.pkl"), clue = imports)
  }

  /** Bootstrap a package-form workspace at `tmp` and copy the library into
    * `tmp/lib/`, for relative `import "lib/<name>"` probes. Bootstrap seeds a
    * minimal dump so `@fh-home` resolves; [[writeDump]] overrides it.
    */
  private def copyLib(tmp: os.Path): Unit = {
    val _ = PklWorkspace.bootstrap(tmp)
    // The whole tree: a probe that copied only the files it names would fail on
    // whatever those import.
    os.copy(PklWorkspace.resourcesLib, tmp / "lib", replaceExisting = true)
  }

  /** Re-seed `@fh-home` from `source`. Its `import "@fh-dashboard/hass.pkl"`
    * then lands on the same `hass` identity `components.pkl` sees.
    */
  private def writeDump(tmp: os.Path, source: String): Unit =
    PklWorkspace.seedDump(tmp, source)

  private def evalProj(tmp: os.Path, entry: String) =
    SourceEval.eval(tmp, entry)

  /** A minimal published package over http, speaking the
    * `/system/pkl/packages/` protocol. Returns the port and a stop handle.
    */
  private def thirdPartyServer(): (Int, () => Unit) = {
    val zip = {
      val dir = os.temp.dir()
      os.write(dir / "mod.pkl", "greeting: String = \"from remote package\"\n")
      LibPackage.zipBytes(dir)
    }
    val metadata =
      s"""{"name":"thirdparty","packageUri":"package://fh.invalid/thirdparty@1.0.0",
         |"version":"1.0.0","packageZipUrl":"https://fh.invalid/thirdparty@1.0.0.zip",
         |"packageZipChecksums":{"sha256":"${LibPackage.sha256(
          zip
        )}"},"dependencies":{}}""".stripMargin
    val server = com.sun.net.httpserver.HttpServer
      .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      { ex =>
        val body: Array[Byte] = ex.getRequestURI.getPath match {
          case "/thirdparty@1.0.0.zip" => zip
          case "/thirdparty@1.0.0"     => metadata.getBytes
          case _                       => Array.emptyByteArray
        }
        if (body.isEmpty) ex.sendResponseHeaders(404, -1)
        else {
          ex.sendResponseHeaders(200, body.length.toLong)
          ex.getResponseBody.write(body)
        }
        ex.close()
      }
    )
    server.start()
    (server.getAddress.getPort, () => server.stop(0))
  }

  /** The staged workspace plus a remote dependency, mapped to the local server
    * by the manifest's own `evaluatorSettings.http.rewrites`, the documented
    * air-gap mechanism.
    */
  private def stageThirdParty(
      tmp: os.Path,
      port: Int,
      version: String
  ): Unit = {
    copyLib(tmp)
    writeThirdPartyManifest(tmp, port, version)
    os.write.over(
      tmp / "probe.pkl",
      """import "@thirdparty/mod.pkl" as m
        |msg: String = m.greeting
        |""".stripMargin
    )
  }

  private def writeThirdPartyManifest(
      tmp: os.Path,
      port: Int,
      version: String
  ): Unit =
    os.write.over(
      tmp / "PklProject",
      s"""amends ".fh/base.pkl"
         |evaluatorSettings {
         |  http {
         |    rewrites {
         |      ["https://fh.invalid/"] = "http://127.0.0.1:$port/"
         |    }
         |  }
         |  // This rewrite points somewhere base.pkl's instance-scoped entry
         |  // does not cover, so the origin has to be allowed too — exactly what
         |  // a user adding their own registry must do. Amending a Listing
         |  // APPENDS, so base.pkl's entries survive.
         |  allowedResources {
         |    "^http://127[.]0[.]0[.]1:$port/"
         |  }
         |}
         |dependencies {
         |  ["thirdparty"] { uri = "package://fh.invalid/thirdparty@$version" }
         |}
         |""".stripMargin
    )

  test(
    "a published third-party package resolves through the manifest's http.rewrites"
  ) {
    // The resolve path derives its HTTP client from the manifest's settings. A
    // hardcoded dummy died with "Dummy HTTP client cannot send request" on any
    // remote dep.
    val (port, stop) = thirdPartyServer()
    try {
      val tmp = os.temp.dir()
      stageThirdParty(tmp, port, "1.0.0")
      val result = evalProj(tmp, "probe.pkl")
      val msg = result.map(_.value.hcursor.get[String]("msg"))
      assertEquals(msg, Right(Right("from remote package")), clue = result)
    } finally stop()
  }

  test(
    "an unresolvable remote dep fails naming the package and keeps the lockfile"
  ) {
    val (port, stop) = thirdPartyServer()
    val tmp = os.temp.dir()
    stageThirdParty(tmp, port, "1.0.0")
    assert(evalProj(tmp, "probe.pkl").isRight)
    stop()
    val lockBefore = os.read(tmp / "PklProject.deps.json")

    // A version neither the warm cache nor the dead registry has: re-resolution
    // must fail loudly with pkl's error, and the previous lockfile must survive
    // (resolve-before-write).
    writeThirdPartyManifest(tmp, port, "2.0.0")
    val _ = os.mtime.set(tmp / "PklProject", System.currentTimeMillis() + 1000)
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isLeft, clue = result)
    assert(result.left.exists(_.contains("thirdparty")), clue = result)
    assertEquals(os.read(tmp / "PklProject.deps.json"), lockBefore)
  }

  test("hass.pkl types the dump's entity shapes with a generic fallback") {
    val tmp = os.temp.dir()
    copyLib(tmp)
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "lib/hass.pkl"
        |
        |// A capability whose values travel together is ONE nullable group on the
        |// domain class: null means unsupported, non-null means every field is
        |// there. Unmodelled attributes still land on the per-entity class.
        |class E_light_kitchen extends hass.LightEntity {
        |  icon: String = "mdi:bulb"
        |}
        |
        |light: E_light_kitchen = new {
        |  entity_id = "light.kitchen"
        |  friendly_name = "Kitchen"
        |  area_id = "kitchen"
        |  colourModes = new Listing { "color_temp" }
        |  colourTemp = new hass.ColourTemp { owner = light; min_kelvin = 2000; max_kelvin = 6535 }
        |  effects = new hass.Effects { list = new Listing { "colorloop" }; owner = light }
        |}
        |
        |// the group IS the predicate, and one guard yields every value in it
        |hasTemp = light.supportsColourTemp
        |kelvinSpan = light.colourTemp.max_kelvin - light.colourTemp.min_kelvin
        |effectNames = light.effects.list
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
    assertEquals(c.get[Boolean]("hasTemp").toOption, Some(true))
    assertEquals(c.get[Int]("kelvinSpan").toOption, Some(4535))
    assertEquals(
      c.get[List[String]]("effectNames").toOption,
      Some(List("colorloop"))
    )
    assertEquals(
      c.downField("tv").get[String]("domain").toOption,
      Some("media_player")
    )
    os.write.over(
      tmp / "probe.pkl",
      """module probe
        |import "lib/hass.pkl"
        |bad: hass.SensorEntity = new { entity_id = "NotAnId" }
        |""".stripMargin
    )
    assert(SourceEval.eval(tmp, "probe.pkl").isLeft)
  }

  /** `RegistryDump.transform`'s output shape. The friendly_name exercises
    * string escaping.
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
    // By alias, not a file sibling: the alias lands `hass` on the URI
    // `components.pkl` sees (ADR 0010, "Module identity").
    assert(src.contains("import \"@fh-dashboard/hass.pkl\""), clue = src)
    // A class per entity, extending the domain class (ADR 0013).
    assert(
      src.contains("class E_light_kitchen extends hass.LightEntity"),
      clue = src
    )
    assert(
      src.contains("const hidden e_light_kitchen: E_light_kitchen"),
      clue = src
    )
    assert(src.contains("class Area_kjokken extends hass.Area"), clue = src)
    assert(
      src.contains("class Floor_ground_floor extends hass.Floor"),
      clue = src
    )
    assert(
      src.contains("friendly_name = \"Kitchen \\\"main\\\" light\""),
      clue = src
    )
    // Narrowed to non-null on the class of the entity that has one (ADR 0013).
    assert(
      src.contains(
        "hidden effects: hass.Effects = new { owner = e_light_kitchen; list = new Listing { \"colorloop\" } }"
      ),
      clue = src
    )
    assert(
      src.contains("allWithHidden = List(light_kitchen, sensor_temp)"),
      clue = src
    )
    // `///` doc lines are skipped: their markdown backticks are not quoting.
    val code = src.linesIterator.filterNot(_.trim.startsWith("///"))
    assert(!code.exists(_.contains("`")), clue = src)
  }

  test("PklDump.render backticks reserved-word and digit-leading names") {
    // "New" slugs to the keyword `new`, "3rd floor" to the digit-leading
    // `3rd_floor`.
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
    // The `Area_`/`Floor_` prefix makes the class names legal, so only the bare
    // slug used as a property key is ticked.
    assert(src.contains("class Area_new extends hass.Area"), clue = src)
    assert(src.contains("class Floor_3rd_floor extends hass.Floor"), clue = src)
    assert(src.contains("`new`: Area_new = new {}"), clue = src)
    assert(src.contains("`3rd_floor`: Floor_3rd_floor = new {}"), clue = src)
    assert(src.contains("areas = List(`new`)"), clue = src)
    assert(
      src.contains("const hidden e_light_lamp: E_light_lamp"),
      clue = src
    )

    val tmp = os.temp.dir()
    copyLib(tmp)
    writeDump(tmp, src)
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-home/dump.pkl" as dump
        |
        |areaId = dump.areas.`new`.area_id
        |floorName = dump.floors.`3rd_floor`.floor_name
        |viaFloor = dump.floors.`3rd_floor`.`new`.light_lamp.entity_id
        |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    assertEquals(c.get[String]("areaId").toOption, Some("new_1"))
    assertEquals(c.get[String]("floorName").toOption, Some("3rd floor"))
    assertEquals(c.get[String]("viaFloor").toOption, Some("light.lamp"))
  }

  test("a tap declares exactly the call it makes, on any target (#389)") {
    val tmp = os.temp.dir()
    copyLib(tmp)
    writeDump(tmp, PklDump.render(fakeTransformedDump))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/components.pkl" as c
        |import "@fh-home/dump.pkl" as dump
        |
        |node = (c.column) {
        |  children {
        |    c.button("Off", c.tap.lights.off(dump.areas.kjokken))
        |    c.button("Floor", c.tap.call("switch/turn_off", dump.floors.ground_floor))
        |    c.button("Toggle", c.tap.toggle(dump.entities.light_kitchen))
        |    c.entityCard(dump.entities.light_kitchen)
        |      .tapAction(c.tap.call("light/turn_on", dump.entities.light_kitchen).with("effect", "colorloop"))
        |  }
        |}
        |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    val node =
      c.downField("node").as[LayoutNode].fold(e => fail(e.toString), identity)
    val dashboard = Dashboard(cards = Map.empty, card = node)
    assertEquals(
      dashboard.calls,
      Set(
        TapCall("light/turn_off", ServiceTarget.Area("kitchen_1"), None),
        TapCall("switch/turn_off", ServiceTarget.Floor("g"), None),
        TapCall("light/toggle", ServiceTarget.Entity("light.kitchen"), None),
        TapCall(
          "light/turn_on",
          ServiceTarget.Entity("light.kitchen"),
          Some("effect")
        )
      )
    )
  }

  test("generated dump.pkl evaluates against hass.pkl with dot-path access") {
    val tmp = os.temp.dir()
    copyLib(tmp)
    writeDump(tmp, PklDump.render(fakeTransformedDump))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-home/dump.pkl" as dump
        |
        |flat = dump.entities.light_kitchen.entity_id
        |viaFloor = dump.floors.ground_floor.kjokken.light_kitchen.entity_id
        |areaLightCount = hass.lights(dump.areas.kjokken.all).length
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

  test("every namespace answers `all`: floors by level, the rest by name") {
    val house = io.circe.parser
      .parse("""
        {
          "areas": {
            "all": { "area_id": "all_1", "area_name": "All" },
            "bad": { "area_id": "bad_1", "area_name": "Bad" }
          },
          "floors": {
            "loft": { "floor_id": "l", "floor_name": "Loft", "level": 2 },
            "shed": { "floor_id": "s", "floor_name": "Shed" },
            "cellar": { "floor_id": "c", "floor_name": "Cellar", "level": -1 },
            "ground": { "floor_id": "g", "floor_name": "Ground", "level": 0 }
          },
          "devices": { "hub": { "device_id": "d1", "device_name": "Hub" } },
          "users": { "peri": { "user_id": "u1", "user_name": "Peri" } },
          "entities": {}
        }
      """)
      .toOption
      .get
    val tmp = os.temp.dir()
    copyLib(tmp)
    writeDump(tmp, PklDump.render(house))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-home/dump.pkl" as dump
        |
        |floors = dump.floors.all.map((f) -> f.floor_id)
        |areas = dump.areas.all.map((a) -> a.area_id)
        |renamed = dump.areas.all_area.area_id
        |devices = dump.devices.all.map((d) -> d.device_id)
        |users = dump.users.all.map((u) -> u.user_id)
        |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    assertEquals(
      c.get[List[String]]("floors").toOption,
      Some(List("c", "g", "l", "s"))
    )
    assertEquals(
      c.get[List[String]]("areas").toOption,
      Some(List("all_1", "bad_1"))
    )
    assertEquals(c.get[String]("renamed").toOption, Some("all_1"))
    assertEquals(c.get[List[String]]("devices").toOption, Some(List("d1")))
    assertEquals(c.get[List[String]]("users").toOption, Some(List("u1")))
  }

  test("an entity hidden in HA is off every default list, and still named") {
    val stashed = io.circe.parser
      .parse("""
        { "entities": { "light_stashed": {
            "entity_id": "light.stashed", "domain": "light",
            "area_id": "kitchen_1", "floor_id": "g", "id_hidden": true,
            "attributes": {} } } }
      """)
      .toOption
      .get
    val tmp = os.temp.dir()
    copyLib(tmp)
    writeDump(tmp, PklDump.render(fakeTransformedDump.deepMerge(stashed)))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-home/dump.pkl" as dump
        |
        |house = dump.lights.map((e) -> e.entity_id)
        |room = hass.lights(dump.areas.kjokken.all).length
        |floor = hass.lights(dump.floors.ground_floor.all).length
        |withHidden = hass.lights(dump.allWithHidden).length
        |byName = dump.entities.light_stashed.entity_id
        |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    assertEquals(
      c.get[List[String]]("house").toOption,
      Some(List("light.kitchen"))
    )
    assertEquals(c.get[Int]("room").toOption, Some(1))
    assertEquals(c.get[Int]("floor").toOption, Some(1))
    assertEquals(c.get[Int]("withHidden").toOption, Some(2))
    assertEquals(c.get[String]("byName").toOption, Some("light.stashed"))
  }

  test("a modelled domain's house-wide list is derived, and partitions `all`") {
    // The generator writes only `all`; `dump.locks` comes from the base's
    // selector. A domain modelled in `hass.pkl` but left in `generic` reads as
    // "no locks in this house", silently.
    val tmp = os.temp.dir()
    copyLib(tmp)
    writeDump(
      tmp,
      PklDump.render(
        io.circe.parser
          .parse("""
            {
              "areas": {},
              "floors": {},
              "entities": {
                "lock_front": {
                  "entity_id": "lock.front", "domain": "lock",
                  "attributes": { "supported_features": 1 }
                },
                "media_player_tv": {
                  "entity_id": "media_player.tv", "domain": "media_player",
                  "attributes": {}
                }
              }
            }
          """)
          .toOption
          .get
      )
    )
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-home/dump.pkl" as dump
        |
        |lockIds = dump.locks.map((l) -> l.entity_id)
        |latch = dump.locks.first.supportsOpen
        |genericIds = dump.generic.map((e) -> e.entity_id)
        |allCount = dump.all.length
        |""".stripMargin
    )

    val result = SourceEval.eval(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    assertEquals(
      c.get[List[String]]("lockIds").toOption,
      Some(List("lock.front"))
    )
    assertEquals(c.get[Boolean]("latch").toOption, Some(true))
    assertEquals(
      c.get[List[String]]("genericIds").toOption,
      Some(List("media_player.tv"))
    )
    assertEquals(c.get[Int]("allCount").toOption, Some(2))
  }

  test(
    "theme-beer.pkl emits the {tokens, tokensDark, stylesheets, styles, chrome} shape"
  ) {
    // The probe re-exposes the theme so the assertions read a pinned shape,
    // independent of the lib module's other exports.
    val tmp = os.temp.dir()
    copyLib(tmp)
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
    // Deferred and nowhere in the blocking list, or a second render-blocking
    // `<link>` for the same URL undoes the deferral.
    assert(
      theme
        .get[List[String]]("deferredStylesheets")
        .toOption
        .exists(_.exists(_.contains("materialdesignicons"))),
      clue = result
    )
    assert(
      !theme
        .get[List[String]]("stylesheets")
        .toOption
        .exists(_.exists(_.contains("materialdesignicons"))),
      clue = result
    )
    // The text font stays blocking: swapping it mid-paint reflows every line
    // (see `interCdn`).
    assert(
      theme
        .get[List[String]]("stylesheets")
        .toOption
        .exists(_.exists(_.contains("inter"))),
      clue = result
    )
    // A theme's `styles` is the paint layer (ADR 0020), so it must carry the
    // palette, including the `--fh-*` re-pointing the cards' colours resolve
    // to.
    assert(
      theme.get[String]("styles").toOption.exists(_.contains("--fh-text-dim:")),
      clue = result
    )
    assert(
      !theme.get[String]("styles").toOption.exists(_.contains(".fh-row{")),
      clue = "the layout contract must not be back in the theme: " + result
    )
    assert(
      theme
        .get[List[String]]("inlineScripts")
        .toOption
        .exists(_.exists(_.contains("pointerdown"))),
      clue = result
    )
  }

  test("components.pkl derives the card registry from the card classes") {
    // `cards` comes from pkl:reflect over the module's Node subclasses, so the
    // key set must be exactly the card classes: no strays such as Tab, Case or
    // SliderSpec.
    val tmp = os.temp.dir()
    copyLib(tmp)
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
      "label" -> List("label", "tone"),
      // The base tile; only the HA layer adds a subject.
      "tile" -> List("label", "value"),
      "entityInfo" -> List("entity_id", "attributes"),
      // A declared slot is one every node of the card carries, so optional ones
      // (`href`/`onclick`, `icon`, `group`, `secondary`) are not listed.
      "button" -> List("label"),
      "pill" -> List("label"),
      "switch" -> List("label"),
      "tab" -> List("label", "onclick", "active"),
      "slider" -> Nil,
      // `label` is only the toggle variant's `aria-label`; the visible one is
      // `sliderText`'s (#151). No `entity_id`: a base slider may have no
      // subject, so only the HA one carries it.
      "sliderHead" -> List(
        "label",
        "value",
        "service",
        "targetKind",
        "targetId",
        "min",
        "max"
      ),
      "sliderText" -> List("label"),
      "popup" -> Nil,
      "tabs" -> Nil,
      "ifhost" -> Nil,
      "cardFeatures" -> Nil,
      "progressCard" -> List("label", "entity_id"),
      // The chart's entity rides in the query's params, so no `entity_id`.
      "historyChart" -> List("chart"),
      "historyWindows" -> Nil,
      "historyReadings" -> List("label", "readings", "unit", "entity_id")
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
      // `tab` is the one wrapAsCell opt-out (the `.tabs > a` selector); every
      // other card omits the key, which defaults to true.
      assertEquals(
        card.get[Option[Boolean]]("wrapAsCell").toOption.flatten,
        Option.when(name == "tab")(false),
        clue = name
      )
    }
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

  // Test-owned entries, not the shipped dashboards, which are free to evolve.
  // They set a dummy theme, so theme CSS is out of scope here.

  private val fixtureFeatures =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
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
       |      c.button("Close", c.tap.closePopup())
       |    }
       |  }
       |}
       |
       |card = (c.column) {
       |  children {
       |    c.title("Features")
       |    c.entityCard(dump.entities.sensor_outside_temp)
       |    c.entityCard(dump.entities.light_kitchen).tapAction(c.tap.call("homeassistant/toggle", dump.entities.light_kitchen))
       |    c.entityCard(dump.entities.light_kitchen) |> c.informative
       |    c.entitySlider(dump.entities.light_kitchen)
       |    q.from(dump.lights)
       |      .where(q.eq(q.stateProp, "on"))
       |      .render((e) -> c.entityCard(e))
       |      .build()
       |    c.button("Detail…", c.tap.openPopup("detail"))
       |    c.button("Inline…", c.tap.openPopupInline(new c.Column {
       |      children {
       |        c.title("Inline")
       |        c.button("Close", c.tap.closePopup())
       |      }
       |    }))
       |  }
       |}
       |""".stripMargin

  /** The two inline-surface hoist paths: tab panels and branches. */
  private val fixtureSurfaces =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
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
       |    c.iff(q.entity(dump.entities.light_kitchen).stateIs("on"))
       |      .then(c.entityCard(dump.entities.light_kitchen))
       |      .`else`(c.entityCard(dump.entities.sensor_outside_temp))
       |  }
       |}
       |""".stripMargin

  /** The shipped starter, against a house that populates its "Low battery"
    * section, which `HouseFixture` does not.
    *
    * The hoist never walked a set's clauses, so a sensor card's default inline
    * more-info popup kept its `@@NODE_ID@@`, and the server refused the starter
    * with "the build left placeholder tokens unresolved".
    */
  test("the starter's low-battery cards hoist their more-info popups") {
    val dump = HouseFixture.dumpWith(
      FixtureEntity(
        "sensor.remote_battery",
        "7",
        Map(
          "friendly_name" -> Json.fromString("Remote Battery"),
          "device_class" -> Json.fromString("battery"),
          "unit_of_measurement" -> Json.fromString("%")
        )
      )
    )
    // The starter is a site. Hoisting the site JSON finds no `card` and passes
    // vacuously.
    val built = PklFixture.eval("site", AddonBootstrap.starterSite, dump)
    val home = built.value.hcursor
      .downField("dashboards")
      .downField("home")
      .focus
      .getOrElse(fail("the starter names no dashboard 'home'"))
    val hoisted = DashboardBuild.hoistInlineSurfaces(home)
    assertEquals(
      DashboardBuild.unresolvedTokens(hoisted),
      Nil,
      clue = "a set clause's inline surface was not hoisted"
    )
    // Otherwise the assertion above is vacuous, which is how this went
    // unnoticed.
    assert(
      hoisted.noSpaces.contains("sensor.remote_battery"),
      clue =
        "the low-battery set selected no candidate; the test proves nothing"
    )
  }

  /** "Renders on any installation" is the starter's design property, so it is
    * checked against a dump it was not written for: `HouseFixture` has no
    * switches.
    */
  test("the bundled starter dashboard builds against an arbitrary house") {
    val d = PklFixture.buildSiteDashboard(
      "home",
      fh.view.build.AddonBootstrap.starterSite
    )
    assertEquals(d.validate(), Nil)
    // The layout names no concrete entity; the sections are queries over the
    // dump's lists. Only the code after `card =` is checked: the header
    // mentions `dump.entities.`.
    val layout = AddonBootstrap.starterSite.dropWhile(_ != '\n')
    assert(
      !layout.substring(layout.indexOf("card =")).contains("dump.entities")
    )

    val sets = {
      def walk(n: LayoutNode): List[LayoutNode.SetNode] = n match {
        case s: LayoutNode.SetNode   => List(s)
        case c: LayoutNode.Component => c.allChildren.flatMap(walk)
      }
      walk(d.card)
    }
    assertEquals(sets.length, 3, clue = sets)
    // The on light keeps both renderings because the guard is live.
    val lights = sets.head
    assertEquals(
      lights.candidates.sorted,
      List("light.kitchen", "light.living_room")
    )
    assertEquals(lights.members("light.kitchen").clauses.length, 2)
    // No candidates builds; it is not an error.
    assertEquals(sets(1).candidates, Nil)
    // `device_class` is registry data, so the temperature sensor is selected
    // out at build time.
    assertEquals(sets(2).candidates, Nil)
  }

  test(
    "fixture-features builds through the full pipeline into a valid Dashboard"
  ) {
    val built = PklFixture.eval("fixture-features", fixtureFeatures)
    val hoisted = DashboardBuild.hoistInlineSurfaces(built.value)
    assert(
      !hoisted.noSpaces.contains(DashboardBuild.NodeIdToken),
      clue = "unspliced NODE_ID token remained in the hoisted JSON"
    )
    val d = hoisted.as[Dashboard].fold(e => fail(s"decode: $e"), identity)

    assert(
      Set(
        "fhcol",
        "sectionTitle",
        "tile",
        "entityInfo",
        "slider",
        "button",
        "popup"
      )
        .subsetOf(d.cards.keySet),
      clue = d.cards.keySet
    )
    // The registered popup plus four hoisted inline surfaces
    // (`<node-id>_self`): the `openPopupInline` button, the `|> c.informative`
    // light, and two sensor cards, one nested in `detail`, that default to
    // more-info because `sensor` has no service (issue #106).
    assert(d.surfaces.contains("detail"), clue = d.surfaces.keySet)
    assertEquals(
      d.surfaces.keys.count(_.endsWith("_self")),
      4,
      clue = d.surfaces.keySet
    )
    // The fixture light reports no colour modes, so its controls are a tappable
    // card, not a slider. Picked by name: the sensors' popups hold an
    // entityInfo too.
    val moreInfo = d.surfaces.values
      .find(s => cardNames(s.content).count(_ == "tile") == 2)
      .getOrElse(fail("no more-info surface was hoisted"))
    assertEquals(
      cardNames(moreInfo.content),
      List(
        "popup",
        "fhcol",
        "tile",
        "fhcol",
        "tile",
        "entityInfo",
        "button"
      )
    )
    assertEquals(sets(d.card).size, 1, clue = d.card)
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

  private def probeComponent(body: String): LayoutNode.Component = {
    val tmp = os.temp.dir()
    copyLib(tmp)
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
      .asComponent
  }

  test("a clause node NAMES its candidate, and bakes its label") {
    // A clause knows its candidate: the id is a literal slot and the name is
    // baked, so neither costs a runtime read.
    val set = probeSet(
      """node = q.from(hass.lights(dump.areas.stue.all)).render((e) -> c.entityCard(e)).build()"""
    )
    val node = set
      .members("light.taklys")
      .clauses
      .head
      .node
      .asComponent
    assertEquals(node.slots("entity_id").literal, Some("light.taklys"))
    assertEquals(node.slots("label").literal, Some("Taklys"))
  }

  /** Over the real dump: the Pkl-to-model path `SetNodeSuite` picks up from. */
  private def probeSet(body: String): LayoutNode.SetNode = {
    val tmp = os.temp.dir()
    copyLib(tmp)
    writeDump(tmp, PklDump.render(setDump))
    os.write(
      tmp / "probe.pkl",
      s"""module probe
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-dashboard/query.pkl" as q
         |import "@fh-dashboard/hass.pkl"
         |import "@fh-home/dump.pkl" as dump
         |
         |$body
         |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    result.toOption.get.value.hcursor
      .downField("node")
      .as[LayoutNode]
      .toOption
      .get
      .asSetNode
  }

  // Two lights in `stue`, one in `bad`, plus a motion sensor to gate them on.
  private def setDump = io.circe.parser
    .parse("""
      {
        "areas": {
          "stue": { "area_id": "stue", "area_name": "Stue" },
          "bad": { "area_id": "bad", "area_name": "Bad" }
        },
        "entities": {
          "light_taklys": {
            "entity_id": "light.taklys", "friendly_name": "Taklys",
            "domain": "light", "area_id": "stue", "attributes": {}
          },
          "light_lampe": {
            "entity_id": "light.lampe", "friendly_name": "Lampe",
            "domain": "light", "area_id": "stue", "attributes": {}
          },
          "light_bad": {
            "entity_id": "light.bad", "friendly_name": "Bad",
            "domain": "light", "area_id": "bad", "attributes": {}
          },
          "sensor_motion": {
            "entity_id": "binary_sensor.motion", "friendly_name": "Motion",
            "domain": "binary_sensor", "area_id": "stue", "attributes": {}
          }
        }
      }
    """)
    .toOption
    .get

  test("a query over the real dump decodes as a set the renderer can consume") {
    // A registry condition selects and never reaches the wire; a live one
    // becomes the member's guard.
    val set = probeSet(
      """node = q.from(hass.lights(dump.areas.stue.all))
        |  .where(q.eq(q.stateProp, "on"))
        |  .render((e) -> c.entityCard(e))
        |  .build()""".stripMargin
    )
    assertEquals(set.candidates.sorted, List("light.lampe", "light.taklys"))
    val clause = set.members("light.taklys").clauses.head
    assertEquals(
      clause.when,
      Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on")))
    )
    val node = clause.node.asComponent
    assertEquals(node.subjectEntity, Some("light.taklys"))
    assertEquals(set.liveEntities.sorted, set.candidates.sorted)
  }

  test("a cross-entity guard rides as `entity`, and joins liveEntities") {
    // The sensor is not a candidate, so only the guard names it. `liveEntities`
    // must learn it from there or the members are never woken.
    val set = probeSet(
      """node = q.from(hass.lights(dump.areas.stue.all) + hass.lights(dump.areas.bad.all))
        |  .where(q.candidate((_e) -> q.entity(dump.areas.stue.sensor_motion).stateIs("on")))
        |  .render((e) -> c.entityCard(e))
        |  .build()""".stripMargin
    )
    val guard = set.members("light.taklys").clauses.head.when.get
    assertEquals(
      guard,
      Predicate.Cmp(
        "state",
        Op.Eq,
        Json.fromString("on"),
        Some("binary_sensor.motion")
      )
    )
    assert(
      set.liveEntities.contains("binary_sensor.motion"),
      clue = set.liveEntities
    )
    assert(
      !set.candidates.contains("binary_sensor.motion"),
      clue = set.candidates
    )
  }

  test("a set validates as a dashboard, clause nodes included") {
    val set = probeSet(
      """node = q.from(hass.lights(dump.areas.stue.all) + hass.lights(dump.areas.bad.all))
        |  .render((e) -> c.entityCard(e))
        |  .build()""".stripMargin
    )
    val cards = Map(
      "tile" -> CardDef(
        // `tile` marks `value` and `tapDisabled` as signal slots, and
        // `validate` rejects a card that declares one without placing its
        // binding — or, for a handler, its read (ADR 0017).
        "<b>{{label}}</b><i {{{value__bind}}}>{{value}}</i>" +
          "<u data-on:click=\"{{tapDisabled__signal}}\"></u>",
        slots = List("label", "value")
      )
    )
    assertEquals(Dashboard(cards = cards, card = set).validate(), Nil)
    val broken = set.copy(candidates = set.candidates :+ "light.ghost")
    assert(
      Dashboard(cards = cards, card = broken)
        .validate()
        .exists(
          _.contains("light.ghost")
        ),
      clue = Dashboard(cards = cards, card = broken).validate()
    )
  }

  test("call-style entityCard emits the same node JSON as the `new` form") {
    // Compared as raw node JSON, not just the decoded model.
    val tmp = os.temp.dir()
    copyLib(tmp)
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |
        |x: hass.LightEntity = new { entity_id = "light.kitchen" }
        |
        |call = (c.entityCard(x)) { tapAction = c.tap.call("homeassistant/toggle", x) }
        |ctor = new c.EntityCard { entity = x; tapAction = c.tap.call("homeassistant/toggle", x) }
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
    // Each method amends `this` and returns the same class, so late binding
    // re-derives `slots`.
    val tmp = os.temp.dir()
    copyLib(tmp)
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |
        |x: hass.LightEntity = new { entity_id = "light.kitchen" }
        |
        |cardBuilder = c.entityCard(x).tapAction(c.tap.call("homeassistant/toggle", x)).label("Office")
        |cardAmend = (c.entityCard(x)) { tapAction = c.tap.call("homeassistant/toggle", x); label = "Office" }
        |cardCtor = new c.EntityCard { entity = x; tapAction = c.tap.call("homeassistant/toggle", x); label = "Office" }
        |
        |btnBuilder = c.button("Close", c.tap.closePopup()).label("Dismiss")
        |btnAmend = new c.Button { label = "Dismiss"; tapAction = c.tap.closePopup() }
        |
        |sliderBuilder = c.entitySlider(x).label("Lamp").min(10).max(200)
        |sliderAmend = new c.EntitySlider { entity = x; label = "Lamp"; min = 10; max = 200 }
        |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val cur = result.toOption.get.value.hcursor
    def focus(k: String) = cur.downField(k).focus
    assertEquals(focus("cardBuilder"), focus("cardAmend"))
    assertEquals(focus("cardBuilder"), focus("cardCtor"))
    assertEquals(focus("btnBuilder"), focus("btnAmend"))
    assertEquals(focus("sliderBuilder"), focus("sliderAmend"))
  }

  test("cell builders emit fh- classes, identical to the property form") {
    val tmp = os.temp.dir()
    copyLib(tmp)
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
        |// One span per node: a later span REPLACES an earlier one (and a card's
        |// default), because both emit a flex-basis rule and which wins would
        |// otherwise be decided by stylesheet order, not the author's last word.
        |respan = c.entityCard(x).columns(3).fullWidth().cellClass("hero").columns(6)
        |// A `Tabs` DEFAULTS its span (a section, not a third of a grid row) and
        |// is still overridable per node — before the split `.columns(n)` here
        |// was accepted and then silently dropped, the wrapper being denied.
        |tabsDefault = (c.tabs) { tabs { ["A"] { c.entityCard(x) } } }
        |tabsSized = ((c.tabs) { tabs { ["A"] { c.entityCard(x) } } }).columns(6)
        |// `hug` is NOT a span (it is the fill/shrink question, not the how-wide
        |// one), so it survives a span and composes with it rather than being
        |// replaced. A Pill defaults to it, the same self-defaulted-cell move.
        |hugged = c.entityCard(x).hug()
        |pill = c.pill("Underetasje", c.tap.navigate("under"))
        |pillSized = c.pill("Underetasje", c.tap.navigate("under")).columns(6)
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
    assertEquals(classes("respan"), Some(List("hero", "fh-cols-6")))
    assertEquals(classes("tabsDefault"), Some(List("fh-cols-full")))
    assertEquals(classes("tabsSized"), Some(List("fh-cols-6")))
    assertEquals(classes("hugged"), Some(List("fh-hug")))
    assertEquals(classes("pill"), Some(List("fh-hug")))
    assertEquals(classes("pillSized"), Some(List("fh-hug", "fh-cols-6")))
    // The null default is dropped from the wire JSON.
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
    copyLib(tmp)
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
    // Centred is the grid's baseline, so it emits no `class`; left-packing
    // rides on `fh-start`.
    assertEquals(clazz("base"), None)
    assertEquals(clazz("packed"), Some("fh-start"))
  }

  test(
    "a render lambda's cell lands on the clause node, the set's on the set"
  ) {
    val set = probeSet(
      """node = q.from(hass.lights(dump.areas.stue.all))
        |  .render((e) -> c.entityCard(e).fullWidth())
        |  .build()""".stripMargin
    )
    val node = set
      .members("light.taklys")
      .clauses
      .head
      .node
      .asComponent
    assertEquals(node.cell.map(_.classes), Some(List("fh-cols-full")))
    val sized = probeSet(
      """node = (q.from(hass.lights(dump.areas.stue.all))
        |  .render((e) -> c.entityCard(e))
        |  .build()).fullWidth()""".stripMargin
    )
    assertEquals(sized.cell.map(_.classes), Some(List("fh-cols-full")))
    assertEquals(
      sized
        .members("light.taklys")
        .clauses
        .head
        .node
        .asComponent
        .cell,
      None
    )
  }

  test("If builder and amend forms produce identical wire output") {
    // `inlineSurfaces` re-derives across chained calls (late binding).
    val tmp = os.temp.dir()
    copyLib(tmp)
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/components.pkl" as c
        |import "@fh-dashboard/query.pkl" as q
        |import "@fh-dashboard/hass.pkl"
        |
        |local lamp: hass.LightEntity = new { entity_id = "light.kitchen" }
        |
        |builder = c.iff(q.entity(lamp).stateIs("on"))
        |  .then(c.title("a"))
        |  .then(c.title("b"))
        |  .`else`(c.title("q"))
        |
        |amend = (c.iff(q.entity(lamp).stateIs("on"))) {
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

  test("q.entity names the entity on the term, not as a property test") {
    val tmp = os.temp.dir()
    copyLib(tmp)
    os.write(
      tmp / "probe.pkl",
      """module probe
        |import "@fh-dashboard/components.pkl" as c
        |import "@fh-dashboard/query.pkl" as q
        |import "@fh-dashboard/hass.pkl"
        |local lamp: hass.LightEntity = new { entity_id = "light.kitchen" }
        |p = q.entity(lamp).stateIs("on")
        |""".stripMargin
    )
    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val p = result.toOption.get.value.hcursor.downField("p").as[Predicate]
    // Naming the entity makes the predicate one lookup, and the reverse index
    // exact.
    assertEquals(
      p,
      Right(
        Predicate.Cmp(
          "state",
          Op.Eq,
          Json.fromString("on"),
          entity = Some("light.kitchen")
        )
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
        |  value = c.exprOf(power, "state")
        |}""".stripMargin
    )
    val value = node.slots("value")
    assertEquals(value.entityId, Some("sensor.power"))
    assertEquals(value.literal, None)
    assertEquals(value.valueKey, "state")
    val plain = probeComponent(
      """light: hass.LightEntity = new { entity_id = "light.kitchen" }
        |node = new c.EntityCard { entity = light; value = c.expr("state") }""".stripMargin
    )
    assertEquals(plain.slots("value").entityId, None)
  }

  test("a navigating button is an anchor: `href`, and no onclick at all") {
    val nav =
      probeComponent("""node = c.button("Home", c.tap.navigate("other"))""")
    // Relative, so it resolves against `<base href>` (ingress-safe).
    assertEquals(nav.slots("href").literal, Some("d/other"))
    assert(!nav.slots.contains("onclick"), clue = nav.slots)
    val toggle = probeComponent(
      """light: hass.LightEntity = new { entity_id = "light.kitchen" }
        |node = c.button("Toggle", c.tap.call("homeassistant/toggle", light))""".stripMargin
    )
    assert(!toggle.slots.contains("href"), clue = toggle.slots)
    // The template assembles the URL around the service (ADR 0017), so no slot
    // carries `@post`.
    assertEquals(
      toggle.slots("service").literal,
      Some("homeassistant/toggle")
    )
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
    val plain = probeComponent(
      """node = new c.Row { children { new c.SectionTitle { text = "x" } } }"""
    )
    assert(!plain.slots.contains("class"), clue = plain.slots)
  }

  test("Slider on a cover resolves the cover spec as string literals") {
    val slider = probeComponent(
      """cover: hass.GenericEntity = new { entity_id = "cover.blind"; domain = "cover" }
        |node = new c.EntitySlider { entity = cover }""".stripMargin
    )
    assertEquals(slider.card, "slider")
    assertEquals(
      rowOf(slider).slots("service").literal,
      Some("cover/set_cover_position")
    )
    assertEquals(rowOf(slider).slots("dataKey").literal, Some("position"))
    assertEquals(rowOf(slider).slots("min").literal, Some("0"))
    assertEquals(rowOf(slider).slots("max").literal, Some("100"))
    // Opted in: the guarded read as structure, presence implicit (ADR 0028).
    rowOf(slider).slots("value").transform match {
      case Transform.Simple.Attr("current_position") => ()
      case other => fail(s"expected the opted-in guarded read, got $other")
    }
  }

  test("a slider in a QUERY bakes its config, with no $lookup($domain)") {
    // A candidate is a known entity, so `action`/`key`/`min`/`max` are
    // literals, not runtime `$lookup`s over the sliderSpec table.
    val set = probeSet(
      """node = q.from(hass.lights(dump.areas.stue.all)).render((e) -> c.entitySlider(e)).build()"""
    )
    val slots = rowOf(
      set
        .members("light.taklys")
        .clauses
        .head
        .node
        .asComponent
    ).slots
    assertEquals(slots("entity_id").literal, Some("light.taklys"))
    assertEquals(slots("service").literal, Some("light/turn_on"))
    assertEquals(slots("dataKey").literal, Some("brightness"))
    assertEquals(slots("min").literal, Some("1"))
    assertEquals(slots("max").literal, Some("255"))
    // The position stays live, since it reads state, but names the attribute
    // directly.
    slots("value").transform match {
      case Transform.Simple.Attr("brightness") => ()
      case other => fail(s"expected the opted-in guarded read, got $other")
    }
    assertEquals(slots("value").default, Some("0"))
    assertEquals(slots("value").bypassUnavailable, false)
    assert(
      !slots.values.exists(_.valueKey.contains("$lookup")),
      clue = slots.view.mapValues(_.transform).toMap
    )
  }

  test("a slider with children is the same card, holding ordinary nodes") {
    val group = probeComponent(
      """light: hass.GenericEntity = new { entity_id = "light.lys"; domain = "light" }
        |a: hass.GenericEntity = new { entity_id = "light.a"; domain = "light" }
        |cover: hass.GenericEntity = new { entity_id = "cover.blind"; domain = "cover" }
        |node = (c.entitySlider(light).withSubSliders(List(a, cover).map((m) -> c.entitySlider(m).readout("percent")))) { icon = "mdi:lightbulb-group"; tapAction = c.tap.call("homeassistant/toggle", light) }
        |""".stripMargin
    )
    assertEquals(group.card, "slider")
    assertEquals(group.slots("group").literal, Some("slider-group"))
    // A head does not repeat a readout its rows carry.
    assert(!rowOf(group).slots.contains("state"), clue = group.slots.keySet)
    assertEquals(rowOf(group).slots("entity_id").literal, Some("light.lys"))
    assertEquals(rowOf(group).slots("service").literal, Some("light/turn_on"))
    assertEquals(
      rowOf(group).slots("icon").literal,
      Some("mdi-lightbulb-group")
    )
    // `tapAction` is shorthand for the head's `actions` region (#151): the
    // press gets a node, and so a busy signal, of its own.
    val actions = actionsOf(group)
    // The shared icon button: a glyph, no label and the `lit` tint are not
    // about sliders.
    assertEquals(actions.map(_.card), List("button"))
    assertEquals(
      actions.head.slots("service").literal,
      Some("homeassistant/toggle")
    )
    assertEquals(actions.head.slots("targetId").literal, Some("light.lys"))
    assertEquals(actions.head.slots("glyph").literal, Some("mdi-power"))
    assertEquals(actions.head.slots("round").literal, Some("1"))

    // `allChildren` would include the head.
    val members =
      group.regions("children").collect { case c: LayoutNode.Component => c }
    assertEquals(members.map(_.card), List("slider", "slider"))
    assertEquals(
      members.map(rowOf(_).slots("entity_id").literal),
      List(Some("light.a"), Some("cover.blind"))
    )
    assertEquals(
      members.map(rowOf(_).slots("dataKey").literal),
      List(Some("brightness"), Some("position"))
    )
    // It reads out its level, off its own range, not its state.
    rowOf(members.head).slots("state").transform match {
      case Transform.Simple.Percent("brightness", 1.0, 255.0) => ()
      case other => fail(s"expected the opted-in percent, got $other")
    }
    rowOf(members(1)).slots("state").transform match {
      case Transform.Simple.Percent("current_position", 0.0, 100.0) => ()
      case other => fail(s"expected the opted-in percent, got $other")
    }
    // That reading is the position, so a drag moves it locally. The head reads
    // out nothing, so its `data-on:input` paints the fill alone.
    assertEquals(rowOf(members.head).slots("dragPercent").literal, Some("1"))
    assert(
      !rowOf(group).slots.contains("dragPercent"),
      clue = group.slots.keySet
    )

    val plain = probeComponent(
      """light: hass.GenericEntity = new { entity_id = "light.lys"; domain = "light" }
        |node = c.entitySlider(light)
        |""".stripMargin
    )
    assertEquals(
      rowOf(plain).slots.keySet -- Set("entity_id", "label", "state"),
      Set(
        "value",
        "fill",
        "fillColor",
        "service",
        "targetKind",
        "targetId",
        "dataKey",
        "tapDisabled",
        "min",
        "max",
        "icon",
        "busyVisual"
      ),
      clue = rowOf(plain).slots.keySet
    )
    assertEquals(plain.slots.keySet, Set.empty[String])
    assertEquals(rowOf(plain).slots("state").valueKey, "state")
    // The light domain's default: the probe entity declares no icon.
    assertEquals(rowOf(plain).slots("icon").literal, Some("mdi-lightbulb"))
    val bare = probeComponent(
      """light: hass.GenericEntity = new { entity_id = "light.lys"; domain = "light" }
        |node = c.entitySlider(light).icon(null)
        |""".stripMargin
    )
    assert(!rowOf(bare).slots.contains("icon"), clue = bare.slots.keySet)
  }

  test("a slider's readout takes an expression, not just the two names") {
    // `percentExpr` and friends are the card's hidden properties, spliceable
    // instead of re-derived.
    val own = probeComponent(
      """light: hass.GenericEntity = new { entity_id = "light.lys"; domain = "light" }
        |node = (c.entitySlider(light)) { readout = c.expr("\(percentExpr) + ' · ' + state") }
        |""".stripMargin
    )
    val state = rowOf(own).slots("state")
    assert(
      state.valueKey.contains("attr[?'brightness']"),
      clue = state.valueKey
    )
    assert(
      state.valueKey.endsWith("""+ ' · ' + state"""),
      clue = state.valueKey
    )
    val other = probeComponent(
      """light: hass.GenericEntity = new { entity_id = "light.lys"; domain = "light" }
        |power: hass.GenericEntity = new { entity_id = "sensor.w"; domain = "sensor" }
        |node = (c.entitySlider(light)).readout(c.exprOf(power, #"state + ' W'"#))
        |""".stripMargin
    )
    assertEquals(rowOf(other).slots("state").entityId, Some("sensor.w"))
    assertEquals(rowOf(other).slots("state").valueKey, """state + ' W'""")
    assertEquals(rowOf(other).slots("entity_id").literal, Some("light.lys"))
  }

  test("a Slider on a non-slider domain (static sensor) fails the constraint") {
    val tmp = os.temp.dir()
    copyLib(tmp)
    os.write(
      tmp / "probe.pkl",
      """module probe
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |sensor: hass.GenericEntity = new { entity_id = "sensor.temp"; domain = "sensor" }
        |node = new c.EntitySlider { entity = sensor }
        |""".stripMargin
    )
    assert(evalProj(tmp, "probe.pkl").isLeft)
  }

  /** Whether Pkl accepts a probe defining its own card class. The rule is the
    * leaf/structure split's guarantee (ADR 0012), enforced in the authoring
    * layer rather than by `Dashboard.validate`. Imports `core/` because a card
    * author writes against it (ADR 0015).
    */
  private def cardShapeAccepted(body: String): Boolean = {
    val tmp = os.temp.dir()
    copyLib(tmp)
    os.write(
      tmp / "probe.pkl",
      s"""module probe
         |import "@fh-dashboard/hass.pkl"
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-dashboard/core/node.pkl" as nodes
         |import "@fh-dashboard/core/slot.pkl" as slotMod
         |$body
         |""".stripMargin
    )
    evalProj(tmp, "probe.pkl").isRight
  }

  private def structuralCard(slot: String): String =
    s"""class Probe extends nodes.Node {
       |  card = "probe"
       |  cardDef = new nodes.CardDef {
       |    regions = new Mapping { ["children"] = new nodes.Region {} }
       |    template = #"<div>{{temp}}{{#children}}{{{html}}}{{/children}}</div>"#
       |    slots { "temp" }
       |  }
       |  slots { ["temp"] = $slot }
       |}
       |node = new Probe {}""".stripMargin

  /** A live bytes slot on structure is a build error: its patch would carry
    * everything the card holds.
    *
    * The positives vary only the slot against the same card, so a probe that
    * stops evaluating for an unrelated reason, such as a renamed class, fails
    * them too instead of passing the negative.
    */
  test("Pkl rejects a live BYTES slot on a card that holds regions") {
    assert(
      !cardShapeAccepted(
        structuralCard("""new slotMod.Slot { entityId = "sensor.t" }""")
      ),
      "a live slot on structure must be rejected"
    )
    // A literal is fine: the rule is about the value. `Grid` is this shape.
    assert(
      cardShapeAccepted(structuralCard(""""hello"""")),
      "a literal slot on structure is fine"
    )
    // So is a signal slot: its value never becomes bytes in this element. It is
    // seeded on the wrapper and updated by its own frame (ADR 0017).
    assert(
      cardShapeAccepted(
        structuralCard(
          """new slotMod.Slot { entityId = "sensor.t"; signal = "text" }"""
        )
      ),
      "a signal slot on structure is fine"
    )
    // And a live slot read without tracking: `onRender`/`once` never put the
    // card in a diff set.
    assert(
      cardShapeAccepted(
        structuralCard(
          """new slotMod.Slot { entityId = "sensor.t"; reads = "once" }"""
        )
      ),
      "a non-live read on structure is fine"
    )
  }

  test("Pkl accepts Grid unchanged — a LITERAL slot on a bare container") {
    // `Grid` holds its children and a `class` slot on the same element, but the
    // value is a plain String that never varies with state. Banning the shape
    // by type would reject the library's most basic cards.
    assert(
      cardShapeAccepted(
        """node = (c.grid.cssClass("hero")) { children { c.title("x") } }"""
      )
    )
  }

  test("floorView emits one section per area-with-lights (title + sliders)") {
    // Floor `over`: `stue` with two lights, `bad` with one sensor and no
    // lights, which floorView must skip.
    val tmp = os.temp.dir()
    copyLib(tmp)
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
        |node = c.recipes.floorView(dump.floors.over)
        |""".stripMargin
    )

    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val node = result.toOption.get.value.hcursor
      .downField("node")
      .as[LayoutNode]
      .toOption
      .get
      .asComponent

    assertEquals(node.card, "fhcol")
    val areaCols = node.allChildren.collect { case c: LayoutNode.Component =>
      c
    }
    assertEquals(areaCols.map(_.card), List("fhcol"))

    val inner = areaCols.head.allChildren.collect {
      case c: LayoutNode.Component =>
        c
    }
    assertEquals(inner.map(_.card), List("sectionTitle", "slider", "slider"))
    assertEquals(inner(0).slots("label").literal, Some("Stue"))
    assertEquals(
      rowOf(inner(1)).slots("entity_id").literal,
      Some("light.stue_1")
    )
    assertEquals(
      rowOf(inner(2)).slots("entity_id").literal,
      Some("light.stue_2")
    )
    assertEquals(
      rowOf(inner(1)).slots("service").literal,
      Some("light/turn_on")
    )
    assertEquals(rowOf(inner(1)).slots("min").literal, Some("1"))
    assertEquals(rowOf(inner(1)).slots("max").literal, Some("255"))
  }

  // Wire snapshots: the fixture entries' evaluated `{cards, card, surfaces}`
  // JSON, before normalize/hoist/decode, minus `theme`, with sorted keys.
  // Regenerate with `sbt dashboardSnapshotsUpdate` and review the diff.
  //
  // The gate is read in the persistent sbt server's JVM. Anything that leaves
  // FH_UPDATE_SNAPSHOTS set there (a shell export, a `; put ; test ; remove`
  // chain whose `remove` is skipped when the test fails) keeps it regenerating,
  // and every later run passes while rewriting files.

  private val snapshotDir =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "resources" / "snapshots"

  private def fixtureWire(slug: String, entry: String): String =
    PklFixture
      .eval(slug, entry)
      .value
      .mapObject { o =>
        val bare = o.remove("theme").remove("css")
        o("cards").fold(bare)(cards => bare.add("cards", stripCardCss(cards)))
      }
      .spaces2SortKeys

  /** Stripped like `theme`: CSS arrives in three places (ADR 0020), and every
    * rule tweak would churn the snapshot. The CSS layers are asserted in
    * `src/test/pkl`.
    */
  private def stripCardCss(cards: Json): Json =
    cards.asObject.fold(cards)(o =>
      Json.fromJsonObject(o.mapValues(_.mapObject(_.remove("css"))))
    )

  /** With `FH_UPDATE_SNAPSHOTS=1` it rewrites `name.json` and passes; otherwise
    * it asserts byte identity and writes the actual output to a temp file.
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
              "sbt dashboardSnapshotsUpdate"
          )
      if (expected != actual) {
        val actualFile = os.temp.dir() / s"$name.actual.json"
        os.write(actualFile, actual)
      }
      assertEquals(
        actual,
        expected,
        clue = s"wire-format snapshot for $name.json changed. If intended, " +
          "regenerate with 'sbt dashboardSnapshotsUpdate' " +
          "(actual output also written to a temp *.actual.json next to the diff)."
      )
    }
  }

  // A capability is a nullable group (ADR 0013), so a control takes the group
  // as its parameter and the site's `!= null` guard is the same fact.

  private def lightProbe(caps: String, node: String): String =
    s"""class E_light_a extends hass.LightEntity {}
       |l: E_light_a = new {
       |  entity_id = "light.a"
       |  friendly_name = "A"
       |$caps
       |}
       |node = $node""".stripMargin

  test("a colourTemp axis retunes the slider onto the light's own range") {
    val s = probeComponent(
      lightProbe(
        """  colourModes = new Listing { "color_temp" }
          |  colourTemp = new hass.ColourTemp { owner = l; min_kelvin = 2000; max_kelvin = 6535 }""".stripMargin,
        "c.entitySlider(l.colourTemp!!)"
      )
    )
    assertEquals(s.card, "slider")
    assertEquals(rowOf(s).slots("service").literal, Some("light/turn_on"))
    assertEquals(rowOf(s).slots("dataKey").literal, Some("color_temp_kelvin"))
    // The light's bounds, not the domain's brightness 1..255.
    assertEquals(rowOf(s).slots("min").literal, Some("2000"))
    assertEquals(rowOf(s).slots("max").literal, Some("6535"))
    // The handle tracks the value it writes, not brightness.
    rowOf(s).slots("value").transform match {
      case Transform.Simple.Attr("color_temp_kelvin") => ()
      case other => fail(s"expected the opted-in guarded read, got $other")
    }
  }

  test("lightControls emits one control per capability the light has") {
    val col = probeComponent(
      lightProbe(
        """  colourModes = new Listing { "color_temp"; "xy" }
          |  colourTemp = new hass.ColourTemp { owner = l; min_kelvin = 2000; max_kelvin = 6535 }
          |  effects = new hass.Effects { owner = l; list = new Listing { "off"; "Color loop" } }""".stripMargin,
        "c.light.controls(l)"
      )
    )
    assertEquals(col.card, "fhcol")
    val kids = col.allChildren.collect { case c: LayoutNode.Component => c }
    assertEquals(kids.map(_.card), List("slider", "slider", "fhrow"))
    assertEquals(rowOf(kids(0)).slots("dataKey").literal, Some("brightness"))
    assertEquals(
      rowOf(kids(1)).slots("dataKey").literal,
      Some("color_temp_kelvin")
    )
    val pills = kids(2).allChildren.collect { case c: LayoutNode.Component =>
      c
    }
    assertEquals(
      pills.map(_.slots("label").literal),
      List(Some("off"), Some("Color loop"))
    )
    // The value is a literal slot the template puts in the route's trailing
    // `/<key>/<value>`; the space is percent-encoded.
    assertEquals(pills(1).slots("service").literal, Some("light/turn_on"))
    assertEquals(pills(1).slots("dataKey").literal, Some("effect"))
    assertEquals(pills(1).slots("dataValue").literal, Some("Color%20loop"))
    // Exact bytes: the transform string ships and hashes as written, so its
    // shape is a contract. The colour-temperature span is the light's own range
    // baked (4535.0), not a constant.
    assertEquals(
      rowOf(kids(0)).slots("fillColor").valueKey,
      """attr[?'rgb_color'].optMap(rgb,
        |  size(rgb) == 3
        |    ? 'rgb(' + str(rgb[0]) + ',' + str(rgb[1]) + ',' + str(rgb[2]) + ')'
        |    : '').orValue('')""".stripMargin
    )
    assertEquals(
      rowOf(kids(1)).slots("fillColor").valueKey,
      """attr[?'color_temp_kelvin'].optMap(k,
        |  cel.bind(t,
        |    (double(k) - 2000.0) < 0.0 ? 0.0 :
        |    ((double(k) - 2000.0) > 4535.0
        |      ? 1.0 : (double(k) - 2000.0) / 4535.0),
        |    'rgb(' + str(math.round(255.0 - 54.0 * t))
        |    + ',' + str(math.round(166.0 + 60.0 * t))
        |    + ',' + str(math.round(87.0 + 168.0 * t)) + ')')).orValue('')""".stripMargin
    )
  }

  test("a switch-only light gets a tappable card and NO sliders") {
    val col = probeComponent(
      lightProbe(
        """  colourModes = new Listing { "onoff" }""",
        "c.light.controls(l)"
      )
    )
    val kids = col.allChildren.collect { case c: LayoutNode.Component => c }
    assertEquals(kids.map(_.card), List("tile"))
    assertEquals(kids.head.slots("tappable").literal, Some("1"))
  }

  test("a generated entity reaches THROUGH its group with no null-proof") {
    // A dashboard naming the entity passes the group to something needing it
    // non-null, with no `!!`; the same value read off a
    // `List<hass.LightEntity>` must still be guarded.
    val tmp = os.temp.dir()
    copyLib(tmp)
    val fakeDump = io.circe.parser
      .parse("""
        {
          "areas": {}, "floors": {},
          "entities": {
            "light_a": {
              "entity_id": "light.a", "friendly_name": "A", "domain": "light",
              "attributes": {
                "supported_color_modes": ["color_temp"],
                "min_color_temp_kelvin": 2000, "max_color_temp_kelvin": 6535
              }
            },
            "light_plug": {
              "entity_id": "light.plug", "friendly_name": "Plug",
              "domain": "light",
              "attributes": { "supported_color_modes": ["onoff"] }
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
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-home/dump.pkl" as dump
        |
        |// the specific entity: no `!!` anywhere on this line
        |node = c.entitySlider(dump.entities.light_a.colourTemp)
        |
        |// ...and the SAME value seen generically is still nullable
        |lights: List<hass.LightEntity> = List(dump.entities.light_a, dump.entities.light_plug)
        |guarded = lights.map((l) -> l.colourTemp?.min_kelvin ?? -1)
        |""".stripMargin
    )

    val result = evalProj(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    assertEquals(c.get[List[Int]]("guarded").toOption, Some(List(2000, -1)))
    val node = c
      .downField("node")
      .as[LayoutNode]
      .toOption
      .get
      .asComponent
    assertEquals(rowOf(node).slots("min").literal, Some("2000"))
    assertEquals(rowOf(node).slots("max").literal, Some("6535"))
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

  // The smoke suites need a browser, and they were the only thing validating a
  // progress card with a bar: the shipped card placed its signals by hand and
  // every dashboard using `.total` was rejected at load.
  test("every smoke dashboard validates") {
    List(
      SmokeDashboard.dashboard,
      SmokeDashboard.appliance,
      SmokeDashboard.percentSlider,
      SmokeDashboard.busyIcon,
      SmokeDashboard.longLabelRows,
      SmokeDashboard.switchSlider
    ).foreach { d =>
      d.validated().left.foreach(e => fail(s"${d.slug}: ${e.mkString("; ")}"))
    }
  }
}
