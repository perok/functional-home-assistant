package fh.view.runtime

import scala.jdk.CollectionConverters.*

import fh.view.FHError
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

  /** What every expression of one dashboard compiles against: its CEL programs
    * by key, and its slug, which CEL reads as `dashboard_slug`.
    */
  final case class Scope(programs: Cel.Key => Cel.Program, slug: String)

  object Scope:
    /** Each program built the first time it is asked for, once per scope, over
      * the process's checked trees: for a dashboard `validate` checked.
      */
    def onDemand(slug: String): Scope =
      val built =
        new java.util.concurrent.ConcurrentHashMap[Cel.Key, Cel.Program]()
      Scope(
        k =>
          built.computeIfAbsent(
            k,
            k => Cel.parse(k).fold(m => throw FHError.internal(m), identity)
          ),
        slug
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
  ): Either[ValueExpr.Mistyped, Compiled] =
    e.typed(env)
      .map(t =>
        Compiled(
          build(e, t.keys.iterator.map(scope.programs), scope, inputs),
          t.out
        )
      )

  /** The run of an expression `validate` typed, alone or as part of the one
    * holding it.
    */
  def runOf(
      e: ValueExpr,
      env: List[(String, ValueType)],
      scope: Scope,
      inputs: Inputs = noInputs
  ): Run =
    compile(e, env, scope, inputs)
      .fold(m => throw FHError.internal(m.message), _.run)

  /** Read per member or per item, with no named inputs. */
  private def inner(e: ValueExpr, scope: Scope): Run = runOf(e, Nil, scope)

  /** `programs`: each CEL's, in the order [[ValueExpr.typed]] keys them. */
  private def build(
      e: ValueExpr,
      programs: Iterator[Cel.Program],
      scope: Scope,
      inputs: Inputs
  ): Run = {
    // How a source is read, picked once from the step after it: where that
    // step can stop early, the items or candidates are read one at a time
    // and the list is never built. A list CEL built is already whole, so its
    // and/or is the step's own.
    val (start, rest) = (e.source, e.steps) match
      case (
            p: Value.Present,
            Step.Count :: Step.Compare(op, Value.Literal(Lit.Whole(n))) :: r
          ) =>
        (countIs(p.candidates, present(p, scope), op, n), r)
      case (Value.List(_, items), Step.AllOf :: r) =>
        (junction(items.map(inner(_, scope)), decides = false), r)
      case (Value.List(_, items), Step.AnyOf :: r) =>
        (junction(items.map(inner(_, scope)), decides = true), r)
      case (source, steps) => (read(source, programs, scope, inputs), steps)
    rest.foldLeft(start)((prev, s) => step(s, prev, programs, scope, inputs))
  }

  /** For readings `validate` keeps from a fetch: a condition, an order key. */
  val noFetch: FetchTemplate => Object = _ => ""

  /** A set's candidates present in a snapshot: what a call over it acts on. */
  def presentOf(
      p: Value.Present,
      scope: Scope
  ): Map[String, EntityState] => List[String] =
    val is = present(p, scope)
    states => p.candidates.filter(is(_, states))

  /** CEL's named inputs, each compiled once, read as CEL takes them: a fetch
    * its answer, a bool `true` only for `true`, anything else its value, `null`
    * CEL's `null`. Subject-free, as `validate` holds them.
    */
  def inputs(values: Map[String, ValueExpr], scope: Scope): Inputs =
    if (values.isEmpty) noInputs
    else
      val runs = values.toList.map { (name, e) =>
        val read
            : (Map[String, EntityState], FetchTemplate => Object) => Object =
          e.source match
            case Value.Fetch(f) => (_, fetched) => fetched(f)
            case _              =>
              val run = inner(e, scope)
              if (e.inputType.contains(ValueType.Bool))
                (states, fetched) =>
                  java.lang.Boolean.valueOf(holds(run(null, states, fetched)))
              else (states, fetched) => Cel.arg(run(null, states, fetched))
        name -> read
      }
      (states, fetched) => runs.map((n, r) => n -> r(states, fetched)).toMap

  /** An ordering, its keys read once per candidate rather than per comparison
    * (`publishSetOrdered`): a bool key sorts `true` first under `asc`; a
    * value's, no value last in either direction, else numbers on the number
    * line and text as text, numeric when both parse.
    */
  final class Keys(terms: List[SortTerm], scope: Scope) {
    private val runs = terms.map(t => inner(t.by, scope)).toArray
    private val bool =
      terms.map(_.by.typed(Nil).exists(_.out == ValueType.Bool)).toArray
    private val descending = terms.map(_.descending).toArray

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

  /** Only a real `true` holds. */
  def holds(v: Any): Boolean = v match
    case b: java.lang.Boolean => b.booleanValue
    case _                    => false

  private def read(
      v: Value,
      programs: Iterator[Cel.Program],
      scope: Scope,
      inputs: Inputs
  ): Run = v match
    // A `once` read is of which entity this is, which it is whether or not
    // the store holds it: a service picked by its domain.
    case Value.Entity(Some(id), part, Reads.Once, _) =>
      val identity = EntityState(id, "", Map.empty)
      (_, states, _) => Steps.read(part, states.getOrElse(id, identity))
    case Value.Entity(of, part, _, _) =>
      of match
        case None =>
          (subject, _, _) =>
            if (subject == null) null else Steps.read(part, subject)
        case Some(id) =>
          (subject, states, _) =>
            val st: EntityState | Null =
              if (subject != null && subject.entityId == id) subject
              else states.getOrElse(id, null)
            if (st == null) null else Steps.read(part, st)
    case Value.Literal(l) =>
      val c = l.raw
      (_, _, _) => c
    case p: Value.Present =>
      val is = present(p, scope)
      (_, states, _) => p.candidates.filter(is(_, states))
    case Value.List(_, items) =>
      val runs = items.map(inner(_, scope))
      (subject, states, fetched) => runs.map(_(subject, states, fetched))
    case _: Value.Cel =>
      val p = programs.next()
      (_, states, fetched) =>
        lift(Cel.eval(p, null, scope.slug, inputs(states, fetched)))
    // Its answer is drawn by its stage, never read here.
    case _: Value.Fetch => (_, _, _) => ""

  private def step(
      s: Step,
      prev: Run,
      programs: Iterator[Cel.Program],
      scope: Scope,
      inputs: Inputs
  ): Run = s match
    case _: Step.Cel =>
      val p = programs.next()
      (subject, states, fetched) =>
        val v = prev(subject, states, fetched)
        if (v == null) null
        else lift(Cel.eval(p, v, scope.slug, inputs(states, fetched)))
    case Step.First(orderBy) =>
      val keys = Keys(orderBy, scope)
      (subject, states, fetched) =>
        prev(subject, states, fetched) match
          case ids: List[?] => leader(ids, keys, states)
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
  ): (String, Map[String, EntityState]) => Boolean = {
    val guards = p.when.view.mapValues(inner(_, scope)).toMap
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
