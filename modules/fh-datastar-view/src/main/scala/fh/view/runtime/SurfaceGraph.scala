package fh.view.runtime

import fh.view.model.{Dashboard, DomId, NodeId, Ready, Surface}

/** What one viewer has selected: the popup it has open, and the case of each
  * pick a node variable decides (ADR 0033). A pick over state is everyone's and
  * is not here. A pick with no entry shows nothing. [[SurfaceGraph.selections]]
  * narrows both, so a reader trusts them.
  */
final case class Selections(popup: Option[String], panels: Map[NodeId, Int])
    derives CanEqual

object Selections {
  val none: Selections = Selections(None, Map.empty)
}

/** Selection and visibility, beside [[MemberGraph]]; `Renderer` paints what
  * they say. A pick over a variable is decided per viewer ([[Selections]]); one
  * over state is the same for everyone, so its cases hide nothing from anybody.
  * Visibility derives from selection — one fact asked three ways.
  *
  * What may be hidden is a CONTAINER: a popup, by its surface id, or a pick's
  * case, by the case's node id. Every node is in one, the innermost holding it
  * (`""` for the main page), and is shown while every container up its chain
  * is: a popup open, a case picked.
  *
  * @param popups
  *   not the whole `Dashboard`, which this has no use for.
  * @param picks
  *   each choice, by its id ([[Surface.groupId]]).
  * @param containerOfIndexed
  *   each node of the static trees' container.
  * @param members
  *   for [[rootOf]] of materialised members and nested sets.
  */
private[runtime] final class SurfaceGraph(
    popups: Map[String, Ready.Surface],
    picks: Map[NodeId, Ready.Pick],
    containerOfIndexed: Map[NodeId, String],
    members: MemberGraph
) {

  private val pickHosts: Map[NodeId, (NodeId, Option[Int])] =
    picks.map((gid, p) => gid -> (p.host, p.clause))

  /** case id -> each pick showing it and at which index: one per clause of a
    * set member, every clause minting the same ids.
    */
  private val caseAt: Map[String, List[(NodeId, Int)]] =
    picks.toList
      .sortBy(_._1)
      .flatMap((gid, p) =>
        p.cases.zipWithIndex.map((c, i) => (c: String) -> (gid, i))
      )
      .groupMap(_._1)(_._2)

  private val containers: Set[String] = popups.keySet ++ caseAt.keySet

  /** The node a pick fills. */
  def hostOf(gid: NodeId): NodeId = pickHosts.get(gid).fold(gid)(_._1)

  /** The clause a pick inside a set member belongs to. */
  def clauseOf(gid: NodeId): Option[Int] = pickHosts.get(gid).flatMap(_._2)

  /** A pick's cases' node ids, the otherwise's last; empty for a node owning
    * none.
    */
  def cases(gid: NodeId): Vector[String] =
    casesOf.getOrElse(gid, Vector.empty)

  // Asked per member on every publish, so no `Option` per lookup.
  private val casesOf: Map[NodeId, Vector[String]] =
    picks.view.mapValues(_.cases.map(c => c: String)).toMap

  /** The pick showing case `id` and its index there: in a set member, the pick
    * of the clause `shared` has it in.
    */
  def pickShowing(id: String, shared: Shared): Option[(NodeId, Int)] =
    caseAt.get(id).flatMap(_.find((gid, _) => groupShown(gid, shared)))

  /** Whether `id` is a case of a pick over a variable: chosen, never opened. */
  def isVarCase(id: String): Boolean =
    caseAt.get(id).exists(_.exists((gid, _) => varPicks(gid)))

  /** The case of a pick over a variable that `value` picks. */
  def memberAt(gid: NodeId, value: String): Option[Int] =
    picks.get(gid).flatMap(_.decide(value))

  /** What picks each case of a pick over a variable, in its order. */
  def memberValues(gid: NodeId): List[String] =
    picks
      .get(gid)
      .fold(Nil)(_.byKey.toList.sortBy(_._2).map(_._1.toString))

  /** A popup open from the first paint. */
  private def defaultOpenPopup(s: Ready.Surface): Boolean = s.defaultOpen

  def isStatePick(gid: NodeId): Boolean = statePicks(gid)

  /** The node variable deciding `gid`, for a tab bar's panel. */
  def varSelecting(gid: NodeId): Option[String] =
    picks.get(gid).flatMap(_.variable)

  /** A viewer's selections: `requested` narrowed by [[openPopup]], and each
    * pick over a variable decided from `env`. A value no case takes and no
    * otherwise catches shows nothing; every value passed `refusals` or
    * `validate`, so that is a guard, which [[selectionAnomalies]] reports.
    */
  def selections(requested: Option[String], env: VarEnv): Selections =
    Selections(
      openPopup(requested),
      panelChoices(env).collect { case (gid, Right(i)) => gid -> i }
    )

  def selectionAnomalies(env: VarEnv): List[String] =
    panelChoices(env).toList.sortBy(_._1).collect { case (_, Left(w)) => w }

  // `env` is total over declarations and `validate` puts the variable in scope
  // at the host, so every such pick has an entry.
  private def panelChoices(env: VarEnv): Map[NodeId, Either[String, Int]] =
    varPicks.toList.flatMap { gid =>
      varSelecting(gid)
        .flatMap(name => env.get(gid).flatMap(_.get(name)))
        .map { raw =>
          gid -> memberAt(gid, raw).toRight(
            s"selection '$raw' for the choice at $gid is not " +
              s"${Surface.membersText(memberValues(gid))}; showing nothing"
          )
        }
    }.toMap

  /** Tabs, decided by a viewer's variable: the chosen case lives in the host,
    * which a patch never carries; filling it is per client
    * ([[Patches.hostFill]]).
    */
  val varPicks: Set[NodeId] =
    picks.collect { case (gid, p) if p.variable.isDefined => gid }.toSet

  /** If/else hosts: rendered once per slug for every viewer. */
  val statePicks: Set[NodeId] = picks.keySet -- varPicks

  /** The innermost container holding `id`, `""` for the main page; a pick's is
    * its host's. Not recoverable from the id. Members and nested sets answer
    * through the graph; unknown would read as visible to everyone.
    */
  def rootOf(id: NodeId): Option[String] = {
    val node = hostOf(id)
    containerOfIndexed
      .get(node)
      .orElse(members.rootOfMember(node))
      .orElse(members.rootOfSet(node))
  }

  // A popup is absent: it hosts on the main page. A case's parent is its
  // host's container; every clause's host is at one id.
  private val surfaceParent: Map[String, String] =
    caseAt.flatMap { (cid, at) =>
      at.headOption
        .flatMap((gid, _) => rootOf(gid))
        .filter(_.nonEmpty)
        .map(cid -> _)
    }

  /** `sid` plus the nested tabs this viewer selected and the cases `shared`
    * picks, transitively.
    */
  def shownWithin(
      sid: String,
      shared: Shared,
      selections: Selections
  ): Set[String] = {
    val open = selectedSurfaces(selections)
    containers.filter(shownUpTo(_, sid, open, shared, Set.empty)) + sid
  }

  /** Every surface [[visibleSurface]] with `open`, which is added whole: a page
    * opens on what the viewer chose, shown or not.
    */
  def shownSurfaces(open: Set[String], shared: Shared): Set[String] =
    open ++ containers.filter(visibleSurface(_, open, shared))

  /** Whether `sid` is on screen for a viewer with `open` open, as `shared` has
    * it: every surface up its chain is, a user surface opened and a state case
    * picked, and a pick inside a set member only while the member is in that
    * clause ([[groupShown]]). `open` may hold a tab panel inside a hidden `If`,
    * hence the whole chain. `cut`: picks switching this frame, whose fill
    * renders what is below them.
    */
  def visibleSurface(
      sid: String,
      open: Set[String],
      shared: Shared,
      cut: Set[NodeId] = Set.empty
  ): Boolean = sid.isEmpty || shownUpTo(sid, "", open, shared, cut)

  /** Every surface from `sid` up to `stop`, exclusive, is shown. */
  private def shownUpTo(
      sid: String,
      stop: String,
      open: Set[String],
      shared: Shared,
      cut: Set[NodeId]
  ): Boolean = {
    def up(sid: String, seen: Set[String]): Boolean =
      sid == stop || (!sid.isEmpty && !seen(sid) && {
        val here = caseAt.get(sid) match
          case None     => open(sid)
          case Some(at) =>
            at.exists((gid, i) =>
              !cut(gid) && groupShown(gid, shared) &&
                (if (isStatePick(gid)) decideByState(gid, shared).contains(i)
                 else open(sid))
            )
        here && up(surfaceParent.getOrElse(sid, ""), seen + sid)
      })
    up(sid, Set.empty)
  }

  /** An unknown id counts as visible: over-sending costs bytes, under-sending
    * loses an update.
    */
  def visibleNode(
      id: NodeId,
      open: Set[String],
      shared: Shared
  ): Boolean =
    rootOf(id).forall(r => r.isEmpty || visibleSurface(r, open, shared))

  /** One read of `by` and a lookup, once per version; `None` fills the host
    * with nothing. `by` is subject-free (`validate`).
    */
  private[runtime] def decideByState(
      gid: NodeId,
      shared: Shared
  ): Option[Int] =
    val p = picks.getOrElse(gid, null)
    if (p == null) None
    else
      shared.branch(gid, p.by.reads)(f =>
        p.decide(p.by.run(null, shared.states, f))
      )

  /** The inverse of what each state pick's `by` reads. Exact, because `by` is
    * subject-free: nothing outside it can move a pick.
    */
  private val statePicksReading: Map[String, List[NodeId]] =
    statePicks.toList.sorted
      .flatMap(gid => picks(gid).by.entities.distinct.map(_ -> gid))
      .groupMap(_._1)(_._2)

  /** The state picks some entity can mark dirty; any other is dirty at every
    * version, so it is never carried.
    */
  val marked: Set[NodeId] = statePicksReading.values.flatten.toSet

  /** The picks `changes` dirty. */
  def dirtied(changes: List[StateChange]): Set[NodeId] =
    changes.iterator
      .flatMap(c => statePicksReading.getOrElse(c.entityId, Nil))
      .toSet

  /** Whether `gid` is on the page as `shared` has it: a pick inside a member
    * only while the member is present and in that clause.
    */
  def groupShown(gid: NodeId, shared: Shared): Boolean =
    clauseOf(gid).forall { k =>
      members
        .memberOwning(hostOf(gid))
        .flatMap(members.memberAt(_, shared))
        .exists(_.clause == k)
    }

  /** Found from the changed entities, not by walking every pick: at 200 members
    * with an If each, the walk evaluated 200 conditions a frame. A pick counts
    * only if [[groupShown]] before and after, since a member arriving or
    * switching clause is placed whole, and only if its root is visible past no
    * other pick switching ([[visibleSurface]]), whose fill holds it.
    */
  def affectedStateGroups(
      changes: List[StateChange],
      prev: Shared,
      next: Shared,
      visible: Set[String] = Set.empty
  ): List[NodeId] = {
    val moved = changes
      .flatMap(c => statePicksReading.getOrElse(c.entityId, Nil))
      .distinct
      .filter { gid =>
        groupShown(gid, prev) && groupShown(gid, next) &&
        decideByState(gid, prev) != decideByState(gid, next)
      }
    val cut = moved.toSet
    moved
      .filter(rootOf(_).exists(visibleSurface(_, visible, next, cut)))
      .sorted
  }

  /** The case a viewer's variable picks, if any. */
  private[runtime] def decideByViewer(
      gid: NodeId,
      selections: Selections
  ): Option[Int] =
    selections.panels.get(gid)

  /** A session's open set. State-picked surfaces never enter it: the shared
    * per-slug pass owns them.
    */
  def selectedSurfaces(
      selections: Selections = Selections.none
  ): Set[String] = {
    val fromPicks =
      varPicks.flatMap(gid =>
        decideByViewer(gid, selections).flatMap(cases(gid).lift)
      )
    val fromPopups =
      popups.collect { case (sid, s) if defaultOpenPopup(s) => sid }.toSet
    fromPicks ++ fromPopups ++ selections.popup
  }

  /** `ui_<popups>` holds a surface id, not an index: the host is not a pick.
    * Narrowed, since a stale URL can name a surface this dashboard cannot
    * serve.
    */
  def openPopup(requested: Option[String]): Option[String] =
    requested.filter(popups.contains)

  /** What `host` can show: every popup at the popup host, else the cases of the
    * picks hosted there.
    */
  def surfacesAt(host: DomId): Set[String] =
    if (host == Dashboard.PopupHostId) popups.keySet
    else casesAtHost.getOrElse(host, Set.empty)

  private val casesAtHost: Map[DomId, Set[String]] =
    picks.values.toList
      .groupMapReduce(p => DomId.derived(p.host))(
        _.cases.map(c => c: String).toSet
      )(_ ++ _)

  /** The `ui_popups` entry a swap makes true — only the swap knows what
    * happened, so only it asserts the selection (ADR 0025). `None` for a pick's
    * host: a tab's commit is its variable's, a branch is server truth.
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

  /** [[selectionsIn]], where a pick none of whose cases `open` holds takes
    * `env`'s: a pick in a set member's clause is first shown when the member
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
      varPicks.toList.flatMap { gid =>
        cases(gid).indexWhere(open) match {
          case -1 => None
          case i  => Some(gid -> i)
        }
      }.toMap
    )
}
