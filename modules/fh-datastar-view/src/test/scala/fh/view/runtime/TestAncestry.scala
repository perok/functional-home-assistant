package fh.view.runtime

import fh.view.model.NodeId

/** Ancestry derived from id spelling, for suites that hand-write ids and have
  * no tree. The inference `NodeAncestry` removes from production, correct here
  * because a test wrote the ids positionally; kept in test code so `main`
  * cannot reach for it.
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

  def of(log: FragmentLog): NodeAncestry =
    of(log.fragments.keySet ++ log.mutations.keySet ++ log.horizon.keySet)
}
