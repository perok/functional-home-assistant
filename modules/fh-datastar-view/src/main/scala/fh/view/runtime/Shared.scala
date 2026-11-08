package fh.view.runtime

import fh.view.model.{FetchTemplate, NodeId, SetId}

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
  */
private[runtime] final class Shared private (
    val states: Map[String, EntityState],
    val version: Long
) {
  private val sets = new Shared.Table[SetId, GroupMembers]
  private val members = new Shared.Table[NodeId, Option[Member]]
  private val branches = new Shared.Table[NodeId, Option[Int]]
  private val gates = new Shared.Table[String, java.lang.Boolean]

  def set(gid: SetId)(derive: => GroupMembers): GroupMembers =
    sets.once(gid)(derive)

  def member(id: NodeId)(derive: => Option[Member]): Option[Member] =
    members.once(id)(derive)

  def branch(gid: NodeId)(derive: => Option[Int]): Option[Int] =
    branches.once(gid)(derive)

  def holds(key: String)(derive: => Boolean): Boolean =
    gates.once(key)(java.lang.Boolean.valueOf(derive)).booleanValue

  /** Linked first, so a fill landing while the carry is taken is offered on
    * rather than lost.
    */
  private[runtime] def carryInto(
      next: Shared,
      dirty: Shared.Dirty,
      marked: Shared.Marked
  ): Unit = {
    sets.carryInto(next.sets, dirty.sets, _ => true)
    members.carryInto(next.members, dirty.members, _ => true)
    branches.carryInto(next.branches, dirty.branches, marked.branch)
    gates.carryInto(next.gates, dirty.gates, marked.gate)
  }
}

private[runtime] object Shared {

  /** Shared with no one: a test's, or a reader's whose version is no longer
    * kept.
    */
  def of(states: Map[String, EntityState], version: Long = 0L): Shared =
    new Shared(states, version)

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

  private final class Table[K, V] {
    @volatile private var carried: Map[K, V] = Map.empty
    private val fills = new ConcurrentHashMap[K, V]()
    @volatile private var next: Table[K, V] | Null = null
    @volatile private var dropped: K => Boolean = _ => true

    // Not `computeIfAbsent`: a derivation that asks for another key in the
    // same table (a group's member, under a `limit`) throws "Recursive update".
    def once(k: K)(derive: => V): V = {
      val filled = fills.get(k)
      if (filled != null) filled.nn
      else
        carried.get(k) match
          case Some(v) => v
          case None    =>
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

  private val answered =
    new AtomicReference[FetchTemplate => Object](Evaluator.unanswered)

  /** The answers to the fetches decisions read, the newest the server prepared:
    * they depend on no snapshot, since the build refuses one that reads a
    * viewer's variable. Before any, a fetch read raises.
    */
  def fetched: FetchTemplate => Object = answered.get().nn

  def answer(fetched: FetchTemplate => Object): Unit = answered.set(fetched)
}
