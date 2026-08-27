package fh.view.runtime

import fh.view.model.NodeId

/** Ancestry for the suites that hand-write their own ids.
  *
  * A [[NodeAncestry]] normally comes from the walk that MINTED the ids, so the
  * relation and the ids cannot disagree. A pure `FragmentLog` suite has no
  * renderer and no tree — just literal strings like `c_0`, `c_1_new` — so this
  * derives the relation from their spelling instead.
  *
  * That is the very inference `NodeAncestry` exists to remove from production,
  * and it is correct HERE for the reason it stopped being correct there: these
  * ids really are position-derived, because a test wrote them that way. Keeping
  * it in test scaffolding is the point — the rule is visible, and nothing in
  * `main` can reach for it.
  */
private[runtime] object TestAncestry {

  /** `child -> parent` by longest `_`-prefix among `ids`. */
  def of(ids: Set[NodeId]): NodeAncestry =
    NodeAncestry.fromParents(
      ids.toList.flatMap { id =>
        ids.toList
          .filter(other => other != id && (id: String).startsWith(other + "_"))
          .sortBy(o => -(o: String).length)
          .headOption
          .map(id -> _)
      }.toMap
    )

  /** Every id a log knows about — what a suite means by "the tree" when the
    * log IS the only structure it has.
    */
  def of(log: FragmentLog): NodeAncestry =
    of(log.fragments.keySet ++ log.mutations.keySet ++ log.horizon.keySet)
}
