package fh.view.runtime

import fh.view.model.{Dashboard, SlotValue, Transform}

/** Every CEL transform compiled once, then only looked up. The tier is the
  * slot's form (ADR 0028); neither tier falls back to the other.
  */
class Transforms private (
    private val compiled: Map[Transform.CelKey, Transform.Compiled]
) {

  // `key` is always one the dashboard declared; a miss is a bug.
  def run(
      key: Transform.CelKey,
      entity: EntityState,
      dashboardSlug: String
  ): String =
    Transform.run(compiled(key), entity, dashboardSlug)

  def runValue(
      expr: String,
      entity: EntityState,
      dashboardSlug: String
  ): SlotValue =
    runValue(Transform.CelKey(expr), entity, dashboardSlug, Map.empty)

  // `values` are the key's own names, resolved for this render.
  def runValue(
      key: Transform.CelKey,
      entity: EntityState,
      dashboardSlug: String,
      values: Map[String, Object]
  ): SlotValue =
    Transform.runValue(compiled(key), entity, dashboardSlug, values)

  def run(s: Transform.Simple, entity: EntityState): String =
    Transform.runSimple(s, entity)

  def runValue(s: Transform.Simple, entity: EntityState): SlotValue =
    Transform.runSimpleValue(s, entity)
}

object Transforms {

  def fromValidated(v: Dashboard.Validated): Transforms =
    new Transforms(v.transforms)
}
