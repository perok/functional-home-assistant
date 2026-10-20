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
        s.group.map(gid => (gid, sid, s.bakeIndex))
      }
      .groupBy(_._1)
      .view
      .mapValues(
        _.sortBy { case (_, sid, bi) => (bi.getOrElse(Int.MaxValue), sid) }
          .map(_._2)
      )
      .toMap

  // A group id is its host's, except inside a set member's clause
  // (`Surface.group`).
  private val groupHosts: Map[NodeId, (NodeId, Option[Int])] =
    surfaces.values.flatMap { s =>
      s.group.zip(s.bakeInto).map((gid, host) => gid -> (host, s.clause))
    }.toMap

  /** The node a group bakes into. */
  def hostOf(gid: NodeId): NodeId = groupHosts.get(gid).fold(gid)(_._1)

  /** The group `host` owns in a member's `clause`, or `host` itself for a node
    * owning none there. `validate` makes every surface baking inside a clause
    * name it, so an untagged one never gets here.
    */
  def groupAt(host: NodeId, clause: Int): NodeId = {
    val tagged = Surface.groupId(host, Some(clause))
    if (groupHosts.contains(tagged)) tagged else host
  }

  /** The clause a group inside a set member belongs to. */
  def clauseOf(gid: NodeId): Option[Int] = groupHosts.get(gid).flatMap(_._2)

  /** Ordered by `bakeIndex`, then surface id: what a variable's index selects
    * among and a state selection walks first-match. A fixed, tiny set, so
    * unlike a candidate set it needs no eviction horizon.
    */
  def bakeGroup(gid: NodeId): List[String] =
    bakeGroups.getOrElse(gid, Nil)

  /** The member of a variable-selected group that `value` selects, else its
    * `otherwise` member.
    */
  def memberAt(gid: NodeId, value: String): Option[Int] = {
    val members = bakeGroup(gid).map(surfaces.get)
    def first(p: Surface => Boolean): Option[Int] =
      Some(members.indexWhere(_.exists(p))).filter(_ >= 0)
    first(_.selectedAt.contains(value)).orElse(first(_.isOtherwise))
  }

  /** What selects each member of a variable-selected group, in its order. */
  def memberValues(gid: NodeId): List[String] =
    bakeGroup(gid).flatMap(surfaces.get(_).flatMap(_.selectedAt))

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
      case Activation.Var(name, _) => name
    }

  /** A viewer's selections: `requested` narrowed by [[openPopup]], and each
    * variable-selected group's member from `env`. A value no member is selected
    * at is dropped, so the group shows its first member; every value passed
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
          gid -> memberAt(gid, raw).toRight(
            s"selection '$raw' for bake group $gid is not " +
              s"${Surface.membersText(memberValues(gid))}; using the first"
          )
        }
    }.toMap

  private val bakeOwnerIds: Set[NodeId] =
    surfaces.values.flatMap(_.group).toSet

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
  def rootOf(id: NodeId): Option[String] = {
    val node = hostOf(id)
    rootOfIndexed
      .get(node)
      .orElse(members.rootOfMember(node))
      .orElse(members.rootOfSet(node))
  }

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
    val open = selectedSurfaces(selections)
    surfaces.keySet.filter(shownUpTo(_, sid, open, states, Set.empty)) + sid
  }

  /** Every surface [[visibleSurface]] with `open`, which is added whole: a page
    * opens on what the viewer chose, shown or not.
    */
  def shownSurfaces(
      open: Set[String],
      states: Map[String, EntityState]
  ): Set[String] =
    open ++ surfaces.keySet.filter(visibleSurface(_, open, states))

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
    surfaces.get(sid).flatMap(_.group).exists { gid =>
      resolveActiveByState(gid, states)
        .flatMap(bakeGroup(gid).lift)
        .contains(sid)
    }

  /** Whether `sid` is on screen for a viewer with `open` open, as `states` has
    * it: every surface up its chain is, a user surface opened and a state
    * branch selected, and a group inside a set member only while the member is
    * in that clause ([[groupShown]]). `open` may hold a tab panel inside a
    * hidden `If`, hence the whole chain. `cut`: groups flipping this frame,
    * whose fill renders what is below them.
    */
  def visibleSurface(
      sid: String,
      open: Set[String],
      states: Map[String, EntityState],
      cut: Set[NodeId] = Set.empty
  ): Boolean = sid.isEmpty || shownUpTo(sid, "", open, states, cut)

  /** Every surface from `sid` up to `stop`, exclusive, is shown. The visited
    * set because authored `bakeInto` may cycle.
    */
  private def shownUpTo(
      sid: String,
      stop: String,
      open: Set[String],
      states: Map[String, EntityState],
      cut: Set[NodeId]
  ): Boolean = {
    def up(sid: String, seen: Set[String]): Boolean =
      sid == stop || (!sid.isEmpty && !seen(sid) && {
        val inGroup = surfaces
          .get(sid)
          .flatMap(_.group)
          .forall(gid => !cut(gid) && groupShown(gid, states))
        val here =
          if (isStateSurface(sid)) stateSelected(sid, states) else open(sid)
        inGroup && here && up(surfaceParent.getOrElse(sid, ""), seen + sid)
      })
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

  /** The inverse of what each state group's conditions read. Exact, because
    * conditions are subject-free: nothing outside it can move a selection.
    */
  private val stateGroupsReading: Map[String, List[NodeId]] =
    stateBakeOwnerIds.toList.sorted
      .flatMap { gid =>
        bakeGroup(gid)
          .flatMap(
            surfaces
              .get(_)
              .toList
              .flatMap(_.activation match {
                case Activation.State(c) => Predicate.referencedEntities(c)
                case _                   => Nil
              })
          )
          .distinct
          .map(_ -> gid)
      }
      .groupMap(_._1)(_._2)

  /** Whether `gid` is on the page as `states` has it: a group inside a member
    * only while the member is present and in that clause.
    */
  def groupShown(gid: NodeId, states: Map[String, EntityState]): Boolean =
    clauseOf(gid).forall { k =>
      members
        .memberOwning(hostOf(gid))
        .flatMap(members.memberAt(_, states))
        .exists(_.clause == k)
    }

  /** Found from the changed entities, not by walking every group: at 200
    * members with an If each, the walk evaluated 200 conditions a frame. A
    * group counts only if [[groupShown]] before and after, since a member
    * arriving or switching clause is placed whole, and only if its root is
    * visible past no other group flipping ([[visibleSurface]]), whose fill
    * holds it.
    */
  def affectedStateGroups(
      changes: List[StateChange],
      before: Map[String, EntityState],
      states: Map[String, EntityState],
      visible: Set[String] = Set.empty
  ): List[NodeId] = {
    val moved = changes
      .flatMap(c => stateGroupsReading.getOrElse(c.entityId, Nil))
      .distinct
      .filter { gid =>
        def selected(at: Map[String, EntityState]) =
          resolveActiveByState(gid, at)
        groupShown(gid, before) && groupShown(gid, states) &&
        selected(before) != selected(states)
      }
    val cut = moved.toSet
    moved
      .filter(rootOf(_).exists(visibleSurface(_, visible, states, cut)))
      .sorted
  }

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
        .flatMap(_._2.group)
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

  /** [[selectionsIn]], where a group none of whose members `open` holds takes
    * `env`'s: a group in a set member's clause is first shown when the member
    * switches to that clause.
    */
  def selectionsShown(open: Set[String], env: VarEnv): Selections = {
    val shown = selectionsIn(open)
    shown.copy(panels =
      panelChoices(env).collect { case (gid, Right(i)) => gid -> i } ++
        shown.panels
    )
  }

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
