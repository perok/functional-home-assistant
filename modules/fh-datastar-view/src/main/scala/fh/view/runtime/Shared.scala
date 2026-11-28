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

  private[runtime] def isCarriedFrom(v: Long): Boolean = {
    val k = tables.kept
    k != null && k.nn.carriedFrom == v
  }

  /** `next` names this version as its source first, so a fill landing while the
    * carry is taken is offered on rather than lost.
    */
  private[runtime] def carryInto(
      next: Shared,
      dirty: Shared.Dirty,
      marked: Shared.Marked,
      live: Shared.Live
  ): Unit = {
    val (from, to) = (tables, next.tables)
    if (to.kept != null) to.kept.nn.carriedFrom = version
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
    new Shared(states, version, new Tables(null), null, null)

  private[runtime] def kept(
      states: Map[String, EntityState],
      version: Long,
      decided: Decided
  ): Shared =
    new Shared(
      states,
      version,
      new Tables(new Kept(version, decided)),
      null,
      null
    )

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

  /** A version `decided` made: apart from the tables, since a `Shared` per node
    * (`wireTick`) paid for these fields on each.
    */
  private[runtime] final class Kept(val version: Long, val decided: Decided) {
    // Long.MinValue: carried from no version.
    @volatile var carriedFrom: Long = Long.MinValue
  }

  /** A version's tables. None refers to another version's: one that died in the
    * old generation kept every later one alive through young collections
    * (`publishIdleSet`'s heap grew to 1.2 GB, 77% of its cpu in G1), so the
    * successor a late fill is offered to is looked up in `decided`.
    */
  private[runtime] final class Tables(val kept: Kept | Null) {
    def successor: Tables | Null =
      if (kept == null) null
      else
        kept.nn.decided.carriedFrom(kept.nn.version) match
          case null      => null
          case s: Shared => s.tables

    import Tables.*
    val sets: Table[SetId, GroupMembers] = new Table(this, setsOf)
    val members: Table[NodeId, Option[Member]] = new Table(this, membersOf)
    val branches: Table[NodeId, Option[Int]] = new Table(this, branchesOf)
    val gates: Table[String, java.lang.Boolean] = new Table(this, gatesOf)
    val viewerBranches: ByValues[NodeId, Option[Int]] =
      new ByValues(this, viewerBranchesOf)
    val viewerSets: ByValues[SetId, GroupMembers] =
      new ByValues(this, viewerSetsOf)
    val viewerGates: ByValues[String, java.lang.Boolean] =
      new ByValues(this, viewerGatesOf)
    val signals = new ConcurrentHashMap[NodeId, Map[SignalId, SlotValue]]()
  }

  // Here, not in the class: a lambda in a class body captures `this`, so each
  // version made seven (343 B/op on `wireTick`).
  private object Tables {
    val setsOf: Tables => Table[SetId, GroupMembers] = _.sets
    val membersOf: Tables => Table[NodeId, Option[Member]] = _.members
    val branchesOf: Tables => Table[NodeId, Option[Int]] = _.branches
    val gatesOf: Tables => Table[String, java.lang.Boolean] = _.gates
    val viewerBranchesOf: Tables => Table[NodeId, ByValues.Entry[Option[Int]]] =
      _.viewerBranches.table
    val viewerSetsOf: Tables => Table[SetId, ByValues.Entry[GroupMembers]] =
      _.viewerSets.table
    val viewerGatesOf
        : Tables => Table[String, ByValues.Entry[java.lang.Boolean]] =
      _.viewerGates.table
  }

  /** `in`'s table, `same` its counterpart in another version's. */
  private[runtime] final class Table[K, V](
      in: Tables,
      same: Tables => Table[K, V]
  ) {
    @volatile private var carried: Map[K, V] = Map.empty
    // Made on the first fill: most versions fill few of their seven tables,
    // and the empty maps were 448 of `Shared.of`'s 984 B.
    @volatile private var fills: ConcurrentHashMap[K, V] | Null = null
    // What this version's frame dirtied, or left unmarked: a late fill of the
    // version before is not taken. Everything, until it is carried into.
    @volatile private var refused: K => Boolean = _ => true

    def get(k: K): Option[V] = {
      val f = fills
      val filled = if (f == null) null else f.nn.get(k)
      if (filled != null) Some(filled.nn) else carried.get(k)
    }

    private def filling: ConcurrentHashMap[K, V] = {
      val f = fills
      if (f != null) f.nn
      else
        synchronized {
          if (fills == null) fills = new ConcurrentHashMap[K, V]()
          fills.nn
        }
    }

    // Not `computeIfAbsent`: a derivation that asks for another key in the
    // same table (a group's member, under a `limit`) throws "Recursive update".
    def once(k: K)(derive: => V): V = {
      val v = derive
      val held = filling.putIfAbsent(k, v)
      if (held != null) held.nn
      else {
        offerOn(k, v)
        v
      }
    }

    private def offer(k: K, v: V): Unit =
      if (
        !refused(k) && !carried.contains(k) && filling.putIfAbsent(k, v) == null
      )
        offerOn(k, v)

    private def offerOn(k: K, v: V): Unit = {
      val n = in.successor
      if (n != null) same(n.nn).offer(k, v)
    }

    /** What this version holds now, carried and filled. */
    def entries: Iterator[V] = {
      val f = fills
      if (f == null) carried.valuesIterator
      else carried.valuesIterator ++ f.nn.values.asScala.iterator
    }

    def carryInto(
        to: Table[K, V],
        dirty: Set[K],
        marked: K => Boolean
    ): Unit = {
      val dropped = (k: K) => dirty(k) || !marked(k)
      to.refused = dropped
      // Read after `refused` is set: a fill that makes the map later is
      // offered on.
      val f = fills
      // Most dashboards never fill the per-values tables, and walking an empty
      // one each frame was +1.8% B/op on `publishSet`.
      if (f == null) {
        if (carried.nonEmpty) to.carried = carried.removedAll(dirty)
      } else
        // Only fills can hold an unmarked key: none is ever carried.
        to.carried = f.nn.asScala.iterator
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
private[runtime] final class ByValues[K, V](
    in: Shared.Tables,
    same: Shared.Tables => Shared.Table[K, ByValues.Entry[V]]
) {
  private[runtime] val table = new Shared.Table[K, ByValues.Entry[V]](in, same)

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
  def at(store: StoreState): Shared =
    kept.get.nn.find(_.states eq store.entities).getOrElse(make(store))

  // Every resume and send asks, and the version is almost always kept: making
  // one first cost each of them a version's tables (`RenderBench.sharedHeld`).
  private def make(store: StoreState): Shared = {
    val made = Shared.kept(store.entities, store.version, this)
    // Not `updateAndGet`: its function captures `store` and `made`, one more
    // object per version.
    @annotation.tailrec
    def keep(): Shared = {
      val held = kept.get.nn
      held.find(_.states eq store.entities) match
        case Some(s) => s
        case None
            if held.sizeIs < 2 || held.exists(_.version < store.version) =>
          val now = (made :: held).sortBy(-_.version).take(2)
          if (kept.compareAndSet(held, now)) made else keep()
        case None => made
    }
    keep()
  }

  /** The kept version carried from `version`: where its late fills go. */
  def carriedFrom(version: Long): Shared | Null = {
    @annotation.tailrec
    def first(l: List[Shared]): Shared | Null = l match
      case s :: rest => if (s.isCarriedFrom(version)) s else first(rest)
      case Nil       => null
    first(kept.get.nn)
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
