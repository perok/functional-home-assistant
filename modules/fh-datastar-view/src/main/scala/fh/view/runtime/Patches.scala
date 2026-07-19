package fh.view.runtime

import org.http4s.ServerSentEvent

/** One DOM patch the diff pass wants to send, rendered to a Datastar SSE event
  * at the edge ([[Patch.toSse]]). The diff no longer yields a uniform "HTML to
  * morph" — a [[Remove]] carries no HTML — so the diff pass speaks this small
  * ADT and only touches [[Datastar]] here.
  *
  *   - [[Morph]]: outer-morph an existing element (its `id` is inside `html`).
  *   - [[Insert]]: add a new element with an explicit `mode`/`selector`
  *     (`before` its DOM successor, or `append` into the group root).
  *   - [[Remove]]: delete the element matching `selector` (no HTML).
  */
private[runtime] enum Patch:
  case Morph(html: String)
  case Insert(html: String, mode: PatchMode, selector: String)
  case Remove(selector: String)

  def toSse: ServerSentEvent = this match
    case Patch.Morph(html)             => Datastar.patchElements(html)
    case Patch.Insert(html, mode, sel) => Datastar.patch(html, mode, Some(sel))
    case Patch.Remove(sel)             => Datastar.remove(sel)

/** The pure diff core, lifted out of [[Server]] so it is testable without a
  * booted server (no HA stub, no `Supervisor`, no SSE plumbing). Two entry
  * points:
  *
  *   - [[plan]] SELECTS what one state change touches — the affected static
  *     component ids, dynamic groups, and flipped state groups — for a given
  *     [[Scope]]. The shared per-slug pass and the per-session pass differ only
  *     in that scope (a shared pass has no `uiState` and no open surfaces; a
  *     session pass carries both), which is exactly what collapses their two
  *     formerly-parallel assembly blocks into one.
  *   - [[diff]] DIFFS that selection against a cache, returning the updated
  *     cache and the SSE patches to emit — the single diff contract both passes
  *     share.
  *
  * Everything here is pure over the entity snapshot; the caller ([[Server]])
  * owns the `Ref`/`IO` that reads the snapshot and `modify`s the cache.
  */
private[runtime] object Patches {

  /** A selection of what one [[StateChange]] touches, ready to [[diff]] against
    * a cache. Bundles the assembled `staticIds`/`dynamics`/`flips` with the
    * render inputs (`change`/`states`/`before`/`uiState`) they are diffed with —
    * replacing the nine-positional-argument call the two passes used to make.
    */
  case class DiffRequest(
      staticIds: List[String],
      dynamics: List[(String, DynamicDelta)],
      flips: List[String],
      change: StateChange,
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      uiState: Map[String, String]
  )

  /** Which pass is selecting — the only real difference between the shared and
    * per-session assembly.
    *
    *   - [[Shared]]: the per-slug pass. Nodes whose HTML is a pure function of
    *     entity state (USER bake-group owners excluded, STATE-selected groups
    *     included); no `uiState`, no open surfaces.
    *   - [[Session]]: the per-connection pass. The client's open surfaces plus
    *     the main-page USER bake-group owners and session-only state groups,
    *     rendered with this session's `uiState`.
    */
  enum Scope:
    case Shared
    case Session(open: Set[String], uiState: Map[String, String])

  /** The snapshot as it was BEFORE this change — the current snapshot with the
    * changed entity rewound to its `previous` value (or dropped when it was
    * newly seen). Lets a dynamic group compute its membership before vs. after
    * from a single [[StateChange]], without the store tracking prior snapshots.
    */
  def beforeSnapshot(
      states: Map[String, EntityState],
      change: StateChange
  ): Map[String, EntityState] =
    change.previous.fold(states - change.entityId)(p =>
      states.updated(change.entityId, p)
    )

  /** Select what `change` touches for `scope`, against `states`. The shared and
    * per-session passes are one method now: the scope decides whether open
    * surfaces and a client `uiState` enter the selection.
    *
    * The state-selected extension (ADR 0002's shared/per-session split, cut by
    * activation mode):
    *
    *   - '''Flips''': each state group whose selection this change moves gets
    *     its HOST re-rendered ([[Renderer]]'s bake picks the newly-selected
    *     member against CURRENT state), morphed, and its members' cache entries
    *     pruned ([[flipStateGroup]]). Shared takes the main-rooted groups minus
    *     the session-only ones; a session takes the groups inside its open
    *     surfaces (containment) plus the main-rooted session-only ones.
    *   - '''Active-member liveness''': each surface in the relevant transitive
    *     active set (excluding just-flipped subtrees — their host morph
    *     re-rendered them wholesale) contributes its components binding the
    *     changed entity plus its query-affected dynamics. Inactive members are
    *     never consulted — the hidden-branch no-updates guarantee, structural:
    *     their ids simply never enter the selection.
    */
  def plan(
      renderer: Renderer,
      states: Map[String, EntityState],
      change: StateChange,
      scope: Scope
  ): DiffRequest = {
    val before = beforeSnapshot(states, change)
    scope match {
      case Scope.Shared =>
        val flips = renderer
          .affectedStateGroups(change, before, states)
          .filterNot(renderer.sessionOnlyStateGroups)
        val flipped = flips.toSet
        val activeSids = renderer.activeStateSurfaces(
          states,
          excluding = flipped ++ renderer.sessionOnlyStateGroups
        )
        val staticIds =
          (renderer
            .componentsFor(change.entityId)
            .toList
            // User owners bake a cookie-selected member (per-session); a
            // session-only state owner bakes one transitively (its branch holds
            // tabs). State owners otherwise stay in the shared pass — selection
            // included, their HTML is a pure function of entity state.
            .filterNot(id =>
              renderer.userBakeOwnerIds(id) ||
                renderer.sessionOnlyStateGroups(id)
            ) ++
            activeSids.toList.flatMap(sid =>
              renderer.surfaceComponentsFor(sid, change.entityId).toList
            )).distinct
            // A flipped host is patched (with prune) by the flip path; don't
            // also morph it as a plain static.
            .filterNot(flipped)
        val dynamics =
          renderer.affectedDynamics(change) ++
            activeSids.toList.flatMap(sid =>
              renderer.affectedSurfaceDynamics(sid, change)
            )
        DiffRequest(
          staticIds,
          dynamics,
          flips,
          change,
          states,
          before,
          Map.empty
        )

      case Scope.Session(open, uiState) =>
        // State-group flips this session must patch itself: groups inside its
        // open surfaces (containment), plus the main-rooted session-only ones
        // (rendered with this session's uiState).
        val openFlips = open.toList.flatMap(sid =>
          renderer.affectedStateGroupsIn(sid, change, before, states)
        )
        val sessionOnlyFlips = renderer
          .affectedStateGroups(change, before, states)
          .filter(renderer.sessionOnlyStateGroups)
        val flips = (openFlips ++ sessionOnlyFlips).distinct
        val flipped = flips.toSet
        // Active state members visible only to this session: those nested inside
        // its open surfaces, plus the main-rooted subtrees the shared pass
        // skipped as session-only (all-active minus shared-active is exactly
        // those). Just-flipped subtrees are excluded — the flip's host morph
        // re-renders them wholesale.
        val openNested = open.toList.flatMap(sid =>
          renderer.activeStateSurfacesIn(sid, states, flipped).toList
        )
        val sessionOnlySids =
          if (renderer.sessionOnlyStateGroups.isEmpty) Set.empty[String]
          else
            renderer.activeStateSurfaces(states, flipped) --
              renderer.activeStateSurfaces(
                states,
                flipped ++ renderer.sessionOnlyStateGroups
              )
        val sids = (open.toList ++ openNested ++ sessionOnlySids).distinct
        // Static components: main-page owners whose bake is per-session
        // (user-selected, or state-selected with a user owner in a branch)
        // binding this entity (a dynamic group is never a bake owner, so main
        // dynamics all belong to the shared pass), plus each visible surface's
        // components binding it.
        val mainIds =
          renderer
            .componentsFor(change.entityId)
            .toList
            .filter(id =>
              renderer.userBakeOwnerIds(id) ||
                renderer.sessionOnlyStateGroups(id)
            )
        val surfaceStaticIds = sids.flatMap(sid =>
          renderer.surfaceComponentsFor(sid, change.entityId).toList
        )
        val staticIds =
          (mainIds ++ surfaceStaticIds).distinct.filterNot(flipped)
        // Dynamic groups this change can move the entity in/out of, per visible
        // surface (surface-namespaced ids never collide across surfaces).
        val dynamics =
          sids
            .flatMap(sid => renderer.affectedSurfaceDynamics(sid, change))
            .distinct
        DiffRequest(staticIds, dynamics, flips, change, states, before, uiState)
    }
  }

  /** Diff a [[DiffRequest]]'s static component ids + affected dynamic groups +
    * flipped state groups against `cache`, returning the updated cache and the
    * SSE patches to emit. The single diff contract shared by the per-slug and
    * per-session passes.
    *
    *   - A flipped state group morphs its HOST (the newly-selected member baked
    *     against current state) and prunes its members' cache entries
    *     ([[flipStateGroup]]). Flips run FIRST: the prune must precede any diff
    *     that could suppress a member fragment against a pre-flip entry.
    *   - Static components outer-morph when their HTML actually changed.
    *   - A dynamic group with an [[DynamicDelta.InPlace]] member re-renders and
    *     outer-morphs that ONE child; an add/remove is patched per-entity when
    *     the churn is a small fraction of the group, else the whole group
    *     repaints ([[renderDynamicGroup]] applies [[Server.MaxChurnFraction]]).
    *   - A whole-group repaint prunes that group's child cache entries so the
    *     next per-entity patch re-establishes from a known base.
    *
    * Pure (all rendering is pure over `states`); the caller wraps it in the
    * cache Ref's `modify`.
    */
  def diff(
      renderer: Renderer,
      cache: Map[String, String],
      req: DiffRequest
  ): (Map[String, String], List[ServerSentEvent]) = {
    val (cacheAfterFlips, flipPatches) =
      req.flips.foldLeft((cache, List.empty[Patch])) { case ((c, acc), gid) =>
        val (c2, ps) = flipStateGroup(renderer, c, gid, req.states, req.uiState)
        (c2, acc ++ ps)
      }
    val rendered =
      req.staticIds.flatMap(id =>
        renderer.renderNodeById(id, req.states, req.uiState).map(id -> _)
      )
    val (cacheAfterStatic, staticPatches) =
      rendered.foldLeft((cacheAfterFlips, List.empty[Patch])) {
        case ((c, acc), (id, html)) =>
          if (c.get(id).contains(html)) (c, acc)
          else (c.updated(id, html), acc :+ Patch.Morph(html))
      }
    val (finalCache, dynPatches) =
      req.dynamics.foldLeft((cacheAfterStatic, List.empty[Patch])) {
        case ((c, acc), (gid, delta)) =>
          val (c2, ps) =
            renderDynamicGroup(
              renderer,
              c,
              gid,
              delta,
              req.change,
              req.states,
              req.before
            )
          (c2, acc ++ ps)
      }
    (finalCache, (flipPatches ++ staticPatches ++ dynPatches).map(_.toSse))
  }

  /** Patch one FLIPPED state-selected bake group: re-render its host node — the
    * bake owner, whose render bakes the newly-selected member against CURRENT
    * state ([[Renderer]]'s `resolveBake`) — morph it, and prune the group's
    * cache entries: the host id plus every member's `s_<sid>__` node prefix. The
    * same prune contract as [[repaintGroup]], and for the same reason:
    * hidden-branch churn deliberately leaves member entries stale (the silence
    * guarantee), so a flip must drop them — otherwise a re-revealed node whose
    * HTML happens to equal its pre-flip entry would be suppressed while the
    * client's DOM (repainted by this very morph) has moved on. Emits nothing
    * when the host HTML is unchanged (defensive; the caller only passes groups
    * whose selection actually moved).
    */
  private def flipStateGroup(
      renderer: Renderer,
      cache: Map[String, String],
      gid: String,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): (Map[String, String], List[Patch]) =
    renderer.renderNodeById(gid, states, uiState) match {
      case None       => (cache, Nil)
      case Some(html) =>
        if (cache.get(gid).contains(html)) (cache, Nil)
        else {
          val prefixes = renderer.bakeMemberPrefixes(gid)
          val pruned = cache.filterNot { case (k, _) =>
            k == gid || prefixes.exists(k.startsWith)
          }
          (pruned.updated(gid, html), List(Patch.Morph(html)))
        }
    }

  /** Patch one affected dynamic group. [[DynamicDelta.InPlace]] re-renders the
    * changed entity's single child and morphs it (unless a case change actually
    * moved membership — then it falls through to the membership path). An add or
    * remove diffs the group's rendered membership before vs. after and either
    * patches the delta per-entity or repaints the whole group
    * ([[renderMembershipChange]]).
    */
  private def renderDynamicGroup(
      renderer: Renderer,
      cache: Map[String, String],
      gid: String,
      delta: DynamicDelta,
      change: StateChange,
      states: Map[String, EntityState],
      before: Map[String, EntityState]
  ): (Map[String, String], List[Patch]) =
    delta match {
      case DynamicDelta.InPlace =>
        // The query boundary was not crossed. Normally re-render just this
        // entity's card; but a case that gained/lost this entity moves the
        // rendered membership even at a fixed query match, so reconcile against
        // the actual member lists and fall through if they differ.
        val membersBefore = renderer.dynamicMembers(gid, before)
        val membersAfter = renderer.dynamicMembers(gid, states)
        if (membersBefore == membersAfter)
          renderer.renderDynamicChild(gid, change.entityId, states) match {
            case None => (cache, Nil) // not a current member — nothing to do
            case Some(html) =>
              val cid = renderer.dynamicChildId(gid, change.entityId)
              if (cache.get(cid).contains(html)) (cache, Nil)
              else (cache.updated(cid, html), List(Patch.Morph(html)))
          }
        else
          renderMembershipChange(
            renderer,
            cache,
            gid,
            membersBefore,
            membersAfter,
            states
          )
      case DynamicDelta.Added | DynamicDelta.Removed =>
        renderMembershipChange(
          renderer,
          cache,
          gid,
          renderer.dynamicMembers(gid, before),
          renderer.dynamicMembers(gid, states),
          states
        )
    }

  /** Apply a membership change to a dynamic group. When the churn (entities
    * added + removed) is a small enough fraction of the group's rendered size
    * ([[Server.MaxChurnFraction]]) AND the group is already established in the
    * cache, patch the delta per-entity: a `remove` patch per departed member and
    * an `insert` (`before` its successor in DOM order, or `append` into the
    * group) per new member. Otherwise — heavy churn, an empty/last-member group,
    * or a group not yet in the cache (post-reload) — repaint the whole group and
    * prune its child cache entries, so a client re-establishes from a known
    * base.
    *
    * Idempotency: the per-entity path fires only for an ESTABLISHED group, so
    * the first membership change after a renderer reload (fresh cache) always
    * repaints; a `remove` of an already-absent id is a no-op (see
    * [[Datastar.remove]]). Residual race: a client that missed an `insert` in
    * the connect gap (subscribed to the shared topic just after the patch) will
    * lack that child until the next whole-group repaint — an in-place morph
    * can't heal an id absent from that client's DOM. Bounded and self-healing;
    * whole-group repaints (heavy churn / reload) re-sync every client.
    */
  private def renderMembershipChange(
      renderer: Renderer,
      cache: Map[String, String],
      gid: String,
      membersBefore: List[String],
      membersAfter: List[String],
      states: Map[String, EntityState]
  ): (Map[String, String], List[Patch]) = {
    val beforeSet = membersBefore.toSet
    val afterSet = membersAfter.toSet
    val added = membersAfter.filterNot(beforeSet)
    val removed = membersBefore.filterNot(afterSet)
    val churn = added.size + removed.size
    val shown = membersBefore.size
    // Per-entity pays off only when the churn is a MINORITY of the group: at the
    // boundary (e.g. 1 of 2 members, or the last member) a whole-group repaint
    // is cheaper than juggling insert/remove patches. Strict `<` so exactly half
    // repaints. `MaxChurnFraction` is tunable.
    val perEntity = churn > 0 && churn < Server.MaxChurnFraction * shown
    val established =
      cache.contains(gid) || cache.keysIterator.exists(_.startsWith(gid + "_"))
    if (!perEntity || !established) repaintGroup(renderer, cache, gid, states)
    else {
      val (afterRemoves, removePatches) =
        removed.foldLeft((cache, List.empty[Patch])) { case ((c, acc), e) =>
          val cid = renderer.dynamicChildId(gid, e)
          (c - cid, acc :+ Patch.Remove("#" + cid))
        }
      val (afterAdds, addPatches) =
        added.sorted.foldLeft((afterRemoves, List.empty[Patch])) {
          case ((c, acc), e) =>
            renderer.renderDynamicChild(gid, e, states) match {
              case None       => (c, acc) // defensive: not renderable, skip
              case Some(html) =>
                val cid = renderer.dynamicChildId(gid, e)
                // Insert before the first EXISTING (pre-change) member sorting
                // after this one; if none, append into the group root.
                val patch = membersBefore.find(_.compareTo(e) > 0) match {
                  case Some(succ) =>
                    Patch.Insert(
                      html,
                      PatchMode.Before,
                      "#" + renderer.dynamicChildId(gid, succ)
                    )
                  case None =>
                    Patch.Insert(html, PatchMode.Append, "#" + gid)
                }
                (c.updated(cid, html), acc :+ patch)
            }
        }
      // Drop the stale group-level cache entry: per-entity edits diverge the DOM
      // from the last whole-group render, so a later repaint must always re-emit
      // rather than diff against an entry that no longer describes the DOM.
      (afterAdds - gid, removePatches ++ addPatches)
    }
  }

  /** Repaint a whole dynamic group by id and prune its child cache entries (so
    * the next per-entity patch re-establishes from a known base). Emits nothing
    * when the group's HTML is unchanged (the defensive path).
    */
  private def repaintGroup(
      renderer: Renderer,
      cache: Map[String, String],
      gid: String,
      states: Map[String, EntityState]
  ): (Map[String, String], List[Patch]) =
    renderer.renderNodeById(gid, states) match {
      case None       => (cache, Nil)
      case Some(html) =>
        if (cache.get(gid).contains(html)) (cache, Nil)
        else {
          val pruned = cache.filterNot { case (k, _) =>
            k == gid || k.startsWith(gid + "_")
          }
          (pruned.updated(gid, html), List(Patch.Morph(html)))
        }
    }
}
