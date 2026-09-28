package fh.view.runtime

import cats.effect.IO
import cats.syntax.traverse.*
import cats.syntax.traverseFilter.*
import fh.view.query.QuerySnapshot
import fh.view.model.{DomId, NodeId, SetId, SignalId, SlotRead, SlotValue}
import fh.view.model.DomId.selector
import io.circe.Json

/** One DOM patch, rendered to SSE only at the edge. A `Morph` carries its
  * target's id inside `html`; `Signals` touches no element (ADR 0017). Targets
  * are [[DomId]]s, never a [[NodeId]], with the `#` added here.
  */
private[runtime] enum Patch:
  case Morph(html: String)
  case Insert(html: String, mode: PatchMode, target: DomId)
  case Remove(target: DomId)
  case Signals(values: Map[SignalId, Json])

  def toSse: SseFrame = this match
    case Patch.Morph(html)                => Datastar.patchElements(html)
    case Patch.Insert(html, mode, target) =>
      Datastar.patch(html, mode, Some(target.selector))
    case Patch.Remove(target)  => Datastar.remove(target.selector)
    case Patch.Signals(values) =>
      Datastar.patchSignals(Datastar.signalsJson(values))

/** A patch and what it does to its client's record. No audience: the session
  * that sends it produced it ([[Patches.resume]]).
  *
  * `establishes` is what its bytes put in the DOM. `invalidates` names hosts an
  * `Inner` fill re-supplied — the only patch that aims at a host — so that
  * after applying any patch, `holds` describes the DOM. A `Remove` needs
  * neither: a stale claim on a gone element costs at most an ignored morph.
  */
private[runtime] case class Addressed(
    patch: Patch,
    establishes: Map[NodeId, Held] = Map.empty,
    // Roots: a host and everything under it ([[NodeAncestry]]).
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
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      // Carried, not re-derived: the graph has already moved.
      membership: Map[SetId, MemberDelta],
      // Read with the snapshot, so nothing claims a version its HTML lacks.
      at: Long
  )

  /** Every entity the frame moved rewound, not one: rewinding one alone
    * describes an instant that never existed.
    */
  def beforeSnapshot(
      states: Map[String, EntityState],
      changes: List[StateChange]
  ): Map[String, EntityState] =
    changes.foldLeft(states) { (acc, change) =>
      change.previous.fold(acc - change.entityId)(p =>
        acc.updated(change.entityId, p)
      )
    }

  /** What one frame touches for every client, rendering nothing. `visible`
    * bounds what is recorded: wide records a node nobody pulls, narrow would
    * drop an update someone needed.
    *
    * Flipped state groups are recorded as [[Mutation]]s and excluded below (the
    * fill re-renders them). Only surfaces active from the page or a visible
    * surface contribute, so a hidden branch never gets updates by construction.
    * No `uiState` is read.
    */
  def plan(
      renderer: Renderer,
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      membership: Map[SetId, MemberDelta],
      at: Long,
      changes: List[StateChange],
      visible: Set[String]
  ): DiffRequest = {
    val flips =
      (renderer.surfaces.affectedStateGroups(changes, before, states) ++
        visible.toList.flatMap(sid =>
          renderer.surfaces.affectedStateGroupsIn(sid, changes, before, states)
        )).distinct
    val flipped = flips.toSet
    val activeSids = renderer.surfaces.activeStateSurfaces(states, flipped) ++
      visible.flatMap(
        renderer.surfaces.activeStateSurfacesIn(_, states, flipped)
      )
    val sids = (visible ++ activeSids).toList
    val staticIds = changes
      .flatMap(c =>
        renderer.componentsFor(c.entityId).toList ++
          sids.flatMap(sid =>
            renderer.surfaceComponentsFor(sid, c.entityId).toList
          )
      )
      .distinct
      .filterNot(flipped)
    val sets =
      (renderer.members.affectedSets(changes) ++
        sids.flatMap(renderer.members.affectedSurfaceSets(_, changes))).distinct
    DiffRequest(staticIds, sets, flips, states, before, membership, at)
  }

  /** Loud, rather than caching `""` forever if `renderInputs` and
    * `renderNodeById` ever disagree.
    */
  private def mustRender(html: Option[String], id: NodeId): String =
    html.getOrElse(
      throw new IllegalStateException(
        s"'$id' has a render key but no rendering"
      )
    )

  /** The fewest survivors to move: everything outside a longest increasing
    * subsequence of old positions. A set ordered on a live value reorders on
    * every crossing, and moving everything is a patch storm. O(n²) on purpose:
    * n is a room's lights, and this version is checkable.
    */
  private[runtime] def reordered(
      before: List[String],
      after: List[String]
  ): List[String] = {
    val was = before.zipWithIndex.toMap
    val idx = after.map(was.getOrElse(_, -1)).toArray
    val n = idx.length
    if (n < 2) Nil
    else {
      // len(i): longest increasing run ending at i. prev(i): its predecessor.
      val len = Array.fill(n)(1)
      val prev = Array.fill(n)(-1)
      for {
        i <- 1 until n
        j <- 0 until i
        if idx(j) < idx(i) && len(j) + 1 > len(i)
      } {
        len(i) = len(j) + 1
        prev(i) = j
      }
      val keep = Iterator
        .iterate((0 until n).maxBy(len))(prev)
        .takeWhile(_ >= 0)
        .toSet
      after.zipWithIndex.collect { case (e, i) if !keep(i) => e }
    }
  }

  def record(
      renderer: Renderer,
      log: FragmentLog,
      req: DiffRequest
  ): FragmentLog = {
    val at = req.at
    // Flips first: nothing may be suppressed against a pre-flip entry.
    val afterFlips = req.flips.foldLeft(log) { (l, gid) =>
      recordFlip(renderer, l, gid, req.before, req.states, at)
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
      before: Map[String, EntityState],
      states: Map[String, EntityState],
      at: Long
  ): FragmentLog = {
    def memberAt(snapshot: Map[String, EntityState]): Option[String] =
      renderer.surfaces
        .resolveActiveByState(gid, snapshot)
        .flatMap(renderer.surfaces.bakeGroup(gid).lift)
    val was = memberAt(before)
    val now = memberAt(states)
    if (was == now) log
    else {
      // The departing branch is gone from the DOM, not just stale.
      val evicted =
        log.invalidateWhere(hostEvicts(renderer, renderer.hostId(gid)))
      val withGone = was
        .map(renderer.surfaceContentId)
        .foldLeft(evicted)(_.removed(gid, _, at))
      now.foldLeft(withGone)((acc, sid) =>
        acc.placed(
          gid,
          MemberKey.Surface(sid),
          renderer.surfaceContentId(sid),
          at
        )
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
    // not in the reverse index.
    val base = delta.replaced.toList.sorted.foldLeft(log)(_.touched(_, at))
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
    * now and send it when its digest differs from what this viewer holds, a
    * missing entry meaning "send". The cursor selects nodes, never content.
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
      holds: Map[NodeId, Held],
      states: Map[String, EntityState],
      // Asked only for this pull's reads; most ask none.
      answers: List[SlotRead] => IO[QuerySnapshot],
      env: VarEnv,
      v: Long,
      open: Set[String] = Set.empty,
      uiState: Map[String, String] = Map.empty
  ): IO[List[Addressed]] = {
    val all = log.since(v, renderer.ancestry)
    // Only what this client can see; the rest would cost only bytes.
    val owed = all.copy(
      moved = all.moved.filter { case (_, m) =>
        renderer.surfaces.visibleNode(m.container, open, states)
      },
      refill = all.refill.filter(renderer.surfaces.visibleNode(_, open, states))
    )
    // A set needs sibling-preserving deltas; a state group's host holds one
    // member and is overwritten.
    val (memberMoves, branch) = owed.moved.partition { case (_, m) =>
      renderer.members.setContainer(m.container).isDefined
    }
    val gone = memberMoves.collect { case (nodeId, _: Mutation.Gone) => nodeId }
    // Without the replay, a client away across a flip sits on an empty host.
    def branchFills(fragments: QuerySnapshot) = branch
      .groupBy { case (_, m) => m.container }
      .toList
      .sortBy(_._1)
      .flatMap { case (gid, entries) =>
        val content = renderer.renderHost(gid, states, uiState, fragments)
        branchPatch(
          renderer,
          gid,
          content.parts.map(_._2).reduceOption(_ + _),
          entries.map(_._1).sorted.headOption
        ).map(
          // Branch ids are `s_<surface>__…`, outside the container's id, so
          // the host names what it holds.
          Addressed(
            _,
            content.claims,
            invalidates = hostEvicts(renderer, renderer.hostId(gid))
          )
        )
      }
    def places(fragments: QuerySnapshot) = memberMoves
      .collect { case (nodeId, p: Mutation.Placed) => (nodeId, p) }
      .groupBy { case (_, p) => p.container }
      .toList
      .sortBy(_._1)
      .flatTraverse { case (container, moves) =>
        renderer.members.setContainer(container).toList.flatTraverse { gid =>
          val members = renderer.members.memberEntities(gid, states)
          val position = members.zipWithIndex.toMap
          moves
            // Still a member (it may have arrived and left while away).
            .flatMap { case (nodeId, p) =>
              p.member match {
                case MemberKey.Entity(e)  => position.get(e).map((nodeId, e, _))
                case _: MemberKey.Surface => None
              }
            }
            .sortBy { case (_, _, at) => -at }
            .flatTraverse { case (nodeId, entityId, _) =>
              bytes(renderer, cache, nodeId, states, uiState, fragments).map(
                _.toList.flatMap { case NodeBytes(html, digest) =>
                  List(
                    Addressed(Patch.Remove(renderer.elementId(nodeId))),
                    Addressed(
                      insertInto(
                        renderer,
                        gid,
                        members,
                        entityId,
                        _ => true,
                        html
                      ),
                      Map(nodeId -> Held.bytes(digest))
                    )
                  )
                }
              )
            }
        }
      }
    // History no longer reaches this cursor: fill the host, still far cheaper
    // than a body repaint.
    def refills(fragments: QuerySnapshot) = owed.refill.sorted.map { gid =>
      val asSet = renderer.members.setContainer(gid)
      val content = renderer.renderHost(gid, states, uiState, fragments)
      Addressed(
        Patch.Insert(
          content.parts.map(_._2).mkString,
          PatchMode.Inner,
          renderer.hostId(gid)
        ),
        content.claims,
        if (asSet.isDefined) Set(gid)
        else hostEvicts(renderer, renderer.hostId(gid))
      )
    }
    // Dropped when a mutation or refill re-supplies an ancestor.
    val fromOpenIds = open.toList
      // `open` includes a tab panel inside a hidden `If`.
      .filter(renderer.surfaces.visibleSurface(_, open, states))
      .flatMap(renderer.surfaceNodeIds)
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
      .filter(renderer.surfaces.visibleNode(_, open, states))
      // Document order among siblings, since ids are location-derived.
      .sorted
    // From the candidates, not the patches: a signal-only change emits no
    // patch.
    val touchedIds =
      (changed ++ fromOpenIds ++ memberMoves.collect {
        case (nodeId, _: Mutation.Placed) => nodeId
      }).distinct
    val hosts = (branch.map(_._2.container) ++ owed.refill).distinct
    for {
      fragments <- answers(
        renderer.readsForPull(touchedIds, hosts, states, uiState, env)
      )
      morphs <- changed.traverseFilter(
        morph(renderer, cache, holds, states, uiState, fragments, _)
      )
      open <- fromOpenIds.traverseFilter(
        morph(renderer, cache, holds, states, uiState, fragments, _)
      )
      placed <- places(fragments)
    } yield signalFrame(renderer, holds, states, touchedIds) ++
      morphs ++ open ++
      gone.toList.sorted.map(id =>
        Addressed(Patch.Remove(renderer.elementId(id)))
      ) ++
      branchFills(fragments) ++ placed ++ refills(fragments)
  }

  /** The batch's one signal frame (ADR 0017), diffed against `holds`. First,
    * because an inserted member's patch-form bytes carry no seed, so the frame
    * is its only value.
    */
  private def signalFrame(
      renderer: Renderer,
      holds: Map[NodeId, Held],
      states: Map[String, EntityState],
      ids: List[NodeId]
  ): List[Addressed] = {
    // One pass: a collections pipeline was 19% of a signals tick's allocation
    // (`RenderBench.resumeSignals`). Never key a node's signals by node id —
    // that silently keeps one of the slider's four.
    val payload = Map.newBuilder[SignalId, Json]
    val heldB = Map.newBuilder[NodeId, Held]
    var anyMoved = false
    ids.foreach { id =>
      val held = holds.get(id).fold(Map.empty[SignalId, SlotValue])(_.signals)
      val nodeB = Map.newBuilder[SignalId, SlotValue]
      var nodeMoved = false
      renderer.signalsFor(id, states).foreach { case (name, value) =>
        if (!held.get(name).contains(value)) {
          // A JSON boolean: the string `"false"` is truthy in every binding.
          payload += ((
            name,
            value match
              case b: Boolean => Json.fromBoolean(b)
              case s: String  => Json.fromString(s)
          ))
          nodeB += ((name, value))
          nodeMoved = true
        }
      }
      if (nodeMoved) {
        heldB += ((id, Held(signals = nodeB.result())))
        anyMoved = true
      }
    }
    if (!anyMoved) Nil
    else List(Addressed(Patch.Signals(payload.result()), heldB.result()))
  }

  /** The whole suppression rule, against this viewer's `holds`. */
  private[runtime] def morph(
      renderer: Renderer,
      cache: RenderCache,
      holds: Map[NodeId, Held],
      states: Map[String, EntityState],
      uiState: Map[String, String],
      fragments: QuerySnapshot,
      id: NodeId
  ): IO[Option[Addressed]] =
    bytes(renderer, cache, id, states, uiState, fragments).map(_.flatMap {
      case NodeBytes(html, digest) =>
        Option.when(!holds.get(id).flatMap(_.digest).contains(digest))(
          Addressed(Patch.Morph(html), Map(id -> Held.bytes(digest)))
        )
    })

  /** Through the slug's [[RenderCache]], so N sessions woken together render a
    * node once; a node without a sound key renders uncached.
    */
  private def bytes(
      renderer: Renderer,
      cache: RenderCache,
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      fragments: QuerySnapshot
  ): IO[Option[NodeBytes]] =
    renderer.renderInputs(id, states, fragments) match {
      case Some(inputs) =>
        cache(
          id,
          renderer,
          inputs,
          renderer.byteSlotValues(id, states, fragments)
        )(
          IO(
            mustRender(
              renderer.renderNodeById(
                id,
                states,
                uiState,
                fragments = fragments
              ),
              id
            )
          )
        ).map(Some(_))
      case None =>
        IO(
          renderer
            .renderNodeById(id, states, uiState, fragments = fragments)
            .map(NodeBytes.of)
        )
    }

  /** Positional in `ordered`, never by comparing ids, so an author-chosen order
    * works.
    */
  private def insertInto(
      renderer: Renderer,
      gid: SetId,
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
          renderer.elementId(renderer.members.memberIdOf(gid, succ))
        )
      case None =>
        Patch.Insert(html, PatchMode.Append, renderer.hostId(gid))
    }

  /** A tab switch or popup open: one client's choice, so it touches only that
    * session's `holds`. The departing member is evicted via `invalidates`; a
    * stale claim would suppress a real change on its return.
    */
  private[runtime] def hostFill(
      renderer: Renderer,
      host: DomId,
      arriving: Option[String],
      states: Map[String, EntityState],
      uiState: Map[String, String],
      fragments: QuerySnapshot
  ): Option[(Addressed, String)] =
    arriving
      .flatMap(renderer.renderSurfaceTraced(_, states, uiState, fragments))
      .map { t =>
        (
          Addressed(
            Patch.Insert(t.html, PatchMode.Inner, host),
            t.claims,
            (renderer.surfaces.surfacesAt(host) ++ arriving)
              .flatMap(renderer.surfaceNodeIds)
          ),
          t.html
        )
      }

  /** [[hostFill]]'s `invalidates` when nothing arrives. */
  private[runtime] def hostEvicts(
      renderer: Renderer,
      host: DomId
  ): Set[NodeId] =
    renderer.surfaces.surfacesAt(host).flatMap(renderer.surfaceNodeIds)

  /** Invalidate first, then claim: a fill re-supplies a host and places members
    * in it. Containment is [[NodeAncestry]]'s, not id spelling.
    */
  def applied(
      ancestry: NodeAncestry,
      holds: Map[NodeId, Held],
      patch: Addressed
  ): Map[NodeId, Held] =
    // Merged per node: a morph says nothing about signals and a signal frame
    // nothing about bytes ([[Held.merge]]).
    patch.establishes.foldLeft(
      if (patch.invalidates.isEmpty) holds
      else
        holds.filterNot { case (id, _) =>
          ancestry.withinAny(id, patch.invalidates.toSet)
        }
    ) { case (acc, (id, later)) =>
      acc.updated(id, acc.get(id).fold(later)(_.merge(later)))
    }

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
      gid: NodeId,
      content: Option[String],
      departed: Option[NodeId]
  ): List[Patch] =
    content match {
      case Some(html) =>
        List(Patch.Insert(html, PatchMode.Inner, renderer.hostId(gid)))
      case None =>
        departed.map(id => Patch.Remove(renderer.elementId(id))).toList
    }

}
