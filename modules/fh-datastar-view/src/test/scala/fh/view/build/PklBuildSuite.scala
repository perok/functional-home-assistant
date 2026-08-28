package fh.view.build

import fh.view.model.{CardDef, Dashboard, LayoutNode, Op, Predicate}
import fh.view.testkit.{FixtureEntity, HouseFixture, PklFixture, PklWorkspace}
import io.circe.Json

class PklBuildSuite extends munit.FunSuite {

  /** Collect every candidate set reachable from a node, nested ones included.
    */
  private def sets(node: LayoutNode): List[LayoutNode.SetNode] =
    node match {
      case c: LayoutNode.Component => c.allChildren.flatMap(sets)
      case s: LayoutNode.SetNode   =>
        s :: s.members.values.toList
          .flatMap(_.clauses)
          .flatMap(cl => sets(cl.node))
    }

  /** A slider's ROW — the `sliderHead` node in its `head` region.
    *
    * The slider is structure now: it holds a head and its members in two
    * regions, and every value the row shows lives on the head. So the
    * assertions that used to read `slider.slots(…)` read this instead, and what
    * stays on the slider itself is the one thing the OUTER markup takes from
    * having members (the `group` modifier).
    */
  private def rowOf(node: LayoutNode.Component): LayoutNode.Component =
    node
      .regions("head")
      .collectFirst { case c: LayoutNode.Component => c }
      .getOrElse(fail(s"card '${node.card}' has no head node"))

  /** A slider's head buttons, in order — the `actions` region of its head. */
  private def actionsOf(
      node: LayoutNode.Component
  ): List[LayoutNode.Component] =
    rowOf(node).regions.getOrElse("actions", Nil).collect {
      case c: LayoutNode.Component => c
    }

  /** Every card name reachable from a node, in document order. */
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

  test(
    "PklBuild.eval excludes cache-backed @fh-dashboard imports from the watch set"
  ) {
    // Under the single package-form resolution mode (ADR 0010), `@fh-dashboard`
    // is a REMOTE cache package, so its `hass.pkl` resolves to a `package://…`
    // URI — NOT a `file:` path under the workspace — and is filtered out of the
    // watch set. The library is immutable per version: editing it does not
    // hot-reload (a restart re-seeds the cache); only entries + their loose
    // imports are watched. So the import set is exactly the probe itself. The
    // `unrelated.pkl` exclusion still guards against the all-*.pkl superset
    // fallback (which would sweep both the alias target and the orphan in).
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

  // The add-on seed/boot contract (manifest generation, package-cache
  // pre-seeding, upgrade migration) is pinned by `AddonBootstrapSuite` — the
  // seed dir here ships only starter ENTRIES; the manifests are generated by
  // `AddonBootstrap` against the bundled lib's version (ADR 0010).

  /** Bootstrap a package-form workspace at `tmp` and copy the given lib modules
    * into `tmp/lib/` (for pure relative `import "lib/<name>"` probes). A probe
    * resolves the aliases exactly as the live server does (ADR 0010, the ONE
    * resolution mode): `@fh-dashboard` and `@fh-home` are cache packages seeded
    * by [[fh.view.testkit.PklWorkspace]] — there is no path-form and no loose
    * `home/dump.pkl`. Bootstrap seeds a minimal dump so `@fh-home` resolves
    * even for `@fh-dashboard`-only probes; [[writeDump]] overrides it with real
    * content when a probe imports the dump.
    */
  private def copyLib(tmp: os.Path): Unit = {
    PklWorkspace.bootstrap(tmp)
    // The WHOLE tree, not a named subset: the library is a graph of modules
    // across `core/`, `components/` and the roots, and a probe that copied only
    // the files it names would fail on whatever those import.
    os.copy(PklWorkspace.resourcesLib, tmp / "lib", replaceExisting = true)
  }

  /** Re-seed the `@fh-home` package from `source`, so a probe that imports
    * `@fh-home/dump.pkl` sees this dump — and its emitted
    * `import "@fh-dashboard/hass.pkl"` resolves to the same `hass` identity
    * `components.pkl` sees (both land on the one cached `@fh-dashboard`).
    */
  private def writeDump(tmp: os.Path, source: String): Unit =
    PklWorkspace.seedDump(tmp, source)

  /** Evaluate a probe against the staged Pkl project (ADR 0010, Track B): a
    * probe that imports the library through the `@fh-dashboard` alias resolves
    * it from the copied `tmp/lib` (`PklBuild` resolves the network-free local
    * lockfile in-process). Pure file-import probes evaluate the same way — the
    * project is inert for them.
    */
  private def evalProj(tmp: os.Path, entry: String) =
    SourceEval.eval(tmp, entry)

  /** A minimal published third-party package served over http — metadata JSON
    * at the bare name, module zip at `.zip`, the same remote-package protocol
    * `/system/pkl/packages/` speaks. Returns the port and a stop handle.
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

  /** The staged local-dep workspace plus a REMOTE third-party dependency,
    * mapped to the local server by the manifest's own
    * `evaluatorSettings.http.rewrites` — exactly how a real workspace maps
    * `fh.invalid` at a real host (the documented air-gap mechanism).
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
    // Package-form consumer (ADR 0010): amends the bootstrapped `.fh/base.pkl`
    // (which supplies the `@fh-dashboard` + `@fh-home` cache-package pins and the
    // `moduleCacheDir`), and adds a REMOTE `thirdparty` dep mapped to the local
    // server via the manifest's own `http.rewrites` — the documented air-gap
    // mechanism. The cached `@fh-dashboard`/`@fh-home` resolve offline; only the
    // uncached `thirdparty` is fetched through the rewrite.
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
    // The post-publish half of persona 4 (ADR 0010): a user adds a released
    // component package next to @fh-dashboard and the server's OWN resolve
    // path fetches it — the client is derived from the manifest's settings,
    // not hardcoded dummy (which died with "Dummy HTTP client cannot send
    // request" for any remote dep).
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

    // Bump the pin to a version neither the warm cache nor the (now-dead)
    // registry has: the manifest edit makes the lockfile stale, re-resolution
    // must fail LOUDLY with pkl's own error naming the package — and the
    // previous lockfile must survive the failure (resolve-before-write).
    writeThirdPartyManifest(tmp, port, "2.0.0")
    os.mtime.set(tmp / "PklProject", System.currentTimeMillis() + 1000)
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

  /** A small transformed dump (the OUTPUT shape of RegistryDump.transform): one
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
    // Each entity gets its OWN class extending the domain class, carrying just
    // that entity's capabilities (ADR 0013).
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
    // Escaped quotes survive; null friendly_name emits no assignment.
    assert(
      src.contains("friendly_name = \"Kitchen \\\"main\\\" light\""),
      clue = src
    )
    // A light's effects are a capability GROUP typed by the domain class,
    // NARROWED to non-null on the class of the entity that has one (ADR 0013).
    assert(
      src.contains(
        "hidden effects: hass.Effects = new { owner = e_light_kitchen; list = new Listing { \"colorloop\" } }"
      ),
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
      src.contains("const hidden e_light_lamp: E_light_lamp"),
      clue = src
    )

    // And the rendered module must actually evaluate, dot-paths included.
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
    copyLib(tmp)
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
    // The icon font is the one sheet the first paint does not need — a glyph
    // arriving late lands in a box the framework already sized. It must be in
    // the deferred list and NOWHERE in the blocking one, or the whole point is
    // lost to a second, render-blocking `<link>` for the same URL.
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
    // The TEXT font stays blocking on purpose: swapping it mid-paint moves
    // every line of text on the page (see `interCdn`).
    assert(
      theme
        .get[List[String]]("stylesheets")
        .toOption
        .exists(_.exists(_.contains("inter"))),
      clue = result
    )
    // A theme's `styles` is the PAINT layer now (ADR 0020): the layout contract
    // lives in `core/css.pkl` and each card's structure in its own `cardDef.css`,
    // so what a theme must still carry is its palette — including the `--fh-*`
    // re-pointing that decides what the cards' colours resolve to.
    assert(
      theme.get[String]("styles").toOption.exists(_.contains("--fh-text-dim:")),
      clue = result
    )
    assert(
      !theme.get[String]("styles").toOption.exists(_.contains(".fh-row{")),
      clue = "the layout contract must not be back in the theme: " + result
    )
    // The gesture half of the CSS: authored in the theme, not the server.
    assert(
      theme
        .get[List[String]]("inlineScripts")
        .toOption
        .exists(_.exists(_.contains("pointerdown"))),
      clue = result
    )
  }

  test("components.pkl derives the card registry from the card classes") {
    // `cards` is assembled via pkl:reflect over the module's concrete Node
    // subclasses (each class carries its template + declared slots as a hidden
    // `cardDef`). The key set must be EXACTLY the card classes — no strays
    // from non-card classes (Tab, Case, SliderSpec, ...), and nothing missing.
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
      "entityCard" -> List("label", "value", "entity_id"),
      // The more-info facts card: its subject, and one live slot holding every
      // attribute the entity reports as text.
      "entityInfo" -> List("entity_id", "attributes"),
      // `href`/`onclick` are the two arms of one choice (anchor vs scripted
      // click), so neither is a declared slot — only `label` always appears.
      "button" -> List("label"),
      // Same two arms as `button`, chip-styled and hugging its label.
      "pill" -> List("label"),
      // `checked`/`onclick` are baked/live but not build-time literals, so
      // only `label` is declared — same shape as `button`/`pill`.
      "toggle" -> List("label"),
      "tab" -> List("label", "onclick", "active"),
      // The slider is STRUCTURE — a head region and a members region — so what
      // the row shows is declared by the row's own card, and the slider itself
      // declares nothing (its only slot, `group`, is optional: a member-less
      // slider has no modifier).
      "slider" -> Nil,
      // No `state`: a slider that holds member rows omits the readout slot
      // entirely, and a declared slot is one EVERY node of the card must carry
      // (`icon`/`group` are optional for the same reason).
      //
      // The head is STRUCTURE too now (#151) — a `text` region and an `actions`
      // region — so `label` here is only the toggle variant's `aria-label`; the
      // visible one is `sliderText`'s.
      "sliderHead" -> List(
        "label",
        "value",
        "action",
        "min",
        "max",
        "key",
        "entity_id"
      ),
      // `secondary` is optional (a plain row carries none).
      "sliderText" -> List("label", "entity_id"),
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
    * (default + tap), a domain-checked slider, a candidate set, a more-info
    * tap, and both a registered and an inline popup — enough composition to
    * exercise the hoist + decode path.
    */
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
       |    c.entityCard(dump.entities.light_kitchen).tapAction(c.tap.toggle)
       |    c.entityCard(dump.entities.light_kitchen) |> c.informative
       |    c.slider(dump.entities.light_kitchen)
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

  /** A surfaces fixture: a tabs group (two panels) + an If/else — the two
    * inline-surface hoist paths (tab panels and branches).
    */
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

  /** The SHIPPED starter entrypoint, not a fixture — it is what every fresh
    * install evaluates on its first boot, and nothing else here would notice it
    * breaking. Deliberately checked against a dump it was not written for
    * (`HouseFixture` has no switches at all): "renders on any installation" is
    * its whole design property, so an empty domain list must build, not throw.
    */
  /** The starter, against a house that actually POPULATES its "Low battery"
    * section — which `HouseFixture` does not, so the test above builds it with
    * three empty sets and proves less than it looks.
    *
    * That gap shipped a real failure: a low-battery `c.entityCard` takes the
    * default tap, a sensor has no domain service, so the default is an INLINE
    * more-info popup — and the hoist never walked a candidate set's clauses, so
    * its `@@NODE_ID@@` survived and the server refused the dashboard with "the
    * build left placeholder tokens unresolved".
    *
    * Asserted on the SHIPPED starter rather than a fixture, because the shape
    * is not exotic: it is what a fresh install serves on first boot.
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
    // The starter is a SITE, so the dashboard is under `dashboards.home` —
    // hoisting the site JSON itself finds no `card` and walks nothing, which is
    // a way to write a vacuous assertion, not a passing one.
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
    // The set really did select it — otherwise the assertion above is vacuous,
    // which is exactly how this went unnoticed.
    assert(
      hoisted.noSpaces.contains("sensor.remote_battery"),
      clue =
        "the low-battery set selected no candidate; the test proves nothing"
    )
  }

  test("the bundled starter dashboard builds against an arbitrary house") {
    val d = PklFixture.buildSiteDashboard(
      "home",
      fh.view.build.AddonBootstrap.starterSite
    )
    assertEquals(d.validate(), Nil)
    // The LAYOUT names no concrete entity — the sections are queries over the
    // dump's house-wide lists, so the candidates come from whatever HA has.
    // (The header comment mentions `dump.entities.` as the way to name one, so
    // only the code after `card =` is checked.)
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
    // Lights: both fixture lights are candidates, and the ON one keeps BOTH
    // renderings (slider while on, toggle otherwise) because the guard is live.
    val lights = sets.head
    assertEquals(
      lights.candidates.sorted,
      List("light.kitchen", "light.living_room")
    )
    assertEquals(lights.members("light.kitchen").clauses.length, 2)
    // Switches: no candidates, and that is a build, not an error.
    assertEquals(sets(1).candidates, Nil)
    // Low battery: `device_class` is registry data, so the only sensor in the
    // house (a temperature one) is selected out at BUILD time.
    assertEquals(sets(2).candidates, Nil)
  }

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
      Set(
        "fhcol",
        "sectionTitle",
        "entityCard",
        "entityInfo",
        "slider",
        "button",
        "popup"
      )
        .subsetOf(d.cards.keySet),
      clue = d.cards.keySet
    )
    // The registered popup plus the hoisted inline surfaces (keyed
    // `<node-id>_self`). Four, and each is a distinct path worth having:
    // the explicit `openPopupInline` button; the `|> c.informative` light; and
    // TWO sensor cards that asked for nothing — one in the layout and one
    // nested inside the registered `detail` surface — which get a more-info
    // popup by default, because `sensor` has no service call (issue #106).
    assert(d.surfaces.contains("detail"), clue = d.surfaces.keySet)
    assertEquals(
      d.surfaces.keys.count(_.endsWith("_self")),
      4,
      clue = d.surfaces.keySet
    )
    // More-info: the entity's card, the controls its domain supports, its raw
    // facts, a close button. The inner column is `lightControls`, and the second
    // entityCard is its own — the fixture light reports no colour modes, so it
    // is a switch, and the controls are a tappable card rather than a slider.
    // Pick the LIGHT's popup by name: the sensors' have no controls column, and
    // `find` over "has an entityInfo" would now match any of the three.
    val moreInfo = d.surfaces.values
      .find(s => cardNames(s.content).count(_ == "entityCard") == 2)
      .getOrElse(fail("no more-info surface was hoisted"))
    assertEquals(
      cardNames(moreInfo.content),
      List(
        "popup",
        "fhcol",
        "entityCard",
        "fhcol",
        "entityCard",
        "entityInfo",
        "button"
      )
    )
    // One candidate set in the layout.
    assertEquals(sets(d.card).size, 1, clue = d.card)
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
      .asInstanceOf[LayoutNode.Component]
  }

  test("a clause node NAMES its candidate, and bakes its label") {
    // The inverse of what a set clause did. A case had to STRIP `entity_id`
    // (the renderer injected the matched entity per match) and leave the label
    // as a live `$attr.friendly_name` transform, because the entity was unknown
    // at build time. A clause knows its candidate: the id is a literal slot and
    // the name is baked, so neither costs a runtime read.
    val set = probeSet(
      """node = q.from(dump.areas.stue.lights).render((e) -> c.entityCard(e)).build()"""
    )
    val node = set
      .members("light.taklys")
      .clauses
      .head
      .node
      .asInstanceOf[LayoutNode.Component]
    assertEquals(node.slots("entity_id").literal, Some("light.taklys"))
    assertEquals(node.slots("label").literal, Some("Taklys"))
  }

  /** Evaluate a probe that imports the query surface over the REAL dump, and
    * decode its `node` as a candidate set — the whole Pkl-to-model path the
    * renderer's `SetNodeSuite` picks up from.
    */
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
      .asInstanceOf[LayoutNode.SetNode]
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
    // The Pkl half of phase 1's slice: a registry condition SELECTS (and never
    // reaches the wire), a live one becomes the member's guard, and the clause
    // node arrives complete — its own card, its own `entity_id`.
    val set = probeSet(
      """node = q.from(dump.areas.stue.lights)
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
    val node = clause.node.asInstanceOf[LayoutNode.Component]
    assertEquals(node.subjectEntity, Some("light.taklys"))
    // Every entity the set can be woken by: its candidates, nothing else here.
    assertEquals(set.liveEntities.sorted, set.candidates.sorted)
  }

  test("a cross-entity guard rides as `entity`, and joins liveEntities") {
    // The sensor is NOT a candidate, so only the guard names it — and the
    // reverse index has to learn about it from there or the members are never
    // woken. `liveEntities` is that derivation.
    val set = probeSet(
      """node = q.from(dump.areas.stue.lights + dump.areas.bad.lights)
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
    // The clauses hold complete nodes, so an unknown card or a bad slot in one
    // has to be caught the same way it is anywhere else in the tree.
    val set = probeSet(
      """node = q.from(dump.areas.stue.lights + dump.areas.bad.lights)
        |  .render((e) -> c.entityCard(e))
        |  .build()""".stripMargin
    )
    val cards = Map(
      "entityCard" -> CardDef(
        // The `value__bind` hole is not decoration: the library's `entityCard`
        // marks `value` as a signal slot, and `validate` rejects a card that
        // declares one without placing its binding (ADR 0017). A stub standing
        // in for a real card has to carry what that card's contract requires.
        "<b>{{label}}</b><i {{{value__bind}}}>{{value}}</i>",
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
    // The call-style factory `(c.entityCard(x)) { ... }` is pure sugar for
    // `new c.EntityCard { entity = x; ... }`: same class, so the emitted node
    // JSON must be byte-identical. Evaluate both through the fake-dump pipeline
    // and compare the raw `card`/`ctor` node JSON (not just the decoded model).
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
        |call = (c.entityCard(x)) { tapAction = c.tap.toggle }
        |ctor = new c.EntityCard { entity = x; tapAction = c.tap.toggle }
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
    // The fluent config methods (`.tapAction(...).label(...)`) are pure sugar for the
    // paren-amend `(c.entityCard(x)) { tapAction = ...; label = ... }`: each amends
    // `this` and returns the same class, so late binding re-derives `slots` and
    // the emitted node JSON must be byte-identical across all three styles
    // (builder, amend, `new`). Covers EntityCard, Button, and Slider.
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
        |cardBuilder = c.entityCard(x).tapAction(c.tap.toggle).label("Office")
        |cardAmend = (c.entityCard(x)) { tapAction = c.tap.toggle; label = "Office" }
        |cardCtor = new c.EntityCard { entity = x; tapAction = c.tap.toggle; label = "Office" }
        |
        |btnBuilder = c.button("Close", c.tap.closePopup()).label("Dismiss")
        |btnAmend = new c.Button { label = "Dismiss"; tapAction = c.tap.closePopup() }
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
    // The non-span class survives; only the span is replaced, last call winning.
    assertEquals(classes("respan"), Some(List("hero", "fh-cols-6")))
    assertEquals(classes("tabsDefault"), Some(List("fh-cols-full")))
    assertEquals(classes("tabsSized"), Some(List("fh-cols-6")))
    assertEquals(classes("hugged"), Some(List("fh-hug")))
    assertEquals(classes("pill"), Some(List("fh-hug")))
    assertEquals(classes("pillSized"), Some(List("fh-hug", "fh-cols-6")))
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
    // Centered is the default -> no `class` slot (the group-center CSS is the
    // grid's baseline); left-packing rides on the `fh-start` marker.
    assertEquals(clazz("base"), None)
    assertEquals(clazz("packed"), Some("fh-start"))
  }

  test(
    "a render lambda's cell lands on the clause node, the set's on the set"
  ) {
    val set = probeSet(
      """node = q.from(dump.areas.stue.lights)
        |  .render((e) -> c.entityCard(e).fullWidth())
        |  .build()""".stripMargin
    )
    val node = set
      .members("light.taklys")
      .clauses
      .head
      .node
      .asInstanceOf[LayoutNode.Component]
    assertEquals(node.cell.map(_.classes), Some(List("fh-cols-full")))
    // The SET's own cell is a layout builder on the built node, so the two
    // cannot be confused for each other.
    val sized = probeSet(
      """node = (q.from(dump.areas.stue.lights)
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
        .asInstanceOf[LayoutNode.Component]
        .cell,
      None
    )
  }

  test("If builder and amend forms produce identical wire output") {
    // `.then(..)/.`else`(..)` builder calls amend the same hidden Listings the
    // amend form fills directly, and the derived inlineSurfaces re-derive
    // across chained calls (late binding) — so the two authoring forms must
    // emit byte-identical node JSON.
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
    // `entity_id == x AND state == y` is how this was spelled when a predicate
    // had to find its subject by testing every entity. Naming the entity makes
    // it one lookup, and makes the reverse index exact.
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

  test("a navigating button is an anchor: `href`, and no onclick at all") {
    val nav =
      probeComponent("""node = c.button("Home", c.tap.navigate("other"))""")
    // Relative, so it resolves against the page's <base href> (ingress-safe),
    // and it is a literal — nothing about a link depends on live state.
    assertEquals(nav.slots("href").literal, Some("d/other"))
    assert(!nav.slots.contains("onclick"), clue = nav.slots)
    // Every other tap keeps the scripted-click form, and offers no href — the
    // template's `{{^href}}` arm is what renders it.
    val toggle = probeComponent(
      """light: hass.LightEntity = new { entity_id = "light.kitchen" }
        |node = c.button("Toggle", c.tap.toggle).entity(light)""".stripMargin
    )
    assert(!toggle.slots.contains("href"), clue = toggle.slots)
    assert(toggle.slots("onclick").transform.contains("@post"))
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
      rowOf(slider).slots("action").literal,
      Some("cover/set_cover_position")
    )
    assertEquals(rowOf(slider).slots("key").literal, Some("position"))
    assertEquals(rowOf(slider).slots("min").literal, Some("0"))
    assertEquals(rowOf(slider).slots("max").literal, Some("100"))
    assertEquals(
      rowOf(slider).slots("value").transform,
      "$attr.current_position"
    )
  }

  test("a slider in a QUERY bakes its config, with no $lookup($domain)") {
    // THE motivating measurement of the sets plan. A `$self` slider could
    // not know its domain until a match, so `action`/`key`/`min`/`max` and the
    // live position each rode as a `reactive: false` JSONata `$lookup` over the
    // whole sliderSpec table — five transforms per member computing a BUILD-TIME
    // fact at runtime. A candidate is a known entity, so all five are literals
    // and the lookup tier is gone.
    val set = probeSet(
      """node = q.from(dump.areas.stue.lights).render((e) -> c.slider(e)).build()"""
    )
    val slots = rowOf(
      set
        .members("light.taklys")
        .clauses
        .head
        .node
        .asInstanceOf[LayoutNode.Component]
    ).slots
    assertEquals(slots("entity_id").literal, Some("light.taklys"))
    assertEquals(slots("action").literal, Some("light/turn_on"))
    assertEquals(slots("key").literal, Some("brightness"))
    assertEquals(slots("min").literal, Some("1"))
    assertEquals(slots("max").literal, Some("255"))
    // The live position is the one that stays live — it reads state, not
    // identity — but it names the attribute directly instead of looking it up.
    assertEquals(slots("value").transform, "$attr.brightness")
    assertEquals(slots("value").default, Some("0"))
    assertEquals(slots("value").bypassUnavailable, false)
    // Not one $lookup anywhere in the member.
    assert(
      !slots.values.exists(_.transform.contains("$lookup")),
      clue = slots.view.mapValues(_.transform).toMap
    )
  }

  test("a slider with children is the same card, holding ordinary nodes") {
    // The master resolves its own domain config exactly like a childless
    // slider — it IS one — and the members arrive as children: their own
    // cards, with their own entities and their own config.
    val group = probeComponent(
      """light: hass.GenericEntity = new { entity_id = "light.lys"; domain = "light" }
        |a: hass.GenericEntity = new { entity_id = "light.a"; domain = "light" }
        |cover: hass.GenericEntity = new { entity_id = "cover.blind"; domain = "cover" }
        |node = (c.slider(light).withSubSliders(List(a, cover).map((m) -> c.slider(m).readout("percent")))) { icon = "mdi:lightbulb-group"; tapAction = c.tap.toggle }
        |""".stripMargin
    )
    assertEquals(group.card, "slider")
    // The one thing the markup takes from having children.
    assertEquals(group.slots("group").literal, Some("slider-group"))
    // A head does not repeat a readout its rows already carry.
    assert(!rowOf(group).slots.contains("state"), clue = group.slots.keySet)
    assertEquals(rowOf(group).slots("entity_id").literal, Some("light.lys"))
    assertEquals(rowOf(group).slots("action").literal, Some("light/turn_on"))
    assertEquals(
      rowOf(group).slots("icon").literal,
      Some("mdi-lightbulb-group")
    )
    // `tapAction` is now the one-button shorthand for the head's ACTIONS region
    // (#151): the press lives on a node of its own, which is what gives it a
    // busy signal nothing else shares.
    val actions = actionsOf(group)
    // The shared round icon button, not a card of the slider's own: what it
    // needed over `c.pill` was a glyph, no label and the `lit` tint, and none of
    // those three is about sliders.
    assertEquals(actions.map(_.card), List("button"))
    assert(actions.head.slots.contains("onclick"), clue = actions.head.slots)
    assertEquals(actions.head.slots("glyph").literal, Some("mdi-power"))
    assertEquals(actions.head.slots("round").literal, Some("1"))

    // The MEMBERS region specifically: `allChildren` would also hand back the
    // head, which is the point of the two regions.
    val members =
      group.regions("children").collect { case c: LayoutNode.Component => c }
    assertEquals(members.map(_.card), List("slider", "slider"))
    assertEquals(
      members.map(rowOf(_).slots("entity_id").literal),
      List(Some("light.a"), Some("cover.blind"))
    )
    // Each member keeps its OWN domain's config — the group does not impose the
    // master's.
    assertEquals(
      members.map(rowOf(_).slots("key").literal),
      List(Some("brightness"), Some("position"))
    )
    // …and reads out its LEVEL rather than its state, off its own range.
    assert(
      rowOf(members.head).slots("state").transform.contains("""& " %""""),
      clue = rowOf(members.head).slots("state").transform
    )
    assert(
      rowOf(members(1))
        .slots("state")
        .transform
        .contains("$attr.current_position"),
      clue = rowOf(members(1)).slots("state").transform
    )
    // …and because that reading IS the position, a drag moves it locally too —
    // the flag the template's section reads. The head, reading out nothing, has
    // no such slot, so its `data-on:input` paints the fill alone.
    assertEquals(rowOf(members.head).slots("dragPercent").literal, Some("1"))
    assert(
      !rowOf(group).slots.contains("dragPercent"),
      clue = group.slots.keySet
    )

    // A childless slider is the plain row it always was: no group modifier, no
    // badge, no button, and its state back as the readout.
    val plain = probeComponent(
      """light: hass.GenericEntity = new { entity_id = "light.lys"; domain = "light" }
        |node = c.slider(light)
        |""".stripMargin
    )
    assertEquals(
      rowOf(plain).slots.keySet -- Set("entity_id", "label", "state"),
      Set(
        "value",
        "fill",
        "fillColor",
        "action",
        "key",
        "min",
        "max",
        "icon",
        "busyVisual"
      ),
      clue = rowOf(plain).slots.keySet
    )
    // A plain slider is STRUCTURE too — its own slots are just the absent
    // group modifier; everything the row shows is on the row.
    assertEquals(plain.slots.keySet, Set.empty[String])
    assertEquals(rowOf(plain).slots("state").transform, "$state")
    // The badge is the entity's OWN icon, baked as a literal — here the light
    // domain's default, since this probe entity declares none.
    assertEquals(rowOf(plain).slots("icon").literal, Some("mdi-lightbulb"))
    // …and opting out drops the slot, so the template renders no badge at all.
    val bare = probeComponent(
      """light: hass.GenericEntity = new { entity_id = "light.lys"; domain = "light" }
        |node = c.slider(light).icon(null)
        |""".stripMargin
    )
    assert(!rowOf(bare).slots.contains("icon"), clue = bare.slots.keySet)
  }

  test("a slider's readout takes an expression, not just the two names") {
    // The named readings are shorthands for expressions needing the axis config
    // the card resolved — which the author can splice instead of re-deriving:
    // `percentExpr` and friends are the card's own hidden properties.
    val own = probeComponent(
      """light: hass.GenericEntity = new { entity_id = "light.lys"; domain = "light" }
        |node = (c.slider(light)) { readout = c.expr("\(percentExpr) & \" · \" & $state") }
        |""".stripMargin
    )
    val state = rowOf(own).slots("state")
    assert(state.transform.contains("$attr.brightness"), clue = state.transform)
    assert(
      state.transform.endsWith(""" & " · " & $state"""),
      clue = state.transform
    )
    // …and it can read a DIFFERENT entity, like every other Expr slot.
    val other = probeComponent(
      """light: hass.GenericEntity = new { entity_id = "light.lys"; domain = "light" }
        |power: hass.GenericEntity = new { entity_id = "sensor.w"; domain = "sensor" }
        |node = (c.slider(light)).readout(c.exprOf(power, #"$state & " W""#))
        |""".stripMargin
    )
    assertEquals(rowOf(other).slots("state").entityId, Some("sensor.w"))
    assertEquals(rowOf(other).slots("state").transform, """$state & " W"""")
    // The subject is unchanged — only the readout looks elsewhere.
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
        |node = new c.Slider { entity = sensor }
        |""".stripMargin
    )
    assert(evalProj(tmp, "probe.pkl").isLeft)
  }

  // ---- the card shape is a type, so an invalid dashboard is unconstructable ----

  /** Evaluate a probe that defines its own card class, and say whether Pkl took
    * it. The rule below is the LEAF/STRUCTURE split's structural guarantee
    * (docs/adr/0012-each-session-renders-what-it-is-owed.md, "a fragment is a
    * node's OWN html") — enforced HERE, in the authoring layer, rather than as
    * a `Dashboard.validate` message after the fact.
    *
    * The `core/` modules are imported because a card AUTHOR writes against them
    * (ADR 0015): the `components.pkl` facade re-exports cards, not the kit they
    * are built from.
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

  /** A card holding one region, with `slot` as its only slot — the shape the
    * rule is about, varying nothing but the slot.
    */
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

  /** A live BYTES slot on STRUCTURE is a build error: such a card holds content
    * AND would re-render on state, so its patch would carry everything it
    * holds.
    *
    * '''The positives are not decoration.''' They vary ONLY the slot against
    * the same card, so a probe that stopped evaluating for an unrelated reason
    * — a renamed class, a moved module — fails them too. The version of this
    * test that regions replaced had no such control, and it outlived the API it
    * named: every probe referenced a `ContainerCard` that no longer existed, so
    * all three `!accepted` assertions passed on a name-resolution error and the
    * rule went untested.
    */
  test("Pkl rejects a live BYTES slot on a card that holds regions") {
    assert(
      !cardShapeAccepted(
        structuralCard("""new slotMod.Slot { entityId = "sensor.t" }""")
      ),
      "a live slot on structure must be rejected"
    )
    // A LITERAL is fine — the rule is about the VALUE, not the card's type.
    // `Grid` is exactly this shape (see the next test).
    assert(
      cardShapeAccepted(structuralCard(""""hello"""")),
      "a literal slot on structure is fine"
    )
    // So is a SIGNAL slot, whatever it reads: its value never becomes bytes in
    // this element — it is seeded on the node's wrapper and updated by its own
    // frame (ADR 0017). This is the exemption the rule's wording turns on.
    assert(
      cardShapeAccepted(
        structuralCard(
          """new slotMod.Slot { entityId = "sensor.t"; signal = "text" }"""
        )
      ),
      "a signal slot on structure is fine"
    )
    // And so is a live slot that reads without TRACKING — `onRender`/`once`
    // never put this card in a diff set.
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
    // The rule is about the slot's VALUE, not the card's type. `Grid` holds its
    // children directly AND carries a `class` slot on that same element — but
    // the value is a plain String (the wire's `literal`), so nothing about it
    // varies with entity state and it never reaches the reverse index. An
    // earlier draft banned this shape by TYPE and would have rejected the
    // library's three most basic cards.
    assert(
      cardShapeAccepted(
        """node = (c.grid.cssClass("hero")) { children { c.title("x") } }"""
      )
    )
  }

  test("floorView emits one section per area-with-lights (title + sliders)") {
    // A fake transformed dump: floor `over` with two areas — `stue` (two
    // lights) and `bad` (one sensor, no lights). floorView must emit ONE area
    // column (stue), skipping the light-less `bad`, each area column holding a
    // sectionTitle(area_name) + a slider per light.
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
        |node = c.recipes.floorView(dump.over)
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
    val areaCols = node.allChildren.collect { case c: LayoutNode.Component =>
      c
    }
    assertEquals(areaCols.map(_.card), List("fhcol"))

    // The area column: the area name, then a slider per light (key-sorted).
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
    // The sliders resolved the light spec at build time (string literals).
    assertEquals(rowOf(inner(1)).slots("action").literal, Some("light/turn_on"))
    assertEquals(rowOf(inner(1)).slots("min").literal, Some("1"))
    assertEquals(rowOf(inner(1)).slots("max").literal, Some("255"))
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
  // To regenerate after an intentional change: `sbt dashboardSnapshotsUpdate`,
  // then read the JSON diff and commit it. It touches only these; the visual
  // PNG baselines have their own gate (`VisualSnapshot`).
  //
  // GOTCHA the command exists to contain: the gate is read from the JVM the
  // tests run IN — the persistent sbt server. Anything that leaves
  // FH_UPDATE_SNAPSHOTS set there (a shell export; a hand-rolled
  // `; put ; test ; remove` chain, whose `remove` is SKIPPED when the test task
  // fails) sticks it in regenerate mode, and every later run then reports green
  // while rewriting files.
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
      .mapObject { o =>
        val bare = o.remove("theme").remove("css")
        o("cards").fold(bare)(cards => bare.add("cards", stripCardCss(cards)))
      }
      .spaces2SortKeys

  /** CSS is stripped for the same reason `theme` is: this snapshot pins the
    * authoring/composition contract, and CSS is neither. Since ADR 0020 it
    * arrives in three places (the dashboard's base `css`, each card's `css`,
    * the theme's `styles`), and leaving any of them in would make every rule
    * tweak a wire-snapshot churn that buries the structure this file exists to
    * protect. What the CSS layers actually contain is asserted in
    * `src/test/pkl` — no JVM, and per card.
    */
  private def stripCardCss(cards: Json): Json =
    cards.asObject.fold(cards)(o =>
      Json.fromJsonObject(o.mapValues(_.mapObject(_.remove("css"))))
    )

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

  // ---- capability-conditional composition (ADR 0013) ----
  // The dump types a capability as a nullable GROUP, so a control that needs one
  // takes the group as its parameter and the composition site's `!= null` guard
  // is the same fact. These pin that the emitted cards follow the capabilities.

  /** A light probe declaring exactly the capability groups `caps` names. */
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
        "c.slider(l.colourTemp!!)"
      )
    )
    assertEquals(s.card, "slider")
    assertEquals(rowOf(s).slots("action").literal, Some("light/turn_on"))
    assertEquals(rowOf(s).slots("key").literal, Some("color_temp_kelvin"))
    // the bounds are the LIGHT's, not the light domain's brightness 1..255
    assertEquals(rowOf(s).slots("min").literal, Some("2000"))
    assertEquals(rowOf(s).slots("max").literal, Some("6535"))
    // and the handle tracks the value it writes, not brightness
    assertEquals(rowOf(s).slots("value").transform, "$attr.color_temp_kelvin")
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
    // brightness first (the domain default), then the colour-temperature one
    assertEquals(rowOf(kids(0)).slots("key").literal, Some("brightness"))
    assertEquals(rowOf(kids(1)).slots("key").literal, Some("color_temp_kelvin"))
    // one pill per named effect, each posting light/turn_on effect=<name>
    val pills = kids(2).allChildren.collect { case c: LayoutNode.Component =>
      c
    }
    assertEquals(
      pills.map(_.slots("label").literal),
      List(Some("off"), Some("Color loop"))
    )
    assert(
      pills(1).slots("onclick").transform.contains("/effect/Color%20loop'"),
      clue = pills(1).slots("onclick").transform
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
    assertEquals(kids.map(_.card), List("entityCard"))
    assertEquals(kids.head.slots("tappable").literal, Some("1"))
  }

  test("a generated entity reaches THROUGH its group with no null-proof") {
    // The payoff of narrowing the group on the per-entity class: a dashboard
    // naming a specific entity passes the group straight to something that
    // demands a non-null one — no `!!`, no guard — while the same value read
    // off a `List<hass.LightEntity>` still meets `ColourTemp?` and still has
    // to be guarded. One name, two views.
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
        |node = c.slider(dump.entities.light_a.colourTemp)
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
      .asInstanceOf[LayoutNode.Component]
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
}
