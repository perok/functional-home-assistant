package fh.view.runtime

import fh.view.build.LibPackage
import fh.view.model.NodeId

import scala.concurrent.duration.*

/** A 128-bit content digest — enough to answer "did this node's HTML change?"
  * and nothing more.
  *
  * A digest rather than `String.hashCode` because a collision here does not
  * cost a redundant send, it SUPPRESSES a real change: the diff would decide
  * the node is unchanged and the client would sit on stale HTML until something
  * else moved it.
  *
  * Hex rather than `Array[Byte]` so `==` means what it says — an array's
  * equality is by reference, which in a `case class` used as a diff baseline is
  * exactly the bug that never shows up in a test that happens to reuse one
  * instance.
  */
private[runtime] opaque type Digest = String

private[runtime] object Digest {
  def of(html: String): Digest =
    LibPackage.sha256(html.getBytes("UTF-8")).take(32)
}

/** WHEN a node last changed — never WHAT it holds.
  *
  * The log used to carry the rendered HTML, which fused two jobs: suppressing a
  * redundant patch, and supplying content on resume. Only the first needs
  * storage, and only a digest of it; the second is better served by rendering
  * from the current snapshot, which is by definition at least as fresh
  * (docs/adr/0012-one-pass-addressed-per-client.md, statement (3)).
  *
  * What that buys is not memory. It makes '''dropping an entry always
  * correct''': with content in the log, invalidating destroyed something the
  * resume path needed, so every DOM-touching path owed the log exact bytes;
  * with a digest, a missing entry means "unknown — send it". The failure mode
  * moves from silent, permanent staleness to redundant bytes.
  *
  * The version is what makes the log a ledger rather than a cache: it answers
  * "when did each node last change", which is what lets a reconnecting client
  * be told the difference instead of the whole body
  * (docs/adr/0011-the-live-connection.md).
  */
private[runtime] case class Fragment(
    // One digest per VARIANT. Almost every node has exactly one, keyed 0: its
    // rendering is a pure function of entity state. A node whose own markup
    // reads its OWN selection (`Renderer.nodeVariesByViewer`) has one per member
    // of its group — a static, tiny set, since a node's own rendering can never
    // carry a descendant's mount and so can never multiply out.
    digests: Map[Int, Digest],
    version: Long
)

/** When a diff pass rendered — both clocks it needs, together.
  *
  *   - `version`: the store version the snapshot was read at. ORDERS everything
  *     (fragment staleness, cursor comparison), and is the only clock any
  *     correctness argument rests on.
  *   - `millis`: wall clock, used ONLY to age mutations out
  *     ([[FragmentLog.Retention]]). Never compared against a version, and never
  *     used to order anything — which is what keeps this from reintroducing the
  *     two-clocks-in-one-ordering problem that ruled out HA's `last_updated` as
  *     the cursor (see docs/adr/0011-the-live-connection.md).
  *
  * One value rather than two positional `Long`s, because a pair of same-typed
  * arguments threaded through the diff helpers is exactly how they get swapped.
  */
private[runtime] case class Stamp(version: Long, millis: Long)

private[runtime] object FragmentLog {

  /** How long a [[Mutation]] is retained before it is evicted.
    *
    * Sized by the question that actually matters — how long can a client be
    * away and still be worth resuming? A backgrounded phone tab is minutes to
    * hours; past that the connection is long dead and a body repaint is the
    * honest answer. Aging out (rather than capping the entry count) makes the
    * retained set mean something: "everything that moved recently enough for a
    * returning client to care".
    *
    * Exceeding it costs a repaint, never correctness — see
    * [[FragmentLog.horizon]].
    *
    * FUTURE: this is a blunt stand-in for what the retention question really
    * is. The precise rule is to truncate below the OLDEST cursor any live
    * connection still holds: [[Sessions]] is already keyed by `conn`, so each
    * could report its last-sent version and the log could evict everything
    * below their minimum, retaining exactly what is still reachable and no
    * more. Two caveats kept this out of the first cut. It reintroduces
    * per-connection server state, which this design otherwise avoids — though
    * only for RETENTION, never for correctness, which is what separates it from
    * the rejected per-client mirror (ADR 0011) and makes it acceptable. And a
    * wedged or hung connection would pin the log open indefinitely, so the age
    * bound has to survive as a floor regardless: the real rule is
    * `min(live cursors)` clamped by this duration, not one or the other.
    */
  val Retention: FiniteDuration = 1.hour
}

/** How a container names ONE of the things in its mount.
  *
  * Two containers keep membership — a dynamic group, whose members are
  * entities, and a state group (an `If`), whose members are its branch surfaces
  * — and a bare `String` refused to carry that distinction. The kind decides
  * how a resume replays the member: an entity's card is a per-member delta that
  * must preserve its siblings, where a branch is one `Inner` over a mount that
  * holds exactly one thing.
  */
private[runtime] enum MemberKey {

  /** A dynamic group's member: the entity whose card sits in the mount. */
  case Entity(id: String)

  /** A state group's member: the branch surface baked into the mount. */
  case Surface(id: String)
}

/** The last STRUCTURAL thing that happened to a node — as opposed to a change
  * in its content, which is a [[Fragment]]. One value rather than a pair of
  * parallel "removed"/"arrived" records, because a node cannot be both gone and
  * present: holding two maps made that invalid state representable and turned
  * every leave-then-rejoin into a special case. Latest wins, so a rejoin is
  * simply [[Placed]] replacing [[Gone]].
  */
private[runtime] enum Mutation(val at: Stamp, val container: NodeId) {

  /** The element was deleted from `in`'s mount. */
  case Gone(in: NodeId, stamp: Stamp) extends Mutation(stamp, in)

  /** The element belongs at its CURRENT position in `in`'s mount, wherever (or
    * whether) the client currently has it. Carries the container and the
    * [[MemberKey]] rather than just the node id because both the anchor and the
    * content have to be re-derived from live state, and
    * [[fh.view.model.LayoutNode.sanitize]] is one-way.
    *
    * Covers an arrival and a re-ordering identically — both mean "put this
    * element here" — which is why an author-chosen member sort needs no new
    * mutation kind.
    */
  case Placed(in: NodeId, member: MemberKey, stamp: Stamp)
      extends Mutation(stamp, in)

  /** Ordering clock — see [[Stamp]]; the only one a resume compares against. */
  def version: Long = at.version

  /** Retention clock — see [[Stamp]]; orders nothing. */
  def millis: Long = at.millis
}

/** What a resume owes a client holding a given cursor:
  *
  *   - `nodes` to render NOW and morph — node ids, not stored HTML. The log
  *     says which nodes moved; the current snapshot says what they contain, and
  *     it is by definition at least as fresh as anything the log could have
  *     kept.
  *   - `moved` (node id -> what happened) to apply structurally. See
  *     [[Patches.resume]] for how each becomes patches, and for the ordering
  *     argument that makes the anchors resolvable.
  *   - `refill`: containers whose membership history no longer reaches back to
  *     this cursor, so the delta cannot be computed and the mount is filled
  *     wholesale. The fallback of LAST resort — and scoped to one container,
  *     where eviction used to cost the whole body.
  *
  * Rendering from one snapshot also retires an ordering rule: morphs used to
  * have to go out ascending by version, because a container's cached HTML
  * embedded its children and a stale parent applied after a fresh child would
  * revert it. Nothing rendered now is stale, and under the self/mount split no
  * fragment contains another node.
  */
private[runtime] case class Resume(
    nodes: List[NodeId],
    moved: List[(NodeId, Mutation)],
    refill: List[NodeId] = Nil
)

/** The per-slug (or per-session) diff cache, versioned.
  *
  * Replaces the bare `Map[nodeId, html]`. The live path is unchanged in cost —
  * a point lookup and a compare — and the `since` scan happens once per
  * reconnect, never on the hot path.
  *
  * `id` identifies THIS log. A cursor minted against a different log (a
  * restarted server, whose version counter reset to 0; a renderer hot-swap,
  * which mints a fresh cache) is rejected outright, because a bare version
  * number means nothing across logs — version 5 of one process describes
  * different state than version 5 of the next.
  *
  * `fragments` answers "what does this node contain"; `mutations` answers
  * "where is this node" — the structural changes that are NOT expressible as a
  * morph of an element the client already has (see [[Mutation]]).
  *
  * Keying both by node id (rather than by event) is what keeps the log small: a
  * node has one latest content and one latest structural fact, however many
  * times it churned. `fragments` needs nothing further — it holds only nodes
  * that currently EXIST, since [[removed]] and [[invalidateWhere]] drop
  * entries.
  *
  * `mutations` does need eviction, and this is the one place the log is not
  * self-limiting. A [[Mutation.Gone]] for a member that left and never came
  * back has nothing to evict it, so the map accumulates one entry per entity
  * that has EVER been a member of any group — bounded by entity count rather
  * than by dashboard size, and growing with elapsed time rather than with
  * dashboard complexity. A `dynamic` group over "every light that is on" will,
  * over a week, name every light in the house. Hence [[FragmentLog.Retention]]
  * and [[horizon]].
  */
private[runtime] case class FragmentLog(
    id: String,
    fragments: Map[NodeId, Fragment] = Map.empty,
    mutations: Map[NodeId, Mutation] = Map.empty,
    // Per CONTAINER, the oldest version for which its membership history is
    // COMPLETE. Rises as that container's mutations are evicted; a cursor below
    // it cannot be served a delta for that container and gets its mount filled
    // instead — which is what makes eviction safe rather than silently lossy.
    //
    // Per-container because that is the granularity at which completeness is
    // actually lost. It used to be one global number, so ONE churning group
    // aging out cost every client below it a whole-body repaint. Now the
    // whole-body repaint is unreachable through eviction at all: it remains only
    // for the genuinely global reasons (no cursor, a log-id mismatch, a cursor
    // ahead of the store, a changed head).
    horizon: Map[NodeId, Long] = Map.empty
) {

  /** Forget everything but keep this log's identity — the body was repainted
    * wholesale, so nothing cached describes the DOM any more, but every cursor
    * already issued against this log stays comparable.
    */
  def cleared: FragmentLog = FragmentLog(id)

  /** Whether `html` is what this node was last known to hold — the ONE question
    * the stored digest answers. `false` for an absent entry: unknown means send
    * it, which is what makes dropping an entry always safe.
    */
  def holds(nodeId: NodeId, html: String, variant: Int = 0): Boolean =
    fragments
      .get(nodeId)
      .exists(_.digests.get(variant).contains(Digest.of(html)))

  /** Whether this node's variant was already recorded at or past `version` —
    * i.e. written from this same state, so its digest already describes what a
    * render would produce.
    *
    * The cheap half of the two skips: an integer comparison that spares the
    * render entirely, where [[holds]] must render first to compare. Versions
    * only grow and a write records the version it rendered from, so an entry
    * can never be ahead of a later read.
    */
  def atLeast(nodeId: NodeId, variant: Int, version: Long): Boolean =
    fragments
      .get(nodeId)
      .exists(f => f.version >= version && f.digests.contains(variant))

  /** Whether this node's element is currently recorded as DELETED.
    *
    * What makes a queued fill STALE. A fill is planned when a selection moves,
    * but sent when its connection reaches it, and by then the selection may
    * have moved again — in which case a later flip has already recorded this
    * member as [[Mutation.Gone]] and this fill would put back a branch that no
    * longer belongs, until the item behind it corrected the DOM.
    */
  def isGone(nodeId: NodeId): Boolean =
    mutations.get(nodeId).exists {
      case _: Mutation.Gone => true
      case _                => false
    }

  /** Whether `gid` is ESTABLISHED — i.e. the log knows what is in its mount, so
    * a membership change can be patched per-entity instead of filled wholesale.
    *
    * A container's MEMBERS are the whole record now: it logs no fragment of its
    * own, because that fragment would contain other nodes.
    */
  def hasChildOf(gid: NodeId): Boolean =
    fragments.keysIterator.exists(_.startsWith(gid + "_"))

  /** Record what the DOCUMENT path put on screen, without overwriting anything
    * the diff pass already knows.
    *
    * Statement (2) — everything that changes a client's DOM goes through the
    * log — applies to the first paint too, and it is the one path that never
    * told the log anything. Left unrecorded, a client's very first connect is
    * offered every node of every open surface as a candidate (no entry means
    * "unknown, send it"), so the page arrives twice.
    *
    * Absent-only because the log is SHARED and the seeding snapshot may already
    * be behind: a newer entry from the live pass describes the DOM better than
    * this one does, and clobbering it would cost a redundant send.
    */
  def seed(
      nodeId: NodeId,
      html: String,
      at: Long,
      variant: Int = 0
  ): FragmentLog =
    if (fragments.get(nodeId).exists(_.digests.contains(variant))) this
    else set(nodeId, html, at, variant)

  /** Record what a node holds, for one variant.
    *
    * **A fragment's version never goes backwards.** A variant-bearing node's
    * entry is written lazily, when some connection first asks for that variant,
    * so two batches can reach here out of order — a slow client forcing an old
    * batch after a newer one has already been served. An older write leaves the
    * version alone and refreshes nothing; the stale patch it produced is still
    * sent, and the batch behind it corrects the client a moment later.
    */
  def set(
      nodeId: NodeId,
      html: String,
      at: Long,
      variant: Int = 0
  ): FragmentLog =
    fragments.get(nodeId) match {
      case Some(f) if f.version > at => this
      case Some(f)                   =>
        copy(
          fragments = fragments.updated(
            nodeId,
            Fragment(f.digests.updated(variant, Digest.of(html)), at)
          )
        )
      case None =>
        copy(
          fragments = fragments.updated(
            nodeId,
            Fragment(Map(variant -> Digest.of(html)), at)
          )
        )
    }

  /** Forget a node's cached HTML WITHOUT recording a removal — the node's DOM
    * is being re-supplied by an ancestor's fresh HTML (a group repaint, a
    * bake-group flip), so the entry is merely stale, not gone. Replaying a
    * removal here would delete an element that ancestor legitimately restored.
    * Use [[removed]] for a node whose DOM really is being deleted.
    */
  def invalidate(nodeId: NodeId): FragmentLog =
    copy(fragments = fragments - nodeId)

  /** Invalidate a whole subtree because its ROOT is being re-stamped in the
    * same operation (a group repaint, a bake-group flip). The root's fresh HTML
    * is authoritative for everything under it, which supersedes the subtree's
    * [[Mutation]]s as well as its fragments — a stale `Gone` would delete a
    * member that root's HTML legitimately restored, and a stale `Placed` would
    * insert one it already contains. Callers must actually [[set]] the root —
    * this is not a bare `filterNot`.
    */
  def invalidateWhere(p: NodeId => Boolean): FragmentLog =
    copy(
      fragments = fragments.filterNot { case (k, _) => p(k) },
      mutations = mutations.filterNot { case (k, _) => p(k) }
    )

  /** Record that `nodeId`'s element was DELETED from `container`'s mount at
    * version `at`. The container rides along so eviction knows whose history it
    * just made incomplete.
    */
  def removed(container: NodeId, nodeId: NodeId, stamp: Stamp): FragmentLog =
    copy(
      fragments = fragments - nodeId,
      mutations = mutations.updated(nodeId, Mutation.Gone(container, stamp))
    ).evicting(stamp.millis)

  /** Record that `member` belongs at its CURRENT position in `container`'s
    * mount — an arrival today, a re-order once member sorting becomes
    * author-controlled. Also stamps its HTML, since the two always travel
    * together.
    */
  def placed(
      container: NodeId,
      member: MemberKey,
      nodeId: NodeId,
      html: String,
      stamp: Stamp
  ): FragmentLog =
    placed(container, member, nodeId, stamp).set(nodeId, html, stamp.version)

  /** [[placed]] for a member whose bytes are NOT one thing: its subtree mounts
    * a client-selected member, so what each viewer received differs and no
    * single digest describes them all.
    *
    * Recording the structure without the bytes is exactly what statement (3)
    * permits — an absent entry reads as "unknown, send it", so the cost is one
    * redundant re-send on the next tick and never a suppressed change. The
    * alternative, storing one viewer's digest as if it were everyone's, would
    * suppress a real change for all the others.
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

  /** Forget mutations older than [[FragmentLog.Retention]] relative to `now`,
    * raising [[horizon]] past everything dropped so no cursor is ever served a
    * delta that is missing one of them.
    *
    * `now` is passed in rather than read from a clock, keeping the whole log
    * pure and testable; the caller reads the clock once per diff, with the
    * snapshot.
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
        // Each affected container is complete only from just after the newest
        // thing forgotten about IT — one group aging out says nothing about any
        // other, which is the whole point of keying this per container.
        horizon = stale.values.foldLeft(horizon) { (h, m) =>
          h.updatedWith(m.container)(prev =>
            Some(math.max(prev.getOrElse(0L), m.version + 1))
          )
        }
      )
  }

  /** Whether a MUTATION is re-supplying an ancestor of `nodeId` — in which case
    * that mutation carries this node too, and anything sent for the node itself
    * would be a duplicate.
    *
    * This replaces a fragment-based test ("an ancestor's cached HTML already
    * contains this"), which the self/mount split retires: no fragment contains
    * another node any more, so nothing could ever be covered that way. But the
    * dedupe is still needed, because a [[Mutation.Placed]] re-supplies a whole
    * SUBTREE while the nodes inside it also carry `version >= cursor` and would
    * each ship as a no-op morph first — a branch's inner nodes would arrive as
    * morphs against ids the client's DOM does not hold yet, and only then the
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

  /** What a client whose cursor is `v` has not seen. TOTAL: there is no longer
    * a "cannot be told" case.
    *
    * It used to return `None` when `v` predated a GLOBAL horizon, meaning
    * "repaint the body". With the horizon per container, the answer for a
    * container whose history is gone is to fill THAT mount — so incompleteness
    * is expressed as a `refill` entry instead of a refusal, and the whole-body
    * repaint survives only for the genuinely global reasons the caller checks
    * (no cursor, a log-id mismatch, a cursor ahead of the store, a changed
    * head).
    *
    * `>=` rather than `>`: the cursor is pushed alongside a patch batch, and
    * one store version can produce several batches (one [[StateChange]] each),
    * so a client can hold version V having seen only part of it. Re-sending the
    * whole of V is idempotent — every patch is a morph or a fresh render — and
    * cheap, where missing half of it would be silent and permanent.
    *
    * Only the LATEST meaningful change per node survives, in three ways. Both
    * maps are keyed by node id, so repeated churn on one element collapses to
    * one entry rather than a replay. A mutated node is not ALSO reported as a
    * morph — the element may not be where (or whether) the client has it, and a
    * morph of an absent id silently does nothing, so its content rides the
    * mutation instead. And anything a MUTATION is re-supplying is dropped
    * ([[coveredByMutation]]): correctness never depended on that, but sending a
    * subtree twice defeats the point of resuming at all.
    *
    * It returns node IDS, not content: the caller renders them from the current
    * snapshot, which is at least as fresh as anything the log could have stored
    * (statement (3)). Sorting by version went with the HTML — there is nothing
    * stale left to order.
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
