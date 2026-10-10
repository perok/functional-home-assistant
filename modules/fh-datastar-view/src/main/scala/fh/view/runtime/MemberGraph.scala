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
private[runtime] final case class GroupMembers(
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

/** Every set's members as of the last frame the recorder applied, plus the
  * entity index over them. The recorder folds it over the state stream
  * (`Server.publisherFor`), which makes it the "before" of the next frame's
  * deltas. Readers never see it: they derive membership from the snapshot they
  * render ([[MemberGraph.membersOf]]), so a page read between a store bump and
  * that frame cannot pair new states with old members.
  */
private[runtime] final case class Membership(
    byGroup: Map[SetId, GroupMembers],
    byEntity: Map[String, Vector[Member]]
) {

  /** Members binding `entityId` under `root`: a member re-renders because
    * something it binds moved, as a static node does.
    */
  def binding(entityId: String, root: String): Set[NodeId] =
    byEntity
      .getOrElse(entityId, Vector.empty)
      .collect { case m if m.root == root => m.id }
      .toSet

  /** Skips on `eq`: [[MemberGraph.syncMembers]] hands back the same value when
    * nothing moved.
    */
  def install(gid: SetId, was: GroupMembers, now: GroupMembers): Membership =
    if ((now eq was) && byGroup.contains(gid)) this
    else {
      val leaving = byGroup.get(gid).toVector.flatMap(_.members)
      Membership(
        byGroup.updated(gid, now),
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

private[runtime] object Membership {
  val empty: Membership = Membership(Map.empty, Map.empty)
}

/** One frame applied: the membership after it, and each set's move. */
private[runtime] final case class Synced(
    membership: Membership,
    deltas: Map[SetId, MemberDelta]
)

/** Who is in every candidate set, and in what order: the graph decides presence
  * and order, the renderer paints (ADR 0003). Holds no state: presence is a
  * function of a snapshot, and the recorder's running [[Membership]] is a value
  * it passes in.
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
    val movedBy: Map[String, List[String]] =
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

  /** `owner`: the member a nested set hangs off. `root`: `""` for the main
    * page, else the surface its patches may reach.
    *
    * Not a case class: a frame dedups these, and structural hashing walks the
    * whole `SetNode` (`publishSet` 11 µs -> 1.4 ms).
    */
  private final class Source(
      val at: NodeId,
      val src: MemberSource,
      val owner: Option[NodeId],
      val root: String
  ) {
    val gid: SetId = src.setId(at)

    /** Every member each candidate can be, one per clause, built once: a
      * snapshot only picks which. `None` for a bare set clause, which has no
      * rendering to be; a set nested inside a component clause is the supported
      * shape.
      */
    private val clauses
        : Map[String, List[(Option[Predicate], Option[Member])]] =
      src.s.members.map { case (e, m) =>
        e -> m.clauses.zipWithIndex.map { case (c, i) =>
          c.when -> (c.node match {
            case comp: LayoutNode.Component => Some(member(this, e, comp, i))
            case _                          => None
          })
        }
      }

    /** An entity HA does not know is evaluated against an empty state, so a
      * clause guarded only on another entity still decides.
      */
    def memberOf(
        entityId: String,
        states: Map[String, EntityState]
    ): Option[Member] = {
      val subject =
        states.getOrElse(entityId, EntityState(entityId, "", Map.empty))
      clauses
        .get(entityId)
        .flatMap(
          _.find(_._1.forall(Conditions.matchesIn(_, subject, states)))
        )
        .flatMap(_._2)
    }

    def materialise(states: Map[String, EntityState]): GroupMembers =
      GroupMembers.of(
        src.arrange(src.candidates.flatMap(memberOf(_, states)), states)
      )
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

  // Runs while the graph is built, so it reads the source, not the maps.
  private def member(
      s: Source,
      entityId: String,
      node: LayoutNode.Component,
      clause: Int
  ): Member = {
    val key = MemberKey.Entity(entityId)
    Member(s.gid, s.root, key, memberId(s.gid, key), node, clause)
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
  private val sourcesWithOwner: List[Source] = {
    def nested(outer: Source): List[Source] =
      for {
        candidate <- outer.src.s.candidates
        (clause, ci) <- outer.src.s.members
          .get(candidate)
          .toList
          .flatMap(_.clauses)
          .zipWithIndex
        found <- setsIn(
          memberId(outer.gid, MemberKey.Entity(candidate)),
          ci,
          clause.node,
          Nil,
          outer.root
        )
      } yield found

    def setsIn(
        member: MemberId,
        clauseIdx: Int,
        node: LayoutNode,
        path: List[LayoutNode.Step],
        root: String
    ): List[Source] = node match {
      case c: LayoutNode.Component =>
        LayoutNode.steps(c.regions).flatMap { case (step, child) =>
          setsIn(member, clauseIdx, child, path :+ step, root)
        }
      case inner: LayoutNode.SetNode =>
        val id = innerSetId(member, clauseIdx, path, inner)
        val found =
          Source(id, MemberSource(inner), Some(NodeId.derived(member)), root)
        found :: nested(found)
    }

    val roots = setNodes.toList.map { case (id, s) =>
      Source(id, MemberSource(s), None, rootOfIndexed.getOrElse(id, ""))
    }
    roots ++ roots.flatMap(nested)
  }

  // Keyed by the id the set is found at, which is also its `SetId`.
  private val sources: Map[NodeId, Source] =
    sourcesWithOwner.map(s => s.at -> s).toMap

  /** The parent edges the static index cannot see, for [[NodeAncestry]].
    * Static: presence varies, the id space does not (ADR 0003).
    */
  def parentEdges: Map[NodeId, NodeId] =
    memberSlot.view.mapValues { case (s, _) => NodeId.derived(s.gid) }.toMap ++
      sourcesWithOwner.flatMap(s => s.owner.map(s.at -> _))

  // A nested set inherits its owner's, which the walk carries down.
  private val sourceRoot: Map[NodeId, String] =
    sourcesWithOwner.map(s => s.at -> s.root).toMap

  /** entity -> every set's candidates it can decide, so a frame visits only the
    * sets it touched.
    */
  private val movedBy: Map[String, List[(Source, String)]] =
    sourcesWithOwner
      .flatMap(s =>
        s.src.movedBy.toList.flatMap { case (e, cids) => cids.map(e -> (s, _)) }
      )
      .groupMap(_._1)(_._2)

  /** member id -> its set and candidate. Exact, so an id is never parsed for
    * its parent: a prefix test cannot tell `c_1_light_a_b` (set `c_1`,
    * `light.a_b`) from a member of set `c_1_light_a`.
    */
  private val memberSlot: Map[NodeId, (Source, String)] =
    sourcesWithOwner.flatMap { s =>
      s.src.candidates.map(e => memberId(s.gid, MemberKey.Entity(e)) -> (s, e))
    }.toMap

  /** Every member id the candidates allow, present or not: the id space is
    * static (ADR 0003).
    */
  def memberIds: Iterable[NodeId] = memberSlot.keys

  /** What a member may render, one node per clause. */
  def clauseNodesOf(member: NodeId): List[LayoutNode] =
    memberSlot.get(member).toList.flatMap { case (s, e) =>
      s.src.s.members.get(e).toList.flatMap(_.clauses).map(_.node)
    }

  /** The only way to get a [[SetId]] for an arbitrary id. Off [[sources]], not
    * the static index, which lacks nested sets — that was silent when wrong.
    */
  def setContainer(id: NodeId): Option[SetId] = sources.get(id).map(_.gid)

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
    sources
      .get(gid)
      .fold(GroupMembers(Vector.empty, Nil))(_.materialise(states))

  /** Only a set with a `limit` needs its neighbours: order alone never decides
    * presence.
    */
  def memberAt(
      id: NodeId,
      states: Map[String, EntityState]
  ): Option[Member] =
    memberSlot.get(id).flatMap { case (s, entityId) =>
      if (s.src.s.limit.isEmpty) s.memberOf(entityId, states)
      else s.materialise(states).members.find(_.id == id)
    }

  /** Apply one frame to every set, visible or not: the recorder holds the
    * result as the next frame's "before". Only changed entities are looked at,
    * and a frame that only ticks members hands each set's value straight back
    * (277 µs a frame on a 2 000-entity house, against a 3.4 ms rescan).
    */
  def syncMembers(
      held: Membership,
      changes: List[StateChange],
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): Synced = {
    val touchedIn = changes
      .flatMap(c => movedBy.getOrElse(c.entityId, Nil))
      .distinct
      .groupMap(_._1.at)(_._2)
    sourcesWithOwner.foldLeft(Synced(held, Map.empty)) { (acc, s) =>
      val src = s.src
      val gid = s.gid
      // A set the recorder has not held yet starts from the frame's "before".
      val was =
        acc.membership.byGroup.getOrElse(gid, s.materialise(before))
      val touched = touchedIn.getOrElse(s.at, Nil)
      val (now, replaced) =
        // With a live ordering or a limit, one entity moves its neighbours, so
        // rebuild — O(candidates), only for a touched set.
        if (touched.isEmpty) (was, Set.empty[MemberId])
        else if (!src.stable) {
          val rebuilt = s.materialise(states)
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
              applyOne(s, group, swapped, entityId, states)
          }
      Synced(
        acc.membership.install(gid, was, now),
        acc.deltas
          .updated(gid, MemberDelta(was.entities, now.entities, replaced))
      )
    }
  }

  /** A clause switch is reported by id, not left to the reverse index: a new
    * clause binding no live entity has no edges, and its bytes would move
    * unrecorded.
    */
  private def applyOne(
      s: Source,
      group: GroupMembers,
      replaced: Set[MemberId],
      entityId: String,
      states: Map[String, EntityState]
  ): (GroupMembers, Set[MemberId]) = {
    val key = MemberKey.Entity(entityId)
    val existing = group.members.find(_.key == key)
    val arriving = s.memberOf(entityId, states)
    if (existing.map(_.node) == arriving.map(_.node)) (group, replaced)
    else {
      val without = group.members.filterNot(_.key == key)
      (
        GroupMembers.of(
          arriving.fold(without)(insertOrdered(s.src, without, _))
        ),
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

  /** Present or not: a departed member's patch is scoped like a present one's.
    */
  def rootOfMember(id: NodeId): Option[String] =
    memberSlot.get(id).map(_._1.root)

  /** Without this a nested set's fill or removal reads as main-page and reaches
    * clients without the surface open.
    */
  def rootOfSet(id: NodeId): Option[String] = sourceRoot.get(id)

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
    changes
      .flatMap(c => movedBy.getOrElse(c.entityId, Nil))
      .collect { case (s, _) if s.root == root => s.gid }
      .distinct
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
