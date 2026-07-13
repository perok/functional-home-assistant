package fh.view.runtime

import com.samskivert.mustache.Template
import fh.view.model.{
  Activation,
  Cell,
  Dashboard,
  DynamicCase,
  LayoutNode,
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
    val indexed: Map[String, (LayoutNode, List[Int])] = {
      def walk(
          node: LayoutNode,
          path: List[Int]
      ): List[(String, (LayoutNode, List[Int]))] = {
        val self = (idPrefix + LayoutNode.pathId(path)) -> (node, path)
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

    val byEntity: Map[String, Set[String]] =
      indexed.toList
        .collect { case (id, (c: LayoutNode.Component, _)) => id -> c }
        .flatMap { case (id, c) => c.liveEntities.map(_ -> id) }
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap

    val dynamicIds: List[String] =
      indexed.collect { case (id, (_: LayoutNode.Dynamic, _)) => id }.toList
  }

  private val mainIndex = new Index(dashboard.card, "")

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
  private val allIndexed: Map[String, (LayoutNode, List[Int], String)] =
    (mainIndex :: surfaceIndexes.values.toList).flatMap { idx =>
      idx.indexed.map { case (id, (n, p)) => id -> (n, p, idx.idPrefix) }
    }.toMap

  /** Each dynamic container's query, by id (main + surfaces), for the
    * affected-by-change test. A group with no query matches every entity.
    */
  private val dynamicQueries: Map[String, Option[Predicate]] =
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
      id: String,
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
  def affectedDynamics(change: StateChange): List[(String, DynamicDelta)] =
    mainIndex.dynamicIds.flatMap(id => dynamicDelta(id, change).map(id -> _))

  /** Just the affected main-page dynamic ids (delta dropped) — the pre-Tier-1
    * shape, kept for callers/tests that only need the membership test.
    */
  def affectedDynamicIds(change: StateChange): List[String] =
    affectedDynamics(change).map(_._1)

  /** Like [[affectedDynamics]], scoped to one open surface. */
  def affectedSurfaceDynamics(
      surfaceId: String,
      change: StateChange
  ): List[(String, DynamicDelta)] =
    surfaceIndexes
      .get(surfaceId)
      .toList
      .flatMap(
        _.dynamicIds.flatMap(id => dynamicDelta(id, change).map(id -> _))
      )

  /** The surfaces baked into component `gid`'s host, ordered by their
    * `bakeIndex` (surface id as a stable tiebreak / fallback when a member
    * carries none). This is the ordered member list a cookie index (user mode)
    * selects among, and the first-match order (then, elseif…, else) state
    * selection walks.
    */
  private def bakeGroup(gid: String): List[String] =
    dashboard.surfaces.toList
      .collect {
        case (sid, s) if s.bakeInto.contains(gid) => (sid, s.bakeIndex)
      }
      .sortBy { case (sid, bi) => (bi.getOrElse(Int.MaxValue), sid) }
      .map(_._1)

  /** Whether a surface has a user-mode activation with `defaultOpen` set — the
    * "shown on first paint with no cookie / no click" flag, read in
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
  private def isStateGroup(gid: String): Boolean =
    bakeGroup(gid).headOption.exists(sid =>
      dashboard.surfaces
        .get(sid)
        .exists(_.activation match {
          case _: Activation.State => true
          case _                   => false
        })
    )

  /** Component ids that own a USER-selected bake group (tabs). Their HTML
    * depends on the client's `uiState` (the baked member is cookie-selected),
    * so their live patches must stay per-session and they are EXCLUDED from the
    * shared per-slug pass (see `Server`).
    */
  val userBakeOwnerIds: Set[String] =
    dashboard.surfaces.values
      .flatMap(_.bakeInto)
      .toSet
      .filterNot(isStateGroup)

  /** Component ids that own a STATE-selected bake group (If/else hosts). Their
    * HTML — selection included — is a pure function of entity state, so unlike
    * user-selected owners they stay IN the shared per-slug pass (rendered once
    * per slug, fanned out to every viewer).
    */
  val stateBakeOwnerIds: Set[String] =
    dashboard.surfaces.values
      .flatMap(_.bakeInto)
      .toSet
      .filter(isStateGroup)

  /** Whether a member surface's content subtree contains a user-selected bake
    * owner, following nested state members transitively. Feeds
    * [[sessionOnlyStateGroups]]: a user owner inside a state branch means that
    * branch's HTML bakes a cookie-selected member, so the branch cannot render
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
    * owner (tabs inside an If). Their host HTML embeds a cookie-selected
    * member, so their flips must be patched PER-SESSION with that session's
    * `uiState` — the Server excludes them from the shared flip path and mirrors
    * them in the per-session pass instead. Every other state group is shared.
    * Computed once per renderer (structure, not state).
    */
  val sessionOnlyStateGroups: Set[String] =
    stateBakeOwnerIds.filter(gid => bakeGroup(gid).exists(subtreeHasUserOwner))

  /** State-selected owner ids grouped by the index that contains the owner
    * node: key `""` = the main page, key `<sid>` = inside surface `<sid>`'s
    * content tree. This is the recursion structure of the transitive active-set
    * / affected-flip walks: a group is only VISIBLE through the chain of active
    * members above it, so each walk starts at one root's owners and descends
    * only into selected members.
    */
  private val stateGidsByRoot: Map[String, List[String]] = {
    val prefixToRoot: Map[String, String] =
      Map(mainIndex.idPrefix -> "") ++
        surfaceIndexes.map { case (sid, idx) => idx.idPrefix -> sid }
    stateBakeOwnerIds.toList.sorted
      .flatMap(gid =>
        allIndexed.get(gid).map { case (_, _, prefix) =>
          prefixToRoot(prefix) -> gid
        }
      )
      .groupMap(_._1)(_._2)
  }

  private def stateGidsAtRoot(root: String): List[String] =
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
    * cookie warnings, pure over the snapshot (so the Server can evaluate it
    * against a before AND after snapshot to detect a flip).
    */
  private[runtime] def resolveActiveByState(
      gid: String,
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
  private def conditionTouched(gid: String, change: StateChange): Boolean =
    change.previous match {
      case None => true
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
  ): List[String] =
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
  ): List[String] =
    affectedStateGroupsFrom(surfaceId, change, before, states)

  private def affectedStateGroupsFrom(
      root: String,
      change: StateChange,
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): List[String] =
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
      excluding: Set[String] = Set.empty
  ): Set[String] =
    activeStateSurfacesFrom("", states, excluding)

  /** Like [[activeStateSurfaces]], rooted at one surface's content tree (the
    * per-session pass, for state groups nested inside an open surface).
    */
  def activeStateSurfacesIn(
      surfaceId: String,
      states: Map[String, EntityState],
      excluding: Set[String] = Set.empty
  ): Set[String] =
    activeStateSurfacesFrom(surfaceId, states, excluding)

  private def activeStateSurfacesFrom(
      root: String,
      states: Map[String, EntityState],
      excluding: Set[String]
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
  def bakeMemberPrefixes(gid: String): List[String] =
    bakeGroup(gid).map(Renderer.surfacePrefix)

  /** Resolve which member of a USER-selected group `gid` is active, given the
    * client's (untrusted) `uiState`. Parses `uiState.get(gid)` with
    * `.toIntOption` and keeps it only when it indexes a real member; otherwise
    * falls back to the group's `defaultOpen` member (or index 0). The second
    * element is `Some(warning)` ONLY when a value was present but off
    * (unparseable, or an int out of range) — `None` when the cookie is absent
    * or valid. Pure: the single source of truth for both the chosen index and
    * the malformed check. State-selected groups never come through here — see
    * [[resolveActiveByState]].
    */
  private[runtime] def resolveActive(
      gid: String,
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
      case None => (fallback, None)
      case Some(raw) =>
        raw.toIntOption.filter(i => i >= 0 && i < n) match {
          case Some(i) => (i, None)
          case None =>
            (
              fallback,
              Some(
                s"ui-state cookie fhui_$gid='$raw' is not a valid tab index " +
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
    * cookies produce nothing; a cookie naming a state-selected group is ignored
    * (no client choice exists there to be malformed).
    */
  def uiStateAnomalies(uiState: Map[String, String]): List[String] =
    dashboard.surfaces.toList
      .flatMap(_._2.bakeInto)
      .distinct
      .filterNot(isStateGroup)
      .flatMap(gid => resolveActive(gid, uiState)._2)

  def componentsFor(entityId: String): Set[String] =
    mainIndex.byEntity.getOrElse(entityId, Set.empty)

  /** The live entities one node (by generated id) binds — the inverse of
    * [[componentsFor]], for edit-mode inspection ("debug this node"). Empty for
    * a dynamic group (its members are per-entity children with their own ids)
    * or an unknown id. Searches main + surface indices.
    */
  def entitiesForNode(id: String): List[String] =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _, _)) => c.liveEntities
      case _                                     => Nil
    }

  /** Main-page node ids whose HTML this entity drives, scoped to one surface.
    */
  def surfaceComponentsFor(surfaceId: String, entityId: String): Set[String] =
    surfaceIndexes
      .get(surfaceId)
      .fold(Set.empty)(_.byEntity.getOrElse(entityId, Set.empty))

  /** Like [[affectedDynamicIds]], scoped to one open surface. */
  def affectedSurfaceDynamicIds(
      surfaceId: String,
      change: StateChange
  ): List[String] =
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

  /** The theme as one `<style>` block: design tokens as `:root` custom
    * properties (dark overrides under `@media (prefers-color-scheme: dark)`, so
    * the page follows the browser) followed by the theme's inline `styles`.
    * Lives inside the patched body so a live reload (or a navigate swap)
    * repaints it. Empty when the theme carries no tokens or styles.
    */
  private val themeStyle: String = {
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

    if (parts.isEmpty) "" else parts.mkString("<style>", "", "</style>")
  }

  /** The dashboard frame, compiled once (like [[themeStyle]]) — a Mustache
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

  /** The dashboard body: theme + the walked layout tree, without the page
    * shell. This is what a navigate swap `inner`-patches into the stable
    * `#dashboard` container (so navigation also swaps the theme).
    */
  def renderBody(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): String =
    s"$themeStyle${render(dashboard.card, Nil, "", states, uiState)}"

  /** The full page: the theme's compiled `chrome` executed with `body =
    * renderBody(...)` — a stable `#dashboard` swap target (and, when the theme
    * provides one, the popup host) so in-place navigation and popups have fixed
    * patch targets.
    */
  def renderPage(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): String =
    chromeTemplate.execute(
      Renderer.javaContext(
        Map("body" -> renderBody(states, uiState)),
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
    * host that also binds a live entity) re-bakes the client's cookie-selected
    * member on a live patch — not the default one.
    */
  def renderNodeById(
      id: String,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[String] =
    allIndexed.get(id).map { case (node, path, prefix) =>
      render(node, path, prefix, states, uiState)
    }

  /** When component `id` owns a bake group (surfaces baked into it), bake the
    * SELECTED member as its `{{{bakeAs}}}` var so the host renders the active
    * panel/branch on first paint, and inject `bakeIndex` (a backend-known
    * structural var, like `id`) so a tabs template can seed its signal to the
    * selected index. Selection dispatches on the group's activation mode:
    * user-selected groups pick the `uiState`-selected member
    * ([[resolveActive]]; no cookie ⇒ the `defaultOpen` member / index 0),
    * state-selected groups pick the first member whose condition holds over
    * live state ([[resolveActiveByState]]) — and when NO condition holds, bake
    * the empty string, so the host still renders its wrapper with empty content
    * rather than stale HTML. The chrome wraps the content just as a later
    * open/switch/flip would, so first-paint and switch-back produce
    * byte-identical HTML. No bake group → both maps empty (absent Mustache vars
    * render empty). Returns `(baked, structural)`.
    */
  private def resolveBake(
      id: String,
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
        val id = idPrefix + LayoutNode.pathId(path)
        val childrenHtml = c.children.zipWithIndex.map { case (child, i) =>
          render(child, path :+ i, idPrefix, states, uiState)
        }
        val (baked, structural) = resolveBake(id, uiState, states)
        // `id` is a backend-injected template var (the author never supplies it).
        // Everything else fills from a slot, a baked surface var, or the injected
        // `bakeIndex`.
        val html = renderTemplate(
          c.card,
          Map("id" -> id) ++ structural ++ baked,
          c.slots,
          childrenHtml,
          states
        )
        // EVERY node is a cell: the backend owns the id'd `.fh-cell` wrapper, so
        // templates never carry `id="{{id}}"` themselves, every node is a
        // Datastar morph target, and containers lay their children out
        // uniformly (`.fh-cell` is the real flex/grid item — the themes style
        // it). Authored `cell` classes (fh-cols-*, …) ride on the
        // wrapper. Exceptions: a card that opted out via `CardDef.wrapAsCell =
        // false` (its root must stay a direct child of a framework-structural
        // parent, e.g. the tab anchors), and a bake-group owner with no live
        // entity of its own (e.g. `Tabs`, `If`) — it is never itself a morph
        // target (only its baked panel/branch, addressed separately via
        // `{{id}}_panel` etc., is), and it may host a card like `If`'s whose
        // template already carries `id="{{id}}"` as the surface host
        // swapHost/flipStateGroup address — an extra id'd wrapper there would
        // duplicate the id and leave the flip's outer-morph patch rootless
        // (Datastar drops a top-level element without an id). A bake-owner that
        // ALSO binds a live entity (e.g. `tabsLive`) still needs the wrapper: it
        // IS a morph target for its own state-driven re-render.
        if (noWrapCards(c.card)) html
        else if (c.liveEntities.isEmpty && bakeGroup(id).nonEmpty) html
        else
          s"""<div class="fh-cell${Renderer.cellClasses(
              c.cell
            )}" id="$id">$html</div>"""
      case d: LayoutNode.Dynamic =>
        renderDynamic(idPrefix + LayoutNode.pathId(path), d, states)
    }

  private def renderDynamic(
      id: String,
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
  def dynamicChildId(groupId: String, entityId: String): String =
    s"${groupId}_${Renderer.sanitize(entityId)}"

  /** The entity ids a dynamic group currently renders as children, in DOM order
    * (sorted by entity id, matching [[renderDynamic]]). A member is an entity
    * that passes the group's `query` AND matches one of its `cases` — an entity
    * matching the query but no case renders nothing, so it is not a member.
    * Pure over the given `states` snapshot, so the Server can compute
    * membership before AND after a change (feeding the child-insert successor +
    * the add/remove churn heuristic). Unknown / non-dynamic id ⇒ empty.
    */
  def dynamicMembers(
      groupId: String,
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
      groupId: String,
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
      groupId: String,
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
    val html = renderTemplate(c.card, Map("id" -> id), slots, Nil, states)
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
      case Predicate.And(items) => items.forall(matches(_, st))
      case Predicate.Or(items)  => items.exists(matches(_, st))
      case Predicate.Not(item)  => !matches(item, st)
      case Predicate.Cmp(property, op, value) =>
        val lhs = property match {
          case "domain" => st.domain
          case "state"  => st.state
          // The entity's identity itself — what lets a state-activation
          // condition pin one entity ("entity X is in state Y") and a dynamic
          // group enumerate an explicit entity set.
          case "entity_id" => st.entityId
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
