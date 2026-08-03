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

/** Group id -> chosen member index: the canonical form of the client's
  * `ui_<gid>` signals, already parsed, clamped to a real member, and restricted
  * to the groups a given render actually reads. That is what makes it a memo
  * key — two viewers who spelled their selection differently, or who carry
  * selections for groups this render never touches, resolve to the SAME key.
  */
private[runtime] type Selections = Map[NodeId, Int]

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

  /** `None` means no query, which matches every entity. */
  private val dynamicQueries: Map[NodeId, Option[Predicate]] =
    allIndexed.collect { case (id, (d: LayoutNode.Dynamic, _, _)) =>
      id -> d.query
    }

  /** Whether `change` touches this dynamic group at all — i.e. whether the
    * group's *query* matched the entity before or after it. Matching neither
    * side leaves the group alone.
    *
    * Deliberately NOT the finer question (joined / left / updated in place): a
    * single change can decide that, but a FRAME cannot — two entities can move
    * in opposite directions in one tick. The membership compare in
    * `Patches.renderDynamicGroup` answers it for the frame as a whole, over the
    * before/after member lists, so this only has to select the groups worth
    * looking at.
    */
  private def touchesDynamic(id: NodeId, change: StateChange): Boolean = {
    val query = dynamicQueries.getOrElse(id, None)
    def matchesQuery(st: EntityState): Boolean =
      query.forall(Renderer.matches(_, st))
    change.previous.exists(matchesQuery) || matchesQuery(change.current)
  }

  /** Main-page dynamic containers this frame touches, each with the changed
    * entities that touched it.
    */
  def affectedDynamics(
      changes: List[StateChange]
  ): List[(NodeId, List[String])] =
    mainIndex.dynamicIds.flatMap(id => touchedBy(id, changes))

  /** Like [[affectedDynamics]], scoped to one open surface. */
  def affectedSurfaceDynamics(
      surfaceId: String,
      changes: List[StateChange]
  ): List[(NodeId, List[String])] =
    surfaceIndexes
      .get(surfaceId)
      .toList
      .flatMap(_.dynamicIds.flatMap(id => touchedBy(id, changes)))

  private def touchedBy(
      id: NodeId,
      changes: List[StateChange]
  ): Option[(NodeId, List[String])] =
    changes.filter(touchesDynamic(id, _)).map(_.entityId) match {
      case Nil     => None
      case touched => Some(id -> touched)
    }

  /** [[bakeGroup]], for the flip path. A state group's members are a FIXED,
    * tiny set (its branches), which is why — unlike a dynamic group over
    * unbounded entities — its mutations can never accumulate and it needs no
    * eviction horizon.
    */
  def bakeMembers(gid: NodeId): List[String] = bakeGroup(gid)

  /** Ordered by `bakeIndex`, with the surface id as a stable tiebreak and as
    * the fallback for a member carrying none. That order is what a ui-state
    * index selects among, and what state selection walks first-match (then,
    * elseif…, else).
    */
  private def bakeGroup(gid: NodeId): List[String] =
    dashboard.surfaces.toList
      .collect {
        case (sid, s) if s.bakeInto.contains(gid) => (sid, s.bakeIndex)
      }
      .sortBy { case (sid, bi) => (bi.getOrElse(Int.MaxValue), sid) }
      .map(_._1)

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

  /** Whether this node's `self` reads `bakeIndex` while the group it owns is
    * USER-selected — a bar that renders its active tab server-side rather than
    * through a `$ui_<id>` expression. Bounded: one variant per member of its
    * OWN group, never a product over the subtree, because a node's own
    * rendering carries no mount.
    *
    * Such a node's patch is deferred (`Patches.Pending`), rendered once per
    * SELECTION rather than per connection, and its log entry carries one digest
    * per variant — so digest suppression still applies, per selection rather
    * than across all of them.
    *
    * Decided by [[Templates]] at compile time by matching a mustache TAG, not a
    * substring of the source, which would make a card per-viewer forever
    * because the word appears in one of its comments.
    */
  def nodeVariesByViewer(id: NodeId): Boolean =
    allIndexed.get(id).exists {
      case (c: LayoutNode.Component, _, _) =>
        userBakeOwnerIds(id) && templates.selvesReadingSelection(c.card)
      case _ => false
    }

  /** `0` for the overwhelming majority of nodes, whose rendering does not vary
    * at all.
    */
  def variantOf(id: NodeId, uiState: Map[String, String]): Int =
    if (nodeVariesByViewer(id)) resolveActive(id, uiState)._1 else 0

  /** [[variantOf]] against already-resolved [[Selections]]. A varying node IS
    * its own group's owner ([[nodeVariesByViewer]] requires
    * `userBakeOwnerIds`), so its own id is the key to look up.
    */
  def variantIn(id: NodeId, selections: Selections): Int =
    if (nodeVariesByViewer(id)) selections.getOrElse(id, 0) else 0

  /** Its own group's selection, or none. For a single node the digest variant
    * and the render key are the same thing.
    */
  def selectionsOf(id: NodeId, uiState: Map[String, String]): Selections =
    if (nodeVariesByViewer(id)) Map(id -> resolveActive(id, uiState)._1)
    else Map.empty

  /** Wider than [[selectionsOf]] deliberately: a surface's HTML varies with any
    * tab inside it, not just with hosts whose OWN markup reads a selection — a
    * tabs card whose bar never prints `bakeIndex` still puts a different panel
    * in the composed bytes.
    */
  def selectionsUnder(sid: String, uiState: Map[String, String]): Selections =
    userGroupsUnder(sid).map(g => g -> resolveActive(g, uiState)._1).toMap

  /** What a per-variant render of `sid` must be keyed by.
    *
    * The walk follows BOTH kinds of member, because a tabs card nested inside
    * another tab's panel varies just as much as one inside an `If` branch. The
    * visited set is not defensive tidiness: `bakeInto` is authored, so a
    * surface can name a host inside its own subtree and the walk would not
    * terminate.
    */
  def userGroupsUnder(sid: String): Set[NodeId] =
    userGroupsBySurface.getOrElse(sid, Set.empty)

  private val userGroupsBySurface: Map[String, Set[NodeId]] = {
    def walk(sid: String, seen: Set[String]): Set[NodeId] =
      if (seen(sid)) Set.empty
      else {
        val ids =
          surfaceIndexes.get(sid).map(_.indexed.keySet).getOrElse(Set.empty)
        ids.filter(userBakeOwnerIds) ++
          ids
            .filter(bakeOwnerIds)
            .flatMap(gid => bakeGroup(gid).flatMap(walk(_, seen + sid)))
      }
    dashboard.surfaces.keySet.map(sid => sid -> walk(sid, Set.empty)).toMap
  }

  /** Errs wide: `true` costs a redundant per-viewer render, where a wrong
    * `false` hands every viewer the same tab.
    */
  def surfaceVariesByViewer(sid: String): Boolean =
    userGroupsUnder(sid).nonEmpty

  private val prefixToRoot: Map[String, String] =
    Map(mainIndex.idPrefix -> "") ++
      surfaceIndexes.map { case (sid, idx) => idx.idPrefix -> sid }

  /** `""` = the main page, `<sid>` = inside that surface. NOT recoverable from
    * the id itself: an id carries only its OWN surface prefix (`s_<sid>__c_0`),
    * and a nesting is three independent prefixes with no link between them.
    */
  private def rootOf(id: NodeId): Option[String] =
    allIndexed.get(id).map { case (_, _, prefix) => prefixToRoot(prefix) }

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
          case Activation.State(condition, quantifier) =>
            holds(condition, quantifier, states)
          case _ => false
        })
    )
    Option.when(idx >= 0)(idx)
  }

  /** The O(1) pre-test of the flip check, same cost model as
    * [[touchesDynamic]]: a state change can only move a group's selection if
    * the CHANGED entity's own match flipped for some member's condition, since
    * the quantified aggregate (any/none/all) is over per-entity matches and
    * only this entity's changed. A newly-seen entity (`previous = None`) skips
    * the shortcut — its mere appearance can move an `all`/`none` aggregate with
    * no per-entity flip at all.
    */
  private def conditionTouched(
      gid: NodeId,
      changes: List[StateChange]
  ): Boolean =
    changes.exists(conditionTouched(gid, _))

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

  def componentsFor(entityId: String): Set[NodeId] =
    mainIndex.byEntity.getOrElse(entityId, Set.empty)

  /** Empty for a dynamic group — its members are per-entity children with ids
    * of their own — and for an unknown id.
    */
  def entitiesForNode(id: NodeId): List[String] =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _, _)) => c.liveEntities
      case _                                     => Nil
    }

  def surfaceComponentsFor(surfaceId: String, entityId: String): Set[NodeId] =
    surfaceIndexes
      .get(surfaceId)
      .fold(Set.empty)(_.byEntity.getOrElse(entityId, Set.empty))

  def surface(surfaceId: String): Option[Surface] =
    dashboard.surfaces.get(surfaceId)

  /** `<link>`-ed by the page, e.g. BeerCSS. */
  def stylesheets: List[String] = dashboard.theme.stylesheets

  /** Injected as `<script type="module">`, e.g. beer.min.js. */
  def scripts: List[String] = dashboard.theme.scripts

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
    dynamicMembers(groupId, states).flatMap(e =>
      renderDynamicChild(groupId, e, states)
        .map(dynamicChildId(groupId, e) -> _)
    )

  /** Decides how a mount is patched. A state group's mount holds at most ONE
    * member (a bake group has one hole), so there are no siblings to preserve
    * and no position to fix: overwriting it IS the delta. A dynamic group's is
    * the opposite, and gets per-member `remove`/`before`.
    */
  def isDynamicContainer(id: NodeId): Boolean =
    allIndexed.get(id).exists { case (n, _, _) =>
      n.isInstanceOf[LayoutNode.Dynamic]
    }

  /** What a wholesale FILL carries, for EITHER kind of container: a dynamic
    * group's members, or a state group's one active branch. Both are "what is
    * in this mount", so they answer here rather than at each fill site.
    */
  def renderMount(
      container: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): List[(NodeId, String)] =
    allIndexed.get(container) match {
      case Some((_: LayoutNode.Dynamic, _, _)) =>
        renderDynamicMembers(container, states)
      case _ =>
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
    * them back. Two kinds are: a static node ([[renderNodeById]]), and a member
    * of a dynamic group, whose per-entity ids are deliberately NOT in the
    * static index.
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
  ): Option[String] =
    renderNodeById(id, states, uiState).orElse(
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
    * group member), and injecting their vars separately means a var added to
    * one silently misses the other. The rule this makes true:
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
    * The log is per SLUG, so a digest recorded for either is one viewer's bytes
    * presented as everyone's — and a resume re-rendering one hands that
    * viewer's variant to whoever asks. Neither loses anything by being
    * excluded: their children are addressable in their own right.
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
        // that element plus its children's FULL renderings; anything else
        // renders its whole card, its own mount included.
        if (hasSelf(c.card)) !c.children.exists(carriesMount)
        else !carriesMount(c)
      case (_: LayoutNode.Dynamic, _, _) => false
    }

  /** Whether rendering this node in FULL — as a parent's markup embeds it —
    * brings a mount along, its own or a descendant's.
    *
    * A dynamic group does not count: its members render with no children and no
    * bake group, so a member card's mount comes out empty and carries nobody's
    * selection.
    */
  private def carriesMount(node: LayoutNode): Boolean = node match {
    case c: LayoutNode.Component =>
      templates.mounts.contains(c.card) || c.children.exists(carriesMount)
    case _: LayoutNode.Dynamic => false
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
          (Map(as -> ""), Map.empty, Map.empty)
      }
    else bakeMember(resolveActive(id, uiState)._1)
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
        // A variant-bearing node IS recorded: this walk renders for ONE viewer,
        // so its bytes are that viewer's variant and the log keys them by it.
        // Excluding them was a leftover from before variants had entries of
        // their own.
        val ownHtml =
          if (hasOwnRendering(id)) selfHtml.orElse(Some(wrapped)) else None
        Traced(
          wrapped,
          kids.foldLeft(bakedTrace)(_ ++ _.own) ++ ownHtml.map(id -> _)
        )
      case d: LayoutNode.Dynamic =>
        val id = LayoutNode.nodeId(idPrefix, path)
        // A group root composes its members and so has no own rendering; the
        // members do, and they are what a fill must fingerprint.
        Traced(
          renderDynamic(id, d, states),
          renderDynamicMembers(id, states).toMap
        )
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
