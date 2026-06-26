package fh.view.runtime

import fh.view.model.{Dashboard, LayoutNode, SlotSource, Transform}

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
    * `state`/`attributes`/`domain`/`entity_id` as same-entity context. `expr`
    * is always one the dashboard declared (the map is total over the layout's
    * transforms), so a miss is a bug, not a runtime condition.
    */
  def run(expr: String, entity: EntityState): String =
    Transform.run(compiled(expr), entity)
}

object Transforms {

  def from(dashboard: Dashboard): Transforms = {
    def slotsOf(node: LayoutNode): List[SlotSource] = node match {
      case c: LayoutNode.Component =>
        c.slots.values.toList ++ c.children.flatMap(slotsOf)
      case d: LayoutNode.Dynamic => d.cases.flatMap(_.slots.values)
      case _: LayoutNode.Mount   => Nil // a mount has no slots/transforms
    }
    // Surfaces (popups) carry their own slots — compile those too, else opening a
    // popup would hit an uncompiled transform.
    val allSlots =
      slotsOf(dashboard.card) ++
        dashboard.surfaces.values.flatMap(s => slotsOf(s.content))
    val compiled = allSlots
      .filter(_.literal.isEmpty) // constant literals carry no transform
      .map(_.transform)
      .distinct
      .map { t =>
        Transform.parse(t) match {
          case Right(c) => t -> c
          case Left(err) =>
            throw new IllegalStateException(
              s"unvalidated transform reached transform setup: $t ($err)"
            )
        }
      }
      .toMap
    new Transforms(compiled)
  }
}
