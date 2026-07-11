package fh.view.build

import fh.view.model.{
  Activation,
  Dashboard,
  LayoutNode,
  Op,
  Predicate,
  Quantifier
}
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
    // Entry imports components.pkl (which transitively imports hass.pkl); an
    // unrelated sibling .pkl in the same dir is NOT imported, so the static
    // import analysis must exclude it (unlike the all-*.pkl superset).
    val tmp = os.temp.dir()
    copyLib(tmp, "hass.pkl", "components.pkl")
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
        |import "lib/hass.pkl"
        |import "lib/components.pkl" as c
        |
        |x = 1
        |""".stripMargin
    )

    val result = SourceEval.eval(tmp, "entry.pkl")
    assert(result.isRight, clue = result)
    val imports = result.toOption.get.imports

    // The entry + its transitive imports, and nothing else.
    assertEquals(
      imports,
      Set(
        tmp / "entry.pkl",
        tmp / "lib" / "components.pkl",
        tmp / "lib" / "hass.pkl"
      ),
      clue = imports
    )
    assert(!imports.contains(tmp / "unrelated.pkl"), clue = imports)
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
    "theme-pico.pkl emits the {tokens, tokensDark, stylesheets, styles, chrome} shape"
  ) {
    // A probe entry re-exposes the theme so the assertions read a pinned
    // shape, independent of whatever else the lib module happens to export.
    // (Pico probes the Theme contract here; the beer DEFAULT theme is pinned
    // by the wire snapshots, which carry the full theme JSON.)
    val tmp = os.temp.dir()
    copyLib(tmp, "theme.pkl", "theme-pico.pkl", "tokens.pkl")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |import "lib/theme-pico.pkl" as themeMod
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
        .exists(_.exists(_.contains("pico"))),
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
        |import "lib/components.pkl" as c
        |cards = c.cards
        |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val cards = result.toOption.get.value.hcursor.downField("cards")

    val expectedSlots = Map(
      "fhrow" -> Nil,
      "fhcol" -> Nil,
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
    }
    // Hidden cardDef never leaks into an emitted node.
    os.write.over(
      tmp / "probe.pkl",
      """module probe
        |import "lib/components.pkl" as c
        |node = new c.SectionTitle { text = "x" }
        |""".stripMargin
    )
    val nodeResult = SourceEval.eval(tmp, "probe.pkl")
    assert(nodeResult.isRight, clue = nodeResult)
    val nodeKeys =
      nodeResult.toOption.get.value.hcursor.downField("node").keys
    assert(
      nodeKeys.exists(ks => !ks.exists(_ == "cardDef")),
      clue = nodeKeys
    )
  }

  test("pkl-demo evaluates through the full pipeline into a valid Dashboard") {
    val resources = resourcesLib / os.up
    val tmp = os.temp.dir()
    copyLib(
      tmp,
      "hass.pkl",
      "components.pkl",
      "theme.pkl",
      "theme-beer.pkl",
      "tokens.pkl",
      "entry.pkl"
    )
    os.copy.into(resources / "pkl-demo.pkl", tmp)

    // Fake transformed dump defining exactly the entities the demo references.
    val fakeDump = io.circe.parser
      .parse("""
        {
          "areas": {},
          "floors": {},
          "entities": {
            "sensor_ams_1a4e_q": {
              "entity_id": "sensor.ams_1a4e_q", "friendly_name": "Power",
              "domain": "sensor", "attributes": {}
            },
            "sensor_ams_1a4e_u1": {
              "entity_id": "sensor.ams_1a4e_u1", "friendly_name": "L1 voltage",
              "domain": "sensor", "attributes": {}
            },
            "light_skyconnect_v1_0_light_group_overetasje_stue_sittegruppe_gang": {
              "entity_id": "light.skyconnect_v1_0_light_group_overetasje_stue_sittegruppe_gang",
              "friendly_name": "Demo light",
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

    // The precise import set is the demo's full transitive closure.
    val importNames = r.imports.map(_.last)
    assert(
      Set(
        "pkl-demo.pkl",
        "components.pkl",
        "theme.pkl",
        "theme-beer.pkl",
        "tokens.pkl",
        "hass.pkl",
        "dump.pkl"
      )
        .subsetOf(importNames),
      clue = importNames
    )

    // The FULL build pipeline: hoist the inline popup surface into the
    // registry (splicing the trigger's NODE_ID), then decode.
    val hoisted = DashboardBuild.hoistInlineSurfaces(r.value)
    // Every @@NODE_ID@@ token was spliced with a real id — none survives.
    assert(
      !hoisted.noSpaces.contains(DashboardBuild.NodeIdToken),
      clue = "unspliced NODE_ID token remained in the hoisted JSON"
    )
    val decoded = hoisted.as[Dashboard]
    assert(decoded.isRight, clue = decoded)
    val d = decoded.toOption.get

    assert(
      Set(
        "fhrow",
        "fhcol",
        "sectionTitle",
        "entityCard",
        "button",
        "slider",
        "popup"
      ).subsetOf(d.cards.keySet),
      clue = d.cards.keySet
    )
    // Two surfaces: the REGISTERED `detail` popup and the hoisted INLINE one
    // (keyed `<node-id>_self`, node-id in the `c`-rooted pathId scheme).
    assert(d.surfaces.contains("detail"), clue = d.surfaces.keySet)
    val inlineId =
      d.surfaces.keys
        .find(_.endsWith("_self"))
        .getOrElse(
          fail(s"no hoisted _self surface: ${d.surfaces.keySet}")
        )
    assert(inlineId.startsWith("c"), clue = inlineId)
    assert(d.theme.tokens.nonEmpty)
    assert(d.theme.chrome.contains("id=\"dashboard\""))

    val root = d.card.asInstanceOf[LayoutNode.Component]
    assertEquals(root.card, "fhcol")
    // The layout now interleaves component cards with two dynamic groups and
    // ends with the popup-opening buttons.
    val children =
      root.children.collect { case c: LayoutNode.Component => c }
    assertEquals(
      children.map(_.card),
      List(
        "sectionTitle",
        "entityCard",
        "entityCard",
        "slider",
        "sectionTitle",
        "sectionTitle",
        "sectionTitle",
        "button",
        "button",
        "button"
      )
    )

    // The inline-popup trigger (the "Quick info…" button) carries a literal
    // onclick that references the SPLICED real surface id, not the raw token.
    val inlineTrigger = children
      .find(
        _.slots.get("onclick").flatMap(_.literal).exists(_.contains("_self"))
      )
      .getOrElse(fail("no inline-popup trigger button found"))
    assertEquals(
      inlineTrigger.slots("onclick").literal,
      Some(s"@post('sse/surface/open/$inlineId')")
    )

    // Two dynamic groups: a per-domain dispatch group and the low-battery one.
    val dyns = dynamics(d.card)
    assertEquals(dyns.size, 2)

    // Dispatch group: query = stateIs("on"); a light branch (a $self Slider)
    // + an always entityCard fallback; no `entity_id` slot survives the cases.
    val dispatch = dyns(0)
    assertEquals(
      dispatch.query,
      Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on")))
    )
    assertEquals(dispatch.cases.map(_.card), List("slider", "entityCard"))
    assertEquals(
      dispatch.cases(0).when,
      Predicate.Cmp("domain", Op.Eq, Json.fromString("light"))
    )
    assertEquals(
      dispatch.cases(1).when,
      Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__"))
    )
    dispatch.cases.foreach(cse =>
      assert(!cse.slots.contains("entity_id"), clue = cse.slots.keySet)
    )

    // Low-battery group: query = lowBattery(20) =
    // deviceClassIs("battery").and(stateBelow(20)).
    val battery = dyns(1)
    assertEquals(battery.cases.map(_.card), List("entityCard"))
    battery.query match {
      case Some(Predicate.And(items)) =>
        assertEquals(
          items,
          List(
            Predicate
              .Cmp("attr:device_class", Op.Eq, Json.fromString("battery")),
            Predicate.Cmp("state", Op.Lt, Json.fromInt(20))
          )
        )
      case other => fail(s"expected And query, got $other")
    }

    // Slider config resolved at build time, as STRING literals (the slot
    // decoder rejects numbers — the highest-risk contract rule).
    val slider = children(3)
    assertEquals(slider.slots("min").literal, Some("1"))
    assertEquals(slider.slots("max").literal, Some("255"))
    assertEquals(slider.slots("action").literal, Some("light/turn_on"))
    assertEquals(slider.slots("key").literal, Some("brightness"))
    assertEquals(
      slider.slots("entity_id").literal,
      Some("light.skyconnect_v1_0_light_group_overetasje_stue_sittegruppe_gang")
    )

    // The tapped entity card: constant `tappable` marker + an identity-derived
    // (non-reactive) onclick expression.
    val tapped = children(2)
    assertEquals(tapped.slots("tappable").literal, Some("1"))
    val onclick = tapped.slots("onclick")
    assertEquals(onclick.literal, None)
    assertEquals(onclick.reactive, false)
    assert(onclick.transform.contains("sse/action/"), clue = onclick)

    // Validation (card refs, required slots, JSONata compile) passes.
    assertEquals(d.validate(SourceEval.literalLocator(r.imports)), Nil)
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
         |import "lib/hass.pkl"
         |import "lib/components.pkl" as c
         |
         |$body
         |
         |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
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
         |import "lib/hass.pkl"
         |import "lib/components.pkl" as c
         |
         |$body
         |
         |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
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
        |import "lib/hass.pkl"
        |import "lib/components.pkl" as c
        |
        |x: hass.LightEntity = new { entity_id = "light.kitchen" }
        |
        |call = (c.entityCard(x)) { tap = c.toggleTap }
        |ctor = new c.EntityCard { entity = x; tap = c.toggleTap }
        |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
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
        |import "lib/hass.pkl"
        |import "lib/components.pkl" as c
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
    val result = SourceEval.eval(tmp, "probe.pkl")
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
        |import "lib/components.pkl" as c
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
    val result = SourceEval.eval(tmp, "probe.pkl")
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
        |import "lib/components.pkl" as c
        |p = c.entityIs("light.kitchen")
        |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
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
        |import "lib/hass.pkl"
        |import "lib/components.pkl" as c
        |sensor: hass.GenericEntity = new { entity_id = "sensor.temp"; domain = "sensor" }
        |node = new c.Slider { entity = sensor }
        |""".stripMargin
    )
    assert(SourceEval.eval(tmp, "probe.pkl").isLeft)
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
    os.write(tmp / "lib" / "dump.pkl", PklDump.render(fakeDump))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "lib/components.pkl" as c
        |import "lib/dump.pkl" as dump
        |
        |node = c.floorView(dump.over)
        |""".stripMargin
    )

    val result = SourceEval.eval(tmp, "probe.pkl")
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

  test(
    "pkl-tabs evaluates, hoists, and validates end-to-end (mirror jsonnet)"
  ) {
    val resources = resourcesLib / os.up
    val tmp = os.temp.dir()
    copyLib(
      tmp,
      "hass.pkl",
      "components.pkl",
      "theme.pkl",
      "theme-beer.pkl",
      "tokens.pkl",
      "entry.pkl"
    )
    os.copy.into(resources / "pkl-tabs.pkl", tmp)

    // Fake transformed dump defining exactly the entities the tabs demo names:
    // a light (Lights tab) + two sensors (Sensors tab).
    val fakeDump = io.circe.parser
      .parse("""
        {
          "areas": {},
          "floors": {},
          "entities": {
            "sensor_ams_1a4e_q": {
              "entity_id": "sensor.ams_1a4e_q", "friendly_name": "Power",
              "domain": "sensor", "attributes": {}
            },
            "sensor_ams_1a4e_u1": {
              "entity_id": "sensor.ams_1a4e_u1", "friendly_name": "L1 voltage",
              "domain": "sensor", "attributes": {}
            },
            "light_skyconnect_v1_0_light_group_overetasje_stue_sittegruppe_gang": {
              "entity_id": "light.skyconnect_v1_0_light_group_overetasje_stue_sittegruppe_gang",
              "friendly_name": "Demo light",
              "domain": "light", "attributes": {}
            }
          }
        }
      """)
      .toOption
      .get
    os.write(tmp / "lib" / "dump.pkl", PklDump.render(fakeDump))

    val result = SourceEval.eval(tmp, "pkl-tabs.pkl")
    assert(result.isRight, clue = result)
    val r = result.toOption.get

    // FULL build pipeline: hoist the inline tab surfaces (splicing each
    // trigger's NODE_ID), then decode.
    val hoisted = DashboardBuild.hoistInlineSurfaces(r.value)
    // No @@NODE_ID@@ token survives the hoist anywhere in the JSON.
    assert(
      !hoisted.noSpaces.contains(DashboardBuild.NodeIdToken),
      clue = "unspliced NODE_ID token remained in the hoisted JSON"
    )
    val decoded = hoisted.as[Dashboard]
    assert(decoded.isRight, clue = decoded)
    val d = decoded.toOption.get

    // The sugar produced two inline tab surfaces.
    assertEquals(d.surfaces.size, 2, clue = d.surfaces.keySet)
    // Both panels share ONE derived host (exclusivity by shared hostId).
    assertEquals(
      d.surfaces.values.map(_.hostId).toSet.size,
      1,
      clue = d.surfaces
    )
    // Ids use the unified scheme: idBase (`c`-rooted pathId) + the `t<i>` key.
    assert(
      d.surfaces.keySet.forall(_.matches("c(_\\d+)+_t\\d+")),
      clue = d.surfaces.keySet
    )
    assert(
      d.surfaces.keySet.exists(_.endsWith("_t0")) &&
        d.surfaces.keySet.exists(_.endsWith("_t1")),
      clue = d.surfaces.keySet
    )
    // Every surface is a chrome-less panel with a bake position.
    assert(
      d.surfaces.values.forall(s =>
        s.bakeInto.isDefined && s.bakeAs.contains("panel")
      ),
      clue = d.surfaces
    )
    assertEquals(
      d.surfaces.values.flatMap(_.bakeIndex).toList.sorted,
      List(0, 1),
      clue = d.surfaces
    )
    // Exactly ONE default-open member — the FIRST tab — expressed through the
    // activation sum ({kind:"user", defaultOpen:true}); the other panel emits
    // no `activation` at all (dropped null) and decodes to the closed user
    // default.
    assertEquals(
      d.surfaces.toList.sortBy(_._1).map(_._2.activation),
      List(Activation.User(true), Activation.User(false)),
      clue = d.surfaces
    )

    // The tabs component sits in the layout with two tab-bar buttons; each
    // button's onclick references the SPLICED real surface id (not the raw
    // token) AND writes the fhui_ restore cookie.
    val root = d.card.asInstanceOf[LayoutNode.Component]
    val tabsNode = root.children
      .collectFirst {
        case c: LayoutNode.Component if c.card == "tabs" => c
      }
      .getOrElse(fail("no tabs component in the layout"))
    val tabButtons =
      tabsNode.children.collect { case c: LayoutNode.Component => c }
    assertEquals(tabButtons.map(_.card), List("tab", "tab"))
    tabButtons.foreach { b =>
      val onclick = b
        .slots("onclick")
        .literal
        .getOrElse(fail(s"tab button onclick not a literal: ${b.slots}"))
      assert(onclick.contains("fhui_"), clue = onclick)
      // The spliced surface id it opens is a real registered surface.
      val opened = d.surfaces.keys
        .find(id => onclick.contains(id))
        .getOrElse(fail(s"onclick references no known surface: $onclick"))
      assert(!opened.contains(DashboardBuild.NodeIdToken), clue = opened)
    }

    // Validation (card refs, required slots, JSONata compile) passes.
    assertEquals(d.validate(SourceEval.literalLocator(r.imports)), Nil)
  }

  test("pkl-if evaluates, hoists, and validates end-to-end (If/else)") {
    val resources = resourcesLib / os.up
    val tmp = os.temp.dir()
    copyLib(
      tmp,
      "hass.pkl",
      "components.pkl",
      "theme.pkl",
      "theme-beer.pkl",
      "tokens.pkl",
      "entry.pkl"
    )
    os.copy.into(resources / "pkl-if.pkl", tmp)
    // The entry names exactly the snapshot dump's entities (the demo light +
    // the two AMS sensors).
    os.write(tmp / "lib" / "dump.pkl", PklDump.render(snapshotDump))

    val result = SourceEval.eval(tmp, "pkl-if.pkl")
    assert(result.isRight, clue = result)
    val r = result.toOption.get

    // FULL build pipeline: hoist the inline branch surfaces (splicing each
    // host's NODE_ID), then decode.
    val hoisted = DashboardBuild.hoistInlineSurfaces(r.value)
    assert(
      !hoisted.noSpaces.contains(DashboardBuild.NodeIdToken),
      clue = "unspliced NODE_ID token remained in the hoisted JSON"
    )
    val decoded = hoisted.as[Dashboard]
    assert(decoded.isRight, clue = decoded)
    val d = decoded.toOption.get

    // Three If hosts in the layout, all referencing the reflect-registered
    // `ifhost` card; hosts carry no static children — branches live in
    // surfaces only (that structural split IS the hidden-branch silence).
    assert(d.cards.contains("ifhost"), clue = d.cards.keySet)
    val root = d.card.asInstanceOf[LayoutNode.Component]
    val hosts = root.children.collect {
      case c: LayoutNode.Component if c.card == "ifhost" => c
    }
    assertEquals(hosts.size, 3)
    assert(hosts.forall(_.children.isEmpty), clue = hosts)

    // Hoisted surface ids are `<host-id>_<then|else>`; the iffNone If has no
    // else member (no match ⇒ the host bakes empty).
    assertEquals(
      d.surfaces.keySet,
      Set("c_1_then", "c_1_else", "c_2_then", "c_2_else", "c_3_then")
    )
    // Every member of a group carries the SAME bake var ("branch" — the
    // backend reads it off the group's first member), its host, and its
    // first-match position.
    assert(
      d.surfaces.values.forall(_.bakeAs.contains("branch")),
      clue = d.surfaces
    )
    assertEquals(d.surfaces("c_1_then").bakeInto, Some("c_1"))
    assertEquals(d.surfaces("c_1_then").bakeIndex, Some(0))
    assertEquals(d.surfaces("c_1_else").bakeIndex, Some(1))

    // The builder-form If: a StateActivation carrying the authored
    // entityIs(..).and(stateIs("on")) condition, default quantifier "any".
    d.surfaces("c_1_then").activation match {
      case Activation.State(Predicate.And(items), q) =>
        assertEquals(q, Quantifier.Any)
        assertEquals(
          items,
          List(
            Predicate.Cmp(
              "entity_id",
              Op.Eq,
              Json.fromString(
                "light.skyconnect_v1_0_light_group_overetasje_stue_sittegruppe_gang"
              )
            ),
            Predicate.Cmp("state", Op.Eq, Json.fromString("on"))
          )
        )
      case other => fail(s"expected a State(And(..)) activation: $other")
    }
    // The else member is State(condition = the always-true predicate) — no
    // nullable condition on the wire.
    assertEquals(
      d.surfaces("c_1_else").activation,
      Activation.State(
        Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__")),
        Quantifier.Any
      )
    )
    // The iffNone variant rides its quantifier onto the wire.
    d.surfaces("c_3_then").activation match {
      case Activation.State(_, q) => assertEquals(q, Quantifier.None)
      case other => fail(s"expected a State activation: $other")
    }
    // Branch bodies are Row-wrapped, like tab panels.
    assertEquals(
      d.surfaces("c_2_then").content.asInstanceOf[LayoutNode.Component].card,
      "fhrow"
    )

    // Validation (card refs incl. ifhost, required slots, JSONata compile,
    // bake-group activation homogeneity) passes.
    assertEquals(d.validate(SourceEval.literalLocator(r.imports)), Nil)
  }

  // ---------------------------------------------------------------------------
  // Wire-format snapshot tests (plan-jsonnet-removal.md Phase 0).
  //
  // These byte-identity-check the evaluated `{cards, card, theme, surfaces}`
  // wire JSON of the REAL Pkl entries against checked-in resource files, so
  // authoring-layer / backend refactors are guarded by `sbt test` instead of
  // manual diffing. The snapshot is exactly what production renders:
  // `PklBuild.eval` → `SourceEval.Result.value` (the raw evaluated JSON, BEFORE
  // normalize/hoist/decode), printed with the fixed `spaces2SortKeys` printer so
  // Pkl map-ordering can never make the output nondeterministic. No live HA is
  // needed — a minimal fake `lib/dump.pkl` (below) supplies the entities.
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

  /** One fake transformed dump covering exactly the entities the entries name:
    * two sensors (`_q` power, `_u1` voltage) and the demo light (with a
    * `color_mode` so the demo slider resolves). No areas/floors are referenced.
    */
  private val snapshotDump = io.circe.parser
    .parse("""
      {
        "areas": {},
        "floors": {},
        "entities": {
          "sensor_ams_1a4e_q": {
            "entity_id": "sensor.ams_1a4e_q", "friendly_name": "Power",
            "domain": "sensor", "attributes": {}
          },
          "sensor_ams_1a4e_u1": {
            "entity_id": "sensor.ams_1a4e_u1", "friendly_name": "L1 voltage",
            "domain": "sensor", "attributes": {}
          },
          "light_skyconnect_v1_0_light_group_overetasje_stue_sittegruppe_gang": {
            "entity_id": "light.skyconnect_v1_0_light_group_overetasje_stue_sittegruppe_gang",
            "friendly_name": "Demo light",
            "domain": "light", "attributes": { "color_mode": "brightness" }
          }
        }
      }
    """)
    .toOption
    .get

  /** Evaluate a real entry through the fake-dump pipeline and return the raw
    * evaluated wire JSON, printed with the fixed deterministic printer.
    */
  private def evalEntryWire(entry: String): String = {
    val resources = resourcesLib / os.up
    val tmp = os.temp.dir()
    copyLib(
      tmp,
      "hass.pkl",
      "components.pkl",
      "theme.pkl",
      "theme-beer.pkl",
      "tokens.pkl",
      "entry.pkl"
    )
    os.copy.into(resources / entry, tmp)
    os.write(tmp / "lib" / "dump.pkl", PklDump.render(snapshotDump))
    val result = SourceEval.eval(tmp, entry)
    assert(result.isRight, clue = result)
    result.toOption.get.value.spaces2SortKeys
  }

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

  test("addon seed dashboard builds and validates against an EMPTY dump") {
    // The seed shipped in the add-on image (home-addon/dashboards-seed/) must
    // work on ANY Home Assistant instance, so it may reference no concrete
    // entities. Proven by the strictest case: it never imports lib/dump.pkl
    // (none is written here), and the decoded dashboard validates.
    val tmp = os.temp.dir()
    copyLib(
      tmp,
      "hass.pkl",
      "components.pkl",
      "theme.pkl",
      "theme-beer.pkl",
      "tokens.pkl",
      "entry.pkl"
    )
    os.copy.into(
      os.pwd / "home-addon" / "dashboards-seed" / "dashboard.pkl",
      tmp
    )

    val result = SourceEval.eval(tmp, "dashboard.pkl")
    assert(result.isRight, clue = result)
    val r = result.toOption.get
    assert(!r.imports.map(_.last).contains("dump.pkl"), clue = r.imports)

    val hoisted = DashboardBuild.hoistInlineSurfaces(r.value)
    val decoded = hoisted.as[Dashboard]
    assert(decoded.isRight, clue = decoded)
    assertEquals(
      decoded.toOption.get.validate(SourceEval.literalLocator(r.imports)),
      Nil
    )
  }

  test("pkl-demo wire JSON matches the checked-in snapshot") {
    checkSnapshot("pkl-demo", evalEntryWire("pkl-demo.pkl"))
  }

  test("pkl-tabs wire JSON matches the checked-in snapshot") {
    checkSnapshot("pkl-tabs", evalEntryWire("pkl-tabs.pkl"))
  }

  test("pkl-if wire JSON matches the checked-in snapshot") {
    checkSnapshot("pkl-if", evalEntryWire("pkl-if.pkl"))
  }
}
