package fh.view.runtime

import fh.view.model.{Dashboard, Transform}

/** The slot value-transform library, pre-compiled once at startup (never on the
  * hot path) — the CEL counterpart to [[Templates]].
  *
  * Every distinct CEL [[SlotSource.transform]] in the layout (and in every
  * surface) is compiled here and thereafter only looked up, so the renderer
  * never parses CEL while rendering. A transform that fails to compile is an
  * invariant breach: [[Dashboard.validate]] runs before any renderer is built
  * and rejects (and locates) bad expressions, so reaching this with an
  * uncompilable one means validation was bypassed — it fails loudly here, at
  * setup, rather than mid render or by silently blanking a value.
  *
  * Two tiers, selected EXPLICITLY by the slot ([[SlotSource.simple]] — there is
  * no recognition of expression spelling, plan Phase 3): a slot carrying a
  * [[Transform.Simple]] value is evaluated by hand-rolled reads
  * ([[Transform.runSimple]] — total, its documented divergences included); a
  * slot carrying a CEL string goes to the engine. Neither path falls back to
  * the other — the opted-in tier owns its values.
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
  def run(expr: String, entity: EntityState, dashboardSlug: String): String =
    Transform.run(compiled(expr), entity, dashboardSlug)

  /** Evaluate an opted-in [[Transform.Simple]] value — no engine involvement.
    */
  def run(s: Transform.Simple, entity: EntityState): String =
    Transform.runSimple(s, entity)
}

object Transforms {

  /** From a [[Dashboard.Validated]]: the transforms are ALREADY compiled (the
    * proof carries them), so this is a total lookup table — no parse, no
    * defensive throw. The production construction point
    * ([[Renderer.fromValidated]]).
    */
  def fromValidated(v: Dashboard.Validated): Transforms =
    new Transforms(v.transforms)

  /** From a raw (unproven) dashboard — the convenience path for tests and
    * [[Renderer.create]]. Compiles every [[Dashboard.transformStrings]]; a
    * parse failure is an invariant breach ([[Dashboard.validate]] runs before
    * any renderer is built in production), so it fails loudly here rather than
    * mid render or by silently blanking a value.
    */
  def from(dashboard: Dashboard): Transforms = {
    val compiled = dashboard.transformStrings.map { t =>
      Transform.parse(t) match {
        case Right(c)  => t -> c
        case Left(err) =>
          throw new IllegalStateException(
            s"unvalidated transform reached transform setup: $t ($err)"
          )
      }
    }.toMap
    new Transforms(compiled)
  }
}
