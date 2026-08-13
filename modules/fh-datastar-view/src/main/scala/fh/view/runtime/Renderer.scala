package fh.view.runtime

import com.samskivert.mustache.Template
import fh.view.build.LibPackage
import fh.view.model.{
  Activation,
  Cell,
  Dashboard,
  DomId,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  SlotSource,
  Surface
}

/** What a node's own rendering reads, reduced to a comparable value — see
  * [[Renderer.renderInputs]] for what goes in each half and why.
  *
  * The asymmetry to hold on to whenever this changes: a key that is TOO
  * DISCRIMINATING costs a wasted render, and the digest then shows nothing
  * moved so nothing is sent — CPU, no bug. A key that is TOO COARSE serves a
  * client bytes that no longer match its state, silently and permanently. When
  * in doubt, over-discriminate.
  */
case class RenderInputs(
    entities: Map[String, Long],
    vars: Map[String, String]
) derives CanEqual {

  /** Whether this was rendered from a snapshot at or ahead of `other` on every
    * entity it reads — the partial order [[RenderCache]] uses to refuse an
    * install that would replace current bytes with superseded ones.
    *
    * PARTIAL on purpose. Different key sets are not ordered at all: an entity
    * appearing or vanishing changes what the node reads, not how fresh it is,
    * and calling that "behind" would let a stale generation sit unchallenged.
    * Only a same-shaped, entity-for-entity comparison answers `true`.
    */
  def isAtLeast(other: RenderInputs): Boolean =
    vars == other.vars &&
      entities.sizeIs == other.entities.size &&
      other.entities.forall((e, v) => entities.get(e).exists(_ >= v))
}

/** One member of a dynamic group, MATERIALISED into the node graph: a real
  * [[LayoutNode.Component]] under the id its [[MemberKey]] derives, carrying
  * the matched entity as a literal `entity_id` slot exactly as the case
  * dispatch used to set it per render.
  *
  * The node is STATE-DERIVED — which case matched, and so which card and slots
  * — so it is frozen at the moment membership was applied and must be replaced
  * when the matched entity moves across a case boundary
  * ([[Renderer.syncMembers]]). Everything else about it is an ordinary node:
  * rendered by [[Renderer.renderNodeById]], keyed by [[Renderer.renderInputs]],
  * patched at [[Renderer.elementId]].
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
    // GROUP and never changes, and because it is what decides who a member's
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

/** What one frame did to one dynamic group's membership: the member lists the
  * recorder compares, and the members whose NODE was swapped in place because
  * their matched case moved.
  */
private[runtime] case class MemberDelta(
    was: List[String],
    now: List[String],
    replaced: Set[NodeId]
)

/** One group's members in DOM order, with the entity projection the recorder
  * compares kept beside them. Derived, but cached rather than recomputed: a
  * frame that ticks a member without moving membership is the common case, and
  * projecting the whole list twice per frame is the thing that would make it
  * cost the group's size instead of the frame's.
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

/** The dynamic half of the graph: every group's members in DOM order, plus the
  * two indices over them that the static half also keeps — id -> node, and
  * entity -> the nodes that read it. Views of one fact, updated together.
  *
  * The entity index is what makes a member an ordinary node on the SELECTION
  * side too: a member re-renders because something it binds moved, found the
  * same way a static component is found, rather than because its group's query
  * happened to be touched.
  */
private final case class MemberGraph(
    byGroup: Map[NodeId, GroupMembers],
    byId: Map[NodeId, Member],
    byEntity: Map[String, Vector[Member]]
) {

  /** Install a group's members, rebuilding that group's indices ONLY when the
    * members actually moved — `eq` because [[Renderer.syncMembers]] returns the
    * value it was given when a frame changed nothing.
    */
  def install(gid: NodeId, was: GroupMembers, now: GroupMembers): MemberGraph =
    if ((now eq was) && byGroup.contains(gid)) this
    else {
      val leaving = byGroup.get(gid).toVector.flatMap(_.members)
      MemberGraph(
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

/** A container is just a Component whose template splices its rendered
  * `children` (`{{#children}}{{{html}}}{{/children}}`), so container kinds
  * (row, column, grid, …) are templates rather than cases here.
  *
  * Ids are location-derived from a node's index path ([[LayoutNode.pathId]]) —
  * authors never invent one. A surface is a separate layout tree whose ids are
  * namespaced (`s_<id>__…`) so they cannot collide with the main page.
  */
class Renderer(
    dashboard: Dashboard,
    templates: Templates,
    transforms: Transforms
) {

  /** An addressable index over one layout tree; generated ids carry `idPrefix`
    * (empty for the main page, `s_<id>__` for a surface).
    */
  private class Index(root: LayoutNode, val idPrefix: String) {
    val indexed: Map[NodeId, (LayoutNode, List[Int])] = {
      def walk(
          node: LayoutNode,
          path: List[Int]
      ): List[(NodeId, (LayoutNode, List[Int]))] = {
        val self = LayoutNode.nodeId(idPrefix, path) -> (node, path)
        node match {
          case c: LayoutNode.Component =>
            self :: c.children.zipWithIndex.flatMap { case (ch, i) =>
              walk(ch, path :+ i)
            }
          // A member container is a LEAF of the static index: its children are
          // members, addressed by `memberId` rather than by a path (see
          // [[Member]]).
          case _: LayoutNode.SetNode => List(self)
        }
      }
      walk(root, Nil).toMap
    }

    val byEntity: Map[String, Set[NodeId]] =
      indexed.toList
        .collect { case (id, (c: LayoutNode.Component, _)) => id -> c }
        .flatMap { case (id, c) => c.liveEntities.map(_ -> id) }
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap

  }

  private val mainIndex = new Index(dashboard.card, "")

  /** Does the browser's `<head>` still match the UNPATCHABLE part of this
    * dashboard — the theme's `<link>`ed stylesheets, its scripts (module and
    * inline), and its `chrome` frame? A mismatch is the one thing neither a
    * body patch nor a head patch can repair (a `<link>` can be added but not
    * un-applied, a script cannot be un-run, and the chrome is the frame the
    * body patch lands INSIDE), so it is the one thing worth a full page
    * **reload** (docs/adr/0011-the-live-connection.md).
    *
    * The rest of the head — tokens, inline CSS, `<title>` — is [[styleHash]]
    * instead: it patches.
    *
    * Deliberately NOT a hash of the whole dashboard. Cards and layout live in
    * the body, which the repaint already re-sends in full, so hashing them
    * would reload the page on every edit for no gain. And it is not needed to
    * gate the resume either: any dashboard change arrives via a renderer swap,
    * which rotates the fragment log's `logId` and rejects the cursor anyway.
    *
    * STABLE ACROSS RESTARTS by design: an add-on restart on an HA update must
    * not refresh every browser when the theme is byte-identical. That is
    * exactly why it cannot double as `logId`, whose whole job is to NOT survive
    * one.
    */
  val headHash: String = Renderer.headFingerprint(dashboard)

  /** The PATCHABLE part of the head: the theme's tokens and inline CSS (which
    * are exactly [[themeStyleTag]]) plus the authored `<title>`. A mismatch
    * costs two small element patches, not a reload — see `Server.headPatches`.
    *
    * Same restart-stability contract as [[headHash]].
    */
  val styleHash: String = Renderer.styleFingerprint(dashboard)

  /** Surfaced so the caller that builds a renderer logs them once, rather than
    * the renderer printing from inside a pure render path (same split as
    * [[uiStateAnomalies]]).
    */
  val warnings: List[String] = dashboard.warnings

  /** Cards that opted out of the `.fh-cell` wrapper
    * ([[fh.view.model.CardDef.wrapAsCell]] = false) — their template root must
    * stay a direct child of a framework-structural parent (the tab anchors).
    */
  private val noWrapCards: Set[String] =
    dashboard.cards.collect { case (name, cd) if !cd.wrapAsCell => name }.toSet

  /** Memo for identity-derived (`reactive: false`) slot values, keyed by
    * `(entityId, transform)`. Such a slot reads only the entity's immutable
    * identity (`$domain`/`$entity_id`), so its value is stable for the life of
    * the entity — resolve once, then reuse (see `renderTemplate`). Lives on the
    * Renderer, which is rebuilt on hot-reload/navigate, so it never needs
    * invalidation; concurrent fibers may race to fill an entry but compute the
    * same value.
    */
  private val identityCache =
    new java.util.concurrent.ConcurrentHashMap[(String, String), String]()

  private val surfaceIndexes: Map[String, Index] =
    dashboard.surfaces.map { case (sid, s) =>
      sid -> new Index(s.content, Renderer.surfacePrefix(sid))
    }

  private val allIndexed: Map[NodeId, (LayoutNode, List[Int], String)] =
    (mainIndex :: surfaceIndexes.values.toList).flatMap { idx =>
      idx.indexed.map { case (id, (n, p)) => id -> (n, p, idx.idPrefix) }
    }.toMap

  /** A container whose children are MEMBERS rather than authored nodes: a
    * [[LayoutNode.SetNode]]'s candidates are decided at BUILD time, so the
    * runtime decides only presence and order
    * (`docs/adr/0003-dynamic-groups.md`).
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

    def cell: Option[Cell] = s.cell

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
            .find(_._1.when.forall(Renderer.matchesIn(_, subject, states)))
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
            Renderer.precedes(s.orderBy, entityOf(a), entityOf(b), states)
          )
      s.limit.fold(ordered)(ordered.take)
    }

    private def entityOf(m: Member): String = sortKey(m.key)
  }

  private def member(
      gid: NodeId,
      entityId: String,
      node: LayoutNode.Component,
      clause: Int
  ): Member = {
    val key = MemberKey.Entity(entityId)
    Member(
      gid,
      rootOf(gid).getOrElse(""),
      key,
      memberId(gid, key),
      node,
      clause
    )
  }

  /** Every member container, INCLUDING the ones nested inside a member — "a
    * tile per room", where each tile holds a set over that room's lights.
    *
    * They can all be enumerated here because a set's candidates are static, so
    * the whole tree of sets is knowable before any state arrives. That is what
    * makes an inner set an ordinary container with an ordinary id rather than
    * something materialised per frame, and it is why the inner members patch
    * themselves instead of the tile re-rendering.
    *
    * The id says where the set hangs: `<member>_<clause>_<child path>`. Every
    * segment is static — a candidate cannot move, and neither can a clause
    * index or a child index.
    */
  /** Where a set nested inside a member hangs, as an id — THE definition of the
    * scheme, read from both ends: [[memberSources]] registers a container under
    * it, and [[memberChild]] renders the element under it.
    *
    * It was written out twice, once per end, with a comment asking them to
    * agree. They did, but the failure mode if they ever stopped is silent in
    * the worst way: the markup and the ids are both correct, the graph syncs,
    * and no patch is ever emitted because the container the recorder knows
    * about is not the element the browser has.
    */
  private def innerSetId(
      member: NodeId,
      clauseIdx: Int,
      path: List[Int]
  ): NodeId =
    NodeId.derived(s"${member}_${clauseIdx}_${path.mkString("_")}")

  private val memberSources: Map[NodeId, MemberSource] = {
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

    val roots = allIndexed.collect { case (id, (s: LayoutNode.SetNode, _, _)) =>
      id -> MemberSource(s)
    }
    roots ++ roots.toList.flatMap { case (gid, src) => nested(gid, src.s) }
  }

  /** Which layout tree each member container is in — `""` for the main page,
    * else the surface id. A nested set inherits its tile's, because it is not
    * in the static index to be looked up in.
    */
  // `lazy`: `prefixToRoot` is a val defined further down the class body.
  private lazy val sourceRoot: Map[NodeId, String] =
    memberSources.keys.map { gid =>
      gid -> allIndexed
        .get(gid)
        .map { case (_, _, prefix) => prefixToRoot(prefix) }
        .getOrElse(
          // A nested set: find the outermost container its id hangs off. Only
          // roots are in `allIndexed`, and a root's id is a prefix of every id
          // below it, so the longest match that IS indexed is the owner.
          allIndexed.keys
            .filter(id => gid.startsWith(id + "_"))
            .toList
            .sortBy(-_.length)
            .headOption
            .flatMap(id => allIndexed.get(id))
            .map { case (_, _, prefix) => prefixToRoot(prefix) }
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
    memberSources.toList.flatMap { case (gid, src) =>
      src.candidates.map(e => memberId(gid, MemberKey.Entity(e)) -> gid)
    }.toMap

  /** The dynamic half of the graph, beside the static [[allIndexed]]: mutable
    * because membership is maintained by the state stream rather than computed
    * from the dashboard, and IN PLACE because three things key on this
    * renderer's IDENTITY — `Server.publisherFor` rotates the changelog on a
    * renderer emission, `Server.reloadRepaints` repaints every connection on
    * one, and [[RenderCache]] compares renderers with `eq`. A membership change
    * that produced a NEW renderer would therefore rotate the log, repaint every
    * browser and flush the cache on exactly the case this exists to make cheap.
    * Mutating in place keeps all three keyed on the dashboard, for free.
    *
    * Same lifetime and the same reason as [[identityCache]]: it dies with the
    * renderer, so it never needs invalidating.
    */
  private val graph =
    new java.util.concurrent.atomic.AtomicReference(
      MemberGraph(Map.empty, Map.empty, Map.empty)
    )

  /** A group's members in DOM order — from the graph once the stream has
    * reached the group, and derived from `states` until then.
    *
    * '''A reader never installs what it derived.''' [[syncMembers]] is the only
    * writer, and that is what keeps the graph a function of the state stream
    * rather than of whoever looked first: a page rendering at version 5 while
    * the recorder is still applying the frame that produced 5 would otherwise
    * install version 5 as the "before" that frame compares against, the frame
    * would see no membership move, and a client still at version 4 would never
    * be told about the arrival. Silent, and permanent until that group moved
    * again.
    *
    * The cost of not installing is one derivation per read before the first
    * recorded frame — which is exactly what every read cost before the graph
    * existed.
    */
  private[runtime] def membersOf(
      gid: NodeId,
      states: Map[String, EntityState]
  ): Vector[Member] = groupOf(gid, states).members

  private def groupOf(
      gid: NodeId,
      states: Map[String, EntityState]
  ): GroupMembers =
    memberSources.get(gid) match {
      case None      => GroupMembers(Vector.empty, Nil)
      case Some(src) =>
        graph.get.byGroup.getOrElse(gid, materialise(gid, src, states))
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

  /** Apply one frame to EVERY dynamic group's membership, reporting what it did
    * to each ([[MemberDelta]]).
    *
    * Only a CHANGED entity can have crossed a query or case boundary, so a
    * frame costs the number of CHANGES per group rather than a rescan of the
    * house — which is the whole point: `dynamicMembers` used to filter every
    * entity in the house twice per frame per group, and once more per pulling
    * session.
    *
    * Nothing walks the member list unless a member actually moved. A frame that
    * only TICKS members — the common case — produces the same nodes, so
    * [[applyOne]] hands its group value straight back, `install` sees `eq` and
    * keeps the id index, and the two projections the recorder compares are one
    * list. That is the difference between costing the frame's size and costing
    * the group's, and it is worth being deliberate about: a first cut without
    * it measured 277 µs per frame on a 2 000-entity house where the scan it
    * replaced cost 3.4 ms.
    *
    * Every group, not only the visible ones. The graph tracks the STATE STREAM:
    * a frame that records nothing (nobody watching, a hidden surface) still
    * moves membership, and the next page render must see the group as it is.
    */
  def syncMembers(
      changes: List[StateChange],
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): Map[NodeId, MemberDelta] =
    memberSources.map { case (gid, src) =>
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
      val _ = graph.updateAndGet(_.install(gid, was, now))
      gid -> MemberDelta(was.entities, now.entities, replaced)
    }

  /** One changed entity's effect on a group: it joined, it left, its case moved
    * (so the node is REPLACED in place), or — the common case — nothing about
    * it as a node changed and `group` comes back untouched.
    *
    * The replacement has to be reported rather than left to the reverse index.
    * A member whose new case binds no live entity contributes no edges at all,
    * so nothing would name it and the switch would go unrecorded while its
    * bytes moved. The id is the sound handle: it exists for every member
    * whatever the card does, because `Dashboard.validate` rejects a
    * `wrapAsCell = false` card as a dynamic case precisely so that every member
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

  /** The member an id names, deriving its group if the stream has not reached
    * it — reachable only before the recorder has synced that group, since a log
    * entry for a member implies a sync produced it.
    */
  private def memberAt(
      id: NodeId,
      states: Map[String, EntityState]
  ): Option[Member] =
    graph.get.byId
      .get(id)
      .orElse(
        memberOwner
          .get(id)
          .flatMap(gid => membersOf(gid, states).find(_.id == id))
      )

  /** Main-page member containers whose MEMBERSHIP this frame could have moved.
    * No entity list: a member that merely ticked is found through the reverse
    * index now, so the only question left here is which groups to ask about
    * membership.
    */
  def affectedDynamics(changes: List[StateChange]): List[NodeId] =
    containersIn("", changes)

  /** Like [[affectedDynamics]], scoped to one open surface. */
  def affectedSurfaceDynamics(
      surfaceId: String,
      changes: List[StateChange]
  ): List[NodeId] = containersIn(surfaceId, changes)

  /** Read off [[memberSources]] rather than the static index, because a set
    * NESTED inside a member is not in the static index — it hangs off a member,
    * which is the dynamic half. Selecting from the index instead is silent when
    * wrong: the inner set syncs, its members move, and nothing records it.
    */
  private def containersIn(
      root: String,
      changes: List[StateChange]
  ): List[NodeId] =
    memberSources.iterator
      .collect {
        case (gid, src)
            if sourceRoot.getOrElse(gid, "") == root &&
              changes.exists(src.affected(_).nonEmpty) =>
          gid
      }
      .toList
      .sorted

  /** [[bakeGroup]], for the flip path. A state group's members are a FIXED,
    * tiny set (its branches), which is why — unlike a dynamic group over
    * unbounded entities — its mutations can never accumulate and it needs no
    * eviction horizon.
    */
  def bakeMembers(gid: NodeId): List[String] = bakeGroup(gid)

  /** Every bake group, computed ONCE. `dashboard.surfaces` is fixed for the
    * life of a renderer, so this is a pure inversion of it: `bakeInto` target
    * -> member surface ids.
    *
    * It has to be a `val`. As a `def` it re-scanned every surface on each call,
    * and `mountId` calls it for EVERY node on EVERY render — so a paint cost
    * O(nodes × surfaces) for an answer that cannot change.
    */
  private val bakeGroups: Map[NodeId, List[String]] =
    dashboard.surfaces.toList
      .flatMap { case (sid, s) =>
        s.bakeInto.map(gid => (gid, sid, s.bakeIndex))
      }
      .groupBy(_._1)
      .view
      .mapValues(
        _.sortBy { case (_, sid, bi) => (bi.getOrElse(Int.MaxValue), sid) }
          .map(_._2)
      )
      .toMap

  /** Ordered by `bakeIndex`, with the surface id as a stable tiebreak and as
    * the fallback for a member carrying none. That order is what a ui-state
    * index selects among, and what state selection walks first-match (then,
    * elseif…, else).
    */
  private def bakeGroup(gid: NodeId): List[String] =
    bakeGroups.getOrElse(gid, Nil)

  /** "Shown on first paint, with no selection and no click." */
  private def defaultOpenUser(s: Surface): Boolean = s.activation match {
    case Activation.User(d) => d
    case _                  => false
  }

  /** `Dashboard.validate` rejects mode-mixed groups, so the first member
    * decides for the whole group. A state-selected group is a pure function of
    * entity state and never reads a session's uiState.
    */
  private def isStateGroup(gid: NodeId): Boolean =
    bakeGroup(gid).headOption.exists(isStateSurface)

  /** Every component id some surface bakes into. `bakeInto` is AUTHORED (a
    * hoist-resolved relation `Dashboard.validate` checks against the registry),
    * which is the one place a node id enters from outside the tree walk.
    */
  private val bakeOwnerIds: Set[NodeId] =
    dashboard.surfaces.values.flatMap(_.bakeInto).map(NodeId.derived).toSet

  /** Tabs. Their own rendering is shared like any other node — the
    * client-selected member lives in the MOUNT, which a patch never carries.
    * What is per-client is FILLING that mount ([[surfaceVariesByViewer]]).
    */
  val userBakeOwnerIds: Set[NodeId] =
    bakeOwnerIds.filterNot(isStateGroup)

  /** If/else hosts. Selection included, their HTML is a pure function of entity
    * state, so they render once per slug for every viewer.
    */
  val stateBakeOwnerIds: Set[NodeId] =
    bakeOwnerIds.filter(isStateGroup)

  private val prefixToRoot: Map[String, String] =
    Map(mainIndex.idPrefix -> "") ++
      surfaceIndexes.map { case (sid, idx) => idx.idPrefix -> sid }

  /** `""` = the main page, `<sid>` = inside that surface. NOT recoverable from
    * the id itself: an id carries only its OWN surface prefix (`s_<sid>__c_0`),
    * and a nesting is three independent prefixes with no link between them.
    *
    * A materialised member answers through its GROUP, which is the tree it is
    * in. Without that a member id reads as "unknown", which
    * [[userSurfaceOfNode]] tags as the main page and [[visibleNode]] treats as
    * visible to everyone — harmless while members were only ever selected by
    * their group's query, wrong once they are selected by the reverse index
    * like any other node, because a member inside a surface would then reach
    * clients who do not have it open.
    */
  private def rootOf(id: NodeId): Option[String] =
    allIndexed
      .get(id)
      .map { case (_, _, prefix) => prefixToRoot(prefix) }
      .orElse(graph.get.byId.get(id).map(_.root))

  /** A surface's place in the tree is where its host node sits, so this is just
    * [[rootOf]] applied to `bakeInto`. A popup has no `bakeInto`, hosts on the
    * main page, and is therefore absent here.
    */
  private val surfaceParent: Map[String, String] =
    dashboard.surfaces.flatMap { case (sid, s) =>
      s.bakeInto.flatMap(rootOf).filter(_.nonEmpty).map(sid -> _)
    }

  /** The tag deciding which clients a patch from `sid`'s tree may reach (`sid`
    * itself included).
    *
    * State surfaces are TRANSPARENT: a branch of an `If` is selected by entity
    * state, identically for every client, so it hides nothing and the walk
    * passes through to whatever encloses it. `None` means no user surface
    * above: visible to everyone.
    */
  def userSurfaceOf(sid: String): Option[String] =
    if (!isStateSurface(sid)) Some(sid)
    else surfaceParent.get(sid).flatMap(userSurfaceOf)

  /** [[userSurfaceOf]] for a node, via the tree it was indexed from. */
  def userSurfaceOfNode(id: NodeId): Option[String] =
    rootOf(id).filter(_.nonEmpty).flatMap(userSurfaceOf)

  private def isStateSurface(sid: String): Boolean =
    dashboard.surfaces
      .get(sid)
      .exists(_.activation match {
        case _: Activation.State => true
        case _                   => false
      })

  /** The state half of visibility; a pure function of entity state. */
  private def stateSelected(
      sid: String,
      states: Map[String, EntityState]
  ): Boolean =
    dashboard.surfaces.get(sid).flatMap(_.bakeInto).exists { gid =>
      resolveActiveByState(gid, states)
        .flatMap(bakeMembers(gid).lift)
        .contains(sid)
    }

  /** `open` alone does not answer this. `selectedSurfaces` reports a selection
    * for every user bake group whether or not that group is on screen, so a tab
    * panel inside a hidden `If` branch is "open" while nothing of it exists in
    * any DOM — and `open.contains` would render and push that panel on every
    * tick of an entity it binds. Harmless, since the morph targets an id the
    * DOM lacks, and pure waste.
    *
    * Hence the walk UP the whole chain. The visited set is for the same reason
    * as [[userGroupsUnder]]'s: `bakeInto` is authored, so the chain is not
    * guaranteed acyclic.
    */
  def visibleSurface(
      sid: String,
      open: Set[String],
      states: Map[String, EntityState]
  ): Boolean = {
    def up(sid: String, seen: Set[String]): Boolean =
      !seen(sid) && {
        val here =
          if (isStateSurface(sid)) stateSelected(sid, states) else open(sid)
        here && surfaceParent.get(sid).forall(up(_, seen + sid))
      }
    up(sid, Set.empty)
  }

  /** An id this renderer does not know (a dynamic group's per-entity child)
    * counts as visible — the safe direction, since over-sending costs bytes
    * where under-sending loses an update.
    */
  def visibleNode(
      id: NodeId,
      open: Set[String],
      states: Map[String, EntityState]
  ): Boolean =
    rootOf(id).forall(r => r.isEmpty || visibleSurface(r, open, states))

  /** The recursion structure of the transitive active-set and affected-flip
    * walks: a group is only reachable through the chain of active members above
    * it, so each walk starts at one root's owners and descends only into
    * selected members.
    */
  private val stateGidsByRoot: Map[String, List[NodeId]] =
    stateBakeOwnerIds.toList.sorted
      .flatMap(gid => rootOf(gid).map(_ -> gid))
      .groupMap(_._1)(_._2)

  private def stateGidsAtRoot(root: String): List[NodeId] =
    stateGidsByRoot.getOrElse(root, Nil)

  /** Whether a state condition holds. It is SUBJECT-FREE — `Dashboard.validate`
    * rejects a `Cmp` in one that does not name its entity — so this is a
    * handful of lookups rather than the whole-map scan a quantifier needed.
    * [[EntityState.none]] stands in for the subject nothing reads.
    */
  private def holds(
      condition: Predicate,
      states: Map[String, EntityState]
  ): Boolean =
    Renderer.matchesIn(condition, EntityState.none, states)

  /** FIRST match in `bakeIndex` order, so an "else" is just a member with an
    * always-true condition at the last index and an `elseif` is one more
    * member, with no special casing. `None` when nothing holds: the host bakes
    * empty content.
    *
    * Pure over the snapshot, which is what lets the caller evaluate it against
    * a before AND an after snapshot to detect a flip.
    */
  private[runtime] def resolveActiveByState(
      gid: NodeId,
      states: Map[String, EntityState]
  ): Option[Int] = {
    val idx = bakeGroup(gid).indexWhere(sid =>
      dashboard.surfaces
        .get(sid)
        .exists(_.activation match {
          case Activation.State(condition) =>
            holds(condition, states)
          case _ => false
        })
    )
    Option.when(idx >= 0)(idx)
  }

  /** Every entity a state group's conditions read. Exact, because a state
    * condition is subject-free: a comparison names its entity, a count names
    * its candidates, and nothing else reaches the snapshot. So a change to an
    * entity outside this set cannot move the group's selection — including the
    * entity's first appearance, which a quantified condition could not rule
    * out.
    */
  private lazy val stateGroupEntities: Map[NodeId, Set[String]] =
    stateBakeOwnerIds.map { gid =>
      gid -> bakeGroup(gid).flatMap { sid =>
        dashboard.surfaces
          .get(sid)
          .toList
          .flatMap(_.activation match {
            case Activation.State(c) => Predicate.referencedEntities(c)
            case _                   => Nil
          })
      }.toSet
    }.toMap

  /** The O(1) pre-test of the flip check: the changed entities decide, not the
    * surfaces, same as [[affectedDynamics]] for membership.
    */
  private def conditionTouched(
      gid: NodeId,
      changes: List[StateChange]
  ): Boolean = {
    val reads = stateGroupEntities.getOrElse(gid, Set.empty)
    changes.exists(c => reads.contains(c.current.entityId))
  }

  /** Walks only through currently-selected members: a flip inside a hidden
    * branch is unreachable DOM, and when its ancestor later flips it in, the
    * ancestor's fill renders it fresh.
    */
  def affectedStateGroups(
      changes: List[StateChange],
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): List[NodeId] =
    affectedStateGroupsFrom("", changes, before, states)

  /** [[affectedStateGroups]] for state groups inside an OPEN user surface,
    * whose visibility is a session's open set rather than the main page.
    */
  def affectedStateGroupsIn(
      surfaceId: String,
      changes: List[StateChange],
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): List[NodeId] =
    affectedStateGroupsFrom(surfaceId, changes, before, states)

  private def affectedStateGroupsFrom(
      root: String,
      changes: List[StateChange],
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): List[NodeId] =
    stateGidsAtRoot(root).flatMap { gid =>
      val flipped =
        conditionTouched(gid, changes) &&
          resolveActiveByState(gid, before) != resolveActiveByState(gid, states)
      // Recurse into the CURRENTLY selected member only: nested groups in the
      // inactive branch are not in any client's DOM.
      val nested = resolveActiveByState(gid, states).toList.flatMap(idx =>
        affectedStateGroupsFrom(bakeGroup(gid)(idx), changes, before, states)
      )
      (if (flipped) List(gid) else Nil) ++ nested
    }

  /** What keeps a hidden branch SILENT, structurally: an inactive member is
    * never in the set, so nothing downstream ever consults its indices and no
    * guard map is needed.
    *
    * `excluding` prunes whole subtrees — the caller passes the groups it flips
    * this round, whose fill re-renders the member wholesale, so patching its
    * parts as well would double-emit.
    */
  def activeStateSurfaces(
      states: Map[String, EntityState],
      excluding: Set[NodeId] = Set.empty
  ): Set[String] =
    activeStateSurfacesFrom("", states, excluding)

  /** Like [[activeStateSurfaces]], rooted at one surface's content tree (the
    * per-session pass, for state groups nested inside an open surface).
    */
  def activeStateSurfacesIn(
      surfaceId: String,
      states: Map[String, EntityState],
      excluding: Set[NodeId] = Set.empty
  ): Set[String] =
    activeStateSurfacesFrom(surfaceId, states, excluding)

  private def activeStateSurfacesFrom(
      root: String,
      states: Map[String, EntityState],
      excluding: Set[NodeId]
  ): Set[String] =
    stateGidsAtRoot(root)
      .filterNot(excluding)
      .flatMap { gid =>
        resolveActiveByState(gid, states).toList.flatMap { idx =>
          val sid = bakeGroup(gid)(idx)
          sid :: activeStateSurfacesFrom(sid, states, excluding).toList
        }
      }
      .toSet

  /** `uiState` is UNTRUSTED: a value is kept only when it indexes a real
    * member, else the group's `defaultOpen` member (or 0) wins.
    *
    * The `Option[String]` is a warning, and is `Some` ONLY when a value was
    * present but off (unparseable, or an int out of range) — never when no
    * selection is present at all. One source of truth for both the chosen index
    * and the malformed check.
    */
  private[runtime] def resolveActive(
      gid: NodeId,
      uiState: Map[String, String]
  ): (Int, Option[String]) = {
    val members = bakeGroup(gid)
    val n = members.size
    val fallback =
      members.indexWhere(sid =>
        dashboard.surfaces.get(sid).exists(defaultOpenUser)
      ) match {
        case -1 => 0
        case i  => i
      }
    uiState.get(gid) match {
      case None      => (fallback, None)
      case Some(raw) =>
        raw.toIntOption.filter(i => i >= 0 && i < n) match {
          case Some(i) => (i, None)
          case None    =>
            (
              fallback,
              Some(
                s"ui-state ui_$gid='$raw' is not a valid tab index " +
                  s"(0..${n - 1}); using $fallback"
              )
            )
        }
    }
  }

  /** What a connection seeds its open set with, so baked panels receive live
    * updates from the first paint.
    *
    * STATE-selected members are excluded entirely: they never enter a session's
    * open set, because their liveness belongs to the shared per-slug pass. That
    * exclusion is what `Patches.Addressed` relies on — tagging a patch with a
    * state surface would hide it from everybody.
    */
  def selectedSurfaces(
      uiState: Map[String, String] = Map.empty
  ): Set[String] = {
    val (baked, unbaked) =
      dashboard.surfaces.toList.partition(_._2.bakeInto.isDefined)
    val fromGroups =
      baked
        .flatMap(_._2.bakeInto)
        .distinct
        .filterNot(isStateGroup)
        .map(gid => bakeGroup(gid)(resolveActive(gid, uiState)._1))
        .toSet
    val fromUnbaked =
      unbaked.collect { case (sid, s) if defaultOpenUser(s) => sid }.toSet
    fromGroups ++ fromUnbaked ++ openPopup(uiState)
  }

  /** The popup host is a selection like any other — `ui_<hostId>`, set by the
    * open/close taps exactly as a tab button sets `ui_<id>` — so it needs no
    * channel of its own. Its VALUE is a surface id rather than a member index,
    * because the host is not a bake group: any registered surface can appear
    * there, one at a time.
    *
    * The narrowing is the whole reason this is a method. A claim can name a
    * surface this dashboard renamed, removed, or never had (a stale URL,
    * another dashboard's dialog), and adopting one would put a session in a
    * state its renderer cannot serve.
    */
  def openPopup(uiState: Map[String, String]): Option[String] =
    uiState
      .get(Dashboard.PopupHostId)
      .filter(_.nonEmpty)
      .filter(sid =>
        dashboard.surfaces.get(sid).exists(_.hostId == Dashboard.PopupHostId)
      )

  /** Returns data rather than logging, so the renderer stays side-effect-free.
    * A value naming a state-selected group is ignored — no client choice exists
    * there to be malformed.
    */
  def uiStateAnomalies(uiState: Map[String, String]): List[String] =
    dashboard.surfaces.toList
      .flatMap(_._2.bakeInto)
      .distinct
      .filterNot(isStateGroup)
      .flatMap(gid => resolveActive(gid, uiState)._2)

  /** The main page's nodes binding `entityId` — materialised members included,
    * which is the whole point of materialising them: a member re-renders
    * because something it binds moved, exactly as a static component does.
    *
    * That covers a case slot naming a SECOND entity, which was authorable and
    * silently never ticked: the only selector was the group's query, and a
    * change to an entity the query does not match touches no group.
    */
  def componentsFor(entityId: String): Set[NodeId] =
    mainIndex.byEntity.getOrElse(entityId, Set.empty) ++ membersBinding(
      entityId,
      ""
    )

  /** Members of groups rooted in `root` that bind `entityId`. */
  private def membersBinding(entityId: String, root: String): Set[NodeId] =
    graph.get.byEntity
      .getOrElse(entityId, Vector.empty)
      .collect { case m if m.root == root => m.id }
      .toSet

  /** Empty for a dynamic group — its members are per-entity children with ids
    * of their own — and for an unknown id.
    */
  def entitiesForNode(id: NodeId): List[String] =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _, _)) => c.liveEntities
      case _ => graph.get.byId.get(id).toList.flatMap(_.node.liveEntities)
    }

  def surfaceComponentsFor(surfaceId: String, entityId: String): Set[NodeId] =
    surfaceIndexes
      .get(surfaceId)
      .fold(Set.empty)(_.byEntity.getOrElse(entityId, Set.empty)) ++
      membersBinding(entityId, surfaceId)

  def surface(surfaceId: String): Option[Surface] =
    dashboard.surfaces.get(surfaceId)

  /** `<link>`-ed by the page, e.g. BeerCSS. */
  def stylesheets: List[String] = dashboard.theme.stylesheets

  /** Injected as `<script type="module">`, e.g. beer.min.js. */
  def scripts: List[String] = dashboard.theme.scripts

  /** Inlined as classic `<script>`, e.g. the slider's press-and-hold gate. */
  def inlineScripts: List[String] = dashboard.theme.inlineScripts

  /** `None` falls back to the slug, at the caller. */
  def title: Option[String] = dashboard.title

  /** The theme as one id'd `<style>` element: design tokens as `:root` custom
    * properties (dark overrides under `@media (prefers-color-scheme: dark)`, so
    * the page follows the browser) followed by the theme's inline `styles`.
    * Empty when the theme carries no tokens or styles.
    *
    * Deliberately OUTSIDE `#dashboard`, i.e. not part of [[renderBody]]. Riding
    * inside the repainted body would let a reload or navigate swap it too, but
    * it is static and it is BIG: on a small demo dashboard it is 7.7 KB of the
    * 9.6 KB a repaint sends, which would be re-transmitted on every reconnect
    * that cannot resume. So it is sent only when it actually changed, morphed
    * by its stable id ([[Renderer.ThemeStyleId]]) — on a navigate to a
    * differently-themed dashboard, and on a reconnect whose [[styleHash]] no
    * longer matches (`Server.headPatches`).
    */
  val themeStyleTag: String = {
    val theme = dashboard.theme
    def vars(tokens: Map[String, String]): String =
      tokens.toList
        .sortBy(_._1)
        .map { case (name, value) => s"--$name:$value;" }
        .mkString

    val parts = List(
      if (theme.tokens.isEmpty) ""
      else s":root{color-scheme:light dark;${vars(theme.tokens)}}",
      if (theme.tokensDark.isEmpty) ""
      else
        s"@media (prefers-color-scheme:dark){:root{${vars(theme.tokensDark)}}}",
      theme.styles
    ).filter(_.nonEmpty)

    // Always the element, even when empty: a navigate into a themed dashboard
    // needs something to morph, and morphing an id that isn't there is a no-op
    // the client reports as an error.
    parts.mkString(
      s"""<style id="${Renderer.ThemeStyleId}">""",
      "",
      "</style>"
    )
  }

  /** Owns the `#dashboard` swap target and, for a theme that uses popups, the
    * popup host's placement. An empty `theme.chrome` falls back to a minimal
    * frame with no popup host — see [[Theme.chrome]] for the contract.
    */
  private val chromeTemplate: Template = {
    val chrome =
      if (dashboard.theme.chrome.nonEmpty) dashboard.theme.chrome
      else """<main class="container" id="dashboard">{{{body}}}</main>"""
    Templates.compiler.compile(chrome)
  }

  /** Without the page shell and without the theme ([[themeStyleTag]] sits
    * outside it): what a repaint or navigate `inner`-patches into `#dashboard`.
    */
  def renderBody(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): String =
    renderBodyTraced(states, uiState).html

  /** [[renderBody]] with the per-node trace — including every surface BAKED
    * into it, which is what a page load needs to seed the log for the surfaces
    * the client can already see.
    */
  private[runtime] def renderBodyTraced(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Traced =
    traced(dashboard.card, Nil, "", states, uiState)

  /** The style sits BEFORE the chrome, so every patch target inside it can be
    * repainted without re-sending the CSS.
    *
    * A restored `popup` is BAKED into the host's `{{{popups}}}` hole, the way a
    * selected tab panel is baked into its owner: otherwise the dialog cannot
    * appear until the stream connects and patches it in, which a refresh sees
    * as the dashboard painting first and the dialog popping in late. A theme
    * whose chrome has no hole renders without it — the var is optional in the
    * contract, and the patch still arrives.
    */
  def renderPage(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      popup: Option[String] = None
  ): String = renderPageTraced(states, uiState, popup).html

  /** The page is the one render that must tell the log what it did. Every other
    * node starts with NO entry, which reads as "you are up to date" and is
    * true: the document was server-rendered from current state. An open surface
    * is the exception — with no entry, the first live tick would hand the
    * client its own surface straight back.
    */
  private[runtime] def renderPageTraced(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      popup: Option[String] = None
  ): Traced = {
    val body = renderBodyTraced(states, uiState)
    val dialog = popup.flatMap(renderSurfaceTraced(_, states, uiState))
    Traced(
      themeStyleTag + chromeTemplate.execute(
        Renderer.javaContext(
          Map(
            "body" -> body.html,
            // The dialog a refresh is restoring, baked into the host exactly as
            // the connect would patch it — same render, so the two are
            // byte-identical and the later patch is a no-op morph.
            "popups" -> dialog.fold("")(_.html)
          ),
          Nil
        )
      ),
      body.own ++ dialog.fold(Map.empty[NodeId, String])(_.own)
    )
  }

  /** Bare content, with no wrapper: every surface is chrome-less, because the
    * host it swaps into and any frame around it live in `theme.chrome` rather
    * than per-surface.
    */
  def renderSurface(
      surfaceId: String,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[String] =
    renderSurfaceTraced(surfaceId, states, uiState).map(_.html)

  /** [[renderSurface]] with the per-node trace — what a FILL uses, so the log
    * learns what the fill put in each node without re-rendering the subtree.
    */
  private[runtime] def renderSurfaceTraced(
      surfaceId: String,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[Traced] =
    dashboard.surfaces.get(surfaceId).map { s =>
      traced(s.content, Nil, Renderer.surfacePrefix(surfaceId), states, uiState)
    }

  /** `uiState` is threaded through so a node that owns a bake group — a `tabs`
    * host that also binds a live entity — re-bakes the viewer's selected member
    * on a live patch rather than the default one.
    */
  def renderNodeById(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[String] =
    memberAt(id, states)
      .map(renderMember(_, states))
      .orElse(renderIndexed(id, states, uiState))

  private def renderIndexed(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): Option[String] =
    allIndexed.get(id).filter(_ => hasOwnRendering(id)).map {
      // A card that declares a `self` patches through THAT element alone — no
      // cell wrapper (the cell contains the mount) and no mount. Statement (1)
      // made structural rather than enforced by suppression: the fragment
      // simply cannot carry what the mount holds. Children DO ride along — a
      // tab bar's buttons are the card's own rendering, not mounted content.
      case (c: LayoutNode.Component, path, prefix) if hasSelf(c.card) =>
        renderTemplateOf(
          templates.selves(c.card),
          structuralVars(id) ++ resolveBakeTraced(id, uiState, states)._2,
          c.slots,
          c.children.zipWithIndex.map { case (child, i) =>
            render(child, path :+ i, prefix, states, uiState)
          },
          states
        )
      case (node, path, prefix) =>
        render(node, path, prefix, states, uiState)
    }

  /** `s_<sid>__c` — what a state group's mount holds, and so what a flip
    * removes or places. The same scheme the build-phase hoist uses, so a
    * branch's build-time id and the id a flip records are one story.
    */
  def surfaceContentId(surfaceId: String): NodeId =
    LayoutNode.nodeId(Renderer.surfacePrefix(surfaceId), Nil)

  /** Which surfaces share `host` as their mount — the eviction group a swap
    * replaces.
    */
  def surfacesAt(host: DomId): Set[String] =
    dashboard.surfaces.collect {
      case (sid, s) if s.hostId == host => sid
    }.toSet

  /** The resume path's SECOND candidate set. A surface a client has open holds
    * nodes the cursor alone would not name, because nothing may have rendered
    * that surface at all while nobody was viewing it — so the log has no
    * version to compare and only re-rendering can tell whether the DOM is
    * current.
    */
  def surfaceNodeIds(surfaceId: String): Set[NodeId] =
    surfaceIndexes.get(surfaceId).fold(Set.empty)(_.indexed.keySet)

  /** In DOM order. Paired rather than concatenated because a fill owes the log
    * a digest per member: the mount's contents are re-supplied wholesale, so
    * the next live diff must compare against what this fill actually put there.
    */
  def renderDynamicMembers(
      groupId: NodeId,
      states: Map[String, EntityState]
  ): List[(NodeId, String)] =
    membersOf(groupId, states).toList.map(m => m.id -> renderMember(m, states))

  /** Decides how a mount is patched. A state group's mount holds at most ONE
    * member (a bake group has one hole), so there are no siblings to preserve
    * and no position to fix: overwriting it IS the delta. A dynamic group's is
    * the opposite, and gets per-member `remove`/`before`.
    */
  def isDynamicContainer(id: NodeId): Boolean = memberSources.contains(id)

  /** What a wholesale FILL carries, for EITHER kind of container: a dynamic
    * group's members, or a state group's one active branch. Both are "what is
    * in this mount", so they answer here rather than at each fill site.
    */
  def renderMount(
      container: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): List[(NodeId, String)] =
    memberSources.get(container) match {
      case Some(_) => renderDynamicMembers(container, states)
      case None    =>
        resolveActiveByState(container, states)
          .flatMap(bakeMembers(container).lift)
          .flatMap(sid =>
            renderSurface(sid, states, uiState).map(surfaceContentId(sid) -> _)
          )
          .toList
    }

  /** The inverse of [[selectedSurfaces]]. `open` is LIVE truth — a tab click
    * moves it mid-connection — where a connection's captured `uiState` is only
    * what it arrived with. Anything rendering for a client after connect must
    * read the selection from here, or it renders the tab that client was on
    * when it opened the page.
    */
  def uiStateFrom(open: Set[String]): Map[String, String] =
    userBakeOwnerIds.toList.flatMap { gid =>
      bakeGroup(gid).indexWhere(open) match {
        case -1 => None
        case i  => Some(gid -> i.toString)
      }
    }.toMap

  /** Every LOG KEY must be resolvable here, because the log holds a digest
    * rather than HTML and a resume renders its candidates instead of reading
    * them back. It is [[renderNodeById]] and nothing else: a dynamic group's
    * member is a node in the graph like any other, which is what this method's
    * second case used to exist for.
    *
    * `uiState` is the viewer this render is FOR. A node whose own markup reads
    * its own selection has one rendering per member, so rendering it without a
    * viewer hands everybody the default member's — on a resume, that is a
    * client's own tab flipped out from under it.
    *
    * `None` means the key names nothing that exists now (its group is gone, the
    * entity is no longer a member), which is exactly when there is nothing to
    * send. That it cannot crash is the point: an unresolvable key must be
    * dropped rather than take the resume with it.
    */
  def renderLogged(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[String] = renderNodeById(id, states, uiState)

  /** The backend-injected structural template vars for one node — the ids an
    * author never composes.
    *
    * ONE derivation, deliberately, because there are two places a node id comes
    * from ([[LayoutNode.pathId]] for the static tree, [[dynamicChildId]] for a
    * group member), and injecting their vars separately means a var added to
    * one silently misses the other. The rule this makes true:
    *
    * > Structural vars are a pure function of the node id in scope.
    *
    * So a container card used as a dynamic case gets `selfId`/`mountId` off its
    * member id for free, with no per-call-site knowledge. `bakeIndex` is NOT
    * here: it is a function of the client's selection, not of the id, and it
    * belongs to the document path alone ([[resolveBakeTraced]]).
    */
  private def structuralVars(id: NodeId): Map[String, String] =
    Map(
      "id" -> id,
      "selfId" -> Renderer.selfElementId(id),
      "mountId" -> mountId(id)
    )

  /** Whether this node HAS a rendering of its own — the thing that decides
    * whether it may be a log key or a patch target at all.
    *
    * The property, not a card shape: **a node's own rendering must contain no
    * mount** — its own, or one belonging to markup that rides along inside it.
    * A mount's contents are whichever member that client selected, so a node
    * carrying one has bytes that differ per viewer.
    *
    * Three shapes fail it, and only the first two are what a card's own
    * definition tells you:
    *
    *   - a BARE container — a mount and no `self` — whose markup is a constant
    *     `.fh-cell` wrapper around a hole;
    *   - a DYNAMIC group root, which composes its members (each addressable in
    *     its own right) rather than having markup of its own;
    *   - anything whose CHILDREN bring a mount along — a pre-split container
    *     splicing `{{#children}}` into its `template`, or a custom card with a
    *     `self` and a bake-owning child. Neither is reachable from the shipped
    *     library, and neither is visible to a test on the card alone, which is
    *     why this asks about the rendering instead.
    *
    * Neither loses anything by being excluded: their children are addressable
    * in their own right.
    *
    * The same rule `Dashboard.validate` enforces when it rejects a live-entity
    * slot on a bare container: no patch target.
    *
    * They keep their [[elementId]] — a structural patch still names them, a
    * `remove` deletes that element and an `insert` anchors before it. What they
    * lose is being rendered BY ID.
    */
  private def hasOwnRendering(id: NodeId): Boolean =
    allIndexed.get(id).exists {
      case (c: LayoutNode.Component, _, _) =>
        // What `renderNodeById` would produce: a card with a `self` renders
        // that element plus — only if the self SPLICES them — its children's
        // full renderings; anything else renders its whole card, its own mount
        // included.
        //
        // A self that leaves its children entirely to the mount (a slider
        // holding member sliders) is unaffected by what those children carry:
        // its bytes never contain them. Asking `children.exists(carriesMount)`
        // unconditionally cost exactly that node its live updates the moment a
        // member card gained a mount of its own.
        if (hasSelf(c.card))
          !(templates.selvesCarryChildren(c.card) &&
            c.children.exists(carriesMount))
        else !carriesMount(c)
      // A member container composes its members and renders nothing of its
      // own; the members are the log keys.
      case (_: LayoutNode.SetNode, _, _) => false
    }

  /** Whether rendering this node in FULL — as a parent's markup embeds it —
    * brings a mount along, its own or a descendant's.
    *
    * A member container does not count: a member renders with no bake group, so
    * a member card's mount comes out empty and carries nobody's selection.
    */
  private def carriesMount(node: LayoutNode): Boolean = node match {
    case c: LayoutNode.Component =>
      templates.mounts.contains(c.card) || c.children.exists(carriesMount)
    case _: LayoutNode.SetNode => false
  }

  /** The ONE predicate the self/mount split turns on: it picks what the patch
    * path renders, what [[patchTargetId]] returns, and so what the diff
    * compares. The three can never disagree.
    */
  private def hasSelf(card: String): Boolean = templates.selves.contains(card)

  /** The DOM element a patch for `id` targets — the ONE crossing from node id
    * to DOM id, and one-way.
    *
    * A container declaring a `self` targets `<id>-self`, so its patch cannot
    * reach the sibling mount holding its children; everything else targets its
    * own element. `-` is safe as the separator because [[Dashboard.sanitize]]
    * maps everything outside `[A-Za-z0-9_]` to `_`, so no generated node id can
    * contain one and no `startsWith(id + "_")` ancestry test can mistake
    * `c_2-self` for a child of `c_2`.
    */
  def patchTargetId(id: NodeId): DomId =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _, _)) if hasSelf(c.card) =>
        Renderer.selfElementId(id)
      case _ => elementId(id)
    }

  /** The node's OWN root element — the `.fh-cell` wrapper `render` emits. What
    * a structural patch names: the thing a `remove` deletes and an `insert`
    * anchors `before`. Distinct from [[patchTargetId]] on purpose: once a
    * container patches its `self` alone, removing that element would leave the
    * mount and its children standing, so "what I morph" and "what I am" stop
    * being the same element.
    */
  def elementId(id: NodeId): DomId = DomId.derived(id)

  /** The element a node's children live IN — an `Inner`/`append` target, and
    * the `{{mountId}}` a container's `mount` part writes.
    *
    * '''This is not a new id — for a bake owner it IS
    * [[fh.view.model.Surface.hostId]]''', so `Tabs` resolves to `c_2_panel`,
    * byte-identical to the `id="{{id}}_panel"` a template would otherwise
    * hardcode. That removes a duplication rather than adding one: the
    * alternative has Pkl and Scala deriving the same string independently, with
    * nothing checking they agree.
    *
    * A mount needs an id only where something FILLS it, which is exactly where
    * `bakeAs` already names it (a tab panel, an `If` branch). `Grid`/`Row`/
    * `Column` mounts are never fill targets — their children arrive nested — so
    * they fall back to the node's own id and simply never use it.
    */
  def mountId(id: NodeId): DomId =
    bakeGroup(id).headOption
      .flatMap(dashboard.surfaces.get)
      .map(_.hostId)
      .getOrElse(elementId(id))

  /** `(baked, structural, trace)` for a component that owns a bake group: the
    * SELECTED member as its `{{{bakeAs}}}` var, so the host renders the active
    * panel on first paint, plus `bakeIndex` so a tabs template can seed its
    * signal. Selection dispatches on activation mode — [[resolveActive]] for
    * user groups, [[resolveActiveByState]] for state groups.
    *
    * Baking happens exactly as a later open/switch/flip would wrap it, so first
    * paint and switch-back are byte-identical and the first live patch is a
    * no-op morph. No bake group leaves all three empty; absent Mustache vars
    * render as empty anyway.
    */
  private def resolveBakeTraced(
      id: NodeId,
      uiState: Map[String, String],
      states: Map[String, EntityState]
  ): (Map[String, String], Map[String, String], Map[NodeId, String]) = {
    val group = bakeGroup(id)
    def bakeMember(
        idx: Int
    ): (Map[String, String], Map[String, String], Map[NodeId, String]) = {
      val sid = group(idx)
      val s = dashboard.surfaces(sid)
      // A baked member's nodes are part of what this render puts on screen, so
      // its trace joins this one's — that is how a page load fingerprints the
      // surfaces it baked without walking them again.
      val member = renderSurfaceTraced(sid, states, uiState)
      (
        Map(s.bakeAs.getOrElse("") -> member.fold("")(_.html)),
        Map("bakeIndex" -> idx.toString),
        member.fold(Map.empty[NodeId, String])(_.own)
      )
    }
    if (group.isEmpty) (Map.empty, Map.empty, Map.empty)
    else
      activeBakeIndex(id, uiState, states) match {
        case Some(idx) => bakeMember(idx)
        case None      =>
          // A state group with no matching branch: the host's {{{bakeAs}}} var
          // is explicitly the empty string (all members share one bakeAs — they
          // bake into one hole), so the wrapper renders empty instead of
          // leaving the var absent-but-meaningful.
          val as = group.headOption
            .flatMap(sid => dashboard.surfaces.get(sid).flatMap(_.bakeAs))
            .getOrElse("")
          (Map(as -> ""), Map.empty, Map.empty)
      }
  }

  /** WHICH member of `id`'s bake group is selected, dispatching on activation
    * mode — the one thing a bake owner's own rendering reads beyond its slots.
    * `None` for a node that owns no group, and for a state group whose branches
    * all fail.
    *
    * Split out of [[resolveBakeTraced]] because [[renderInputs]] needs the
    * selection WITHOUT rendering the member it selects. Sharing it is what
    * makes the cache key honest: a key derived independently could drift from
    * the render it claims to describe.
    */
  private def activeBakeIndex(
      id: NodeId,
      uiState: Map[String, String],
      states: Map[String, EntityState]
  ): Option[Int] =
    if (bakeGroup(id).isEmpty) None
    else if (isStateGroup(id)) resolveActiveByState(id, states)
    else Some(resolveActive(id, uiState)._1)

  /** Everything a node's OWN rendering reads, as a comparable value — the key a
    * render cache needs
    * (docs/adr/0012-each-session-renders-what-it-is-owed.md).
    *
    * Two parts, and the split is the point:
    *
    *   - `entities` — the CONTENT VERSION of each entity the node's slots bind
    *     ([[entitiesForNode]]). A version rather than the value because
    *     [[EntityState]]'s synthesized `hashCode` recurses into the attribute
    *     map on every lookup, and because it is MORE discriminating than the
    *     render is: `lastUpdated` moves on ticks that change no rendered byte.
    *     An entity the snapshot does not hold has NO entry, which is a distinct
    *     key from any version it could have — [[resolveSlot]] renders such a
    *     slot from a synthetic empty state.
    *   - `vars` — the structural vars the bake group contributes (`bakeIndex`),
    *     taken as the RESOLVED value rather than the inputs it derives from.
    *     That is what keeps the key small where it could not otherwise be: a
    *     state group's branch is a QUANTIFIED predicate over the whole entity
    *     map ([[holds]]), so keying on its sources would key on every entity.
    *
    * Deliberately NOT included: the node's children. Including them would make
    * any descendant's tick invalidate every ancestor to the root, which is the
    * whole reason a per-node cache earns anything.
    *
    * `None` — NOT CACHEABLE — is what keeps that honest, and it is the reason
    * this returns an `Option` rather than a key that a caller has to know not
    * to trust. Two nodes get it:
    *
    *   - one with no rendering of its own ([[hasOwnRendering]]): a bare
    *     container or a dynamic group root, which composes rather than renders
    *     and is not addressable at all;
    *   - one whose OWN bytes carry its children — [[renderNodeById]] splices
    *     them eagerly, so its bytes move when a child's entity moves while this
    *     key stands still. In the shipped library that is exactly `Tabs`, whose
    *     `self` is the bar and whose children are the tab buttons.
    *
    * The second could be turned into a `Some` by rendering own markup with
    * HOLES where the children go and substituting their (separately keyed)
    * bytes in a second pass — [[renderTemplateOf]] taking `childrenHtml` is the
    * seam. That is an optimisation, not a precondition: such nodes are a
    * handful and are not the hot path. Excluding them costs their renders and
    * can never be wrong; including them without the split would be silently
    * wrong.
    */
  def renderInputs(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): Option[RenderInputs] =
    memberAt(id, states)
      .map(m =>
        RenderInputs(
          // The SUBJECT is in the key whether or not a slot reads it: the
          // materialised node is state-derived, so the entity that chose its
          // case has to be able to invalidate the bytes that case produced.
          versions(m.node.subjectEntity.toList ++ m.node.liveEntities, states),
          Map.empty
        )
      )
      .orElse(
        Option
          .when(hasOwnRendering(id) && !ownBytesCarryChildren(id))(
            RenderInputs(
              versions(entitiesForNode(id), states),
              activeBakeIndex(id, uiState, states)
                .fold(Map.empty[String, String])(i =>
                  Map("bakeIndex" -> i.toString)
                )
            )
          )
      )

  private def versions(
      entities: List[String],
      states: Map[String, EntityState]
  ): Map[String, Long] =
    entities.distinct
      .flatMap(e => states.get(e).map(e -> _.contentVersion))
      .toMap

  private def ownBytesCarryChildren(id: NodeId): Boolean =
    allIndexed.get(id).exists {
      case (c: LayoutNode.Component, _, _) => c.children.nonEmpty
      case _                               => false
    }

  private def render(
      node: LayoutNode,
      path: List[Int],
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): String = traced(node, path, idPrefix, states, uiState).html

  /** The composed rendering, and every node's OWN html inside it.
    *
    * The walk already computes both — a card's `self` is built and then spliced
    * into `template` — so the trace is a matter of not discarding it. Anything
    * needing per-node bytes after a wholesale render (a fill recording what it
    * put in a mount, the page seeding the log for its open surfaces) would
    * otherwise walk the whole subtree a SECOND time, node by node.
    *
    * `own` carries an entry only for nodes that have a rendering of their own
    * ([[hasOwnRendering]]) — the same set that may be a log key — and its bytes
    * are what [[renderNodeById]] would return for that id, which is what makes
    * the digests comparable at all.
    */
  private[runtime] case class Traced(html: String, own: Map[NodeId, String])

  private def traced(
      node: LayoutNode,
      path: List[Int],
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): Traced =
    node match {
      case c: LayoutNode.Component =>
        val id = LayoutNode.nodeId(idPrefix, path)
        val kids = c.children.zipWithIndex.map { case (child, i) =>
          traced(child, path :+ i, idPrefix, states, uiState)
        }
        val childrenHtml = kids.map(_.html)
        val (baked, bakeIndex, bakedTrace) =
          resolveBakeTraced(id, uiState, states)
        // The document path renders the whole card: its two parts first, then
        // `template` with them spliced in. A leaf card has neither part, so its
        // `template` renders exactly as before.
        //
        // The `self` sees the structural vars AND `bakeIndex` — precisely what
        // `renderNodeById` gives it, which is what lets the trace be captured
        // here and compared there. NOT the baked member itself: that is the
        // mount's contents, and statement (1) is that a node's own rendering
        // never carries them.
        val selfVars = structuralVars(id) ++ bakeIndex
        val selfHtml = templates.selves
          .get(c.card)
          .map(renderTemplateOf(_, selfVars, c.slots, childrenHtml, states))
        val vars = selfVars ++ baked
        val mountHtml = templates.mounts
          .get(c.card)
          .map(renderTemplateOf(_, vars, c.slots, childrenHtml, states))
        val html = renderTemplate(
          c.card,
          vars ++ Map(
            "self" -> selfHtml.getOrElse(""),
            "mount" -> mountHtml.getOrElse("")
          ),
          c.slots,
          childrenHtml,
          states
        )
        // EVERY node is a cell — containers included. The backend owns the id'd
        // `.fh-cell` wrapper, so templates never carry `id="{{id}}"` themselves
        // and authored `cell` classes (fh-cols-*, …) ride on it.
        //
        // The one exception is a card that opted out via
        // `CardDef.wrapAsCell = false`, which means exactly one thing: my root
        // must not be wrapped in a layout box (the tab anchors, which must stay
        // direct children of BeerCSS's `.tabs`). It does NOT imply "never a
        // morph target" — that is decided by card shape.
        //
        // A bake owner gets a wrapper like anything else, because the
        // self/mount split separates the two roles: the cell is the layout
        // item, the `self` element is the patch target. Conflating them denies
        // `Tabs`/`If` a cell and silently drops `.columns(n)` on them.
        val wrapped =
          if (noWrapCards(c.card)) html
          else
            s"""<div class="fh-cell${Renderer.cellClasses(
                c.cell
              )}" id="$id">$html</div>"""
        // What this node contributes to the trace: its `self` when it has one,
        // otherwise its whole (wrapped) rendering when it holds no mount, and
        // nothing at all when it is a bare container. Mirrors `renderNodeById`
        // exactly — including the wrapper, which that method's leaf branch also
        // returns.
        val ownHtml =
          if (hasOwnRendering(id)) selfHtml.orElse(Some(wrapped)) else None
        Traced(
          wrapped,
          kids.foldLeft(bakedTrace)(_ ++ _.own) ++ ownHtml.map(id -> _)
        )
      // A container root composes its members and so has no own rendering; the
      // members do, and they are what a fill must fingerprint.
      case s: LayoutNode.SetNode =>
        val id = LayoutNode.nodeId(idPrefix, path)
        Traced(
          renderDynamic(id, s.cell, states),
          renderDynamicMembers(id, states).toMap
        )
    }

  private def renderDynamic(
      id: NodeId,
      cell: Option[Cell],
      states: Map[String, EntityState]
  ): String = {
    val children = membersOf(id, states).map(renderMember(_, states))
    // The group root is itself a cell (a first-class layout item in its
    // container) plus `.fh-group`, the themed flow container its per-entity
    // member cells live in. Authored `cell` classes (e.g. `fh-cols-full` to
    // span a parent grid) ride on it.
    s"""<div class="fh-cell fh-group${Renderer.cellClasses(
        cell
      )}" id="$id">${children.mkString}</div>"""
  }

  /** The stable id of one dynamic-group member (`<groupId>_<slug>`), the
    * outer-morph / insert / remove target for a single member.
    *
    * Derived from the member's KEY, never from its position: a positional id
    * would rename every node below an arrival, which is exactly what a
    * per-member delta exists to avoid. "One member is one entity" is a property
    * of the predicate engine, not of the id scheme — [[MemberKey]] is already a
    * sum, so a group whose unit of membership becomes something else needs no
    * new id story.
    */
  private def memberId(groupId: NodeId, key: MemberKey): NodeId =
    NodeId.derived(s"${groupId}_${Renderer.sanitize(sortKey(key))}")

  /** [[memberId]] for the entity case — the form the Server's per-entity patch
    * path and the resume's anchors name.
    */
  def dynamicChildId(groupId: NodeId, entityId: String): NodeId =
    memberId(groupId, MemberKey.Entity(entityId))

  /** The entity ids a dynamic group currently renders as children, in DOM order
    * (sorted by entity id). A member is an entity that passes the group's
    * `query` AND matches one of its `cases` — an entity matching the query but
    * no case renders nothing, so it is not a member.
    *
    * Read off the GRAPH rather than re-derived from `states`: membership is
    * maintained by the state stream ([[syncMembers]]), and `states` is here
    * only to answer for a group the stream has not reached yet. Unknown /
    * non-dynamic id ⇒ empty.
    */
  def dynamicMembers(
      groupId: NodeId,
      states: Map[String, EntityState]
  ): List[String] = groupOf(groupId, states).entities

  /** Render ONE dynamic-group member by its entity — the by-key accessor into
    * the graph. `None` when the group id is unknown/non-dynamic or the entity
    * is not a current member.
    */
  def renderDynamicChild(
      groupId: NodeId,
      entityId: String,
      states: Map[String, EntityState]
  ): Option[String] =
    membersOf(groupId, states)
      .find(_.key == MemberKey.Entity(entityId))
      .map(renderMember(_, states))

  /** A materialised member's own bytes.
    *
    * Every member gets the SAME id'd `.fh-cell` wrapper as a static component,
    * so it is an addressable patch target (in-place morph / insert / remove)
    * rather than only ever re-rendered as part of the whole group — which is
    * why the wrap here is UNCONDITIONAL (a `wrapAsCell = false` card has no
    * member morph target and is not usable as a dynamic case). It renders WHOLE
    * rather than through the self/mount split, because a member composes
    * nothing: its children are empty and its mount would carry nobody's
    * selection.
    */
  private def renderMember(
      m: Member,
      states: Map[String, EntityState]
  ): String = {
    val html = renderWhole(
      m.node.card,
      structuralVars(m.id),
      m.node.slots,
      // A member may render a SUBTREE — a card with children, not only a leaf.
      // Ordinary children come out INSIDE the member's bytes with no ids of
      // their own, so the member is the single patch target for them and a
      // child's entity changing re-renders it (which is why
      // [[Member.entitiesOf]] walks them). A nested SET is the exception: it
      // is addressable, and [[Member.entitiesOf]] stops there.
      m.node.children.zipWithIndex.map { case (child, i) =>
        memberChild(m, child, List(i), m.clause, states)
      },
      states
    )
    s"""<div class="fh-cell${Renderer.cellClasses(
        m.node.cell
      )}" id="${m.id}">$html</div>"""
  }

  /** One node inside a member. Ordinary children render whole and unaddressed —
    * the member is their patch target. A nested SET is the exception: it is an
    * addressable container of its own, so it renders as its group element and
    * its members are patched individually rather than through the tile.
    *
    * That split is the synthesised `self`/`mount`: the tile's own bytes are
    * everything except the inner group, and the inner group is the mount. It
    * needs no template support because the tile's own content is static —
    * `Dashboard.validate` already refuses a live slot on a container with no
    * `self`, and a room's NAME is a registry fact, hence a literal.
    */
  private def memberChild(
      m: Member,
      node: LayoutNode,
      path: List[Int],
      clauseIdx: Int,
      states: Map[String, EntityState]
  ): String = node match {
    case c: LayoutNode.Component =>
      val html = renderWhole(
        c.card,
        structuralVars(m.id),
        c.slots,
        c.children.zipWithIndex.map { case (child, i) =>
          memberChild(m, child, path :+ i, clauseIdx, states)
        },
        states
      )
      s"""<div class="fh-cell${Renderer.cellClasses(c.cell)}">$html</div>"""
    case inner: LayoutNode.SetNode =>
      renderDynamic(innerSetId(m.id, clauseIdx, path), inner.cell, states)
  }

  private def renderTemplate(
      cardName: String,
      injected: Map[String, String],
      slots: Map[String, SlotSource],
      childrenHtml: List[String],
      states: Map[String, EntityState]
  ): String =
    templates.components.get(cardName) match {
      case None =>
        // Unreachable by construction: Dashboard.validate resolves every card
        // reference before a Renderer is built.
        throw new IllegalStateException(
          s"unknown card '$cardName' — validate should have rejected this dashboard"
        )
      case Some(tpl) =>
        renderTemplateOf(tpl, injected, slots, childrenHtml, states)
    }

  /** The DOCUMENT path's card render: both parts, then `template` with them
    * spliced into its `{{{self}}}`/`{{{mount}}}` holes. A leaf card has
    * neither, so its `template` renders exactly as it always did.
    *
    * The one place a mount is rendered as part of its own node, which is why
    * the document path can hand a client fully-populated mounts on first paint
    * while the patch path never touches one.
    */
  private def renderWhole(
      cardName: String,
      vars: Map[String, String],
      slots: Map[String, SlotSource],
      childrenHtml: List[String],
      states: Map[String, EntityState]
  ): String = {
    def part(of: Map[String, Template]): String =
      of.get(cardName)
        .fold("")(renderTemplateOf(_, vars, slots, childrenHtml, states))
    renderTemplate(
      cardName,
      vars ++ Map(
        "self" -> part(templates.selves),
        "mount" -> part(templates.mounts)
      ),
      slots,
      childrenHtml,
      states
    )
  }

  /** Render an already-resolved template — `template`, `self` or `mount` — with
    * the same slot resolution for all three, so a card's parts can never
    * disagree about what a slot means.
    */
  private def renderTemplateOf(
      tpl: Template,
      injected: Map[String, String],
      slots: Map[String, SlotSource],
      childrenHtml: List[String],
      states: Map[String, EntityState]
  ): String = {
    // The card's subject entity: the `entity_id` slot resolved against its
    // OWN entity (it DEFINES the subject, so it never inherits it). Normally
    // a literal; a transform form (indirection) grounds on its own entityId.
    val subject: Option[String] =
      slots.get("entity_id").map { s =>
        s.literal.getOrElse(resolveSlot(s.entityId, s, states))
      }
    val resolved = slots.map { case (slot, source) =>
      val value = source.literal match {
        // A constant literal: used verbatim, reading no entity and running no
        // transform — the cheap path for a hardcoded label/action.
        case Some(text) => text
        case None       =>
          // A slot's entity is its own `entityId`, or the subject when it
          // leaves it unset (slot-level inheritance — the card's entity, or
          // the matched entity in a dynamic case). The `entity_id` slot
          // itself never inherits — it is the subject.
          val srcEntity =
            if (slot == "entity_id") source.entityId
            else source.entityId.orElse(subject)
          // A `reactive: false` slot is identity-derived — its transform
          // reads only `$domain`/`$entity_id` (a service action, the
          // slider's domain config), both immutable for the life of the
          // entity. So its value never changes: resolve it ONCE per
          // (entity, transform) and reuse forever. This is what keeps the
          // dynamic render path slick — a dynamic group re-renders every
          // matched card on every event, but those cards' action/config
          // slots become a cache lookup, not a JSONata eval. Live slots
          // (`reactive: true`) always re-resolve. `$entity_id` is in the key
          // (the action URL embeds it), so two entities never collide.
          if (!source.reactive)
            identityCache.computeIfAbsent(
              (srcEntity.getOrElse(""), source.transform),
              _ => resolveSlot(srcEntity, source, states)
            )
          else resolveSlot(srcEntity, source, states)
      }
      slot -> value
    }
    tpl.execute(Renderer.javaContext(injected ++ resolved, childrenHtml))
  }

  /** Resolve a non-literal slot's value against its producing entity's state.
    * It resolves even before any state has arrived (so a `$domain` action still
    * resolves); with no entity at all the state is empty.
    */
  private def resolveSlot(
      srcEntity: Option[String],
      source: SlotSource,
      states: Map[String, EntityState]
  ): String = {
    val st =
      srcEntity
        .flatMap(states.get)
        .getOrElse(EntityState(srcEntity.getOrElse(""), "", Map.empty))
    // An unavailable/unknown entity on a value-display slot shows its raw state
    // and never enters the transform — that bypass, not the transform, is what
    // keeps such states readable. Identity slots leave it off so an action still
    // resolves.
    if (source.bypassUnavailable && st.unavailable) st.state
    else {
      val out = transforms.run(source.transform, st)
      if (out.nonEmpty) out else source.default.getOrElse("")
    }
  }
}

object Renderer {

  /** Build a renderer from a (validated) dashboard, compiling its template and
    * transform libraries up front. The single construction point so call sites
    * never wire `Templates`/`Transforms` by hand.
    */
  def create(dashboard: Dashboard): Renderer =
    new Renderer(
      dashboard,
      Templates.from(dashboard),
      Transforms.from(dashboard)
    )

  /** Build a renderer from a PROVEN dashboard ([[Dashboard.Validated]]) — the
    * production construction point (decode / reload / push all go through
    * [[Dashboard.validated]] first). Uses the proof's pre-compiled transforms,
    * so nothing is recompiled and the transform-setup invariant throw of
    * [[Transforms.from]] is unreachable by type.
    */
  def fromValidated(v: Dashboard.Validated): Renderer =
    new Renderer(
      v.dashboard,
      Templates.from(v.dashboard),
      Transforms.fromValidated(v)
    )

  /** 12 hex of SHA-256 over the part of `<head>` only a reload can change — the
    * same idiom as `fh-home@1.0.0-g<hash>`. See [[Renderer.headHash]].
    *
    * The `<base href>` is excluded on purpose: it is per-REQUEST (the ingress
    * prefix), not per-dashboard, so folding it in would make one dashboard hash
    * differently depending on how it was reached.
    *
    * Over the decoded model rather than the evaluated JSON text, so it is blind
    * to key order and formatting. `toString` is a deterministic rendering here:
    * the nested `Map`s are keyed by `String`, whose `hashCode` is specified, so
    * an immutable map's iteration order is a pure function of its contents and
    * its (decode-order) insertion sequence — no identity hashes, nothing
    * JVM-run-specific.
    */
  private[runtime] def headFingerprint(dashboard: Dashboard): String =
    fingerprint(
      (
        dashboard.theme.stylesheets,
        dashboard.theme.scripts,
        dashboard.theme.inlineScripts,
        dashboard.theme.chrome
      ).toString
    )

  /** 12 hex over the patchable part of `<head>`. See [[Renderer.styleHash]].
    */
  private[runtime] def styleFingerprint(dashboard: Dashboard): String =
    fingerprint(
      (
        dashboard.theme.tokens,
        dashboard.theme.tokensDark,
        dashboard.theme.styles,
        dashboard.title
      ).toString
    )

  private def fingerprint(s: String): String =
    LibPackage.sha256(s.getBytes("UTF-8")).take(12)

  /** Id of the theme's `<style>` element — stable across dashboards, so a
    * navigate can morph one theme into another.
    */
  val ThemeStyleId: String = "fh-theme"

  /** The `self` element's DOM id for a node — `<nodeId>-self`.
    *
    * One derivation, used both to WRITE the id (`{{selfId}}`) and to TARGET it
    * ([[Renderer.patchTargetId]]), so the template and the patch cannot drift
    * apart. `-` cannot appear in a generated node id ([[LayoutNode.sanitize]]),
    * which is what keeps the log's `startsWith(id + "_")` ancestry tests from
    * reading `c_2-self` as a child of `c_2`.
    */
  def selfElementId(id: NodeId): DomId = DomId.derived(id + "-self")

  // The id scheme lives in the model ([[LayoutNode]]) so the build-phase hoist
  // and the renderer share one story; these delegate.
  def surfacePrefix(surfaceId: String): String =
    LayoutNode.surfacePrefix(surfaceId)
  def sanitize(s: String): String = LayoutNode.sanitize(s)

  /** A node's authored layout-cell classes as the wrapper `class` suffix
    * (leading space included), `""` when absent/empty. Validated by
    * `Dashboard.validate` to be plain class tokens.
    */
  private def cellClasses(cell: Option[Cell]): String =
    cell.map(_.classes).filter(_.nonEmpty).fold("")(_.mkString(" ", " ", ""))

  /** Build the jmustache context at the Java boundary: the string slot/param
    * context plus, when present, a `children` list of `{html}` maps for
    * container templates (`{{#children}}{{{html}}}{{/children}}`). Kept here so
    * the rest of the renderer works in plain `Map[String, String]`.
    */
  private def javaContext(
      context: Map[String, String],
      childrenHtml: List[String]
  ): java.util.Map[String, AnyRef] = {
    val m = new java.util.HashMap[String, AnyRef](context.size + 1)
    context.foreach { case (k, v) => m.put(k, v) }
    if (childrenHtml.nonEmpty) {
      val list = new java.util.ArrayList[AnyRef](childrenHtml.size)
      childrenHtml.foreach(h =>
        list.add(java.util.Collections.singletonMap("html", h))
      )
      val _ = m.put("children", list)
    }
    m
  }

  /** Evaluate a query predicate against one entity's live state. The entity's
    * id and domain come off the [[EntityState]] itself.
    *
    * A `Cmp` naming another entity cannot be resolved from a single state, so
    * this treats it as false; use [[matchesIn]] where the snapshot is
    * available.
    */
  def matches(p: Predicate, st: EntityState): Boolean =
    matchesIn(p, st, Map.empty)

  /** [[matches]] with the snapshot in hand, so a guard may name a DIFFERENT
    * entity than its subject. `Cmp.entity` absent still means "the subject".
    */
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

  private def compareOn(
      term: LayoutNode.SortTerm,
      a: String,
      b: String,
      states: Map[String, EntityState]
  ): Int = {
    val raw = term.by match {
      case LayoutNode.SortKey.Holds(p) =>
        // True first under `asc` — "the ones that are on, then the rest".
        def holds(id: String) =
          states.get(id).exists(st => matchesIn(p, st, states))
        java.lang.Boolean.compare(holds(b), holds(a))
      case LayoutNode.SortKey.Prop(property) =>
        def read(id: String) =
          states.get(id).fold("")(propertyOf(property, _))
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

  /** One entity property, as the string a comparison or an ordering reads.
    * `reg:` is deliberately absent: a registry fact is build-time data, so a
    * comparison on one folds away before it reaches here and an ordering on one
    * leaves the candidates pre-sorted. Seeing a `reg:` here means the build
    * emitted something it should have resolved.
    */
  def propertyOf(property: String, st: EntityState): String =
    property match {
      case "domain" => st.domain
      case "state"  => st.state
      // The entity's identity itself — what lets a state-activation condition
      // pin one entity ("entity X is in state Y") and a dynamic group
      // enumerate an explicit entity set.
      case "entity_id"                        => st.entityId
      case other if other.startsWith("attr:") =>
        st.attributes
          .get(other.stripPrefix("attr:"))
          .map(StateStore.jsonToString)
          .getOrElse("")
      case _ => ""
    }

  def matchesIn(
      p: Predicate,
      subject: EntityState,
      states: Map[String, EntityState]
  ): Boolean =
    p match {
      case Predicate.And(items) => items.forall(matchesIn(_, subject, states))
      case Predicate.Or(items)  => items.exists(matchesIn(_, subject, states))
      case Predicate.Not(item)  => !matchesIn(item, subject, states)
      case Predicate.Count(candidates, when, op, value) =>
        // A candidate with no guard is unconditionally present — the same rule
        // a set member with an unguarded clause follows.
        val n = candidates.count(id =>
          when
            .get(id)
            .forall(g => states.get(id).exists(matchesIn(g, _, states)))
        )
        compare(n.toString, StateStore.jsonToString(value), op)
      case Predicate.Cmp(_, _, _, Some(other)) if !states.contains(other) =>
        // Named an entity the snapshot does not have: it can never hold, and
        // saying so beats reading the subject's value by accident.
        false
      case Predicate.Cmp(property, op, value, entity) =>
        val st = entity.flatMap(states.get).getOrElse(subject)
        compare(propertyOf(property, st), StateStore.jsonToString(value), op)
    }

  /** Ordering ops compare NUMERICALLY, and are false unless both sides parse as
    * numbers; equality ops compare the raw strings.
    */
  private def compare(lhs: String, rhs: String, op: Op): Boolean = {
    def numeric(cmp: (Double, Double) => Boolean): Boolean =
      (lhs.toDoubleOption, rhs.toDoubleOption) match {
        case (Some(l), Some(r)) => cmp(l, r)
        case _                  => false
      }
    op match {
      case Op.Eq  => lhs == rhs
      case Op.Ne  => lhs != rhs
      case Op.Lt  => numeric(_ < _)
      case Op.Lte => numeric(_ <= _)
      case Op.Gt  => numeric(_ > _)
      case Op.Gte => numeric(_ >= _)
    }
  }
}
