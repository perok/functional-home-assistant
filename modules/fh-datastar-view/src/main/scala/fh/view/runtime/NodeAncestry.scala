package fh.view.runtime

import fh.view.model.NodeId

/** Which node contains which — as a RELATION over the id space, rather than a
  * test on how ids are spelled.
  *
  * Ancestry decides real things: which fragments a container's refill makes
  * stale, whether a node's own change is already covered by an ancestor that
  * moved, what a patch displaced. All three used to ask
  * `id.startsWith(parent + "_")`, justified by ids being position-derived so
  * that `c_3` prefixes `c_3_0` and nothing else.
  *
  * That justification stopped holding when an author could NAME a node
  * (`LayoutNode.Component.id`): `detail` and `detail_0` are unrelated nodes
  * whose ids read as parent and child, so invalidating the first would silently
  * take the second. The choice is to constrain every author forever, or to stop
  * inferring structure from a string. This is the second.
  *
  * It costs nothing to know: **the whole id space is static.** The layout tree
  * and every surface's content are fixed at build time, and so is the set of
  * possible members — `MemberGraph.sources` says so for the part that looks
  * dynamic ("a set's candidates are static, so the whole tree of sets is
  * knowable before any state arrives"). Presence varies; the id space does not.
  *
  * Built once per renderer and shared, which is also why the queries got
  * cheaper: `FragmentLog.filled` used to scan every entry in the log testing a
  * string, and now removes exactly the subtree.
  */
private[runtime] final class NodeAncestry private (
    ancestors: Map[NodeId, Set[NodeId]],
    descendants: Map[NodeId, Set[NodeId]]
) {

  /** STRICT: a node is never its own ancestor. Every caller here means "someone
    * else covers me", and a node covering itself would suppress its own
    * emission.
    */
  def ancestorsOf(id: NodeId): Set[NodeId] = ancestors.getOrElse(id, Set.empty)

  /** STRICT descendants — everything under `id`, not `id`. */
  def descendantsOf(id: NodeId): Set[NodeId] =
    descendants.getOrElse(id, Set.empty)

  /** Is `id` strictly beneath any of `roots`? */
  def under(id: NodeId, roots: Set[NodeId]): Boolean =
    roots.nonEmpty && ancestorsOf(id).exists(roots.contains)

  /** Is `id` one of `roots`, or beneath one? What a wholesale re-supply of
    * `roots` makes unknown.
    */
  def withinAny(id: NodeId, roots: Set[NodeId]): Boolean =
    roots.contains(id) || under(id, roots)
}

private[runtime] object NodeAncestry {

  /** For a log with no renderer behind it. Nothing is related to anything, so
    * every node stands alone — which is the right answer for an empty tree and
    * the wrong one for a real dashboard, hence [[fromParents]] everywhere else.
    */
  val empty: NodeAncestry = fromParents(Map.empty)

  /** `child -> its parent`, from whoever walked the tree and minted the ids —
    * so the relation and the ids come from the SAME traversal and cannot
    * disagree.
    */
  def fromParents(parentOf: Map[NodeId, NodeId]): NodeAncestry = {
    // Walk up once per node. Bounded by the map size rather than trusting the
    // input to be acyclic: a cycle here would hang the renderer at
    // construction, which is a worse failure than a wrong answer.
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
