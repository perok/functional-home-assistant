package fh.view.runtime

import fh.view.model.NodeId

/** Containment as a relation, not a test on id spelling: an authored id
  * `detail` and an unrelated `detail_0` read as parent and child. Knowable up
  * front because the whole id space is static; presence varies, ids do not.
  */
private[runtime] final class NodeAncestry private (
    ancestors: Map[NodeId, Set[NodeId]],
    descendants: Map[NodeId, Set[NodeId]]
) {

  /** Strict: a node covering itself would suppress its own emission. */
  def ancestorsOf(id: NodeId): Set[NodeId] = ancestors.getOrElse(id, Set.empty)

  def descendantsOf(id: NodeId): Set[NodeId] =
    descendants.getOrElse(id, Set.empty)

  def under(id: NodeId, roots: Set[NodeId]): Boolean =
    roots.nonEmpty && ancestorsOf(id).exists(roots.contains)

  def withinAny(id: NodeId, roots: Set[NodeId]): Boolean =
    roots.contains(id) || under(id, roots)
}

private[runtime] object NodeAncestry {

  val empty: NodeAncestry = fromParents(Map.empty)

  /** From the walk that minted the ids, so the two cannot disagree. */
  def fromParents(parentOf: Map[NodeId, NodeId]): NodeAncestry = {
    // Bounded, not trusting the input to be acyclic: a cycle would hang the
    // renderer at construction.
    val limit = parentOf.size + 1
    val ancestors: Map[NodeId, Set[NodeId]] =
      parentOf.keys.map { id =>
        var acc = Set.empty[NodeId]
        var cur = parentOf.get(id)
        var steps = 0
        while (cur.isDefined && steps < limit && !acc.contains(cur.get)) {
          acc = acc + cur.get
          cur = parentOf.get(cur.get)
          steps += 1
        }
        id -> acc
      }.toMap

    val descendants: Map[NodeId, Set[NodeId]] =
      ancestors.toList
        .flatMap { case (id, above) => above.map(_ -> id) }
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap

    new NodeAncestry(ancestors, descendants)
  }
}
