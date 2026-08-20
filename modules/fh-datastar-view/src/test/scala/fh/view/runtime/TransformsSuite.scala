package fh.view.runtime

import fh.view.model.{CardDef, Dashboard, LayoutNode, SlotSource}
import io.circe.Json

/** Where the dashboard's slug enters a transform (ADR 0023).
  *
  * A tap builds its action URL as a JSONata expression, and the URL carries the
  * slug so the server can bound what the tap may touch. The slug is not
  * authorable — a module does not know its own — so the renderer supplies it,
  * and [[Transforms]] is where a transform's copy of the token is filled.
  *
  * The trap this suite exists for is WHEN. Filling at validate time would be
  * cheaper and wrong: `Validated.withSlug` re-slugs a pushed dashboard AFTER
  * validation (`fh push --slug`), so a baked-in slug would be the old one and
  * every tap on a renamed dashboard would post to a dashboard it is not on —
  * refused, with nothing in the URL to suggest why.
  */
class TransformsSuite extends munit.FunSuite {

  private val tapUrl =
    "\"@post('sse/action/{{fhSlug}}/\" & (\"light/toggle\") & \"/\" & $entity_id & \"')\""

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

  private def state = EntityState("light.kitchen", "on", Map.empty[String, Json])

  test("a transform's slug token is filled with the dashboard's own slug") {
    val out = Transforms
      .from(dashboard("kitchen"))
      .run(tapUrl, state)
    assertEquals(
      out,
      "@post('sse/action/kitchen/light/toggle/light.kitchen')"
    )
  }

  /** The push path: validate, then rename, then build the renderer. */
  test("a dashboard renamed after validation posts to its NEW slug") {
    val validated = dashboard("as-authored")
      .validated()
      .fold(errs => fail(s"fixture did not validate: $errs"), identity)
      .withSlug("renamed")

    val out = Transforms.fromValidated(validated).run(tapUrl, state)
    assert(
      clue(out).contains("sse/action/renamed/"),
      "the transform kept the slug it was validated under"
    )
  }

  test("the lookup key stays the ORIGINAL expression the slot names") {
    // Filling rewrites the compiled expression, not the slot — a slot still
    // asks for the transform it declared, and a miss here is a crash rather
    // than a blank value.
    val transforms = Transforms.from(dashboard("kitchen"))
    assert(transforms.run(tapUrl, state).nonEmpty)
  }

  test("a transform with no token is left exactly alone") {
    val plain = "$state"
    val d = dashboard("kitchen").copy(
      card = LayoutNode.Component(
        card = "c",
        slots = Map("onclick" -> SlotSource(transform = plain))
      )
    )
    assertEquals(Transforms.from(d).run(plain, state), "on")
  }
}
