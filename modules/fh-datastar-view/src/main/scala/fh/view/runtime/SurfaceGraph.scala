package fh.view.runtime

import fh.view.model.{Dashboard, DomId, Ix, NodeId, Ready, Surface, TreeIndex}

import scala.collection.immutable.BitSet

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
  * What may be hidden is a CONTAINER, a node of the [[TreeIndex]]: a popup's
  * root or a pick's case. Every node is in one, the innermost holding it (the
  * page's root for the page), and is shown while every container up its chain
  * is: a popup open, a case picked. Inside a renderer a container is its `Ix`
  * and an open set a `BitSet` of them ([[openOf]]); what crosses a swap
  * (`SessionState.open`) spells each by its root's DOM id, under which a
  * member's clauses share one name and so read as one `Ix` per clause.
  *
  * @param popups
  *   not the whole `Dashboard`, which this has no use for.
  * @param picks
  *   each choice, by its id ([[Surface.groupId]]).
  */
private[runtime] final class SurfaceGraph(
    index: TreeIndex,
    popups: Map[String, Ready.Surface],
    picks: Map[NodeId, Ready.Pick],
    members: MemberGraph
) {

  private val pickHosts: Map[NodeId, (NodeId, Option[Int])] =
    picks.map((gid, p) => gid -> (p.host, p.clause))

  // By `Ix`, so a visibility walk boxes nothing. `null`: not a case.
  private val caseGid = new Array[NodeId](index.size)
  private val caseIdx = new Array[Int](index.size)
  picks.foreach((gid, p) =>
    p.choice.cases.zipWithIndex.foreach { (c, i) =>
      caseGid(c.ix.toInt) = gid
      caseIdx(c.ix.toInt) = i
    }
  )

  // The container its container's root sits in; -1 for a tree's root.
  private val above: Array[Int] =
    Array.tabulate(index.size)(i =>
      index.containerAbove(Ix(i)).fold(-1)(_.toInt)
    )

  /** The page's root: always shown, never in an open set. */
  val pageRoot: Ix = index.roots.head

  private val popupRoots: Map[String, Ix] =
    popups.map((sid, s) => sid -> s.content.ix)

  private val popupBySpelling: Map[String, String] =
    popupRoots.map((sid, r) => (index.id(r): String) -> sid)

  /** Every container but the page's root. */
  private val containers: BitSet =
    BitSet.fromSpecific(
      index.all
        .filter(ix => index.isContainer(ix) && ix != pageRoot)
        .map(_.toInt)
    )

  /** A popup's container as a session spells it: its root's DOM id. */
  def popupSpelling(sid: String): Option[String] =
    popupRoots.get(sid).map(index.id)

  /** The popup whose root `spelled` names. */
  def popupNamed(spelled: String): Option[String] = popupBySpelling.get(spelled)

  /** The containers `spelled` names: one per clause it is under. */
  def containersSpelled(spelled: String): List[Ix] =
    index
      .ixsOf(NodeId.derived(spelled))
      .filter(ix => containers(ix.toInt))

  /** A session's open set, read into this renderer once per step. A name
    * nothing here has (a stale swap) drops out.
    */
  def openOf(spelled: Set[String]): BitSet =
    BitSet.fromSpecific(
      spelled.iterator.flatMap(containersSpelled).map(_.toInt)
    )

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
    containersSpelled(id).iterator
      .map(_.toInt)
      .collect { case i if caseGid(i) != null => caseGid(i) -> caseIdx(i) }
      .find((gid, _) => groupShown(gid, shared))

  /** Whether `id` is a case of a pick over a variable: chosen, never opened. */
  def isVarCase(id: String): Boolean =
    containersSpelled(id).exists { ix =>
      val gid = caseGid(ix.toInt)
      gid != null && varPicks(gid)
    }

  /** The case of a pick over a variable that `value` picks. */
  def memberAt(gid: NodeId, value: String): Option[Int] =
    picks.get(gid).flatMap(_.decide(value))

  /** What picks each case of a pick over a variable, in its order. */
  def memberValues(gid: NodeId): List[String] =
    picks
      .get(gid)
      .fold(Nil)(_.choice.byKey.toList.sortBy(_._2).map(_._1.toString))

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

  /** The nodes `id` names, a pick by its host, one per clause. */
  private def nodesOf(id: NodeId): List[Ix] =
    picks.get(id) match
      case Some(p) => p.node.ix :: Nil
      case None    => index.ixsOf(id)

  /** Every container from `c` up to its tree's root is shown, or, with a
    * `stop`, up to one of its: a container outside `stop` is not. `cut`: picks
    * switching this frame, whose fill renders what is below them.
    */
  private def shownUpTo(
      c: Int,
      stop: BitSet,
      open: BitSet,
      shared: Shared,
      cut: Set[NodeId]
  ): Boolean =
    if (stop.nonEmpty && stop(c)) true
    else if (c == pageRoot.toInt) stop.isEmpty
    else {
      val gid = caseGid(c)
      val here =
        if (gid == null) open(c)
        else
          !cut(gid) && groupShown(gid, shared) &&
          (if (isStatePick(gid))
             decideByState(gid, shared).contains(caseIdx(c))
           else open(c))
      here && {
        val up = above(c)
        if (up < 0) stop.isEmpty else shownUpTo(up, stop, open, shared, cut)
      }
    }

  /** The containers shown from `spelled` down, it included, as `shared` and
    * `selections` have them.
    */
  def shownWithin(
      spelled: String,
      shared: Shared,
      selections: Selections
  ): BitSet = {
    val stop = BitSet.fromSpecific(containersSpelled(spelled).map(_.toInt))
    val open = openOf(selectedSurfaces(selections))
    containers.filter(shownUpTo(_, stop, open, shared, Set.empty)) | stop
  }

  /** Every container [[visibleContainer]] with `open`, which is added whole: a
    * page opens on what the viewer chose, shown or not.
    */
  def shownSurfaces(open: BitSet, shared: Shared): BitSet =
    open | containers.filter(
      shownUpTo(_, BitSet.empty, open, shared, Set.empty)
    )

  /** Whether container `c` is on screen for a viewer with `open` open, as
    * `shared` has it: every container up its chain is, a popup opened and a
    * case picked, and a pick inside a set member only while the member is in
    * that clause ([[groupShown]]). `open` may hold a tab panel inside a hidden
    * `If`, hence the whole chain.
    */
  def visibleContainer(
      c: Ix,
      open: BitSet,
      shared: Shared,
      cut: Set[NodeId] = Set.empty
  ): Boolean = shownUpTo(c.toInt, BitSet.empty, open, shared, cut)

  /** The containers of one session's open set that are on its screen. */
  def onScreen(spelled: Set[String], shared: Shared): BitSet = {
    val open = openOf(spelled)
    open.filter(shownUpTo(_, BitSet.empty, open, shared, Set.empty))
  }

  /** An unknown id counts as visible: over-sending costs bytes, under-sending
    * loses an update.
    */
  def visibleNode(id: NodeId, open: BitSet, shared: Shared): Boolean =
    visibleNodeCut(id, open, shared, Set.empty)

  private def visibleNodeCut(
      id: NodeId,
      open: BitSet,
      shared: Shared,
      cut: Set[NodeId]
  ): Boolean = {
    val ixs = nodesOf(id)
    ixs.isEmpty || ixs.exists(ix =>
      shownUpTo(index.container(ix).toInt, BitSet.empty, open, shared, cut)
    )
  }

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
    * switching clause is placed whole, and only if its host is visible past no
    * other pick switching, whose fill holds it.
    */
  def affectedStateGroups(
      changes: List[StateChange],
      prev: Shared,
      next: Shared,
      visible: BitSet = BitSet.empty
  ): List[NodeId] = {
    val moved = changes
      .flatMap(c => statePicksReading.getOrElse(c.entityId, Nil))
      .distinct
      .filter { gid =>
        groupShown(gid, prev) && groupShown(gid, next) &&
        decideByState(gid, prev) != decideByState(gid, next)
      }
    val cut = moved.toSet
    moved.filter(visibleNodeCut(_, visible, next, cut)).sorted
  }

  /** The case a viewer's variable picks, if any. */
  private[runtime] def decideByViewer(
      gid: NodeId,
      selections: Selections
  ): Option[Int] =
    selections.panels.get(gid)

  /** A session's open set, spelled. State-picked cases never enter it: the
    * shared per-slug pass owns them.
    */
  def selectedSurfaces(
      selections: Selections = Selections.none
  ): Set[String] = {
    val fromPicks =
      varPicks.flatMap(gid =>
        decideByViewer(gid, selections).flatMap(cases(gid).lift)
      )
    val fromPopups =
      popups.collect { case (sid, s) if s.defaultOpen => sid }.toSet
    fromPicks ++ (fromPopups ++ selections.popup).flatMap(popupSpelling)
  }

  /** `ui_<popups>` holds a surface id, not an index: the host is not a pick.
    * Narrowed, since a stale URL can name a surface this dashboard cannot
    * serve.
    */
  def openPopup(requested: Option[String]): Option[String] =
    requested.filter(popups.contains)

  /** What `host` can show, spelled: every popup at the popup host, else the
    * cases of the picks hosted there.
    */
  def surfacesAt(host: DomId): Set[String] =
    if (host == Dashboard.PopupHostId) popupBySpelling.keySet
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
      arriving: Option[String]
  ): Option[(String, String)] =
    Option.when(host == Dashboard.PopupHostId)(
      Dashboard.PopupHostId -> arriving.flatMap(popupNamed).getOrElse("")
    )

  /** The popup's `ui_*` picture, restated on connect: a stream dying between a
    * swap's patch and its signal leaves them disagreeing.
    */
  def committedSelections(open: Set[String]): Map[String, String] =
    Map(Dashboard.PopupHostId -> openPopupIn(open).getOrElse(""))

  private def openPopupIn(open: Set[String]): Option[String] =
    popupBySpelling.toList.sorted.collectFirst {
      case (spelled, sid) if open(spelled) => sid
    }

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
      openPopupIn(open),
      varPicks.toList.flatMap { gid =>
        cases(gid).indexWhere(open) match {
          case -1 => None
          case i  => Some(gid -> i)
        }
      }.toMap
    )
}
