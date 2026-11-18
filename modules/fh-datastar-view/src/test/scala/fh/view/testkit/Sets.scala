package fh.view.testkit

import fh.view.model.{
  Case,
  CardDef,
  Cell,
  Choice,
  Fill,
  IdKind,
  LayoutNode,
  Lit,

  SortTerm,
  Slot,
  Step,
  Value,
  ValueExpr
}

/** A candidate set as the library writes it: a `group` card whose one nodes
  * slot holds a [[Fill.Set]], each member a [[Choice]] of its node. [[node]]
  * takes a member as its clauses, as a guard and the complete node it renders,
  * the first guard that holds deciding, so a fixture reads as the set it stands
  * for.
  */
object Sets:

  /** The group card's name; every dashboard that holds a set registers [[card]]
    * under it.
    */
  val Card: String = "fhSet"

  val SlotName: String = LayoutNode.DefaultRegion

  /** `.build()`'s root, as `core/group.pkl` lowers it. */
  val card: CardDef = CardDef(
    """<div class="fh-cell fh-group{{{cellClasses}}}"{{{cell}}}>{{{children}}}</div>""",
    regions = Set(SlotName)
  )

  /** The group card by its name, for a dashboard's `cards`. */
  val cards: Map[String, CardDef] = Map(Card -> card)

  /** `cards` and the group card. */
  def withCard(cards: Map[String, CardDef]): Map[String, CardDef] =
    cards + (Card -> card)

  /** If `when` holds (always, for none), render `node`. */
  final case class Clause(when: Option[ValueExpr] = None, node: LayoutNode)

  final case class Member(clauses: List[Clause] = Nil)

  /** A member as the wire holds it: its node alone, a lone guard as `by`, or
    * the first of several that holds, by its position.
    */
  def choice(m: Member): Choice[LayoutNode] = m.clauses match
    case List(Clause(None, n))    => Choice.Fixed(n)
    case List(Clause(Some(g), n)) =>
      Choice.Chosen(g, List(Case(Lit.Bool(true), n)))
    case cs =>
      Choice.Chosen(
        ValueExpr(
          Value.List(
            fh.view.model.ValueType.Bool,
            cs.map(_.when.getOrElse(ValueExpr(Value.Literal(Lit.Bool(true)))))
          ),
          List(Step.IndexOfTrue)
        ),
        cs.zipWithIndex.map((c, i) => Case(Lit.Whole(i.toLong), c.node))
      )

  def fill(
      candidates: List[String] = Nil,
      members: Map[String, Member] = Map.empty,
      orderBy: List[SortTerm] = Nil,
      limit: Option[Int] = None,
      of: IdKind = IdKind.Entity
  ): Fill.Set =
    Fill.Set(
      candidates,
      members.view.mapValues(choice).toMap,
      orderBy,
      limit,
      of
    )

  /** The set's node: the group card holding it. */
  def node(
      candidates: List[String] = Nil,
      members: Map[String, Member] = Map.empty,
      orderBy: List[SortTerm] = Nil,
      limit: Option[Int] = None,
      cell: Option[Cell] = None,
      of: IdKind = IdKind.Entity
  ): LayoutNode.Component =
    LayoutNode.Component(
      Card,
      Map(
        SlotName -> Slot.Nodes(fill(candidates, members, orderBy, limit, of))
      ),
      cell
    )

  extension (s: Fill.Set)
    /** `candidate`'s member as the clauses it was written from. */
    def clausesOf(candidate: String): List[Clause] = clauses(
      s.members(candidate)
    )

  /** What a node holds, if it is a set. */
  def of(n: LayoutNode): Option[Fill.Set] = n match
    case c: LayoutNode.Component => c.set.map(_._2)

  /** A member's clauses back: each guard, as the member's `by` spells it, and
    * its node.
    */
  def clauses(m: Choice[LayoutNode]): List[Clause] = m match
    case Choice.Fixed(n) => List(Clause(None, n))
    case Choice.Chosen(
          ValueExpr(Value.List(_, gs), List(Step.IndexOfTrue), _, _),
          cs,
          _
        ) =>
      gs.zip(cs).map((g, c) => Clause(Some(g), c.value))
    case Choice.Chosen(by, cs, _) => cs.map(c => Clause(Some(by), c.value))
