package fh.view.runtime

import fh.view.model.{
  FetchTemplate,
  LayoutNode,
  Lit,
  Op,
  Part,
  Step,
  Transform,
  Value,
  ValueExpr
}

/** The one interpreter of a condition, a [[ValueExpr.isCondition]]: clause
  * guards, state surfaces, presence, order terms and a condition filling a slot
  * or a CEL input.
  */
private[view] object Conditions {

  /** A bool [[ValueExpr]] decided: an unnamed read reads `subject`. */
  def holds(
      e: ValueExpr,
      subject: EntityState,
      states: Map[String, EntityState]
  ): Boolean =
    run(e, subject, states) match
      case b: Boolean => b
      case _          => false

  /** A [[ValueExpr]] read as a condition reads it: an attribute as its JSON
    * text, so `21.0` is not `21`, and an entity the snapshot lacks fails the
    * comparison over it rather than reading empty. A list, or a set's ids,
    * decided by its first steps stops early and builds no list.
    */
  def eval(
      e: ValueExpr,
      subject: EntityState,
      states: Map[String, EntityState]
  ): Any =
    run(e, subject, states) match
      case Missing => ""
      case v       => v

  private def run(
      e: ValueExpr,
      subject: EntityState,
      states: Map[String, EntityState]
  ): Any =
    // Allocation-free on the common shapes: this runs per guard per member per
    // change (`RenderBench.condition`, `publishSet`).
    e.source match
      case Value.List(items) =>
        e.steps match
          case Step.AllOf :: rest =>
            after(
              items.forall(holds(_, subject, states)),
              rest,
              subject,
              states
            )
          case Step.AnyOf :: rest =>
            after(
              items.exists(holds(_, subject, states)),
              rest,
              subject,
              states
            )
          case Step.Count :: rest =>
            after(items.size.toLong, rest, subject, states)
          case steps =>
            after(read(e.source, subject, states), steps, subject, states)
      case p: Value.Present =>
        e.steps match
          case Step.Count :: Step.Compare(op, Value.Literal(Lit.Whole(n))) ::
              rest =>
            after(countIs(p, op, n, states), rest, subject, states)
          case Step.Count :: rest =>
            after(present(p, states).toLong, rest, subject, states)
          case Step.First(orderBy) :: rest =>
            after(
              leader(p, orderBy, states).getOrElse(""),
              rest,
              subject,
              states
            )
          case steps => after(presentOf(p, states), steps, subject, states)
      case source =>
        after(read(source, subject, states), e.steps, subject, states)

  @scala.annotation.tailrec
  private def after(
      v: Any,
      steps: List[Step],
      subject: EntityState,
      states: Map[String, EntityState]
  ): Any = steps match
    case Nil       => v
    case s :: rest => after(step(s, v, subject, states), rest, subject, states)

  private def step(
      s: Step,
      v: Any,
      subject: EntityState,
      states: Map[String, EntityState]
  ): Any = s match
    case Step.Compare(op, to) =>
      read(to, subject, states) match
        case Missing => false
        case rhs     =>
          v match
            case Missing => false
            case _       => compare(Transform.text(v), Transform.text(rhs), op)
    case Step.Not =>
      v match
        case b: Boolean => !b
        case other      => other
    case f: Step.Simple =>
      v match
        case Missing => Missing
        case _       =>
          Step.operands(f).map(read(_, subject, states)) match
            case ops if ops.contains(Missing) => Missing
            case ops                          =>
              Transform.formatWith(f, v, ops.lift(0).orNull, ops.lift(1).orNull)
    // CEL, a stage or a misplaced `First`, which `validate` refuses in a
    // condition (`ValueExpr.conditionError`).
    case _ => v

  /** An entity the snapshot lacks, which no comparison over holds. */
  private object Missing

  private def read(
      v: Value,
      subject: EntityState,
      states: Map[String, EntityState]
  ): Any = v match
    case Value.Literal(Lit.Bool(b))   => b
    case Value.Literal(l)             => l.raw.toString
    case Value.Entity(of, part, _, _) =>
      val st: EntityState | Null = of match
        case None     => subject
        case Some(id) => states.getOrElse(id, null)
      if (st == null) Missing
      else
        part match
          case Part.State      => st.state
          case Part.Attr(name) =>
            val j = st.attributes.getOrElse(name, null)
            if (j == null) "" else StateStore.jsonToString(j)
          case Part.Whole => st.celValue
    case p: Value.Present              => presentOf(p, states)
    case Value.List(items)             => items.map(eval(_, subject, states))
    case _: Value.Fetch | _: Value.Cel => ""

  /** Named inputs as CEL reads them: a literal as written, a condition whether
    * it holds, a count how many are present now and the ids themselves without
    * one, a fetch its answer (`fetched`), an entity's part as it reads now.
    */
  def resolve(
      inputs: Map[String, ValueExpr],
      states: Map[String, EntityState],
      fetched: FetchTemplate => Object
  ): Map[String, Object] =
    if (inputs.isEmpty) Map.empty
    else
      inputs.view
        .mapValues[Object] {
          // Subject-free (`validate`), as a state condition is.
          case v if v.isCondition =>
            java.lang.Boolean.valueOf(holds(v, EntityState.none, states))
          case ValueExpr(Value.Literal(Lit.Text(s)), _, _) => s
          case ValueExpr(Value.Literal(Lit.Bool(b)), _, _) =>
            java.lang.Boolean.valueOf(b)
          case ValueExpr(Value.Literal(Lit.Whole(n)), _, _) =>
            java.lang.Long.valueOf(n)
          case ValueExpr(Value.Literal(Lit.Real(d)), _, _) =>
            java.lang.Double.valueOf(d)
          case ValueExpr(p: Value.Present, Nil, _) =>
            Cel.arg(presentOf(p, states))
          case ValueExpr(p: Value.Present, _, _) =>
            java.lang.Long.valueOf(present(p, states).toLong)
          case ValueExpr(Value.Fetch(f), _, _)                     => fetched(f)
          case ValueExpr(Value.Entity(Some(id), part, _, _), _, _) =>
            Cel.arg(
              Transform.read(
                part,
                states.getOrElse(id, EntityState(id, "", Map.empty))
              )
            )
          // `validate` refuses any other shape (`ValueExpr.inputType`).
          case _ => ""
        }
        .toMap

  /** Unguarded means present, as for a set member. */
  def present(p: Value.Present, states: Map[String, EntityState]): Int =
    p.candidates.count(isPresent(p, _, states))

  /** What [[present]] counts: the ids a call over the set acts on. */
  def presentOf(
      p: Value.Present,
      states: Map[String, EntityState]
  ): List[String] =
    p.candidates.filter(isPresent(p, _, states))

  private def isPresent(
      p: Value.Present,
      id: String,
      states: Map[String, EntityState]
  ): Boolean =
    val guard = p.when.getOrElse(id, null)
    guard == null || {
      val st = states.getOrElse(id, null)
      st != null && holds(guard, st, states)
    }

  /** `present(p) op n`, stopping once the candidates left cannot change it:
    * `> 0` at the first present, `== length` at the first absent.
    */
  private def countIs(
      p: Value.Present,
      op: Op,
      n: Long,
      states: Map[String, EntityState]
  ): Boolean =
    def is(v: Long): Boolean = op match
      case Op.Eq  => v == n
      case Op.Ne  => v != n
      case Op.Lt  => v < n
      case Op.Lte => v <= n
      case Op.Gt  => v > n
      case Op.Gte => v >= n
    // Every count in [lo, hi] is still reachable; settled when they all agree.
    def settled(lo: Long, hi: Long): Boolean = op match
      case Op.Eq | Op.Ne => lo == hi || n < lo || n > hi
      case _             => is(lo) == is(hi)
    var lo = 0L
    var hi = p.candidates.size.toLong
    var rest = p.candidates
    while (rest.nonEmpty && !settled(lo, hi)) {
      if (isPresent(p, rest.head, states)) lo += 1 else hi -= 1
      rest = rest.tail
    }
    is(lo)

  /** The earliest wins a tie, as a set's stable sort keeps candidate order. */
  private def leader(
      p: Value.Present,
      orderBy: List[LayoutNode.SortTerm],
      states: Map[String, EntityState]
  ): Option[String] =
    presentOf(p, states).reduceLeftOption((best, id) =>
      if (MemberGraph.precedes(orderBy, id, best, states)) id else best
    )

  // Ordering is numeric, false unless both parse; equality compares strings.
  private[view] def compare(lhs: String, rhs: String, op: Op): Boolean = {
    def numeric(cmp: (Double, Double) => Boolean): Boolean =
      (lhs.toDoubleOption, rhs.toDoubleOption) match {
        case (Some(l), Some(r)) => cmp(l, r)
        case _                  => false
      }
    op match {
      case Op.Eq  => lhs == rhs
      case Op.Ne  => lhs != rhs
      case Op.Lt  => numeric(_ < _)
      case Op.Lte => numeric(_ <= _)
      case Op.Gt  => numeric(_ > _)
      case Op.Gte => numeric(_ >= _)
    }
  }
}
