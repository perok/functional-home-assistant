package fh.view.runtime

import fh.view.model.{LayoutNode, MemberId, NodeId, Predicate, SetId}

/** A candidate set member materialised as an ordinary node. Its node is
  * state-derived (which clause matched), so it is replaced when the entity
  * crosses a clause boundary ([[MemberGraph.syncMembers]]).
  */
private[runtime] object Member {

  /** The whole subtree's entities: a member renders its children under its own
    * id.
    */
  def entitiesOf(node: LayoutNode): List[String] = node match {
    case c: LayoutNode.Component =>
      (c.liveEntities ++ c.allChildren.flatMap(entitiesOf)).distinct
    // Not into a nested set, whose members patch themselves; descending would
    // wake the whole tile on any bulb inside it.
    case _ => Nil
  }
}

private[runtime] case class Member(
    gid: SetId,
    // `""` for the main page, else the surface id: who its patch may reach.
    root: String,
    key: MemberKey,
    id: MemberId,
    node: LayoutNode.Component,
    // Part of a nested set's id, so two clauses holding sets cannot share one.
    clause: Int
)

/** `replaced`: members whose node was swapped because their clause moved. */
private[runtime] case class MemberDelta(
    was: List[String],
    now: List[String],
    replaced: Set[MemberId]
)

/** Cached with its entity projection, so a frame that only ticks members costs
  * the frame, not the set.
  */
private final case class GroupMembers(
    members: Vector[Member],
    entities: List[String]
)

private object GroupMembers {
  def of(members: Vector[Member]): GroupMembers =
    GroupMembers(
      members,
      members.toList.collect {
        case Member(_, _, MemberKey.Entity(e), _, _, _) =>
          e
      }
    )
}

/** Every set's members plus the id and entity indices over them, updated
  * together. The entity index lets a member re-render because something it
  * binds moved, as a static node does.
  */
private final case class MemberIndex(
    byGroup: Map[SetId, GroupMembers],
    // `NodeId`, not `MemberId`: this lookup IS the parse of "is this arbitrary
    // id a member?".
    byId: Map[NodeId, Member],
    byEntity: Map[String, Vector[Member]]
) {

  /** Skips on `eq`: [[MemberGraph.syncMembers]] hands back the same value when
    * nothing moved.
    */
  def install(gid: SetId, was: GroupMembers, now: GroupMembers): MemberIndex =
    if ((now eq was) && byGroup.contains(gid)) this
    else {
      val leaving = byGroup.get(gid).toVector.flatMap(_.members)
      MemberIndex(
        byGroup.updated(gid, now),
        byId -- leaving.map(_.id) ++ now.members.map(m => m.id -> m),
        now.members.foldLeft(leaving.foldLeft(byEntity)(drop)) { (idx, m) =>
          Member
            .entitiesOf(m.node)
            .foldLeft(idx)((acc, e) =>
              acc.updated(e, acc.getOrElse(e, Vector.empty) :+ m)
            )
        }
      )
    }

  private def drop(
      idx: Map[String, Vector[Member]],
      m: Member
  ): Map[String, Vector[Member]] =
    Member.entitiesOf(m.node).foldLeft(idx) { (acc, e) =>
      acc.get(e).map(_.filterNot(_.id == m.id)) match {
        case Some(rest) if rest.nonEmpty => acc.updated(e, rest)
        case _                           => acc - e
      }
    }
}

/** Who is in every candidate set, and in what order: the graph decides presence
  * and order, the renderer paints (ADR 0003).
  *
  * @param setNodes
  *   the statically indexed sets; nested ones are discovered from these.
  * @param rootOfIndexed
  *   static node id -> its layout tree (`""` for the main page).
  */
private[runtime] final class MemberGraph(
    setNodes: Map[NodeId, LayoutNode.SetNode],
    rootOfIndexed: Map[NodeId, String]
) {

  private case class MemberSource(s: LayoutNode.SetNode) {

    private val position: Map[String, Int] = s.candidates.zipWithIndex.toMap

    /** entity -> the candidates it can decide, so a frame costs its changes,
      * not the candidate list.
      */
    private val movedBy: Map[String, List[String]] =
      s.candidates
        .flatMap { cid =>
          val named = s.members
            .get(cid)
            .toList
            .flatMap(_.clauses)
            .flatMap(_.when.toList)
            .flatMap(Predicate.referencedEntities)
          (cid :: named).distinct.map(_ -> cid)
        }
        .groupMap(_._1)(_._2)

    val candidates: Vector[String] = s.candidates.toVector

    def setId(at: NodeId): SetId = SetId.of(at, s)

    /** An entity HA does not know is evaluated against an empty state, so a
      * clause guarded only on another entity still decides.
      */
    def memberOf(
        gid: SetId,
        entityId: String,
        states: Map[String, EntityState]
    ): Option[Member] = {
      val subject =
        states.getOrElse(entityId, EntityState(entityId, "", Map.empty))
      s.members
        .get(entityId)
        .flatMap(
          _.clauses.zipWithIndex
            .find(_._1.when.forall(Conditions.matchesIn(_, subject, states)))
        )
        // A bare set clause has no rendering to be; a set nested inside a
        // component clause is the supported shape.
        .collect { case (LayoutNode.SetClause(_, c: LayoutNode.Component), i) =>
          member(gid, entityId, c, i)
        }
    }

    def affected(change: StateChange): Iterable[String] =
      movedBy.getOrElse(change.entityId, Nil)

    def ordinal(entityId: String): (Int, String) =
      (position.getOrElse(entityId, Int.MaxValue), entityId)

    def stable: Boolean = s.orderBy.isEmpty && s.limit.isEmpty

    /** Over present members only: a hidden member's key moving emits nothing. A
      * cut member is absent, like one whose clauses did not match.
      */
    def arrange(
        members: Vector[Member],
        states: Map[String, EntityState]
    ): Vector[Member] = {
      val ordered =
        if (s.orderBy.isEmpty) members
        else
          // Stable over candidate order: the tiebreak that stops ties
          // reshuffling on every tick.
          members.sortWith((a, b) =>
            MemberGraph.precedes(s.orderBy, entityOf(a), entityOf(b), states)
          )
      s.limit.fold(ordered)(ordered.take)
    }

    private def entityOf(m: Member): String = sortKey(m.key)
  }

  /** From the key, never the position: a positional id would rename every node
    * after an arrival. Spelled by [[LayoutNode.memberSegment]].
    */
  private def memberId(setId: SetId, key: MemberKey): MemberId =
    MemberId.of(
      NodeId.derived(LayoutNode.memberSegment(setId, sortKey(key)))
    )

  def memberIdOf(setId: SetId, entityId: String): MemberId =
    memberId(setId, MemberKey.Entity(entityId))

  private def member(
      gid: SetId,
      entityId: String,
      node: LayoutNode.Component,
      clause: Int
  ): Member = {
    val key = MemberKey.Entity(entityId)
    Member(
      gid,
      sourceRoot.getOrElse(gid, ""),
      key,
      memberId(gid, key),
      node,
      clause
    )
  }

  /** `<member>_<clause>_<child path>`, all static. Read by both [[sources]] and
    * `Renderer.resolveChild`; two spellings would sync the graph and never
    * patch the browser.
    */
  def innerSetId(
      member: MemberId,
      clauseIdx: Int,
      path: List[LayoutNode.Step],
      set: LayoutNode.SetNode
  ): SetId =
    SetId.of(
      NodeId.derived(
        s"${member}_${clauseIdx}_${LayoutNode.segments(path)}"
      ),
      set
    )

  /** Every set, nested ones ("a tile per room") included with the member they
    * hang off. Enumerable up front because candidates are static, which makes
    * an inner set an ordinary container whose members patch themselves.
    */
  private val sourcesWithOwner: List[(NodeId, MemberSource, Option[NodeId])] = {
    def nested(
        gid: SetId,
        s: LayoutNode.SetNode
    ): List[(NodeId, MemberSource, Option[NodeId])] =
      for {
        candidate <- s.candidates
        (clause, ci) <- s.members
          .get(candidate)
          .toList
          .flatMap(_.clauses)
          .zipWithIndex
        found <- setsIn(
          memberId(gid, MemberKey.Entity(candidate)),
          ci,
          clause.node,
          Nil
        )
      } yield found

    def setsIn(
        member: MemberId,
        clauseIdx: Int,
        node: LayoutNode,
        path: List[LayoutNode.Step]
    ): List[(NodeId, MemberSource, Option[NodeId])] = node match {
      case c: LayoutNode.Component =>
        LayoutNode.steps(c.regions).flatMap { case (step, child) =>
          setsIn(member, clauseIdx, child, path :+ step)
        }
      case inner: LayoutNode.SetNode =>
        val id = innerSetId(member, clauseIdx, path, inner)
        (id, MemberSource(inner), Some(NodeId.derived(member))) ::
          nested(id, inner)
    }

    val roots = setNodes.toList.map { case (id, s) =>
      (id, MemberSource(s), None)
    }
    roots ++ roots.flatMap { case (gid, src, _) =>
      nested(src.setId(gid), src.s)
    }
  }

  private val sources: Map[NodeId, MemberSource] =
    sourcesWithOwner.map { case (id, src, _) => id -> src }.toMap

  /** The parent edges the static index cannot see, for [[NodeAncestry]].
    * Static: presence varies, the id space does not (ADR 0003).
    */
  def parentEdges: Map[NodeId, NodeId] =
    memberOwner.view.mapValues(g => NodeId.derived(g)).toMap ++
      sourcesWithOwner.collect { case (id, _, Some(owner)) => id -> owner }

  private val sourceRoot: Map[NodeId, String] =
    sources.keys.map { gid =>
      gid -> rootOfIndexed.getOrElse(
        gid,
        // A nested set: the longest indexed id prefix is its owner.
        rootOfIndexed.keys
          .filter(id => gid.startsWith(id + "_"))
          .toList
          .sortBy(-_.length)
          .headOption
          .flatMap(rootOfIndexed.get)
          .getOrElse("")
      )
    }.toMap

  /** Exact, so an id is never parsed for its parent: a prefix test cannot tell
    * `c_1_light_a_b` (set `c_1`, `light.a_b`) from a member of set
    * `c_1_light_a`.
    */
  private val memberOwner: Map[NodeId, SetId] =
    sources.toList.flatMap { case (at, src) =>
      val gid = src.setId(at)
      src.candidates.map(e => memberId(gid, MemberKey.Entity(e)) -> gid)
    }.toMap

  /** Mutated in place because three things key on the renderer's identity — the
    * log rotation, the reload repaint and [[RenderCache]] — and a new renderer
    * per membership change would trigger all three. Dies with the renderer, so
    * it needs no invalidation.
    */
  private val index =
    new java.util.concurrent.atomic.AtomicReference(
      MemberIndex(Map.empty, Map.empty, Map.empty)
    )

  /** The only way to get a [[SetId]] for an arbitrary id. Off [[sources]], not
    * the static index, which lacks nested sets — that was silent when wrong.
    */
  def setContainer(id: NodeId): Option[SetId] =
    sources.get(id).map(_.setId(id))

  /** From the graph, or derived from `states` before the stream reaches the
    * set. '''A reader never installs what it derived''': a page rendering
    * mid-frame would install that frame's result as its own "before", and a
    * client behind would never hear of the arrival.
    */
  def membersOf(
      gid: SetId,
      states: Map[String, EntityState]
  ): Vector[Member] = groupOf(gid, states).members

  def memberEntities(
      gid: SetId,
      states: Map[String, EntityState]
  ): List[String] = groupOf(gid, states).entities

  private def groupOf(
      gid: SetId,
      states: Map[String, EntityState]
  ): GroupMembers =
    sources.get(gid) match {
      case None      => GroupMembers(Vector.empty, Nil)
      case Some(src) =>
        index.get.byGroup.getOrElse(gid, materialise(gid, src, states))
    }

  private def materialise(
      gid: SetId,
      src: MemberSource,
      states: Map[String, EntityState]
  ): GroupMembers =
    GroupMembers.of(
      src.arrange(
        src.candidates.flatMap(src.memberOf(gid, _, states)),
        states
      )
    )

  /** Apply one frame to every set, visible or not: the next page renders from
    * the graph. Only changed entities are looked at, and a frame that only
    * ticks members hands each set's value straight back (277 µs a frame on a 2
    * 000-entity house, against a 3.4 ms rescan).
    */
  def syncMembers(
      changes: List[StateChange],
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): Map[SetId, MemberDelta] =
    sources.map { case (at, src) =>
      val gid = src.setId(at)
      val was = groupOf(gid, before)
      val touched = changes.iterator.flatMap(src.affected).distinct.toList
      val (now, replaced) =
        // With a live ordering or a limit, one entity moves its neighbours, so
        // rebuild — O(candidates), only for a touched set.
        if (touched.isEmpty) (was, Set.empty[MemberId])
        else if (!src.stable) {
          val rebuilt = materialise(gid, src, states)
          // As `applyOne` reports: a clause binding no live entity has no index
          // edge, so nothing else would name it.
          val swapped = rebuilt.members.iterator
            .filter(m =>
              was.members.exists(w => w.key == m.key && w.node != m.node)
            )
            .map(_.id)
            .toSet
          // The old value when unchanged, so `install` skips on `eq`.
          if (rebuilt.members == was.members) (was, swapped)
          else (rebuilt, swapped)
        } else
          touched.foldLeft((was, Set.empty[MemberId])) {
            case ((group, swapped), entityId) =>
              applyOne(gid, src, group, swapped, entityId, states)
          }
      val _ = index.updateAndGet(_.install(gid, was, now))
      gid -> MemberDelta(was.entities, now.entities, replaced)
    }

  /** A clause switch is reported by id, not left to the reverse index: a new
    * clause binding no live entity has no edges, and its bytes would move
    * unrecorded.
    */
  private def applyOne(
      gid: SetId,
      src: MemberSource,
      group: GroupMembers,
      replaced: Set[MemberId],
      entityId: String,
      states: Map[String, EntityState]
  ): (GroupMembers, Set[MemberId]) = {
    val key = MemberKey.Entity(entityId)
    val existing = group.members.find(_.key == key)
    val arriving = src.memberOf(gid, entityId, states)
    if (existing.map(_.node) == arriving.map(_.node)) (group, replaced)
    else {
      val without = group.members.filterNot(_.key == key)
      (
        GroupMembers.of(arriving.fold(without)(insertOrdered(src, without, _))),
        // Present before and after; arrivals and departures are mutations.
        if (existing.isDefined && arriving.isDefined)
          replaced ++ arriving.map(_.id)
        else replaced
      )
    }
  }

  /** Where a full materialisation would put it. */
  private def insertOrdered(
      src: MemberSource,
      members: Vector[Member],
      arriving: Member
  ): Vector[Member] = {
    val ord = Ordering[(Int, String)]
    def at(m: Member) = src.ordinal(sortKey(m.key))
    val i = members.indexWhere(m => ord.gt(at(m), at(arriving)))
    if (i < 0) members :+ arriving else members.patch(i, List(arriving), 0)
  }

  private def sortKey(key: MemberKey): String = key match {
    case MemberKey.Entity(id)  => id
    case MemberKey.Surface(id) => id
  }

  /** Derives the set only before the recorder has synced it. */
  def memberAt(
      id: NodeId,
      states: Map[String, EntityState]
  ): Option[Member] =
    index.get.byId
      .get(id)
      .orElse(
        memberOwner
          .get(id)
          .flatMap(gid => membersOf(gid, states).find(_.id == id))
      )

  def rootOfMember(id: NodeId): Option[String] =
    index.get.byId.get(id).map(_.root)

  /** Without this a nested set's fill or removal reads as main-page and reaches
    * clients without the surface open.
    */
  def rootOfSet(id: NodeId): Option[String] = sourceRoot.get(id)

  def membersBinding(entityId: String, root: String): Set[NodeId] =
    index.get.byEntity
      .getOrElse(entityId, Vector.empty)
      .collect { case m if m.root == root => m.id }
      .toSet

  def liveEntitiesOf(id: NodeId): List[String] =
    entitiesOf(id)(_.liveEntities)

  /** See [[fh.view.model.LayoutNode.Component.liveEntitiesAsBytes]]. */
  def liveEntitiesAsBytesOf(id: NodeId): List[String] =
    entitiesOf(id)(_.liveEntitiesAsBytes)

  private def entitiesOf(
      id: NodeId
  )(read: LayoutNode.Component => List[String]): List[String] =
    index.get.byId.get(id).toList.flatMap(m => read(m.node))

  /** Membership only; a member that ticked is found by the reverse index. */
  def affectedSets(changes: List[StateChange]): List[SetId] =
    containersIn("", changes)

  def affectedSurfaceSets(
      surfaceId: String,
      changes: List[StateChange]
  ): List[SetId] = containersIn(surfaceId, changes)

  // Off [[sources]], for the reason [[setContainer]] gives.
  private def containersIn(
      root: String,
      changes: List[StateChange]
  ): List[SetId] =
    sources.iterator
      .collect {
        case (at, src)
            if sourceRoot.getOrElse(at, "") == root &&
              changes.exists(src.affected(_).nonEmpty) =>
          src.setId(at)
      }
      .toList
      .sortBy(id => id: String)
}

private[runtime] object MemberGraph {

  /** `false` for equal, so the caller's stable sort keeps them in place. */
  def precedes(
      terms: List[LayoutNode.SortTerm],
      a: String,
      b: String,
      states: Map[String, EntityState]
  ): Boolean =
    terms.iterator
      .map(t => compareOn(t, a, b, states))
      .find(_ != 0)
      .exists(_ < 0)

  private[runtime] def compareOn(
      term: LayoutNode.SortTerm,
      a: String,
      b: String,
      states: Map[String, EntityState]
  ): Int = {
    val raw = term.by match {
      case LayoutNode.SortKey.Holds(p) =>
        def holds(id: String) =
          states.get(id).exists(st => Conditions.matchesIn(p, st, states))
        java.lang.Boolean.compare(holds(b), holds(a))
      case LayoutNode.SortKey.Prop(property) =>
        def read(id: String) =
          states.get(id).fold("")(Conditions.propertyOf(property, _))
        val (l, r) = (read(a), read(b))
        // Numeric when both are numbers, so 2 < 10.
        (l.toDoubleOption, r.toDoubleOption) match {
          case (Some(x), Some(y)) => java.lang.Double.compare(x, y)
          case _                  => l.compareTo(r)
        }
    }
    if (term.descending) -raw else raw
  }
}
