package fh.view.runtime

import scala.jdk.CollectionConverters.*

import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{
  FetchTemplate,
  Lit,
  Op,
  Reads,
  SlotAsk,
  SlotRead,
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
    * states, and what else is in hand.
    */
  type Run =
    (
        EntityState | Null,
        Map[String, EntityState],
        Lookup
    ) => Any

  /** What a run reads besides state: the fetches' answers, and the node
    * variables at the node it runs for. A read of either that is not in hand is
    * a wiring bug, so it throws: a decision has no viewer, so no variables.
    */
  trait Lookup:
    def fetched(f: FetchTemplate): Object
    def variable(name: String): String

  object Lookup:
    /** Answers and no variables: a decision's. */
    def answering(answer: FetchTemplate => Object): Lookup = new Lookup:
      def fetched(f: FetchTemplate): Object = answer(f)
      def variable(name: String): String = noVariable(name)

  def noVariable(name: String): Nothing =
    throw FHError.internal(
      s"a value read the variable '$name' where no viewer's values are in hand"
    )

  /** A CEL's named inputs, as it reads them. */
  type Inputs =
    (Map[String, EntityState], Lookup) => Map[String, Object]

  private val noInputs: Inputs = (_, _) => Map.empty

  /** A named input made ready, read as CEL takes it: a fetch its answer, a bool
    * `true` only for `true`, anything else its value, `null` CEL's `null`.
    * Subject-free, as `validate` holds them.
    */
  final case class Named(
      read: (Map[String, EntityState], Lookup) => Object,
      out: ValueType
  )

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

  /** Typed with `named` in scope, and compiled: every expression inside it the
    * same way, through the same [[Scope]].
    */
  def compile(
      e: ValueExpr,
      scope: Scope,
      named: Map[String, Named] = Map.empty
  ): Built[Compiled] =
    e.typed(env(named)).flatMap(compiled(e, _, scope, named))

  /** From a typing already done, `validate`'s, so nothing is typed twice. */
  def compiled(
      e: ValueExpr,
      typed: ValueExpr.Typed,
      scope: Scope,
      named: Map[String, Named] = Map.empty
  ): Built[Compiled] =
    typed.keys
      .traverse(k => scope.program(k).map(k -> _))
      .flatMap(ps => build(e, ps.iterator, scope, named))
      .map(Compiled(_, typed.out))

  /** The types `named` declares, as [[ValueExpr.typed]] takes them. */
  def env(named: Map[String, Named]): List[(String, ValueType)] =
    named.toList.map((n, v) => n -> v.out).sortBy(_._1)

  /** Each value made a named input; `outer` is in scope of a CEL inside one, as
    * a node's inputs are of a CEL's own.
    */
  def named(
      inputs: Map[String, ValueExpr],
      scope: Scope,
      outer: Map[String, Named] = Map.empty
  ): Built[Map[String, Named]] =
    inputs.toList
      .traverse((n, e) => input(e, scope, outer).map(n -> _))
      .map(_.toMap)

  private def input(
      e: ValueExpr,
      scope: Scope,
      outer: Map[String, Named]
  ): Built[Named] = e.source match
    case Value.Fetch(f) if e.steps.isEmpty =>
      Right(Named((_, in) => in.fetched(f), ValueType.Series))
    case _ =>
      compile(e, scope, outer).map { c =>
        val run = c.run
        if (c.out == ValueType.Bool)
          Named(
            (states, fetched) =>
              java.lang.Boolean.valueOf(holds(run(null, states, fetched))),
            c.out
          )
        else
          Named((states, fetched) => Cel.arg(run(null, states, fetched)), c.out)
      }

  /** What one CEL reads by name: those its key declares. */
  def activation(names: List[(String, Named)]): Inputs =
    if (names.isEmpty) noInputs
    else
      (states, fetched) =>
        names.map((n, v) => n -> v.read(states, fetched)).toMap

  /** Its own inputs, `named` beside them, its own winning. */
  private def celInputs(
      k: Cel.Key,
      own: Map[String, ValueExpr],
      scope: Scope,
      named: Map[String, Named]
  ): Built[Inputs] =
    Evaluator.named(own, scope, named).map { mine =>
      val all = named ++ mine
      activation(k.env.map((n, _) => n -> all(n)))
    }

  /** Read per member, with no named inputs. */
  private def inner(e: ValueExpr, scope: Scope): Built[Compiled] =
    compile(e, scope)

  /** `programs`: each CEL's, in the order [[ValueExpr.typed]] keys them. */
  private def build(
      e: ValueExpr,
      programs: Iterator[(Cel.Key, Cel.Program)],
      scope: Scope,
      named: Map[String, Named]
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
        runs(items, scope, named).map(rs => (junction(rs, decides = false), r))
      case (Value.List(_, items), Step.AnyOf :: r) =>
        runs(items, scope, named).map(rs => (junction(rs, decides = true), r))
      case (source, steps) =>
        read(source, programs, scope, named).map(_ -> steps)
    start.flatMap((run, rest) =>
      rest.foldLeftM(run)((prev, s) => step(s, prev, programs, scope, named))
    )
  }

  /** A list's items, in their expression's scope, as `validate` types them. */
  private def runs(
      items: List[ValueExpr],
      scope: Scope,
      named: Map[String, Named]
  ): Built[List[Run]] =
    items.traverse(compile(_, scope, named).map(_.run))

  /** Where no fetch was answered: a run that reads one is a wiring bug, so loud
    * rather than an empty series.
    */
  val unanswered: Lookup = new Lookup:
    def fetched(f: FetchTemplate): Object =
      throw FHError.internal(
        s"a value read the fetch '${f.key}' where none is answered"
      )
    def variable(name: String): String = noVariable(name)

  /** A set's candidates present in a snapshot: what a call over it acts on. */
  def presentOf(
      p: Value.Present,
      scope: Scope
  ): Built[
    (Map[String, EntityState], Lookup) => List[String]
  ] =
    present(p, scope).map(is =>
      (states, fetched) => p.candidates.filter(is(_, states, fetched))
    )

  /** An ordering, its keys read once per candidate rather than per comparison
    * (`publishSetOrdered`): a bool key sorts `true` first under `asc`; a
    * value's, no value last in either direction, else numbers on the number
    * line and text as text, numeric when both parse.
    */
  final class Keys private (
      runs: Array[Run],
      bool: Array[Boolean],
      descending: Array[Boolean],
      // Beyond the member's own: what dirties the whole ordering.
      val entities: List[String],
      val reads: Set[SlotRead]
  ) {

    /** A candidate the store lacks reads as nothing: false, or no value. Term
      * `i`'s value is at `i`, `null` when it is no value, and its number, when
      * it parses, at `runs.length + i`.
      */
    def of(
        id: String,
        states: Map[String, EntityState],
        fetched: Lookup
    ): Array[Any] =
      val out = buffer()
      into(out, id, states, fetched)
      out

    def buffer(): Array[Any] = new Array[Any](2 * runs.length)

    def into(
        out: Array[Any],
        id: String,
        states: Map[String, EntityState],
        fetched: Lookup
    ): Unit =
      val st = states.getOrElse(id, null)
      val n = runs.length
      var i = 0
      while (i < n) {
        val v = runs(i)(st, states, fetched)
        if (bool(i)) out(i) = v
        else if (noValue(v)) { out(i) = null; out(n + i) = null }
        else { out(i) = v; out(n + i) = boxedNumber(v) }
        i += 1
      }

    private def boxedNumber(v: Any): java.lang.Double | Null = v match
      case d: java.lang.Double => d
      case x: java.lang.Number => java.lang.Double.valueOf(x.doubleValue)
      case s: String           =>
        s.toDoubleOption match
          case Some(d) => java.lang.Double.valueOf(d)
          case None    => null
      case _ => null

    /** Negative when `a` sorts first; `0` keeps a stable sort's order. */
    def compare(a: Array[Any], b: Array[Any]): Int =
      var i = 0
      var c = 0
      while (c == 0 && i < runs.length) {
        c = term(i, a, b)
        i += 1
      }
      c

    private def term(i: Int, a: Array[Any], b: Array[Any]): Int =
      def directed(raw: Int) = if (descending(i)) -raw else raw
      val l = a(i)
      val r = b(i)
      if (bool(i)) directed(java.lang.Boolean.compare(holds(r), holds(l)))
      // Last in either direction: `unknown` is no value, not the largest
      // string, which under `desc` crowned a scene never activated.
      else if (l == null || r == null)
        java.lang.Boolean.compare(l == null, r == null)
      else
        directed(a(runs.length + i) match
          case x: Double =>
            b(runs.length + i) match
              case y: Double => java.lang.Double.compare(x, y)
              case _         => Steps.text(l).compareTo(Steps.text(r))
          case _ => Steps.text(l).compareTo(Steps.text(r)))

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
            terms.map(_.descending).toArray,
            terms.flatMap(_.entities).distinct,
            SlotAsk.decision(terms.flatMap(_.by.fetches))
          )
        )

  /** Only a real `true` holds. */
  def holds(v: Any): Boolean = v match
    case b: java.lang.Boolean => b.booleanValue
    case _                    => false

  private def read(
      v: Value,
      programs: Iterator[(Cel.Key, Cel.Program)],
      scope: Scope,
      named: Map[String, Named]
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
        (_, states, fetched) => p.candidates.filter(is(_, states, fetched))
      )
    case Value.List(_, items) =>
      runs(items, scope, named).map(rs =>
        (subject, states, fetched) => rs.map(_(subject, states, fetched))
      )
    case Value.Cel(_, own) =>
      val (k, p) = programs.next()
      celInputs(k, own, scope, named).map(inputs =>
        (_, states, fetched) =>
          lift(Cel.eval(p, null, scope.slug, inputs(states, fetched)))
      )
    // Its answer is drawn by its stage, never read here.
    case _: Value.Fetch  => Right((_, _, _) => "")
    case Value.Var(name) => Right((_, _, in) => in.variable(name))

  private def step(
      s: Step,
      prev: Run,
      programs: Iterator[(Cel.Key, Cel.Program)],
      scope: Scope,
      named: Map[String, Named]
  ): Built[Run] = s match
    case Step.Cel(_, own) =>
      val (k, p) = programs.next()
      celInputs(k, own, scope, named).map(inputs =>
        (subject, states, fetched) =>
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
              case ids: List[?] => leader(ids, keys, states, fetched)
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
  ): Built[
    (String, Map[String, EntityState], Lookup) => Boolean
  ] =
    p.when.toList
      .traverse((id, g) => inner(g, scope).map(id -> _.run))
      .map { list =>
        val guards = list.toMap
        (id, states, fetched) =>
          val g = guards.getOrElse(id, null)
          g == null || {
            val st = states.getOrElse(id, null)
            st != null && holds(g(st, states, fetched))
          }
      }

  /** `present op n`, stopping once the candidates left cannot change it: `> 0`
    * at the first present, `== length` at the first absent.
    */
  private def countIs(
      candidates: List[String],
      is: (
          String,
          Map[String, EntityState],
          Lookup
      ) => Boolean,
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
    (_, states, fetched) =>
      var lo = 0L
      var hi = size
      var rest = candidates
      while (rest.nonEmpty && !settled(lo, hi)) {
        if (is(rest.head, states, fetched)) lo += 1 else hi -= 1
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
      states: Map[String, EntityState],
      fetched: Lookup
  ): Any =
    var best: String | Null = null
    var bestKeys = keys.buffer()
    var k = keys.buffer()
    var rest = ids
    while (rest.nonEmpty) {
      rest.head match
        case id: String =>
          keys.into(k, id, states, fetched)
          if (best == null || keys.compare(k, bestKeys) < 0) {
            best = id
            val t = bestKeys
            bestKeys = k
            k = t
          }
        case _ => ()
      rest = rest.tail
    }
    best
}
