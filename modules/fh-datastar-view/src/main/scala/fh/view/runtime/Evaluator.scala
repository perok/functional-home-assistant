package fh.view.runtime

import scala.jdk.CollectionConverters.*

import cats.syntax.all.*
import fh.view.model.{
  FetchTemplate,
  Lit,
  Op,
  Reads,
  SortTerm,
  Step,
  Value,
  ValueExpr,
  ValueType
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

  /** A compile's answer: an expression that does not compile says why. */
  type Built[A] = Either[ValueExpr.Mistyped, A]

  /** What every expression of one dashboard compiles against: its slug, which
    * CEL reads as `dashboard_slug`, and its CEL programs, each built the first
    * time it is asked for.
    */
  final class Scope(val slug: String):
    private val built =
      new java.util.concurrent.ConcurrentHashMap[Cel.Key, Built[Cel.Program]]()

    def program(k: Cel.Key): Built[Cel.Program] =
      built.computeIfAbsent(
        k,
        k => Cel.parse(k).left.map(ValueExpr.Mistyped(_))
      )

  /** An expression made ready to run, and the type it answers. */
  final case class Compiled(run: Run, out: ValueType)

  /** Typed against `env`, the named inputs its CEL reads, and compiled: every
    * expression inside it the same way, through the same [[Scope]].
    */
  def compile(
      e: ValueExpr,
      env: List[(String, ValueType)],
      scope: Scope,
      inputs: Inputs = noInputs
  ): Built[Compiled] =
    e.typed(env).flatMap(compiled(e, _, scope, inputs))

  /** From a typing already done, `validate`'s, so nothing is typed twice. */
  def compiled(
      e: ValueExpr,
      typed: ValueExpr.Typed,
      scope: Scope,
      inputs: Inputs = noInputs
  ): Built[Compiled] =
    typed.keys
      .traverse(scope.program)
      .flatMap(ps => build(e, ps.iterator, scope, inputs))
      .map(Compiled(_, typed.out))

  /** Read per member or per item, with no named inputs. */
  private def inner(e: ValueExpr, scope: Scope): Built[Compiled] =
    compile(e, Nil, scope)

  /** `programs`: each CEL's, in the order [[ValueExpr.typed]] keys them. */
  private def build(
      e: ValueExpr,
      programs: Iterator[Cel.Program],
      scope: Scope,
      inputs: Inputs
  ): Built[Run] = {
    // How a source is read, picked once from the step after it: where that
    // step can stop early, the items or candidates are read one at a time
    // and the list is never built. A list CEL built is already whole, so its
    // and/or is the step's own.
    val start: Built[(Run, List[Step])] = (e.source, e.steps) match
      case (
            p: Value.Present,
            Step.Count :: Step.Compare(op, Value.Literal(Lit.Whole(n))) :: r
          ) =>
        present(p, scope).map(is => (countIs(p.candidates, is, op, n), r))
      case (Value.List(_, items), Step.AllOf :: r) =>
        runs(items, scope).map(rs => (junction(rs, decides = false), r))
      case (Value.List(_, items), Step.AnyOf :: r) =>
        runs(items, scope).map(rs => (junction(rs, decides = true), r))
      case (source, steps) =>
        read(source, programs, scope, inputs).map(_ -> steps)
    start.flatMap((run, rest) =>
      rest.foldLeftM(run)((prev, s) => step(s, prev, programs, scope, inputs))
    )
  }

  private def runs(items: List[ValueExpr], scope: Scope): Built[List[Run]] =
    items.traverse(inner(_, scope).map(_.run))

  /** For readings `validate` keeps from a fetch: a condition, an order key. */
  val noFetch: FetchTemplate => Object = _ => ""

  /** A set's candidates present in a snapshot: what a call over it acts on. */
  def presentOf(
      p: Value.Present,
      scope: Scope
  ): Built[Map[String, EntityState] => List[String]] =
    present(p, scope).map(is => states => p.candidates.filter(is(_, states)))

  /** CEL's named inputs, each compiled once, read as CEL takes them: a fetch
    * its answer, a bool `true` only for `true`, anything else its value, `null`
    * CEL's `null`. Subject-free, as `validate` holds them.
    */
  def inputs(values: Map[String, ValueExpr], scope: Scope): Built[Inputs] =
    if (values.isEmpty) Right(noInputs)
    else
      values.toList
        .traverse { (name, e) =>
          val read: Built[
            (Map[String, EntityState], FetchTemplate => Object) => Object
          ] =
            e.source match
              case Value.Fetch(f) => Right((_, fetched) => fetched(f))
              case _              =>
                inner(e, scope).map(c =>
                  val run = c.run
                  if (e.inputType.contains(ValueType.Bool))
                    (states, fetched) =>
                      java.lang.Boolean
                        .valueOf(holds(run(null, states, fetched)))
                  else (states, fetched) => Cel.arg(run(null, states, fetched))
                )
          read.map(name -> _)
        }
        .map(runs =>
          (states, fetched) => runs.map((n, r) => n -> r(states, fetched)).toMap
        )

  /** An ordering, its keys read once per candidate rather than per comparison
    * (`publishSetOrdered`): a bool key sorts `true` first under `asc`; a
    * value's, no value last in either direction, else numbers on the number
    * line and text as text, numeric when both parse.
    */
  final class Keys private (
      runs: Array[Run],
      bool: Array[Boolean],
      descending: Array[Boolean]
  ) {

    /** A candidate the store lacks reads as nothing: false, or no value. */
    def of(id: String, states: Map[String, EntityState]): Array[Any] =
      val st = states.getOrElse(id, null)
      val out = new Array[Any](runs.length)
      var i = 0
      while (i < runs.length) {
        out(i) = runs(i)(st, states, noFetch)
        i += 1
      }
      out

    /** Negative when `a` sorts first; `0` keeps a stable sort's order. */
    def compare(a: Array[Any], b: Array[Any]): Int =
      var i = 0
      var c = 0
      while (c == 0 && i < runs.length) {
        c = term(i, a(i), b(i))
        i += 1
      }
      c

    private def term(i: Int, l: Any, r: Any): Int =
      def directed(raw: Int) = if (descending(i)) -raw else raw
      if (bool(i)) directed(java.lang.Boolean.compare(holds(r), holds(l)))
      // Last in either direction: `unknown` is no value, not the largest
      // string, which under `desc` crowned a scene never activated.
      else if (noValue(l) || noValue(r))
        java.lang.Boolean.compare(noValue(l), noValue(r))
      else
        directed((Steps.number(l), Steps.number(r)) match
          case (Some(x), Some(y)) => java.lang.Double.compare(x, y)
          case _                  => Steps.text(l).compareTo(Steps.text(r)))

    private def noValue(v: Any): Boolean = v match
      case null      => true
      case s: String => s.isEmpty || EntityState.unavailableStates.contains(s)
      case _         => false
  }

  object Keys:
    def of(terms: List[SortTerm], scope: Scope): Built[Keys] =
      terms
        .traverse(t => inner(t.by, scope))
        .map(cs =>
          Keys(
            cs.map(_.run).toArray,
            cs.map(_.out == ValueType.Bool).toArray,
            terms.map(_.descending).toArray
          )
        )

  /** Only a real `true` holds. */
  def holds(v: Any): Boolean = v match
    case b: java.lang.Boolean => b.booleanValue
    case _                    => false

  private def read(
      v: Value,
      programs: Iterator[Cel.Program],
      scope: Scope,
      inputs: Inputs
  ): Built[Run] = v match
    // A `once` read is of which entity this is, which it is whether or not
    // the store holds it: a service picked by its domain.
    case Value.Entity(Some(id), part, Reads.Once, _) =>
      val identity = EntityState(id, "", Map.empty)
      Right((_, states, _) => Steps.read(part, states.getOrElse(id, identity)))
    case Value.Entity(of, part, _, _) =>
      Right(of match
        case None =>
          (subject, _, _) =>
            if (subject == null) null else Steps.read(part, subject)
        case Some(id) =>
          (subject, states, _) =>
            val st: EntityState | Null =
              if (subject != null && subject.entityId == id) subject
              else states.getOrElse(id, null)
            if (st == null) null else Steps.read(part, st))
    case Value.Literal(l) =>
      val c = l.raw
      Right((_, _, _) => c)
    case p: Value.Present =>
      present(p, scope).map(is =>
        (_, states, _) => p.candidates.filter(is(_, states))
      )
    case Value.List(_, items) =>
      runs(items, scope).map(rs =>
        (subject, states, fetched) => rs.map(_(subject, states, fetched))
      )
    case _: Value.Cel =>
      val p = programs.next()
      Right((_, states, fetched) =>
        lift(Cel.eval(p, null, scope.slug, inputs(states, fetched)))
      )
    // Its answer is drawn by its stage, never read here.
    case _: Value.Fetch => Right((_, _, _) => "")

  private def step(
      s: Step,
      prev: Run,
      programs: Iterator[Cel.Program],
      scope: Scope,
      inputs: Inputs
  ): Built[Run] = s match
    case _: Step.Cel =>
      val p = programs.next()
      Right((subject, states, fetched) =>
        val v = prev(subject, states, fetched)
        if (v == null) null
        else lift(Cel.eval(p, v, scope.slug, inputs(states, fetched)))
      )
    case Step.First(orderBy) =>
      Keys
        .of(orderBy, scope)
        .map(keys =>
          (subject, states, fetched) =>
            prev(subject, states, fetched) match
              case ids: List[?] => leader(ids, keys, states)
              case _            => null
        )
    case f: Step.Simple => Right(simple(f, prev))
    // The renderer's own, applied after the run.
    case _: Step.Stage => Right(prev)

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
          else Steps.formatWith(f, v, null, null)
      case List(a) =>
        (subject, states, fetched) =>
          val v = prev(subject, states, fetched)
          if (v == null && strictIn) null
          else
            val x = a(subject, states)
            if (x == null && strictOps) null
            else Steps.formatWith(f, v, x, null)
      case a :: b :: _ =>
        (subject, states, fetched) =>
          val v = prev(subject, states, fetched)
          if (v == null && strictIn) null
          else
            val x = a(subject, states)
            val y = b(subject, states)
            if ((x == null || y == null) && strictOps) null
            else Steps.formatWith(f, v, x, y)
  }

  /** A step's operand: a literal, read once here, or an entity's part. */
  private def operand(
      v: Value.Operand
  ): (EntityState | Null, Map[String, EntityState]) => Any =
    v match
      case Value.Literal(l) =>
        val c = l.raw
        (_, _) => c
      case Value.Entity(Some(id), part, _, _) =>
        (_, states) =>
          val st = states.getOrElse(id, null)
          if (st == null) null else Steps.read(part, st)
      case Value.Entity(None, part, _, _) =>
        (subject, _) => if (subject == null) null else Steps.read(part, subject)

  /** CEL's optional is the value or `null` once it leaves the step, and its
    * list the `List` every list step reads: a Java list read as no value in
    * each of them.
    */
  private def lift(v: Any): Any = v match
    case o: java.util.Optional[?] => if (o.isPresent) o.get else null
    case l: java.util.List[?]     => List.from(l.iterator.asScala)
    case other                    => other

  /** Whether a candidate is present: unguarded, or its guard holds over it. */
  private def present(
      p: Value.Present,
      scope: Scope
  ): Built[(String, Map[String, EntityState]) => Boolean] =
    p.when.toList
      .traverse((id, g) => inner(g, scope).map(id -> _.run))
      .map { list =>
        val guards = list.toMap
        (id, states) =>
          val g = guards.getOrElse(id, null)
          g == null || {
            val st = states.getOrElse(id, null)
            st != null && holds(g(st, states, noFetch))
          }
      }

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

  /** An and/or over a list source's items, each run only until one decides. */
  private def junction(items: List[Run], decides: Boolean): Run =
    (subject, states, fetched) =>
      var rest = items
      Steps.kleene(decides)(rest.nonEmpty) {
        val v = rest.head(subject, states, fetched)
        rest = rest.tail
        v
      }

  /** One pass, each key read once; the earliest wins a tie, as a set's stable
    * sort keeps candidate order.
    */
  private def leader(
      ids: List[?],
      keys: Keys,
      states: Map[String, EntityState]
  ): Any =
    var best: String | Null = null
    var bestKeys: Array[Any] = Array.empty
    ids.foreach {
      case id: String =>
        val k = keys.of(id, states)
        if (best == null || keys.compare(k, bestKeys) < 0) {
          best = id
          bestKeys = k
        }
      case _ => ()
    }
    best
}
