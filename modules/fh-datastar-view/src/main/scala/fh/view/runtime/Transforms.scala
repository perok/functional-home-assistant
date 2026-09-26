package fh.view.runtime

import fh.view.model.{Dashboard, SlotValue, Transform}

/** Every CEL transform compiled once, then only looked up. The tier is the
  * slot's form (ADR 0028); neither tier falls back to the other.
  */
class Transforms private (
    private val compiled: Map[String, Transform.Compiled]
) {

  // `expr` is always one the dashboard declared; a miss is a bug.
  def run(expr: String, entity: EntityState, dashboardSlug: String): String =
    Transform.run(compiled(expr), entity, dashboardSlug)

  def runValue(
      expr: String,
      entity: EntityState,
      dashboardSlug: String
  ): SlotValue =
    Transform.runValue(compiled(expr), entity, dashboardSlug)

  def run(s: Transform.Simple, entity: EntityState): String =
    Transform.runSimple(s, entity)

  def runValue(s: Transform.Simple, entity: EntityState): SlotValue =
    Transform.runSimpleValue(s, entity)
}

object Transforms {

  def fromValidated(v: Dashboard.Validated): Transforms =
    new Transforms(v.transforms)

  /** For tests and [[Renderer.create]]. A parse failure means validation was
    * bypassed, so it fails loudly at setup rather than blanking a value.
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
