package fh.view.model

import scala.collection.mutable.ArrayBuffer

/** A node's place in its dashboard's [[TreeIndex]]. A DOM id names a place, not
  * a node: two clauses of one member share theirs. An `Ix` is one index's, so a
  * URL never holds one, and a session or the log holds one only under the stamp
  * of the renderer that painted or recorded it (`runtime.Painter`,
  * `FragmentLog.stamp`).
  */
opaque type Ix = Int

object Ix {
  private[view] def apply(i: Int): Ix = i

  extension (ix: Ix) private[view] def toInt: Int = ix

  given CanEqual[Ix, Ix] = CanEqual.derived

  // So a host's shown roots are one `Array[Int]`, not boxed.
  given scala.reflect.ClassTag[Ix] = scala.reflect.ClassTag.Int
}

/** Every node of a dashboard's tree in pre-order, every clause of every
  * candidate and every case included. The walk that mints node ids; everything
  * else reads it. Built from trees `validate` refuses too, so it records (a
  * repeated id) and never fails.
  */
final class TreeIndex private (entries: Vector[TreeIndex.Entry]) {

  def size: Int = entries.size

  def all: Iterator[Ix] = Iterator.range(0, entries.size).map(Ix(_))

  /** The page's root. */
  val root: Ix = Ix(0)

  def node(ix: Ix): LayoutNode.Component = entries(ix.toInt).node

  def id(ix: Ix): NodeId = entries(ix.toInt).id

  /** A clause's root has the set's host as its parent. */
  def parent(ix: Ix): Option[Ix] =
    Option.when(entries(ix.toInt).parent >= 0)(Ix(entries(ix.toInt).parent))

  /** Where its parent's nodes slots hold it; none for a root or a clause. */
  def step(ix: Ix): Option[LayoutNode.Step] = entries(ix.toInt).step

  def within(ix: Ix): Dashboard.Within = entries(ix.toInt).within

  /** The pick it hosts ([[Dashboard.pickId]], a clause's own); else its id. */
  def group(ix: Ix): NodeId = {
    val e = entries(ix.toInt)
    if (e.node.chosen.isEmpty) e.id
    else Dashboard.pickId(e.id, e.within.map(_._2))
  }

  /** The innermost case holding it, else the root. */
  def container(ix: Ix): Ix = Ix(entries(ix.toInt).container)

  /** The root, or a case: the root is always shown, a case may be hidden. */
  def isContainer(ix: Ix): Boolean = entries(ix.toInt).container == ix.toInt

  /** The container its container's root sits in; none for the root. */
  def containerAbove(c: Ix): Option[Ix] = parent(c).map(container)

  /** Every node spelled `id`: one per clause where a member's clauses share it.
    */
  def ixsOf(id: NodeId): List[Ix] = byId.getOrElse(id, Nil)

  private lazy val byId: Map[NodeId, List[Ix]] =
    all.toList.groupBy(id)

  /** Whether `inner` is `outer` or below it: `[outer, end)` is its subtree. */
  def contains(outer: Ix, inner: Ix): Boolean =
    outer.toInt <= inner.toInt && inner.toInt < entries(outer.toInt).end

  /** Its set's clauses by candidate, each candidate's in clause order. */
  def clauses(ix: Ix): Map[String, List[Ix]] =
    children(ix)
      .flatMap(c => entries(c.toInt).candidate.map(_ -> c))
      .groupMap(_._1)(_._2)

  /** Its slots' nodes in step order, then its set's clauses. */
  def children(ix: Ix): List[Ix] = {
    val end = entries(ix.toInt).end
    List.unfold(ix.toInt + 1)(c =>
      Option.when(c < end)(Ix(c) -> entries(c).end)
    )
  }
}

object TreeIndex {

  private final case class Entry(
      node: LayoutNode.Component,
      id: NodeId,
      parent: Int,
      step: Option[LayoutNode.Step],
      within: Dashboard.Within,
      candidate: Option[String],
      container: Int,
      end: Int
  )

  def of(card: LayoutNode): TreeIndex = {
    val out = ArrayBuffer.empty[Entry]
    // `container` -1: this node is one (a root or a case).
    def walk(
        node: LayoutNode,
        id: NodeId,
        parent: Int,
        step: Option[LayoutNode.Step],
        within: Dashboard.Within,
        candidate: Option[String],
        container: Int
    ): Unit = node match {
      case c: LayoutNode.Component =>
        val ix = out.size
        val in = if (container < 0) ix else container
        out += Entry(
          c,
          id,
          parent,
          step,
          within,
          candidate,
          in,
          -1
        )
        LayoutNode.steps(c.slotNodes).foreach { (st, ch) =>
          walk(
            ch,
            LayoutNode.childIdWithin(id, st, ch, within),
            ix,
            Some(st),
            within.map((m, cl, path) => (m, cl, path :+ st)),
            None,
            if (c.chosen.contains(st.region)) -1 else in
          )
        }
        c.set.foreach { (_, s) =>
          s.members.toList.sortBy(_._1).foreach { (entityId, m) =>
            val member = NodeId.derived(LayoutNode.memberSegment(id, entityId))
            m.indexed.foreach((clause, n) =>
              walk(
                n,
                member,
                ix,
                None,
                Some((member, clause, Nil)),
                Some(entityId),
                in
              )
            )
          }
        }
        out(ix) = out(ix).copy(end = out.size)
    }
    walk(card, LayoutNode.rootId(card), -1, None, None, None, -1)
    new TreeIndex(out.toVector)
  }
}
