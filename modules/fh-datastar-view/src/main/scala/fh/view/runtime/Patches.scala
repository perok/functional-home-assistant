package fh.view.runtime

import cats.effect.IO
import cats.syntax.traverse.*
import cats.syntax.traverseFilter.*
import fh.view.fetch.FetchSnapshot
import fh.view.model.{
  Dashboard,
  DomId,
  Ix,
  NodeId,
  SetId,
  SignalId,
  SlotRead,
  SlotValue,
  TreeIndex
}
import fh.view.model.DomId.selector
import io.circe.Json

import scala.collection.immutable.{BitSet, HashMap}

/** One DOM patch, rendered to SSE only at the edge. A `Morph` carries its
  * target's id inside `html`; `Signals` touches no element (ADR 0017). Targets
  * are [[DomId]]s, never a [[NodeId]], with the `#` added here.
  *
  * A set's or a pick's host is changed by `Fill`, `Place` and `Leave`, which
  * name the host's [[Ix]] and what it then shows ([[Patches.applied]] reads
  * them); `Insert` is left for `#popups`, which no node owns.
  */
private[runtime] enum Patch:
  case Morph(html: String)
  case Insert(html: String, mode: PatchMode, target: DomId)
  case Signals(values: Map[SignalId, Json])

  /** Everything inside `host`, whose element is `target`; `shows` is the root
    * of each thing now in it.
    */
  case Fill(
      host: Ix,
      target: DomId,
      html: String,
      shows: Option[IArray[Ix]]
  )

  /** One member into `host`, which then shows `shown`: before `before`, or
    * last. `target` is `before`'s element, else the host's.
    */
  case Place(
      host: Ix,
      target: DomId,
      html: String,
      shown: Ix,
      before: Option[Ix]
  )

  /** `key`, in `host`, goes: by id, as every clause of a member spells it,
    * since a caller cannot say which one the client holds.
    */
  case Leave(host: Ix, key: NodeId)

  def toSse: SseFrame = this match
    case Patch.Morph(html)                => Datastar.patchElements(html)
    case Patch.Insert(html, mode, target) =>
      Datastar.patch(html, mode, Some(target.selector))
    case Patch.Signals(values) =>
      Datastar.patchSignals(Datastar.signalsJson(values))
    case Patch.Fill(_, target, html, _) =>
      Datastar.patch(html, PatchMode.Inner, Some(target.selector))
    case Patch.Place(_, target, html, _, before) =>
      Datastar.patch(
        html,
        if before.isDefined then PatchMode.Before else PatchMode.Append,
        Some(target.selector)
      )
    case Patch.Leave(_, key) => Datastar.remove(DomId.derived(key).selector)

/** What a host's client does to show what was decided, in order
  * ([[Patches.planHost]]); each key is the [[Ix]] of a shown root. `Fill` with
  * no keys empties the host.
  */
private[runtime] enum HostStep:
  case Fill(keys: IArray[Ix])
  case Leave(key: Ix)

  /** `key` goes before `before`, or last. */
  case Place(key: Ix, before: Option[Ix])

/** A patch and what it does to its client's record. No audience: the session
  * that sends it produced it ([[Patches.resume]]).
  *
  * `establishes` is what its bytes put in the DOM. `invalidates` names the
  * `#popups` host an `Inner` insert re-supplied, so that after applying any
  * patch, `holds` describes the DOM.
  */
private[runtime] case class Addressed(
    patch: Patch,
    establishes: Map[Ix, Held] = Map.empty,
    // Roots: a host and everything under it ([[TreeIndex.contains]]).
    invalidates: Set[NodeId] = Set.empty
)

/** The pure core of the live path, apart from [[Server]]'s `Ref`s and `IO`. The
  * recorder runs [[plan]] and [[record]] once per slug per frame, with no
  * rendering; each session runs [[resume]], [[applied]] and [[encode]].
  */
private[runtime] object Patches {

  case class DiffRequest(
      staticIds: List[NodeId],
      // Membership only: a member that ticked is in `staticIds`.
      sets: List[SetId],
      flips: List[NodeId],
      prev: Shared,
      next: Shared,
      membership: Map[SetId, MemberDelta]
  ):
    // The frame's own, so nothing claims a version its HTML lacks.
    def at: Long = next.version

  /** What one frame touches for every client, rendering nothing. `visible`
    * bounds what is recorded: wide records a node nobody pulls, narrow would
    * drop an update someone needed.
    *
    * Flipped state groups are recorded as [[Mutation]]s and excluded below (the
    * fill re-renders them). A surface binding a changed entity contributes only
    * if it is shown from the page or a visible surface
    * ([[SurfaceGraph.visibleContainer]]), so a hidden branch never gets
    * updates. No viewer's [[Selections]] are read. `prev` is the version
    * before, so its decisions are what viewers were sent.
    */
  def plan(
      renderer: Renderer,
      prev: Shared,
      next: Shared,
      changes: List[StateChange],
      visible: BitSet
  ): DiffRequest = {
    val flips =
      renderer.surfaces.affectedStateGroups(changes, prev, next, visible)
    val flipped = flips.toSet
    // Per frame: every changed entity a branch binds asks of the same surface,
    // and each asking evaluates the conditions above it (`publishFlip`).
    val asked = scala.collection.mutable.BitSet.empty
    val reached = scala.collection.mutable.BitSet.empty
    val shown: Ix => Boolean = c =>
      if (asked.add(c.toInt)) {
        val r = renderer.surfaces.visibleContainer(c, visible, next, flipped)
        if (r) reached += c.toInt
        r
      } else reached(c.toInt)
    // First: `deltas` makes `next`'s set decisions incrementally, where a
    // `binding` reading them first would rebuild each from scratch.
    val membership = renderer.members.deltas(
      renderer.members.affectedSetsWithin(changes, shown),
      changes,
      prev,
      next
    )
    // Members included, so a case slot naming a second entity ticks: that
    // entity need not be a candidate.
    val staticIds = changes
      .flatMap(c =>
        renderer.componentsFor(c.entityId).toList ++
          renderer.members.binding(c.entityId, shown, next) ++
          renderer
            .surfacesBinding(c.entityId)
            .filter(shown)
            .flatMap(renderer.surfaceComponentsFor(_, c.entityId))
      )
      .distinct
      .filterNot(flipped)
    DiffRequest(
      staticIds,
      membership.keys.toList.sortBy(id => id: String),
      flips,
      prev,
      next,
      membership
    )
  }

  /** Loud, rather than caching `""` forever if `byteSlotValues` and
    * `renderPatch` ever disagree on which nodes have a rendering.
    */
  private def mustRender(html: Option[String], id: NodeId): String =
    html.getOrElse(
      throw new IllegalStateException(
        s"'$id' has byte-slot values but no rendering"
      )
    )

  /** The indices of a longest increasing run of `positions`, the survivors that
    * need no move. A set ordered on a live value reorders on every crossing,
    * and moving everything is a patch storm. O(n²) on purpose: n is a room's
    * lights, and this version is checkable.
    */
  private def longestRun(positions: Array[Int]): Set[Int] = {
    val n = positions.length
    if (n == 0) Set.empty
    else {
      // len(i): longest increasing run ending at i. prev(i): its predecessor.
      val len = Array.fill(n)(1)
      val prev = Array.fill(n)(-1)
      for {
        i <- 1 until n
        j <- 0 until i
        if positions(j) < positions(i) && len(j) + 1 > len(i)
      } {
        len(i) = len(j) + 1
        prev(i) = j
      }
      Iterator
        .iterate((0 until n).maxBy(len))(prev(_))
        .takeWhile(_ >= 0)
        .toSet
    }
  }

  /** The fewest survivors to move: everything outside [[longestRun]] of old
    * positions.
    */
  private[runtime] def reordered(
      before: List[String],
      after: List[String]
  ): List[String] = {
    val was = before.zipWithIndex.toMap
    val keep = longestRun(after.map(was.getOrElse(_, -1)).toArray)
    after.zipWithIndex.collect { case (e, i) if !keep(i) => e }
  }

  /** The steps that turn a host whose client shows `held` into one showing
    * `decided`. `None` is a page nobody knows, where only a fill is safe. A
    * kept key is held, decided and not `again`; the rest of `held` leaves, so a
    * key sent `again` is taken out and put back whole, and the placing runs
    * back to front so each anchor is on the page when its turn comes.
    */
  private[runtime] def planHost(
      held: Option[IArray[Ix]],
      decided: IArray[Ix],
      again: Ix => Boolean
  ): List[HostStep] = held match
    case None                         => List(HostStep.Fill(decided))
    case Some(was) if decided.isEmpty => was.toList.map(HostStep.Leave(_))
    case Some(was)                    =>
      val heldAt = was.indices.map(i => was(i) -> i).toMap
      val keepable = decided.toList.filter(k => heldAt.contains(k) && !again(k))
      if keepable.isEmpty then List(HostStep.Fill(decided))
      else {
        val run = longestRun(keepable.map(heldAt).toArray)
        val stays = keepable.zipWithIndex.collect {
          case (k, i) if run(i) => k
        }.toSet
        val leaves = was.toList.filterNot(stays).map(HostStep.Leave(_))
        val places = decided.indices.reverseIterator.collect {
          case i if !stays(decided(i)) =>
            HostStep.Place(
              decided(i),
              Option.when(i + 1 < decided.length)(decided(i + 1))
            )
        }
        leaves ++ places
      }

  def record(
      renderer: Renderer,
      log: FragmentLog,
      req: DiffRequest
  ): FragmentLog = {
    val at = req.at
    // Flips first: nothing may be suppressed against a pre-flip entry.
    val afterFlips = req.flips.foldLeft(log) { (l, gid) =>
      recordFlip(renderer, l, gid, req.prev, req.next, at)
    }
    val afterNodes = req.staticIds.foldLeft(afterFlips)(_.touched(_, at))
    req.sets.foldLeft(afterNodes) { (l, gid) =>
      req.membership
        .get(gid)
        .fold(l)(recordSet(renderer, l, gid, _, at))
    }
  }

  /** [[resume]]'s branch fill is the other half. */
  private def recordFlip(
      renderer: Renderer,
      log: FragmentLog,
      gid: NodeId,
      prev: Shared,
      next: Shared,
      at: Long
  ): FragmentLog = {
    def memberAt(shared: Shared): Option[String] =
      renderer.surfaces
        .decideByState(gid, shared)
        .flatMap(renderer.surfaces.cases(gid).lift)
    val was = memberAt(prev)
    val now = memberAt(next)
    if (was == now) log
    else {
      // The departing branch is gone from the DOM, not just stale.
      val evicted =
        log.invalidateWhere(hostEvicts(renderer, renderer.hostId(gid)))
      val withGone = was
        .map(NodeId.derived)
        .foldLeft(evicted)(_.removed(gid, _, at))
      now.foldLeft(withGone)((acc, id) =>
        acc.placed(gid, MemberKey.Surface(id), NodeId.derived(id), at)
      )
    }
  }

  /** Once per frame, not per entity: two entities can cross the boundary in
    * opposite directions in one tick.
    *
    * Deltas by default. A fill re-sends the unchanged members and raises the
    * host's horizon, so it is used only when `was` or `now` is empty (nothing
    * to re-send) or the log holds none of the members (no baseline to patch).
    */
  private def recordSet(
      renderer: Renderer,
      log: FragmentLog,
      gid: SetId,
      delta: MemberDelta,
      at: Long
  ): FragmentLog = {
    val was = delta.was
    val now = delta.now
    // A member whose case moved. By id: a case binding no live entity is
    // not in the reverse index. One that can hold nodes has no single element
    // to morph, so it is placed again where it stands.
    val (morphed, replaced) =
      delta.replaced.sortBy(_.id).partition(m => renderer.memberIsTarget(m.id))
    val base =
      replaced.foldLeft(morphed.foldLeft(log)((l, m) => l.touched(m.id, at))) {
        (l, m) => l.placed(gid, m.key, m.id, at)
      }
    if (was == now) base
    else {
      val nowSet = now.toSet
      val wasSet = was.toSet
      val added = now.filterNot(wasSet)
      val removed = was.filterNot(nowSet)
      // Only a set ordered by a live value reorders.
      val moved = Patches.reordered(was.filter(nowSet), now.filter(wasSet))
      val churn = added.size + removed.size + moved.size
      if (churn == 0) base
      else if (
        was.isEmpty || now.isEmpty || !base.holdsAnyOf(
          was.map(renderer.members.memberIdOf(gid, _))
        )
      )
        // Touched too, so the next change has a baseline and does not fill
        // again.
        now.foldLeft(base.filled(gid, at, renderer.ancestry))((l, e) =>
          l.touched(renderer.members.memberIdOf(gid, e), at)
        )
      else {
        // A move is a departure plus an arrival, so it needs no patch kind.
        val afterRemoves = (removed ++ moved).foldLeft(base)((l, e) =>
          l.removed(gid, renderer.members.memberIdOf(gid, e), at)
        )
        // Back to front, so each anchor (its successor) is already in the DOM;
        // an insert-before a missing selector is silently dropped.
        val place = (added ++ moved).sortBy(now.indexOf).reverse
        place.foldLeft(afterRemoves) { (l, e) =>
          val cid = renderer.members.memberIdOf(gid, e)
          // Touched too, for the next change's baseline; `since` reports it
          // once, so no extra patch.
          l.placed(gid, MemberKey.Entity(e), cid, at).touched(cid, at)
        }
      }
    }
  }

  /** Everything a client at cursor `v` is owed (ADR 0011) — also the live path,
    * since a pull is a resume from `position + 1`.
    *
    * '''One rule:''' candidates are the nodes logged at `>= v` plus every node
    * of an open surface (which nothing recorded while unwatched). Render each
    * now and send it when its byte-slot values differ from what this viewer
    * holds, a missing entry meaning "send". The cursor selects nodes, never
    * content.
    *
    * A [[Mutation.Placed]] emits `remove` and `insert`, so it is idempotent
    * whatever the DOM holds, and an arrival and a reorder are one operation.
    * '''Placed nodes go out descending by current position''', so each anchor
    * is a member the client had or one placed a moment ago; this needs only
    * that both sides agree on some total order
    * ([[MemberGraph.memberEntities]]).
    */
  def resume(
      renderer: Renderer,
      cache: RenderCache,
      log: FragmentLog,
      holds: Map[Ix, Held],
      shared: Shared,
      // The decisions' fetches, then this pull's reads; most ask none.
      decisions: Set[SlotRead] => IO[FetchSnapshot],
      answers: List[SlotRead] => IO[FetchSnapshot],
      env: VarEnv,
      v: Long,
      open: Set[String] = Set.empty,
      selections: Selections = Selections.none
  ): IO[List[Addressed]] =
    renderer
      .decide(decisions) { run =>
        val at = shared.asking(run)
        val o = owing(renderer, log, at, v, open)
        (
          at,
          o,
          renderer.readsForPull(o.touchedIds, o.hosts, at, selections, env)
        )
      }
      .flatMap { (at, o, reads) =>
        answers(reads).flatMap(snapshot =>
          patches(renderer, cache, holds, at, Viewing(selections, snapshot), o)
        )
      }

  /** What a pull owes one client, as `shared` decides it. */
  private final case class Owing(
      owed: Resume,
      memberMoves: List[(NodeId, Mutation)],
      branch: List[(NodeId, Mutation)],
      fromOpenIds: List[NodeId],
      changed: List[NodeId],
      touchedIds: List[NodeId],
      hosts: List[NodeId]
  )

  private def owing(
      renderer: Renderer,
      log: FragmentLog,
      shared: Shared,
      v: Long,
      spelled: Set[String]
  ): Owing = {
    val open = renderer.surfaces.openOf(spelled)
    val all = log.since(v, renderer.ancestry)
    // Only what this client can see; the rest would cost only bytes.
    val owed = all.copy(
      moved = all.moved.filter { case (_, m) =>
        renderer.surfaces.visibleNode(m.container, open, shared)
      },
      refill = all.refill.filter(renderer.surfaces.visibleNode(_, open, shared))
    )
    // A set needs sibling-preserving deltas; a state group's host holds one
    // member and is overwritten.
    val (memberMoves, branch) = owed.moved.partition { case (_, m) =>
      renderer.members.setContainer(m.container).isDefined
    }
    // Dropped when a mutation or refill re-supplies an ancestor.
    val fromOpenIds = open.toList
      .map(Ix(_))
      // `open` includes a tab panel inside a hidden `If`.
      .filter(renderer.surfaces.visibleContainer(_, open, shared))
      .flatMap(renderer.containerNodeIds)
      .distinct
      .filterNot(id =>
        owed.nodes.contains(id) || owed.moved.exists(_._1 == id) ||
          log.coveredByMutation(
            id,
            owed.moved.map(_._1).toSet ++ owed.refill,
            renderer.ancestry
          )
      )
      .sorted
    val changed = owed.nodes
      .filter(renderer.surfaces.visibleNode(_, open, shared))
      // Document order among siblings, since ids are location-derived.
      .sorted
    // From the candidates, not the patches: a signal-only change emits no
    // patch.
    val touchedIds =
      (changed ++ fromOpenIds ++ memberMoves.collect {
        case (nodeId, _: Mutation.Placed) => nodeId
      }).distinct
    val hosts = (branch.map(_._2.container) ++ owed.refill).distinct
    Owing(owed, memberMoves, branch, fromOpenIds, changed, touchedIds, hosts)
  }

  private def patches(
      renderer: Renderer,
      cache: RenderCache,
      holds: Map[Ix, Held],
      shared: Shared,
      viewing: Viewing,
      o: Owing
  ): IO[List[Addressed]] = {
    import o.*
    val gone = memberMoves.collect { case (nodeId, g: Mutation.Gone) =>
      nodeId -> g.container
    }
    // Without the replay, a client away across a flip sits on an empty host.
    def branchFills = branch
      .groupBy { case (_, m) => m.container }
      .toList
      .sortBy(_._1)
      .flatMap { case (gid, entries) =>
        val content = renderer.renderHost(gid, shared, viewing)
        branchPatch(
          renderer,
          renderer.hostIx(gid, shared),
          gid,
          content,
          entries.map(_._1).sorted.headOption
        ).map(Addressed(_, content.claims))
      }
    def places = memberMoves
      .collect { case (nodeId, p: Mutation.Placed) => (nodeId, p) }
      .groupBy { case (_, p) => p.container }
      .toList
      .sortBy(_._1)
      .flatTraverse { case (container, moves) =>
        renderer.members.setContainer(container).toList.flatTraverse { gid =>
          val members = renderer.members.membersOf(gid, shared)
          val position = members.zipWithIndex.collect {
            case (Member(_, MemberKey.Entity(e), _, _, _), at) => e -> at
          }.toMap
          val host = renderer.hostIx(container, shared)
          moves
            // Still a member (it may have arrived and left while away).
            .flatMap { case (nodeId, p) =>
              p.member match {
                case MemberKey.Entity(e)  => position.get(e).map((nodeId, _))
                case _: MemberKey.Surface => None
              }
            }
            .sortBy { case (_, at) => -at }
            .flatTraverse { case (nodeId, at) =>
              placed(renderer, cache, nodeId, shared, viewing)
                .map(_.toList.flatMap { (html, claims) =>
                  List(
                    Addressed(Patch.Leave(host, nodeId)),
                    Addressed(
                      insertInto(renderer, host, gid, members, at, html),
                      claims
                    )
                  )
                })
            }
        }
      }
    // History no longer reaches this cursor: fill the host, still far cheaper
    // than a body repaint.
    def refills = owed.refill.sorted.map { gid =>
      val content = renderer.renderHost(gid, shared, viewing)
      Addressed(
        Patch.Fill(
          renderer.hostIx(gid, shared),
          renderer.hostId(gid),
          content.parts.map(_._2).mkString,
          Some(content.shown)
        ),
        content.claims
      )
    }
    for {
      morphs <- changed.traverseFilter(
        morph(renderer, cache, holds, shared, viewing, _)
      )
      open <- fromOpenIds.traverseFilter(
        morph(renderer, cache, holds, shared, viewing, _)
      )
      placed <- places
    } yield signalFrame(
      renderer,
      holds,
      shared,
      viewing.fragments,
      touchedIds
    ) ++
      morphs ++ open ++
      gone.sortBy(_._1).map { (id, container) =>
        Addressed(Patch.Leave(renderer.hostIx(container, shared), id))
      } ++
      branchFills ++ placed ++ refills
  }

  /** The batch's one signal frame (ADR 0017), diffed against `holds`. First,
    * because an inserted member's patch-form bytes carry no seed, so the frame
    * is its only value.
    */
  private def signalFrame(
      renderer: Renderer,
      holds: Map[Ix, Held],
      shared: Shared,
      fragments: FetchSnapshot,
      ids: List[NodeId]
  ): List[Addressed] = {
    // Per session and per signal, so no tuple, `Some` or builder that a
    // lookup or a pair-taking `+=` would allocate: this was most of an extra
    // client's cost (`RenderBench.resumeSignalsFanout`). Never key a node's
    // signals by node id — that silently keeps one of the slider's four.
    val payload = HashMap.newBuilder[SignalId, Json]
    val heldB = HashMap.newBuilder[Ix, Held]
    var anyMoved = false
    ids.foreach { id =>
      renderer.nodeAt(id, shared) match
        case Some(node) =>
          val held = holds.get(node.ix) match
            case Some(h) => h.signals
            case None    => Map.empty[SignalId, SlotValue]
          val now = renderer.signalsOf(id, node, shared, fragments)
          var moved = 0
          now.foreachEntry { (name, value) =>
            if (!holdsValue(held, name, value)) {
              payload.addOne(name, SlotValue.json(value))
              moved += 1
            }
          }
          if (moved > 0) {
            // Usually every one moved, and then the node's map is the held one.
            val nodeHeld =
              if (moved == now.size) now
              else now.filter((name, value) => !holdsValue(held, name, value))
            heldB.addOne(node.ix, Held(signals = nodeHeld))
            anyMoved = true
          }
        case None => ()
    }
    if (!anyMoved) Nil
    else List(Addressed(Patch.Signals(payload.result()), heldB.result()))
  }

  private def holdsValue(
      held: Map[SignalId, SlotValue],
      name: SignalId,
      value: SlotValue
  ): Boolean = {
    val h = held.getOrElse(name, null)
    h != null && h == value
  }

  /** The whole suppression rule, against this viewer's `holds`. */
  private[runtime] def morph(
      renderer: Renderer,
      cache: RenderCache,
      holds: Map[Ix, Held],
      shared: Shared,
      viewing: Viewing,
      id: NodeId
  ): IO[Option[Addressed]] = {
    // Equal byte-slot values, equal patch bytes (`ByteValuesPropertySuite`):
    // what this viewer holds already answers, with no render and no cache
    // lookup. Unequal values are sent: the bytes rarely survive a value change,
    // and a morph to the bytes the client has changes nothing in its DOM.
    renderer.ownValues(id, shared, viewing.fragments) match {
      case Some(own)
          if !holds.get(own.ix).exists(_.values.contains(own.values)) =>
        IO(
          Some(
            Addressed(
              Patch.Morph(cached(renderer, cache, id, own, shared)),
              Map(own.ix -> Held(Some(own.values)))
            )
          )
        )
      case _ => unchanged
    }
  }

  // Shared: the common tick skips every node, and an `IO` each was 1 kB a tick.
  private val unchanged: IO[Option[Addressed]] = IO.pure(None)

  /** Through the slug's [[RenderCache]], so the sessions one tick wakes share a
    * node's render. Runs inside its caller's `IO`.
    */
  private def cached(
      renderer: Renderer,
      cache: RenderCache,
      id: NodeId,
      own: Own,
      shared: Shared
  ): String =
    cache(own.ix, renderer, own.values)(
      mustRender(renderer.renderPatch(id, shared, own.values), id)
    )

  /** What a placed member inserts and claims. A member that is a leaf comes
    * through the cache as any target does; one holding nodes renders per viewer
    * with its subtree's claims, since it can show this viewer's choice.
    */
  private def placed(
      renderer: Renderer,
      cache: RenderCache,
      id: NodeId,
      shared: Shared,
      viewing: Viewing
  ): IO[Option[(String, Map[Ix, Held])]] =
    IO(
      if (renderer.memberIsTarget(id))
        renderer
          .ownValues(id, shared, viewing.fragments)
          .map(own =>
            cached(renderer, cache, id, own, shared) ->
              Map(own.ix -> Held(Some(own.values)))
          )
      else
        renderer
          .renderMemberTraced(id, shared, viewing)
          .map(t => t.html -> t.claims)
    )

  /** Positional in `ordered`, never by comparing ids, so an author-chosen order
    * works.
    */
  private def insertInto(
      renderer: Renderer,
      host: Ix,
      gid: SetId,
      ordered: Vector[Member],
      at: Int,
      html: String
  ): Patch = {
    val shown = ordered(at).node.ix
    ordered.lift(at + 1) match {
      case Some(succ) =>
        Patch.Place(
          host,
          renderer.elementId(succ.id),
          html,
          shown,
          Some(succ.node.ix)
        )
      case None =>
        Patch.Place(host, renderer.hostId(gid), html, shown, None)
    }
  }

  /** A tab switch or popup open: one client's choice, so it touches only that
    * session's `holds`. The departing member is evicted, by the fill's range
    * for a pick and by `invalidates` for `#popups`; a stale claim would
    * suppress a real change on its return.
    */
  private[runtime] def hostFill(
      renderer: Renderer,
      host: DomId,
      arriving: Option[String],
      shared: Shared,
      viewing: Viewing
  ): Option[(Addressed, String)] =
    arriving.flatMap { id =>
      renderer.renderShownTraced(id, shared, viewing).map { t =>
        val addressed =
          if host == Dashboard.PopupHostId then
            Addressed(
              Patch.Insert(t.html, PatchMode.Inner, host),
              t.claims,
              (renderer.surfaces.surfacesAt(host) ++ arriving)
                .flatMap(renderer.surfaceNodeIds)
            )
          else
            Addressed(
              Patch.Fill(
                renderer
                  .hostIxShowing(id, shared)
                  .getOrElse(
                    throw new IllegalStateException(
                      s"'$id' is shown in '$host' but no pick shows it"
                    )
                  ),
                host,
                t.html,
                Some(IArray(t.root))
              ),
              t.claims
            )
        (addressed, t.html)
      }
    }

  /** The body and the open popup, and what they leave the session holding. The
    * popup's host sits outside `#dashboard`: a repaint that stopped there left
    * the dialog showing what it held before, until a pull happened to re-send
    * its nodes.
    */
  private[runtime] def repaint(
      renderer: Renderer,
      shared: Shared,
      viewing: Viewing
  ): (List[SseFrame], Map[Ix, Held]) = {
    val body = renderer.renderBodyTraced(shared, viewing)
    val popup = hostFill(
      renderer,
      Dashboard.PopupHostId,
      viewing.selections.popup.flatMap(renderer.surfaces.popupSpelling),
      shared,
      viewing
    ).map(_._1)
    val held = popup.foldLeft(body.claims)(applied(renderer.index, _, _))
    // The seeds alone are not enough: the morph re-applies a seed only when
    // its text changed, so on a page that missed frames (a forgotten session,
    // a restart) a value that moved and came back stayed where the frames
    // left it.
    val values = held.valuesIterator
      .flatMap(_.signals)
      .map((name, v) => name -> SlotValue.json(v))
      .toMap
    (
      Datastar.patch(body.html, PatchMode.Inner, Some("#dashboard")) ::
        popup.map(_.patch.toSse).toList ++
        Option.when(values.nonEmpty)(Patch.Signals(values).toSse),
      held
    )
  }

  /** What a host's surfaces evict when nothing arrives in it (a close). */
  private[runtime] def hostEvicts(
      renderer: Renderer,
      host: DomId
  ): Set[NodeId] =
    renderer.surfaces.surfacesAt(host).flatMap(renderer.surfaceNodeIds)

  /** Invalidate first, then claim: a fill re-supplies a host and places members
    * in it. Containment is [[TreeIndex.contains]]'s, not id spelling.
    *
    * A host's list ([[Held.shows]]) follows the structural patch that moved it:
    *   - `Fill` drops everything below the host and sets the list;
    *   - `Place` inserts before its anchor, or last, and an anchor the list
    *     lacks makes the list unknown;
    *   - `Leave` drops the subtree of each clause of the key and takes it out
    *     of the list.
    *
    * A host with no entry has no list to move, and a `Place` into one leaves it
    * so: its next fill sets one.
    */
  def applied(
      index: TreeIndex,
      holds: Map[Ix, Held],
      patch: Addressed
  ): Map[Ix, Held] = {
    val roots = patch.invalidates.toList.flatMap(index.ixsOf)
    val structural = patch.patch match
      case Patch.Fill(host, _, _, shows) =>
        showing(
          holds.filterNot((ix, _) => ix != host && index.contains(host, ix)),
          host
        )(_ => shows)
      case Patch.Place(host, _, _, shown, before) =>
        showing(holds, host)(_.flatMap(placedIn(_, shown, before)))
      case Patch.Leave(host, key) =>
        val keys = index.ixsOf(key)
        showing(
          holds.filterNot((ix, _) => keys.exists(index.contains(_, ix))),
          host
        )(_.map(_.filterNot(ix => keys.contains(ix))))
      case _ => holds
    // Merged per node: a morph says nothing about signals and a signal frame
    // nothing about bytes ([[Held.merge]]).
    patch.establishes.foldLeft(
      if (roots.isEmpty) structural
      else
        structural.filterNot { case (ix, _) =>
          roots.exists(index.contains(_, ix))
        }
    ) { case (acc, (ix, later)) =>
      acc.updated(ix, acc.get(ix).fold(later)(_.merge(later)))
    }
  }

  /** `host`'s list through `change`; a host with no entry keeps none unless
    * `change` makes a list from nothing, as a fill does.
    */
  private def showing(holds: Map[Ix, Held], host: Ix)(
      change: Option[IArray[Ix]] => Option[IArray[Ix]]
  ): Map[Ix, Held] =
    holds.get(host) match
      case Some(held) =>
        holds.updated(host, held.copy(shows = change(held.shows)))
      case None =>
        change(None).fold(holds)(list =>
          holds.updated(host, Held(shows = Some(list)))
        )

  private def placedIn(
      list: IArray[Ix],
      shown: Ix,
      before: Option[Ix]
  ): Option[IArray[Ix]] =
    before match
      case None         => Some(list :+ shown)
      case Some(anchor) =>
        val at = list.indexOf(anchor)
        Option.when(at >= 0)(list.take(at) ++ (shown +: list.drop(at)))

  /** Merges adjacent morphs and adjacent signal frames, never across an insert
    * or remove: a later morph may target what the insert created.
    */
  def encode(patches: List[Addressed]): List[SseFrame] =
    patches
      .map(_.patch)
      .foldLeft(List.empty[Patch]) {
        case (Patch.Morph(before) :: rest, Patch.Morph(next)) =>
          Patch.Morph(before + next) :: rest
        // Adjacent only, which keeps the trailing cursor an ack: past an
        // element patch it stays its own event (ADR 0011).
        case (Patch.Signals(before) :: rest, Patch.Signals(next)) =>
          Patch.Signals(before ++ next) :: rest
        case (acc, one) => one :: acc
      }
      .reverse
      .map(_.toSse)

  /** `Inner` is both the delta and idempotent for a one-member host. Emptying
    * is a `remove` of the departed branch: an empty `Inner` is not a
    * well-formed patch.
    */
  private def branchPatch(
      renderer: Renderer,
      host: Ix,
      gid: NodeId,
      content: HostContent,
      departed: Option[NodeId]
  ): List[Patch] =
    content.parts.map(_._2).reduceOption(_ + _) match {
      case Some(html) =>
        List(
          Patch.Fill(host, renderer.hostId(gid), html, Some(content.shown))
        )
      case None =>
        departed.map(Patch.Leave(host, _)).toList
    }

}
