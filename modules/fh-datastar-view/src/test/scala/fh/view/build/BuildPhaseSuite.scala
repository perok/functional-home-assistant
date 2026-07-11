package fh.view.build

import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  Op,
  Predicate,
  Quantifier,
  SlotSource,
  Surface
}
import io.circe.{parser, Json}

class BuildPhaseSuite extends munit.FunSuite {

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

  test("validate reports a component missing a required card slot") {
    val d = Dashboard(
      cards = Map(
        "card" -> CardDef(
          """<div id="{{id}}">{{label}}</div>""",
          slots = List("id", "label")
        )
      ),
      // `id` is backend-injected; only "label" is missing here.
      card = LayoutNode.Component(card = "card")
    )
    val errs = d.validate()
    assert(errs.exists(_.contains("label")), clue = errs)
    assert(!errs.exists(_.contains("missing slots: id")), clue = errs)
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
                  "transform": "\"@post('sse/surface/open/@@NODE_ID@@_self')\"" } },
                "inlineSurfaces": { "self": {
                  "content": { "kind": "component", "card": "card" } } } }
            ]
          }
        }
      """)
      .toOption
      .get
    val hoisted = DashboardBuild.hoistInlineSurfaces(json).hcursor

    // surface lifted under "<idBase>_self" (idBase = c_0, the render-time `{{id}}`
    // of card child 0 — the build/hoist id scheme equals LayoutNode.pathId)
    val keys = hoisted.downField("surfaces").keys.map(_.toList).getOrElse(Nil)
    assertEquals(keys, List("c_0_self"), clue = keys)

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
      Some("\"@post('sse/surface/open/c_0_self')\"")
    )
    // the moved content lives under the new surface id
    assertEquals(
      hoisted
        .downField("surfaces")
        .downField("c_0_self")
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
    // card is the marker-bearing node, so idBase = "c" = pathId(Nil)). The
    // "panelHost" node param is an arbitrary string value (unrelated to the
    // Surface model) used to demonstrate splicing reaches every string leaf in
    // the subtree, not just Surface fields; `bakeInto`/`bakeAs` are the real
    // Surface fields that share a host (`Surface.hostId` derives from them).
    val json = parser
      .parse("""
        {
          "cards": {},
          "card": {
            "kind": "component", "card": "tabs", "entities": [], "slots": {},
            "params": { "initial": "@@NODE_ID@@_0", "panelHost": "panel_@@NODE_ID@@", "sig": "tab_@@NODE_ID@@" },
            "children": [
              { "kind": "component", "card": "button", "entities": [],
                "params": { "active": "$tab_@@NODE_ID@@ == '@@NODE_ID@@_0'" },
                "slots": { "onclick": { "entity": "",
                  "transform": "\"@post('sse/surface/open/@@NODE_ID@@_0')\"" } } }
            ],
            "inlineSurfaces": {
              "0": { "content": { "kind":"component","card":"card" }, "bakeInto": "@@NODE_ID@@", "bakeAs": "panel" },
              "1": { "content": { "kind":"component","card":"card" }, "bakeInto": "@@NODE_ID@@", "bakeAs": "panel" }
            }
          }
        }
      """)
      .toOption
      .get
    val h = DashboardBuild.hoistInlineSurfaces(json).hcursor

    // both surfaces lifted under "<idBase>_<localKey>", sharing one bakeInto
    // (so they derive the SAME hostId — the panel host, one per tabs group)
    val surfaces = h.downField("surfaces")
    assertEquals(
      surfaces.keys.map(_.toSet).getOrElse(Set.empty),
      Set("c_0", "c_1")
    )
    for (k <- Set("c_0", "c_1")) {
      assertEquals(
        surfaces.downField(k).get[String]("bakeInto").toOption,
        Some("c")
      )
      assertEquals(
        surfaces.downField(k).get[String]("bakeAs").toOption,
        Some("panel")
      )
    }

    // the container lost its marker; the NODE token was spliced everywhere
    val node = h.downField("card")
    assert(node.downField("inlineSurfaces").failed, clue = "marker not removed")
    assertEquals(
      node.downField("params").get[String]("initial").toOption,
      Some("c_0")
    )
    assertEquals(
      node.downField("params").get[String]("panelHost").toOption,
      Some("panel_c")
    )

    val first = node.downField("children").downN(0)
    assertEquals(
      first.downField("params").get[String]("active").toOption,
      Some("$tab_c == 'c_0'")
    )
    assertEquals(
      first
        .downField("slots")
        .downField("onclick")
        .get[String]("transform")
        .toOption,
      Some("\"@post('sse/surface/open/c_0')\"")
    )
  }

  test("Surface.activation decodes: absent key -> User(false)") {
    val decoded = parser
      .parse("""{ "content": { "kind": "component", "card": "x" } }""")
      .toOption
      .get
      .as[Surface]
    assertEquals(decoded.toOption.get.activation, Activation.User(false))
    // The RETIRED flat `defaultOpen` is an unknown key the decoder ignores —
    // the interim contract while the Pkl authoring layer still emits it (its
    // effect survives via resolveActive's index-0 fallback).
    val flat = parser
      .parse(
        """{ "content": { "kind": "component", "card": "x" }, "defaultOpen": true }"""
      )
      .toOption
      .get
      .as[Surface]
    assertEquals(flat.toOption.get.activation, Activation.User(false))
  }

  test(
    "Surface.activation decodes both kinds; quantifier is case-insensitive"
  ) {
    def surface(activation: String): io.circe.Decoder.Result[Surface] =
      parser
        .parse(
          s"""{ "content": { "kind": "component", "card": "x" },
             |  "activation": $activation }""".stripMargin
        )
        .toOption
        .get
        .as[Surface]

    assertEquals(
      surface(
        """{ "kind": "user", "defaultOpen": true }"""
      ).toOption.get.activation,
      Activation.User(defaultOpen = true)
    )
    val cond =
      """{ "kind": "cmp", "property": "state", "op": "eq", "value": "on" }"""
    // Quantifier decodes case-insensitively (wire strings any/none/all)...
    assertEquals(
      surface(
        s"""{ "kind": "state", "condition": $cond, "quantifier": "NONE" }"""
      ).toOption.get.activation,
      Activation.State(
        Predicate.Cmp("state", Op.Eq, Json.fromString("on")),
        Quantifier.None
      )
    )
    // ...defaults to `any` when absent...
    assertEquals(
      surface(
        s"""{ "kind": "state", "condition": $cond }"""
      ).toOption.get.activation,
      Activation.State(
        Predicate.Cmp("state", Op.Eq, Json.fromString("on")),
        Quantifier.Any
      )
    )
    // ...and an unknown quantifier fails the decode (no silent fallback).
    assert(
      surface(
        s"""{ "kind": "state", "condition": $cond, "quantifier": "some" }"""
      ).isLeft
    )
  }

  test(
    "validate rejects a bake group mixing user- and state-activated members"
  ) {
    def member(index: Int, activation: Activation): Surface =
      Surface(
        LayoutNode.Component("ok"),
        bakeInto = Some("c"),
        bakeAs = Some("branch"),
        bakeIndex = Some(index),
        activation = activation
      )
    val state =
      Activation.State(Predicate.Cmp("state", Op.Eq, Json.fromString("on")))
    val mixed = Dashboard(
      cards = Map("ok" -> CardDef("<i></i>")),
      card = LayoutNode.Component("ok"),
      surfaces = Map(
        "a" -> member(0, Activation.User(defaultOpen = true)),
        "b" -> member(1, state)
      )
    )
    assert(
      mixed.validate().exists(_.contains("mixes user- and state-activated")),
      clue = mixed.validate()
    )
    // Homogeneous groups of either mode pass.
    val allState = mixed.copy(surfaces =
      Map("a" -> member(0, state), "b" -> member(1, state))
    )
    assertEquals(allState.validate(), Nil)
    val allUser = mixed.copy(surfaces =
      Map(
        "a" -> member(0, Activation.User(defaultOpen = true)),
        "b" -> member(1, Activation.User())
      )
    )
    assertEquals(allUser.validate(), Nil)
  }

  test("hoistInlineSurfaces lifts the activation object onto the surface") {
    // The lifted-field list carries `activation` (the flat `defaultOpen` is
    // retired — DashboardBuild.surfaceOf drops it).
    val json = parser
      .parse("""
        {
          "cards": {},
          "card": {
            "kind": "component", "card": "ifhost",
            "inlineSurfaces": { "then": {
              "content": { "kind": "component", "card": "card" },
              "bakeInto": "@@NODE_ID@@", "bakeAs": "branch", "bakeIndex": 0,
              "defaultOpen": true,
              "activation": { "kind": "state", "quantifier": "any",
                "condition": { "kind": "cmp", "property": "state", "op": "eq", "value": "on" } }
            } }
          }
        }
      """)
      .toOption
      .get
    val hoisted = DashboardBuild.hoistInlineSurfaces(json).hcursor
    val lifted = hoisted.downField("surfaces").downField("c_then")
    assertEquals(
      lifted.downField("activation").get[String]("kind").toOption,
      Some("state")
    )
    assert(
      lifted.downField("defaultOpen").failed,
      clue = "flat key not dropped"
    )
  }

  test("validate checks card references inside a surface") {
    val d = Dashboard(
      cards = Map("ok" -> CardDef("<i>{{label}}</i>", slots = List("label"))),
      card = LayoutNode.Component(
        "ok",
        slots = Map("label" -> SlotSource(literal = Some("x")))
      ),
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

  test("literalLocator points a transform back at its Pkl source line") {
    val dir = os.temp.dir()
    os.write(
      dir / "dashboard.pkl",
      "import \"lib/components.pkl\" as c\n" +
        "card = (c.entityCard(p)) { transform = \"$round($number($state), 1)\" }\n"
    )
    // The generated dump is skipped even if it contains the literal.
    os.write(
      dir / "lib" / "dump.pkl",
      "x = \"$round($number($state), 1)\"\n",
      createFolders = true
    )

    val locate = SourceEval.literalLocator(
      Set(dir / "dashboard.pkl", dir / "lib" / "dump.pkl")
    )
    assertEquals(
      locate("$round($number($state), 1)"),
      Some("dashboard.pkl:2")
    )
    assertEquals(locate("$nope($)"), None)
  }
}
