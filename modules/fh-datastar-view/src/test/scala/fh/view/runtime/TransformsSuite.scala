package fh.view.runtime

import fh.view.build.DashboardBuild
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  Reads,
  SlotSource,
  Transform
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
          slots = Map("onclick" -> SlotSource(transform = "state"))
        )
      ),
      "state"
    )
    states.foreach { state =>
      val entity = EntityState("sensor.a", state, Map.empty[String, Json])
      assertEquals(
        Transform.runSimple(Transform.Simple.State(), entity, Map.empty),
        Transform.run(cel, entity, "dashboard"),
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
    def read(program: Transform.Compiled, attr: Object): Either[String, Any] =
      scala.util
        .Try(program.eval(new dev.cel.runtime.CelVariableResolver {
          def find(name: String): java.util.Optional[Object] =
            if (name == "attr") java.util.Optional.of(attr)
            else java.util.Optional.empty()
        }))
        .toEither
        .left
        .map(_.getClass.getName)
    List(
      "'brightness' in attr",
      "'gone' in attr",
      "attr['brightness']",
      "attr.brightness",
      "attr[?'nope'].orValue('none')",
      "attr['rgb_color'][1]",
      "size(attr['effect_list'])",
      "attr['dynamics']['speed']",
      "attr['on'] ? 'y' : 'n'",
      "size(attr)",
      "attr.transformList(k, v, k).sort().join(',')",
      "attr['nope']"
    ).foreach { src =>
      val p = Transform.parse(src).fold(e => fail(e), identity)
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
        "slots" -> Json.obj(
          "v" -> Json.obj(
            "transform" -> Json.obj(
              "kind" -> Json.fromString("Op"),
              "op" -> Json.fromString("Suffix"),
              "literal" -> Json.fromString(" W")
            )
          )
        )
      )
    )
    DashboardBuild.decode(simpleWire).map { validated =>
      validated.dashboard.card match {
        case c: LayoutNode.Component =>
          // The transform is the union: no parallel field.
          c.slots("v").transform match {
            case Transform.Simple.Suffix(" W", _) => ()
            case other => fail(s"expected the opted-in suffix, got $other")
          }
        case other => fail(s"unexpected node: $other")
      }
      assertEquals(
        Transform.runSimple(Transform.Simple.Suffix(" W"), state, Map.empty),
        "on W"
      )
    }
  }

  test("a degenerate percent range is rejected at validate") {
    val bad = dashboard("kitchen").copy(
      card = LayoutNode.Component(
        card = "c",
        slots = Map(
          "onclick" -> SlotSource(
            transform = Transform.Simple.Percent("brightness", 1.0, 1.0)
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
            slots = Map(
              "onclick" -> SlotSource(transform = Transform.Simple.Duration(s))
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
    def withMatch(m: Transform.Simple) =
      dashboard("kitchen")
        .copy(card =
          LayoutNode.Component(
            card = "c",
            slots = Map("onclick" -> SlotSource(transform = m))
          )
        )
        .validated()
        .fold(identity, _ => Nil)

    val mixed = withMatch(
      Transform.Simple
        .Match(Map("on" -> "yes", "off" -> false), otherwise = "no")
    )
    assert(mixed.exists(_.contains("string and boolean arms")), clue = mixed)

    // `otherwise` is the value an unmatched state gets, so it counts as an arm.
    val mixedElse = withMatch(
      Transform.Simple.Match(Map("on" -> true), otherwise = "")
    )
    assert(
      mixedElse.exists(_.contains("string and boolean arms")),
      clue = mixedElse
    )

    assertEquals(
      withMatch(
        Transform.Simple.Match(Map("on" -> true), otherwise = false)
      ),
      Nil
    )
    assertEquals(
      withMatch(
        Transform.Simple.Match(Map("on" -> "yes"), otherwise = "no")
      ),
      Nil
    )
  }

  // A tap URL built as a CEL transform, the way a surface tap's is: slug and
  // entity from bindings, `noSignals` inside the string.
  private val tapUrl =
    "\"@post('sse/action/\" + dashboard_slug + \"/\" + 'light/toggle' " +
      "+ \"/\" + entity_id + \"', {filterSignals:{exclude:'.*'}})\""

  private def programOf(d: Dashboard, src: String): Transform.Compiled =
    d.validated()
      .fold(e => fail(e.mkString("\n")), _.transforms(Transform.CelKey(src)))

  private def dashboard(slug: String) =
    Dashboard(
      cards = Map(
        "c" -> CardDef(template = "<b>{{onclick}}</b>", slots = List("onclick"))
      ),
      card = LayoutNode.Component(
        card = "c",
        slots = Map(
          "onclick" -> SlotSource(transform = tapUrl, reads = Reads.Once)
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
        "slots" -> Json.obj(
          "onclick" -> Json.obj(
            "transform" -> Json.fromString(tapUrl),
            "reactive" -> Json.False
          )
        )
      )
    )

  test("a tap's URL carries the dashboard it was rendered for") {
    assertEquals(
      Transform.run(programOf(dashboard("kitchen"), tapUrl), state, "kitchen"),
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
          Transform.run(
            validated.transforms(Transform.CelKey(tapUrl)),
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
        slots = Map("onclick" -> SlotSource(transform = "state"))
      )
    )
    assertEquals(
      Transform.run(programOf(plain, "state"), state, "x"),
      "on"
    )
  }
}
