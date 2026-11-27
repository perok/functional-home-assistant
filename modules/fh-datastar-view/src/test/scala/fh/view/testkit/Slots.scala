package fh.view.testkit

import fh.view.model.{
  CardDef,
  Case,
  Choice,
  Fill,
  LayoutNode,
  Lit,
  Slot,
  TemplateSlot,
  Value,
  ValueExpr
}

/** A hand-built component's `slots`, for fixtures that name the two kinds. */
object Slots:

  def values(m: Map[String, TemplateSlot]): Map[String, Slot] =
    m.map((n, s) => n -> Slot.Value(s))

  def nodes(m: Map[String, List[LayoutNode]]): Map[String, Slot] =
    m.map((n, ns) => n -> Slot.Nodes(Fill.Nodes(Choice.Fixed(ns))))

  /** `cs` in the default nodes slot. */
  def kids(cs: LayoutNode*): Map[String, Slot] =
    if cs.isEmpty then Map.empty
    else nodes(Map(LayoutNode.DefaultRegion -> cs.toList))

  /** The card [[inGroup]] puts a choice in: its one nodes slot is its whole
    * template, as `core/group.pkl`'s.
    */
  val GroupCard: String = "group"
  val groupCard: CardDef =
    TestCard(
      s"{{{${LayoutNode.DefaultRegion}}}}",
      Set(LayoutNode.DefaultRegion)
    )

  /** `choice` beside other content: a live fill is its card's whole template,
    * so it sits in a [[GroupCard]] node, whose root is the host.
    */
  def inGroup(choice: Slot): Slot =
    Slot.Nodes(
      Fill.Nodes(
        Choice.Fixed(
          List(
            LayoutNode.Component(
              GroupCard,
              Map(LayoutNode.DefaultRegion -> choice)
            )
          )
        )
      )
    )

  /** A nodes slot filled with a choice: case `i` is served as the surface
    * `<host>_t<i>`, the otherwise as `<host>_else`.
    */
  def chosen(
      by: ValueExpr,
      cases: List[(Lit, LayoutNode)],
      otherwise: Option[LayoutNode] = None
  ): Slot =
    Slot.Nodes(
      Fill.Nodes(
        Choice.Chosen(
          by,
          cases.map((is, n) => Case(is, List(n))),
          otherwise.map(List(_))
        )
      )
    )

  /** A choice over the node variable `name`, a case per value. */
  def byVar(
      name: String,
      cases: List[(String, LayoutNode)],
      otherwise: Option[LayoutNode] = None
  ): Slot =
    chosen(
      ValueExpr(Value.Var(name)),
      cases.map((v, n) => Lit.Text(v) -> n),
      otherwise
    )

  /** A tab bar's panel: case `"<i>"` for the `i`th node. */
  def tabs(name: String, panels: LayoutNode*): Slot =
    byVar(name, panels.toList.zipWithIndex.map((n, i) => i.toString -> n))

  /** An If over `cond`: `then` while it holds, else `otherwise`. */
  def iff(
      cond: ValueExpr,
      `then`: LayoutNode,
      otherwise: Option[LayoutNode] = None
  ): Slot =
    chosen(cond, List(Lit.Bool(true) -> `then`), otherwise)
