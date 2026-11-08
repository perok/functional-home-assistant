package fh.view.runtime

import fh.view.testkit.Conds

import fh.view.build.DashboardBuild
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  Lit,
  Part,
  Ready,
  Step,
  Value,
  ValueExpr,
  TemplateSlot,
  ValueType
}
import io.circe.Json

/** How a dashboard's slug reaches the action URL a tap builds (ADR 0023). A
  * module does not know its own slug, so the renderer binds `dashboard_slug`.
  * What matters is when it is settled: before validation, so a `Validated` is
  * final, since a compiled tap URL derives from it.
  */
class TransformsSuite extends munit.CatsEffectSuite {

  test("the opted-in state tier renders exactly what CEL would") {
    // The simple tier is entered by a structured transform, never by
    // recognising spelling (ADR 0028), and is safe only while it renders every
    // state shape like the engine, so CEL itself is the oracle.
    val states = List(
      "on",
      "off",
      "21.44",
      "",
      "unavailable",
      "ø 😀",
      "locks path with \"quotes\""
    )
    val cel = programOf(
      dashboard("kitchen").copy(
        card = LayoutNode.Component(
          card = "c",
          templateSlots = Map(
            "onclick" -> TemplateSlot(
              ValueExpr(
                Value.Entity(Some("sensor.a"), Part.Whole),
                List(Step.Cel("value.state"))
              )
            )
          )
        )
      ),
      "value.state"
    )
    states.foreach { state =>
      val entity = EntityState("sensor.a", state, Map.empty[String, Json])
      assertEquals(
        Steps.text(Steps.read(Part.State, entity)),
        Cel.run(cel, entity, "dashboard"),
        clue = state
      )
    }
  }

  test("CEL reads attr through its own map type as it reads a plain map") {
    // `javaAttributes` is cel-java's internal `MutableMapValue`, so an upgrade
    // that changes how CEL reads one shows here.
    val attrs = Map(
      "brightness" -> Json.fromInt(180),
      "rgb_color" -> Json.arr(Json.fromInt(255), Json.fromInt(170)),
      "xy_color" -> Json.arr(Json.fromDoubleOrNull(0.55)),
      "effect_list" -> Json
        .arr(Json.fromString("None"), Json.fromString("fire")),
      "dynamics" -> Json.obj("speed" -> Json.fromInt(3)),
      "on" -> Json.True,
      "friendly_name" -> Json.fromString("Kjøkken"),
      "gone" -> Json.Null
    )
    val entity = EntityState("light.k", "on", attrs)
    val plain = EntityState.toJavaObject(attrs)
    def read(program: Cel.Program, attr: Object): Either[String, Any] =
      scala.util
        .Try(program.eval(new dev.cel.runtime.CelVariableResolver {
          def find(name: String): java.util.Optional[Object] =
            if (name == "value") java.util.Optional.of(attr)
            else java.util.Optional.empty()
        }))
        .toEither
        .left
        .map(_.getClass.getName)
    List(
      "'brightness' in value",
      "'gone' in value",
      "value['brightness']",
      "value.brightness",
      "value[?'nope'].orValue('none')",
      "value['rgb_color'][1]",
      "size(value['effect_list'])",
      "value['dynamics']['speed']",
      "value['on'] ? 'y' : 'n'",
      "size(value)",
      "value.transformList(k, v, k).sort().join(',')",
      "value['nope']"
    ).foreach { src =>
      val p = Cel
        .parse(Cel.Key(src, Nil, Some(ValueType.Dyn)))
        .fold(e => fail(e), identity)
      assertEquals(
        read(p, entity.javaAttributes),
        read(p, plain),
        clue = src
      )
    }
  }

  test("the simple tier decodes from the wire's explicit opt-in") {
    // `kind` picks the wire shape and `op` the operator, and the decoded slot
    // dispatches without the engine.
    val simpleWire = Json.obj(
      "slug" -> Json.fromString("k"),
      "cards" -> Json.obj(
        "c" -> Json.obj(
          "template" -> Json.fromString("<b>{{v}}</b>"),
          "slots" -> Json.arr(Json.fromString("v"))
        )
      ),
      "card" -> Json.obj(
        "kind" -> Json.fromString("Component"),
        "card" -> Json.fromString("c"),
        "templateSlots" -> Json.obj(
          "v" -> Json.obj(
            "source" -> Json.obj(
              "kind" -> Json.fromString("Entity"),
              "of" -> Json.fromString("sensor.a")
            ),
            "steps" -> Json.arr(
              Json.obj(
                "kind" -> Json.fromString("Suffix"),
                "text" -> Json.fromString(" W")
              )
            )
          )
        )
      )
    )
    DashboardBuild.decode(simpleWire).map { validated =>
      validated.dashboard.card match {
        case c: LayoutNode.Component =>
          assertEquals(
            c.templateSlots("v").expr.steps,
            List(Step.Suffix(Value.Literal(Lit.Text(" W"))))
          )
        case other => fail(s"unexpected node: $other")
      }
      assertEquals(
        Conds.run(
          ValueExpr(
            Value.Entity(),
            List(Step.Suffix(Value.Literal(Lit.Text(" W"))))
          )
        )(state, Map(state.entityId -> state), Evaluator.unanswered),
        "on W"
      )
    }
  }

  test("a degenerate percent range is rejected at validate") {
    val bad = dashboard("kitchen").copy(
      card = LayoutNode.Component(
        card = "c",
        templateSlots = Map(
          "onclick" -> TemplateSlot(
            ValueExpr(
              Value
                .Entity(Some("light.kitchen"), part = Part.Attr("brightness")),
              List(
                Step.Percent(
                  Value.Literal(Lit.Real(1.0)),
                  Value.Literal(Lit.Real(1.0))
                )
              )
            )
          )
        )
      )
    )
    val errs = bad.validated().fold(identity, _ => Nil)
    assert(errs.exists(_.contains("degenerate")), clue = errs)
  }

  test("a non-positive duration scale is rejected at validate") {
    // It does not error, it renders `0s` for every reading: a card that looks
    // finished forever.
    def scaled(s: Double) =
      dashboard("kitchen")
        .copy(card =
          LayoutNode.Component(
            card = "c",
            templateSlots = Map(
              "onclick" -> TemplateSlot(
                ValueExpr(
                  Value.Entity(Some("light.kitchen")),
                  List(Step.Duration(s))
                )
              )
            )
          )
        )
        .validated()
        .fold(identity, _ => Nil)

    assert(scaled(0.0).exists(_.contains("duration scale")), clue = scaled(0.0))
    assert(scaled(-60.0).exists(_.contains("duration scale")))
    assertEquals(scaled(60.0), Nil)
  }

  test("a match with both string and boolean arms is rejected at validate") {
    // CEL needs one type across a map's values and both ternary arms, so a
    // mixed lookup has no CEL equivalent (ADR 0028), and it would set an
    // attribute by value in one state and by presence in another.
    def withMatch(m: Step.Match) =
      dashboard("kitchen")
        .copy(card =
          LayoutNode.Component(
            card = "c",
            templateSlots = Map(
              "onclick" -> TemplateSlot(
                ValueExpr(Value.Entity(Some("light.kitchen")), List(m))
              )
            )
          )
        )
        .validated()
        .fold(identity, _ => Nil)

    val mixed = withMatch(
      Step.Match(
        Map("on" -> Lit.Text("yes"), "off" -> Lit.Bool(false)),
        Value.Literal(Lit.Text("no"))
      )
    )
    assert(mixed.exists(_.contains("string and boolean arms")), clue = mixed)

    // `otherwise` is the value an unmatched state gets, so it counts as an arm.
    val mixedElse = withMatch(
      Step.Match(Map("on" -> Lit.Bool(true)), Value.Literal(Lit.Text("")))
    )
    assert(
      mixedElse.exists(_.contains("string and boolean arms")),
      clue = mixedElse
    )

    assertEquals(
      withMatch(
        Step.Match(Map("on" -> Lit.Bool(true)), Value.Literal(Lit.Bool(false)))
      ),
      Nil
    )
    assertEquals(
      withMatch(
        Step.Match(Map("on" -> Lit.Text("yes")), Value.Literal(Lit.Text("no")))
      ),
      Nil
    )
  }

  // A tap URL built as a CEL transform, the way a surface tap's is: slug and
  // entity from bindings, `noSignals` inside the string.
  private val tapUrl =
    "\"@post('sse/action/\" + dashboard_slug + \"/\" + 'light/toggle' " +
      "+ \"/\" + value.entity_id + \"', {filterSignals:{exclude:'.*'}})\""

  private def program(src: String): Cel.Program =
    Cel.parse(Cel.Key(src)).fold(fail(_), identity)

  private def programOf(d: Dashboard, src: String): Cel.Program =
    d.validated()
      .fold(e => fail(e.mkString("\n")), _ => program(src))

  private def dashboard(slug: String) =
    Dashboard(
      cards = Map(
        "c" -> CardDef(template = "<b>{{onclick}}</b>")
      ),
      card = LayoutNode.Component(
        card = "c",
        templateSlots = Map(
          "onclick" -> TemplateSlot(
            ValueExpr(
              Value.Entity(Some("light.kitchen"), Part.Whole),
              List(Step.Cel(tapUrl))
            )
          )
        )
      ),
      slug = slug
    )

  private def state =
    EntityState("light.kitchen", "on", Map.empty[String, Json])

  /** `Dashboard` decodes but does not encode, and `DashboardBuild.decode` is
    * under test.
    */
  private def wire(slug: String): Json =
    Json.obj(
      "slug" -> Json.fromString(slug),
      "cards" -> Json.obj(
        "c" -> Json.obj(
          "template" -> Json.fromString("<b>{{onclick}}</b>"),
          "slots" -> Json.arr(Json.fromString("onclick"))
        )
      ),
      "card" -> Json.obj(
        "kind" -> Json.fromString("Component"),
        "card" -> Json.fromString("c"),
        "templateSlots" -> Json.obj(
          "onclick" -> Json.obj(
            "source" -> Json.obj(
              "kind" -> Json.fromString("Entity"),
              "of" -> Json.fromString("light.kitchen"),
              "part" -> Json.obj("kind" -> Json.fromString("Whole"))
            ),
            "steps" -> Json.arr(
              Json.obj(
                "kind" -> Json.fromString("Cel"),
                "src" -> Json.fromString(tapUrl)
              )
            )
          )
        )
      )
    )

  test("a tap's URL carries the dashboard it was rendered for") {
    assertEquals(
      Cel.run(programOf(dashboard("kitchen"), tapUrl), state, "kitchen"),
      "@post('sse/action/kitchen/light/toggle/light.kitchen', " +
        "{filterSignals:{exclude:'.*'}})"
    )
  }

  /** Otherwise the compiled tap URL is proven against a name the dashboard no
    * longer has, and every tap is refused with nothing in the URL to say why.
    */
  test("a pushed dashboard is validated under the slug it will be served as") {
    DashboardBuild
      .decode(wire("as-authored"), slug = Some("renamed"))
      .map { validated =>
        assertEquals(validated.dashboard.slug, "renamed")
        assertEquals(
          Cel.run(
            program(tapUrl),
            state,
            validated.dashboard.slug
          ),
          "@post('sse/action/renamed/light/toggle/light.kitchen', " +
            "{filterSignals:{exclude:'.*'}})"
        )
      }
  }

  test("decoding without a slug leaves the authored one alone") {
    DashboardBuild
      .decode(wire("as-authored"))
      .map(v => assertEquals(v.dashboard.slug, "as-authored"))
  }

  test("a transform that reads no slug is unaffected by it") {
    val plain = dashboard("kitchen").copy(
      card = LayoutNode.Component(
        card = "c",
        templateSlots = Map(
          "onclick" -> TemplateSlot(
            ValueExpr(
              Value.Entity(Some("light.kitchen"), Part.Whole),
              List(Step.Cel("value.state"))
            )
          )
        )
      )
    )
    assertEquals(
      Cel.run(programOf(plain, "value.state"), state, "x"),
      "on"
    )
  }

  /** A slot's value as the compiled tree runs it. */
  private def slotRun(
      e: ValueExpr,
      nodeValues: Map[String, ValueExpr] = Map.empty
  ): String =
    Dashboard(
      Map("c" -> CardDef("<b>{{v}}</b>")),
      LayoutNode.Component(
        "c",
        templateSlots = Map("v" -> TemplateSlot(e)),
        values = nodeValues
      )
    ).validated()
      .fold(errs => fail(errs.mkString("\n")), _.ready.card) match
      case c: Ready.Component =>
        Steps.text(c.slots("v").run(null, Map.empty, Evaluator.unanswered))
      case other => fail(s"not a component: $other")

  private def whole(n: Long) = ValueExpr(Value.Literal(Lit.Whole(n)))

  test("each CEL reads its own inputs, not another CEL's of the same name") {
    val e = ValueExpr(
      Value.Cel("x", Map("x" -> whole(1))),
      List(Step.Cel("value + x", Map("x" -> whole(10))))
    )
    assertEquals(slotRun(e), "11")
  }

  test("a CEL's input may be any expression that compiles, CEL included") {
    val e = ValueExpr(Value.Cel("y + 1", Map("y" -> ValueExpr(Value.Cel("2")))))
    assertEquals(slotRun(e), "3")
  }

  test("a CEL's own input wins over its node's value of that name") {
    // The step reads the node's: the source's own `x` is the source's alone.
    val e = ValueExpr(
      Value.Cel("x", Map("x" -> whole(5))),
      List(Step.Cel("value + x"))
    )
    assertEquals(slotRun(e, Map("x" -> whole(1))), "6")
  }
}
