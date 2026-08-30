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

/** How a dashboard's slug reaches the action URL a tap builds (ADR 0023).
  *
  * A tap's URL is a CEL expression, and it carries the slug so the server can
  * bound what the tap may touch. The slug is not authorable — a module does not
  * know its own — so the renderer binds it as `dashboard_slug`, the one binding
  * that is not about the entity.
  *
  * The property worth pinning is not the binding itself but WHEN the slug is
  * settled: before validation, so a `Validated` is final. It used to be applied
  * afterwards (`withSlug`), which was harmless only while nothing derived from
  * the slug — and a compiled tap URL derives from it.
  */
class TransformsSuite extends munit.CatsEffectSuite {

  test("the opted-in state tier renders exactly what CEL would") {
    // The simple tier is entered by the slot's `simple` field, never by
    // recognising spelling (ADR 0028). It is only safe while it renders
    // EVERY state shape identically to the engine, so this compares them
    // rather than asserting expected output: the oracle is CEL itself.
    val states = List(
      "on",
      "off",
      "21.44",
      "",
      "unavailable",
      "ø 😀",
      "locks path with \"quotes\""
    )
    val t = Transforms.from(
      dashboard("kitchen").copy(
        card = LayoutNode.Component(
          card = "c",
          slots = Map("onclick" -> SlotSource(transform = "state"))
        )
      )
    )
    states.foreach { state =>
      val entity = EntityState("sensor.a", state, Map.empty[String, Json])
      assertEquals(
        t.run(Transform.Simple.State, entity),
        t.run("state", entity, "dashboard"),
        clue = state
      )
    }
  }

  test("the simple tier decodes from the wire's explicit opt-in") {
    // `"simple"` rides beside `transform` as the slot's own object; its `kind`
    // discriminator picks the case. A decoded simple slot dispatches without
    // the engine.
    val simpleWire = Json.obj(
      "slug" -> Json.fromString("k"),
      "cards" -> Json.obj(
        "c" -> Json.obj(
          "template" -> Json.fromString("<b>{{v}}</b>"),
          "slots" -> Json.arr(Json.fromString("v"))
        )
      ),
      "card" -> Json.obj(
        "kind" -> Json.fromString("component"),
        "card" -> Json.fromString("c"),
        "slots" -> Json.obj(
          "v" -> Json.obj(
            "transform" -> Json.obj(
              "kind" -> Json.fromString("suffix"),
              "literal" -> Json.fromString(" W")
            )
          )
        )
      )
    )
    DashboardBuild.decode(simpleWire).map { validated =>
      validated.dashboard.card match {
        case c: LayoutNode.Component =>
          // The transform IS the union: an object decodes straight into the
          // Simple case — no parallel field.
          c.slots("v").transform match {
            case Transform.Simple.Suffix(" W") => ()
            case other => fail(s"expected the opted-in suffix, got $other")
          }
        case other => fail(s"unexpected node: $other")
      }
      assertEquals(
        Transforms
          .fromValidated(validated)
          .run(
            Transform.Simple.Suffix(" W"),
            state
          ),
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

  // The expression the shipped `c.tap.service("light/toggle")` emits: the action
  // as a single-quoted CEL literal, the slug and entity spliced from bindings,
  // and `noSignals` riding inside the built string.
  private val tapUrl =
    "\"@post('sse/action/\" + dashboard_slug + \"/\" + 'light/toggle' " +
      "+ \"/\" + entity_id + \"', {filterSignals:{exclude:'.*'}})\""

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

  /** The same dashboard as wire JSON — `Dashboard` decodes but does not encode,
    * and `DashboardBuild.decode` is the path under test.
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
        "kind" -> Json.fromString("component"),
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
      Transforms.from(dashboard("kitchen")).run(tapUrl, state, "kitchen"),
      "@post('sse/action/kitchen/light/toggle/light.kitchen', " +
        "{filterSignals:{exclude:'.*'}})"
    )
  }

  /** `fh push --slug` renames a dashboard on the way in. The rename has to land
    * BEFORE validation, or the compiled tap URL is proven against a name the
    * dashboard no longer has and every tap posts to a dashboard it is not on —
    * refused, with nothing in the URL to say why.
    */
  test("a pushed dashboard is validated under the slug it will be served as") {
    DashboardBuild
      .decode(wire("as-authored"), slug = Some("renamed"))
      .map { validated =>
        assertEquals(validated.dashboard.slug, "renamed")
        assertEquals(
          Transforms
            .fromValidated(validated)
            .run(tapUrl, state, validated.dashboard.slug),
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
    assertEquals(Transforms.from(plain).run("state", state, "x"), "on")
  }
}
