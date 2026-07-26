package fh.view.runtime

/** One rendered fragment and the store version its HTML reflects.
  *
  * A named type rather than a `(String, Long)` because the version is the whole
  * point: it turns the diff cache from "what did we last broadcast" into "when
  * did each fragment last change", which is what lets a reconnecting client be
  * told the difference instead of the whole body (docs/plan-sse-resume.md).
  */
private[runtime] case class Fragment(html: String, version: Long)

/** What a resume owes a client holding a given cursor:
  *
  *   - `fragments` to morph, in version order — a container's cached HTML
  *     embeds its children, so a stale parent applied after a fresh child would
  *     revert it;
  *   - `removals`: node ids to delete, replayed verbatim (a `remove` of an
  *     absent id is a no-op, so this needs no knowledge of the client's DOM);
  *   - `groups`: dynamic groups that GAINED a member, which must be re-rendered
  *     because an `insert` cannot be replayed — see [[FragmentLog.structural]].
  */
private[runtime] case class Resume(
    fragments: List[Fragment],
    removals: List[String],
    groups: List[String]
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
  * The two membership records are split by what can be REPLAYED, which is the
  * only asymmetry that matters here:
  *
  *   - `tombstones` (node id -> version it was removed at) covers a member
  *     LEAVING. A `remove` patch is idempotent (see [[Datastar.remove]]), so it
  *     replays verbatim — no re-render, and a departure costs one tiny patch
  *     instead of a whole-group morph.
  *   - `structural` (group id -> version) covers a member ARRIVING, which
  *     cannot be replayed at all: the `insert` was positioned relative to a DOM
  *     neighbour that may be gone by resume time, and recomputing the anchor
  *     needs the client's DOM ordering — the one thing this design refuses to
  *     track. Re-rendering the group from current state is correct regardless
  *     of what the client holds.
  *
  * Both are keyed by id (node, group) rather than by event, so — like
  * `fragments` — they are bounded by dashboard size and never need trimming.
  */
private[runtime] case class FragmentLog(
    id: String,
    fragments: Map[String, Fragment] = Map.empty,
    tombstones: Map[String, Long] = Map.empty,
    structural: Map[String, Long] = Map.empty
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
    * tombstones as well as its fragments: a member that left and later rejoined
    * would otherwise keep a tombstone able to delete it again on resume.
    * Callers must actually [[set]] the root — this is not a bare `filterNot`.
    */
  def invalidateWhere(p: String => Boolean): FragmentLog =
    copy(
      fragments = fragments.filterNot { case (k, _) => p(k) },
      tombstones = tombstones.filterNot { case (k, _) => p(k) }
    )

  /** Record that `nodeId`'s element was DELETED from the DOM at version `at`.
    */
  def removed(nodeId: String, at: Long): FragmentLog =
    copy(
      fragments = fragments - nodeId,
      tombstones = tombstones.updated(nodeId, at)
    )

  /** Record that `gid` GAINED a member at version `at`, so a resume must
    * re-render it rather than replay patches.
    */
  def structuralAt(gid: String, at: Long): FragmentLog =
    copy(structural = structural.updated(gid, at))

  /** What a client whose cursor is `v` has not seen.
    *
    * `>=` rather than `>`: the cursor is pushed alongside a patch batch, and
    * one store version can produce several batches (one [[StateChange]] each),
    * so a client can hold version V having seen only part of it. Re-sending the
    * whole of V is idempotent — every patch is a morph or a fresh render — and
    * cheap, where missing half of it would be silent and permanent.
    *
    * Anything belonging to a group being re-rendered is dropped, because that
    * render is authoritative for the group's CURRENT membership. For fragments
    * that is merely redundant; for tombstones it is load-bearing — an entity
    * that left and later rejoined has both a tombstone and a structural mark,
    * and replaying the removal after the re-render would delete a live member.
    */
  def since(v: Long): Resume = {
    val groups = structural.collect { case (gid, at) if at >= v => gid }.toList
    val covered = (id: String) =>
      groups.exists(gid => id == gid || id.startsWith(gid + "_"))
    Resume(
      fragments
        .collect {
          case (nodeId, f) if f.version >= v && !covered(nodeId) => f
        }
        .toList
        .sortBy(_.version),
      tombstones.collect {
        case (nodeId, at) if at >= v && !covered(nodeId) => nodeId
      }.toList,
      groups
    )
  }
}
