package fh.view.runtime

/** One rendered fragment and the store version its HTML reflects.
  *
  * A named type rather than a `(String, Long)` because the version is the whole
  * point: it turns the diff cache from "what did we last broadcast" into "when
  * did each fragment last change", which is what lets a reconnecting client be
  * told the difference instead of the whole body (docs/plan-sse-resume.md).
  */
private[runtime] case class Fragment(html: String, version: Long)

/** What a resume owes a client holding a given cursor: fragments to morph (in
  * version order — a container's cached HTML embeds its children, so a stale
  * parent applied after a fresh child would revert it) and dynamic groups whose
  * MEMBERSHIP moved, which must be re-rendered rather than replayed.
  */
private[runtime] case class Resume(
    fragments: List[Fragment],
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
  * `structural` records dynamic groups whose rendered MEMBERSHIP changed, and
  * exists because a membership change cannot be replayed from cached HTML: an
  * added member was sent as an `insert` positioned relative to its DOM
  * neighbours, and by resume time that anchor may itself be gone. Re-rendering
  * the group from current state is correct regardless of what the client holds.
  * Keyed by group id, so — like `fragments`, keyed by node id — it is bounded by
  * dashboard size and never needs trimming.
  */
private[runtime] case class FragmentLog(
    id: String,
    fragments: Map[String, Fragment] = Map.empty,
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

  /** Forget a node's cached HTML. Deliberately NOT a tombstone: every
    * invalidation site (a group repaint, a bake-group flip, a per-entity
    * membership edit) either morphs an ancestor whose fresh HTML contains the
    * node, or marks the group [[structuralAt]] — so a resuming client is
    * repaired by that, and replaying a removal here would delete elements the
    * ancestor's HTML legitimately re-supplied.
    */
  def invalidate(nodeId: String): FragmentLog =
    copy(fragments = fragments - nodeId)

  def invalidateWhere(p: String => Boolean): FragmentLog =
    copy(fragments = fragments.filterNot { case (k, _) => p(k) })

  /** Record that `gid`'s rendered membership moved at version `at`. */
  def structuralAt(gid: String, at: Long): FragmentLog =
    copy(structural = structural.updated(gid, at))

  /** What a client whose cursor is `v` has not seen.
    *
    * `>=` rather than `>`: the cursor is pushed alongside a patch batch, and one
    * store version can produce several batches (one [[StateChange]] each), so a
    * client can hold version V having seen only part of it. Re-sending the whole
    * of V is idempotent — every patch is a morph or a fresh render — and cheap,
    * where missing half of it would be silent and permanent.
    *
    * Fragments belonging to a group being re-rendered are dropped: the group's
    * fresh HTML already contains them.
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
      groups
    )
  }
}
