package fh.view.runtime

import fh.view.build.LibPackage
import fh.view.model.NodeId

import scala.concurrent.duration.*

/** A 128-bit content digest.
  *
  * Not `String.hashCode`: a collision here does not cost a redundant send, it
  * SUPPRESSES a real change, leaving the client on stale HTML until something
  * else moves it.
  *
  * Hex rather than `Array[Byte]` so `==` means what it says — array equality is
  * by reference, which in a `case class` used as a diff baseline is the bug
  * that never shows up in a test that happens to reuse one instance.
  */
private[runtime] opaque type Digest = String

private[runtime] object Digest {
  def of(html: String): Digest =
    LibPackage.sha256(html.getBytes("UTF-8")).take(32)

  /** For suites that hand-build a log from HTML literals, the same trade
    * [[fh.view.testkit.TestIds]] makes for [[NodeId]]: the type exists to stop
    * the SERVER hashing the wrong thing or comparing an unhashed string, and a
    * test writing `holds("a", "<a/>")` has no such confusion available — the
    * literal IS the markup. Test-only, so production still cannot skip the hash
    * by accident.
    */
  private[runtime] object AsHtml {
    given Conversion[String, Digest] = Digest.of(_)
  }
}

/** A missing entry reads as "unknown — send it", which is what makes dropping
  * one always safe: the failure mode is redundant bytes, never silent
  * staleness.
  *
  * `version` is the store version this was rendered from, and is what lets a
  * reconnecting client be told the difference instead of the whole body
  * (docs/adr/0011-the-live-connection.md).
  */
private[runtime] case class Fragment(
    // Keyed by VARIANT. Almost every node has exactly one, keyed 0; a node whose
    // own markup reads its OWN selection (`Renderer.nodeVariesByViewer`) has one
    // per member of its group.
    digests: Map[Int, Digest],
    version: Long
)

/** One value rather than two `Long`s at every call site, which is how they get
  * swapped.
  *
  *   - `version` ORDERS everything, and is the only clock any correctness
  *     argument rests on.
  *   - `millis` is wall clock, used ONLY to age mutations out
  *     ([[FragmentLog.Retention]]). Mixing the two into one ordering is what
  *     ruled out HA's `last_updated` as the cursor
  *     (docs/adr/0011-the-live-connection.md).
  */
private[runtime] case class Stamp(version: Long, millis: Long)

private[runtime] object FragmentLog {

  /** Sized by how long a client can be away and still be worth resuming: a
    * backgrounded phone tab is minutes to hours, past which a body repaint is
    * the honest answer. Exceeding it costs that repaint, never correctness —
    * see [[FragmentLog.horizon]].
    */
  val Retention: FiniteDuration = 1.hour
}

/** Which kind decides how a resume replays the member: an entity's card is a
  * per-member delta that must preserve its siblings, where a branch is one
  * `Inner` over a mount holding exactly one thing.
  */
private[runtime] enum MemberKey {
  case Entity(id: String)
  case Surface(id: String)
}

/** One value rather than parallel "removed"/"arrived" maps, because a node
  * cannot be both: two maps make that state representable and turn every
  * leave-then-rejoin into a special case. Latest wins.
  */
private[runtime] enum Mutation(val at: Stamp, val container: NodeId) {

  case Gone(in: NodeId, stamp: Stamp) extends Mutation(stamp, in)

  /** The element belongs at its CURRENT position, wherever (or whether) the
    * client currently has it. Carries the container and the [[MemberKey]]
    * rather than just the node id because both the anchor and the content have
    * to be re-derived from live state, and
    * [[fh.view.model.LayoutNode.sanitize]] is one-way.
    *
    * An arrival and a re-ordering are the same thing here, which is why an
    * author-chosen member sort needs no new case.
    */
  case Placed(in: NodeId, member: MemberKey, stamp: Stamp)
      extends Mutation(stamp, in)

  /** The only clock a resume compares against. */
  def version: Long = at.version

  /** Retention only; orders nothing. */
  def millis: Long = at.millis
}

/** `refill` names containers whose membership history no longer reaches back to
  * the cursor, so no delta can be computed and the mount is filled wholesale —
  * the fallback of LAST resort. See [[Patches.resume]] for how `moved` becomes
  * patches, and for the ordering argument that makes the anchors resolvable.
  */
private[runtime] case class Resume(
    nodes: List[NodeId],
    moved: List[(NodeId, Mutation)],
    refill: List[NodeId] = Nil
)

/** `id` identifies THIS log. A cursor minted against a different one (a
  * restarted server, whose version counter reset to 0; a renderer hot-swap) is
  * rejected outright, because a bare version number means nothing across logs.
  *
  * `fragments` is self-limiting — [[removed]] and [[invalidateWhere]] drop
  * entries, so it holds only nodes that currently exist. `mutations` is not: a
  * [[Mutation.Gone]] for a member that left and never came back has nothing to
  * evict it, so it accumulates one entry per entity that has EVER been a member
  * of any group, growing with elapsed time rather than dashboard size. A
  * `dynamic` group over "every light that is on" will, over a week, name every
  * light in the house. Hence [[FragmentLog.Retention]] and [[horizon]].
  */
private[runtime] case class FragmentLog(
    id: String,
    fragments: Map[NodeId, Fragment] = Map.empty,
    mutations: Map[NodeId, Mutation] = Map.empty,
    // Per container, the oldest version for which its membership history is
    // COMPLETE. Rises as that container's mutations are evicted; a cursor below
    // it gets that container's mount filled instead of a delta, which is what
    // makes eviction safe rather than silently lossy. Per-container because that
    // is the granularity at which completeness is actually lost: one churning
    // group aging out says nothing about any other.
    horizon: Map[NodeId, Long] = Map.empty
) {

  /** Keeps the identity, so every cursor already issued stays comparable. */
  def cleared: FragmentLog = FragmentLog(id)

  /** `false` for an absent entry: unknown means send it.
    *
    * Takes the DIGEST, not the HTML: the bytes were hashed when they were
    * rendered ([[NodeBytes]]), and hashing them again here — and a third time
    * in [[set]] — was SHA-256 over every fragment two or three times per batch.
    */
  def holds(nodeId: NodeId, digest: Digest, variant: Int = 0): Boolean =
    fragments.get(nodeId).exists(_.digests.get(variant).contains(digest))

  /** This log flattened to what ONE viewer holds: the variant dimension exists
    * only because the log is shared, so picking a viewer removes it.
    *
    * The projection a [[Session]] eventually replaces — a per-connection record
    * is already one viewer's, so it needs no variant key at all.
    */
  def digestsFor(variant: NodeId => Int): Map[NodeId, Digest] =
    fragments.flatMap { case (id, f) =>
      f.digests.get(variant(id)).map(id -> _)
    }

  /** The cheap half of the two skips: an integer comparison that spares the
    * render entirely, where [[holds]] must render first to compare. Sound
    * because versions only grow and a write records the version it rendered
    * from, so an entry can never be ahead of a later read.
    */
  def atLeast(nodeId: NodeId, variant: Int, version: Long): Boolean =
    fragments
      .get(nodeId)
      .exists(f => f.version >= version && f.digests.contains(variant))

  /** What makes a queued fill STALE: a fill is planned when a selection moves
    * but sent when its connection reaches it, and by then a later flip may have
    * recorded this member as [[Mutation.Gone]]. Sending it would restore a
    * branch that no longer belongs, until the item behind it corrected the DOM.
    */
  def isGone(nodeId: NodeId): Boolean =
    mutations.get(nodeId).exists {
      case _: Mutation.Gone => true
      case _                => false
    }

  /** Whether the log knows what is in `gid`'s mount, so a membership change can
    * be patched per-entity instead of filled wholesale. Its MEMBERS are the
    * whole record: a container logs no fragment of its own, because that
    * fragment would contain other nodes.
    */
  def hasChildOf(gid: NodeId): Boolean =
    fragments.keysIterator.exists(_.startsWith(gid + "_"))

  /** For the DOCUMENT path. Left unrecorded, a client's first connect is
    * offered every node of every open surface as a candidate, so the page
    * arrives twice.
    *
    * Absent-only because the log is SHARED and this snapshot may already be
    * behind: a newer entry from the live pass describes the DOM better.
    */
  def seed(
      nodeId: NodeId,
      digest: Digest,
      at: Long,
      variant: Int = 0
  ): FragmentLog =
    if (fragments.get(nodeId).exists(_.digests.contains(variant))) this
    else set(nodeId, digest, at, variant)

  /** **A fragment's version never goes backwards.** A variant-bearing node's
    * entry is written lazily, when some connection first asks for that variant,
    * so two batches can reach here out of order — a slow client forcing an old
    * batch after a newer one was served. The stale patch it produced is still
    * sent; the batch behind it corrects the client a moment later.
    */
  def set(
      nodeId: NodeId,
      digest: Digest,
      at: Long,
      variant: Int = 0
  ): FragmentLog =
    fragments.get(nodeId) match {
      case Some(f) if f.version > at => this
      case Some(f)                   =>
        copy(
          fragments = fragments
            .updated(nodeId, Fragment(f.digests.updated(variant, digest), at))
        )
      case None =>
        copy(
          fragments =
            fragments.updated(nodeId, Fragment(Map(variant -> digest), at))
        )
    }

  /** For a node whose DOM an ancestor is RE-SUPPLYING (a group repaint, a
    * bake-group flip) — stale, not gone. Recording a removal here would delete
    * an element that ancestor legitimately restored. Use [[removed]] when the
    * DOM really is being deleted.
    */
  def invalidate(nodeId: NodeId): FragmentLog =
    copy(fragments = fragments - nodeId)

  /** [[invalidate]] for a whole subtree whose ROOT is being re-stamped in the
    * same operation, which is why it drops [[Mutation]]s too: a stale `Gone`
    * would delete a member that root's HTML restored, a stale `Placed` insert
    * one it already contains. Callers must actually [[set]] the root — this is
    * not a bare `filterNot`.
    */
  def invalidateWhere(p: NodeId => Boolean): FragmentLog =
    copy(
      fragments = fragments.filterNot { case (k, _) => p(k) },
      mutations = mutations.filterNot { case (k, _) => p(k) }
    )

  /** `container` rides along so eviction knows whose history it just made
    * incomplete.
    */
  def removed(container: NodeId, nodeId: NodeId, stamp: Stamp): FragmentLog =
    copy(
      fragments = fragments - nodeId,
      mutations = mutations.updated(nodeId, Mutation.Gone(container, stamp))
    ).evicting(stamp.millis)

  def placed(
      container: NodeId,
      member: MemberKey,
      nodeId: NodeId,
      digest: Digest,
      stamp: Stamp
  ): FragmentLog =
    placed(container, member, nodeId, stamp).set(nodeId, digest, stamp.version)

  /** [[placed]] for a member whose bytes are NOT one thing: its subtree mounts
    * a client-selected member, so no single digest describes what every viewer
    * received. Recording no digest costs one redundant re-send on the next
    * tick; recording one viewer's would suppress a real change for the others.
    */
  def placed(
      container: NodeId,
      member: MemberKey,
      nodeId: NodeId,
      stamp: Stamp
  ): FragmentLog =
    copy(
      mutations =
        mutations.updated(nodeId, Mutation.Placed(container, member, stamp))
    ).evicting(stamp.millis)

  /** `now` is passed in rather than read from a clock, keeping the log pure;
    * the caller reads the clock once per diff, with the snapshot.
    */
  private def evicting(now: Long): FragmentLog = {
    val cutoff = now - FragmentLog.Retention.toMillis
    val (stale, fresh) = mutations.partition { case (_, m) =>
      m.millis < cutoff
    }
    if (stale.isEmpty) this
    else
      copy(
        mutations = fresh,
        // Each container is complete only from just after the newest thing
        // forgotten about IT — one group aging out says nothing about any other.
        horizon = stale.values.foldLeft(horizon) { (h, m) =>
          h.updatedWith(m.container)(prev =>
            Some(math.max(prev.getOrElse(0L), m.version + 1))
          )
        }
      )
  }

  /** Whether a mutation in `moved` is re-supplying an ANCESTOR of `nodeId`, and
    * so carries it already. Without this, a [[Mutation.Placed]] re-supplies a
    * subtree whose nodes also carry `version >= cursor`, so they each ship as a
    * morph against an id the client's DOM does not hold yet, and only then the
    * `remove`/`append` that actually carries them.
    *
    * STRICT ancestors: a node never covers itself, or every mutation would
    * suppress its own emission. Ancestry is a string-prefix test because ids
    * are location-derived ([[fh.view.model.LayoutNode.pathId]]: `c`, `c_0`,
    * `c_0_1`); the trailing `_` keeps `c_1` from matching `c_10`, and no
    * generated id can contain the `-` a `self` element's DOM id uses.
    */
  def coveredByMutation(nodeId: NodeId, moved: Set[NodeId]): Boolean =
    moved.exists(id => id != nodeId && nodeId.startsWith(id + "_"))

  /** TOTAL: a container whose history is gone yields a `refill` rather than a
    * refusal, so a whole-body repaint survives only for the genuinely global
    * reasons the caller checks (no cursor, a log-id mismatch, a cursor ahead of
    * the store, a changed head).
    *
    * `>=` rather than `>`: the cursor is pushed alongside a patch batch and one
    * store version can produce several, so a client can hold version V having
    * seen only part of it. Re-sending all of V is idempotent and cheap; missing
    * half of it would be silent and permanent.
    *
    * A mutated node is not ALSO reported in `nodes` — the element may not be
    * where the client has it, and a morph of an absent id silently does
    * nothing, so its content rides the mutation instead.
    *
    * Returns node IDS: the caller renders them from the current snapshot, which
    * is at least as fresh as anything the log could have stored
    * (docs/adr/0012-one-pass-addressed-per-client.md, statement (3)).
    */
  def since(v: Long): Resume = {
    val refill = horizon.collect { case (gid, h) if v < h => gid }.toList
    val moved = mutations.filter { case (_, m) => m.version >= v }
    // A refill re-supplies its container's whole mount, so it covers by prefix
    // exactly the way a `Placed` does — which is why "a refilled container's
    // members must not ALSO be sent" is not a rule to remember, just this union.
    val resupplied = moved.keySet ++ refill
    Resume(
      fragments.collect {
        case (nodeId, f)
            if f.version >= v && !resupplied.contains(nodeId) &&
              !coveredByMutation(nodeId, resupplied) =>
          nodeId
      }.toList,
      moved.filterNot { case (nodeId, _) =>
        coveredByMutation(nodeId, resupplied)
      }.toList,
      refill
    )
  }
}
