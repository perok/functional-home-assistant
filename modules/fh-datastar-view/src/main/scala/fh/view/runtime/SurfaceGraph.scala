package fh.view.runtime

import fh.view.model.{Activation, Dashboard, DomId, NodeId, Predicate, Surface}

/** Which parts of a dashboard are showing, and to whom.
  *
  * The second decision half beside [[MemberGraph]], and the same split: this
  * decides SELECTION (which branch of a bake group is active, which tab a
  * viewer is on) and VISIBILITY (which clients a patch at a given node may
  * reach); `Renderer` paints whatever it says. Nothing here needs a template, a
  * mustache context or the document walk.
  *
  * Two activation modes run through every question, and keeping them apart is
  * the whole subtlety. A USER group's selection is per-viewer — it comes out of
  * that connection's `uiState` — so two clients can be looking at different
  * branches of the same node. A STATE group's is a pure function of entity
  * state, identical for everyone, which is why it renders once per slug and why
  * a state surface is TRANSPARENT to visibility: it hides nothing from anybody.
  *
  * '''Four sections, one fact.''' The branches and which mode owns them; where
  * a node lives and who may see it; state selection and what a frame flipped;
  * user selection, which is untrusted input. They look separable and are not:
  * `visibleSurface` asks `stateSelected`, which asks `resolveActiveByState` —
  * visibility DERIVES from selection rather than sitting beside it. So "which
  * surface is showing" is one fact asked three ways (now, after this frame, to
  * whom), and splitting it would fake one with the other.
  *
  * @param surfaces
  *   the dashboard's surfaces, by id — and NOT the `Dashboard` they came from.
  *   Every question here is about surfaces; taking the whole aggregate would
  *   declare a dependency on cards, css, theme and slug that this cannot use
  *   and should not see. Same reason [[MemberGraph]] takes its `SetNode`s
  *   rather than the index they were collected from.
  * @param rootOfIndexed
  *   every statically-indexed node id -> the layout tree it is in (`""` for the
  *   main page, else the surface id).
  * @param members
  *   consulted by [[rootOf]] for the two kinds of node the static index cannot
  *   answer for: a materialised member, and a nested set container.
  */
private[runtime] final class SurfaceGraph(
    surfaces: Map[String, Surface],
    rootOfIndexed: Map[NodeId, String],
    members: MemberGraph
) {

  // ---- the branches, and which mode selects among them ---------------------

  /** Every bake group, computed ONCE. `surfaces` is fixed for the life of a
    * renderer, so this is a pure inversion of it: `bakeInto` target -> member
    * surface ids.
    *
    * It has to be a `val`. As a `def` it re-scanned every surface on each call,
    * and `mountId` calls it for EVERY node on EVERY render — so a paint cost
    * O(nodes × surfaces) for an answer that cannot change.
    */
  private val bakeGroups: Map[NodeId, List[String]] =
    surfaces.toList
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

  /** A bake group's branches — its surface ids, or empty for anything that is
    * not a bake host.
    *
    * Ordered by `bakeIndex`, with the surface id as a stable tiebreak and as
    * the fallback for a member carrying none. That order is what a ui-state
    * index selects among, and what state selection walks first-match (then,
    * elseif…, else).
    *
    * A group's branches are a FIXED, tiny set, which is why — unlike a
    * candidate set over unbounded entities — its mutations can never accumulate
    * and it needs no eviction horizon.
    */
  def bakeGroup(gid: NodeId): List[String] =
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
  def isStateGroup(gid: NodeId): Boolean =
    bakeGroup(gid).headOption.exists(isStateSurface)

  /** Every component id some surface bakes into. `bakeInto` is AUTHORED (a
    * hoist-resolved relation `Dashboard.validate` checks against the registry),
    * which is the one place a node id enters from outside the tree walk.
    */
  private val bakeOwnerIds: Set[NodeId] =
    surfaces.values.flatMap(_.bakeInto).map(NodeId.derived).toSet

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

  // ---- where a node lives, and who may see it ------------------------------

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
    *
    * A NESTED SET CONTAINER needs the same treatment and for the same reason:
    * it is not in the static index either, and patches aim at it directly (a
    * mount fill, and the `remove` of a departing member, which names its
    * container). The graph is the only thing that knows where it hangs.
    */
  def rootOf(id: NodeId): Option[String] =
    rootOfIndexed
      .get(id)
      .orElse(members.rootOfMember(id))
      .orElse(members.rootOfSet(id))

  /** A surface's place in the tree is where its host node sits, so this is just
    * [[rootOf]] applied to `bakeInto`. A popup has no `bakeInto`, hosts on the
    * main page, and is therefore absent here.
    */
  private val surfaceParent: Map[String, String] =
    surfaces.flatMap { case (sid, s) =>
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
    surfaces
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
    surfaces.get(sid).flatMap(_.bakeInto).exists { gid =>
      resolveActiveByState(gid, states)
        .flatMap(bakeGroup(gid).lift)
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

  /** An id this renderer does not know (a candidate set's per-entity child)
    * counts as visible — the safe direction, since over-sending costs bytes
    * where under-sending loses an update.
    */
  def visibleNode(
      id: NodeId,
      open: Set[String],
      states: Map[String, EntityState]
  ): Boolean =
    rootOf(id).forall(r => r.isEmpty || visibleSurface(r, open, states))

  // ---- state selection, and what one frame flipped -------------------------

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
    Conditions.matchesIn(condition, EntityState.none, states)

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
      surfaces
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
        surfaces
          .get(sid)
          .toList
          .flatMap(_.activation match {
            case Activation.State(c) => Predicate.referencedEntities(c)
            case _                   => Nil
          })
      }.toSet
    }.toMap

  /** The O(1) pre-test of the flip check: the changed entities decide, not the
    * surfaces, same as [[MemberGraph.affectedSets]] for membership.
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

  // ---- user selection: per viewer, and untrusted ---------------------------

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
    val branches = bakeGroup(gid)
    val n = branches.size
    val fallback =
      branches.indexWhere(sid =>
        surfaces.get(sid).exists(defaultOpenUser)
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
      surfaces.toList.partition(_._2.bakeInto.isDefined)
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
        surfaces.get(sid).exists(_.hostId == Dashboard.PopupHostId)
      )

  /** Returns data rather than logging, so the renderer stays side-effect-free.
    * A value naming a state-selected group is ignored — no client choice exists
    * there to be malformed.
    */
  def uiStateAnomalies(uiState: Map[String, String]): List[String] =
    surfaces.toList
      .flatMap(_._2.bakeInto)
      .distinct
      .filterNot(isStateGroup)
      .flatMap(gid => resolveActive(gid, uiState)._2)

  /** Which surfaces share `host` as their mount — the eviction group a swap
    * replaces.
    */
  def surfacesAt(host: DomId): Set[String] =
    surfaces.collect {
      case (sid, s) if s.hostId == host => sid
    }.toSet

  /** What a swap of `host` makes TRUE about this client's selection, as the
    * `ui_*` ui-state entry (id -> raw value) the server is now entitled to
    * assert. `None` when there is no client selection to assert: a
    * state-activated group (server truth, no client choice) or a host whose
    * arriving surface is not one of its members.
    *
    * The two value shapes are [[resolveActive]]'s and [[openPopup]]'s, from the
    * other end — a bake group's is a member INDEX, the popup host's a surface
    * id (or `""` for a close), because the popup host is not a bake group.
    *
    * This exists because the client used to assert it: a tap set `ui_<id>`
    * itself, so a POST that never landed left the URL claiming a panel the DOM
    * did not have. Only the swap knows what actually happened, so only the swap
    * may say (`docs/plan-pending-signals.md`).
    */
  def committedSelection(
      host: DomId,
      newSurface: Option[String]
  ): Option[(String, String)] =
    if (host == Dashboard.PopupHostId)
      Some(Dashboard.PopupHostId -> newSurface.getOrElse(""))
    else
      surfacesAt(host).toList
        .flatMap(surfaces.get)
        .flatMap(_.bakeInto)
        .headOption
        .filterNot(isStateGroup)
        .zip(newSurface)
        .flatMap { case (gid, sid) =>
          bakeGroup(gid).indexOf(sid) match {
            case -1 => None
            case i  => Some(gid -> i.toString)
          }
        }

  /** Every selection this session's open set makes true, as [[uiStateFrom]]
    * plus the popup host — the whole `ui_*` picture rather than the one entry a
    * swap moves.
    *
    * A stream states this when it connects. The per-swap frame is enough while
    * a stream is up, but the two halves of a swap (the patch, then the signal)
    * are separate writes, so a stream dying between them leaves a DOM holding
    * one panel and a signal naming another. Restating it on connect costs one
    * small frame and makes the returning client's selection the server's answer
    * rather than whatever the last frame it received happened to say.
    */
  def committedSelections(open: Set[String]): Map[String, String] =
    uiStateFrom(open) +
      (Dashboard.PopupHostId -> surfacesAt(Dashboard.PopupHostId)
        .find(open)
        .getOrElse(""))

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
}
