package fh.view.runtime

import fh.view.build.DashboardBuild
import fh.view.model.{CardDef, Dashboard, LayoutNode, SlotSource}
import io.circe.Json

/** How a dashboard's slug reaches the action URL a tap builds (ADR 0023).
  *
  * A tap's URL is a JSONata expression, and it carries the slug so the server
  * can bound what the tap may touch. The slug is not authorable — a module does
  * not know its own — so the renderer binds it as `$dashboardSlug`, the one
  * binding that is not about the entity.
  *
  * The property worth pinning is not the binding itself but WHEN the slug is
  * settled: before validation, so a `Validated` is final. It used to be applied
  * afterwards (`withSlug`), which was harmless only while nothing derived from
  * the slug — and a compiled tap URL derives from it.
  */
class TransformsSuite extends munit.CatsEffectSuite {

  private val tapUrl =
    "\"@post('sse/action/\" & $dashboardSlug & \"/light/toggle/\" & $entity_id & \"')\""

  private def dashboard(slug: String) =
    Dashboard(
      cards = Map(
        "c" -> CardDef(template = "<b>{{onclick}}</b>", slots = List("onclick"))
      ),
      card = LayoutNode.Component(
        card = "c",
        slots = Map(
          "onclick" -> SlotSource(transform = tapUrl, reactive = false)
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
      "@post('sse/action/kitchen/light/toggle/light.kitchen')"
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
          "@post('sse/action/renamed/light/toggle/light.kitchen')"
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
        slots = Map("onclick" -> SlotSource(transform = "$state"))
      )
    )
    assertEquals(Transforms.from(plain).run("$state", state, "x"), "on")
  }
}
