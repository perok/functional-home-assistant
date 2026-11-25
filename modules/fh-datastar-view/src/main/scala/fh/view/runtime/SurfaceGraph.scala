package fh.view.runtime

import fh.view.model.{Dashboard, Ix, NodeId, Ready, TreeIndex}

import scala.collection.immutable.BitSet

/** Selection and visibility, beside [[MemberGraph]]; `Renderer` paints what
  * they say. A pick over a variable is decided per viewer, from the values its
  * [[Shared]] views with, and so is one computing over a variable, alone or
  * with state ([[viewerPicks]]); one over state alone is the same for everyone,
  * so its cases hide nothing from anybody. Visibility derives from selection —
  * one fact asked three ways.
  *
  * What may be hidden is a CONTAINER, a node of the [[TreeIndex]]: a pick's
  * case. Every node is in one, the innermost holding it (the root for the
  * page's own), and is shown while every container up its chain is picked.
  * Inside a renderer a container is its `Ix` and an open set a `BitSet` of
  * them: the cases the viewer's values pick ([[screen]]).
  *
  * @param picks
  *   each choice, by its id ([[Dashboard.pickId]]).
  */
private[runtime] final class SurfaceGraph(
    index: TreeIndex,
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
  val pageRoot: Ix = index.root

  /** Every container but the page's root. */
  private val containers: BitSet =
    BitSet.fromSpecific(
      index.all
        .filter(ix => index.isContainer(ix) && ix != pageRoot)
        .map(_.toInt)
    )

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

  /** The picks a node fills: one per clause of the member it is in. */
  def picksAt(host: NodeId): List[NodeId] = picksByHost.getOrElse(host, Nil)

  private val picksByHost: Map[NodeId, List[NodeId]] =
    pickHosts.toList.groupMap(_._2._1)(_._1)

  /** The pick a case root belongs to, and its index there. */
  def caseOf(ix: Ix): Option[(NodeId, Int)] =
    Option(caseGid(ix.toInt)).map(_ -> caseIdx(ix.toInt))

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

  /** A value no case takes and no otherwise catches shows nothing; every value
    * passed `refusals` or `validate`, so that is a guard, which this reports.
    */
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
              s"${Dashboard.membersText(memberValues(gid))}; showing nothing"
          )
        }
    }.toMap

  /** Tabs, decided by a viewer's variable: the chosen case lives in the host,
    * which a patch never carries; planning it is per client
    * ([[Patches.written]]).
    */
  val varPicks: Set[NodeId] =
    picks.collect { case (gid, p) if p.variable.isDefined => gid }.toSet

  private val varPickList: Array[NodeId] = varPicks.toArray.sorted

  /** Picks computing over a variable, alone or with state: decided at the
    * viewer's values and kept per values ([[Shared.branchAt]]), so viewers on
    * different ones are never handed each other's case. The variables are read
    * in the host's scope.
    */
  val viewerPicks: Set[NodeId] =
    picks.collect { case (gid, p) if p.readsViewer => gid }.toSet

  private val viewerPickList: Array[NodeId] = viewerPicks.toArray.sorted

  /** The variables a viewer pick reads, in the order its decision is kept by.
    */
  def viewerVariables(gid: NodeId): List[String] =
    picks.get(gid).filter(_.readsViewer).fold(Nil)(_.by.variables)

  /** The case roots the viewer's values pick, shown or not: a lookup for a
    * plain variable, which costs less than keeping it would, the kept decision
    * for a viewer pick. A viewer pick is decided wherever it stands, though
    * nobody shows it: it reads no fetch (`chosenErrors`), so asking costs a
    * memo entry per values.
    */
  def chosen(shared: Shared): BitSet =
    BitSet.fromSpecific(
      varPickList.iterator.flatMap(gid =>
        decideByVariable(gid, shared).map(picks(gid).choice.cases(_).ix.toInt)
      ) ++ viewerPickList.iterator.flatMap(gid =>
        decideByViewer(gid, shared).map(picks(gid).choice.cases(_).ix.toInt)
      )
    )

  /** A viewer's open set: `spelled`'s containers and the cases its values pick.
    */
  def screen(spelled: Set[String], shared: Shared): BitSet =
    openOf(spelled) | chosen(shared)

  /** If/else hosts over state alone: rendered once per slug for every viewer.
    */
  val statePicks: Set[NodeId] = picks.keySet -- varPicks -- viewerPicks

  /** The nodes `id` names, a pick by its host, one per clause. */
  private def nodesOf(id: NodeId): List[Ix] =
    picks.get(id) match
      case Some(p) => p.node.ix :: Nil
      case None    => index.ixsOf(id)

  /** Every container from `c` up to its tree's root is shown, or, with a
    * `stop`, up to one of its: a container outside `stop` is not. `cut`: picks
    * switching this frame, whose fill renders what is below them. A case of a
    * pick a viewer decides is shown while `open` holds it: `open` is that
    * viewer's [[screen]], which has [[chosen]]'s cases in it.
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

  /** The containers shown from `spelled` down, it included, as `shared` has
    * them.
    */
  def shownWithin(spelled: String, shared: Shared): BitSet = {
    val stop = BitSet.fromSpecific(containersSpelled(spelled).map(_.toInt))
    val open = chosen(shared)
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
    * `shared` has it: every container up its chain is picked, and a pick inside
    * a set member only while the member is in that clause ([[groupShown]]).
    * `open` may hold a tab panel inside a hidden `If`, hence the whole chain.
    */
  def visibleContainer(
      c: Ix,
      open: BitSet,
      shared: Shared,
      cut: Set[NodeId] = Set.empty
  ): Boolean = shownUpTo(c.toInt, BitSet.empty, open, shared, cut)

  /** The containers of one viewer's [[screen]] that are on it. */
  def onScreen(spelled: Set[String], shared: Shared): BitSet = {
    val open = screen(spelled, shared)
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

  /** The inverse of what each of `gids`' `by` reads. Exact, because `by` is
    * subject-free: nothing outside it can move a pick.
    */
  private def reading(gids: Set[NodeId]): Map[String, List[NodeId]] =
    gids.toList.sorted
      .flatMap(gid => picks(gid).by.entities.distinct.map(_ -> gid))
      .groupMap(_._1)(_._2)

  private val statePicksReading: Map[String, List[NodeId]] = reading(statePicks)

  private val viewerPicksReading: Map[String, List[NodeId]] =
    reading(viewerPicks)

  /** The picks some entity can mark dirty; any other is dirty at every version,
    * so it is never carried. A viewer pick reading no entity reads its
    * variables and literals alone, which its memo is keyed by, so no frame can
    * stale it and it is always carried.
    */
  val marked: Set[NodeId] =
    statePicksReading.values.flatten.toSet ++
      viewerPicksReading.values.flatten ++
      viewerPicks.filter(picks(_).by.entities.isEmpty)

  /** The picks `changes` dirty. */
  def dirtied(changes: List[StateChange]): Set[NodeId] =
    changes.iterator
      .flatMap(c =>
        statePicksReading.getOrElse(c.entityId, Nil) ++
          viewerPicksReading.getOrElse(c.entityId, Nil)
      )
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

  /** A viewer pick's `by` read once at the values `shared` views with and
    * looked up, kept per values; `None` fills the host with nothing.
    */
  private[runtime] def decideByViewer(
      gid: NodeId,
      shared: Shared
  ): Option[Int] =
    val p = picks.getOrElse(gid, null)
    if (p == null) None
    else
      shared.branchAt(gid, p.by.variables, p.by.reads)(f =>
        p.decide(p.by.run(null, shared.states, f))
      )

  /** The case `gid` shows for `shared`'s viewer, however it is decided. */
  private[runtime] def decide(gid: NodeId, shared: Shared): Option[Int] =
    if (statePicks(gid)) decideByState(gid, shared)
    else if (viewerPicks(gid)) decideByViewer(gid, shared)
    else decideByVariable(gid, shared)

  /** The viewer picks `changes` move for some of `seen`, each with the cases
    * arriving for those it moves. As [[affectedStateGroups]], per viewer by its
    * own screen: a pick counts for one only if [[groupShown]] before and after
    * and its host is visible past no other pick switching for it, a state flip
    * (`stateFlips`) or another of its own. One moving for no viewer is not
    * here.
    */
  def affectedViewerGroups(
      changes: List[StateChange],
      prev: Shared,
      next: Shared,
      seen: List[SurfaceGraph.Seen],
      stateFlips: Set[NodeId]
  ): List[(NodeId, List[Int])] = {
    val candidates = changes
      .flatMap(c => viewerPicksReading.getOrElse(c.entityId, Nil))
      .distinct
      .filter(gid => groupShown(gid, prev) && groupShown(gid, next))
    if (candidates.isEmpty) Nil
    else {
      val flips = seen.flatMap { s =>
        val (was, now) = (prev.viewing(s.env), next.viewing(s.env))
        val moved = candidates.filter(gid =>
          decideByViewer(gid, was) != decideByViewer(gid, now)
        )
        val cut = stateFlips ++ moved
        moved
          .filter(visibleNodeCut(_, s.visible, now, cut))
          .map(gid => gid -> decideByViewer(gid, now))
      }
      flips
        .groupMap(_._1)(_._2)
        .toList
        .sortBy(_._1)
        .map((gid, cases) => gid -> cases.flatten.distinct)
    }
  }

  /** The case the viewer's value of `gid`'s variable picks, if any. */
  private[runtime] def decideByVariable(
      gid: NodeId,
      shared: Shared
  ): Option[Int] =
    varSelecting(gid)
      .flatMap(name => shared.viewer.get(gid).flatMap(_.get(name)))
      .flatMap(memberAt(gid, _))
}

private[runtime] object SurfaceGraph {

  /** One distinct set of values live sessions hold, and the containers on
    * screen for those holding it.
    */
  final case class Seen(env: VarEnv, visible: BitSet)
}
