package fh.view.runtime

import fh.view.FHError
import fh.view.fetch.FetchSnapshot
import fh.view.history.Series
import fh.view.model.{
  NodeId,
  SetId,
  SignalId,
  SlotAsk,
  SlotRead,
  SlotValue,
  Step
}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/** What one state version decides, each thing once: which members a set holds
  * and in which clause, which branch a state group shows, and whether a
  * `valueWhile` holds. The pre-read, the walk, the render key, the paint and
  * the recorder ask here, so they cannot disagree (pipeline doc §1a).
  *
  * A decision reading only state, fetches and literals is the same for every
  * viewer. One that also reads variables is made at the values they hold for
  * the viewer this is [[viewing]] for, and kept under them: viewers on equal
  * values share it, and asked with no viewer it raises.
  *
  * Each decision is either carried from the version before, which no change
  * since has dirtied, or made here on demand (a fill). A fill made after the
  * next version exists is offered on to it, unless that version's frame dirtied
  * it.
  *
  * A decision that reads a fetch is made only inside a run
  * ([[Renderer.decide]]), from its answers; read [[asking]] one.
  */
private[runtime] final class Shared private (
    val states: Map[String, EntityState],
    val version: Long,
    private val tables: Shared.Tables,
    private val run: Shared.Run | Null,
    private val viewedBy: VarEnv | Null
) {

  /** The same decisions, made with `r`'s answers. */
  def asking(r: Shared.Run): Shared =
    new Shared(states, version, tables, r, viewedBy)

  def viewing(env: VarEnv): Shared =
    new Shared(states, version, tables, run, env)

  def viewer: VarEnv =
    if (viewedBy == null)
      throw FHError.internal(
        "a decision reading a variable was asked with no viewer"
      )
    else viewedBy.nn

  /** A pick's case read at the values of `names` in scope at `gid`; `derive`
    * reads them as variables.
    */
  def branchAt(gid: NodeId, names: List[String], reads: Set[SlotRead])(
      derive: Shared.Fetched => Option[Int]
  ): Option[Int] =
    decideAt(tables.viewerBranches, gid, gid, names, reads, None)(derive)

  /** As [[branchAt]], in scope at the set's node `at`. */
  def setAt(
      gid: SetId,
      at: NodeId,
      names: List[String],
      reads: Set[SlotRead],
      missing: => GroupMembers
  )(derive: Shared.Fetched => GroupMembers): GroupMembers =
    decideAt(tables.viewerSets, gid, at, names, reads, missing)(derive)

  private def decideAt[K, V](
      t: ByValues[K, V],
      k: K,
      at: NodeId,
      names: List[String],
      reads: Set[SlotRead],
      missing: => V
  )(derive: Shared.Fetched => V): V = {
    val scope = viewer.getOrElse(at, Map.empty)
    val values = names.map(n => scope.getOrElse(n, Evaluator.noVariable(n)))
    t.get(k, values) match
      case Some(v) => v
      case None    =>
        answered(reads) match
          case None    => missing
          case Some(f) =>
            val in = new Evaluator.Lookup:
              def fetched(t: fh.view.model.FetchTemplate): Object = f.fetched(t)
              def variable(name: String): String | Null =
                Evaluator.held(scope, name)
            t.once(k, at, names, values)(derive(in))
  }

  /** As [[branchAt]], in scope at the slot's node `at`. */
  def holdsAt(
      key: String,
      at: NodeId,
      names: List[String],
      reads: Set[SlotRead]
  )(derive: Shared.Fetched => Boolean): Boolean =
    decideAt(
      tables.viewerGates,
      key,
      at,
      names,
      reads,
      java.lang.Boolean.FALSE
    )(f => java.lang.Boolean.valueOf(derive(f))).booleanValue

  def set(gid: SetId, reads: Set[SlotRead], missing: => GroupMembers)(
      derive: Shared.Fetched => GroupMembers
  ): GroupMembers = decide(tables.sets, gid, reads, missing)(derive)

  def member(id: NodeId, reads: Set[SlotRead])(
      derive: Shared.Fetched => Option[Member]
  ): Option[Member] = decide(tables.members, id, reads, None)(derive)

  def branch(gid: NodeId, reads: Set[SlotRead])(
      derive: Shared.Fetched => Option[Int]
  ): Option[Int] = decide(tables.branches, gid, reads, None)(derive)

  /** A node's signal values at this version, for a node no viewer's fetch or
    * variable reaches. Never carried: any change may move them.
    */
  inline def signals(id: NodeId)(
      derive: => Map[SignalId, SlotValue]
  ): Map[SignalId, SlotValue] = {
    val got = tables.signals.get(id)
    if (got != null) got.nn
    else {
      val v = derive
      val _ = tables.signals.putIfAbsent(id, v)
      v
    }
  }

  def holds(key: String, reads: Set[SlotRead])(
      derive: Shared.Fetched => Boolean
  ): Boolean =
    decide(tables.gates, key, reads, java.lang.Boolean.FALSE)(f =>
      java.lang.Boolean.valueOf(derive(f))
    ).booleanValue

  /** `reads`' answers, or `None` while this run lacks them: noted as needed, so
    * the walk reads what waits on them as not shown.
    */
  def answered(reads: Set[SlotRead]): Option[Shared.Fetched] =
    if (reads.isEmpty) Some(Evaluator.unanswered)
    else
      run match
        case null =>
          throw FHError.internal(
            s"a decision reading ${reads.size} fetch(es) was reached outside a run"
          )
        case r: Shared.Run => r.answers(reads)

  // Not cached when missing: what stands in is only the walk's, until the
  // next round has the answers.
  private def decide[K, V](
      t: Shared.Table[K, V],
      k: K,
      reads: Set[SlotRead],
      missing: => V
  )(derive: Shared.Fetched => V): V =
    t.get(k) match
      case Some(v) => v
      case None    =>
        answered(reads) match
          case Some(f) => t.once(k)(derive(f))
          case None    => missing

  /** Linked first, so a fill landing while the carry is taken is offered on
    * rather than lost.
    */
  private[runtime] def carryInto(
      next: Shared,
      dirty: Shared.Dirty,
      marked: Shared.Marked,
      live: Shared.Live
  ): Unit = {
    val (from, to) = (tables, next.tables)
    from.sets.carryInto(to.sets, dirty.sets, _ => true)
    from.members.carryInto(to.members, dirty.members, _ => true)
    from.branches.carryInto(to.branches, dirty.branches, marked.branch)
    from.gates.carryInto(to.gates, dirty.gates, marked.gate)
    from.viewerBranches.carryInto(
      to.viewerBranches,
      dirty.branches,
      marked.branch,
      live
    )
    from.viewerSets.carryInto(to.viewerSets, dirty.sets, _ => true, live)
    from.viewerGates.carryInto(
      to.viewerGates,
      dirty.gates,
      marked.gate,
      live
    )
  }
}

private[runtime] object Shared {

  type Fetched = Evaluator.Lookup

  /** Shared with no one: a test's, or a reader's whose version is no longer
    * kept.
    */
  def of(states: Map[String, EntityState], version: Long = 0L): Shared =
    new Shared(states, version, new Tables, null, null)

  /** One walk's answers so far, and what it found it needs. A walk is
    * synchronous, so one thread writes it. Once its walk needs nothing more,
    * what the send goes on to read of it is the result, so a decision it lacks
    * the answers for raises rather than standing in as not shown.
    */
  final class Run(have: FetchSnapshot) {
    private var wanted = Set.empty[SlotRead]
    private var done = false

    def needs: Set[SlotRead] = wanted

    private[runtime] def close(): Unit = done = true

    private val fetched: Fetched = Evaluator.Lookup.answering(f =>
      Series.celPoints(have.staged(SlotAsk(f, Step.Json).fixed).value)
    )

    private[Shared] def answers(reads: Set[SlotRead]): Option[Fetched] =
      val missing = reads.filterNot(have.has)
      if (missing.isEmpty) Some(fetched)
      else if (done)
        throw FHError.internal(
          s"a decision reading ${missing.size} fetch(es) its run did not " +
            "reach was read after it"
        )
      else {
        wanted ++= missing
        None
      }
  }

  final case class Dirty(
      sets: Set[SetId],
      members: Set[NodeId],
      branches: Set[NodeId],
      gates: Set[String]
  )

  /** The decisions some entity can mark dirty. Any other is dirty at every
    * version, so it is never carried: a decision the index missed is decided
    * again rather than kept stale. Every set and member has its candidate.
    */
  final case class Marked(branch: NodeId => Boolean, gate: String => Boolean)

  /** The values live sessions hold, when they changed since the last carry: an
    * entry no live session's values reach is dropped, which bounds the
    * per-values tables by the sessions. `Unchanged` keeps every entry.
    */
  enum Live {
    case Unchanged
    case Now(envs: List[VarEnv])
  }

  private final class Tables {
    val sets = new Table[SetId, GroupMembers]
    val members = new Table[NodeId, Option[Member]]
    val branches = new Table[NodeId, Option[Int]]
    val gates = new Table[String, java.lang.Boolean]
    val viewerBranches = new ByValues[NodeId, Option[Int]]
    val viewerSets = new ByValues[SetId, GroupMembers]
    val viewerGates = new ByValues[String, java.lang.Boolean]
    val signals = new ConcurrentHashMap[NodeId, Map[SignalId, SlotValue]]()
  }

  private[runtime] final class Table[K, V] {
    @volatile private var carried: Map[K, V] = Map.empty
    private val fills = new ConcurrentHashMap[K, V]()
    @volatile private var next: Table[K, V] | Null = null
    @volatile private var dropped: K => Boolean = _ => true

    def get(k: K): Option[V] = {
      val filled = fills.get(k)
      if (filled != null) Some(filled.nn) else carried.get(k)
    }

    // Not `computeIfAbsent`: a derivation that asks for another key in the
    // same table (a group's member, under a `limit`) throws "Recursive update".
    def once(k: K)(derive: => V): V = {
      val v = derive
      val held = fills.putIfAbsent(k, v)
      if (held != null) held.nn
      else {
        offerOn(k, v)
        v
      }
    }

    private def offer(k: K, v: V): Unit =
      if (!carried.contains(k) && fills.putIfAbsent(k, v) == null)
        offerOn(k, v)

    private def offerOn(k: K, v: V): Unit = {
      val n = next
      if (n != null && !dropped(k)) n.nn.offer(k, v)
    }

    /** What this version holds now, carried and filled. */
    def entries: Iterator[V] =
      carried.valuesIterator ++ fills.values.asScala.iterator

    def carryInto(
        to: Table[K, V],
        dirty: Set[K],
        marked: K => Boolean
    ): Unit = {
      dropped = k => dirty(k) || !marked(k)
      next = to
      // Most dashboards never fill the per-values tables, and walking an empty
      // one each frame was +1.8% B/op on `publishSet`.
      if (fills.isEmpty && carried.isEmpty) ()
      else
        // Only fills can hold an unmarked key: none is ever carried.
        to.carried = fills.asScala.iterator
          .filterNot((k, _) => dropped(k))
          .foldLeft(carried.removedAll(dirty))(_ + _)
    }
  }
}

/** A [[Shared.Table]] of decisions kept per values: one entry per decision,
  * holding each values' answer, so a dirty decision drops every values in one
  * removal. An entry's map is shared by the versions it is carried through,
  * which is sound because carried means nothing it reads changed between them.
  */
private[runtime] final class ByValues[K, V] {
  private val table = new Shared.Table[K, ByValues.Entry[V]]

  def get(k: K, values: List[String]): Option[V] =
    table.get(k).flatMap(e => Option(e.answers.get(values)).map(_.nn))

  def once(k: K, at: NodeId, names: List[String], values: List[String])(
      derive: => V
  ): V = {
    val e = table.get(k).getOrElse(table.once(k)(new ByValues.Entry(at, names)))
    val v = derive
    val held = e.answers.putIfAbsent(values, v)
    if (held != null) held.nn else v
  }

  def carryInto(
      to: ByValues[K, V],
      dirty: Set[K],
      marked: K => Boolean,
      live: Shared.Live
  ): Unit = {
    table.carryInto(to.table, dirty, marked)
    live match
      case Shared.Live.Unchanged => ()
      case Shared.Live.Now(envs) =>
        to.table.entries.foreach { e =>
          val reached = envs.map { env =>
            val scope = env.getOrElse(e.at, Map.empty)
            e.names.map(scope.getOrElse(_, ""))
          }.toSet
          e.answers.keySet.removeIf(v => !reached(v))
        }
  }
}

private[runtime] object ByValues {
  final class Entry[V](val at: NodeId, val names: List[String]) {
    val answers = new ConcurrentHashMap[List[String], V]()
  }
}

/** The last two versions' [[Shared]]: the recorder asks of a frame's "before"
  * and "after" in turn, and one slot would make each evict the other.
  */
private[runtime] final class Decided(
    marked: Shared.Marked = Shared.Marked(_ => false, _ => false)
) {
  private val kept = new AtomicReference[List[Shared]](Nil)

  /** `store`'s version, the same value for every reader of it. A version older
    * than both kept is decided apart: correct, and only unshared.
    */
  def at(store: StoreState): Shared = {
    val made = Shared.of(store.entities, store.version)
    val now = kept.updateAndGet { held =>
      if (held.exists(_.states eq store.entities)) held
      else if (held.sizeIs < 2 || held.exists(_.version < store.version))
        (made :: held).sortBy(-_.version).take(2)
      else held
    }.nn
    now.find(_.states eq store.entities).getOrElse(made)
  }

  /** `frame`'s version, carrying what `prev` decided that `dirty` leaves. A
    * reader ahead of the recorder may have made it already; the carry goes
    * beneath what it filled.
    */
  def advance(
      prev: Shared,
      frame: Frame,
      dirty: Shared.Dirty,
      live: Shared.Live
  ): Shared = {
    val next = at(frame.state)
    prev.carryInto(next, dirty, marked, live)
    next
  }
}

/** One viewer's, for one send, beside the [[Shared]] it was read against: the
  * answers to the slot fetches the send reads (which carry the viewer's node
  * variables).
  */
final case class Viewing(fragments: FetchSnapshot)
