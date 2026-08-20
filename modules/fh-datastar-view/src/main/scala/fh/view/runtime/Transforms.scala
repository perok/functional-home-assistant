package fh.view.runtime

import fh.view.model.{Dashboard, Transform}

/** The slot value-transform library, pre-compiled once at startup (never on the
  * hot path) — the JSONata counterpart to [[Templates]].
  *
  * Every distinct [[SlotSource.transform]] in the layout (and in every surface)
  * is compiled here and thereafter only looked up, so the renderer never parses
  * JSONata while rendering. A transform that fails to compile is an invariant
  * breach: [[Dashboard.validate]] runs before any renderer is built and rejects
  * (and locates) bad expressions, so reaching this with an uncompilable one
  * means validation was bypassed — it fails loudly here, at setup, rather than
  * mid render or by silently blanking a value.
  */
class Transforms private (
    private val compiled: Map[String, Transform.Compiled]
) {

  /** Apply the transform named by `expr` to the producing entity, reading its
    * `state`/`attributes`/`domain`/`entity_id` as same-entity context, plus the
    * dashboard's `slug`. `expr` is always one the dashboard declared (the map
    * is total over the layout's transforms), so a miss is a bug, not a runtime
    * condition.
    */
  def run(expr: String, entity: EntityState): String =
    Transform.run(compiled(expr), entity)
}

object Transforms {

  /** The renderer-filled dashboard slug, as it appears in a transform.
    *
    * The SAME token a card writes in its own template (`core/tap.pkl`,
    * `components/slider.pkl`) — one name for one fact. What differs is only who
    * fills it, and that is forced by where the text ends up: Mustache renders a
    * template per node, but a transform's OUTPUT is inserted raw
    * (`{{{onclick}}}`) and Mustache never sees it, so a transform's copy is
    * filled here instead.
    *
    * Filled ONCE per dashboard, at renderer construction — not per render, and
    * not at validate time: `Validated.withSlug` re-slugs a pushed dashboard
    * after validation, so anything baked earlier would carry the old slug.
    */
  val SlugToken: String = "{{fhSlug}}"

  /** From a [[Dashboard.Validated]]: the transforms are ALREADY compiled (the
    * proof carries them), so this is a total lookup table — no parse, no
    * defensive throw. The production construction point
    * ([[Renderer.fromValidated]]).
    *
    * The exception is a transform carrying [[SlugToken]], which is recompiled
    * with the slug in place. Keyed by the ORIGINAL expression, because that is
    * what the slot still names.
    */
  def fromValidated(v: Dashboard.Validated): Transforms =
    new Transforms(withSlug(v.transforms, v.dashboard.slug))

  private def withSlug(
      compiled: Map[String, Transform.Compiled],
      slug: String
  ): Map[String, Transform.Compiled] =
    compiled.map {
      case (expr, c) if expr.contains(SlugToken) =>
        expr -> parseOrThrow(expr.replace(SlugToken, slug))
      case entry => entry
    }

  private def parseOrThrow(expr: String): Transform.Compiled =
    Transform.parse(expr) match {
      case Right(c)  => c
      case Left(err) =>
        throw new IllegalStateException(
          s"unvalidated transform reached transform setup: $expr ($err)"
        )
    }

  /** From a raw (unproven) dashboard — the convenience path for tests and
    * [[Renderer.create]]. Compiles every [[Dashboard.transformStrings]]; a
    * parse failure is an invariant breach ([[Dashboard.validate]] runs before
    * any renderer is built in production), so it fails loudly here rather than
    * mid render or by silently blanking a value.
    */
  def from(dashboard: Dashboard): Transforms = {
    val compiled = dashboard.transformStrings.map { t =>
      t -> parseOrThrow(t.replace(SlugToken, dashboard.slug))
    }.toMap
    new Transforms(compiled)
  }
}
