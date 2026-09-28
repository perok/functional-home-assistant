package fh.view.runtime

import fh.view.model.{Activation, Dashboard, DomId, NodeId, Predicate, Surface}

/** Selection and visibility, beside [[MemberGraph]]; `Renderer` paints what
  * they say. A user group's selection is per viewer (`uiState`); a state
  * group's is the same for everyone, so a state surface hides nothing from
  * anybody. Visibility derives from selection — one fact asked three ways.
  *
  * @param surfaces
  *   not the whole `Dashboard`, which this has no use for.
  * @param members
  *   for [[rootOf]] of materialised members and nested sets.
  */
private[runtime] final class SurfaceGraph(
    surfaces: Map[String, Surface],
    rootOfIndexed: Map[NodeId, String],
    members: MemberGraph
) {

  /** A `val`: `hostId` asks for every node on every render. */
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

  /** Ordered by `bakeIndex`, then surface id: what a user index selects among
    * and a state selection walks first-match. A fixed, tiny set, so unlike a
    * candidate set it needs no eviction horizon.
    */
  def bakeGroup(gid: NodeId): List[String] =
    bakeGroups.getOrElse(gid, Nil)

  private def defaultOpenUser(s: Surface): Boolean = s.activation match {
    case Activation.User(d) => d
    case _                  => false
  }

  /** The first member decides: `validate` rejects mode-mixed groups. */
  def isStateGroup(gid: NodeId): Boolean =
    bakeGroup(gid).headOption.exists(isStateSurface)

  private val bakeOwnerIds: Set[NodeId] =
    surfaces.values.flatMap(_.bakeInto).map(NodeId.derived).toSet

  /** Tabs: the selected member lives in the host, which a patch never carries;
    * filling it is per client ([[Patches.hostFill]]).
    */
  val userBakeOwnerIds: Set[NodeId] =
    bakeOwnerIds.filterNot(isStateGroup)

  /** If/else hosts: rendered once per slug for every viewer. */
  val stateBakeOwnerIds: Set[NodeId] =
    bakeOwnerIds.filter(isStateGroup)

  /** `""` for the main page. Not recoverable from the id, which carries only
    * its own surface's prefix. Members and nested sets answer through the
    * graph; unknown would read as visible to everyone.
    */
  def rootOf(id: NodeId): Option[String] =
    rootOfIndexed
      .get(id)
      .orElse(members.rootOfMember(id))
      .orElse(members.rootOfSet(id))

  // A popup has no `bakeInto` and is absent: it hosts on the main page.
  private val surfaceParent: Map[String, String] =
    surfaces.flatMap { case (sid, s) =>
      s.bakeInto.flatMap(rootOf).filter(_.nonEmpty).map(sid -> _)
    }

  /** `sid` plus the nested tabs this viewer selected and the branches `states`
    * picks, transitively.
    */
  def shownWithin(
      sid: String,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): Set[String] = {
    val selected = selectedSurfaces(uiState)
    def close(acc: Set[String]): Set[String] = {
      val next = acc ++ acc.flatMap(activeStateSurfacesIn(_, states)) ++
        selected.filter(s => surfaceParent.get(s).exists(acc))
      if (next == acc) acc else close(next)
    }
    close(Set(sid))
  }

  private def isStateSurface(sid: String): Boolean =
    surfaces
      .get(sid)
      .exists(_.activation match {
        case _: Activation.State => true
        case _                   => false
      })

  private def stateSelected(
      sid: String,
      states: Map[String, EntityState]
  ): Boolean =
    surfaces.get(sid).flatMap(_.bakeInto).exists { gid =>
      resolveActiveByState(gid, states)
        .flatMap(bakeGroup(gid).lift)
        .contains(sid)
    }

  /** Walks up the chain: `open` includes a tab panel inside a hidden `If`. The
    * visited set because authored `bakeInto` may cycle.
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

  /** An unknown id counts as visible: over-sending costs bytes, under-sending
    * loses an update.
    */
  def visibleNode(
      id: NodeId,
      open: Set[String],
      states: Map[String, EntityState]
  ): Boolean =
    rootOf(id).forall(r => r.isEmpty || visibleSurface(r, open, states))

  // Walks start at one root's owners and descend only into selected members.
  private val stateGidsByRoot: Map[String, List[NodeId]] =
    stateBakeOwnerIds.toList.sorted
      .flatMap(gid => rootOf(gid).map(_ -> gid))
      .groupMap(_._1)(_._2)

  private def stateGidsAtRoot(root: String): List[NodeId] =
    stateGidsByRoot.getOrElse(root, Nil)

  // Subject-free (`validate`), so the subject is a stand-in nothing reads.
  private def holds(
      condition: Predicate,
      states: Map[String, EntityState]
  ): Boolean =
    Conditions.matchesIn(condition, EntityState.none, states)

  /** First match in `bakeIndex` order; `None` bakes empty content. */
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

  /** Exact, because conditions are subject-free: nothing outside this set can
    * move the selection.
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

  private def conditionTouched(
      gid: NodeId,
      changes: List[StateChange]
  ): Boolean = {
    val reads = stateGroupEntities.getOrElse(gid, Set.empty)
    changes.exists(c => reads.contains(c.current.entityId))
  }

  /** Only through selected members: a hidden branch is rendered fresh by its
    * ancestor's fill when it flips in.
    */
  def affectedStateGroups(
      changes: List[StateChange],
      before: Map[String, EntityState],
      states: Map[String, EntityState]
  ): List[NodeId] =
    affectedStateGroupsFrom("", changes, before, states)

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
      val nested = resolveActiveByState(gid, states).toList.flatMap(idx =>
        affectedStateGroupsFrom(bakeGroup(gid)(idx), changes, before, states)
      )
      (if (flipped) List(gid) else Nil) ++ nested
    }

  /** What keeps a hidden branch silent by construction. `excluding`: groups
    * flipping this frame, whose fill re-renders them.
    */
  def activeStateSurfaces(
      states: Map[String, EntityState],
      excluding: Set[NodeId] = Set.empty
  ): Set[String] =
    activeStateSurfacesFrom("", states, excluding)

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

  /** `uiState` is untrusted: an invalid value falls back to the `defaultOpen`
    * member (or 0), with a warning. No value is not a warning.
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

  /** A session's open set. State-selected surfaces never enter it: the shared
    * per-slug pass owns them.
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

  /** `ui_<popups>` holds a surface id, not an index: the host is not a bake
    * group. Narrowed, since a stale URL can name a surface this dashboard
    * cannot serve.
    */
  def openPopup(uiState: Map[String, String]): Option[String] =
    uiState
      .get(Dashboard.PopupHostId)
      .filter(_.nonEmpty)
      .filter(sid =>
        surfaces.get(sid).exists(_.hostId == Dashboard.PopupHostId)
      )

  def uiStateAnomalies(uiState: Map[String, String]): List[String] =
    surfaces.toList
      .flatMap(_._2.bakeInto)
      .distinct
      .filterNot(isStateGroup)
      .flatMap(gid => resolveActive(gid, uiState)._2)

  def surfacesAt(host: DomId): Set[String] =
    surfaces.collect {
      case (sid, s) if s.hostId == host => sid
    }.toSet

  /** The `ui_*` entry a swap makes true — only the swap knows what happened, so
    * only it asserts the selection (ADR 0025). `None` for a state group or a
    * surface that is not the host's.
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

  /** The whole `ui_*` picture, restated on connect: a stream dying between a
    * swap's patch and its signal leaves them disagreeing.
    */
  def committedSelections(open: Set[String]): Map[String, String] =
    uiStateFrom(open) +
      (Dashboard.PopupHostId -> surfacesAt(Dashboard.PopupHostId)
        .find(open)
        .getOrElse(""))

  /** From the live `open`, not the connection's arriving `uiState`, which a tab
    * click has since moved.
    */
  def uiStateFrom(open: Set[String]): Map[String, String] =
    userBakeOwnerIds.toList.flatMap { gid =>
      bakeGroup(gid).indexWhere(open) match {
        case -1 => None
        case i  => Some(gid -> i.toString)
      }
    }.toMap
}
