package fh.view.testkit

import fh.view.model.{
  Case,
  Cell,
  Choice,
  Dashboard,
  LayoutNode,
  Lit,
  TemplateSlot,
  Value,
  ValueExpr
}

import scala.collection.immutable.ListMap
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

  /** A table chosen by the variable `name`, as `p.history.chosen` writes one:
    * each key a text case, no `otherwise`.
    */
  def chosenBy[A](name: String, table: Map[String, A]): Choice[A] =
    Choice.Chosen(
      ValueExpr(Value.Var(name)),
      table.toList.sortBy(_._1).map((k, a) => Case(Lit.Text(k), a))
    )

  /** An [[EntityState]] with optional typed attributes. */
  def st(entityId: String, state: String, attrs: (String, Json)*): EntityState =
    EntityState(entityId, state, attrs.toMap)

  /** A subject where there is none; nothing reads it. */
  val noSubject: EntityState = EntityState("", "", Map.empty)

  /** A node's `vars`: each name at its default, in the URL, opened nowhere
    * else.
    */
  def declared(defaults: (String, String)*): List[Dashboard.Declaration] =
    defaults.toList.map(Dashboard.Declaration(_, _))

  /** A constant literal slot (a bare value, no entity/transform). */
  def lit(s: String): TemplateSlot = TemplateSlot(ValueExpr.literal(s))

  /** A cell holding these classes, each always there. */
  def classes(names: String*): Cell =
    Cell(classes = ListMap.from(names.map(_ -> ValueExpr.literal("1"))))

  /** A leaf/container component referencing `card` with the given slots. */
  def component(
      card: String,
      slots: (String, TemplateSlot)*
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
