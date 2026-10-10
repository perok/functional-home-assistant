package fh.view.runtime

import fh.view.model.{Activation, Dashboard, DomId, NodeId, Predicate, Surface}

/** What one viewer has selected: the popup it has open, and the member of each
  * bake group a node variable selects (ADR 0033). A state group's branch is
  * everyone's and is not here. [[SurfaceGraph.selections]] narrows both, so a
  * reader trusts them.
  */
final case class Selections(popup: Option[String], panels: Map[NodeId, Int])
    derives CanEqual

object Selections {
  val none: Selections = Selections(None, Map.empty)
}

/** Selection and visibility, beside [[MemberGraph]]; `Renderer` paints what
  * they say. A variable-selected group's selection is per viewer
  * ([[Selections]]); a state group's is the same for everyone, so a state
  * surface hides nothing from anybody. Visibility derives from selection — one
  * fact asked three ways.
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

  /** Ordered by `bakeIndex`, then surface id: what a variable's index selects
    * among and a state selection walks first-match. A fixed, tiny set, so
    * unlike a candidate set it needs no eviction horizon.
    */
  def bakeGroup(gid: NodeId): List[String] =
    bakeGroups.getOrElse(gid, Nil)

  /** A popup open from the first paint; a bake group's member is chosen. */
  private def defaultOpenPopup(s: Surface): Boolean = s.activation match {
    case Activation.User(d) => d
    case _                  => false
  }

  /** The first member decides: `validate` rejects mode-mixed groups. */
  def isStateGroup(gid: NodeId): Boolean =
    bakeGroup(gid).headOption.exists(isStateSurface)

  /** The node variable selecting `gid`'s member, for a tab bar's panel. */
  def varSelecting(gid: NodeId): Option[String] =
    bakeGroup(gid).headOption.flatMap(surfaces.get).map(_.activation).collect {
      case Activation.Var(name) => name
    }

  /** A viewer's selections: `requested` narrowed by [[openPopup]], and each
    * variable-selected group's member from `env`. An index outside its group is
    * dropped, so the group shows its first member; every value passed
    * `refusals` or `validate`, so that is a guard, which [[selectionAnomalies]]
    * reports.
    */
  def selections(requested: Option[String], env: VarEnv): Selections =
    Selections(
      openPopup(requested),
      panelChoices(env).collect { case (gid, Right(i)) => gid -> i }
    )

  def selectionAnomalies(env: VarEnv): List[String] =
    panelChoices(env).toList.sortBy(_._1).collect { case (_, Left(w)) => w }

  // `env` is total over declarations and `validate` puts the variable in scope
  // at the host, so every such group has an entry.
  private def panelChoices(env: VarEnv): Map[NodeId, Either[String, Int]] =
    varBakeOwnerIds.toList.flatMap { gid =>
      varSelecting(gid)
        .flatMap(name => env.get(gid).flatMap(_.get(name)))
        .map { raw =>
          val n = bakeGroup(gid).size
          gid -> raw.toIntOption
            .filter(i => i >= 0 && i < n)
            .toRight(
              s"selection '$raw' for bake group $gid is not a member index " +
                s"(0..${n - 1}); using 0"
            )
        }
    }.toMap

  private val bakeOwnerIds: Set[NodeId] =
    surfaces.values.flatMap(_.bakeInto).map(NodeId.derived).toSet

  /** Tabs, selected by a viewer's variable: the selected member lives in the
    * host, which a patch never carries; filling it is per client
    * ([[Patches.hostFill]]).
    */
  val varBakeOwnerIds: Set[NodeId] =
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
      selections: Selections
  ): Set[String] = {
    val selected = selectedSurfaces(selections)
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

  /** The first member when nothing chose one. */
  private[runtime] def resolveActive(gid: NodeId, selections: Selections): Int =
    selections.panels.getOrElse(gid, 0)

  /** A session's open set. State-selected surfaces never enter it: the shared
    * per-slug pass owns them.
    */
  def selectedSurfaces(
      selections: Selections = Selections.none
  ): Set[String] = {
    val (baked, unbaked) =
      surfaces.toList.partition(_._2.bakeInto.isDefined)
    val fromGroups =
      baked
        .flatMap(_._2.bakeInto)
        .distinct
        .filterNot(isStateGroup)
        .map(gid => bakeGroup(gid)(resolveActive(gid, selections)))
        .toSet
    val fromUnbaked =
      unbaked.collect { case (sid, s) if defaultOpenPopup(s) => sid }.toSet
    fromGroups ++ fromUnbaked ++ selections.popup
  }

  /** `ui_<popups>` holds a surface id, not an index: the host is not a bake
    * group. Narrowed, since a stale URL can name a surface this dashboard
    * cannot serve.
    */
  def openPopup(requested: Option[String]): Option[String] =
    requested.filter(sid =>
      surfaces.get(sid).exists(_.hostId == Dashboard.PopupHostId)
    )

  def surfacesAt(host: DomId): Set[String] =
    surfaces.collect {
      case (sid, s) if s.hostId == host => sid
    }.toSet

  /** The `ui_popups` entry a swap makes true — only the swap knows what
    * happened, so only it asserts the selection (ADR 0025). `None` for a bake
    * group's host: a tab's commit is its variable's, a branch is server truth.
    */
  def committedSelection(
      host: DomId,
      newSurface: Option[String]
  ): Option[(String, String)] =
    Option.when(host == Dashboard.PopupHostId)(
      Dashboard.PopupHostId -> newSurface.getOrElse("")
    )

  /** The popup's `ui_*` picture, restated on connect: a stream dying between a
    * swap's patch and its signal leaves them disagreeing.
    */
  def committedSelections(open: Set[String]): Map[String, String] =
    Map(
      Dashboard.PopupHostId -> surfacesAt(Dashboard.PopupHostId)
        .find(open)
        .getOrElse("")
    )

  /** What `open` records: a live pull renders with these, since a press moves
    * `open` and not the request its stream connected with.
    */
  def selectionsIn(open: Set[String]): Selections =
    Selections(
      surfacesAt(Dashboard.PopupHostId).find(open),
      varBakeOwnerIds.toList.flatMap { gid =>
        bakeGroup(gid).indexWhere(open) match {
          case -1 => None
          case i  => Some(gid -> i)
        }
      }.toMap
    )
}
