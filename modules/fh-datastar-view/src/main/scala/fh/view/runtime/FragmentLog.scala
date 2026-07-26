package fh.view.runtime

import scala.concurrent.duration.*

/** One rendered fragment and the store version its HTML reflects.
  *
  * A named type rather than a `(String, Long)` because the version is the whole
  * point: it turns the diff cache from "what did we last broadcast" into "when
  * did each fragment last change", which is what lets a reconnecting client be
  * told the difference instead of the whole body (docs/plan-sse-resume.md).
  */
private[runtime] case class Fragment(html: String, version: Long)

/** When a diff pass rendered — both clocks it needs, together.
  *
  *   - `version`: the store version the snapshot was read at. ORDERS everything
  *     (fragment staleness, cursor comparison), and is the only clock any
  *     correctness argument rests on.
  *   - `millis`: wall clock, used ONLY to age mutations out
  *     ([[FragmentLog.Retention]]). Never compared against a version, and never
  *     used to order anything — which is what keeps this from reintroducing the
  *     two-clocks-in-one-ordering problem that ruled out HA's `last_updated` as
  *     the cursor (see docs/plan-sse-resume.md).
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
    * the rejected per-client mirror (alternatives (a)/(b) in the plan) and
    * makes it acceptable. And a wedged or hung connection would pin the log
    * open indefinitely, so the age bound has to survive as a floor regardless:
    * the real rule is `min(live cursors)` clamped by this duration, not one or
    * the other.
    */
  val Retention: FiniteDuration = 1.hour
}

/** The last STRUCTURAL thing that happened to a node — as opposed to a change
  * in its content, which is a [[Fragment]]. One value rather than a pair of
  * parallel "removed"/"arrived" records, because a node cannot be both gone and
  * present: holding two maps made that invalid state representable and turned
  * every leave-then-rejoin into a special case. Latest wins, so a rejoin is
  * simply [[Placed]] replacing [[Gone]].
  */
private[runtime] enum Mutation(val version: Long, val millis: Long) {

  /** The element was deleted from the DOM. */
  case Gone(at: Long, wall: Long) extends Mutation(at, wall)

  /** The element belongs at its CURRENT position in `gid`, wherever (or
    * whether) the client currently has it. Carries `gid`/`entityId` rather than
    * just the node id because the anchor has to be re-derived from live
    * membership and [[Dashboard.sanitize]] is one-way.
    *
    * Covers an arrival and a re-ordering identically — both mean "put this
    * element here" — which is why an author-chosen member sort needs no new
    * mutation kind.
    */
  case Placed(gid: String, entityId: String, at: Long, wall: Long)
      extends Mutation(at, wall)
}

/** What a resume owes a client holding a given cursor:
  *
  *   - `fragments` to morph, in version order — a container's cached HTML
  *     embeds its children, so a stale parent applied after a fresh child would
  *     revert it;
  *   - `mutations` (node id -> what happened) to apply structurally. See
  *     [[Patches.resume]] for how each becomes patches, and for the ordering
  *     argument that makes the anchors resolvable.
  */
private[runtime] case class Resume(
    fragments: List[Fragment],
    mutations: List[(String, Mutation)]
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
    fragments: Map[String, Fragment] = Map.empty,
    mutations: Map[String, Mutation] = Map.empty,
    // The oldest version for which `mutations` is COMPLETE. Rises as entries are
    // evicted; a cursor below it cannot be served a delta and must repaint,
    // which is what makes eviction safe rather than silently lossy.
    horizon: Long = 0L
) {

  /** Forget everything but keep this log's identity — the body was repainted
    * wholesale, so nothing cached describes the DOM any more, but every cursor
    * already issued against this log stays comparable.
    */
  def cleared: FragmentLog = FragmentLog(id)

  def html(nodeId: String): Option[String] = fragments.get(nodeId).map(_.html)

  def has(nodeId: String): Boolean = fragments.contains(nodeId)

  /** Whether `gid` has any rendered child entry — half of the "group is
    * established" test that decides per-entity patching vs. a whole repaint.
    */
  def hasChildOf(gid: String): Boolean =
    fragments.keysIterator.exists(_.startsWith(gid + "_"))

  def set(nodeId: String, html: String, at: Long): FragmentLog =
    copy(fragments = fragments.updated(nodeId, Fragment(html, at)))

  /** Forget a node's cached HTML WITHOUT recording a removal — the node's DOM
    * is being re-supplied by an ancestor's fresh HTML (a group repaint, a
    * bake-group flip), so the entry is merely stale, not gone. Replaying a
    * removal here would delete an element that ancestor legitimately restored.
    * Use [[removed]] for a node whose DOM really is being deleted.
    */
  def invalidate(nodeId: String): FragmentLog =
    copy(fragments = fragments - nodeId)

  /** Invalidate a whole subtree because its ROOT is being re-stamped in the
    * same operation (a group repaint, a bake-group flip). The root's fresh HTML
    * is authoritative for everything under it, which supersedes the subtree's
    * [[Mutation]]s as well as its fragments — a stale `Gone` would delete a
    * member that root's HTML legitimately restored, and a stale `Placed` would
    * insert one it already contains. Callers must actually [[set]] the root —
    * this is not a bare `filterNot`.
    */
  def invalidateWhere(p: String => Boolean): FragmentLog =
    copy(
      fragments = fragments.filterNot { case (k, _) => p(k) },
      mutations = mutations.filterNot { case (k, _) => p(k) }
    )

  /** Record that `nodeId`'s element was DELETED from the DOM at version `at`.
    */
  def removed(nodeId: String, stamp: Stamp): FragmentLog =
    copy(
      fragments = fragments - nodeId,
      mutations =
        mutations.updated(nodeId, Mutation.Gone(stamp.version, stamp.millis))
    ).evicting(stamp.millis)

  /** Record that `entityId` belongs at its CURRENT position in group `gid` — an
    * arrival today, a re-order once member sorting becomes author-controlled.
    * Also stamps its HTML, since the two always travel together.
    */
  def placed(
      gid: String,
      entityId: String,
      nodeId: String,
      html: String,
      stamp: Stamp
  ): FragmentLog =
    set(nodeId, html, stamp.version)
      .copy(
        mutations = mutations.updated(
          nodeId,
          Mutation.Placed(gid, entityId, stamp.version, stamp.millis)
        )
      )
      .evicting(stamp.millis)

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
        // Complete only from just after the newest thing we forgot.
        horizon = math.max(horizon, stale.values.map(_.version).max + 1)
      )
  }

  /** Whether an ANCESTOR of `nodeId` holds HTML rendered no earlier than
    * `version` — in which case that HTML already contains this node as of at
    * least `version`, and anything we would send for the node itself is
    * redundant. A container's rendered HTML embeds its whole subtree, which is
    * what makes this sound.
    *
    * STRICT ancestors: a node never covers itself, or every fragment would
    * suppress its own emission. A dynamic-group child is covered by its group,
    * since its id is `{gid}_{entity}` and so has `gid` as a prefix-ancestor.
    *
    * Ancestry is a string-prefix test because ids are location-derived
    * ([[Dashboard.pathId]]: `c`, `c_0`, `c_0_1`); the trailing `_` keeps `c_1`
    * from matching `c_10`.
    */
  def coveredByAncestor(nodeId: String, version: Long): Boolean =
    fragments.exists { case (id, f) =>
      f.version >= version && id != nodeId && nodeId.startsWith(id + "_")
    }

  /** What a client whose cursor is `v` has not seen, or `None` when it cannot
    * be told — `v` predates [[horizon]], so an evicted mutation may be exactly
    * what this client is missing. `None` means "repaint the body", the same
    * answer as a mismatched log [[id]].
    *
    * An `Option` rather than a `resumable` predicate the caller is trusted to
    * check first: the failure mode of forgetting is a client left holding a
    * ghost element indefinitely, with nothing to observe at the time of the
    * mistake.
    *
    * `>=` rather than `>`: the cursor is pushed alongside a patch batch, and
    * one store version can produce several batches (one [[StateChange]] each),
    * so a client can hold version V having seen only part of it. Re-sending the
    * whole of V is idempotent — every patch is a morph or a fresh render — and
    * cheap, where missing half of it would be silent and permanent.
    *
    * Only the LATEST meaningful change per node survives, in three ways. Both
    * maps are keyed by node id, so repeated churn on one element collapses to
    * one entry rather than a replay. A mutated node's fragment is not ALSO
    * reported as a morph — the element may not be where (or whether) the client
    * has it, and a morph of an absent id silently does nothing, so its (latest)
    * HTML rides the mutation instead. And anything an ANCESTOR's HTML already
    * carries is dropped ([[coveredByAncestor]]): correctness never depended on
    * that (version order makes the ancestor win anyway), but sending a subtree
    * twice defeats the point of resuming at all.
    */
  def since(v: Long): Option[Resume] =
    Option.when(v >= horizon) {
      val moved = mutations.filter { case (nodeId, m) =>
        m.version >= v && !coveredByAncestor(nodeId, m.version)
      }
      Resume(
        fragments
          .collect {
            case (nodeId, f)
                if f.version >= v && !moved.contains(nodeId) &&
                  !coveredByAncestor(nodeId, f.version) =>
              f
          }
          .toList
          .sortBy(_.version),
        moved.toList
      )
    }
}
