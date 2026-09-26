package fh.view.testkit

import fh.view.model.{LayoutNode, SlotSource}
import fh.view.runtime.EntityState
import io.circe.Json

/** Shared constructors for the runtime suites. Card templates and whole
  * dashboards stay in the suites, since their exact HTML is what those tests
  * assert.
  */
object DashboardBuilders {

  /** Fails naming what the node actually was, where a `ClassCastException`
    * names neither the node nor the assertion.
    */
  extension (node: LayoutNode) {
    def asComponent: LayoutNode.Component = node match {
      case c: LayoutNode.Component => c
      case other                   =>
        throw new AssertionError(s"expected a Component, got: $other")
    }

    def asSetNode: LayoutNode.SetNode = node match {
      case s: LayoutNode.SetNode => s
      case other => throw new AssertionError(s"expected a SetNode, got: $other")
    }
  }

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

  /** A `col` container over `kids` (the card name each suite's `cards`
    * defines).
    */
  def col(kids: LayoutNode*): LayoutNode.Component =
    LayoutNode.Component("col", regions = LayoutNode.kids(kids*))

  /** A `row` container over `kids`. */
  def row(kids: LayoutNode*): LayoutNode.Component =
    LayoutNode.Component("row", regions = LayoutNode.kids(kids*))
}
