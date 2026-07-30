package fh.view.runtime

import com.samskivert.mustache.Template
import fh.view.build.LibPackage
import fh.view.model.{
  Activation,
  Cell,
  Dashboard,
  DomId,
  DynamicCase,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  Quantifier,
  SlotSource,
  Surface
}

/** How one state change moves the changed entity across a dynamic group's
  * membership boundary — the two query-match booleans (before ∧ after) kept
  * apart instead of collapsed to a single "touched" flag, so the live-patch
  * path can narrow a whole-group re-render down to a per-entity patch.
  *
  *   - [[InPlace]] (`prev ∧ cur`): the entity was and still is a member — its
  *     card is re-rendered and outer-morphed in place (the hot path). Covers a
  *     case-branch switch for free (the child id doesn't encode the branch).
  *   - [[Added]] (`¬prev ∧ cur`): the entity newly matches — a member joins.
  *   - [[Removed]] (`prev ∧ ¬cur`): the entity no longer matches — a member
  *     leaves.
  */
enum DynamicDelta:
  case InPlace, Added, Removed

/** Renders the recursive dashboard layout tree from current entity state.
  *
  * Every node is a `Component` referencing a shared template by name; a
  * container is just a Component whose template splices its rendered `children`
  * (`{{#children}}{{{html}}}{{/children}}`), so container kinds (row, column,
  * grid, …) are defined as templates rather than special-cased here.
  *
  * Addressable nodes get a stable, location-based id derived from their index
  * path in the tree ([[LayoutNode.pathId]]) — authors never invent ids.
  * [[componentsFor]] + [[affectedDynamicIds]] drive the live update loop, and
  * [[renderNodeById]] re-renders a single patchable node.
  *
  * A dashboard's **surfaces** (popups, later tabs) are separate layout trees
  * rendered on demand by [[renderSurface]] and kept live only while a
  * connection has them open. Their node ids are namespaced (`s_<id>__…`) so
  * they never collide with the main page; [[surfaceComponentsFor]] /
  * [[affectedSurfaceDynamicIds]] are the surface-scoped equivalents of the
  * main-page update indices.
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
          case _: LayoutNode.Dynamic => List(self)
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

    val dynamicIds: List[NodeId] =
      indexed.collect { case (id, (_: LayoutNode.Dynamic, _)) => id }.toList
  }

  private val mainIndex = new Index(dashboard.card, "")

  /** Does the browser's `<head>` still match the UNPATCHABLE part of this
    * dashboard — the theme's `<link>`ed stylesheets, its module scripts, and
    * its `chrome` frame? A mismatch is the one thing neither a body patch nor a
    * head patch can repair (a `<link>` can be added but not un-applied, a
    * module script cannot be re-run, and the chrome is the frame the body patch
    * lands INSIDE), so it is the one thing worth a full page **reload**
    * (docs/adr/0011-the-live-connection.md).
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

  /** This dashboard's non-fatal problems ([[Dashboard.warnings]]) — surfaced
    * here so the caller that builds a renderer logs them once, rather than the
    * renderer printing from inside a pure render path (the same split as
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

  /** Every addressable node (main + all surfaces) keyed by its generated id,
    * paired with its path and the id prefix needed to re-render it.
    */
  private val allIndexed: Map[NodeId, (LayoutNode, List[Int], String)] =
    (mainIndex :: surfaceIndexes.values.toList).flatMap { idx =>
      idx.indexed.map { case (id, (n, p)) => id -> (n, p, idx.idPrefix) }
    }.toMap

  /** Each dynamic container's query, by id (main + surfaces), for the
    * affected-by-change test. A group with no query matches every entity.
    */
  private val dynamicQueries: Map[NodeId, Option[Predicate]] =
    allIndexed.collect { case (id, (d: LayoutNode.Dynamic, _, _)) =>
      id -> d.query
    }

  /** How a state change moves the changed entity across one dynamic group's
    * membership boundary, or `None` when it leaves the group untouched. Derived
    * from the group's *query* match before vs after the change: `prev ∧ cur` is
    * an in-place update of a member ([[DynamicDelta.InPlace]]), `¬prev ∧ cur` a
    * join ([[DynamicDelta.Added]]), `prev ∧ ¬cur` a leave
    * ([[DynamicDelta.Removed]]); matching neither side changes nothing
    * (`None`). The two booleans are kept apart (rather than collapsed to
    * "touched") so the Server can patch a member in place vs. add/remove it
    * per-entity.
    */
  private def dynamicDelta(
      id: NodeId,
      change: StateChange
  ): Option[DynamicDelta] = {
    val query = dynamicQueries.getOrElse(id, None)
    def matchesQuery(st: EntityState): Boolean =
      query.forall(Renderer.matches(_, st))
    val prev = change.previous.exists(matchesQuery)
    val cur = matchesQuery(change.current)
    (prev, cur) match {
      case (true, true)   => Some(DynamicDelta.InPlace)
      case (false, true)  => Some(DynamicDelta.Added)
      case (true, false)  => Some(DynamicDelta.Removed)
      case (false, false) => None
    }
  }

  /** Main-page dynamic containers this change affects, each with the membership
    * delta ([[dynamicDelta]]) so the caller can pick a per-entity patch vs. a
    * whole-group repaint. Unrelated entities are filtered out, sparing the
    * whole-group re-scan + re-render on every event.
    */
  def affectedDynamics(change: StateChange): List[(NodeId, DynamicDelta)] =
    mainIndex.dynamicIds.flatMap(id => dynamicDelta(id, change).map(id -> _))

  /** Just the affected main-page dynamic ids (delta dropped) — the pre-Tier-1
    * shape, kept for callers/tests that only need the membership test.
    */
  def affectedDynamicIds(change: StateChange): List[NodeId] =
    affectedDynamics(change).map(_._1)

  /** Like [[affectedDynamics]], scoped to one open surface. */
  def affectedSurfaceDynamics(
      surfaceId: String,
      change: StateChange
  ): List[(NodeId, DynamicDelta)] =
    surfaceIndexes
      .get(surfaceId)
      .toList
      .flatMap(
        _.dynamicIds.flatMap(id => dynamicDelta(id, change).map(id -> _))
      )

  /** The surfaces baked into component `gid`'s host, ordered by their
    * `bakeIndex` (surface id as a stable tiebreak / fallback when a member
    * carries none). This is the ordered member list a ui-state index (user
    * mode) selects among, and the first-match order (then, elseif…, else) state
    * selection walks.
    */
  /** [[bakeGroup]], for the flip path: which surfaces `gid`'s mount can hold,
    * in selection order. A state group's members are a FIXED, tiny set (its
    * branches), which is why — unlike a dynamic group over unbounded entities —
    * its mutations can never accumulate and it needs no eviction horizon.
    */
  def bakeMembers(gid: NodeId): List[String] = bakeGroup(gid)

  private def bakeGroup(gid: NodeId): List[String] =
    dashboard.surfaces.toList
      .collect {
        case (sid, s) if s.bakeInto.contains(gid) => (sid, s.bakeIndex)
      }
      .sortBy { case (sid, bi) => (bi.getOrElse(Int.MaxValue), sid) }
      .map(_._1)

  /** Whether a surface has a user-mode activation with `defaultOpen` set — the
    * "shown on first paint with no selection / no click" flag, read in
    * [[resolveActive]]'s fallback and for ungrouped (popup) surfaces in
    * [[selectedSurfaces]].
    */
  private def defaultOpenUser(s: Surface): Boolean = s.activation match {
    case Activation.User(d) => d
    case _                  => false
  }

  /** Whether `gid`'s bake group is STATE-selected (its members carry
    * `Activation.State`). `Dashboard.validate` rejects mode-mixed groups, so
    * the first member decides for the whole group. State-selected groups are a
    * pure function of entity state: they render in the SHARED per-slug pass,
    * never against a session's uiState/open set (the core split — ADR 0002's
    * shared/per-session cost model extended by activation mode).
    */
  private def isStateGroup(gid: NodeId): Boolean =
    bakeGroup(gid).headOption.exists(isStateSurface)

  /** Every component id some surface bakes into. `bakeInto` is AUTHORED (a
    * hoist-resolved relation `Dashboard.validate` checks against the registry),
    * which is the one place a node id enters from outside the tree walk.
    */
  private val bakeOwnerIds: Set[NodeId] =
    dashboard.surfaces.values.flatMap(_.bakeInto).map(NodeId.derived).toSet

  /** Component ids that own a USER-selected bake group (tabs). Their HTML
    * depends on the client's `uiState` (the baked member is client-selected),
    * so their live patches must stay per-session and they are EXCLUDED from the
    * shared per-slug pass (see `Server`).
    */
  val userBakeOwnerIds: Set[NodeId] =
    bakeOwnerIds.filterNot(isStateGroup)

  /** Component ids that own a STATE-selected bake group (If/else hosts). Their
    * HTML — selection included — is a pure function of entity state, so unlike
    * user-selected owners they stay IN the shared per-slug pass (rendered once
    * per slug, fanned out to every viewer).
    */
  val stateBakeOwnerIds: Set[NodeId] =
    bakeOwnerIds.filter(isStateGroup)

  /** Whether a member surface's content subtree contains a user-selected bake
    * owner, following nested state members transitively. Feeds
    * [[sessionOnlyStateGroups]]: a user owner inside a state branch means that
    * branch's HTML bakes a client-selected member, so the branch cannot render
    * shared.
    */
  private def subtreeHasUserOwner(sid: String): Boolean = {
    val ids =
      surfaceIndexes.get(sid).map(_.indexed.keySet).getOrElse(Set.empty)
    ids.exists(userBakeOwnerIds) ||
    ids.exists(gid =>
      stateBakeOwnerIds(gid) && bakeGroup(gid).exists(subtreeHasUserOwner)
    )
  }

  /** State-selected groups whose member subtree contains a user-selected bake
    * owner (tabs inside an If). Their host HTML embeds a client-selected
    * member, so their flips must be patched PER-SESSION with that session's
    * `uiState` — the Server excludes them from the shared flip path and mirrors
    * them in the per-session pass instead. Every other state group is shared.
    * Computed once per renderer (structure, not state).
    */
  val sessionOnlyStateGroups: Set[NodeId] =
    stateBakeOwnerIds.filter(gid => bakeGroup(gid).exists(subtreeHasUserOwner))

  private val prefixToRoot: Map[String, String] =
    Map(mainIndex.idPrefix -> "") ++
      surfaceIndexes.map { case (sid, idx) => idx.idPrefix -> sid }

  /** Which content tree a node lives in: `""` = the main page, `<sid>` = inside
    * surface `<sid>`. Every node belongs to exactly one, by construction — it
    * was indexed from that tree. This is NOT recoverable from the id itself: an
    * id carries only its OWN surface prefix (`s_<sid>__c_0`), and a nesting is
    * three independent prefixes with no link between them.
    */
  private def rootOf(id: NodeId): Option[String] =
    allIndexed.get(id).map { case (_, _, prefix) => prefixToRoot(prefix) }

  /** The surface CONTAINING this one — absent for a main-rooted surface. A
    * surface's place in the tree is where its host node sits, so the relation
    * is just [[rootOf]] applied to `bakeInto`; a popup (no `bakeInto`) hosts on
    * the main page and so is main-rooted.
    */
  private val surfaceParent: Map[String, String] =
    dashboard.surfaces.flatMap { case (sid, s) =>
      s.bakeInto.flatMap(rootOf).filter(_.nonEmpty).map(sid -> _)
    }

  /** The innermost USER-selected surface containing `sid` (itself included) —
    * the tag deciding which clients a patch from that tree may reach.
    *
    * State surfaces are TRANSPARENT: a branch of an `If` is selected by entity
    * state, identically for every client, so it hides nothing and the walk
    * passes through it to whatever encloses it. Reaching the main page means
    * "no user surface above me": visible to everyone.
    */
  def userSurfaceOf(sid: String): Option[String] =
    if (!isStateSurface(sid)) Some(sid)
    else surfaceParent.get(sid).flatMap(userSurfaceOf)

  /** [[userSurfaceOf]] for a node, via the tree it was indexed from. */
  def userSurfaceOfNode(id: NodeId): Option[String] =
    rootOf(id).filter(_.nonEmpty).flatMap(userSurfaceOf)

  private def isStateSurface(sid: String): Boolean =
    dashboard.surfaces.get(sid).exists(_.activation match {
      case _: Activation.State => true
      case _                   => false
    })

  /** State-selected owner ids grouped by the tree that contains the owner node
    * ([[rootOf]]). This is the recursion structure of the transitive active-set
    * / affected-flip walks: a group is only VISIBLE through the chain of active
    * members above it, so each walk starts at one root's owners and descends
    * only into selected members.
    */
  private val stateGidsByRoot: Map[String, List[NodeId]] =
    stateBakeOwnerIds.toList.sorted
      .flatMap(gid => rootOf(gid).map(_ -> gid))
      .groupMap(_._1)(_._2)

  private def stateGidsAtRoot(root: String): List[NodeId] =
    stateGidsByRoot.getOrElse(root, Nil)

  /** Whether a state condition, quantified over the WHOLE live state map,
    * holds. See [[fh.view.model.Quantifier]] for why `none` is its own
    * quantifier and not a `Not` in the condition.
    */
  private def holds(
      condition: Predicate,
      quantifier: Quantifier,
      states: Map[String, EntityState]
  ): Boolean =
    quantifier match {
      case Quantifier.Any =>
        states.values.exists(Renderer.matches(condition, _))
      case Quantifier.None =>
        !states.values.exists(Renderer.matches(condition, _))
      case Quantifier.All =>
        states.values.forall(Renderer.matches(condition, _))
    }

  /** Resolve which member of a STATE-selected group `gid` is active: the FIRST
    * member (in `bakeIndex` order) whose quantified condition holds over
    * `states` — so an "else" is just a member with an always-true condition at
    * the last index, and a later `elseif` is one more member, no special
    * casing. `None` when no member's condition holds (the host bakes empty
    * content). The state-mode sibling of [[resolveActive]] — no uiState, no
    * ui-state warnings, pure over the snapshot (so the Server can evaluate it
    * against a before AND after snapshot to detect a flip).
    */
  private[runtime] def resolveActiveByState(
      gid: NodeId,
      states: Map[String, EntityState]
  ): Option[Int] = {
    val idx = bakeGroup(gid).indexWhere(sid =>
      dashboard.surfaces
        .get(sid)
        .exists(_.activation match {
          case Activation.State(condition, quantifier) =>
            holds(condition, quantifier, states)
          case _ => false
        })
    )
    Option.when(idx >= 0)(idx)
  }

  /** The O(1) pre-test of the flip check, same cost model as [[dynamicDelta]]:
    * a state change can only move a group's selection if the CHANGED entity's
    * own match flipped for some member's condition — the quantified aggregate
    * (any/none/all) is over per-entity matches, and only this entity's match
    * changed. Only when this passes does [[affectedStateGroups]] pay for the
    * full before/after selection. A newly-seen entity (`previous = None`) skips
    * the shortcut: its mere appearance can move an `all`/`none` aggregate
    * without any per-entity flip.
    */
  private def conditionTouched(gid: NodeId, change: StateChange): Boolean =
    change.previous match {
      case None       => true
      case Some(prev) =>
        bakeGroup(gid).exists(sid =>
          dashboard.surfaces
            .get(sid)
            .exists(_.activation match {
              case Activation.State(condition, _) =>
                Renderer.matches(condition, prev) !=
                  Renderer.matches(condition, change.current)
              case _ => false
            })
        )
    }

  /** The state-selected groups whose ACTIVE MEMBER this change flips, visible
    * from the main page — i.e. walking only through currently-selected members
    * (a flip inside a hidden branch is unreachable DOM; when its ancestor later
    * flips it in, the ancestor's host morph re-renders it fresh). Two-step per
    * group: the O(1) [[conditionTouched]] shortcut, then
    * [[resolveActiveByState]] over `before` vs `states`. The Server morphs each
    * returned host (minus [[sessionOnlyStateGroups]] on the shared pass) and
    * prunes its members' cache entries.
    */
  def affectedStateGroups(
      change: StateChange,
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): List[NodeId] =
    affectedStateGroupsFrom("", change, before, states)

  /** Like [[affectedStateGroups]], rooted at one surface's content tree — the
    * per-session variant for state groups inside an OPEN (user) surface, whose
    * visibility is this session's open set rather than the main page.
    */
  def affectedStateGroupsIn(
      surfaceId: String,
      change: StateChange,
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): List[NodeId] =
    affectedStateGroupsFrom(surfaceId, change, before, states)

  private def affectedStateGroupsFrom(
      root: String,
      change: StateChange,
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): List[NodeId] =
    stateGidsAtRoot(root).flatMap { gid =>
      val flipped =
        conditionTouched(gid, change) &&
          resolveActiveByState(gid, before) != resolveActiveByState(gid, states)
      // Recurse into the CURRENTLY selected member only: nested groups in the
      // inactive branch are not in any client's DOM.
      val nested = resolveActiveByState(gid, states).toList.flatMap(idx =>
        affectedStateGroupsFrom(bakeGroup(gid)(idx), change, before, states)
      )
      (if (flipped) List(gid) else Nil) ++ nested
    }

  /** The transitive ACTIVE set of state-selected member surfaces visible from
    * the main page: each state group contributes its selected member's sid,
    * then recurses into that member for nested groups. This is what keeps a
    * hidden branch silent — inactive members are never in the set, so the
    * Server never consults their indices (no guard map needed; silence is
    * structural). `excluding` prunes whole subtrees: the Server passes the
    * groups it flips this round (their host morph re-renders the member
    * wholesale — patching its parts too would double-emit) and, on the shared
    * pass, [[sessionOnlyStateGroups]].
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

  /** The `s_<sid>__` node-id prefixes of every member of `gid`'s bake group —
    * the cache-prune scope for a state-group flip (host id + these prefixes,
    * the same contract as the Server's `repaintGroup`), so a later re-revealed
    * member diffs from a known base instead of being suppressed by a stale
    * pre-flip entry.
    */
  def bakeMemberPrefixes(gid: NodeId): List[String] =
    bakeGroup(gid).map(Renderer.surfacePrefix)

  /** Resolve which member of a USER-selected group `gid` is active, given the
    * client's (untrusted) `uiState`. Parses `uiState.get(gid)` with
    * `.toIntOption` and keeps it only when it indexes a real member; otherwise
    * falls back to the group's `defaultOpen` member (or index 0). The second
    * element is `Some(warning)` ONLY when a value was present but off
    * (unparseable, or an int out of range) — `None` when no selection is
    * present or valid. Pure: the single source of truth for both the chosen
    * index and the malformed check. State-selected groups never come through
    * here — see [[resolveActiveByState]].
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

  /** Surfaces shown from the first paint with no user action, given the
    * client's `uiState` (default empty ⇒ today's behaviour). For each
    * USER-selected `bakeInto` group the [[resolveActive]]-selected member is
    * chosen; ungrouped surfaces (`bakeInto = None`) contribute their
    * `defaultOpen` ones as before. A connection seeds its open set with these
    * so the baked panels receive live updates from the first paint (and on a
    * navigate swap). STATE-selected members are excluded entirely — they never
    * enter a session's open set, because their liveness is the SHARED per-slug
    * pass's job (sessions keep handling only user-opened/user-baked surfaces).
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
    fromGroups ++ fromUnbaked
  }

  /** Warnings for any USER-selected bake group whose `uiState` value was
    * present but off (unparseable / out of range). Pure — returns data (the
    * Server logs it), so the renderer stays side-effect-free. Absent/valid
    * values produce nothing; a value naming a state-selected group is ignored
    * (no client choice exists there to be malformed).
    */
  def uiStateAnomalies(uiState: Map[String, String]): List[String] =
    dashboard.surfaces.toList
      .flatMap(_._2.bakeInto)
      .distinct
      .filterNot(isStateGroup)
      .flatMap(gid => resolveActive(gid, uiState)._2)

  def componentsFor(entityId: String): Set[NodeId] =
    mainIndex.byEntity.getOrElse(entityId, Set.empty)

  /** The live entities one node (by generated id) binds — the inverse of
    * [[componentsFor]], for edit-mode inspection ("debug this node"). Empty for
    * a dynamic group (its members are per-entity children with their own ids)
    * or an unknown id. Searches main + surface indices.
    */
  def entitiesForNode(id: NodeId): List[String] =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _, _)) => c.liveEntities
      case _                                     => Nil
    }

  /** Main-page node ids whose HTML this entity drives, scoped to one surface.
    */
  def surfaceComponentsFor(surfaceId: String, entityId: String): Set[NodeId] =
    surfaceIndexes
      .get(surfaceId)
      .fold(Set.empty)(_.byEntity.getOrElse(entityId, Set.empty))

  /** Like [[affectedDynamicIds]], scoped to one open surface. */
  def affectedSurfaceDynamicIds(
      surfaceId: String,
      change: StateChange
  ): List[NodeId] =
    affectedSurfaceDynamics(surfaceId, change).map(_._1)

  /** The surface's declaration (content/group/mount), if it exists. */
  def surface(surfaceId: String): Option[Surface] =
    dashboard.surfaces.get(surfaceId)

  /** External stylesheet URLs the theme wants `<link>`-ed (e.g. BeerCSS). */
  def stylesheets: List[String] = dashboard.theme.stylesheets

  /** External JS URLs the theme wants `<script type="module">`-injected (e.g.
    * beer.min.js). See [[fh.view.model.Theme.scripts]].
    */
  def scripts: List[String] = dashboard.theme.scripts

  /** The dashboard's authored page title, if any (the Server falls back to the
    * slug when `None`). See [[fh.view.model.Dashboard.title]].
    */
  def title: Option[String] = dashboard.title

  /** The theme as one id'd `<style>` element: design tokens as `:root` custom
    * properties (dark overrides under `@media (prefers-color-scheme: dark)`, so
    * the page follows the browser) followed by the theme's inline `styles`.
    * Empty when the theme carries no tokens or styles.
    *
    * Deliberately OUTSIDE `#dashboard`, i.e. not part of [[renderBody]]. It
    * used to ride inside the repainted body so that a reload or navigate
    * swapped it too — but it is static and it is BIG: on a small demo dashboard
    * it is 7.7 KB of the 9.6 KB a repaint sends, re-transmitted on every
    * reconnect that cannot resume. It is now sent only when it actually
    * changed, morphed by its stable id ([[Renderer.ThemeStyleId]]) — on a
    * navigate to a differently-themed dashboard, and on a reconnect whose
    * [[styleHash]] no longer matches (`Server.headPatches`).
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

  /** The dashboard frame, compiled once (like [[themeStyleTag]]) — a Mustache
    * template with a single `{{{body}}}` hole, owning the `#dashboard` swap
    * target and (for a theme that uses popups) the popup host's placement. An
    * empty `theme.chrome` falls back to the minimal frame with no popup host —
    * see [[Theme.chrome]]/[[Dashboard.validate]] for the contract.
    */
  private val chromeTemplate: Template = {
    val chrome =
      if (dashboard.theme.chrome.nonEmpty) dashboard.theme.chrome
      else """<main class="container" id="dashboard">{{{body}}}</main>"""
    Templates.compiler.compile(chrome)
  }

  /** The dashboard body: the walked layout tree, without the page shell and
    * without the theme ([[themeStyleTag]] sits outside it). This is what a
    * repaint and a navigate swap `inner`-patch into the stable `#dashboard`
    * container.
    */
  def renderBody(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): String =
    render(dashboard.card, Nil, "", states, uiState)

  /** The full page: [[themeStyleTag]] followed by the theme's compiled `chrome`
    * executed with `body = renderBody(...)` — a stable `#dashboard` patch
    * target (and, when the theme provides one, the popup host) so popups have a
    * fixed patch target. The style sits BEFORE the chrome, so every patch
    * target inside it can be repainted without re-sending the CSS.
    *
    * A restored `popup` is BAKED into the host's `{{{popups}}}` hole, the way a
    * selected tab panel is baked into its owner: otherwise the dialog cannot
    * appear until the stream connects and patches it in, which a refresh sees
    * as the dashboard painting first and the dialog popping in late. A theme
    * whose chrome has no hole simply renders without it (the patch still
    * arrives) — the var is optional in the contract.
    */
  def renderPage(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      popup: Option[String] = None
  ): String =
    themeStyleTag + chromeTemplate.execute(
      Renderer.javaContext(
        Map(
          "body" -> renderBody(states, uiState),
          // The dialog a refresh is restoring, baked into the host exactly as
          // the connect would patch it — same `renderSurface` call, so the two
          // are byte-identical and the later patch is a no-op morph.
          "popups" -> popup
            .flatMap(renderSurface(_, states, uiState))
            .getOrElse("")
        ),
        Nil
      )
    )

  /** Render a surface's bare content, namespaced under its surface-scoped id
    * prefix (`s_<id>__…`, [[Renderer.surfacePrefix]]) so its inner nodes never
    * collide with the main page. Every surface is chrome-less — the host it
    * swaps into (the popup overlay or a `tabs` card's panel host) and any
    * frame/dialog around it lives in `theme.chrome`, not per-surface — so this
    * returns `render(...)` directly with no wrapper. `None` if the surface id
    * is unknown.
    */
  def renderSurface(
      surfaceId: String,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[String] =
    dashboard.surfaces.get(surfaceId).map { s =>
      render(s.content, Nil, Renderer.surfacePrefix(surfaceId), states, uiState)
    }

  /** Render a single addressable node (for live SSE patches), main or surface.
    * `uiState` is threaded through so a node that owns a bake group (a `tabs`
    * host that also binds a live entity) re-bakes the client's client-selected
    * member on a live patch — not the default one.
    */
  def renderNodeById(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[String] =
    allIndexed.get(id).map {
      // A card that declares a `self` patches through THAT element alone — no
      // cell wrapper (the cell contains the mount) and no mount. Statement (1)
      // made structural rather than enforced by suppression: the fragment
      // simply cannot carry what the mount holds. Children DO ride along — a
      // tab bar's buttons are the card's own rendering, not mounted content.
      case (c: LayoutNode.Component, path, prefix) if hasSelf(c.card) =>
        renderTemplateOf(
          templates.selves(c.card),
          structuralVars(id),
          c.slots,
          c.children.zipWithIndex.map { case (child, i) =>
            render(child, path :+ i, prefix, states, uiState)
          },
          states
        )
      case (node, path, prefix) =>
        render(node, path, prefix, states, uiState)
    }

  /** The node id of a surface's CONTENT root (`s_<sid>__c`) — what a state
    * group's mount holds, and therefore the thing a flip removes or places.
    *
    * The same scheme the build-phase hoist uses to name a surface's nodes, so a
    * branch's build-time id and the id a flip's mutation records are one story.
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

  /** Every addressable node inside one surface's content tree.
    *
    * The resume path's SECOND candidate set: a surface a client has open holds
    * nodes the cursor alone would not name, because nothing may have rendered
    * that surface at all while nobody was viewing it — so the log has no
    * version to compare, and only re-rendering can tell whether the client's
    * DOM is current.
    */
  def surfaceNodeIds(surfaceId: String): Set[NodeId] =
    surfaceIndexes.get(surfaceId).fold(Set.empty)(_.indexed.keySet)

  /** Every current member of a dynamic group, id and rendered HTML, in DOM
    * order — what an `Inner` fill of the group's mount carries.
    *
    * Paired rather than concatenated because a fill owes the log a fingerprint
    * per member: the mount's contents are re-supplied wholesale, so the next
    * live diff must compare against what this fill actually put there.
    */
  def renderDynamicMembers(
      groupId: NodeId,
      states: Map[String, EntityState]
  ): List[(NodeId, String)] =
    dynamicMembers(groupId, states).flatMap(e =>
      renderDynamicChild(groupId, e, states)
        .map(dynamicChildId(groupId, e) -> _)
    )

  /** Everything currently in a container's mount, id and HTML per occupant —
    * what a wholesale FILL carries, for either kind of container.
    *
    * A dynamic group's mount holds its members; a state group's holds the one
    * active branch. Both are "what is in this mount", so they answer here
    * rather than at each fill site.
    */
  /** Whether `id` is a DYNAMIC group — a mount over unboundedly many members,
    * where position matters and a delta must preserve siblings.
    *
    * The distinction decides how a mount is patched. A state group's mount
    * holds at most ONE member (a bake group has one hole), so there are no
    * siblings to preserve and no position to fix: overwriting it IS the delta.
    * A dynamic group's is the opposite, and gets per-member `remove`/`before`.
    */
  def isDynamicContainer(id: NodeId): Boolean =
    allIndexed.get(id).exists { case (n, _, _) =>
      n.isInstanceOf[LayoutNode.Dynamic]
    }

  def renderMount(
      container: NodeId,
      states: Map[String, EntityState]
  ): List[(NodeId, String)] =
    allIndexed.get(container) match {
      case Some((_: LayoutNode.Dynamic, _, _)) =>
        renderDynamicMembers(container, states)
      case _ =>
        resolveActiveByState(container, states)
          .flatMap(bakeMembers(container).lift)
          .flatMap(sid =>
            renderSurface(sid, states).map(surfaceContentId(sid) -> _)
          )
          .toList
    }

  /** Render whatever node a LOG KEY names — the inverse the ledger needs.
    *
    * Since the log holds a digest rather than HTML, a resume renders its
    * candidates instead of reading them back, so every key must be resolvable
    * here. Two kinds are: a static node ([[renderNodeById]]) and one member of
    * a dynamic group, whose ids are per-entity and deliberately NOT in the
    * static index.
    *
    * `None` means the key names nothing that exists right now — its group is
    * gone, or the entity is no longer a member — which is exactly when there is
    * nothing to send. That it cannot crash is the point: an unresolvable key is
    * a fragment that can never be sent again, so it must be dropped visibly
    * rather than taking the resume with it.
    */
  def renderLogged(
      id: NodeId,
      states: Map[String, EntityState]
  ): Option[String] =
    renderNodeById(id, states).orElse(
      // `sanitize` is one-way, so the entity cannot be read back out of the id —
      // it is found by re-deriving each current member's id. O(members) on a
      // reconnect, never on the hot path.
      allIndexed.iterator
        .collect {
          case (gid, (_: LayoutNode.Dynamic, _, _))
              if id.startsWith(gid + "_") =>
            gid
        }
        .flatMap(gid => dynamicMembers(gid, states).iterator.map(gid -> _))
        .collectFirst {
          case (gid, e) if dynamicChildId(gid, e) == id =>
            renderDynamicChild(gid, e, states)
        }
        .flatten
    )

  /** The backend-injected structural template vars for one node — the ids an
    * author never composes.
    *
    * ONE derivation, deliberately, because there are two places a node id comes
    * from ([[LayoutNode.pathId]] for the static tree, [[dynamicChildId]] for a
    * group member) and they used to inject their vars separately, so a var
    * added to one silently missed the other. The rule this makes true:
    *
    * > Structural vars are a pure function of the node id in scope.
    *
    * So a container card used as a dynamic case gets `selfId`/`mountId` off its
    * member id for free, with no per-call-site knowledge. `bakeIndex` is NOT
    * here: it is a function of the client's selection, not of the id, and it
    * belongs to the document path alone ([[resolveBake]]).
    */
  private def structuralVars(id: NodeId): Map[String, String] =
    Map(
      "id" -> id,
      "selfId" -> Renderer.selfElementId(id),
      "mountId" -> mountId(id)
    )

  /** Whether a card patches through a `self` element of its own — the ONE
    * predicate the split turns on. It picks what the patch path renders, what
    * [[patchTargetId]] returns, and (with it) what the diff compares, so the
    * three can never disagree.
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
    * byte-identical to the `id="{{id}}_panel"` the template used to hardcode.
    * That removes a duplication rather than adding one: Pkl and Scala used to
    * derive the same string independently, with nothing checking they agreed.
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

  /** When component `id` owns a bake group (surfaces baked into it), bake the
    * SELECTED member as its `{{{bakeAs}}}` var so the host renders the active
    * panel/branch on first paint, and inject `bakeIndex` (a backend-known
    * structural var, like `id`) so a tabs template can seed its signal to the
    * selected index. Selection dispatches on the group's activation mode:
    * user-selected groups pick the `uiState`-selected member
    * ([[resolveActive]]; no selection ⇒ the `defaultOpen` member / index 0),
    * state-selected groups pick the first member whose condition holds over
    * live state ([[resolveActiveByState]]) — and when NO condition holds, bake
    * the empty string, so the host still renders its wrapper with empty content
    * rather than stale HTML. The chrome wraps the content just as a later
    * open/switch/flip would, so first-paint and switch-back produce
    * byte-identical HTML. No bake group → both maps empty (absent Mustache vars
    * render empty). Returns `(baked, structural)`.
    */
  private def resolveBake(
      id: NodeId,
      uiState: Map[String, String],
      states: Map[String, EntityState]
  ): (Map[String, String], Map[String, String]) = {
    val group = bakeGroup(id)
    def bakeMember(idx: Int): (Map[String, String], Map[String, String]) = {
      val sid = group(idx)
      val s = dashboard.surfaces(sid)
      (
        Map(
          s.bakeAs.getOrElse("") -> renderSurface(sid, states, uiState)
            .getOrElse("")
        ),
        Map("bakeIndex" -> idx.toString)
      )
    }
    if (group.isEmpty) (Map.empty, Map.empty)
    else if (isStateGroup(id))
      resolveActiveByState(id, states) match {
        case Some(idx) => bakeMember(idx)
        case None      =>
          // No branch matches: the host's {{{bakeAs}}} var is explicitly the
          // empty string (all members share one bakeAs — they bake into one
          // hole), so the wrapper renders empty instead of leaving the var
          // absent-but-meaningful.
          val as = group.headOption
            .flatMap(sid => dashboard.surfaces.get(sid).flatMap(_.bakeAs))
            .getOrElse("")
          (Map(as -> ""), Map.empty)
      }
    else bakeMember(resolveActive(id, uiState)._1)
  }

  private def render(
      node: LayoutNode,
      path: List[Int],
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): String =
    node match {
      case c: LayoutNode.Component =>
        val id = LayoutNode.nodeId(idPrefix, path)
        val childrenHtml = c.children.zipWithIndex.map { case (child, i) =>
          render(child, path :+ i, idPrefix, states, uiState)
        }
        val (baked, bakeIndex) = resolveBake(id, uiState, states)
        // The document path renders the whole card: its two parts first (each
        // seeing the same vars), then `template` with them spliced in. A leaf
        // card has neither part, so its `template` renders exactly as before.
        val html = renderWhole(
          c.card,
          structuralVars(id) ++ bakeIndex ++ baked,
          c.slots,
          childrenHtml,
          states
        )
        // EVERY node is a cell — containers included. The backend owns the id'd
        // `.fh-cell` wrapper, so templates never carry `id="{{id}}"` themselves
        // and authored `cell` classes (fh-cols-*, …) ride on it.
        //
        // The one exception is a card that opted out via
        // `CardDef.wrapAsCell = false`, which now means exactly one thing: my
        // root must not be wrapped in a layout box (the tab anchors, which must
        // stay direct children of BeerCSS's `.tabs`). It no longer implies
        // "never a morph target" — that is decided by card shape.
        //
        // A bake owner used to be denied the wrapper, because the cell WAS the
        // morph target and a bake owner's patch would have carried its whole
        // baked panel. The split separates the two: the cell is the layout item,
        // the `self` element is the patch target. So `Tabs`/`If` are ordinary
        // cells, and `.columns(n)` on them stops being silently dropped.
        if (noWrapCards(c.card)) html
        else
          s"""<div class="fh-cell${Renderer.cellClasses(
              c.cell
            )}" id="$id">$html</div>"""
      case d: LayoutNode.Dynamic =>
        renderDynamic(LayoutNode.nodeId(idPrefix, path), d, states)
    }

  private def renderDynamic(
      id: NodeId,
      d: LayoutNode.Dynamic,
      states: Map[String, EntityState]
  ): String = {
    val children =
      states.toList
        .filter { case (_, st) =>
          d.query.forall(Renderer.matches(_, st))
        }
        .sortBy(_._1)
        .flatMap { case (entityId, st) =>
          d.cases
            .find(c => Renderer.matches(c.when, st))
            .map(renderCase(id, entityId, _, states))
        }
    // The group root is itself a cell (a first-class layout item in its
    // container) plus `.fh-group`, the themed flow container its per-entity
    // member cells live in. Authored `cell` classes (e.g. `fh-cols-full` to
    // span a parent grid) ride on it.
    s"""<div class="fh-cell fh-group${Renderer.cellClasses(
        d.cell
      )}" id="$id">${children.mkString}</div>"""
  }

  /** The stable, per-entity id of one dynamic-group child (`<groupId>_<slug>`),
    * the outer-morph / insert / remove target for a single group member. Shared
    * by [[renderCase]] and the Server's per-entity patch path.
    */
  def dynamicChildId(groupId: NodeId, entityId: String): NodeId =
    NodeId.derived(s"${groupId}_${Renderer.sanitize(entityId)}")

  /** The entity ids a dynamic group currently renders as children, in DOM order
    * (sorted by entity id, matching [[renderDynamic]]). A member is an entity
    * that passes the group's `query` AND matches one of its `cases` — an entity
    * matching the query but no case renders nothing, so it is not a member.
    * Pure over the given `states` snapshot, so the Server can compute
    * membership before AND after a change (feeding the child-insert successor +
    * the add/remove churn heuristic). Unknown / non-dynamic id ⇒ empty.
    */
  def dynamicMembers(
      groupId: NodeId,
      states: Map[String, EntityState]
  ): List[String] =
    allIndexed.get(groupId) match {
      case Some((d: LayoutNode.Dynamic, _, _)) =>
        states.toList
          .filter { case (_, st) => d.query.forall(Renderer.matches(_, st)) }
          .sortBy(_._1)
          .collect {
            case (entityId, st)
                if d.cases.exists(c => Renderer.matches(c.when, st)) =>
              entityId
          }
      case _ => Nil
    }

  /** Render ONE dynamic-group child (the hot in-place path): confirm the entity
    * still passes the group's `query`, dispatch its `case`, and render it in
    * the same `fh-cell` wrapper [[renderCase]] uses — so the result
    * outer-morphs the child's id in place, no whole-group re-render. `None`
    * when the group id is unknown/non-dynamic, the entity no longer matches the
    * query, or no case matches (i.e. the entity is not a current member).
    */
  def renderDynamicChild(
      groupId: NodeId,
      entityId: String,
      states: Map[String, EntityState]
  ): Option[String] =
    allIndexed.get(groupId) match {
      case Some((d: LayoutNode.Dynamic, _, _)) =>
        states
          .get(entityId)
          .filter(st => d.query.forall(Renderer.matches(_, st)))
          .flatMap(st => d.cases.find(c => Renderer.matches(c.when, st)))
          .map(renderCase(groupId, entityId, _, states))
      case _ => None
    }

  private def renderCase(
      groupId: NodeId,
      entityId: String,
      c: DynamicCase,
      states: Map[String, EntityState]
  ): String = {
    // Set the matched entity as the card's subject: a literal `entity_id` slot
    // (the case stripped the build-time one). Every inheriting slot then binds
    // to it — including the label (`$attr.friendly_name`). A slot that names its
    // own entity keeps it; a constant literal reads no entity at all.
    val slots =
      c.slots.updated("entity_id", SlotSource(literal = Some(entityId)))
    val id = dynamicChildId(groupId, entityId)
    val html = renderWhole(c.card, structuralVars(id), slots, Nil, states)
    // Each child gets the SAME id'd `.fh-cell` wrapper as a static component, so
    // it is an addressable per-entity patch target (in-place morph / insert /
    // remove) rather than only ever re-rendered as part of the whole group —
    // which is why the wrap here is UNCONDITIONAL (a `wrapAsCell = false` card
    // has no per-entity morph target and is not usable as a dynamic case). The
    // case's `cell` classes are static wire data shared by every member, so
    // in-place morphs / inserts / whole-group repaints re-emit them
    // identically. The child id does not encode the matched case, so a
    // case-branch switch is just a morph.
    s"""<div class="fh-cell${Renderer.cellClasses(
        c.cell
      )}" id="$id">$html</div>"""
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
    */
  def matches(p: Predicate, st: EntityState): Boolean =
    p match {
      case Predicate.And(items)               => items.forall(matches(_, st))
      case Predicate.Or(items)                => items.exists(matches(_, st))
      case Predicate.Not(item)                => !matches(item, st)
      case Predicate.Cmp(property, op, value) =>
        val lhs = property match {
          case "domain" => st.domain
          case "state"  => st.state
          // The entity's identity itself — what lets a state-activation
          // condition pin one entity ("entity X is in state Y") and a dynamic
          // group enumerate an explicit entity set.
          case "entity_id"                        => st.entityId
          case other if other.startsWith("attr:") =>
            st.attributes
              .get(other.stripPrefix("attr:"))
              .map(StateStore.jsonToString)
              .getOrElse("")
          case _ => ""
        }
        val rhs = StateStore.jsonToString(value)
        // Ordering ops compare numerically, and are false unless both sides
        // parse as numbers; equality ops compare the raw strings.
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
