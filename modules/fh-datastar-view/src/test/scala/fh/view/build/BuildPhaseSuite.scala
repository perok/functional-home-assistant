package fh.view.build

import fh.view.testkit.Sets

import fh.view.testkit.Slots

import fh.view.testkit.TestCard
import fh.view.testkit.Conds
import fh.view.testkit.{FixtureEntity, PklFixture}

import fh.view.model.{
  Part,
  CardDef,
  Case,
  Cell,
  Choice,
  Dashboard,
  Fill,
  IdKind,
  LayoutNode,
  Lit,
  Op,
  SignalBind,
  Slot,
  SlotType,
  Step,
  Value,
  ValueExpr,
  TemplateSlot
}
import fh.view.testkit.DashboardBuilders.classes
import io.circe.{parser, Json}

import scala.collection.immutable.ListMap

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
        "card" -> TestCard(
          """<div id="{{id}}">{{label}}</div>"""
        )
      ),
      card = LayoutNode.Component(card = "card")
    )
    val errs = d.validate()
    assert(errs.exists(_.contains("label")), clue = errs)
    assert(!errs.exists(_.contains("missing slots: id")), clue = errs)
  }

  private def oneCard(
      template: String,
      slots: Map[String, TemplateSlot],
      regions: Set[String] = Set.empty,
      filled: Map[String, List[LayoutNode]] = Map.empty
  ): List[String] =
    Dashboard(
      cards = Map(
        "card" -> TestCard(template, regions = regions),
        "leaf" -> TestCard("<i></i>")
      ),
      card = LayoutNode.Component(
        "card",
        slots = Slots.values(slots) ++ Slots.nodes(filled)
      )
    ).validate()

  private val text = TemplateSlot(ValueExpr.literal("x"))

  test("validate: a slot no template name reads is reported") {
    val errs =
      oneCard("<b>{{label}}</b>", Map("label" -> text, "lable" -> text))
    assert(errs.exists(_.contains("never reads slots: lable")), clue = errs)
  }

  test("validate: a misspelt section leaves its slot unread") {
    val errs = oneCard("{{#rond}}r{{/rond}}", Map("round" -> text))
    assert(errs.exists(_.contains("never reads slots: round")), clue = errs)
  }

  test("validate: a view reads its slot") {
    val errs = oneCard("<b {{{label.bind}}}></b>", Map("label" -> text))
    assertEquals(errs, Nil)
  }

  test("validate: an unknown view is named") {
    val errs = oneCard(
      "<b {{{label.bnid}}}>{{label}}</b>",
      Map("label" -> text)
    )
    assert(errs.exists(_.contains("reads 'label.bnid'")), clue = errs)
  }

  test("validate: a binding inside a section on its own value is rejected") {
    def errs(tpl: String) = oneCard(tpl, Map("icon" -> text))
    assert(
      errs("{{#icon}}<i {{{icon.bind}}}></i>{{/icon}}")
        .exists(_.contains("guard it with {{#icon.has}}")),
      clue = errs("{{#icon}}<i {{{icon.bind}}}></i>{{/icon}}")
    )
    assertEquals(
      errs("{{#icon.has}}<i {{{icon.bind}}}>{{icon}}</i>{{/icon.has}}"),
      Nil
    )
  }

  test("validate: x.signal is refused outside a section on x.signal") {
    val unguarded = "places {{x.signal}} outside {{#x.signal}}"
    // The rule reads the template alone: a signal slot is held to it too.
    val signal = TemplateSlot(
      ValueExpr(Value.Entity(Some("light.a"))),
      signal = Some(SignalBind.Handler)
    )
    def placed(tpl: String) =
      oneCard(s"<b>{{x}}</b>$tpl", Map("x" -> signal))
        .filter(_.contains(unguarded))
    val write = "<i data-on:x=\"${{x.signal}} = 1\"></i>"
    val under = (s: String) => s"{{$s}}$write{{/${s.drop(1)}}}"
    assertEquals(placed(write).length, 1)
    assertEquals(placed(under("#x.signal")), Nil)
    assertEquals(placed(s"{{#flag}}${under("#x.signal")}{{/flag}}"), Nil)
    // Only its own, not inverted: anything else could still print nothing.
    for s <- List("#flag", "#x.has", "#x", "^x.signal", "#y.signal") do
      assertEquals(placed(under(s)).length, 1, clue = s)
  }

  test("validate: a slot name with a '.' is rejected") {
    val errs = oneCard("<b>{{a}}</b>", Map("a" -> text, "a.b" -> text))
    assert(errs.exists(_.contains("slot 'a.b' has a '.'")), clue = errs)
  }

  test("validate: a region is placed once, as {{{name}}}, outside sections") {
    def errs(tpl: String) = oneCard(
      tpl,
      Map.empty,
      regions = Set("children")
    )
    assertEquals(errs("<div>{{{children}}}</div>"), Nil)
    for tpl <- List(
        "{{#children}}{{{html}}}{{/children}}",
        "<div>{{children}}</div>",
        "{{#busy}}{{{children}}}{{/busy}}"
      )
    do
      assert(
        errs(tpl).exists(_.contains("write {{{children}}}")),
        clue = (tpl, errs(tpl))
      )
    assert(
      errs("{{{children}}}{{{children}}}").exists(_.contains("2 times")),
      clue = errs("{{{children}}}{{{children}}}")
    )
  }

  test("validate: a slot named like its card's region is rejected") {
    val errs = oneCard(
      "<div>{{{children}}}</div>",
      Map("children" -> text),
      regions = Set("children")
    )
    assert(errs.exists(_.contains("named like")), clue = errs)
  }

  test("validate: a node filling a region its card lacks is rejected") {
    val errs = oneCard(
      "<div></div>",
      Map.empty,
      filled = Map("children" -> List(LayoutNode.Component("leaf")))
    )
    assert(errs.exists(_.contains("fills region 'children'")), clue = errs)
  }

  test("validate: a text slot yielding a list is rejected; dyn passes") {
    def cel(src: String) =
      oneCard(
        "{{v}}",
        Map(
          "v" -> TemplateSlot(
            ValueExpr(
              Value.Entity(Some("sensor.t"), Part.Whole),
              List(Step.Cel(src))
            )
          )
        )
      )
    assert(
      cel("[1, 2]").exists(_.contains("yields a list")),
      clue = cel("[1, 2]")
    )
    assert(
      cel("{'a': 1}").exists(_.contains("yields a map")),
      clue = cel("{'a': 1}")
    )
    assertEquals(cel("value.attr['x']"), Nil)
    assertEquals(cel("num(value.state) * 2.0"), Nil)
  }

  test("validate: a text step takes text, a number or dyn, nothing else") {
    val x: Value.Operand = Value.Literal(Lit.Text("x"))
    val e = Some("sensor.t")
    def errs(source: Value, step: Step) =
      oneCard("{{v}}", Map("v" -> TemplateSlot(ValueExpr(source, List(step)))))
    for step <- List(Step.Prefix(x), Step.Suffix(x), Step.WithUnit(x)) do
      for src <- List(
          Value.Entity(e, Part.State),
          Value.Entity(e, Part.Attr("x")),
          Value.Literal(Lit.Whole(2))
        )
      do assertEquals(errs(src, step), Nil, clue = (step, src))
      for src <- List(
          Value.Entity(e, Part.Whole),
          Value.Present(Nil, Map.empty),
          Value.Literal(Lit.Bool(true))
        )
      do
        assert(
          errs(src, step).exists(_.contains("takes a number or text")),
          clue = (step, src, errs(src, step))
        )
  }

  test("validate: a literal is checked against its slot's type") {
    def errs(l: Lit, t: SlotType) =
      oneCard(
        if t == SlotType.Bool then "{{#v}}on{{/v}}" else "{{v}}",
        Map("v" -> TemplateSlot(ValueExpr(Value.Literal(l)), `type` = t))
      )
    // A bool literal becomes text at a text slot, as a bool reading does.
    for (l, t) <- List(
        Lit.Bool(true) -> SlotType.Bool,
        Lit.Bool(true) -> SlotType.Text,
        Lit.Text("x") -> SlotType.Text,
        Lit.Whole(2) -> SlotType.Text
      )
    do assertEquals(errs(l, t), Nil, clue = (l, t))
    for (l, t) <- List(
        Lit.Text("x") -> SlotType.Bool,
        Lit.Whole(1) -> SlotType.Bool
      )
    do assert(errs(l, t).exists(_.contains("literal, which a")), clue = (l, t))
  }

  test("validate rejects a cell class that is not a plain CSS class token") {
    // Interpolated into the wrapper's class attribute, so anything beyond
    // [A-Za-z0-9_-]+ fails the build.
    val d = Dashboard(
      cards = Map("card" -> TestCard("""<div>x</div>""")),
      card = LayoutNode.Component(
        card = "card",
        cell = Some(classes("fh-cols-3", """bad"><script"""))
      )
    )
    val errs = d.validate()
    assert(errs.exists(_.contains("cell class")), clue = errs)
    assert(!errs.exists(_.contains("'fh-cols-3'")), clue = errs)
  }

  test("validate takes a set's cell as any card's: classes, and readings") {
    // A set is a card's slot, so its root binds a reading as any root does.
    val reading = ValueExpr(Value.Entity(Some("light.a")))
    val on = ValueExpr(
      Value.Entity(Some("light.a")),
      List(Step.Compare(Op.Eq, Value.Literal(Lit.Text("on"))))
    )
    val cell = Cell(
      classes = classes("fh-cols-3").classes + ("fh-on" -> on),
      styles = ListMap("--x" -> reading)
    )
    val errs = Dashboard(
      cards = Sets.withCard(Map("card" -> TestCard("<i></i>"))),
      card = Sets.node(cell = Some(cell))
    ).validate()
    assertEquals(errs, Nil)
  }

  test(
    "validate refuses a set beside other content, a set as a member, an otherwise"
  ) {
    val tile = LayoutNode.Component("card")
    val beside = Dashboard(
      cards = Map(
        Sets.Card -> CardDef(
          "<div{{{cell}}}>{{{children}}}{{{more}}}</div>",
          regions = Set(Sets.SlotName, "more")
        ),
        "card" -> TestCard("<i></i>")
      ),
      card = Sets.node(
        List("light.a"),
        Map("light.a" -> Sets.Member(List(Sets.Clause(None, tile))))
      )
    ).validate()
    assert(beside.exists(_.contains("not its card's whole template")), beside)

    val inner = Sets.node(List("light.b"))
    val nested = Dashboard(
      cards = Sets.withCard(Map("card" -> TestCard("<i></i>"))),
      card = Sets.node(
        List("light.a"),
        Map("light.a" -> Sets.Member(List(Sets.Clause(None, inner))))
      )
    ).validate()
    assert(nested.exists(_.contains("is itself a set")), nested)

    val on = ValueExpr(
      Value.Entity(Some("light.a")),
      List(Step.Compare(Op.Eq, Value.Literal(Lit.Text("on"))))
    )
    val otherwise = Dashboard(
      cards = Sets.withCard(Map("card" -> TestCard("<i></i>"))),
      card = LayoutNode.Component(
        Sets.Card,
        Map(
          Sets.SlotName -> Slot.Nodes(
            Fill.Set(
              List("light.a"),
              Map(
                "light.a" -> Choice.Chosen(
                  on,
                  List(Case(Lit.Bool(true), tile)),
                  Some(tile)
                )
              )
            )
          )
        )
      )
    ).validate()
    assert(otherwise.exists(_.contains("has an otherwise")), otherwise)
  }

  test("validate refuses a literal cell style, which nothing would write") {
    // A literal class is written as it is; a literal style is no cell slot.
    val cell = Cell(styles = ListMap("--x" -> ValueExpr.literal("1")))
    val errs = Dashboard(
      cards = Map("card" -> TestCard("<i></i>")),
      card = LayoutNode.Component("card", cell = Some(cell))
    ).validate()
    assert(
      errs.exists(_.contains("cellStyle '--x' must read live state")),
      errs
    )
  }

  private val ok = Map(
    "ok" -> TestCard("<i>{{{branch}}}</i>", regions = Set("branch"))
  )

  private def choosing(branch: Slot, vars: List[Dashboard.Declaration] = Nil) =
    LayoutNode.Component("ok", Map("branch" -> branch), vars = vars)

  test("a choice over a variable needs a case or an otherwise at its default") {
    // Declared at a value only the otherwise takes.
    def dash(otherwise: Boolean) = PklFixture.snippet(
      s"""local window = c.variable("window")
         |card = (c.column) {
         |  vars { window.defaultingTo("24h") }
         |  children {
         |    groupMod.group("div", "", nodeMod.match(window.value, new Mapping { ["30d"] = t.label("x") })${
          if (otherwise) """.otherwise(t.label("y"))""" else ""
        })
         |  }
         |}
         |""".stripMargin
    )
    assertEquals(dash(otherwise = true).validate(), Nil)
    assert(
      dash(otherwise = false)
        .validate()
        .exists(_.contains("declared '24h', which no case takes")),
      clue = dash(otherwise = false).validate()
    )
  }

  test("validate rejects a choice over state that names no entity") {
    def dash(condition: ValueExpr) = Dashboard(
      cards = ok,
      card = choosing(Slots.iff(condition, LayoutNode.Component("ok")))
    )
    val on = Conds.cmp("state", Op.Eq, Json.fromString("on"))
    // A choice supplies no subject, so this would mean "some entity in the
    // house is on". Rejected anywhere in the tree.
    for (c <- List(on, Conds.not(on), Conds.and(List(on))))
      assert(
        dash(c).validate().exists(_.contains("unnamed entity")),
        clue = dash(c).validate()
      )

    // A candidate's guards are bound by their candidate; the empty conjunction
    // is an `else`.
    val named =
      Conds.cmp("state", Op.Eq, Json.fromString("on"), Some("light.a"))
    val count = Conds.count(
      candidates = List("light.a"),
      when = Map("light.a" -> on),
      op = Op.Gt,
      value = Json.fromInt(0)
    )
    // CEL decides as a condition does: a step on a read, or a source over its
    // own inputs.
    val celStep =
      ValueExpr(Value.Entity(Some("light.a")), List(Step.Cel("value == 'on'")))
    val celSource = ValueExpr(
      Value.Cel(
        "x == 'on'",
        Map("x" -> ValueExpr(Value.Entity(Some("light.a"))))
      )
    )
    for (
      c <- List(
        named,
        count,
        Conds.and(Nil),
        Conds.or(List(count)),
        celStep,
        celSource,
        Conds.and(List(celStep, named))
      )
    )
      assertEquals(dash(c).validate(), Nil, clue = c)
  }

  test("validate refuses a set of areas whose guard reads the area itself") {
    val card = Sets.withCard(Map("t" -> TestCard("<i>t</i>")))
    def rooms(guard: ValueExpr) = Dashboard(
      cards = card,
      card = Sets.node(
        candidates = List("soverom"),
        members = Map(
          "soverom" -> Sets.Member(
            List(Sets.Clause(Some(guard), LayoutNode.Component("t")))
          )
        ),
        of = IdKind.Area
      )
    )
    def on(of: Option[String]) = ValueExpr(
      Value.Entity(of),
      List(Step.Compare(Op.Eq, Value.Literal(Lit.Text("on"))))
    )
    assertEquals(rooms(on(Some("light.disco"))).validate(), Nil)
    val itself = rooms(on(None)).validate()
    assert(itself.exists(_.contains("reads its member")), clue = itself)
  }

  test("validate refuses a clause or a choice's key that does not decide") {
    // A wire Pkl never writes, so only a hand-built one reaches this.
    val text = ValueExpr(Value.Entity(Some("light.a")))
    val viaCel = ValueExpr(Value.Cel("'on'"))
    def clause(when: ValueExpr) = Dashboard(
      cards = Sets.withCard(ok),
      card = Sets.node(
        candidates = List("light.a"),
        members = Map(
          "light.a" -> Sets.Member(
            List(Sets.Clause(Some(when), LayoutNode.Component("ok")))
          )
        )
      )
    )
    val set = clause(text)
    assert(
      set
        .validate()
        .exists(_.contains("a clause of 'light.a' does not decide")),
      clue = set.validate()
    )
    val celClause =
      ValueExpr(Value.Entity(Some("light.a")), List(Step.Cel("value == 'on'")))
    assertEquals(clause(celClause).validate(), Nil)
    assert(
      clause(viaCel)
        .validate()
        .exists(_.contains("a clause of 'light.a' does not decide")),
      clue = clause(viaCel).validate()
    )
    // An If's case is `true`, which a text `by` never reads.
    val choice = Dashboard(
      cards = ok,
      card = choosing(Slots.iff(viaCel, LayoutNode.Component("ok")))
    )
    assert(
      choice.validate().exists(_.contains("is chosen by a text")),
      clue = choice.validate()
    )
  }

  test("validate rejects a choice in a slot its card does not declare") {
    val library = PklFixture.snippet(
      """card = c.iff(q.entity(dump.entities.alarm_h).stateIs("armed")).then(t.label("x"))""",
      FixtureEntity("alarm.h", "armed")
    )
    // The shipped If with its card's regions dropped.
    def dash(keep: Boolean) = library.copy(
      cards = library.cards.map((n, d) =>
        n -> (if (keep || n != "ifhost") d
              else d.copy(regions = Set.empty))
      )
    )
    assert(
      dash(keep = false)
        .validate()
        .exists(_.contains("fills region 'branch'")),
      clue = dash(keep = false).validate()
    )
    // Non-vacuous.
    assertEquals(dash(keep = true).validate(), Nil)
  }

  /** A placeholder decodes, validates and renders verbatim; the first symptom
    * is a binding that never matches, so the build says so.
    */
  test(
    "validate reports a slot whose transform fails to compile (blocks load)"
  ) {
    val d = Dashboard(
      cards = Map("card" -> TestCard("<span>{{state}}</span>")),
      card = LayoutNode.Component(
        "card",
        slots = Slots.values(
          Map(
            "state" -> TemplateSlot(
              ValueExpr(
                Value.Entity(Some("e.x"), Part.Whole),
                List(Step.Cel("'unclosed"))
              )
            )
          )
        )
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
