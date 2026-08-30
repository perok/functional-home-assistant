package fh.view.runtime

import cats.effect.IO
import cats.syntax.traverse.*
import cats.syntax.traverseFilter.*
import fh.view.model.{DomId, NodeId, SetId, SignalId}
import fh.view.model.DomId.selector
import io.circe.Json
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
  *   - [[Signals]]: set signal-slot values, touching no element at all — the
  *     whole point of ADR 0017. It is the one variant with no target, because
  *     the elements bound to those signals update themselves.
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
  case Signals(values: Map[SignalId, Json])

  def toSse: ServerSentEvent = this match
    case Patch.Morph(html)                => Datastar.patchElements(html)
    case Patch.Insert(html, mode, target) =>
      Datastar.patch(html, mode, Some(target.selector))
    case Patch.Remove(target)  => Datastar.remove(target.selector)
    case Patch.Signals(values) =>
      Datastar.patchSignals(Datastar.signalsJson(values))

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
  * `invalidates` names bake HOSTS this patch RE-SUPPLIED. An ordinary morph can
  * never touch a child — only a LEAF is a patch target, and a leaf holds no
  * regions — so this is about the one patch that aims AT a host: an `Inner`
  * fill is all-or-nothing over its children by design.
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
    establishes: Map[NodeId, Held] = Map.empty,
    // Roots: a host and everything under it ([[NodeAncestry]]).
    invalidates: Set[NodeId] = Set.empty
)

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
    * Bundles the assembled `staticIds`/`sets`/`flips` with the render inputs
    * (`change`/`states`/`before`) they are diffed with, rather than nine
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
      // The affected groups. No entity list any more: a member that merely
      // TICKED is selected by the reverse index like any other node, so what is
      // left here is the membership question alone.
      sets: List[(SetId, Option[String])],
      flips: List[(NodeId, Option[String])],
      changes: List[StateChange],
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      // What this frame did to each candidate set's membership, as
      // `MemberGraph.syncMembers` applied it to the graph. Carried rather than
      // re-derived because it IS the delta: recomputing it would ask the same
      // question a second time, of a graph that has already moved.
      membership: Map[SetId, MemberDelta],
      // The store version `states` was read at, applied to every fragment and
      // mutation this request records. Read atomically WITH the snapshot, so a
      // fragment can never claim a version its HTML does not reflect.
      at: Long
  )

  /** The snapshot as it was BEFORE this FRAME — the current snapshot with every
    * entity the frame moved rewound to its `previous` value (or dropped, when
    * it was newly seen). Lets a candidate set compute its membership before vs.
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
    *     affected candidate sets. Just-flipped subtrees are excluded (the host
    *     morph re-rendered them wholesale). Inactive members are never
    *     consulted: the hidden-branch no-updates guarantee, structural — their
    *     ids simply never enter the selection.
    *
    * Nothing here reads a client's `uiState`. The one thing that depends on it
    * — which member of a USER-selected host a viewer chose — is not rendered at
    * all: the host comes out empty and each connection fills its own from the
    * host's own [[Mutation]], which each session fills for itself.
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
    // One entry per group, however many of the frame's entities moved inside
    // it: the membership question is asked once, at the frame boundary.
    val sets =
      (renderer.members.affectedSets(changes) ++
        sids.flatMap(renderer.members.affectedSurfaceSets(_, changes))).distinct
    request(
      renderer,
      staticIds,
      sets,
      flips,
      changes,
      states,
      before,
      membership,
      at
    )
  }

  /** Tag each selected node with the innermost user surface containing it, and
    * bundle the request. The tag comes from the node's PLACE in the tree
    * ([[SurfaceGraph.userSurfaceOfNode]]) — not from its id, which encodes only
    * its own surface, and not from threading the originating surface down every
    * branch of the selection above, which goes wrong the moment the walk grows
    * a branch.
    */
  private def request(
      renderer: Renderer,
      staticIds: List[NodeId],
      sets: List[SetId],
      flips: List[NodeId],
      changes: List[StateChange],
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      membership: Map[SetId, MemberDelta],
      at: Long
  ): DiffRequest = {
    def tag(id: NodeId) = renderer.surfaces.userSurfaceOfNode(id)
    DiffRequest(
      staticIds.map(id => id -> tag(id)),
      sets.map(gid => gid -> tag(gid)),
      flips.map(gid => gid -> tag(gid)),
      changes,
      states,
      before,
      membership,
      at
    )
  }

  /** A key exists only where a rendering does — `renderInputs` is `Some`
    * exactly when the node has one of its own, and a `memberEntities` member is
    * exactly what `renderMemberById` renders. Loud rather than caching an empty
    * string forever if those ever drift apart.
    */
  private def mustRender(bytes: Option[NodeBytes], id: NodeId): NodeBytes =
    bytes.getOrElse(
      throw new IllegalStateException(
        s"'$id' has a render key but no rendering"
      )
    )

  /** Which of these survivors have to be MOVED to turn `before` into `after` —
    * the fewest possible, in `after` order.
    *
    * Both lists hold the same members, so the answer is everything outside a
    * longest increasing subsequence of their old positions: that subsequence is
    * the largest set that is already in the right relative order, and each
    * element outside it costs a remove/insert pair. Minimising it is not
    * fussiness — a set ordered on a live value reorders whenever any two
    * members cross, and moving every element on each crossing is the patch
    * storm P7 exists to prevent.
    *
    * O(n²) over one container's members, deliberately: n is a room's worth of
    * lights, and the quadratic version is the one a reader can check.
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
    // Flips first: their prune must precede anything that could be suppressed
    // against a pre-flip entry.
    val afterFlips = req.flips.foldLeft(log) { case (l, (gid, _)) =>
      recordFlip(renderer, l, gid, req.before, req.states, at)
    }
    val afterNodes = req.staticIds.foldLeft(afterFlips) { case (l, (id, _)) =>
      l.touched(id, at)
    }
    req.sets.foldLeft(afterNodes) { case (l, (gid, _)) =>
      req.membership
        .get(gid)
        .fold(l)(recordSet(renderer, l, gid, _, at))
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
      // The departing branch's nodes are not merely stale, they are GONE from
      // the DOM:
      // a morph at one would land nowhere, and the changelog must stop naming
      // them.
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

  /** The membership question, answered ONCE per frame — per entity it could not
    * be, since two entities can cross the query boundary in opposite directions
    * in one tick, and each single-entity view of that reports a change the
    * frame did not make. `was`/`now` arrive from the graph
    * ([[MemberGraph.syncMembers]]), which applied that frame.
    *
    * '''Deltas by default; a fill only where it costs nothing or is the only
    * option.''' A fill re-renders the WHOLE host, so it re-sends the members
    * that did not change — and it raises the host's horizon, which drops every
    * client below that cursor onto the same wholesale path. It is worth it in
    * exactly two places:
    *
    *   - the unchanged set is EMPTY (`was` or `now` is), so there is nothing to
    *     re-send: everything arrived, or everything left. One patch instead of
    *     N, identical bytes.
    *   - `holdsAnyOf` is false — the log knows none of the members to patch
    *     after a renderer swap or an earlier fill — and a delta would be
    *     patching a baseline nobody can vouch for. Correctness, not cost.
    *
    * This replaced a churn FRACTION (fill past half the group), which turned
    * out to be backwards for ordinary frames: at its own motivating boundary —
    * removing 1 of 2 members — the delta is a single `remove` carrying no HTML
    * at all, where the fill re-renders the survivor for nothing. The case it
    * genuinely won, near-total churn of many tiny members, is narrow enough to
    * pay for out of simplicity.
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
    // A member whose case moved: still a member, so no structural mutation
    // describes it, and its bytes changed. Named by id rather than found
    // through the reverse index, which a case binding no live entity would
    // leave empty.
    val base = delta.replaced.toList.sorted.foldLeft(log)(_.touched(_, at))
    if (was == now) base
    else {
      val nowSet = now.toSet
      val wasSet = was.toSet
      val added = now.filterNot(wasSet)
      val removed = was.filterNot(nowSet)
      // Members that survived the frame but changed PLACE. Only a set ordered by
      // a LIVE value can produce these — authored candidate order cannot move —
      // and they are a real DOM change, so "the set did not change" is not the
      // same question as "nothing moved".
      val moved = Patches.reordered(was.filter(nowSet), now.filter(wasSet))
      val churn = added.size + removed.size + moved.size
      // The query boundary moved but the RENDERED membership did not.
      if (churn == 0) base
      else if (
        was.isEmpty || now.isEmpty || !base.holdsAnyOf(
          was.map(renderer.members.memberIdOf(gid, _))
        )
      )
        // Touched as well as filled: the fill re-supplies the host, and the
        // entries it leaves are what make the group ESTABLISHED for the next
        // membership change. Without them every change fills, and every fill
        // raises the horizon past another cursor.
        now.foldLeft(base.filled(gid, at, renderer.ancestry))((l, e) =>
          l.touched(renderer.members.memberIdOf(gid, e), at)
        )
      else {
        // A move is a departure and an arrival at the new place — the same
        // idempotent pair an arrival always is, which is why a reorder needs no
        // patch kind of its own.
        val afterRemoves = (removed ++ moved).foldLeft(base)((l, e) =>
          l.removed(gid, renderer.members.memberIdOf(gid, e), at)
        )
        // Placed from the BACK of the new order forwards, so each one's anchor
        // — its successor — is already in the DOM: either it never moved, or
        // this loop put it there. Forwards, two adjacent arrivals would have
        // the first anchor on an element that does not exist yet, and an
        // insert-before a missing selector is silently dropped.
        val place = (added ++ moved).sortBy(now.indexOf).reverse
        place.foldLeft(afterRemoves) { (l, e) =>
          val cid = renderer.members.memberIdOf(gid, e)
          // Touched as well as placed: the mutation is what a resume replays,
          // but the fragment entry is what keeps the group ESTABLISHED for the
          // next membership change. `since` reports a resupplied node once, so
          // this adds no patch.
          l.placed(gid, MemberKey.Entity(e), cid, at).touched(cid, at)
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
    * A fill establishes NOTHING and invalidates its host: composed bytes have
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
    * popup's nodes are in `open`, and a tab bar is a NODE beside the panel
    * rather than markup wrapped around it, so a client returning after a long
    * absence gets the bar's new HTML and keeps its panel — no restore branch of
    * its own.
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
    * order over members, which [[MemberGraph.memberEntities]] provides; nothing
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
      holds: Map[NodeId, Held],
      states: Map[String, EntityState],
      v: Long,
      open: Set[String] = Set.empty,
      uiState: Map[String, String] = Map.empty
  ): IO[List[Addressed]] = {
    val all = log.since(v, renderer.ancestry)
    // Only what this client can SEE. A mutation inside a surface it does not
    // have open would patch an id its DOM lacks — a silent no-op, so this only
    // ever costs bytes, but it is one client's worth of another client's tab on
    // every frame. This is where the audience tag used to do its work, asked
    // now of the container itself.
    val owed = all.copy(
      moved = all.moved.filter { case (_, m) =>
        renderer.surfaces.visibleNode(m.container, open, states)
      },
      refill = all.refill.filter(renderer.surfaces.visibleNode(_, open, states))
    )
    // Split by CONTAINER KIND, because the two want different tools: a
    // candidate set needs per-member deltas that preserve siblings, a state
    // group's host holds one member and is simply overwritten.
    val (memberMoves, branch) = owed.moved.partition { case (_, m) =>
      renderer.members.setContainer(m.container).isDefined
    }
    val gone = memberMoves.collect { case (nodeId, _: Mutation.Gone) => nodeId }
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
            .renderHost(gid, states, uiState)
            .map(_._2.html)
            .reduceOption(_ + _),
          entries.map(_._1).sorted.headOption
        ).map(
          // A branch's content ids are `s_<surface>__…`, which no prefix of the
          // container's id reaches — so the host says which nodes it holds.
          Addressed(
            _,
            invalidates = hostEvicts(renderer, renderer.hostId(gid))
          )
        )
      }
    val places = memberMoves
      .collect { case (nodeId, p: Mutation.Placed) => (nodeId, p) }
      .groupBy { case (_, p) => p.container }
      .toList
      .sortBy(_._1)
      .flatTraverse { case (container, moves) =>
        // `memberMoves` is exactly the moves whose container the graph knows,
        // so this always answers — and it is the one place a log key becomes a
        // set id.
        renderer.members.setContainer(container).toList.flatTraverse { gid =>
          val members = renderer.members.memberEntities(gid, states)
          val position = members.zipWithIndex.toMap
          moves
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
                      Map(nodeId -> Held.bytes(digest))
                    )
                  )
                }
              )
            }
        }
      }
    // Containers whose membership history no longer reaches this cursor: the
    // delta is uncomputable, so the host is filled wholesale. `Inner` is
    // all-or-nothing over a host's children, so this cannot be partial — which
    // is precisely why it is the fallback of last resort, and why it is worth
    // having only because it replaced a whole-BODY repaint.
    val refills = owed.refill.sorted.map { gid =>
      val asSet = renderer.members.setContainer(gid)
      val members = renderer.renderHost(gid, states, uiState)
      Addressed(
        Patch.Insert(
          members.map(_._2.html).mkString,
          PatchMode.Inner,
          renderer.hostId(gid)
        ),
        // A SET host's contents are one resolvable node per member, so the
        // fill can say what it put in each and the next tick can tell
        // "unchanged" from "never told". The digest is the member's INPUT
        // digest (ADR 0029) — the same one the document walk recorded, so a
        // member whose inputs did not move is suppressed. A state group's is
        // one composed subtree under a root with no rendering of its own — a
        // digest there could never be resolved, so it claims nothing and pays
        // a redundant patch instead.
        if (asSet.isDefined)
          members.map { case (id, nb) => id -> Held(Some(nb.digest)) }.toMap
        else Map.empty,
        if (asSet.isDefined) Set(gid)
        else hostEvicts(renderer, renderer.hostId(gid))
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
      // The cursor names every node that changed, across every surface — it
      // knows nothing about who is looking. A morph at an id this client's DOM
      // lacks is a silent no-op, so this only ever cost bytes; it is still one
      // client's worth of another client's tab on every reconnect.
      .filter(renderer.surfaces.visibleNode(_, open, states))
      // The log is a Map, so its order is nobody's; ids are location-derived,
      // so sorting them is document order among siblings.
      .sorted
    // Every node whose patch-form bytes this batch decides about — sent OR
    // suppressed. Collected from the CANDIDATES rather than from the patches,
    // because a node whose only movement was a signal slot emits no patch at
    // all: that silence is the feature, not an omission.
    val touchedIds =
      (changed ++ fromOpenIds ++ memberMoves.collect {
        case (nodeId, _: Mutation.Placed) => nodeId
      }).distinct
    for {
      morphs <- changed.traverseFilter(
        morph(renderer, cache, holds, states, uiState, _)
      )
      open <- fromOpenIds.traverseFilter(
        morph(renderer, cache, holds, states, uiState, _)
      )
      placed <- places
    } yield signalFrame(renderer, holds, states, touchedIds) ++
      morphs ++ open ++
      gone.toList.sorted.map(id =>
        Addressed(Patch.Remove(renderer.elementId(id)))
      ) ++
      branchFills ++ placed ++ refills
  }

  /** The one `datastar-patch-signals` frame a batch carries, or nothing (ADR
    * 0017).
    *
    * It goes FIRST, which costs nothing and reads right: a signal set before
    * the element binding it is simply the value that element paints with when
    * it arrives, and Datastar re-evaluates a binding on morph either way. That
    * ordering is what makes a member INSERT correct — its bytes are patch-form
    * and carry no seed, so the frame is the only thing that gives it a value.
    *
    * Diffed against what this viewer holds, for the same reason the bytes are:
    * a node is a candidate because an entity it binds moved, which is not the
    * same as its signal slots having moved. A frame for a node whose signals
    * stood still is pure waste on the wire.
    */
  private def signalFrame(
      renderer: Renderer,
      holds: Map[NodeId, Held],
      states: Map[String, EntityState],
      ids: List[NodeId]
  ): List[Addressed] = {
    val moved = ids.flatMap { id =>
      val held = holds.get(id).fold(Map.empty[SignalId, String])(_.signals)
      renderer
        .signalsFor(id, states)
        .filterNot { case (name, value) => held.get(name).contains(value) }
        // `.toList` FIRST, and it is load-bearing: mapping a Map to pairs
        // rebuilds a Map, and every pair here shares the node id — so all but
        // one of a node's signals was silently dropped. Invisible on a card
        // with one signal slot, fatal on the slider's four.
        .toList
        .map(id -> _)
    }
    Option
      .when(moved.nonEmpty)(
        Addressed(
          Patch.Signals(
            moved.map { case (_, (name, value)) =>
              name -> io.circe.Json.fromString(value)
            }.toMap
          ),
          moved.groupMap(_._1)(_._2).map { case (id, kvs) =>
            id -> Held(signals = kvs.toMap)
          }
        )
      )
      .toList
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
      holds: Map[NodeId, Held],
      states: Map[String, EntityState],
      uiState: Map[String, String],
      id: NodeId
  ): IO[Option[Addressed]] =
    bytes(renderer, cache, id, states, uiState).map(_.flatMap {
      case NodeBytes(html, digest) =>
        Option.when(!holds.get(id).flatMap(_.digest).contains(digest))(
          Addressed(Patch.Morph(html), Map(id -> Held.bytes(digest)))
        )
    })

  /** One node's bytes for THIS viewer, through the slug's [[RenderCache]] —
    * which is what keeps N sessions woken by one doorbell from rendering the
    * same node N times.
    *
    * Two cases: a node with a sound key ([[Renderer.renderInputs]]) goes
    * through the cache, and anything else — a container whose own bytes carry
    * its children — is rendered UNCACHED rather than cached wrongly. A set
    * member needed a third until it became a node in the graph; it is keyed and
    * rendered by id like everything else now.
    */
  private def bytes(
      renderer: Renderer,
      cache: RenderCache,
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): IO[Option[NodeBytes]] =
    renderer.renderInputs(id, states) match {
      case Some(inputs) =>
        cache(id, renderer, inputs)(
          IO(mustRender(renderer.renderNodeById(id, states, uiState), id))
        ).map(Some(_))
      case None =>
        IO(renderer.renderNodeById(id, states, uiState))
    }

  /** ONE anchor rule for both the live add path and the resume replay, because
    * an insert is the same problem in both: name a sibling that is really
    * there. What differs is only which siblings qualify, which is `anchorable`.
    *
    * It reads the order out of `ordered` — the list
    * [[MemberGraph.memberEntities]] produced — rather than comparing entity
    * ids. That is what keeps this correct if member order ever becomes
    * author-chosen: comparing ids directly silently requires id-sorted
    * membership, and disagrees with the resume path, which does it
    * positionally.
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
            t.own.map { case (id, p) =>
              id -> Held(Some(p.digest), p.signals)
            },
            (renderer.surfaces.surfacesAt(host) ++ arriving)
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
    renderer.surfaces.surfacesAt(host).flatMap(renderer.surfaceNodeIds)

  /** Apply what a patch did to one client's record: forget the hosts it
    * re-supplied, then claim what its bytes placed.
    *
    * That ORDER, because a fill both re-supplies a host and places members
    * inside it — invalidating afterwards would drop the very claims the same
    * patch just earned.
    *
    * Containment comes from [[NodeAncestry]], the same relation ancestry uses
    * everywhere here — not from how the ids are spelled, which stopped being
    * safe once an author could name a node. A root itself goes too: it is
    * inside the DOM the fill replaced.
    */
  def applied(
      ancestry: NodeAncestry,
      holds: Map[NodeId, Held],
      patch: Addressed
  ): Map[NodeId, Held] =
    // MERGED per node, not replaced: a patch-form morph establishes bytes and
    // says nothing about the signals bound inside them, and a signals frame is
    // the mirror image. Overwriting either way would forget the half this patch
    // was silent about (see [[Held.merge]]).
    patch.establishes.foldLeft(
      if (patch.invalidates.isEmpty) holds
      else
        holds.filterNot { case (id, _) =>
          ancestry.withinAny(id, patch.invalidates.toSet)
        }
    ) { case (acc, (id, later)) =>
      acc.updated(id, acc.get(id).fold(later)(_.merge(later)))
    }

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
        // Adjacent signal frames merge for the same reason morphs do — one
        // event instead of two, with identical effect, since a
        // `datastar-patch-signals` payload is merged into the client's store
        // rather than replacing it.
        //
        // ADJACENT is the whole rule, and it is what keeps the cursor honest:
        // the cursor rides as a signal patch at the END of a batch, so it
        // merges with the batch's own frame exactly when nothing separates them
        // — a signals-only batch, which is the common case for a value tick.
        // Put an element patch between them and they stay two events, which is
        // required: a client echoing the cursor must have applied what came
        // before it (ADR 0011).
        case (Patch.Signals(before) :: rest, Patch.Signals(next)) =>
          Patch.Signals(before ++ next) :: rest
        case (acc, one) => one :: acc
      }
      .reverse
      .map(_.toSse)

  /** Put `content` in a STATE group's host — one patch, whatever the client's
    * DOM currently holds there.
    *
    * The host takes at most one member, so `Inner` is both the delta (no
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
        List(Patch.Insert(html, PatchMode.Inner, renderer.hostId(gid)))
      case None =>
        departed.map(id => Patch.Remove(renderer.elementId(id))).toList
    }

}
