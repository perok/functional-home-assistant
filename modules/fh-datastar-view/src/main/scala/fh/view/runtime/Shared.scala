package fh.view.runtime

import fh.view.FHError
import fh.view.fetch.FetchSnapshot
import fh.view.history.Series
import fh.view.model.{NodeId, SetId, SlotAsk, SlotRead, Step}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/** What one state version decides, each thing once, for every viewer: which
  * members a set holds and in which clause, which branch a state group shows,
  * and whether a `valueWhile` holds. The pre-read, the walk, the render key,
  * the paint and the recorder ask here, so they cannot disagree (pipeline doc
  * §1a).
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
    private val run: Shared.Run | Null
) {

  /** This version as `r`'s walk reads it: the same decisions, made with its
    * answers.
    */
  def asking(r: Shared.Run): Shared = new Shared(states, version, tables, r)

  def set(gid: SetId, reads: Set[SlotRead], missing: => GroupMembers)(
      derive: Shared.Fetched => GroupMembers
  ): GroupMembers = decide(tables.sets, gid, reads, missing)(derive)

  def member(id: NodeId, reads: Set[SlotRead])(
      derive: Shared.Fetched => Option[Member]
  ): Option[Member] = decide(tables.members, id, reads, None)(derive)

  def branch(gid: NodeId, reads: Set[SlotRead])(
      derive: Shared.Fetched => Option[Int]
  ): Option[Int] = decide(tables.branches, gid, reads, None)(derive)

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
      marked: Shared.Marked
  ): Unit = {
    val (from, to) = (tables, next.tables)
    from.sets.carryInto(to.sets, dirty.sets, _ => true)
    from.members.carryInto(to.members, dirty.members, _ => true)
    from.branches.carryInto(to.branches, dirty.branches, marked.branch)
    from.gates.carryInto(to.gates, dirty.gates, marked.gate)
  }
}

private[runtime] object Shared {

  type Fetched = Evaluator.Lookup

  /** Shared with no one: a test's, or a reader's whose version is no longer
    * kept.
    */
  def of(states: Map[String, EntityState], version: Long = 0L): Shared =
    new Shared(states, version, new Tables, null)

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
      Series.celPoints(have.shared(SlotAsk(f, Step.Json)))
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

  /** What a frame's changes dirtied. */
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

  private final class Tables {
    val sets = new Table[SetId, GroupMembers]
    val members = new Table[NodeId, Option[Member]]
    val branches = new Table[NodeId, Option[Int]]
    val gates = new Table[String, java.lang.Boolean]
  }

  private final class Table[K, V] {
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

    def carryInto(
        to: Table[K, V],
        dirty: Set[K],
        marked: K => Boolean
    ): Unit = {
      dropped = k => dirty(k) || !marked(k)
      next = to
      // Only fills can hold an unmarked key: none is ever carried.
      to.carried = fills.asScala.iterator
        .filterNot((k, _) => dropped(k))
        .foldLeft(carried.removedAll(dirty))(_ + _)
    }
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
  def advance(prev: Shared, frame: Frame, dirty: Shared.Dirty): Shared = {
    val next = at(frame.state)
    prev.carryInto(next, dirty, marked)
    next
  }
}

/** One viewer's, for one send, beside the [[Shared]] it was read against: its
  * selections, and the answers to the slot fetches the send reads (which carry
  * the viewer's node variables).
  */
final case class Viewing(selections: Selections, fragments: FetchSnapshot)
