package fh.view.runtime

import fh.view.model.{DomId, NodeId}
import fh.view.model.DomId.selector
import org.http4s.ServerSentEvent

/** One DOM patch the diff pass wants to send, rendered to a Datastar SSE event
  * at the edge ([[Patch.toSse]]). The diff no longer yields a uniform "HTML to
  * morph" — a [[Remove]] carries no HTML — so the diff pass speaks this small
  * ADT and only touches [[Datastar]] here.
  *
  *   - [[Morph]]: outer-morph an existing element (its `id` is inside `html`).
  *   - [[Insert]]: add a new element relative to an explicit `target` (`before`
  *     its DOM successor, or `append` into the group root).
  *   - [[Remove]]: delete the target element (no HTML).
  *
  * [[Insert]] and [[Remove]] name their target as a [[DomId]], not a bare
  * selector string: the whole point of the split is that a patch aims at ONE
  * element, and a [[NodeId]] reaching a target slot would be exactly the
  * confusion [[fh.view.model.NodeId]] exists to make impossible. The `#` is
  * added here, at the wire edge.
  */
private[runtime] enum Patch:
  case Morph(html: String)
  case Insert(html: String, mode: PatchMode, target: DomId)
  case Remove(target: DomId)

  def toSse: ServerSentEvent = this match
    case Patch.Morph(html)                => Datastar.patchElements(html)
    case Patch.Insert(html, mode, target) =>
      Datastar.patch(html, mode, Some(target.selector))
    case Patch.Remove(target) => Datastar.remove(target.selector)

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
    * render inputs (`change`/`states`/`before`/`uiState`) they are diffed with
    * — replacing the nine-positional-argument call the two passes used to make.
    */
  case class DiffRequest(
      staticIds: List[NodeId],
      dynamics: List[(NodeId, DynamicDelta)],
      flips: List[NodeId],
      change: StateChange,
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      uiState: Map[String, String],
      // When `states` was read (see [[Stamp]]), applied to every fragment and
      // mutation this request records. The version is read atomically WITH the
      // snapshot, so a fragment can never claim a version its HTML does not
      // reflect.
      stamp: Stamp
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
      stamp: Stamp,
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
            // User owners bake a client-selected member (per-session); a
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
          Map.empty,
          stamp
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
        val sessionOnlySids: Set[String] =
          if (renderer.sessionOnlyStateGroups.isEmpty) Set.empty
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
        DiffRequest(
          staticIds,
          dynamics,
          flips,
          change,
          states,
          before,
          uiState,
          stamp
        )
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
      log: FragmentLog,
      req: DiffRequest
  ): (FragmentLog, List[ServerSentEvent]) = {
    val at = req.stamp
    val (logAfterFlips, flipPatches) =
      req.flips.foldLeft((log, List.empty[Patch])) { case ((c, acc), gid) =>
        val (c2, ps) =
          flipStateGroup(renderer, c, gid, req.before, req.states, at)
        (c2, acc ++ ps)
      }
    val rendered =
      req.staticIds.flatMap(id =>
        renderer.renderNodeById(id, req.states, req.uiState).map(id -> _)
      )
    val (logAfterStatic, staticPatches) =
      rendered.foldLeft((logAfterFlips, List.empty[Patch])) {
        case ((c, acc), (id, html)) =>
          if (c.holds(id, html)) (c, acc)
          else (c.set(id, html, at.version), acc :+ Patch.Morph(html))
      }
    val (finalLog, dynPatches) =
      req.dynamics.foldLeft((logAfterStatic, List.empty[Patch])) {
        case ((c, acc), (gid, delta)) =>
          val (c2, ps) =
            renderDynamicGroup(
              renderer,
              c,
              gid,
              delta,
              req.change,
              req.states,
              req.before,
              at
            )
          (c2, acc ++ ps)
      }
    (finalLog, (flipPatches ++ staticPatches ++ dynPatches).map(_.toSse))
  }

  /** Everything a client resuming at cursor `v` is owed, as SSE events. The
    * pure core of the resume path (ADR 0011): the caller reads the log +
    * snapshot and writes the stream; the ordering argument lives here.
    *
    * '''ONE rule, one candidate set, one snapshot:'''
    *
    * > Candidates = nodes whose logged version is `>= v`, plus every node in an
    * > OPEN surface. Render each from the current snapshot, and send it when >
    * `version >= v || fingerprint != stored`, a MISSING entry counting as >
    * "send".
    *
    * The two disjuncts are two different ignorances. `version >= v` means the
    * node changed at or after the cursor, so the client may never have applied
    * it — send what we have now, which is at least as new. `fingerprint !=
    * stored` is the UNTRACKED case: a surface nothing rendered while nobody was
    * viewing it, where only re-rendering can tell whether the client's DOM is
    * current.
    *
    * That replaces two mechanisms with one. Per-session fragments used to be
    * painted fresh on every resume (their HTML baked a client-selected member
    * and their only diff cache died with the previous connection), and an open
    * popup needed a restore branch of its own. Both are now just candidates:
    * the popup's nodes are in `open`, and a container's `self` does not contain
    * its mount, so a client returning after a long absence gets the bar's new
    * HTML and keeps its panel.
    *
    * '''The cursor selects which nodes; the renderer decides what to send.'''
    * The cursor is never consulted for content, which is what makes filtering
    * patches out of a cursor-bearing stream safe.
    *
    * Morphs go out first, then the [[Mutation]]s. A [[Mutation.Placed]] emits
    * `remove` AND `insert` for itself, which makes it idempotent in the
    * client's DOM whatever state that DOM is in — present at the wrong
    * position, present at the right one, or absent. That self-containment is
    * what lets an arrival and a re-order be the same operation, and it removes
    * any cross-mutation ordering requirement except one:
    *
    * '''Placed nodes are emitted descending by current position.''' An insert
    * needs an anchor that EXISTS in the client's DOM, and the only anchors we
    * can name are current members. Going high-to-low makes that provable: the
    * anchor is either a member the client already had, or one placed a moment
    * ago. Ascending fails — a node's anchor can be a later node not yet
    * inserted. This relies only on server and client agreeing on SOME total
    * order over members, which [[Renderer.dynamicMembers]] provides; nothing
    * here depends on that order being by entity id, so an author-chosen sort
    * works unchanged.
    *
    * A `Placed` is dropped rather than emitted when its entity is no longer a
    * member — it arrived and left while the client was away. Unreachable in
    * practice, since the LATEST mutation would then be a `Gone`, so this is a
    * defence rather than a case. (Placements an ancestor's HTML already carries
    * were dropped earlier, by [[FragmentLog.since]].)
    */
  def resume(
      renderer: Renderer,
      log: FragmentLog,
      states: Map[String, EntityState],
      v: Long,
      open: Set[String] = Set.empty
  ): List[ServerSentEvent] = {
    val owed = log.since(v)
    // Split by CONTAINER KIND, because the two mounts want different tools: a
    // dynamic group's needs per-member deltas that preserve siblings, a state
    // group's holds one member and is simply overwritten.
    val (dynamic, branch) = owed.moved.partition { case (_, m) =>
      renderer.isDynamicContainer(m.container)
    }
    val gone = dynamic.collect { case (nodeId, _: Mutation.Gone) => nodeId }
    // Replaying a flip is the whole reason it is recorded structurally: without
    // it a client that was away across one gets the removal and nothing else,
    // and sits on an EMPTY host until something unrelated moves. ONE `Inner` per
    // affected container, through the same primitive the live flip uses — so a
    // client that missed a flip is treated byte-identically to one that did not.
    val branchFills = branch
      .groupBy { case (_, m) => m.container }
      .toList
      .sortBy(_._1)
      .flatMap { case (gid, entries) =>
        branchPatch(
          renderer,
          gid,
          renderer.renderMount(gid, states).map(_._2).reduceOption(_ + _),
          entries.map(_._1).sorted.headOption
        )
      }
    val places = dynamic
      .collect { case (nodeId, p: Mutation.Placed) => (nodeId, p) }
      .groupBy { case (_, p) => p.container }
      .toList
      .sortBy(_._1)
      .flatMap { case (gid, inGroup) =>
        val members = renderer.dynamicMembers(gid, states)
        val position = members.zipWithIndex.toMap
        inGroup
          // Still a member; anything an ancestor is re-supplying was already
          // dropped by `since`.
          .flatMap { case (nodeId, p) =>
            p.member match {
              case MemberKey.Entity(e) =>
                position.get(e).map((nodeId, e, p.member, _))
              case _: MemberKey.Surface => None
            }
          }
          .sortBy { case (_, _, _, at) => -at }
          .flatMap { case (nodeId, entityId, member, _) =>
            // Rendered NOW, not read back: the snapshot is at least as fresh as
            // anything the log could have kept, and it is what lets the log hold
            // a digest instead of bytes. The member resolves ITSELF — the whole
            // point of [[MemberKey]] being a sum type.
            member.render(renderer, gid, states).toList.flatMap { html =>
              // Every current member is a usable anchor here: emitting
              // descending by position means a node's successor was either
              // already in the client's DOM or placed a moment ago.
              List(
                Patch.Remove(renderer.elementId(nodeId)),
                insertInto(renderer, gid, members, entityId, _ => true, html)
              )
            }
          }
      }
    // Containers whose membership history no longer reaches this cursor: the
    // delta is uncomputable, so the mount is filled wholesale. `Inner` is
    // all-or-nothing over a mount's children, so this cannot be partial — which
    // is precisely why it is the fallback of last resort, and why it is worth
    // having only because it replaced a whole-BODY repaint.
    val refills = owed.refill.sorted.map { gid =>
      Patch.Insert(
        renderer.renderMount(gid, states).map(_._2).mkString,
        PatchMode.Inner,
        renderer.mountId(gid)
      )
    }
    // The second candidate set: an open surface's nodes, which the cursor alone
    // would not name. Sorted for a deterministic order (ids are location-derived,
    // so this is document order among siblings), and dropped when a mutation or a
    // refill is already re-supplying an ancestor.
    val fromOpen = open.toList
      .flatMap(renderer.surfaceNodeIds)
      .distinct
      .filterNot(id =>
        owed.nodes.contains(id) || owed.moved.exists(_._1 == id) ||
          log.coveredByMutation(id, owed.moved.map(_._1).toSet ++ owed.refill)
      )
      .sorted
      .flatMap(id =>
        renderer.renderLogged(id, states).flatMap { html =>
          // A MISSING entry counts as "send": unknown, so tell the client.
          Option.when(!log.holds(id, html))(Patch.Morph(html))
        }
      )
    (owed.nodes
      .flatMap(id => renderer.renderLogged(id, states).map(Patch.Morph(_))) ++
      fromOpen ++
      gone.toList.sorted.map(id => Patch.Remove(renderer.elementId(id))) ++
      branchFills ++ places ++ refills).map(_.toSse)
  }

  /** The patch that puts `html` at `entity`'s place in group `gid`: `before`
    * the nearest member ordered after it that the client's DOM can anchor on
    * (`anchorable`), or `append`ed into the group root when there is none.
    *
    * ONE anchor rule for both the live add path and the resume replay, because
    * an insert is the same problem in both: name a sibling that is really
    * there. What differs is only which siblings qualify, which is the
    * predicate.
    *
    * It reads the order out of `ordered` — the list [[Renderer.dynamicMembers]]
    * produced — rather than comparing entity ids. That is what keeps this
    * correct if member order ever becomes author-chosen: the live path used to
    * compare ids directly and silently required id-sorted membership, which
    * disagreed with the resume path doing it positionally.
    */
  private def insertInto(
      renderer: Renderer,
      gid: NodeId,
      ordered: List[String],
      entity: String,
      anchorable: String => Boolean,
      html: String
  ): Patch =
    ordered.dropWhile(_ != entity).drop(1).find(anchorable) match {
      case Some(succ) =>
        Patch.Insert(
          html,
          PatchMode.Before,
          renderer.elementId(renderer.dynamicChildId(gid, succ))
        )
      case None =>
        Patch.Insert(html, PatchMode.Append, renderer.mountId(gid))
    }

  /** Patch one FLIPPED state-selected bake group — as a MEMBERSHIP DELTA, the
    * same record a dynamic group already keeps.
    *
    * '''An `If` flip is a membership change on a list of one:''' the old branch
    * is [[Mutation.Gone]], the new one is [[Mutation.Placed]] into the group's
    * mount. Repeated flips collapse by latest-wins per node id.
    *
    * On the WIRE that is a single `Inner` at the mount, not a `remove` plus an
    * `append`. A bake group has one hole, so its mount holds at most one
    * member: there are no siblings to preserve and no position to fix, which
    * means overwriting the mount IS the delta rather than a wholesale fallback.
    * It is also idempotent by construction — it lands the same whether the
    * client currently holds the old branch, the new one, or nothing, so the
    * paired `remove` that makes a dynamic member's re-order safe buys nothing
    * here. A condition matching NO branch is the one other shape: a `Gone` with
    * no `Placed`, emitted as a plain `remove` (an `Inner` of empty content is
    * not a well-formed patch).
    *
    * It used to morph the HOST instead, whose HTML embedded the selected branch
    * — the one place a state group's patch carried other nodes. Splitting
    * content from structure keeps the useful half: the log records WHICH member
    * is in the mount (identity), never what it holds. If the container's record
    * moved when a CHILD's content changed, every child change would re-supply
    * the container, which is exactly the problem this design exists to remove.
    *
    * Without the structural half the deletion would leave a hole: a client
    * disconnected across a flip would show the old branch '''permanently''' —
    * the new branch's nodes would arrive as morphs against ids its DOM lacks
    * (silent no-ops) and nothing would remove the old ones. `selectedSurfaces`
    * does `filterNot(isStateGroup)`, so a branch is never in `open` either.
    *
    * The prune stays, and for its original reason: hidden-branch churn
    * deliberately leaves member entries stale (the silence guarantee), so a
    * re-revealed node whose HTML happens to equal its pre-flip entry would be
    * suppressed while the client's DOM has moved on.
    */
  private def flipStateGroup(
      renderer: Renderer,
      log: FragmentLog,
      gid: NodeId,
      before: Map[String, EntityState],
      states: Map[String, EntityState],
      at: Stamp
  ): (FragmentLog, List[Patch]) = {
    def memberAt(
        snapshot: Map[String, EntityState]
    ): Option[String] =
      renderer
        .resolveActiveByState(gid, snapshot)
        .flatMap(renderer.bakeMembers(gid).lift)
    val was = memberAt(before)
    val now = memberAt(states)
    // Defensive: the caller only passes groups whose selection actually moved.
    if (was == now) (log, Nil)
    else {
      val prefixes = renderer.bakeMemberPrefixes(gid)
      val pruned = log.invalidateWhere(k => prefixes.exists(k.startsWith))
      val departed = was.map(renderer.surfaceContentId)
      val arrived = now.flatMap(sid =>
        val member = MemberKey.Surface(sid)
        member
          .render(renderer, gid, states)
          .map(html => (renderer.surfaceContentId(sid), member, html))
      )
      val withGone = departed.foldLeft(pruned)(_.removed(gid, _, at))
      val withPlaced = arrived.foldLeft(withGone) {
        case (l, (nodeId, member, html)) =>
          l.placed(gid, member, nodeId, html, at)
      }
      (withPlaced, branchPatch(renderer, gid, arrived.map(_._3), departed))
    }
  }

  /** Put `content` in a STATE group's mount — one patch, whatever the client's
    * DOM currently holds there.
    *
    * The mount takes at most one member, so `Inner` is both the delta (no
    * siblings exist to preserve) and idempotent (it lands the same on the old
    * branch, the new one, or an empty host). `departed` is only consulted when
    * nothing holds now: an `Inner` of empty content is not a well-formed patch,
    * so the emptying case stays a `remove` of the branch that left.
    *
    * Shared by the live flip and the resume replay, so a client that missed a
    * flip gets byte-identical treatment to one that did not.
    */
  private def branchPatch(
      renderer: Renderer,
      gid: NodeId,
      content: Option[String],
      departed: Option[NodeId]
  ): List[Patch] =
    content match {
      case Some(html) =>
        List(Patch.Insert(html, PatchMode.Inner, renderer.mountId(gid)))
      case None =>
        departed.map(id => Patch.Remove(renderer.elementId(id))).toList
    }

  /** Patch one affected dynamic group. [[DynamicDelta.InPlace]] re-renders the
    * changed entity's single child and morphs it (unless a case change actually
    * moved membership — then it falls through to the membership path). An add
    * or remove diffs the group's rendered membership before vs. after and
    * either patches the delta per-entity or repaints the whole group
    * ([[renderMembershipChange]]).
    */
  private def renderDynamicGroup(
      renderer: Renderer,
      log: FragmentLog,
      gid: NodeId,
      delta: DynamicDelta,
      change: StateChange,
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      at: Stamp
  ): (FragmentLog, List[Patch]) =
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
            case None => (log, Nil) // not a current member — nothing to do
            case Some(html) =>
              val cid = renderer.dynamicChildId(gid, change.entityId)
              if (log.holds(cid, html)) (log, Nil)
              else (log.set(cid, html, at.version), List(Patch.Morph(html)))
          }
        else
          renderMembershipChange(
            renderer,
            log,
            gid,
            membersBefore,
            membersAfter,
            states,
            at
          )
      case DynamicDelta.Added | DynamicDelta.Removed =>
        renderMembershipChange(
          renderer,
          log,
          gid,
          renderer.dynamicMembers(gid, before),
          renderer.dynamicMembers(gid, states),
          states,
          at
        )
    }

  /** Apply a membership change to a dynamic group. When the churn (entities
    * added + removed) is a small enough fraction of the group's rendered size
    * ([[Server.MaxChurnFraction]]) AND the group is already established in the
    * cache, patch the delta per-entity: a `remove` patch per departed member
    * and an `insert` (`before` its successor in DOM order, or `append` into the
    * group) per new member. Otherwise — heavy churn, an empty/last-member
    * group, or a group not yet in the cache (post-reload) — repaint the whole
    * group and prune its child cache entries, so a client re-establishes from a
    * known base.
    *
    * Resume bookkeeping: departures are tombstoned (they replay verbatim), an
    * arrival marks the group structural (its `insert` cannot be replayed) — see
    * [[FragmentLog]].
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
      log: FragmentLog,
      gid: NodeId,
      membersBefore: List[String],
      membersAfter: List[String],
      states: Map[String, EntityState],
      at: Stamp
  ): (FragmentLog, List[Patch]) = {
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
    val established = log.hasChildOf(gid)
    // The query boundary moved but the RENDERED membership did not — an entity
    // matching the query but no case is not a member either way, so there is
    // nothing to send and nothing to fill.
    if (churn == 0) (log, Nil)
    else if (!perEntity || !established)
      fillGroup(renderer, log, gid, states, at)
    else {
      val (afterRemoves, removePatches) =
        removed.foldLeft((log, List.empty[Patch])) { case ((c, acc), e) =>
          val cid = renderer.dynamicChildId(gid, e)
          (
            c.removed(gid, cid, at),
            acc :+ Patch.Remove(renderer.elementId(cid))
          )
        }
      val (afterAdds, addPatches) =
        added.sorted.foldLeft((afterRemoves, List.empty[Patch])) {
          case ((c, acc), e) =>
            renderer.renderDynamicChild(gid, e, states) match {
              case None       => (c, acc) // defensive: not renderable, skip
              case Some(html) =>
                val cid = renderer.dynamicChildId(gid, e)
                // Anchor only on members ALREADY in the client's DOM, i.e.
                // pre-change ones: a co-arrival may not be inserted yet.
                val patch =
                  insertInto(renderer, gid, membersAfter, e, beforeSet, html)
                (
                  c.placed(gid, MemberKey.Entity(e), cid, html, at),
                  acc :+ patch
                )
            }
        }
      (afterAdds, removePatches ++ addPatches)
    }
  }

  /** Fill a dynamic group's mount with its CURRENT members — the wholesale
    * fallback, and the last place a patch carried other nodes.
    *
    * It used to outer-morph the group element and log that HTML under `gid`,
    * which is exactly what made a container's fragment contain its children
    * (and what `coveredByAncestor` existed to compensate for). A group's root
    * element IS its mount, so the same content goes out as an `Inner` fill and
    * no container-level fragment is written at all.
    *
    * '''The fill writes each member's fingerprint.''' It re-supplies the
    * mount's contents wholesale, so without that the next live diff would
    * compare against a baseline the client never had and suppress a real
    * change. Which is also why `Inner` and not something partial: it is
    * all-or-nothing over the mount's children — a named-but-empty child is
    * wiped, an omitted one deleted (`DatastarMorphContractSuite`) — so a fill
    * cannot preserve siblings and is reached only where the knowledge for a
    * delta is gone.
    */
  private def fillGroup(
      renderer: Renderer,
      log: FragmentLog,
      gid: NodeId,
      states: Map[String, EntityState],
      at: Stamp
  ): (FragmentLog, List[Patch]) = {
    val members = renderer.renderDynamicMembers(gid, states)
    // Prune first: a member that LEFT must not keep an entry, and its stale
    // mutation must not replay against a mount this fill just re-supplied.
    val pruned = log.invalidateWhere(k => k == gid || k.startsWith(gid + "_"))
    val stamped = members.foldLeft(pruned) { case (l, (cid, html)) =>
      l.set(cid, html, at.version)
    }
    (
      stamped,
      List(
        Patch.Insert(
          members.map(_._2).mkString,
          PatchMode.Inner,
          renderer.mountId(gid)
        )
      )
    )
  }
}
