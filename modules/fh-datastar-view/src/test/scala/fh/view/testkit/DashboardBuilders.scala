package fh.view.testkit

import fh.view.model.{LayoutNode, SlotSource}
import fh.view.runtime.EntityState
import io.circe.Json

/** The small constructors the runtime unit suites (`RendererSuite`,
  * `ServerSuite`) used to each re-declare — entity states, literal slots, and
  * the common container/leaf layout nodes. Pulled here so the scaffolding is
  * written once and a suite reads as the behaviour under test, not its plumbing.
  *
  * Card *templates* and whole dashboards stay in the suites: their exact HTML is
  * what those tests assert, so they are fixtures, not shared scaffolding.
  */
object DashboardBuilders {

  /** An [[EntityState]] with optional typed attributes. */
  def st(entityId: String, state: String, attrs: (String, Json)*): EntityState =
    EntityState(entityId, state, attrs.toMap)

  /** A constant literal slot (a bare value, no entity/transform). */
  def lit(s: String): SlotSource = SlotSource(literal = Some(s))

  /** A leaf/container component referencing `card` with the given slots. */
  def component(
      card: String,
      slots: (String, SlotSource)*
  ): LayoutNode.Component =
    LayoutNode.Component(card, slots.toMap)

  /** A `col` container over `kids` (the card name each suite's `cards` defines). */
  def col(kids: LayoutNode*): LayoutNode.Component =
    LayoutNode.Component("col", children = kids.toList)

  /** A `row` container over `kids`. */
  def row(kids: LayoutNode*): LayoutNode.Component =
    LayoutNode.Component("row", children = kids.toList)
}
