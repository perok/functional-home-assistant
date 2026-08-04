package fh.view.runtime

import cats.effect.IO
import cats.syntax.traverse.*
import cats.syntax.traverseFilter.*
import fh.view.model.{DomId, NodeId}
import fh.view.model.DomId.selector
import org.http4s.ServerSentEvent

/** One DOM patch the diff pass wants to send, rendered to a Datastar SSE event
  * at the edge ([[Patch.toSse]]). The diff does not yield a uniform "HTML to
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

/** One patch, and what it does to the record of the client it is going to.
  *
  * There is no audience tag any more, and its absence is the point: a patch is
  * produced BY the session that will send it ([[Patches.resume]]), against that
  * session's own open set and own `holds`, so there is nobody left to hide it
  * from. What used to be a shared patch plus a surface filter is now simply a
  * patch nobody else was offered.
  *
  * `establishes` is what this patch's BYTES put in the client's DOM: one entry
  * per node the patch renders, digest included. It is the only thing that can
  * tell a session what it just sent.
  *
  * `invalidates` names mounts this patch RE-SUPPLIED. The self/mount split
  * means an ordinary morph can never touch a child — a container's patch
  * targets `<id>-self`, not the sibling mount ([[Renderer.patchTargetId]]) — so
  * this is about the one patch that aims AT a mount: an `Inner` fill is
  * all-or-nothing over its children by design.
  *
  * Load-bearing where the fill carries no per-node trace (a branch fill, a
  * refill, a body repaint): those nodes are still on screen showing fill-time
  * bytes while `holds` claims older ones, and a value coming round again would
  * be suppressed against a DOM that never had it. A fill that DOES trace what
  * it painted covers itself through `establishes`, and its roots only clear
  * members the fill deleted — kept anyway, so that "after applying a patch,
  * `holds` describes the DOM" holds without a per-site exception.
  *
  * A [[Patch.Remove]] needs neither: it places no bytes, and a stale claim for
  * an element that is GONE costs at most a morph at a missing id, which the
  * client silently ignores. What brings it back is an insert, which establishes
  * afresh.
  */
private[runtime] case class Addressed(
    patch: Patch,
    establishes: Map[NodeId, Digest] = Map.empty,
    // Roots, applied by prefix — a mount and everything under it.
    invalidates: Set[NodeId] = Set.empty
)

/** A frame's renders, done once and through the per-slug [[RenderCache]].
  *
  * '''Currently off the server's path.''' The publisher renders nothing at all
  * now ([[Patches.record]]); the renders happen in each session's
  * [[Patches.resume]], and wiring this cache into THAT is the next step — it is
  * what restores the fan-out between sessions viewing one slug
  * (docs/plan-session-pulled-changelog.md). Kept rather than deleted and
  * rewritten, because the keying it exercises ([[Renderer.renderInputs]],
  * `RenderInputsSuite`) is what that step needs to still hold.
  *
  * '''Misses fall through to the renderer''' rather than failing, which keeps
  * this a cache rather than a contract.
  */
private[runtime] final class Renders(
    val renderer: Renderer,
    states: Map[String, EntityState],
    before: Map[String, EntityState],
    nodes: Map[NodeId, Option[NodeBytes]],
    children: Map[(NodeId, String), Option[NodeBytes]],
    fills: Map[NodeId, List[(NodeId, NodeBytes)]],
    membersNow: Map[NodeId, List[String]],
    membersWas: Map[NodeId, List[String]]
) {
  def node(id: NodeId): Option[NodeBytes] =
    nodes.getOrElse(id, renderer.renderNodeById(id, states).map(NodeBytes.of))

  def child(gid: NodeId, entityId: String): Option[NodeBytes] =
    children.getOrElse(
      (gid, entityId),
      renderer.renderDynamicChild(gid, entityId, states).map(NodeBytes.of)
    )

  def fill(gid: NodeId): List[(NodeId, NodeBytes)] =
    fills.getOrElse(
      gid,
      renderer.renderDynamicMembers(gid, states).map { case (id, html) =>
        id -> NodeBytes.of(html)
      }
    )

  def membersAfter(gid: NodeId): List[String] =
    membersNow.getOrElse(gid, renderer.dynamicMembers(gid, states))

  def membersBefore(gid: NodeId): List[String] =
    membersWas.getOrElse(gid, renderer.dynamicMembers(gid, before))
}

/** The pure core, lifted out of [[Server]] so it is testable without a booted
  * server (no HA stub, no `Supervisor`, no SSE plumbing). Two paths meet here,
  * and they no longer share a pass:
  *
  *   - the PUBLISHER, once per slug per frame: [[plan]] SELECTS what one state
  *     change touches, [[record]] writes that to the changelog. No rendering.
  *   - the SESSION, once per connection: [[resume]] renders what THIS client is
  *     owed from `position + 1`, [[applied]] folds the result into its record,
  *     [[encode]] puts it on the wire.
  *
  * Everything here is pure over the entity snapshot; the caller ([[Server]])
  * owns the `Ref`/`IO` that reads the snapshot and updates the log.
  */
private[runtime] object Patches {

  /** A selection of what one [[StateChange]] touches, ready for [[record]].
    * Bundles the assembled `staticIds`/`dynamics`/`flips` with the render
    * inputs (`change`/`states`/`before`) they are diffed with, rather than nine
    * positional arguments at the call site.
    */
  case class DiffRequest(
      // Each selected node carries WHOSE it is: the user-selected surface it
      // sits inside, or `None` for the main page. The tag originates here,
      // where the surface is known — it cannot be recovered from a node id
      // afterwards, because a node's id encodes only its own surface, not the
      // chain of surfaces containing it (a tab panel inside an `If` branch
      // inside another tab panel is three independent prefixes).
      staticIds: List[(NodeId, Option[String])],
      // Nodes whose OWN markup reads their own selection, so there is one
      // rendering per member and no single answer to diff against. They never
      // enter the pure pass: their verdict needs the log AND an effect, and it
      // is computed lazily, once per variant somebody actually holds — see
      // `Server.varyingPatches`.
      varyingIds: List[(NodeId, Option[String])],
      // Each affected group with the entities this frame moved inside it.
      dynamics: List[(NodeId, Option[String], List[String])],
      flips: List[(NodeId, Option[String])],
      changes: List[StateChange],
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      // When `states` was read (see [[Stamp]]), applied to every fragment and
      // mutation this request records. The version is read atomically WITH the
      // snapshot, so a fragment can never claim a version its HTML does not
      // reflect.
      stamp: Stamp
  )

  /** The snapshot as it was BEFORE this FRAME — the current snapshot with every
    * entity the frame moved rewound to its `previous` value (or dropped, when
    * it was newly seen). Lets a dynamic group compute its membership before vs.
    * after without the store tracking prior snapshots.
    *
    * All of them, not one: rewinding a single entity while its frame-mates hold
    * their new values describes an instant that never existed, and a condition
    * reading two of them would be asked about it.
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

  /** Select what one FRAME of changes touches for EVERY client on this
    * dashboard, against `states` — one pass, whose patches are then addressed
    * per client by their [[Addressed]] tag rather than re-selected per
    * connection.
    *
    * `visible` is the union of the connected clients' open surfaces. It is a
    * render GATE, not a correctness input: a surface nobody has open is not
    * worth rendering. Erring wide costs bytes here and nothing on the wire (the
    * tag still hides each patch from clients who cannot see it); erring narrow
    * would drop an update someone needed.
    *
    *   - '''Flips''': each state group whose selection this frame moves gets
    *     its HOST re-rendered ([[Renderer]]'s bake picks the newly-selected
    *     member against CURRENT state), morphed, and its members' cache entries
    *     pruned ([[flipStateGroup]]).
    *   - '''Active-member liveness''': each surface in the transitive active
    *     set — reachable from the main page or from a visible surface —
    *     contributes its components binding the changed entity plus its
    *     query-affected dynamics. Just-flipped subtrees are excluded (the host
    *     morph re-rendered them wholesale). Inactive members are never
    *     consulted: the hidden-branch no-updates guarantee, structural — their
    *     ids simply never enter the selection.
    *
    * Nothing here reads a client's `uiState`. The one thing that depends on it
    * — which member of a USER-selected mount a viewer chose — is not rendered
    * at all: the mount comes out empty and each connection fills its own from
    * the mount's own [[Mutation]], which each session fills for itself.
    */
  def plan(
      renderer: Renderer,
      states: Map[String, EntityState],
      stamp: Stamp,
      changes: List[StateChange],
      visible: Set[String]
  ): DiffRequest = {
    val before = beforeSnapshot(states, changes)
    val flips = (renderer.affectedStateGroups(changes, before, states) ++
      visible.toList.flatMap(sid =>
        renderer.affectedStateGroupsIn(sid, changes, before, states)
      )).distinct
    val flipped = flips.toSet
    val activeSids = renderer.activeStateSurfaces(states, flipped) ++
      visible.flatMap(renderer.activeStateSurfacesIn(_, states, flipped))
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
    // One entry per group, however many of the frame's entities moved inside
    // it: the membership question is asked once, at the frame boundary.
    val dynamics =
      (renderer.affectedDynamics(changes) ++
        sids.flatMap(renderer.affectedSurfaceDynamics(_, changes)))
        .groupMapReduce(_._1)(_._2)(_ ++ _)
        .toList
        .map { case (gid, touched) => (gid, touched.distinct) }
    request(
      renderer,
      staticIds,
      dynamics,
      flips,
      changes,
      states,
      before,
      stamp
    )
  }

  /** Tag each selected node with the innermost user surface containing it, and
    * bundle the request. The tag comes from the node's PLACE in the tree
    * ([[Renderer.userSurfaceOfNode]]) — not from its id, which encodes only its
    * own surface, and not from threading the originating surface down every
    * branch of the selection above, which goes wrong the moment the walk grows
    * a branch.
    */
  private def request(
      renderer: Renderer,
      staticIds: List[NodeId],
      dynamics: List[(NodeId, List[String])],
      flips: List[NodeId],
      changes: List[StateChange],
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      stamp: Stamp
  ): DiffRequest = {
    def tag(id: NodeId) = renderer.userSurfaceOfNode(id)
    val (varying, shared) = staticIds.partition(renderer.nodeVariesByViewer)
    DiffRequest(
      shared.map(id => id -> tag(id)),
      varying.map(id => id -> tag(id)),
      dynamics.map { case (gid, d) => (gid, tag(gid), d) },
      flips.map(gid => gid -> tag(gid)),
      changes,
      states,
      before,
      stamp
    )
  }

  /** Render a frame's affected nodes through the cache. See [[Renders]] for why
    * this is currently unwired.
    *
    * It mirrors what a frame needs rendered, from STATE alone. A group whose
    * membership did not move renders its touched children; one whose membership
    * moved renders either the arrivals or the whole mount, and the churn
    * heuristic that chooses between them is pure state
    * ([[Server.MaxChurnFraction]] over the member counts).
    */
  def prepare(
      renderer: Renderer,
      cache: RenderCache,
      req: DiffRequest
  ): IO[Renders] = {
    val states = req.states

    val membersNow = req.dynamics.map { case (gid, _, _) =>
      gid -> renderer.dynamicMembers(gid, states)
    }.toMap
    val membersWas = req.dynamics.map { case (gid, _, _) =>
      gid -> renderer.dynamicMembers(gid, req.before)
    }.toMap

    // A member of `membersNow` is exactly an entity [[Renderer.dynamicMembers]]
    // and [[Renderer.renderDynamicChild]] both accept — they test the same
    // query and the same cases over the same snapshot. Asking membership first
    // is what lets the render itself go through the cache, which returns bytes
    // rather than an Option.
    def child(gid: NodeId, entityId: String): IO[Option[NodeBytes]] =
      if (!membersNow(gid).contains(entityId)) IO.pure(None)
      else
        cache(
          renderer.dynamicChildId(gid, entityId),
          renderer,
          renderer.dynamicChildInputs(gid, entityId, states)
        )(mustRender(renderer.renderDynamicChild(gid, entityId, states), gid))
          .map(Some(_))

    for {
      nodes <- req.staticIds.traverse { case (id, _) =>
        node(renderer, cache, id, states).map(id -> _)
      }
      // A fill IS its members' renders in DOM order
      // ([[Renderer.renderDynamicMembers]] is defined that way), so assembling
      // it from the same cache entries a tick uses is byte-identical AND makes
      // a whole-mount fill reuse the per-entity renders it already paid for.
      prepared <- req.dynamics.traverse { case (gid, _, touched) =>
        val was = membersWas(gid)
        val now = membersNow(gid)
        def ticks(of: List[String]) =
          of.traverse(e => child(gid, e).map(h => (gid, e) -> h))
            .map(_ -> Option.empty[(NodeId, List[(NodeId, NodeBytes)])])

        if (was == now) ticks(touched) // the hot path: one tick per entity
        else {
          val added = now.filterNot(was.toSet)
          val churn = added.size + was.filterNot(now.toSet).size
          if (churn == 0)
            // The member LIST moved but the member SET did not, so `diff` sends
            // nothing. Rendering a fill here would be pure waste — and this
            // branch is where the wasteful one lives, so the guard has to be
            // here too, not only there.
            IO.pure(Nil -> None)
          else if (perEntityChurn(churn, was.size)) ticks(added)
          else
            now
              .traverse(e =>
                child(gid, e).map(_.map(renderer.dynamicChildId(gid, e) -> _))
              )
              .map(filled => Nil -> Some(gid -> filled.flatten))
        }
      }
    } yield new Renders(
      renderer,
      states,
      req.before,
      nodes.toMap,
      prepared.flatMap(_._1).toMap,
      prepared.flatMap(_._2).toMap,
      membersNow,
      membersWas
    )
  }

  private def node(
      renderer: Renderer,
      cache: RenderCache,
      id: NodeId,
      states: Map[String, EntityState]
  ): IO[Option[NodeBytes]] =
    renderer.renderInputs(id, states, Map.empty) match {
      // No sound key for this node — see `Renderer.renderInputs`. Rendered
      // uncached rather than cached wrongly.
      case None =>
        IO(renderer.renderNodeById(id, states).map(NodeBytes.of))
      case Some(inputs) =>
        cache(id, renderer, inputs)(
          mustRender(renderer.renderNodeById(id, states), id)
        ).map(Some(_))
    }

  /** A key exists only where a rendering does — `renderInputs` is `Some`
    * exactly when the node has one of its own, and a `dynamicMembers` member is
    * exactly what `renderDynamicChild` renders. Loud rather than caching an
    * empty string forever if those ever drift apart.
    */
  private def mustRender(html: Option[String], id: NodeId): String =
    html.getOrElse(
      throw new IllegalStateException(
        s"'$id' has a render key but no rendering"
      )
    )

  /** Per-entity pays off only when the churn is a MINORITY of the group: at the
    * boundary (e.g. 1 of 2 members, or the last member) a whole-group repaint
    * is cheaper than juggling insert/remove patches. Strict `<` so exactly half
    * repaints. `MaxChurnFraction` is tunable.
    *
    * Named because [[prepare]] and [[recordDynamic]] must agree on it: one
    * decides what to render, the other what to record.
    */
  private def perEntityChurn(churn: Int, shown: Int): Boolean =
    churn > 0 && churn < Server.MaxChurnFraction * shown

  def record(
      renderer: Renderer,
      log: FragmentLog,
      req: DiffRequest
  ): FragmentLog = {
    val at = req.stamp
    // Flips first: their prune must precede anything that could be suppressed
    // against a pre-flip entry.
    val afterFlips = req.flips.foldLeft(log) { case (l, (gid, _)) =>
      recordFlip(renderer, l, gid, req.before, req.states, at)
    }
    // A varying node is no longer a kind of its own here: its version moves like
    // any other, and the per-viewer render happens where the viewer is.
    val afterNodes = (req.staticIds ++ req.varyingIds).foldLeft(afterFlips) {
      case (l, (id, _)) => l.touched(id, at.version)
    }
    req.dynamics.foldLeft(afterNodes) { case (l, (gid, _, touched)) =>
      recordDynamic(renderer, l, gid, touched, req.before, req.states, at)
    }
  }

  /** [[flipStateGroup]] with the render taken out: evict the departing branch's
    * entries and record WHERE the branch went. [[resume]]'s branch fill is the
    * other half.
    */
  private def recordFlip(
      renderer: Renderer,
      log: FragmentLog,
      gid: NodeId,
      before: Map[String, EntityState],
      states: Map[String, EntityState],
      at: Stamp
  ): FragmentLog = {
    def memberAt(snapshot: Map[String, EntityState]): Option[String] =
      renderer
        .resolveActiveByState(gid, snapshot)
        .flatMap(renderer.bakeMembers(gid).lift)
    val was = memberAt(before)
    val now = memberAt(states)
    if (was == now) log
    else {
      // The departing branch's nodes are not merely stale, they are unmounted:
      // a morph at one would land nowhere, and the changelog must stop naming
      // them.
      val evicted =
        log.invalidateWhere(hostEvicts(renderer, renderer.mountId(gid)))
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

  /** The membership question, asked ONCE per frame — per entity it could not be
    * answered, since two entities can cross the query boundary in opposite
    * directions in one tick, and each single-entity view of that reports a
    * change the frame did not make.
    *
    * Two conditions still choose between a per-member delta and a whole-mount
    * fill, and they come from different places: `perEntityChurn` is pure state,
    * where `hasChildOf` asks whether the log holds children to patch AGAINST —
    * false after a renderer swap or a fill, and a delta then patches against a
    * baseline nobody can vouch for.
    */
  private def recordDynamic(
      renderer: Renderer,
      log: FragmentLog,
      gid: NodeId,
      touched: List[String],
      before: Map[String, EntityState],
      states: Map[String, EntityState],
      at: Stamp
  ): FragmentLog = {
    val was = renderer.dynamicMembers(gid, before)
    val now = renderer.dynamicMembers(gid, states)
    if (was == now)
      touched
        .filter(now.contains)
        .foldLeft(log)((l, e) =>
          l.touched(renderer.dynamicChildId(gid, e), at.version)
        )
    else {
      val nowSet = now.toSet
      val added = now.filterNot(was.toSet)
      val removed = was.filterNot(nowSet)
      val churn = added.size + removed.size
      // The query boundary moved but the RENDERED membership did not.
      if (churn == 0) log
      else if (!perEntityChurn(churn, was.size) || !log.hasChildOf(gid))
        // Touched as well as filled: the fill re-supplies the mount, and the
        // entries it leaves are what make the group ESTABLISHED for the next
        // membership change. Without them every change fills, and every fill
        // raises the horizon past another cursor.
        now.foldLeft(log.filled(gid, at.version))((l, e) =>
          l.touched(renderer.dynamicChildId(gid, e), at.version)
        )
      else {
        val afterRemoves = removed.foldLeft(log)((l, e) =>
          l.removed(gid, renderer.dynamicChildId(gid, e), at)
        )
        added.sorted.foldLeft(afterRemoves) { (l, e) =>
          val cid = renderer.dynamicChildId(gid, e)
          // Touched as well as placed: the mutation is what a resume replays,
          // but the fragment entry is what keeps the group ESTABLISHED for the
          // next membership change. `since` reports a resupplied node once, so
          // this adds no patch.
          l.placed(gid, MemberKey.Entity(e), cid, at).touched(cid, at.version)
        }
      }
    }
  }

  /** Everything a client resuming at cursor `v` is owed, as patches carrying
    * what their bytes establish. The pure core of the resume path (ADR 0011):
    * the caller reads the log + snapshot, records the [[Addressed.establishes]]
    * into that session's `holds`, and writes the stream; the ordering argument
    * lives here.
    *
    * Untagged — every patch here was already decided against THIS client's
    * `open` and `holds`, so there is nobody left to hide it from.
    *
    * A fill establishes NOTHING and invalidates its mount: composed bytes have
    * no per-node trace here, so the honest record is "these nodes are unknown
    * again", which costs redundant patches and never staleness.
    *
    * '''ONE rule, one candidate set, one snapshot:'''
    *
    * > Candidates = nodes whose logged version is `>= v`, plus every node in an
    * > OPEN surface. Render each from the current snapshot, and send it when
    * its > fingerprint differs from what this viewer holds — a MISSING entry >
    * counting as "send".
    *
    * The two candidate sets are two different ignorances. `version >= v` means
    * the node changed at or after the cursor, so the client may never have
    * applied it. An open surface is the UNTRACKED case: nothing rendered it
    * while nobody was viewing it, so only re-rendering can tell whether the
    * client's DOM is current. Both then ask the same question of the same
    * record, which is what makes this the live path as well as the resume one —
    * a session pulling one frame is a resume from `position + 1`.
    *
    * One mechanism covers what would otherwise be two special cases. A
    * per-session fragment and an open popup are both just candidates here: the
    * popup's nodes are in `open`, and a container's `self` does not contain its
    * mount, so a client returning after a long absence gets the bar's new HTML
    * and keeps its panel — no restore branch of its own.
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
      cache: RenderCache,
      log: FragmentLog,
      holds: Map[NodeId, Digest],
      states: Map[String, EntityState],
      v: Long,
      open: Set[String] = Set.empty,
      uiState: Map[String, String] = Map.empty
  ): IO[List[Addressed]] = {
    val all = log.since(v)
    // Only what this client can SEE. A mutation inside a surface it does not
    // have open would patch an id its DOM lacks — a silent no-op, so this only
    // ever costs bytes, but it is one client's worth of another client's tab on
    // every frame. This is where the audience tag used to do its work, asked
    // now of the container itself.
    val owed = all.copy(
      moved = all.moved.filter { case (_, m) =>
        renderer.visibleNode(m.container, open, states)
      },
      refill = all.refill.filter(renderer.visibleNode(_, open, states))
    )
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
          renderer
            .renderMount(gid, states, uiState)
            .map(_._2)
            .reduceOption(_ + _),
          entries.map(_._1).sorted.headOption
        ).map(
          // A branch's content ids are `s_<surface>__…`, which no prefix of the
          // container's id reaches — so the mount says which nodes it holds.
          Addressed(
            _,
            invalidates = hostEvicts(renderer, renderer.mountId(gid))
          )
        )
      }
    val places = dynamic
      .collect { case (nodeId, p: Mutation.Placed) => (nodeId, p) }
      .groupBy { case (_, p) => p.container }
      .toList
      .sortBy(_._1)
      .flatTraverse { case (gid, inGroup) =>
        val members = renderer.dynamicMembers(gid, states)
        val position = members.zipWithIndex.toMap
        inGroup
          // Still a member; anything an ancestor is re-supplying was already
          // dropped by `since`.
          .flatMap { case (nodeId, p) =>
            p.member match {
              case MemberKey.Entity(e)  => position.get(e).map((nodeId, e, _))
              case _: MemberKey.Surface => None
            }
          }
          .sortBy { case (_, _, at) => -at }
          .flatTraverse { case (nodeId, entityId, _) =>
            // Rendered NOW, not read back: the snapshot is at least as fresh as
            // anything the log could have kept, and it is what lets the log hold
            // a version instead of bytes.
            bytes(renderer, cache, nodeId, states, uiState).map(
              _.toList.flatMap { case NodeBytes(html, digest) =>
                // Every current member is a usable anchor here: emitting
                // descending by position means a node's successor was either
                // already in the client's DOM or placed a moment ago.
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
                    Map(nodeId -> digest)
                  )
                )
              }
            )
          }
      }
    // Containers whose membership history no longer reaches this cursor: the
    // delta is uncomputable, so the mount is filled wholesale. `Inner` is
    // all-or-nothing over a mount's children, so this cannot be partial — which
    // is precisely why it is the fallback of last resort, and why it is worth
    // having only because it replaced a whole-BODY repaint.
    val refills = owed.refill.sorted.map { gid =>
      val members = renderer.renderMount(gid, states, uiState)
      Addressed(
        Patch.Insert(
          members.map(_._2).mkString,
          PatchMode.Inner,
          renderer.mountId(gid)
        ),
        // A DYNAMIC mount's contents are one resolvable node per member, so the
        // fill can say what it put in each and the next tick can tell
        // "unchanged" from "never told". A state group's is one composed
        // subtree under a root with no rendering of its own — a digest there
        // could never be resolved, so it claims nothing and pays a redundant
        // patch instead.
        if (renderer.isDynamicContainer(gid))
          members.map { case (id, html) => id -> Digest.of(html) }.toMap
        else Map.empty,
        if (renderer.isDynamicContainer(gid)) Set(gid)
        else hostEvicts(renderer, renderer.mountId(gid))
      )
    }
    // The second candidate set: an open surface's nodes, which the cursor alone
    // would not name. Sorted for a deterministic order (ids are location-derived,
    // so this is document order among siblings), and dropped when a mutation or a
    // refill is already re-supplying an ancestor.
    val fromOpenIds = open.toList
      // Only what this client can actually SEE. `open` reports a selection for
      // every bake group whether or not that group is on screen, so a tab panel
      // inside a hidden `If` branch is in here and in nobody's DOM.
      .filter(renderer.visibleSurface(_, open, states))
      .flatMap(renderer.surfaceNodeIds)
      .distinct
      .filterNot(id =>
        owed.nodes.contains(id) || owed.moved.exists(_._1 == id) ||
          log.coveredByMutation(id, owed.moved.map(_._1).toSet ++ owed.refill)
      )
      .sorted
    val changed = owed.nodes
      // The cursor names every node that changed, across every surface — it
      // knows nothing about who is looking. A morph at an id this client's DOM
      // lacks is a silent no-op, so this only ever cost bytes; it is still one
      // client's worth of another client's tab on every reconnect.
      .filter(renderer.visibleNode(_, open, states))
      // The log is a Map, so its order is nobody's; ids are location-derived,
      // so sorting them is document order among siblings.
      .sorted
    for {
      morphs <- changed.traverseFilter(
        morph(renderer, cache, holds, states, uiState, _)
      )
      open <- fromOpenIds.traverseFilter(
        morph(renderer, cache, holds, states, uiState, _)
      )
      placed <- places
    } yield morphs ++ open ++
      gone.toList.sorted.map(id =>
        Addressed(Patch.Remove(renderer.elementId(id)))
      ) ++
      branchFills ++ placed ++ refills
  }

  /** Render one node and send it only if it is not what this viewer already
    * holds — the whole suppression rule, in the one place both candidate sets
    * go through.
    *
    * Compared against what THIS viewer holds: another viewer's digest says
    * nothing about this DOM, which is why `holds` arrives already narrowed to
    * one client. A MISSING entry counts as "send" — unknown, so tell the
    * client.
    */
  private def morph(
      renderer: Renderer,
      cache: RenderCache,
      holds: Map[NodeId, Digest],
      states: Map[String, EntityState],
      uiState: Map[String, String],
      id: NodeId
  ): IO[Option[Addressed]] =
    bytes(renderer, cache, id, states, uiState).map(_.flatMap {
      case NodeBytes(html, digest) =>
        Option.when(!holds.get(id).contains(digest))(
          Addressed(Patch.Morph(html), Map(id -> digest))
        )
    })

  /** One node's bytes for THIS viewer, through the slug's [[RenderCache]] —
    * which is what keeps N sessions woken by one doorbell from rendering the
    * same node N times.
    *
    * Three cases, and the fallthrough is the important one: a node with a sound
    * key ([[Renderer.renderInputs]]) goes through the cache; a dynamic group's
    * member is keyed by [[Renderer.dynamicChildInputs]], since its id is
    * derived per entity rather than indexed; anything else — a container whose
    * own bytes carry its children — has no sound key and is rendered UNCACHED
    * rather than cached wrongly.
    */
  private def bytes(
      renderer: Renderer,
      cache: RenderCache,
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): IO[Option[NodeBytes]] =
    renderer.renderInputs(id, states, uiState) match {
      case Some(inputs) =>
        cache(id, renderer, inputs)(
          mustRender(renderer.renderNodeById(id, states, uiState), id)
        ).map(Some(_))
      case None =>
        renderer.dynamicOwnerOf(id, states) match {
          case Some((gid, entityId)) =>
            cache(
              id,
              renderer,
              renderer.dynamicChildInputs(gid, entityId, states)
            )(
              mustRender(renderer.renderDynamicChild(gid, entityId, states), id)
            ).map(Some(_))
          case None =>
            IO(renderer.renderLogged(id, states, uiState).map(NodeBytes.of))
        }
    }

  /** ONE anchor rule for both the live add path and the resume replay, because
    * an insert is the same problem in both: name a sibling that is really
    * there. What differs is only which siblings qualify, which is `anchorable`.
    *
    * It reads the order out of `ordered` — the list [[Renderer.dynamicMembers]]
    * produced — rather than comparing entity ids. That is what keeps this
    * correct if member order ever becomes author-chosen: comparing ids directly
    * silently requires id-sorted membership, and disagrees with the resume
    * path, which does it positionally.
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

  /** Fill `host` with `arriving`'s rendering, as a patch that knows what it
    * placed. THE fill primitive for a client's own selection: a tab switch and
    * a popup open are the same operation, differing only in who chose.
    *
    * It touches no shared structure, and that is the change of ownership the
    * pull model makes: one client switching a tab says nothing about anyone
    * else's DOM, so the eviction and the trace both belong to that session's
    * `holds` — via [[applied]], exactly like any other patch it is sent.
    *
    * Eviction is in `invalidates` rather than done first-and-separately: the
    * departing member's DOM is gone, so its claims describe nothing, and a
    * stale one would suppress a real change on the way back.
    *
    * A state-group FLIP is the other caller of the same idea and does not come
    * through here: it is server truth for every viewer, so it is recorded as a
    * [[Mutation]] ([[recordFlip]]) and each session fills for itself.
    */
  private[runtime] def hostFill(
      renderer: Renderer,
      host: DomId,
      arriving: Option[String],
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): Option[(Addressed, String)] =
    arriving
      .flatMap(renderer.renderSurfaceTraced(_, states, uiState))
      .map { t =>
        (
          Addressed(
            Patch.Insert(t.html, PatchMode.Inner, host),
            t.own.map { case (id, html) => id -> Digest.of(html) },
            (renderer.surfacesAt(host) ++ arriving)
              .flatMap(renderer.surfaceNodeIds)
          ),
          t.html
        )
      }

  /** What a host swap makes UNKNOWN when nothing arrives — a popup closing, a
    * flip whose condition now matches no branch. Same set [[hostFill]] carries
    * in its `invalidates`, without the bytes.
    */
  private[runtime] def hostEvicts(
      renderer: Renderer,
      host: DomId
  ): Set[NodeId] =
    renderer.surfacesAt(host).flatMap(renderer.surfaceNodeIds)

  /** Apply what a patch did to one client's record: forget the mounts it
    * re-supplied, then claim what its bytes placed.
    *
    * That ORDER, because a fill both re-supplies a mount and places members
    * inside it — invalidating afterwards would drop the very claims the same
    * patch just earned.
    *
    * Prefix semantics on the roots, the same string test ancestry uses
    * everywhere here ([[FragmentLog.coveredByMutation]]): ids are
    * location-derived, and the trailing `_` keeps `c_1` from swallowing `c_10`.
    * A root itself goes too — it is inside the DOM the fill replaced.
    */
  def applied(
      holds: Map[NodeId, Digest],
      patch: Addressed
  ): Map[NodeId, Digest] =
    (if (patch.invalidates.isEmpty) holds
     else
       holds.filterNot { case (id, _) =>
         patch.invalidates.exists(r => id == r || id.startsWith(r + "_"))
       }) ++ patch.establishes

  /** Combine adjacent morphs, then put them on the wire.
    *
    * Merging is still a property of ONE client's outgoing stream — it just no
    * longer needs saying, because the list already is one client's. What
    * survives is the barrier rule: an [[Patch.Insert]]/[[Patch.Remove]] names
    * its own target and cannot join, and a morph after an insert may target the
    * element that insert created, so nothing may be reordered across one.
    */
  def encode(patches: List[Addressed]): List[ServerSentEvent] =
    patches
      .map(_.patch)
      .foldLeft(List.empty[Patch]) {
        case (Patch.Morph(before) :: rest, Patch.Morph(next)) =>
          Patch.Morph(before + next) :: rest
        case (acc, one) => one :: acc
      }
      .reverse
      .map(_.toSse)

  /** Put `content` in a STATE group's mount — one patch, whatever the client's
    * DOM currently holds there.
    *
    * The mount takes at most one member, so `Inner` is both the delta (no
    * siblings exist to preserve) and idempotent (it lands the same on the old
    * branch, the new one, or an empty host). `departed` is only consulted when
    * nothing holds now: an `Inner` of empty content is not a well-formed patch,
    * so the emptying case stays a `remove` of the branch that left.
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

}
