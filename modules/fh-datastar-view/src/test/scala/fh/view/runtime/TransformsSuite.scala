package fh.view.runtime

import fh.view.build.DashboardBuild
import fh.view.model.{CardDef, Dashboard, LayoutNode, Reads, SlotSource}
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

  test("a state fast-path transform renders exactly what CEL would") {
    // The fast path skips the engine for the plain `state` read (issue #237).
    // It is only safe while the two render EVERY state shape identically, so
    // this compares them rather than asserting expected output: the oracle is
    // the CEL engine itself.
    val states = List(
      "on",
      "off",
      "21.44",
      "",
      "unavailable",
      "ø 😀",
      "locks path with \"quotes\""
    )
    val expr = "state"
    val simple = fh.view.model.Transform
      .simple(expr)
      .getOrElse(fail(s"$expr should be recognised as simple"))
    states.foreach { state =>
      val entity = EntityState("sensor.a", state, Map.empty[String, Json])
      val viaEngine = fh.view.model.Transform.run(
        fh.view.model.Transform
          .parse(expr)
          .getOrElse(fail(s"could not compile $expr")),
        entity,
        "dashboard"
      )
      assertEquals(
        fh.view.model.Transform.runSimple(simple, entity),
        Some(viaEngine),
        clue = state
      )
    }
  }

  test("the closed set is recognised; near-misses go to the engine") {
    // The catalog is closed over the strings the shipped library bakes — each
    // of these IS one, byte-for-byte:
    val canonical = List(
      "state",
      "  state  ",
      "'brightness' in attr ? attr['brightness'] : null",
      "('friendly_name' in attr ? attr['friendly_name'] : entity_id)",
      "state + ('unit_of_measurement' in attr ? ' ' + " +
        "attr['unit_of_measurement'] : '')",
      "state + ' W'",
      "'lit' + state",
      "state == 'on' ? 'Open' : 'Closed'",
      "state == 'locked' ? 'lock/unlock' : 'lock/lock'",
      // the slider's baked percent and fill (min 1, max 255)
      "cel.bind(v, 'brightness' in attr ? attr['brightness'] : null, " +
        "v != null ? str(math.round((double(v) - 1.0) * 100.0 / (255.0 - 1.0))) " +
        "+ ' %' : '0 %')",
      "str(cel.bind(v, 'brightness' in attr ? attr['brightness'] : null, " +
        "v != null ? 100.0 - ((double(v) - 1.0) * 100.0 / (255.0 - 1.0)) " +
        ": 100.0)) + '%'"
    )
    canonical.foreach(e =>
      assert(fh.view.model.Transform.simple(e).isDefined, clue = e)
    )
    // The guard against this growing into a second implementation of the
    // language. Each of these READS like a member of the set and is not one:
    // a different fallback, a bare read, an int literal where a float is
    // spliced, a half-formed enum — all engine work.
    List(
      "stater",
      "'state'",
      "str(state)",
      "attr",
      "attr['brightness']",
      "str(attr['brightness'])",
      "'friendly_name' in attr ? attr['friendly_name'] : entity_id",
      "'friendly_name' in attr ? attr['friendly_name'] : ''",
      "state + \" W\"",
      "state + 1.0",
      "state == 'on' ? 'Open'",
      "state == 5 ? 'Open' : 'Closed'",
      "cel.bind(v, 'brightness' in attr ? attr['brightness'] : null, " +
        "v != null ? str(math.round((double(v) - 1) * 100.0 / (255 - 1))) " +
        "+ ' %' : '0 %')",
      "str(cel.bind(v, 'brightness' in attr ? attr['brightness'] : null, " +
        "v != null ? 100.0 - ((double(v) - 1.0) * 100.0 / (255.0 - 1.0)) " +
        ": 100.0)) + ' %'"
    ).foreach(e =>
      assertEquals(fh.view.model.Transform.simple(e), None, clue = e)
    )
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
