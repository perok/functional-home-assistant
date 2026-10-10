package fh.view.build

import fh.view.model.{
  Activation,
  CardDef,
  Cell,
  Dashboard,
  LayoutNode,
  Op,
  Predicate,
  Region,
  SlotSource,
  Surface
}
import fh.view.testkit.TestIds.given
import io.circe.{parser, Json}

class BuildPhaseSuite extends munit.FunSuite {

  test("RegistryDump.transform keys entities by id, areas/floors by name") {
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

    val transformed = RegistryDump.transform(raw).hcursor
    val entities = transformed.downField("entities")

    // Dotless, sanitized keys.
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

    // Keyed by name, slugified.
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
      card = LayoutNode.Component(card = "card")
    )
    val errs = d.validate()
    assert(errs.exists(_.contains("label")), clue = errs)
    assert(!errs.exists(_.contains("missing slots: id")), clue = errs)
  }

  test("validate names a surface's node by the id it renders with") {
    val d = Dashboard(
      cards = Map("card" -> CardDef("<div>x</div>")),
      card = LayoutNode.Component(card = "card"),
      surfaces = Map(
        "detail" -> Surface(
          LayoutNode.Component(
            card = "card",
            regions = Map(
              LayoutNode.DefaultRegion -> List(LayoutNode.Component("missing"))
            )
          )
        )
      )
    )
    val id = LayoutNode.surfacePrefix("detail") + "c_0"
    assertEquals(
      d.validate().filter(_.contains("missing")),
      List(s"surface 'detail': $id: references unknown card 'missing'")
    )
  }

  test("validate rejects a cell class that is not a plain CSS class token") {
    // Interpolated into the wrapper's class attribute, so anything beyond
    // [A-Za-z0-9_-]+ fails the build.
    val d = Dashboard(
      cards = Map("card" -> CardDef("""<div>x</div>""")),
      card = LayoutNode.Component(
        card = "card",
        cell = Some(Cell(classes = List("fh-cols-3", """bad"><script""")))
      )
    )
    val errs = d.validate()
    assert(errs.exists(_.contains("cell class")), clue = errs)
    assert(!errs.exists(_.contains("'fh-cols-3'")), clue = errs)
  }

  /** `openPopupInline` mints `surfaces["<nodeId>_self"]`, so with a derived id
    * moving the button renames the popup. Naming the button pins it, and makes
    * it referenceable at all: `@@NODE_ID@@` resolves only to a node's own id.
    */
  test("hoistInlineSurfaces keys an inline surface off an AUTHORED node id") {
    def hoist(idField: String) = DashboardBuild
      .hoistInlineSurfaces(
        parser
          .parse(s"""
            { "cards": {}, "card": {
                "kind": "component", "card": "fhcol",
                "regions": { "children": [
                  { "kind": "component", "card": "card" },
                  { "kind": "component", "card": "button"$idField,
                    "slots": { "onclick": "open @@NODE_ID@@_self" },
                    "inlineSurfaces": { "self": {
                      "content": { "kind": "component", "card": "card" } } } }
                ] } } }
          """)
          .toOption
          .get
      )

    assertEquals(
      hoist("").hcursor.downField("surfaces").keys.map(_.toList),
      Some(List("c_1_self"))
    )
    val named = hoist(""", "id": "quickInfo"""")
    assertEquals(
      named.hcursor.downField("surfaces").keys.map(_.toList),
      Some(List("quickInfo_self"))
    )
    assert(
      named.noSpaces.contains("open quickInfo_self"),
      clue = named.noSpaces
    )
    assertEquals(DashboardBuild.unresolvedTokens(named), Nil)
  }

  /** A set holds its nodes under `members[…].clauses[…].node`, which this pass
    * did not walk, so an inline surface in a set kept its `@@NODE_ID@@`. The
    * shipped starter's "Low battery" section does exactly that (a sensor's
    * default tap is an inline popup, ADR 0016), so any house with a battery
    * under 20 % got a refused dashboard.
    *
    * A member id has no clause index (only a set nested in a clause needs one),
    * so both clauses hoist under one id; see the duplicate-key test.
    */
  test("hoistInlineSurfaces descends a candidate set's clauses") {
    val json = parser
      .parse("""
        { "cards": {}, "card": {
            "kind": "component", "card": "fhcol",
            "regions": { "children": [
              { "kind": "set",
                "candidates": ["sensor.batt"],
                "members": { "sensor.batt": { "clauses": [
                  { "node": { "kind": "component", "card": "tile",
                      "slots": { "onclick": "open @@NODE_ID@@_self" },
                      "inlineSurfaces": { "self": {
                        "content": { "kind": "component", "card": "card" } } } } }
                ] } } }
            ] } } }
      """)
      .toOption
      .get
    val hoisted = DashboardBuild.hoistInlineSurfaces(json)
    assertEquals(
      DashboardBuild.unresolvedTokens(hoisted),
      Nil,
      clue = hoisted.noSpaces
    )
    // Under the id the renderer gives the member (`MemberGraph.memberId`), or
    // the popup is registered where no node looks.
    assertEquals(
      hoisted.hcursor.downField("surfaces").keys.map(_.toList),
      Some(List("c_0_sensor_batt_self"))
    )
  }

  test("two clauses of one candidate cannot both own a popup") {
    // Merging keeps the last repeated key, so quietly this is a popup showing
    // another clause's content.
    val json = parser
      .parse("""
        { "cards": {}, "card": {
            "kind": "component", "card": "fhcol",
            "regions": { "children": [
              { "kind": "set",
                "candidates": ["sensor.batt"],
                "members": { "sensor.batt": { "clauses": [
                  { "node": { "kind": "component", "card": "a",
                      "inlineSurfaces": { "self": {
                        "content": { "kind": "component", "card": "card" } } } } },
                  { "node": { "kind": "component", "card": "b",
                      "inlineSurfaces": { "self": {
                        "content": { "kind": "component", "card": "card" } } } } }
                ] } } }
            ] } } }
      """)
      .toOption
      .get
    val e = intercept[fh.view.FHError](DashboardBuild.hoistInlineSurfaces(json))
    assert(
      e.getMessage.contains("c_0_sensor_batt_self"),
      clue = e.getMessage
    )
  }

  /** This pass once stopped at a region-keyed node, so nothing below a grouped
    * slider's head or members was hoisted. Asserted as the property, no token
    * survives anywhere, since the same gap swallows every region a card grows.
    */
  test("hoistInlineSurfaces descends every region, at every depth") {
    val json = parser
      .parse("""
        { "cards": {}, "card": {
            "kind": "component", "card": "fhcol",
            "regions": { "children": [
              { "kind": "component", "card": "slider",
                "regions": {
                  "head": [
                    { "kind": "component", "card": "sliderHead",
                      "regions": {
                        "actions": [
                          { "kind": "component", "card": "sliderAction",
                            "slots": { "onclick": "open @@NODE_ID@@_self" },
                            "inlineSurfaces": { "self": {
                              "content": { "kind": "component", "card": "card" } } } }
                        ] } }
                  ],
                  "children": [
                    { "kind": "component", "card": "slider",
                      "slots": { "onclick": "open @@NODE_ID@@_self" },
                      "inlineSurfaces": { "self": {
                        "content": { "kind": "component", "card": "card" } } } }
                  ] } }
            ] } } }
      """)
      .toOption
      .get
    val hoisted = DashboardBuild.hoistInlineSurfaces(json)
    assertEquals(
      DashboardBuild.unresolvedTokens(hoisted),
      Nil,
      clue = hoisted.noSpaces
    )
    // Registered under an id no node has is as broken, and as quiet, as an
    // unspliced token. The default region contributes its index (`_0`), a named
    // one both (`_head_0`): `LayoutNode.segment`.
    assertEquals(
      hoisted.hcursor.downField("surfaces").keys.map(_.toList.sorted),
      Some(List("c_0_0_self", "c_0_head_0_actions_0_self"))
    )
  }

  private def node(fields: String, children: String*): String =
    s"""{ "kind": "component", "card": "x"$fields""" +
      (if (children.isEmpty) ""
       else s""", "regions": { "children": [${children.mkString(",")}] }""") +
      " }"

  private def tokenAt(name: String): String =
    s""", "slots": { "g": "${DashboardBuild.declarerToken(name)}" }"""

  private def hoistCard(card: String, surfaces: String = "{}"): Json =
    DashboardBuild.hoistInlineSurfaces(
      parser
        .parse(s"""{ "cards": {}, "card": $card, "surfaces": $surfaces }""")
        .toOption
        .get
    )

  // Every value of slot `g`, by the node's position in the default regions.
  private def spliced(j: Json): List[String] =
    j.findAllByKey("g").flatMap(_.asString)

  test("a declarer token is the nearest declaring ancestor's id") {
    val window = """, "vars": { "window": "24h" }"""
    val hoisted = hoistCard(
      node(
        window,
        node(tokenAt("window")),
        // Shadowing: the inner declaration wins for its own subtree only.
        node(window, node(tokenAt("window"))),
        // A node declaring nothing is transparent.
        node("", node(tokenAt("window")))
      )
    )
    assertEquals(spliced(hoisted), List("c", "c_1", "c"))
    assertEquals(DashboardBuild.unresolvedTokens(hoisted), Nil)
  }

  test("a declarer token with no declarer above it fails the build") {
    // Unspliced it would ship as a literal, and every press would 404.
    val e = intercept[fh.view.FHError](
      hoistCard(node(""", "vars": { "other": "1" }""", node(tokenAt("window"))))
    )
    assert(e.getMessage.contains("c_0"), clue = e.getMessage)
    assert(e.getMessage.contains("'window'"), clue = e.getMessage)
  }

  test("a surface does not see the declarations of the page that opens it") {
    // ADR 0033: a surface is its own scope root, since one content can be
    // shown from more than one place.
    val opener = node(
      """, "vars": { "window": "24h" }, "inlineSurfaces": { "self": """ +
        s"""{ "content": ${node(tokenAt("window"))} } }"""
    )
    val e = intercept[fh.view.FHError](hoistCard(node("", opener)))
    assert(e.getMessage.contains("'window'"), clue = e.getMessage)
    // Its own declaration is in scope, under the surface's ids.
    val registered = hoistCard(
      node(""),
      s"""{ "detail": { "content": ${node(
          """, "vars": { "window": "24h" }""",
          node(tokenAt("window"))
        )} } }"""
    )
    assertEquals(
      spliced(registered),
      List(LayoutNode.surfacePrefix("detail") + "c")
    )
  }

  test("a candidate set's clause names a declarer outside the set") {
    // The declarer's id is static even though the member's is not, so this is
    // allowed where a READ inside a set is refused.
    val set =
      """{ "kind": "set", "candidates": ["sensor.a"], "members": { "sensor.a": """ +
        s"""{ "clauses": [ { "node": ${node(tokenAt("window"))} } ] } } }"""
    val hoisted = hoistCard(node(""", "vars": { "window": "24h" }""", set))
    assertEquals(spliced(hoisted), List("c"))
  }

  test("hoistInlineSurfaces lifts an inline surface and splices the node id") {
    // The onclick already references the future id via the node token; the
    // hoist lifts the content and splices the id.
    val json = parser
      .parse("""
        {
          "cards": {},
          "card": {
            "kind": "component", "card": "fhcol",
            "regions": { "children": [
              { "kind": "component", "card": "button",
                "params": { "label": "More" },
                "entities": [],
                "slots": { "onclick": { "entity": "",
                  "transform": "\"@post('sse/surface/open/@@NODE_ID@@_self')\"" } },
                "inlineSurfaces": { "self": {
                  "content": { "kind": "component", "card": "card" } } } }
            ] }
          }
        }
      """)
      .toOption
      .get
    val hoisted = DashboardBuild.hoistInlineSurfaces(json).hcursor

    // idBase = c_0, the render-time `{{id}}` of child 0: the hoist id scheme
    // equals `LayoutNode.pathId`.
    val keys = hoisted.downField("surfaces").keys.map(_.toList).getOrElse(Nil)
    assertEquals(keys, List("c_0_self"), clue = keys)

    val trigger = hoisted
      .downField("card")
      .downField("regions")
      .downField("children")
      .downN(0)
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
    // As c.tabs emits: a container with inline surfaces and triggers
    // referencing the future ids. `panelHost` is an arbitrary string param
    // showing the splice reaches every string leaf; `bakeInto`/`bakeAs` are the
    // real shared-host fields.
    val json = parser
      .parse("""
        {
          "cards": {},
          "card": {
            "kind": "component", "card": "tabs", "entities": [], "slots": {},
            "params": { "initial": "@@NODE_ID@@_0", "panelHost": "panel_@@NODE_ID@@", "sig": "tab_@@NODE_ID@@" },
            "regions": { "children": [
              { "kind": "component", "card": "button", "entities": [],
                "params": { "active": "$tab_@@NODE_ID@@ == '@@NODE_ID@@_0'" },
                "slots": { "onclick": { "entity": "",
                  "transform": "\"@post('sse/surface/open/@@NODE_ID@@_0')\"" } } }
            ] },
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

    // One shared bakeInto, so the same hostId.
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

    val first = node.downField("regions").downField("children").downN(0)
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
    // The retired flat `defaultOpen` is ignored while Pkl still emits it; its
    // effect survives through resolveActive's index-0 fallback.
    val flat = parser
      .parse(
        """{ "content": { "kind": "component", "card": "x" }, "defaultOpen": true }"""
      )
      .toOption
      .get
      .as[Surface]
    assertEquals(flat.toOption.get.activation, Activation.User(false))
  }

  test("Surface.activation decodes both kinds") {
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
    // No quantifier: the condition names the entities it reads.
    val cond =
      """{ "kind": "cmp", "property": "state", "op": "eq", "value": "on",
         |  "entity": "light.a" }""".stripMargin
    assertEquals(
      surface(
        s"""{ "kind": "state", "condition": $cond }"""
      ).toOption.get.activation,
      Activation.State(
        Predicate
          .Cmp("state", Op.Eq, Json.fromString("on"), entity = Some("light.a"))
      )
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
    val state = Activation.State(
      Predicate
        .Cmp("state", Op.Eq, Json.fromString("on"), entity = Some("light.a"))
    )
    val mixed = Dashboard(
      // The bake target must declare the region its surfaces name.
      cards = Map(
        "ok" -> CardDef(
          "<i>{{#branch}}{{{html}}}{{/branch}}</i>",
          regions = Map("branch" -> Region(Region.Baked))
        )
      ),
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

  test("validate rejects a state condition that names no entity") {
    def dash(condition: Predicate) = Dashboard(
      // The bake target must declare the region its surfaces name.
      cards = Map(
        "ok" -> CardDef(
          "<i>{{#branch}}{{{html}}}{{/branch}}</i>",
          regions = Map("branch" -> Region(Region.Baked))
        )
      ),
      card = LayoutNode.Component("ok"),
      surfaces = Map(
        "a" -> Surface(
          LayoutNode.Component("ok"),
          bakeInto = Some("c"),
          bakeAs = Some("branch"),
          bakeIndex = Some(0),
          activation = Activation.State(condition)
        )
      )
    )
    val on = Predicate.Cmp("state", Op.Eq, Json.fromString("on"))
    // A surface supplies no subject, so this would mean "some entity in the
    // house is on". Rejected anywhere in the tree.
    for (c <- List(on, Predicate.Not(on), Predicate.And(List(on))))
      assert(
        dash(c).validate().exists(_.contains("unnamed entity")),
        clue = dash(c).validate()
      )

    // A candidate's guards are bound by their candidate; the empty conjunction
    // is an `else`.
    val named = on.copy(entity = Some("light.a"))
    val count = Predicate.Count(
      candidates = List("light.a"),
      when = Map("light.a" -> on),
      op = Op.Gt,
      value = Json.fromInt(0)
    )
    for (c <- List(named, count, Predicate.And(Nil), Predicate.Or(List(count))))
      assertEquals(dash(c).validate(), Nil, clue = c)
  }

  /** A missing region fails as `danglingBakes` describes for a missing node: an
    * empty hole, indistinguishable from a state group that matched nothing.
    */
  test("validate rejects a surface baking into a region its card lacks") {
    def dash(hostCard: CardDef, as: String) = Dashboard(
      cards = Map("host" -> hostCard),
      card = LayoutNode.Component("host"),
      surfaces = Map(
        "s" -> Surface(
          LayoutNode.Component("host"),
          bakeInto = Some("c"),
          bakeAs = Some(as),
          bakeIndex = Some(0),
          activation = Activation.User(defaultOpen = true)
        )
      )
    )
    val hasBranch = CardDef(
      "<i>{{#branch}}{{{html}}}{{/branch}}</i>",
      regions = Map("branch" -> Region(Region.Baked))
    )

    assert(
      dash(hasBranch, "panel").validate().exists(_.contains("no baked region")),
      clue = dash(hasBranch, "panel").validate()
    )
    assert(
      dash(CardDef("<i></i>"), "branch")
        .validate()
        .exists(_.contains("it declares none")),
      clue = dash(CardDef("<i></i>"), "branch").validate()
    )
    // A surface fills its hole lazily and an eager region is filled by the
    // node's children; the fills differ by declared kind, not spelling.
    assert(
      dash(
        CardDef(
          "<i>{{#branch}}{{{html}}}{{/branch}}</i>",
          regions = Map("branch" -> Region())
        ),
        "branch"
      ).validate().exists(_.contains("no baked region")),
      clue = "an eager region must not satisfy a bakeAs"
    )
    // Non-vacuous.
    assertEquals(dash(hasBranch, "branch").validate(), Nil)
  }

  /** A placeholder decodes, validates and renders verbatim; the first symptom
    * is a binding that never matches, so the build says so.
    */
  test("unresolvedTokens finds a placeholder the build failed to fill in") {
    def json(s: String) = parser.parse(s).fold(throw _, identity)

    assertEquals(
      DashboardBuild.unresolvedTokens(
        json(
          """{"card":{"slots":{"active":"($_@@NODE_ID@@__pending || $x) == 0"}},
            | "cards":{"a":{"template":"<i class=\"@@CLASSBIND:busySpin:$b@@\"></i>"}}}""".stripMargin
        )
      ),
      List("@@CLASSBIND:busySpin:$b@@", "@@NODE_ID@@").sorted
    )

    // Anchored on both sides: an ordinary `@` in an onclick is not a token, nor
    // a lone `@@` in prose.
    assertEquals(
      DashboardBuild.unresolvedTokens(
        json("""{"a":"@post('sse/x')","b":"see @@ below","c":42,"d":null}""")
      ),
      Nil
    )
  }

  test("hoistInlineSurfaces lifts the activation object onto the surface") {
    // `DashboardBuild.surfaceOf` drops the retired flat `defaultOpen`.
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
              "activation": { "kind": "state",
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
      dir / "site.pkl",
      "import \"lib/components.pkl\" as c\n" +
        "card = (c.entityCard(p)) { transform = \"str(math.round(num(state)))\" }\n"
    )
    os.write(
      dir / "lib" / "dump.pkl",
      "x = \"str(math.round(num(state)))\"\n",
      createFolders = true
    )

    val locate = SourceEval.literalLocator(
      Set(dir / "site.pkl", dir / "lib" / "dump.pkl")
    )
    assertEquals(
      locate("str(math.round(num(state)))"),
      Some("site.pkl:2")
    )
    assertEquals(locate("nope(state)"), None)
  }
}
