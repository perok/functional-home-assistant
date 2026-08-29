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

  test("validate rejects a cell class that is not a plain CSS class token") {
    // Cell classes are string-interpolated into the wrapper's class attribute,
    // so anything beyond [A-Za-z0-9_-]+ must fail the build loudly.
    val d = Dashboard(
      cards = Map("card" -> CardDef("""<div>x</div>""")),
      card = LayoutNode.Component(
        card = "card",
        cell = Some(Cell(classes = List("fh-cols-3", """bad"><script""")))
      )
    )
    val errs = d.validate()
    assert(errs.exists(_.contains("cell class")), clue = errs)
    // The valid token passes; only the bad one is reported.
    assert(!errs.exists(_.contains("'fh-cols-3'")), clue = errs)
  }

  /** The payoff for authoring an id, in the place it is most useful: an inline
    * popup's surface id stops being positional.
    *
    * `openPopupInline` mints `surfaces["<nodeId>_self"]`, so with a derived id
    * the popup is `c_0_self` and moving the button that defines it renames it.
    * Naming the button pins it — which is also what makes it referenceable from
    * elsewhere at all, since `@@NODE_ID@@` only ever resolves to a node's OWN
    * id and so cannot be used to point at someone else's popup.
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

    // Derived: positional, and the second child's index is in the name.
    assertEquals(
      hoist("").hcursor.downField("surfaces").keys.map(_.toList),
      Some(List("c_1_self"))
    )
    // Authored: the name is the author's, and the onclick was spliced with it.
    val named = hoist(""", "id": "quickInfo"""")
    assertEquals(
      named.hcursor.downField("surfaces").keys.map(_.toList),
      Some(List("quickInfo_self"))
    )
    assert(
      named.noSpaces.contains("open quickInfo_self"),
      clue = named.noSpaces
    )
    // Nothing unresolved is left behind either way.
    assertEquals(DashboardBuild.unresolvedTokens(named), Nil)
  }

  /** A candidate set's clause nodes were invisible to this pass — it knew only
    * `children`, and a set holds its nodes under `members[…].clauses[…].node`.
    * So an inline surface inside a set was never hoisted and its `@@NODE_ID@@`
    * reached the browser.
    *
    * That is not an exotic shape: it is what the SHIPPED starter does. Its "Low
    * battery" section renders `c.entityCard` over sensors, a sensor has no
    * domain service, so its default tap is more-info — an INLINE popup (ADR
    * 0016). Any house with a battery sensor under 20 % built a dashboard the
    * server then refused.
    *
    * The member's id carries no clause index, deliberately (`MemberGraph`: only
    * a set NESTED in a clause needs one), so both clauses of a candidate hoist
    * under the same id — see the duplicate-key test below.
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
                  { "node": { "kind": "component", "card": "entityCard",
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
    // Under the id the RENDERER gives that member — `<setId>_<entity>`, with
    // the entity sanitised (`MemberGraph.memberId`). An id this pass invented
    // instead would leave the popup registered where no node looks for it.
    assertEquals(
      hoisted.hcursor.downField("surfaces").keys.map(_.toList),
      Some(List("c_0_sensor_batt_self"))
    )
  }

  test("two clauses of one candidate cannot both own a popup") {
    // The consequence of a member id with no clause index, made LOUD. Merging
    // keeps the last of a repeated key, so the quiet version of this is a popup
    // that opens and shows another clause's content.
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

  /** This pass once read only a BARE ARRAY of children, which the wire also
    * allowed. It did not merely skip the region-keyed form: it STOPPED at such
    * a node, so nothing below a grouped slider's head or members was hoisted
    * and the `@@NODE_ID@@` down there reached the browser verbatim. One wire
    * shape is what removed that class of bug; this holds the depth it costs.
    *
    * Asserted as the PROPERTY — no token survives, wherever the surface sits —
    * rather than on the one id that was wrong, because the same gap swallows
    * every region a card ever grows.
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
    // ...and under the ids the RENDERER derives, which is the other half: a
    // surface registered under an id no node has is as broken as an unspliced
    // token, and just as quiet. The default region contributes only its index
    // (`children` -> `_0`), a named one contributes both (`head` -> `_head_0`)
    // — `LayoutNode.segment`, the one encoding.
    assertEquals(
      hoisted.hcursor.downField("surfaces").keys.map(_.toList.sorted),
      Some(List("c_0_0_self", "c_0_head_0_actions_0_self"))
    )
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

    // surface lifted under "<idBase>_self" (idBase = c_0, the render-time `{{id}}`
    // of card child 0 — the build/hoist id scheme equals LayoutNode.pathId)
    val keys = hoisted.downField("surfaces").keys.map(_.toList).getOrElse(Nil)
    assertEquals(keys, List("c_0_self"), clue = keys)

    // the trigger lost its marker; the NODE token was spliced with the real id
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
    // A state condition is the condition alone — no quantifier beside it, since
    // it names the entities it reads.
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
      // The bake target must declare the region its surfaces name; these
      // dashboards were only ever valid because nothing checked.
      cards = Map(
        "ok" -> CardDef(
          "<i>{{{branch}}}</i>",
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

  test("validate rejects a state condition that names no entity") {
    def dash(condition: Predicate) = Dashboard(
      // The bake target must declare the region its surfaces name; these
      // dashboards were only ever valid because nothing checked.
      cards = Map(
        "ok" -> CardDef(
          "<i>{{{branch}}}</i>",
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
    // A surface supplies no subject, so this used to mean "some entity in the
    // house is on" — never what an author meant. Rejected wherever it sits in
    // the tree, not only at the top.
    for (c <- List(on, Predicate.Not(on), Predicate.And(List(on))))
      assert(
        dash(c).validate().exists(_.contains("unnamed entity")),
        clue = dash(c).validate()
      )

    // What passes: a comparison that names its entity, a count over named
    // candidates (whose per-candidate guards are bound by their candidate), and
    // the vacuously-true empty conjunction an `else` member carries.
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

  /** `bakeAs` names the template var a surface's content is substituted into,
    * which since regions IS a region name — so the two can be checked against
    * each other rather than agreeing by convention.
    *
    * Naming a region the host does not declare fails exactly the way
    * `danglingBakes` describes for a missing NODE: the host renders its wrapper
    * with an empty hole, indistinguishable from a state group that legitimately
    * matched nothing. That is why it is worth a build error.
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
      "<i>{{{branch}}}</i>",
      regions = Map("branch" -> Region(Region.Baked))
    )

    // Named region, wrong name.
    assert(
      dash(hasBranch, "panel").validate().exists(_.contains("no baked region")),
      clue = dash(hasBranch, "panel").validate()
    )
    // A card with no regions at all — the shape the fixtures in this file
    // silently had before this rule existed.
    assert(
      dash(CardDef("<i></i>"), "branch")
        .validate()
        .exists(_.contains("it declares none")),
      clue = dash(CardDef("<i></i>"), "branch").validate()
    )
    // An EAGER region of the right name is still wrong: a surface fills a hole
    // lazily, and `{{#branch}}` is not the hole it substitutes into.
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

  /** An unresolved placeholder is a plain String: it decodes, it validates, and
    * it renders into the DOM verbatim. Nothing used to notice, and the first
    * symptom is a binding that quietly never matches — so the build says so.
    */
  test("unresolvedTokens finds a placeholder the build failed to fill in") {
    def json(s: String) = parser.parse(s).fold(throw _, identity)

    // Nested anywhere, in a value the author composed around it.
    assertEquals(
      DashboardBuild.unresolvedTokens(
        json(
          """{"card":{"slots":{"active":"($_@@NODE_ID@@__pending || $x) == 0"}},
            | "cards":{"a":{"template":"<i class=\"@@CLASSBIND:busySpin:$b@@\"></i>"}}}""".stripMargin
        )
      ),
      List("@@CLASSBIND:busySpin:$b@@", "@@NODE_ID@@").sorted
    )

    // Non-vacuous, and the reason the pattern is anchored on both sides: an
    // ordinary `@` in an onclick is not a token, and neither is a lone `@@`
    // inside prose.
    assertEquals(
      DashboardBuild.unresolvedTokens(
        json("""{"a":"@post('sse/x')","b":"see @@ below","c":42,"d":null}""")
      ),
      Nil
    )
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
        // unterminated string literal -> CEL compile failure
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
    // The generated dump is skipped even if it contains the literal.
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
