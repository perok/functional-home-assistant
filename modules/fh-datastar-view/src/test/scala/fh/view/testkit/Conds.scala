package fh.view.testkit

import fh.view.runtime.Evaluator
import fh.view.model.{
  LayoutNode,
  Lit,
  NodeId,
  Op,
  Part,
  Ready,
  SortTerm,
  Step,
  Surface,
  TreeIndex,
  Value,
  ValueExpr,
  ValueType
}
import io.circe.Json

/** Conditions as `core/predicate.pkl` writes them, so a suite states one in the
  * author's terms: `cmp("state", Op.Eq, on)` rather than its reading.
  */
object Conds {

  /** A reading compiled alone, as the walk compiles one: a suite's own, so a
    * reading that does not compile fails the suite rather than returning.
    */
  def run(e: ValueExpr): Evaluator.Run =
    compiled(Evaluator.compile(e, Evaluator.Scope(""))).run

  /** `n` compiled as the root of a page, or of the popup `sid`. */
  def compiledAlone(n: LayoutNode): Ready.Component = {
    val i = TreeIndex.of(n, Map.empty)
    compiled(Ready.Compile(Evaluator.Scope("")).node(i, i.roots.head))
  }

  /** The set `s` fills, compiled where a graph places it, so its ids are the
    * graph's.
    */
  def compiledSet(at: NodeId, s: LayoutNode.Component): Ready.SetFill =
    compiledAlone(s.copy(id = Some(at))).content match
      case Ready.Content.Live(_, Ready.LiveFill.Set(f)) => f
      case other => throw AssertionError(s"not a set: $other")

  def compiledPopup(sid: String, s: Surface): Ready.Surface = {
    val i = TreeIndex.of(s.content, Map(sid -> s))
    val root = i.roots.filter(i.tree(_) == sid).head
    compiled(Ready.Compile(Evaluator.Scope("")).surface(i, root, s))
  }

  /** What compiled, as the walk would have it. */
  def compiled[A](b: Evaluator.Built[A]): A =
    b.fold(m => throw AssertionError(m.message), identity)

  private def lit(v: Json): Value.Operand =
    Value.Literal(v.as[Lit].fold(e => throw e, identity))

  /** `property` is `state` or `attr:<name>`; no `entity` is the one this is
    * about.
    */
  def cmp(
      property: String,
      op: Op,
      value: Json,
      entity: Option[String] = None
  ): ValueExpr =
    ValueExpr(
      Value.Entity(entity, partOf(property)),
      List(Step.Compare(op, lit(value)))
    )

  /** An order key: the member's `state` or `attr:<name>`, as `propertyKey`. */
  def prop(property: String): ValueExpr =
    ValueExpr(Value.Entity(None, partOf(property)))

  private def partOf(property: String): Part =
    if (property == "state") Part.State
    else Part.Attr(property.stripPrefix("attr:"))

  /** The empty one is the build's settled `true`, as `alwaysHolds` is. */
  def and(items: List[ValueExpr]): ValueExpr =
    if (items.isEmpty) always
    else ValueExpr(Value.List(ValueType.Bool, items), List(Step.AllOf))

  def or(items: List[ValueExpr]): ValueExpr =
    if (items.isEmpty) never
    else ValueExpr(Value.List(ValueType.Bool, items), List(Step.AnyOf))

  def not(item: ValueExpr): ValueExpr =
    item.copy(steps = item.steps :+ Step.Not)

  val always: ValueExpr = ValueExpr(Value.Literal(Lit.Bool(true)))
  val never: ValueExpr = ValueExpr(Value.Literal(Lit.Bool(false)))

  def count(
      candidates: List[String] = Nil,
      when: Map[String, ValueExpr] = Map.empty,
      op: Op,
      value: Json
  ): ValueExpr =
    ValueExpr(
      Value.Present(candidates, when),
      List(Step.Count, Step.Compare(op, lit(value)))
    )

  def first(
      candidates: List[String] = Nil,
      when: Map[String, ValueExpr] = Map.empty,
      orderBy: List[SortTerm] = Nil,
      entity: String
  ): ValueExpr =
    ValueExpr(
      Value.Present(candidates, when),
      List(
        Step.First(orderBy),
        Step.Compare(Op.Eq, Value.Literal(Lit.Text(entity)))
      )
    )
}
