package fh.view.build

import fh.view.model.{CardDef, Dashboard, LayoutNode, SlotSource, Surface}
import io.circe.parser
import io.circe.syntax.*

class BuildPhaseSuite extends munit.FunSuite {

  private def dynamics(node: LayoutNode): List[LayoutNode.Dynamic] =
    node match {
      case c: LayoutNode.Component => c.children.flatMap(dynamics)
      case d: LayoutNode.Dynamic   => List(d)
    }

  test("DataDump.transform keys entities by id, areas/floors by name") {
    val raw = parser
      .parse("""
        {
          "areas": [
            { "area_id": "kitchen_1", "floor_id": "g", "area_name": "Kjøkken" },
            { "area_id": "lr_2", "floor_id": "g", "area_name": "Living Room" }
          ],
          "floors": [
            { "floor_id": "g", "floor_name": "Ground floor" }
          ],
          "entities": [
            { "entity_id": "sensor.temp", "friendly_name": "Temp", "domain": "sensor" },
            { "entity_id": "light.kitchen", "friendly_name": "Kitchen", "domain": "light" }
          ]
        }
      """)
      .toOption
      .get

    val transformed = DataDump.transform(raw).hcursor
    val entities = transformed.downField("entities")

    // entities: dotless, sanitized keys (no '*' member)
    assert(entities.downField("sensor_temp").succeeded)
    assert(entities.downField("light_kitchen").succeeded)
    assertEquals(
      entities.downField("sensor_temp").get[String]("friendly_name").toOption,
      Some("Temp")
    )
    assertEquals(
      entities.keys.map(_.toSet),
      Some(Set("sensor_temp", "light_kitchen"))
    )

    // areas/floors: keyed by their NAME, slugified (lower-cased, ASCII-folded)
    val areas = transformed.downField("areas")
    assertEquals(areas.keys.map(_.toSet), Some(Set("kjokken", "living_room")))
    assertEquals(
      areas.downField("kjokken").get[String]("area_id").toOption,
      Some("kitchen_1")
    )
    assertEquals(
      transformed.downField("floors").keys.map(_.toSet),
      Some(Set("ground_floor"))
    )
  }

  test(
    "JsonnetBuild evaluates the example dashboard into a valid Dashboard model"
  ) {
    val resources =
      os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards"

    val tmp = os.temp.dir()
    os.copy.into(resources / "components.libsonnet", tmp)
    os.copy.into(resources / "dashboard.jsonnet", tmp)
    os.copy.into(resources / "tokens.libsonnet", tmp)
    os.copy.into(resources / "theme.libsonnet", tmp)

    // Minimal fake dump standing in for the build-phase HA dump.
    val fakeDump = io.circe.Json
      .obj(
        "areas" -> io.circe.Json.obj(),
        "floors" -> io.circe.Json.obj(),
        "entities" -> io.circe.Json.obj(
          "sensor_temp" -> io.circe.Json.obj(
            "entity_id" -> "sensor.temp".asJson,
            "friendly_name" -> "Temperature".asJson,
            "domain" -> "sensor".asJson
          ),
          // The example dashboard statically references these entities by name
          // (the second is the multi-entity card's cross-entity secondary line).
          "sensor_ams_1a4e_p" -> io.circe.Json.obj(
            "entity_id" -> "sensor.ams_1a4e_p".asJson,
            "friendly_name" -> "Power".asJson,
            "domain" -> "sensor".asJson
          ),
          "sensor_ams_1a4e_u1" -> io.circe.Json.obj(
            "entity_id" -> "sensor.ams_1a4e_u1".asJson,
            "friendly_name" -> "L1 voltage".asJson,
            "domain" -> "sensor".asJson
          )
        )
      )
    os.write(tmp / "dump.libsonnet", fakeDump.spaces2)

    val result = JsonnetBuild.eval(tmp, "dashboard.jsonnet")
    assert(result.isRight, clue = result)

    // The import set is the entry + its transitive imports (any depth).
    val importNames = result.toOption.get.imports.map(_.last)
    assert(
      Set(
        "dashboard.jsonnet",
        "components.libsonnet",
        "theme.libsonnet",
        "tokens.libsonnet",
        "dump.libsonnet"
      ).subsetOf(importNames),
      clue = importNames
    )

    // `decode`'s pipeline: normalize single children, then hoist inline popups
    // into the surfaces registry, then decode.
    val dashboard = result.flatMap(r =>
      DashboardBuild
        .hoistInlineSurfaces(DashboardBuild.normalizeChildren(r.value))
        .as[Dashboard]
        .left
        .map(_.getMessage)
    )
    assert(dashboard.isRight, clue = dashboard)

    val d = dashboard.toOption.get
    // Shared card library is referenced by name (not baked per entity).
    assert(d.cards.contains("entityCard"), clue = d.cards.keySet)
    assert(d.cards.contains("button"), clue = d.cards.keySet)
    assert(d.cards.contains("slider"), clue = d.cards.keySet)
    // Recursive layout: top-level container (column) with the two dynamic
    // groups (per-domain dispatch + low-battery) somewhere inside.
    assertEquals(
      d.card.asInstanceOf[LayoutNode.Component].card,
      "fhcol"
    )
    assertEquals(dynamics(d.card).size, 2)
    // The theme carries tokens (+ dark overrides) AND its stylesheets/CSS, so
    // the CSS framework (Pico) is a theme property, not baked into the app.
    assert(
      d.theme.tokens.contains("primary-color"),
      clue = d.theme.tokens.keySet
    )
    assertEquals(d.theme.tokens.get("ha-card-border-radius"), Some("12px"))
    assert(
      d.theme.tokensDark.contains("card-background-color"),
      clue = d.theme.tokensDark
    )
    assert(
      d.theme.stylesheets.exists(_.contains("pico")),
      clue = d.theme.stylesheets
    )
    assert(d.theme.styles.contains(".card"), clue = d.theme.styles.take(80))
    // The composed dashboard is internally consistent.
    assertEquals(d.validate(), Nil)
  }

  test("the tabs example evaluates, hoists, and validates end-to-end") {
    val resources =
      os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards"
    val tmp = os.temp.dir()
    for (
      f <- Seq(
        "components.libsonnet",
        "tabs.jsonnet",
        "tokens.libsonnet",
        "theme.libsonnet"
      )
    )
      os.copy.into(resources / f, tmp)

    // A light + a sensor so each tab's comprehension yields a card.
    val fakeDump = io.circe.Json.obj(
      "areas" -> io.circe.Json.obj(),
      "floors" -> io.circe.Json.obj(),
      "entities" -> io.circe.Json.obj(
        "light_k" -> io.circe.Json.obj(
          "entity_id" -> "light.k".asJson,
          "friendly_name" -> "Kitchen".asJson,
          "domain" -> "light".asJson
        ),
        "sensor_t" -> io.circe.Json.obj(
          "entity_id" -> "sensor.t".asJson,
          "friendly_name" -> "Temp".asJson,
          "domain" -> "sensor".asJson,
          "attributes" -> io.circe.Json.obj()
        )
      )
    )
    os.write(tmp / "dump.libsonnet", fakeDump.spaces2)

    val result = JsonnetBuild.eval(tmp, "tabs.jsonnet")
    assert(result.isRight, clue = result)
    val dashboard = result.flatMap(r =>
      DashboardBuild
        .hoistInlineSurfaces(DashboardBuild.normalizeChildren(r.value))
        .as[Dashboard]
        .left
        .map(_.getMessage)
    )
    assert(dashboard.isRight, clue = dashboard)
    val d = dashboard.toOption.get
    // The sugar produced two grouped tab surfaces and a consistent dashboard.
    assertEquals(d.surfaces.size, 2, clue = d.surfaces.keySet)
    assert(d.surfaces.values.forall(_.mount.isDefined), clue = d.surfaces)
    assertEquals(
      d.surfaces.values.flatMap(_.group).toSet.size,
      1,
      clue = d.surfaces
    )
    assertEquals(d.validate(), Nil)
  }

  test("normalizeChildren wraps a single (non-array) child into a list") {
    val single = parser
      .parse("""{ "kind":"component", "card":"fhrow",
                 "children": { "kind":"component", "card":"x" } }""")
      .toOption
      .get
    val fixed = DashboardBuild.normalizeChildren(single)
    val kids = fixed.hcursor.downField("children")
    assert(kids.values.nonEmpty, clue = fixed) // now an array
    assertEquals(kids.downN(0).get[String]("card").toOption, Some("x"))

    // an existing array is left as-is
    val arr = parser
      .parse("""{ "children": [ { "card":"a" }, { "card":"b" } ] }""")
      .toOption
      .get
    assertEquals(
      DashboardBuild
        .normalizeChildren(arr)
        .hcursor
        .downField("children")
        .values
        .map(_.size),
      Some(2)
    )
  }

  test("validate reports a component missing a required card param") {
    val d = Dashboard(
      cards = Map(
        "card" -> CardDef(
          """<div id="{{id}}">{{label}}</div>""",
          params = List("id", "label")
        )
      ),
      // `id` is backend-injected; only "label" is missing here.
      card = LayoutNode.Component(card = "card")
    )
    val errs = d.validate()
    assert(errs.exists(_.contains("label")), clue = errs)
    assert(!errs.exists(_.contains("missing params: id")), clue = errs)
  }

  test("hoistInlineSurfaces lifts an inline surface and splices the node id") {
    // The node already carries the authored onclick referencing the future id
    // via the NODE token; the hoist only lifts the content + splices the id.
    val json = parser
      .parse("""
        {
          "cards": {},
          "card": {
            "kind": "component", "card": "fhcol",
            "children": [
              { "kind": "component", "card": "button",
                "params": { "label": "More" },
                "entities": [],
                "slots": { "onclick": { "entity": "",
                  "transform": "\"@post('/sse/surface/open/@@NODE@@_self')\"" } },
                "inlineSurfaces": { "self": {
                  "content": { "kind": "component", "card": "card" } } } }
            ]
          }
        }
      """)
      .toOption
      .get
    val hoisted = DashboardBuild.hoistInlineSurfaces(json).hcursor

    // surface lifted under "<idBase>_self" (idBase = inline_0 for card child 0)
    val keys = hoisted.downField("surfaces").keys.map(_.toList).getOrElse(Nil)
    assertEquals(keys, List("inline_0_self"), clue = keys)

    // the trigger lost its marker; the NODE token was spliced with the real id
    val trigger = hoisted.downField("card").downField("children").downN(0)
    assert(
      trigger.downField("inlineSurfaces").failed,
      clue = "marker not removed"
    )
    assertEquals(
      trigger
        .downField("slots")
        .downField("onclick")
        .get[String]("transform")
        .toOption,
      Some("\"@post('/sse/surface/open/inline_0_self')\"")
    )
    // the moved content lives under the new surface id
    assertEquals(
      hoisted
        .downField("surfaces")
        .downField("inline_0_self")
        .downField("content")
        .get[String]("card")
        .toOption,
      Some("card")
    )
  }

  test(
    "hoistInlineSurfaces lifts a multi-entry marker and splices ids across the subtree"
  ) {
    // Shaped like what c.tabs emits: a container with N inline surfaces + child
    // triggers referencing the future ids via the NODE token (here the top-level
    // card is the marker-bearing node, so idBase = "inline").
    val json = parser
      .parse("""
        {
          "cards": {},
          "card": {
            "kind": "component", "card": "tabs", "entities": [], "slots": {},
            "params": { "initial": "@@NODE@@_0", "mount": "panel_@@NODE@@", "sig": "tab_@@NODE@@" },
            "children": [
              { "kind": "component", "card": "button", "entities": [],
                "params": { "active": "$tab_@@NODE@@ == '@@NODE@@_0'" },
                "slots": { "onclick": { "entity": "",
                  "transform": "\"@post('/sse/surface/open/@@NODE@@_0')\"" } } }
            ],
            "inlineSurfaces": {
              "0": { "content": { "kind":"component","card":"card" }, "group":"panel_@@NODE@@", "mount":"panel_@@NODE@@" },
              "1": { "content": { "kind":"component","card":"card" }, "group":"panel_@@NODE@@", "mount":"panel_@@NODE@@" }
            }
          }
        }
      """)
      .toOption
      .get
    val h = DashboardBuild.hoistInlineSurfaces(json).hcursor

    // both surfaces lifted under "<idBase>_<localKey>", sharing one group+mount
    val surfaces = h.downField("surfaces")
    assertEquals(
      surfaces.keys.map(_.toSet).getOrElse(Set.empty),
      Set("inline_0", "inline_1")
    )
    val mount = "panel_inline"
    for (k <- Set("inline_0", "inline_1")) {
      assertEquals(
        surfaces.downField(k).get[String]("group").toOption,
        Some(mount)
      )
      assertEquals(
        surfaces.downField(k).get[String]("mount").toOption,
        Some(mount)
      )
    }

    // the container lost its marker; the NODE token was spliced everywhere
    val node = h.downField("card")
    assert(node.downField("inlineSurfaces").failed, clue = "marker not removed")
    assertEquals(
      node.downField("params").get[String]("initial").toOption,
      Some("inline_0")
    )
    assertEquals(
      node.downField("params").get[String]("mount").toOption,
      Some(mount)
    )

    val first = node.downField("children").downN(0)
    assertEquals(
      first.downField("params").get[String]("active").toOption,
      Some("$tab_inline == 'inline_0'")
    )
    assertEquals(
      first
        .downField("slots")
        .downField("onclick")
        .get[String]("transform")
        .toOption,
      Some("\"@post('/sse/surface/open/inline_0')\"")
    )
  }

  test("validate checks card references inside a surface") {
    val d = Dashboard(
      cards = Map("ok" -> CardDef("<i>{{label}}</i>", params = List("label"))),
      card = LayoutNode.Component("ok", params = Map("label" -> "x")),
      surfaces = Map("p" -> Surface(LayoutNode.Component("nope")))
    )
    val errs = d.validate()
    assert(
      errs.exists(e => e.contains("surface 'p'") && e.contains("unknown card")),
      clue = errs
    )
  }

  test(
    "validate reports a slot whose transform fails to compile (blocks load)"
  ) {
    val d = Dashboard(
      cards =
        Map("card" -> CardDef("<span>{{state}}</span>", slots = List("state"))),
      card = LayoutNode.Component(
        "card",
        // unterminated string literal -> JSONata compile failure
        slots = Map("state" -> SlotSource(Some("e.x"), transform = "'unclosed"))
      )
    )
    assert(
      d.validate().exists(_.contains("invalid transform")),
      clue = d.validate()
    )
  }

  test("validate reports a reference to an unknown card") {
    val d = Dashboard(
      cards = Map.empty,
      card = LayoutNode.Component("nope")
    )
    assert(d.validate().exists(_.contains("unknown card")), clue = d.validate())
  }

  test("literalLocator points a transform back at its jsonnet line") {
    val dir = os.temp.dir()
    os.write(
      dir / "dashboard.jsonnet",
      "local c = import 'x';\n" +
        "{ value: c.entityCard(p, transform='$round($number($state), 1)') }\n"
    )
    // The generated dump is skipped even if it contains the literal.
    os.write(dir / "dump.libsonnet", "{ x: '$round($number($state), 1)' }\n")

    val locate = JsonnetBuild.literalLocator(
      Set(dir / "dashboard.jsonnet", dir / "dump.libsonnet")
    )
    assertEquals(
      locate("$round($number($state), 1)"),
      Some("dashboard.jsonnet:2")
    )
    assertEquals(locate("$nope($)"), None)
  }
}
