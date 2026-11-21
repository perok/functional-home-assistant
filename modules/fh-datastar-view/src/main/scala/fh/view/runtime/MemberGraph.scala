package fh.view.runtime

import fh.view.model.{
  Fetching,
  IdKind,
  LayoutNode,
  MemberId,
  NodeId,
  Ready,
  SetId,
  Ix,
  SlotRead,
  TreeIndex
}

/** A candidate set member materialised as an ordinary node. Its node is
  * state-derived (which clause matched), so it is replaced when the entity
  * crosses a clause boundary ([[MemberGraph.deltas]]).
  */
private[runtime] object Member {

  /** The whole subtree's entities, the set membership index's edges. A nested
    * set's members patch themselves, so its root has none of theirs: descending
    * would wake the whole tile on any bulb inside it.
    */
  def entitiesOf(node: Ready.Node): List[String] = node match {
    case c: Ready.Component =>
      (c.liveEntities ++ c.allChildren.flatMap(entitiesOf)).distinct
  }

  /** A member's components, each with its parent. Not a nested set's root, nor
    * its members, which are its own.
    */
  def subtree(
      node: Ready.Component
  ): List[(NodeId, Ready.Component, Option[NodeId])] = {
    def walk(
        n: Ready.Component,
        parent: Option[NodeId]
    ): List[(NodeId, Ready.Component, Option[NodeId])] =
      (n.id, n, parent) :: n.allChildren.flatMap { case k: Ready.Component =>
        k.content match
          case Ready.Content.Live(_, _: Ready.LiveFill.Set) => Nil
          case _ => walk(k, Some(n.id))
      }
    walk(node, None)
  }
}

private[runtime] case class Member(
    gid: SetId,
    key: MemberKey,
    id: MemberId,
    node: Ready.Component,
    // Part of a nested set's id, so two clauses holding sets cannot share one.
    clause: Int
) {

  /** Its own node and every component below it, by id. */
  lazy val nodes: Map[NodeId, Ready.Component] =
    Member.subtree(node).map((at, n, _) => at -> n).toMap

  /** What its nodes read, each under its own id, in id order. */
  lazy val fetching: List[(NodeId, Fetching)] =
    nodes.toList.sortBy(_._1).flatMap((at, n) => n.fetching.map(at -> _))

  /** entity -> the nodes here binding it: what a tick on it re-renders. */
  lazy val byEntity: Map[String, List[Ready.Component]] =
    nodes.values.toList
      .flatMap(n => n.liveEntities.map(_ -> n))
      .groupMap(_._1)(_._2)
}

/** `replaced`: members present before and after whose clause moved, as they are
  * now.
  */
private[runtime] case class MemberDelta(
    was: List[String],
    now: List[String],
    replaced: Vector[Member]
)

/** Cached with its entity projection, so a frame that only ticks members costs
  * the frame, not the set.
  */
private[runtime] final case class GroupMembers(
    members: Vector[Member],
    entities: List[String]
) {

  /** entity -> the members binding it, built once per decision. Scanning the
    * members per change instead cost a set's frame 134 µs against 17
    * (`RenderBench.publishSet`).
    */
  lazy val binding: Map[String, Vector[Member]] =
    members
      .flatMap(m => m.byEntity.keysIterator.map(_ -> m))
      .groupMap(_._1)(_._2)
}

private object GroupMembers {
  val empty: GroupMembers = GroupMembers(Vector.empty, Nil)

  def of(members: Vector[Member]): GroupMembers =
    GroupMembers(
      members,
      members.toList.collect { case Member(_, MemberKey.Entity(e), _, _, _) =>
        e
      }
    )
}

/** Who is in every candidate set, and in what order: the graph decides presence
  * and order, the renderer paints (ADR 0003). Presence is a function of a
  * version, kept in its [[Shared]], which the recorder and every render read.
  *
  * @param setNodes
  *   the statically indexed sets; nested ones are discovered from these.
  * @param index
  *   where each set's host sits: its container is who a patch may reach.
  */
private[runtime] final class MemberGraph(
    setNodes: Map[NodeId, Ready.SetFill],
    index: TreeIndex
) {

  private case class MemberSource(s: Ready.SetFill) {

    private val position: Map[String, Int] = s.candidates.zipWithIndex.toMap

    /** What its guards and order read: the set's decision and each member's.
      */
    val reads: Set[SlotRead] =
      s.members.values.flatMap(_.guard).flatMap(_.reads).toSet
        ++ s.order.fold(Set.empty[SlotRead])(_.reads)

    /** entity -> the candidates it can decide, so a frame costs its changes,
      * not the candidate list.
      */
    val movedBy: Map[String, List[String]] =
      s.candidates
        .flatMap { cid =>
          val named = s.members
            .get(cid)
            .toList
            .flatMap(_.guard)
            .flatMap(_.entities)
          // An area or a floor has no state, so only its guard moves it.
          val own = if (s.of == IdKind.Entity) List(cid) else Nil
          (own ++ named).distinct.map(_ -> cid)
        }
        .groupMap(_._1)(_._2)

    val candidates: Vector[String] = s.candidates.toVector

    /** What dirties the ordering as a whole, beyond each member's own. */
    val orderEntities: List[String] = s.order.toList.flatMap(_.entities)

    def setId(at: NodeId): SetId = SetId.of(at, s)

    def ordinal(entityId: String): (Int, String) =
      (position.getOrElse(entityId, Int.MaxValue), entityId)

    def stable: Boolean = s.order.isEmpty && s.limit.isEmpty

    /** Over present members only: a hidden member's key moving emits nothing. A
      * cut member is absent, like one whose clauses did not match.
      */
    def arrange(
        members: Vector[Member],
        states: Map[String, EntityState],
        fetched: Shared.Fetched
    ): Vector[Member] = {
      val ordered = s.order match
        case None       => members
        case Some(keys) =>
          // Stable over candidate order: the tiebreak that stops ties
          // reshuffling on every tick.
          members
            .map(m => m -> keys.of(entityOf(m), states, fetched))
            .sortWith((a, b) => keys.compare(a._2, b._2) < 0)
            .map(_._1)
      s.limit.fold(ordered)(ordered.take)
    }

    private def entityOf(m: Member): String = sortKey(m.key)
  }

  /** `owner`: the member a nested set hangs off. `root`: its host's container,
    * which its patches may reach only while shown.
    *
    * Not a case class: a frame dedups these, and structural hashing walks the
    * whole `SetFill` (`publishSet` 11 µs -> 1.4 ms).
    */
  private final class Source(
      val at: NodeId,
      val src: MemberSource,
      val owner: Option[NodeId],
      val root: Ix,
      // The owner's clause it is inside; 0 for a set no member owns.
      val clause: Int = 0
  ) {
    val gid: SetId = src.setId(at)

    /** Every member each candidate can be, one per case, built once: a snapshot
      * only picks which.
      */
    private val cases: Map[String, (Ready.MemberChoice, Vector[Member])] =
      src.s.members.map { case (e, m) =>
        e -> (m -> m.nodes.zipWithIndex.map { (node, i) =>
          member(this, e, node, i)
        })
      }

    /** The id `entityId`'s member has in every clause. */
    def idOf(entityId: String): MemberId = cases(entityId)._2.head.id

    /** Every member `entityId` can be, one per case. */
    def clauseMembers(entityId: String): List[Member] =
      cases.get(entityId).toList.flatMap(_._2)

    /** An entity HA does not know is evaluated against an empty state, so a
      * guard on another entity alone still decides.
      */
    def memberOf(
        entityId: String,
        states: Map[String, EntityState],
        fetched: Shared.Fetched
    ): Option[Member] = {
      val subject = states.getOrElse(entityId, null)
      cases.get(entityId).flatMap { (m, members) =>
        val picked =
          m.guard.fold(0)(g => m.pick(g.run(subject, states, fetched)))
        Option.when(picked >= 0)(members(picked))
      }
    }

    def materialise(
        states: Map[String, EntityState],
        fetched: Shared.Fetched
    ): GroupMembers =
      GroupMembers.of(
        src.arrange(
          src.candidates.flatMap(memberOf(_, states, fetched)),
          states,
          fetched
        )
      )
  }

  /** Its node's: from the candidate, never the position, since a positional id
    * would rename every node after an arrival ([[LayoutNode.memberSegment]]).
    */
  def memberIdOf(setId: SetId, entityId: String): MemberId =
    sources(setId).idOf(entityId)

  // Runs while the graph is built, so it reads the source, not the maps.
  private def member(
      s: Source,
      entityId: String,
      node: Ready.Component,
      clause: Int
  ): Member = {
    val key = MemberKey.Entity(entityId)
    Member(s.gid, key, MemberId.of(node.id), node, clause)
  }

  /** Every set, nested ones ("a tile per room") included with the member they
    * hang off. Enumerable up front because candidates are static, which makes
    * an inner set an ordinary container whose members patch themselves.
    */
  private val sourcesWithOwner: List[Source] = {
    def nested(outer: Source): List[Source] =
      for {
        candidate <- outer.src.s.candidates
        (node, ci) <- outer.src.s.members
          .get(candidate)
          .toList
          .flatMap(_.nodes.zipWithIndex)
        found <- setsIn(MemberId.of(node.id), ci, node)
      } yield found

    def setsIn(
        member: MemberId,
        clauseIdx: Int,
        node: Ready.Node
    ): List[Source] = node match {
      case c: Ready.Component =>
        c.content match {
          case Ready.Content.Live(_, Ready.LiveFill.Set(inner)) =>
            val found = Source(
              c.id,
              MemberSource(inner),
              Some(NodeId.derived(member)),
              index.container(c.ix),
              clauseIdx
            )
            found :: nested(found)
          case Ready.Content.Live(_, Ready.LiveFill.Pick(cases, _, _, _, _)) =>
            cases.flatMap(setsIn(member, clauseIdx, _))
          case Ready.Content.Fixed(r) =>
            LayoutNode.steps(r).flatMap((_, k) => setsIn(member, clauseIdx, k))
        }
    }

    // A static host is one node.
    val roots = setNodes.toList.map { case (id, s) =>
      Source(id, MemberSource(s), None, index.container(index.ixsOf(id).head))
    }
    roots ++ roots.flatMap(nested)
  }

  // Keyed by the id the set is found at, which is also its `SetId`.
  private val sources: Map[NodeId, Source] =
    sourcesWithOwner.map(s => s.at -> s).toMap

  private val setsByClause: Map[(NodeId, Int), List[SetId]] =
    sourcesWithOwner
      .flatMap(s => s.owner.map(o => (o, s.clause) -> s.gid))
      .groupMap(_._1)(_._2)

  /** The sets directly inside a member, in the clause it is in. */
  def setsIn(m: Member): List[SetId] =
    setsByClause.getOrElse((m.id, m.clause), Nil)

  /** The parent edges the static index cannot see, for [[NodeAncestry]].
    * Static: presence varies, the id space does not (ADR 0003). Every clause of
    * a candidate mints the same ids below it, so their edges agree.
    */
  def parentEdges: Map[NodeId, NodeId] =
    memberSlot.view.mapValues { case (s, _) => NodeId.derived(s.gid) }.toMap ++
      sourcesWithOwner.flatMap(s => s.owner.map(s.at -> _)) ++
      memberSlot.toList.flatMap { case (_, (s, e)) =>
        s.clauseMembers(e)
          .flatMap(m => Member.subtree(m.node))
          .flatMap((at, _, parent) => parent.map(at -> _))
      }

  /** id below a member -> the member. Each member indexes its own subtree
    * ([[Member.nodes]]); this only says whose an id is.
    */
  private lazy val ownerBelow: Map[NodeId, NodeId] =
    memberSlot.toList.flatMap { case (mid, (s, e)) =>
      s.clauseMembers(e).flatMap(_.nodes.keys).filterNot(_ == mid).map(_ -> mid)
    }.toMap

  /** The member a node belongs to: itself for a member, its member for a node
    * below one.
    */
  def memberOwning(id: NodeId): Option[NodeId] =
    if (memberSlot.contains(id)) Some(id) else ownerBelow.get(id)

  /** A member or a node below one, as its member's current clause has it. */
  def nodeAt(
      id: NodeId,
      shared: Shared
  ): Option[(Member, Ready.Component)] =
    memberOwning(id)
      .flatMap(memberAt(_, shared))
      .flatMap(m => m.nodes.get(id).map(m -> _))

  /** Every id below a member, every clause's. */
  def memberIdsBelow: Iterable[NodeId] = ownerBelow.keys

  /** What `id` can be in each clause, presence aside: a node's static reads. */
  def clauseNodesAt(id: NodeId): List[Ready.Component] =
    memberOwning(id).flatMap(memberSlot.get).toList.flatMap { (s, e) =>
      s.clauseMembers(e).flatMap(_.nodes.get(id))
    }

  /** entity -> every set's candidates it can decide, so a frame visits only the
    * sets it touched.
    */
  private val movedBy: Map[String, List[(Source, String)]] =
    sourcesWithOwner
      .flatMap(s =>
        s.src.movedBy.toList.flatMap { case (e, cids) => cids.map(e -> (s, _)) }
      )
      .groupMap(_._1)(_._2)

  /** entity -> the sets whose decision it dirties: a candidate, a name a guard
    * reads, a name an order term reads.
    */
  private val dirtiesSets: Map[String, List[Source]] =
    sourcesWithOwner
      .flatMap(s => (s.src.movedBy.keys ++ s.src.orderEntities).map(_ -> s))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.distinct)
      .toMap

  /** entity -> the member decisions it dirties: its own candidates', and under
    * a `limit` every member's, since one arrival can cut another.
    */
  private val dirtiesMembers: Map[String, Set[NodeId]] = {
    def all(s: Source): List[NodeId] =
      s.src.candidates.toList.map(s.idOf)
    sourcesWithOwner
      .flatMap { s =>
        val limited = s.src.s.limit.isDefined
        s.src.movedBy.toList.flatMap { (e, cids) =>
          (if (limited) all(s)
           else cids.map(s.idOf))
            .map(e -> _)
        } ++ (if (limited) s.src.orderEntities.flatMap(e => all(s).map(e -> _))
              else Nil)
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap
  }

  /** The set and member decisions `changes` dirty. */
  def dirtied(changes: List[StateChange]): (Set[SetId], Set[NodeId]) =
    (
      changes.iterator
        .flatMap(c => dirtiesSets.getOrElse(c.entityId, Nil))
        .map(_.gid)
        .toSet,
      changes.iterator
        .flatMap(c => dirtiesMembers.getOrElse(c.entityId, Set.empty))
        .toSet
    )

  /** entity -> the sets whose members' nodes can read it, in any clause: where
    * [[binding]] looks, before presence narrows it.
    */
  private val readingSets: Map[String, List[Source]] =
    sourcesWithOwner
      .flatMap(s =>
        s.src.candidates
          .flatMap(s.clauseMembers)
          .flatMap(m => Member.entitiesOf(m.node))
          .distinct
          .map(_ -> s)
      )
      .groupMap(_._1)(_._2)

  /** The nodes inside present members under a root `within` holds that bind
    * `entityId`: each re-renders because something it binds moved, as a static
    * node does. A nested set counts only while its owner is present, in the
    * clause holding it, and a node in a case below the member only while
    * `within` holds that case.
    */
  def binding(
      entityId: String,
      within: Ix => Boolean,
      shared: Shared
  ): Set[NodeId] =
    readingSets
      .getOrElse(entityId, Nil)
      .iterator
      .filter(s => within(s.root) && ownerPresent(s, shared))
      .flatMap(s =>
        groupOf(s.gid, shared).binding
          .getOrElse(entityId, Vector.empty)
          .iterator
          .flatMap(_.byEntity.getOrElse(entityId, Nil))
          .collect {
            // Outside any case below its member, it is in the set's own.
            case n
                if index.container(n.ix) == s.root ||
                  within(index.container(n.ix)) =>
              n.id
          }
      )
      .toSet

  // Off the owner's set, which every walk decides, not `memberAt`: a member's
  // own decision is one the page may never have made, so it would ask again.
  private def ownerPresent(s: Source, shared: Shared): Boolean =
    s.owner.forall(o =>
      memberSlot
        .get(o)
        .exists((outer, _) =>
          groupOf(outer.gid, shared).members
            .exists(m => m.id == o && m.clause == s.clause)
        )
    )

  /** member id -> its set and candidate. Exact, so an id is never parsed for
    * its parent: a prefix test cannot tell `c_1_light_a_b` (set `c_1`,
    * `light.a_b`) from a member of set `c_1_light_a`.
    */
  private val memberSlot: Map[NodeId, (Source, String)] =
    sourcesWithOwner.flatMap { s =>
      s.src.candidates.map(e => (s.idOf(e): NodeId) -> (s, e))
    }.toMap

  /** Every member id the candidates allow, present or not: the id space is
    * static (ADR 0003).
    */
  def memberIds: Iterable[NodeId] = memberSlot.keys

  /** What a member may render, one node per clause. */
  def clauseNodesOf(member: NodeId): List[Ready.Node] =
    memberSlot.get(member).toList.flatMap { case (s, e) =>
      s.src.s.members.get(e).toList.flatMap(_.nodes)
    }

  /** The only way to get a [[SetId]] for an arbitrary id. Off [[sources]], not
    * the static index, which lacks nested sets — that was silent when wrong.
    */
  def setContainer(id: NodeId): Option[SetId] = sources.get(id).map(_.gid)

  def membersOf(gid: SetId, shared: Shared): Vector[Member] =
    groupOf(gid, shared).members

  def memberEntities(gid: SetId, shared: Shared): List[String] =
    groupOf(gid, shared).entities

  private def groupOf(gid: SetId, shared: Shared): GroupMembers =
    sources.get(gid) match
      case None    => GroupMembers.empty
      case Some(s) =>
        shared.set(gid, s.src.reads, GroupMembers.empty)(
          s.materialise(shared.states, _)
        )

  /** Only a set with a `limit` needs its neighbours: order alone never decides
    * presence.
    */
  def memberAt(id: NodeId, shared: Shared): Option[Member] =
    memberSlot.get(id).flatMap { case (s, entityId) =>
      shared.member(id, s.src.reads)(f =>
        if (s.src.s.limit.isEmpty) s.memberOf(entityId, shared.states, f)
        else groupOf(s.gid, shared).members.find(_.id == id)
      )
    }

  /** Each of `sets`' move from `prev` to `next`, which is what the recorder
    * logs. `was` is `prev`'s decision, made there if nobody had, so it is what
    * its viewers were sent. A stable set looks only at the changed entities, so
    * a frame that ticks one member costs that member (277 µs a frame on a 2
    * 000-entity house, against a 3.4 ms rescan); with a live ordering or a
    * limit one entity moves its neighbours, so it is rebuilt. That holds only
    * where this makes `next`'s decision, so it runs before anything else reads
    * `next`'s sets, outer before inner (`sets` sorted by id).
    *
    * A member arriving or leaving is placed whole, so a nested set moves only
    * under an owner present on both sides; one that is not is left out, and its
    * decision unasked.
    */
  def deltas(
      sets: List[SetId],
      changes: List[StateChange],
      prev: Shared,
      next: Shared
  ): Map[SetId, MemberDelta] = {
    val touchedIn = changes
      .flatMap(c => movedBy.getOrElse(c.entityId, Nil))
      .distinct
      .groupMap(_._1.at)(_._2)
    sets
      .flatMap(sources.get)
      // One pass, so an outer set's delta makes its `next` decision before
      // its inner sets' owner check reads it.
      .flatMap { s =>
        if (!(ownerPresent(s, prev) && ownerPresent(s, next))) None
        else Some(delta(s, touchedIn.getOrElse(s.at, Nil), prev, next))
      }
      .toMap
  }

  private def delta(
      s: Source,
      touched: List[String],
      prev: Shared,
      next: Shared
  ): (SetId, MemberDelta) = {
    val was = groupOf(s.gid, prev)
    var swapped = Option.empty[Vector[Member]]
    // `was` stands in only while `next` does: one run answers both.
    val now = next.set(s.gid, s.src.reads, GroupMembers.empty) { f =>
      if (s.src.stable) {
        val (group, sw) = touched
          .foldLeft((was, Vector.empty[Member])) { case ((g, w), entityId) =>
            applyOne(s, g, w, entityId, next.states, f)
          }
        swapped = Some(sw)
        group
      } else s.materialise(next.states, f)
    }
    s.gid -> MemberDelta(
      was.entities,
      now.entities,
      swapped.getOrElse(clauseMoved(was, now))
    )
  }

  /** As `applyOne` reports: a clause binding no live entity has no index edge,
    * so nothing else would name it.
    */
  private def clauseMoved(
      was: GroupMembers,
      now: GroupMembers
  ): Vector[Member] =
    val clauseOf = was.members.iterator.map(m => m.key -> m.clause).toMap
    now.members.filter(m => clauseOf.get(m.key).exists(_ != m.clause))

  /** A clause switch is reported by id, not left to the reverse index: a new
    * clause binding no live entity has no edges, and its bytes would move
    * unrecorded.
    */
  private def applyOne(
      s: Source,
      group: GroupMembers,
      replaced: Vector[Member],
      entityId: String,
      states: Map[String, EntityState],
      fetched: Shared.Fetched
  ): (GroupMembers, Vector[Member]) = {
    val key = MemberKey.Entity(entityId)
    val existing = group.members.find(_.key == key)
    val arriving = s.memberOf(entityId, states, fetched)
    // By clause, which fixes the node; not by node, since hoisting lifts a
    // slider's heads out of it and two slider clauses can leave equal nodes
    // over different surfaces.
    if (existing.map(_.clause) == arriving.map(_.clause)) (group, replaced)
    else {
      val without = group.members.filterNot(_.key == key)
      (
        GroupMembers.of(
          arriving.fold(without)(insertOrdered(s.src, without, _))
        ),
        // Present before and after; arrivals and departures are mutations.
        if (existing.isDefined && arriving.isDefined)
          replaced ++ arriving
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

  /** The sets under a root `within` holds whose membership a change can move; a
    * member that ticked is found by the reverse index. Off [[sources]], for the
    * reason [[setContainer]] gives.
    */
  def affectedSetsWithin(
      changes: List[StateChange],
      within: Ix => Boolean
  ): List[SetId] =
    changes
      .flatMap(c => dirtiesSets.getOrElse(c.entityId, Nil))
      .collect { case s if within(s.root) => s.gid }
      .distinct
      .sortBy(id => id: String)
}
