package fh.view.runtime

import fh.view.model.{LayoutNode, NodeId, Predicate}

/** One member of a candidate set, MATERIALISED into the node graph: a real
  * [[LayoutNode.Component]] under the id its [[MemberKey]] derives, carrying
  * the matched entity as a literal `entity_id` slot exactly as the clause
  * dispatch used to set it per render.
  *
  * The node is STATE-DERIVED — which clause matched, and so which card and
  * slots — so it is frozen at the moment membership was applied and must be
  * replaced when the matched entity moves across a clause boundary
  * ([[MemberGraph.syncMembers]]). Everything else about it is an ordinary node:
  * rendered by `Renderer.renderNodeById`, keyed by `Renderer.renderInputs`,
  * patched at `Renderer.elementId`.
  */
private[runtime] object Member {

  /** Every entity a member's bytes read — its own slots AND its children's,
    * because a member renders its whole subtree under one id. `liveEntities` on
    * the node alone stops at the node, which is right for an addressable node
    * (its children are addressable too) and wrong here (they are not).
    */
  def entitiesOf(node: LayoutNode): List[String] = node match {
    case c: LayoutNode.Component =>
      (c.liveEntities ++ c.children.flatMap(entitiesOf)).distinct
    // Deliberately NOT into a nested set: its members are tracked as members,
    // and they patch themselves. Descending here would wake the whole tile on
    // any bulb inside it — re-rendering, and re-supplying, everything the inner
    // members had just patched for themselves.
    case _ => Nil
  }
}

private[runtime] case class Member(
    gid: NodeId,
    // Which layout tree the member is in — `""` for the main page, else the
    // surface id. Carried rather than looked up because it is a property of the
    // SET and never changes, and because it is what decides who a member's
    // patch may reach.
    root: String,
    key: MemberKey,
    id: NodeId,
    node: LayoutNode.Component,
    // Which of the candidate's clauses produced this node. Part of the id of
    // any set nested inside it, so that two clauses holding sets cannot share
    // one.
    clause: Int
)

/** What one frame did to one candidate set's membership: the member lists the
  * recorder compares, and the members whose NODE was swapped in place because
  * their matched clause moved.
  */
private[runtime] case class MemberDelta(
    was: List[String],
    now: List[String],
    replaced: Set[NodeId]
)

/** One set's members in DOM order, with the entity projection the recorder
  * compares kept beside them. Derived, but cached rather than recomputed: a
  * frame that ticks a member without moving membership is the common case, and
  * projecting the whole list twice per frame is the thing that would make it
  * cost the set's size instead of the frame's.
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

/** The live half of the graph: every set's members in DOM order, plus the two
  * indices over them that the static half also keeps — id -> node, and entity
  * -> the nodes that read it. Views of one fact, updated together.
  *
  * The entity index is what makes a member an ordinary node on the SELECTION
  * side too: a member re-renders because something it binds moved, found the
  * same way a static component is found, rather than because its set's query
  * happened to be touched.
  */
private final case class MemberIndex(
    byGroup: Map[NodeId, GroupMembers],
    byId: Map[NodeId, Member],
    byEntity: Map[String, Vector[Member]]
) {

  /** Install a set's members, rebuilding that set's indices ONLY when the
    * members actually moved — `eq` because [[MemberGraph.syncMembers]] returns
    * the value it was given when a frame changed nothing.
    */
  def install(gid: NodeId, was: GroupMembers, now: GroupMembers): MemberIndex =
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

/** Who is in every candidate set right now, and in what order.
  *
  * Split out of the renderer because presence and ordering are pure functions
  * of (candidates, state) and have nothing to say about bytes: **the graph
  * decides presence and order, the renderer paints.** Nothing here needs a
  * template, a mustache context or the document walk, and nothing in here
  * reaches back into `Renderer` — the two static facts it needs arrive as
  * constructor arguments.
  *
  * @param setNodes
  *   the [[LayoutNode.SetNode]]s reachable in the STATIC index, by id. Sets
  *   nested inside a member are discovered from these.
  * @param rootOfIndexed
  *   every statically-indexed node id -> the layout tree it is in (`""` for the
  *   main page, else the surface id).
  */
private[runtime] final class MemberGraph(
    setNodes: Map[NodeId, LayoutNode.SetNode],
    rootOfIndexed: Map[NodeId, String]
) {

  /** A container whose children are MEMBERS rather than authored nodes: a
    * [[LayoutNode.SetNode]]'s candidates are decided at BUILD time, so the
    * runtime decides only presence and order
    * (`docs/adr/0003-candidate-sets.md`).
    *
    * One shape, deliberately. This was a trait over two implementations while
    * query-driven groups existed, and every difference between them was a
    * consequence of one thing: a query group's members were invented at
    * runtime, so it could not say who its candidates were, could not place them
    * by anything but entity id, and had to rescan to find them. None of that
    * survives a static candidate list.
    */
  private case class MemberSource(s: LayoutNode.SetNode) {

    private val position: Map[String, Int] = s.candidates.zipWithIndex.toMap

    /** entity -> the candidates whose presence it can decide: itself, plus
      * every candidate whose guards NAME it. This is the reverse index that
      * makes a frame cost the changed entities rather than the candidate list.
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

    /** Every candidate, in DOM order — what a full materialisation walks. */
    val candidates: Vector[String] = s.candidates.toVector

    /** The first clause whose guard holds. Falling off the end means the
      * candidate is NOT RENDERED — which is why a set has no presence field.
      *
      * A candidate HA does not know is evaluated against an empty state rather
      * than dropped outright, so a clause guarded only on ANOTHER entity ("show
      * this while the hall sensor is on") still decides. An unguarded clause is
      * unconditionally present: that is a build-time decision the runtime does
      * not get to revisit (P3).
      */
    def memberOf(
        gid: NodeId,
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
        // A clause whose node is itself a set has no rendering of its own to
        // be, so it is not a member; a set nested INSIDE a component clause is
        // the supported shape.
        .collect { case (LayoutNode.SetClause(_, c: LayoutNode.Component), i) =>
          member(gid, entityId, c, i)
        }
    }

    def affected(change: StateChange): Iterable[String] =
      movedBy.getOrElse(change.entityId, Nil)

    /** Authored candidate order — which IS the ordering when every key folded
      * to a registry fact at build time.
      */
    def ordinal(entityId: String): (Int, String) =
      (position.getOrElse(entityId, Int.MaxValue), entityId)

    def stable: Boolean = s.orderBy.isEmpty && s.limit.isEmpty

    /** Order the present members by the live keys, then cut to `limit`.
      *
      * Both only make sense over the PRESENT members: a hidden member's sort
      * key moving must emit nothing, and it acquires its place in the `Placed`
      * that shows it. A cut member is absent from the DOM rather than hidden in
      * it (P7), so it looks exactly like one whose clauses did not match — the
      * difference is only in why.
      */
    def arrange(
        members: Vector[Member],
        states: Map[String, EntityState]
    ): Vector[Member] = {
      val ordered =
        if (s.orderBy.isEmpty) members
        else
          // `sortWith` is stable, and the input is in candidate order, so
          // equal keys keep the order the author wrote. That is the mandatory
          // tiebreak: without it a set ordered on a live value would reshuffle
          // its ties on every tick.
          members.sortWith((a, b) =>
            MemberGraph.precedes(s.orderBy, entityOf(a), entityOf(b), states)
          )
      s.limit.fold(ordered)(ordered.take)
    }

    private def entityOf(m: Member): String = sortKey(m.key)
  }

  /** The stable id of one set member (`<setId>_<slug>`), the outer-morph /
    * insert / remove target for a single member.
    *
    * Derived from the member's KEY, never from its position: a positional id
    * would rename every node below an arrival, which is exactly what a
    * per-member delta exists to avoid. "One member is one entity" is a property
    * of the predicate engine, not of the id scheme — [[MemberKey]] is already a
    * sum, so a set whose unit of membership becomes something else needs no new
    * id story.
    */
  private def memberId(setId: NodeId, key: MemberKey): NodeId =
    NodeId.derived(s"${setId}_${LayoutNode.sanitize(sortKey(key))}")

  /** [[memberId]] for the entity case — the form the Server's per-entity patch
    * path and the resume's anchors name.
    */
  def memberIdOf(setId: NodeId, entityId: String): NodeId =
    memberId(setId, MemberKey.Entity(entityId))

  private def member(
      gid: NodeId,
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

  /** Where a set nested inside a member hangs, as an id — THE definition of the
    * scheme, read from both ends: [[sources]] registers a container under it,
    * and `Renderer.memberChild` renders the element under it.
    *
    * It was written out twice, once per end, with a comment asking them to
    * agree. They did, but the failure mode if they ever stopped is silent in
    * the worst way: the markup and the ids are both correct, the graph syncs,
    * and no patch is ever emitted because the container the recorder knows
    * about is not the element the browser has.
    *
    * The id says where the set hangs: `<member>_<clause>_<child path>`. Every
    * segment is static — a candidate cannot move, and neither can a clause
    * index or a child index.
    */
  def innerSetId(
      member: NodeId,
      clauseIdx: Int,
      path: List[Int]
  ): NodeId =
    NodeId.derived(s"${member}_${clauseIdx}_${path.mkString("_")}")

  /** Every member container, INCLUDING the ones nested inside a member — "a
    * tile per room", where each tile holds a set over that room's lights.
    *
    * They can all be enumerated here because a set's candidates are static, so
    * the whole tree of sets is knowable before any state arrives. That is what
    * makes an inner set an ordinary container with an ordinary id rather than
    * something materialised per frame, and it is why the inner members patch
    * themselves instead of the tile re-rendering.
    */
  private val sources: Map[NodeId, MemberSource] = {
    def nested(
        gid: NodeId,
        s: LayoutNode.SetNode
    ): List[(NodeId, MemberSource)] =
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
        member: NodeId,
        clauseIdx: Int,
        node: LayoutNode,
        path: List[Int]
    ): List[(NodeId, MemberSource)] = node match {
      case c: LayoutNode.Component =>
        c.children.zipWithIndex.flatMap { case (child, i) =>
          setsIn(member, clauseIdx, child, path :+ i)
        }
      case inner: LayoutNode.SetNode =>
        val id = innerSetId(member, clauseIdx, path)
        (id -> MemberSource(inner)) :: nested(id, inner)
    }

    val roots = setNodes.map { case (id, s) => id -> MemberSource(s) }
    roots ++ roots.toList.flatMap { case (gid, src) => nested(gid, src.s) }
  }

  /** Which layout tree each member container is in — `""` for the main page,
    * else the surface id. A nested set inherits its tile's, because it is not
    * in the static index to be looked up in.
    */
  private val sourceRoot: Map[NodeId, String] =
    sources.keys.map { gid =>
      gid -> rootOfIndexed.getOrElse(
        gid,
        // A nested set: find the outermost container its id hangs off. Only
        // roots are in the static index, and a root's id is a prefix of every
        // id below it, so the longest match that IS indexed is the owner.
        rootOfIndexed.keys
          .filter(id => gid.startsWith(id + "_"))
          .toList
          .sortBy(-_.length)
          .headOption
          .flatMap(rootOfIndexed.get)
          .getOrElse("")
      )
    }.toMap

  /** member id -> the container that owns it, for every member that can be
    * named ahead of time. A candidate set's members are static, so this is an
    * exact answer and the id never has to be PARSED to find its parent.
    *
    * There used to be an id-prefix search beside it, for the query group whose
    * member could be any entity in the house. It went with them, and good
    * riddance: a prefix test cannot tell `c_1_light_a_b` (set `c_1`, entity
    * `light.a_b`) from a member of a set called `c_1_light_a`, and once sets
    * nest inside members it cannot tell an inner member from an outer one.
    */
  private val memberOwner: Map[NodeId, NodeId] =
    sources.toList.flatMap { case (gid, src) =>
      src.candidates.map(e => memberId(gid, MemberKey.Entity(e)) -> gid)
    }.toMap

  /** The live half of the graph, beside the renderer's static index: mutable
    * because membership is maintained by the state stream rather than computed
    * from the dashboard, and IN PLACE because three things key on the owning
    * renderer's IDENTITY — `Server.publisherFor` rotates the changelog on a
    * renderer emission, `Server.reloadRepaints` repaints every connection on
    * one, and [[RenderCache]] compares renderers with `eq`. A membership change
    * that produced a NEW renderer would therefore rotate the log, repaint every
    * browser and flush the cache on exactly the case this exists to make cheap.
    * Mutating in place keeps all three keyed on the dashboard, for free.
    *
    * Same lifetime and the same reason as `Renderer.identityCache`: it dies
    * with the renderer, so it never needs invalidating.
    */
  private val index =
    new java.util.concurrent.atomic.AtomicReference(
      MemberIndex(Map.empty, Map.empty, Map.empty)
    )

  /** Whether `id` names a member container — a candidate set, at any nesting
    * depth. Read off [[sources]] rather than the static index, because a set
    * NESTED inside a member is not in the static index.
    *
    * This is what decides how a mount is patched. A state group's mount holds
    * at most ONE member (a bake group has one hole), so there are no siblings
    * to preserve and no position to fix: overwriting it IS the delta. A set's
    * is the opposite, and gets per-member `remove`/`before`.
    */
  def isSetContainer(id: NodeId): Boolean = sources.contains(id)

  /** A set's members in DOM order — from the graph once the stream has reached
    * the set, and derived from `states` until then.
    *
    * '''A reader never installs what it derived.''' [[syncMembers]] is the only
    * writer, and that is what keeps the graph a function of the state stream
    * rather than of whoever looked first: a page rendering at version 5 while
    * the recorder is still applying the frame that produced 5 would otherwise
    * install version 5 as the "before" that frame compares against, the frame
    * would see no membership move, and a client still at version 4 would never
    * be told about the arrival. Silent, and permanent until that set moved
    * again.
    *
    * The cost of not installing is one derivation per read before the first
    * recorded frame — which is exactly what every read cost before the graph
    * existed.
    */
  def membersOf(
      gid: NodeId,
      states: Map[String, EntityState]
  ): Vector[Member] = groupOf(gid, states).members

  /** The entity ids a set currently renders as children, in DOM order. A member
    * is a candidate whose first matching clause renders a component — a
    * candidate matching no clause renders nothing, so it is not a member.
    *
    * Read off the GRAPH rather than re-derived from `states`, for the reason
    * [[membersOf]] gives. Unknown / non-set id ⇒ empty.
    */
  def memberEntities(
      gid: NodeId,
      states: Map[String, EntityState]
  ): List[String] = groupOf(gid, states).entities

  private def groupOf(
      gid: NodeId,
      states: Map[String, EntityState]
  ): GroupMembers =
    sources.get(gid) match {
      case None      => GroupMembers(Vector.empty, Nil)
      case Some(src) =>
        index.get.byGroup.getOrElse(gid, materialise(gid, src, states))
    }

  private def materialise(
      gid: NodeId,
      src: MemberSource,
      states: Map[String, EntityState]
  ): GroupMembers =
    GroupMembers.of(
      src.arrange(
        src.candidates.flatMap(src.memberOf(gid, _, states)),
        states
      )
    )

  /** Apply one frame to EVERY candidate set's membership, reporting what it did
    * to each ([[MemberDelta]]).
    *
    * Only a CHANGED entity can have crossed a guard or clause boundary, so a
    * frame costs the number of CHANGES per set rather than a rescan of the
    * house — which is the whole point: the query group this replaced filtered
    * every entity in the house twice per frame per group, and once more per
    * pulling session.
    *
    * Nothing walks the member list unless a member actually moved. A frame that
    * only TICKS members — the common case — produces the same nodes, so
    * [[applyOne]] hands its set value straight back, `install` sees `eq` and
    * keeps the id index, and the two projections the recorder compares are one
    * list. That is the difference between costing the frame's size and costing
    * the set's, and it is worth being deliberate about: a first cut without it
    * measured 277 µs per frame on a 2 000-entity house where the scan it
    * replaced cost 3.4 ms.
    *
    * Every set, not only the visible ones. The graph tracks the STATE STREAM: a
    * frame that records nothing (nobody watching, a hidden surface) still moves
    * membership, and the next page render must see the set as it is.
    */
  def syncMembers(
      changes: List[StateChange],
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): Map[NodeId, MemberDelta] =
    sources.map { case (gid, src) =>
      val was = groupOf(gid, before)
      val touched = changes.iterator.flatMap(src.affected).distinct.toList
      val (now, replaced) =
        // An UNSTABLE container cannot be patched one member at a time: with a
        // live ordering one entity moving reorders its neighbours, and with a
        // limit it can push a different member out entirely. So rebuild the
        // list — O(candidates) for a container this frame actually touched,
        // which is bounded and static, where the query group it replaced
        // rescanned the whole house.
        if (touched.isEmpty) (was, Set.empty[NodeId])
        else if (!src.stable) {
          val rebuilt = materialise(gid, src, states)
          // A rebuild has to report the same thing `applyOne` does: a member
          // still present whose NODE moved (its clause switched). Nothing else
          // names it — a clause binding no live entity has no index edge — and
          // the id is sound whatever the card does.
          val swapped = rebuilt.members.iterator
            .filter(m =>
              was.members.exists(w => w.key == m.key && w.node != m.node)
            )
            .map(_.id)
            .toSet
          // Hand BACK the old value when the rebuild produced the same list —
          // the common case, since most ticks do not make two members cross.
          // `install` skips on `eq`, so without this a set with an ordering
          // would rebuild its id and entity indices on every frame that touched
          // it, which is the cost the incremental path exists to avoid. One
          // vector comparison buys it back.
          if (rebuilt.members == was.members) (was, swapped)
          else (rebuilt, swapped)
        } else
          touched.foldLeft((was, Set.empty[NodeId])) {
            case ((group, swapped), entityId) =>
              applyOne(gid, src, group, swapped, entityId, states)
          }
      val _ = index.updateAndGet(_.install(gid, was, now))
      gid -> MemberDelta(was.entities, now.entities, replaced)
    }

  /** One changed entity's effect on a set: it joined, it left, its clause moved
    * (so the node is REPLACED in place), or — the common case — nothing about
    * it as a node changed and `group` comes back untouched.
    *
    * The replacement has to be reported rather than left to the reverse index.
    * A member whose new clause binds no live entity contributes no edges at
    * all, so nothing would name it and the switch would go unrecorded while its
    * bytes moved. The id is the sound handle: it exists for every member
    * whatever the card does, because `Dashboard.validate` rejects a
    * `wrapAsCell = false` card as a set clause precisely so that every member
    * has its own element.
    */
  private def applyOne(
      gid: NodeId,
      src: MemberSource,
      group: GroupMembers,
      replaced: Set[NodeId],
      entityId: String,
      states: Map[String, EntityState]
  ): (GroupMembers, Set[NodeId]) = {
    val key = MemberKey.Entity(entityId)
    val existing = group.members.find(_.key == key)
    val arriving = src.memberOf(gid, entityId, states)
    if (existing.map(_.node) == arriving.map(_.node)) (group, replaced)
    else {
      val without = group.members.filterNot(_.key == key)
      (
        GroupMembers.of(arriving.fold(without)(insertOrdered(src, without, _))),
        // Present before AND after: an arrival or a departure is a structural
        // mutation the changelog already carries.
        if (existing.isDefined && arriving.isDefined)
          replaced ++ arriving.map(_.id)
        else replaced
      )
    }
  }

  /** Members are ordered by their container's [[MemberSource.ordinal]] —
    * matching the order a full materialisation produces, so an arrival lands
    * where a rescan would have put it and no other member's position moves.
    */
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

  /** The member an id names, deriving its set if the stream has not reached it
    * — reachable only before the recorder has synced that set, since a log
    * entry for a member implies a sync produced it.
    */
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

  /** Which layout tree a MATERIALISED member is in, for the renderer's
    * `rootOf`. `None` for anything that is not a current member.
    */
  def rootOfMember(id: NodeId): Option[String] =
    index.get.byId.get(id).map(_.root)

  /** Members of sets rooted in `root` that bind `entityId`. */
  def membersBinding(entityId: String, root: String): Set[NodeId] =
    index.get.byEntity
      .getOrElse(entityId, Vector.empty)
      .collect { case m if m.root == root => m.id }
      .toSet

  /** What a materialised member's own node binds — empty for anything that is
    * not a current member.
    */
  def liveEntitiesOf(id: NodeId): List[String] =
    index.get.byId.get(id).toList.flatMap(_.node.liveEntities)

  /** Main-page member containers whose MEMBERSHIP this frame could have moved.
    * No entity list: a member that merely ticked is found through the reverse
    * index now, so the only question left here is which sets to ask about
    * membership.
    */
  def affectedSets(changes: List[StateChange]): List[NodeId] =
    containersIn("", changes)

  /** Like [[affectedSets]], scoped to one open surface. */
  def affectedSurfaceSets(
      surfaceId: String,
      changes: List[StateChange]
  ): List[NodeId] = containersIn(surfaceId, changes)

  /** Read off [[sources]] rather than the static index, because a set NESTED
    * inside a member is not in the static index — it hangs off a member, which
    * is the live half. Selecting from the index instead is silent when wrong:
    * the inner set syncs, its members move, and nothing records it.
    */
  private def containersIn(
      root: String,
      changes: List[StateChange]
  ): List[NodeId] =
    sources.iterator
      .collect {
        case (gid, src)
            if sourceRoot.getOrElse(gid, "") == root &&
              changes.exists(src.affected(_).nonEmpty) =>
          gid
      }
      .toList
      .sorted
}

private[runtime] object MemberGraph {

  /** Does `a` sort before `b` under this lexicographic ordering? The first
    * position that separates them decides; if none does they are equal, and
    * `false` keeps the caller's stable sort from moving them.
    */
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
        // True first under `asc` — "the ones that are on, then the rest".
        def holds(id: String) =
          states.get(id).exists(st => Conditions.matchesIn(p, st, states))
        java.lang.Boolean.compare(holds(b), holds(a))
      case LayoutNode.SortKey.Prop(property) =>
        def read(id: String) =
          states.get(id).fold("")(Conditions.propertyOf(property, _))
        val (l, r) = (read(a), read(b))
        // Numeric when BOTH sides are numbers, so brightness sorts 2 < 10;
        // otherwise lexicographic, which is what a name or a state wants.
        (l.toDoubleOption, r.toDoubleOption) match {
          case (Some(x), Some(y)) => java.lang.Double.compare(x, y)
          case _                  => l.compareTo(r)
        }
    }
    if (term.descending) -raw else raw
  }
}
