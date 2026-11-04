package fh.view.runtime

import fh.view.model.{
  FetchTemplate,
  LayoutNode,
  Lit,
  Op,
  Reads,
  Step,
  Transform,
  Value,
  ValueExpr
}

/** The compiler of a [[ValueExpr]] into the function that answers it, built
  * once so a call walks no syntax (SICP's `analyze`). `null` is the one absent
  * value, made only by a read or a step with no result, and every step is
  * strict, in its input and its operands, except those that say what they make
  * of `null`: `Match`, and/or, and `WithUnit`'s unit. CEL is strict too; what
  * is absent inside a value (an attribute) its own optionals handle.
  */
private[view] object Evaluator {

  /** Over the subject an unnamed read reads (`null` when there is none), the
    * states, and the fetches' answers.
    */
  type Run =
    (
        EntityState | Null,
        Map[String, EntityState],
        FetchTemplate => Object
    ) => Any

  /** CEL's named inputs as the slot resolves them. */
  type Inputs =
    (Map[String, EntityState], FetchTemplate => Object) => Map[String, Object]

  private val noInputs: Inputs = (_, _) => Map.empty

  /** `programs`: each CEL's, in the order [[ValueExpr.typed]] keys them. */
  def compile(
      e: ValueExpr,
      programs: Iterator[Transform.Compiled],
      slug: String,
      inputs: Inputs
  ): Run = {
    // The early stops, picked once: each decides without building the list.
    val (start, rest) = (e.source, e.steps) match
      case (
            p: Value.Present,
            Step.Count :: Step.Compare(op, Value.Literal(Lit.Whole(n))) :: r
          ) =>
        (countIs(p.candidates, present(p, slug), op, n), r)
      case (Value.List(items), Step.AllOf :: r) =>
        (kleene(items.map(plain(_, slug)), decides = false), r)
      case (Value.List(items), Step.AnyOf :: r) =>
        (kleene(items.map(plain(_, slug)), decides = true), r)
      case (source, steps) => (read(source, programs, slug, inputs), steps)
    rest.foldLeft(start)((prev, s) => step(s, prev, programs, slug, inputs))
  }

  /** A reading with no CEL: a list's item, a guard, an order key. */
  def plain(e: ValueExpr, slug: String): Run =
    compile(e, Iterator.empty, slug, noInputs)

  /** Only a real `true` holds. */
  def holds(v: Any): Boolean = v match
    case b: java.lang.Boolean => b.booleanValue
    case _                    => false

  private def read(
      v: Value,
      programs: Iterator[Transform.Compiled],
      slug: String,
      inputs: Inputs
  ): Run = v match
    // A `once` read is of which entity this is, which it is whether or not
    // the store holds it: a service picked by its domain.
    case Value.Entity(Some(id), part, Reads.Once, _) =>
      val identity = EntityState(id, "", Map.empty)
      (_, states, _) => Transform.read(part, states.getOrElse(id, identity))
    case Value.Entity(of, part, _, _) =>
      of match
        case None =>
          (subject, _, _) =>
            if (subject == null) null else Transform.read(part, subject)
        case Some(id) =>
          (subject, states, _) =>
            val st: EntityState | Null =
              if (subject != null && subject.entityId == id) subject
              else states.getOrElse(id, null)
            if (st == null) null else Transform.read(part, st)
    case Value.Literal(l) =>
      val c = l.raw
      (_, _, _) => c
    case p: Value.Present =>
      val is = present(p, slug)
      (_, states, _) => p.candidates.filter(is(_, states))
    case Value.List(items) =>
      val runs = items.map(plain(_, slug))
      (subject, states, fetched) => runs.map(_(subject, states, fetched))
    case _: Value.Cel =>
      val p = programs.next()
      (_, states, fetched) =>
        lift(Cel.eval(p, null, slug, inputs(states, fetched)))
    // Its answer is drawn by its stage, never read here.
    case _: Value.Fetch => (_, _, _) => ""

  private def step(
      s: Step,
      prev: Run,
      programs: Iterator[Transform.Compiled],
      slug: String,
      inputs: Inputs
  ): Run = s match
    case _: Step.Cel =>
      val p = programs.next()
      (subject, states, fetched) =>
        val v = prev(subject, states, fetched)
        if (v == null) null
        else lift(Cel.eval(p, v, slug, inputs(states, fetched)))
    case Step.First(orderBy) =>
      (subject, states, fetched) =>
        prev(subject, states, fetched) match
          case ids: List[?] => leader(ids, orderBy, states)
          case _            => null
    case f: Step.Simple => simple(f, prev)
    // The renderer's own, applied after the run.
    case _: Step.Stage => prev

  private def simple(f: Step.Simple, prev: Run): Run = {
    val ops = Step.operands(f).map(operand)
    // `WithUnit`'s unit is optional by its spelling; `Match` takes `null` as
    // the value no case names.
    val strictIn = f match
      case _: Step.Match => false
      case _             => true
    val strictOps = f match
      case _: Step.WithUnit => false
      case _                => true
    ops match
      case Nil =>
        (subject, states, fetched) =>
          val v = prev(subject, states, fetched)
          if (v == null && strictIn) null
          else Transform.formatWith(f, v, null, null)
      case List(a) =>
        (subject, states, fetched) =>
          val v = prev(subject, states, fetched)
          if (v == null && strictIn) null
          else
            val x = a(subject, states)
            if (x == null && strictOps) null
            else Transform.formatWith(f, v, x, null)
      case a :: b :: _ =>
        (subject, states, fetched) =>
          val v = prev(subject, states, fetched)
          if (v == null && strictIn) null
          else
            val x = a(subject, states)
            val y = b(subject, states)
            if ((x == null || y == null) && strictOps) null
            else Transform.formatWith(f, v, x, y)
  }

  /** A step's operand: a literal, read once here, or an entity's part. */
  private def operand(
      v: Value
  ): (EntityState | Null, Map[String, EntityState]) => Any =
    v match
      case Value.Literal(l) =>
        val c = l.raw
        (_, _) => c
      case Value.Entity(Some(id), part, _, _) =>
        (_, states) =>
          val st = states.getOrElse(id, null)
          if (st == null) null else Transform.read(part, st)
      case Value.Entity(None, part, _, _) =>
        (subject, _) =>
          if (subject == null) null else Transform.read(part, subject)
      case _ => (_, _) => null

  /** CEL's optional is the value or `null` once it leaves the step. */
  private def lift(v: Any): Any = v match
    case o: java.util.Optional[?] => if (o.isPresent) o.get else null
    case other                    => other

  /** Whether a candidate is present: unguarded, or its guard holds over it. */
  private def present(
      p: Value.Present,
      slug: String
  ): (String, Map[String, EntityState]) => Boolean = {
    val guards = p.when.view.mapValues(plain(_, slug)).toMap
    (id, states) =>
      val g = guards.getOrElse(id, null)
      g == null || {
        val st = states.getOrElse(id, null)
        st != null && holds(g(st, states, noFetch))
      }
  }

  private val noFetch: FetchTemplate => Object = _ => ""

  /** `present op n`, stopping once the candidates left cannot change it: `> 0`
    * at the first present, `== length` at the first absent.
    */
  private def countIs(
      candidates: List[String],
      is: (String, Map[String, EntityState]) => Boolean,
      op: Op,
      n: Long
  ): Run = {
    def holdsAt(v: Long): Boolean = op match
      case Op.Eq  => v == n
      case Op.Ne  => v != n
      case Op.Lt  => v < n
      case Op.Lte => v <= n
      case Op.Gt  => v > n
      case Op.Gte => v >= n
    // Every count in [lo, hi] is still reachable; settled when they all agree.
    def settled(lo: Long, hi: Long): Boolean = op match
      case Op.Eq | Op.Ne => lo == hi || n < lo || n > hi
      case _             => holdsAt(lo) == holdsAt(hi)
    val size = candidates.size.toLong
    (_, states, _) =>
      var lo = 0L
      var hi = size
      var rest = candidates
      while (rest.nonEmpty && !settled(lo, hi)) {
        if (is(rest.head, states)) lo += 1 else hi -= 1
        rest = rest.tail
      }
      holdsAt(lo)
  }

  /** And (`decides = false`) or or (`true`), Kleene's: the value that decides
    * stops it, else `null` if any item had none.
    */
  private def kleene(items: List[Run], decides: Boolean): Run =
    (subject, states, fetched) =>
      var rest = items
      var unknown = false
      var decided = false
      while (rest.nonEmpty) {
        rest.head(subject, states, fetched) match
          case b: java.lang.Boolean if b.booleanValue == decides =>
            decided = true
            rest = Nil
          case _: java.lang.Boolean => rest = rest.tail
          case _                    =>
            unknown = true
            rest = rest.tail
      }
      if (decided) decides else if (unknown) null else !decides

  /** The earliest wins a tie, as a set's stable sort keeps candidate order. */
  private def leader(
      ids: List[?],
      orderBy: List[LayoutNode.SortTerm],
      states: Map[String, EntityState]
  ): Any =
    ids
      .collect { case id: String => id }
      .reduceLeftOption((best, id) =>
        if (MemberGraph.precedes(orderBy, id, best, states)) id else best
      ) match
      case Some(id) => id
      case None     => null
}
