package fh.view.runtime

import fh.view.model.{NodeId, SignalId, SlotValue}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 128 bits, not `String.hashCode`: a collision suppresses a real change and
  * leaves the client stale. Hex, not `Array[Byte]`, so `==` compares content.
  */
private[runtime] opaque type Digest = String

private[runtime] object Digest {

  /** Per thread, since `getInstance` is a provider lookup per painted node.
    * Sound under fibers only because each use is synchronous and never
    * suspends.
    */
  private val digester: ThreadLocal[MessageDigest] =
    ThreadLocal.withInitial(() => MessageDigest.getInstance("SHA-256"))

  private val Hex: Array[Char] = "0123456789abcdef".toCharArray

  /** `LibPackage.sha256(...).take(32)`, hex-encoding only the 16 bytes kept, by
    * table: `"%02x".format` cost more than the hashing here.
    */
  def of(html: String): Digest = hex(digestOf(html))

  /** Copies the slice: a `CharsetEncoder` version without the copy measured +83
    * kB and +17% time, since `String.getBytes` is intrinsified.
    */
  def ofRange(buf: CharSequence, from: Int, until: Int): Digest =
    of(buf.subSequence(from, until).toString)

  private def digestOf(html: String): Array[Byte] = {
    val md = digester.get()
    md.reset()
    md.digest(html.getBytes(StandardCharsets.UTF_8))
  }

  private def hex(bytes: Array[Byte]): Digest = {
    val out = new Array[Char](32)
    var i = 0
    while (i < 16) {
      val b = bytes(i) & 0xff
      out(2 * i) = Hex(b >>> 4)
      out(2 * i + 1) = Hex(b & 0x0f)
      i += 1
    }
    new String(out)
  }
}

/** What one client has for one node: the digest of the bytes it was sent and
  * its signal values (ADR 0017). One map keyed by node, so a host fill's
  * invalidation is the containment test `Patches.applied` already runs.
  * `digest` is optional because a patch may establish only one half.
  */
private[runtime] case class Held(
    digest: Option[Digest] = None,
    signals: Map[SignalId, SlotValue] = Map.empty
) {

  /** `later` says nothing by being absent, so re-sending one of two signals
    * cannot drop the other.
    */
  def merge(later: Held): Held =
    Held(later.digest.orElse(digest), signals ++ later.signals)
}

private[runtime] object Held {
  def bytes(digest: Digest): Held = Held(Some(digest))
  def of(html: String): Held = bytes(Digest.of(html))
}

/** Decides the replay: a set member is a sibling-preserving delta, a branch one
  * `Inner` over its host.
  */
private[runtime] enum MemberKey {
  case Entity(id: String)
  case Surface(id: String)
}

/** One value, not "removed"/"arrived" maps, which could hold both. Latest wins.
  */
private[runtime] enum Mutation(val version: Long, val container: NodeId) {

  case Gone(in: NodeId, at: Long) extends Mutation(at, in)

  /** Belongs at its current position, wherever the client has it. Carries the
    * key because anchor and content are re-derived from live state, and
    * `sanitize` is one-way. Arrival and reordering are the same case.
    */
  case Placed(in: NodeId, member: MemberKey, at: Long) extends Mutation(at, in)
}

/** `refill`: containers whose history no longer reaches the cursor. */
private[runtime] case class Resume(
    nodes: List[NodeId],
    moved: List[(NodeId, Mutation)],
    refill: List[NodeId] = Nil
)

/** A cursor minted against another `id` (restart, renderer swap) is refused:
  * versions mean nothing across logs.
  *
  * `fragments` holds only existing nodes. `mutations` would grow with time — a
  * `Gone` for a member that never returns has nothing to evict it — hence
  * [[pruned]] and [[horizon]]. No clock is read: versions order everything (ADR
  * 0011).
  */
private[runtime] case class FragmentLog(
    id: String,
    // A missing entry reads "send it", so dropping one is always safe.
    fragments: Map[NodeId, Long] = Map.empty,
    mutations: Map[NodeId, Mutation] = Map.empty,
    // Per container, the oldest version its membership history is complete
    // from; a cursor below it gets a refill, which makes pruning safe.
    horizon: Map[NodeId, Long] = Map.empty,
    // The oldest version the whole log describes ([[skipped]], [[reaches]]).
    completeFrom: Long = 0
) {

  /** Named ids, not a prefix: a prefix would count a nested set's members as
    * the outer set's.
    */
  def holdsAnyOf(ids: Iterable[NodeId]): Boolean =
    ids.exists(fragments.contains)

  /** Nobody watched this version. The history goes too: [[reaches]] now refuses
    * every cursor at or below it, and a later session starts above it
    * ([[Server.recordFrame]]), so an idle slug keeps one number.
    */
  def skipped(version: Long): FragmentLog =
    if (completeFrom > version) this
    else
      FragmentLog(id = id, completeFrom = version + 1)

  /** `floor` is the slowest session's position, so nothing below it can be
    * asked for again. The horizon still rises: a client whose session was
    * reaped can present any cursor.
    */
  def pruned(floor: Long): FragmentLog = {
    val (stale, fresh) = mutations.partition { case (_, m) =>
      m.version < floor
    }
    if (stale.isEmpty) this
    else
      copy(
        mutations = fresh,
        horizon = stale.values.foldLeft(horizon) { (h, m) =>
          h.updatedWith(m.container)(prev =>
            Some(math.max(prev.getOrElse(0L), m.version + 1))
          )
        }
      )
  }

  /** `v + 1`: a document at V holds all of V, so it needs only `(V, now]`;
    * reading `v` would repaint every first connect to an idle dashboard.
    */
  def reaches(v: Long): Boolean = completeFrom <= v + 1

  // A fragment's version never goes backwards.
  def touched(nodeId: NodeId, at: Long): FragmentLog =
    if (fragments.get(nodeId).exists(_ > at)) this
    else copy(fragments = fragments.updated(nodeId, at))

  /** The host was re-supplied: drop the subtree and raise the horizon to
    * `at + 1`, so a session pulling `at` itself still gets the refill.
    */
  def filled(
      container: NodeId,
      at: Long,
      ancestry: NodeAncestry
  ): FragmentLog =
    invalidateOf(ancestry.descendantsOf(container) + container)
      .copy(horizon =
        horizon.updatedWith(container)(prev =>
          Some(math.max(prev.getOrElse(0L), at + 1))
        )
      )

  /** Mutations go too: a stale `Gone` would delete what the re-supplied HTML
    * restored. Use [[removed]] when the DOM really is deleted.
    */
  def invalidateOf(ids: Set[NodeId]): FragmentLog =
    copy(fragments = fragments -- ids, mutations = mutations -- ids)

  def invalidateWhere(p: NodeId => Boolean): FragmentLog =
    copy(
      fragments = fragments.filterNot { case (k, _) => p(k) },
      mutations = mutations.filterNot { case (k, _) => p(k) }
    )

  def removed(container: NodeId, nodeId: NodeId, at: Long): FragmentLog =
    copy(
      fragments = fragments - nodeId,
      mutations = mutations.updated(nodeId, Mutation.Gone(container, at))
    )

  def placed(
      container: NodeId,
      member: MemberKey,
      nodeId: NodeId,
      at: Long
  ): FragmentLog =
    copy(
      mutations =
        mutations.updated(nodeId, Mutation.Placed(container, member, at))
    )

  /** A strict ancestor in `moved` already carries it; otherwise its morph would
    * target an id the client does not have yet.
    */
  def coveredByMutation(
      nodeId: NodeId,
      moved: Set[NodeId],
      ancestry: NodeAncestry
  ): Boolean = ancestry.under(nodeId, moved)

  /** Total: lost history yields a `refill`, not a refusal. `>=`, since a client
    * can hold part of version V; re-sending V is idempotent. Ids only: the
    * caller renders from the current snapshot (ADR 0012).
    */
  def since(v: Long, ancestry: NodeAncestry): Resume = {
    val refill = horizon.collect { case (gid, h) if v < h => gid }.toList
    val moved = mutations.filter { case (_, m) => m.version >= v }
    // A refill covers like a `Placed`.
    val resupplied = moved.keySet ++ refill
    Resume(
      fragments.collect {
        case (nodeId, at)
            if at >= v && !resupplied.contains(nodeId) &&
              !coveredByMutation(nodeId, resupplied, ancestry) =>
          nodeId
      }.toList,
      moved.filterNot { case (nodeId, _) =>
        coveredByMutation(nodeId, resupplied, ancestry)
      }.toList,
      refill
    )
  }
}
