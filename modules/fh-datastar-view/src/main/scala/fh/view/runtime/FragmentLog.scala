package fh.view.runtime

/** One rendered fragment and the store version its HTML reflects.
  *
  * A named type rather than a `(String, Long)` because the version is the whole
  * point: it turns the diff cache from "what did we last broadcast" into "when
  * did each fragment last change", which is what lets a reconnecting client be
  * told the difference instead of the whole body (docs/plan-sse-resume.md).
  */
private[runtime] case class Fragment(html: String, version: Long)

/** The last STRUCTURAL thing that happened to a node — as opposed to a change
  * in its content, which is a [[Fragment]]. One value rather than a pair of
  * parallel "removed"/"arrived" records, because a node cannot be both gone and
  * present: holding two maps made that invalid state representable and turned
  * every leave-then-rejoin into a special case. Latest wins, so a rejoin is
  * simply [[Placed]] replacing [[Gone]].
  */
private[runtime] enum Mutation(val version: Long) {

  /** The element was deleted from the DOM. */
  case Gone(at: Long) extends Mutation(at)

  /** The element belongs at its CURRENT position in `gid`, wherever (or
    * whether) the client currently has it. Carries `gid`/`entityId` rather than
    * just the node id because the anchor has to be re-derived from live
    * membership and [[Dashboard.sanitize]] is one-way.
    *
    * Covers an arrival and a re-ordering identically — both mean "put this
    * element here" — which is why an author-chosen member sort needs no new
    * mutation kind.
    */
  case Placed(gid: String, entityId: String, at: Long) extends Mutation(at)
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
  * morph of an element the client already has (see [[Mutation]]). Both are
  * keyed by node id rather than by event, so the log is bounded by dashboard
  * size and never needs trimming: a node has one latest content and one latest
  * structural fact.
  */
private[runtime] case class FragmentLog(
    id: String,
    fragments: Map[String, Fragment] = Map.empty,
    mutations: Map[String, Mutation] = Map.empty
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
  def removed(nodeId: String, at: Long): FragmentLog =
    copy(
      fragments = fragments - nodeId,
      mutations = mutations.updated(nodeId, Mutation.Gone(at))
    )

  /** Record that `entityId` belongs at its CURRENT position in group `gid` as
    * of version `at` — an arrival today, a re-order once member sorting becomes
    * author-controlled. Also stamps its HTML, since the two always travel
    * together.
    */
  def placed(
      gid: String,
      entityId: String,
      nodeId: String,
      html: String,
      at: Long
  ): FragmentLog =
    set(nodeId, html, at).copy(
      mutations = mutations.updated(nodeId, Mutation.Placed(gid, entityId, at))
    )

  /** Whether some fragment at or after `version` already covers `nodeId` — i.e.
    * the node itself, or an ANCESTOR of it, is being re-sent with HTML rendered
    * no earlier than `version`, so that HTML already contains this node.
    *
    * Ancestry is a string-prefix test because ids are location-derived
    * ([[Dashboard.pathId]]: `c`, `c_0`, `c_0_1`); the trailing `_` keeps `c_1`
    * from matching `c_10`.
    */
  def coveredByAncestor(nodeId: String, version: Long): Boolean =
    fragments.exists { case (id, f) =>
      f.version >= version && (id == nodeId || nodeId.startsWith(id + "_"))
    }

  /** What a client whose cursor is `v` has not seen.
    *
    * `>=` rather than `>`: the cursor is pushed alongside a patch batch, and
    * one store version can produce several batches (one [[StateChange]] each),
    * so a client can hold version V having seen only part of it. Re-sending the
    * whole of V is idempotent — every patch is a morph or a fresh render — and
    * cheap, where missing half of it would be silent and permanent.
    *
    * A mutated node's fragment is NOT also reported as a morph: the element may
    * not be where (or whether) the client has it, and a morph of an absent id
    * silently does nothing. Its HTML rides the mutation instead.
    */
  def since(v: Long): Resume = {
    val moved = mutations.filter { case (_, m) => m.version >= v }
    Resume(
      fragments
        .collect {
          case (nodeId, f) if f.version >= v && !moved.contains(nodeId) => f
        }
        .toList
        .sortBy(_.version),
      moved.toList
    )
  }
}
