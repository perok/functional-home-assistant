package fh.view.runtime

import cats.effect.IO
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

/** One patch and WHO it is for.
  *
  * `None` means the main page: every connection sees it. `Some(sid)` means the
  * patch belongs inside a user-selected surface, and only a connection with
  * that surface open should receive it.
  *
  * The tag names the innermost USER-selected surface, and that qualifier is
  * load-bearing. `Renderer.selectedSurfaces` does `filterNot(isStateGroup)`, so
  * a state-activated branch is never in anyone's `open` set — its visibility is
  * server-decided and identical for every client. Tagging a node with a state
  * surface would therefore filter its patches away from EVERYONE. State
  * surfaces are transparent here: a node inside an `If` branch nested in a tab
  * panel is tagged with the tab panel.
  *
  * Over-sending is safe and under-sending is not: a morph at an id the DOM
  * lacks is a silent no-op, so the filter can only ever cost bytes. That
  * asymmetry is why anything the renderer cannot attribute to a user-selected
  * surface stays untagged.
  */
private[runtime] case class Addressed(
    surface: Option[String],
    event: ServerSentEvent
) extends Directed

/** A patch whose BYTES depend on which member the viewer has mounted.
  *
  * A flip inserts a whole branch, and if a tabs card sits inside it, that
  * branch's HTML is not one thing — it is one thing per selection. So the
  * shared pass does not render it at all: it carries the render, and each
  * connection performs it against its own selections at send time.
  *
  * This is the ONLY per-viewer rendering in the system, and it is the
  * irreducible one. Everything else about the branch — that it flipped, when,
  * and which member won — was decided by entity state and is identical for
  * everybody.
  *
  * `resolve` yields a DECISION, not a render: for a node compared against the
  * log it may be "nothing to send". And it is memoised per [[Selections]], not
  * per connection — two viewers holding the same selection must both receive
  * the patch, so what is computed once is the verdict, which is then handed to
  * everyone who resolves to that key.
  *
  * Every one of these is built from a [[Pending]], in one place in the shell. A
  * branch fill and a single varying node differ only in how many nodes their
  * render writes.
  */
private[runtime] case class Varying(
    surface: Option[String],
    resolve: (Map[String, String], StoreState) => IO[Option[ServerSentEvent]]
) extends Directed

/** A render the shell has to perform LATER, per variant, because its verdict
  * needs the log and an effect.
  *
  * The core decides everything about it — which node, whose it is, and how to
  * render one variant — so the only thing left outside is when to force it and
  * what to do with the result. Teaching the shell to render as well would put
  * "what goes on the wire" in two places.
  */
private[runtime] case class Pending(
    surface: Option[String],
    // The nodes whose digests this render writes — known BEFORE rendering, so
    // the version prune can skip the render entirely.
    keys: List[NodeId],
    // The member this render puts in a mount, when it fills one. A queued fill
    // can be SUPERSEDED — the selection moved again before this connection got
    // to its item — and then its bytes are not merely redundant but wrong for a
    // moment, until the item behind it corrects them. `None` for a node morph,
    // which fills nothing and so cannot be overtaken.
    placing: Option[NodeId],
    // This client's selections, narrowed to the ones this render reads. Two
    // viewers who agree on those share one render however else they differ.
    selections: Map[String, String] => Selections,
    // Takes the snapshot to render FROM, rather than closing over the batch's.
    // A queued item is forced whenever its connection gets to it, and by then
    // newer state may exist — which is the state worth sending, since anything
    // older is about to be superseded by an item already behind it in the same
    // queue.
    render: (Selections, Map[String, EntityState]) => Option[Rendered]
)

/** What a pending render produced: the patch, and what it put in each node.
  *
  * The two travel together because the log owes a digest for exactly what went
  * on the wire. A single-node morph carries one entry; a fill carries one per
  * node it placed — which is what lets a fill be suppressed and resumed by the
  * same per-node rules as everything else, without ever fingerprinting a
  * composed subtree under one id.
  */
private[runtime] case class Rendered(patch: Patch, own: Map[NodeId, String])

/** Something the shared pass produced, plus who may see it. Either already
  * bytes ([[Addressed]]) or a render one connection performs for itself
  * ([[Varying]]) — the only two kinds there are, because a client's own
  * selection is the only thing a per-slug render cannot know.
  */
private[runtime] sealed trait Directed {
  def surface: Option[String]

  /** `Option.forall` over the single tag is the whole visibility test. */
  def visibleTo(open: Set[String]): Boolean = surface.forall(open)
}

/** The pure diff core, lifted out of [[Server]] so it is testable without a
  * booted server (no HA stub, no `Supervisor`, no SSE plumbing). Two entry
  * points:
  *
  *   - [[plan]] SELECTS what one state change touches — the affected static
  *     component ids, dynamic groups, and flipped state groups — for every
  *     client at once.
  *   - [[diff]] DIFFS that selection against a cache, returning the updated
  *     cache and what to emit.
  *
  * Everything here is pure over the entity snapshot; the caller ([[Server]])
  * owns the `Ref`/`IO` that reads the snapshot and `modify`s the cache.
  */
private[runtime] object Patches {

  /** A selection of what one [[StateChange]] touches, ready to [[diff]] against
    * a cache. Bundles the assembled `staticIds`/`dynamics`/`flips` with the
    * render inputs (`change`/`states`/`before`) they are diffed with, rather
    * than nine positional arguments at the call site.
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
    * the [[Pending]] this pass emits alongside the flip.
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

  /** '''Flips run FIRST.''' Their prune must precede any diff that could
    * suppress a member fragment against a pre-flip entry.
    *
    * Pure over `states`; the caller wraps it in the log Ref's `modify`.
    */
  def diff(
      renderer: Renderer,
      log: FragmentLog,
      req: DiffRequest
  ): (FragmentLog, List[Directed], List[Pending]) = {
    val at = req.stamp
    // Each stage carries its own patches' tag through, so a patch's audience is
    // decided once — where the node was SELECTED — and never re-derived.
    val (logAfterFlips, flipPatches, flipPending) =
      req.flips.foldLeft(
        (log, List.empty[(Option[String], Patch)], List.empty[Pending])
      ) { case ((c, acc, deferred), (gid, surface)) =>
        val (c2, ps, pending) =
          flipStateGroup(
            renderer,
            c,
            gid,
            surface,
            req.before,
            req.states,
            at
          )
        (
          c2,
          acc ++ ps.map(surface -> _),
          deferred ++ pending
        )
      }
    val rendered =
      req.staticIds.flatMap { case (id, surface) =>
        renderer
          .renderNodeById(id, req.states)
          .map(html => (id, surface, html))
      }
    val (logAfterStatic, staticPatches) =
      rendered.foldLeft((logAfterFlips, List.empty[(Option[String], Patch)])) {
        case ((c, acc), (id, surface, html)) =>
          if (c.holds(id, html)) (c, acc)
          else
            (c.set(id, html, at.version), acc :+ (surface, Patch.Morph(html)))
      }
    val (finalLog, dynPatches) =
      req.dynamics.foldLeft(
        (logAfterStatic, List.empty[(Option[String], Patch)])
      ) { case ((c, acc), (gid, surface, touched)) =>
        val (c2, ps) =
          renderDynamicGroup(
            renderer,
            c,
            gid,
            touched,
            req.states,
            req.before,
            at
          )
        (c2, acc ++ ps.map(surface -> _))
      }
    // The per-variant renders the shell forces on demand. Described here, next
    // to everything else that decides what goes on the wire.
    val pending = req.varyingIds.map { case (id, surface) =>
      Pending(
        surface,
        List(id),
        None,
        renderer.selectionsOf(id, _),
        (sel, states) =>
          renderer
            .renderNodeById(id, states, uiFrom(sel))
            .map(html => Rendered(Patch.Morph(html), Map(id -> html)))
      )
    }
    (
      finalLog,
      addressed(flipPatches ++ staticPatches ++ dynPatches),
      flipPending ++ pending
    )
  }

  /** Everything a client resuming at cursor `v` is owed, as SSE events. The
    * pure core of the resume path (ADR 0011): the caller reads the log +
    * snapshot and writes the stream; the ordering argument lives here.
    *
    * '''ONE rule, one candidate set, one snapshot:'''
    *
    * > Candidates = nodes whose logged version is `>= v`, plus every node in an
    * > OPEN surface. Render each from the current snapshot, and send it when >
    * `version >= v || fingerprint != stored`, a MISSING entry counting as >
    * "send".
    *
    * The two disjuncts are two different ignorances. `version >= v` means the
    * node changed at or after the cursor, so the client may never have applied
    * it — send what we have now, which is at least as new. `fingerprint !=
    * stored` is the UNTRACKED case: a surface nothing rendered while nobody was
    * viewing it, where only re-rendering can tell whether the client's DOM is
    * current.
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
      log: FragmentLog,
      states: Map[String, EntityState],
      v: Long,
      open: Set[String] = Set.empty,
      uiState: Map[String, String] = Map.empty
  ): List[ServerSentEvent] = {
    val owed = log.since(v)
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
        )
      }
    val places = dynamic
      .collect { case (nodeId, p: Mutation.Placed) => (nodeId, p) }
      .groupBy { case (_, p) => p.container }
      .toList
      .sortBy(_._1)
      .flatMap { case (gid, inGroup) =>
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
          .flatMap { case (nodeId, entityId, _) =>
            // Rendered NOW, not read back: the snapshot is at least as fresh as
            // anything the log could have kept, and it is what lets the log hold
            // a digest instead of bytes.
            renderer
              .renderDynamicChild(gid, entityId, states)
              .toList
              .flatMap { html =>
                // Every current member is a usable anchor here: emitting
                // descending by position means a node's successor was either
                // already in the client's DOM or placed a moment ago.
                List(
                  Patch.Remove(renderer.elementId(nodeId)),
                  insertInto(renderer, gid, members, entityId, _ => true, html)
                )
              }
          }
      }
    // Containers whose membership history no longer reaches this cursor: the
    // delta is uncomputable, so the mount is filled wholesale. `Inner` is
    // all-or-nothing over a mount's children, so this cannot be partial — which
    // is precisely why it is the fallback of last resort, and why it is worth
    // having only because it replaced a whole-BODY repaint.
    val refills = owed.refill.sorted.map { gid =>
      Patch.Insert(
        renderer.renderMount(gid, states, uiState).map(_._2).mkString,
        PatchMode.Inner,
        renderer.mountId(gid)
      )
    }
    // The second candidate set: an open surface's nodes, which the cursor alone
    // would not name. Sorted for a deterministic order (ids are location-derived,
    // so this is document order among siblings), and dropped when a mutation or a
    // refill is already re-supplying an ancestor.
    val fromOpen = open.toList
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
      .flatMap(id =>
        renderer.renderLogged(id, states, uiState).flatMap { html =>
          // Compared against THIS viewer's variant — the log holds one digest
          // per variant, and another viewer's says nothing about this DOM.
          // A MISSING entry counts as "send": unknown, so tell the client.
          Option.when(!log.holds(id, html, renderer.variantOf(id, uiState)))(
            Patch.Morph(html)
          )
        }
      )
    (owed.nodes
      // The cursor names every node that changed, across every surface — it
      // knows nothing about who is looking. A morph at an id this client's DOM
      // lacks is a silent no-op, so this only ever cost bytes; it is still one
      // client's worth of another client's tab on every reconnect.
      .filter(renderer.visibleNode(_, open, states))
      .flatMap(id =>
        renderer.renderLogged(id, states, uiState).map(Patch.Morph(_))
      ) ++
      fromOpen ++
      gone.toList.sorted.map(id => Patch.Remove(renderer.elementId(id))) ++
      branchFills ++ places ++ refills).map(_.toSse)
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

  /** Fill `host` with `arriving`'s rendering, and tell the log what it put
    * there. THE fill primitive: a tab switch, a popup open and a state-group
    * flip are the same operation, differing only in who chose the member.
    *
    * The model already says so — a tab panel and an `If` branch are both
    * surfaces with `bakeInto`/`bakeAs`/`bakeIndex`, and `Renderer.mountId`
    * derives a group's mount from its members' `Surface.hostId` — so both
    * callers name the same host the same way. Only the SELECTOR differs
    * (`resolveActive` reads the client's signal, `resolveActiveByState` reads
    * entity state), and that stays with the caller.
    *
    * Eviction must come first: the departing member's DOM is gone, so its
    * entries describe nothing, and a stale one would suppress a real change on
    * the way back. The arrival is rendered ONCE and traced, so the next live
    * tick can tell "unchanged" from "never told".
    *
    * What it does NOT do is record a [[Mutation]] — that is the caller's,
    * because the two callers disagree about what a fill MEANS. A flip is server
    * truth for every viewer, so it is a membership change the log must replay
    * to a client that missed it. A tab switch is one client's choice, and
    * asserting it as shared structure would replay one viewer's selection to
    * everybody.
    */
  private[runtime] def fillHost(
      renderer: Renderer,
      log: FragmentLog,
      host: DomId,
      arriving: Option[String],
      states: Map[String, EntityState],
      uiState: Map[String, String],
      version: Long
  ): (FragmentLog, Option[String]) = {
    val resupplied =
      (renderer.surfacesAt(host) ++ arriving).flatMap(renderer.surfaceNodeIds)
    val pruned = log.invalidateWhere(resupplied)
    arriving.flatMap(renderer.renderSurfaceTraced(_, states, uiState)) match {
      case None    => (pruned, None)
      case Some(t) =>
        val recorded = t.own.foldLeft(pruned) { case (l, (id, html)) =>
          l.set(id, html, version, renderer.variantOf(id, uiState))
        }
        (recorded, Some(t.html))
    }
  }

  /** Render a batch's patches to the wire, merging what can share an event.
    *
    * A [[Patch.Morph]] carries no selector and no mode — it finds its target by
    * the id inside its own HTML — so any run of them is one
    * `datastar-patch-elements` carrying several top-level elements, each
    * morphed against its own id (pinned in `DatastarMorphContractSuite`). One
    * HA frame touching a dozen entities is then one event instead of a dozen.
    *
    * Two constraints, both load-bearing:
    *
    *   - '''Same tag only.''' The tag is what keeps a popup's patch from
    *     reaching a client without it open. Merging across tags would weld a
    *     tagged patch to an untagged one and leak it to everybody.
    *   - '''Adjacent only.''' An [[Patch.Insert]]/[[Patch.Remove]] names its
    *     own target and cannot join, but it is also a BARRIER: a morph after an
    *     insert may target the element that insert just created, and reordering
    *     across it would aim the morph at an id the DOM does not hold yet — a
    *     silent no-op.
    */
  private def addressed(
      patches: List[(Option[String], Patch)]
  ): List[Directed] =
    patches
      .foldLeft(List.empty[(Option[String], Patch)]) {
        case (
              (prevTag, Patch.Morph(before)) :: rest,
              (tag, Patch.Morph(next))
            ) if prevTag == tag =>
          (tag, Patch.Morph(before + next)) :: rest
        case (acc, one) => one :: acc
      }
      .reverse
      .map { case (tag, p) => Addressed(tag, p.toSse) }

  /** [[Selections]] spelled as the ui-state a render reads. Canonical by
    * construction — every value is an in-range index — which is what makes two
    * viewers with differently-spelled but equivalent signals share one render.
    */
  private def uiFrom(sel: Selections): Map[String, String] =
    sel.map { case (gid, idx) => gid -> idx.toString }

  /** '''An `If` flip is a membership change on a list of one:''' the old branch
    * is [[Mutation.Gone]], the new one is [[Mutation.Placed]] into the mount.
    * Repeated flips collapse by latest-wins per node id.
    *
    * On the wire that is a single `Inner`, not a `remove` plus an `append`. A
    * bake group has one hole, so there are no siblings to preserve and no
    * position to fix, and it is idempotent by construction: it lands the same
    * whether the client holds the old branch, the new one, or nothing. A
    * condition matching NO branch is the one other shape — a `Gone` with no
    * `Placed`, emitted as a plain `remove`, since an `Inner` of empty content
    * is not a well-formed patch.
    *
    * It does NOT morph the host, whose HTML would embed the selected branch and
    * so carry other nodes. The log records WHICH member is in the mount, never
    * what it holds: if a container's record moved when a CHILD's content
    * changed, every child change would re-supply the container.
    *
    * Without the structural half a client disconnected across a flip would show
    * the old branch '''permanently''' — the new branch's nodes arrive as morphs
    * against ids its DOM lacks (silent no-ops) and nothing removes the old
    * ones. `selectedSurfaces` does `filterNot(isStateGroup)`, so a branch is
    * never in `open` either.
    *
    * The prune is load-bearing: hidden-branch churn deliberately leaves member
    * entries stale (the silence guarantee), so a re-revealed node whose HTML
    * happens to equal its pre-flip entry would be suppressed while the client's
    * DOM has moved on.
    */
  private def flipStateGroup(
      renderer: Renderer,
      log: FragmentLog,
      gid: NodeId,
      surface: Option[String],
      before: Map[String, EntityState],
      states: Map[String, EntityState],
      at: Stamp
  ): (FragmentLog, List[Patch], Option[Pending]) = {
    def memberAt(
        snapshot: Map[String, EntityState]
    ): Option[String] =
      renderer
        .resolveActiveByState(gid, snapshot)
        .flatMap(renderer.bakeMembers(gid).lift)
    val was = memberAt(before)
    val now = memberAt(states)
    // Defensive: the caller only passes groups whose selection actually moved.
    if (was == now) (log, Nil, None)
    else {
      val host = renderer.mountId(gid)
      val departed = was.map(renderer.surfaceContentId)
      // The mutation names WHERE the branch went; [[fillHost]] tells the log
      // what the fill put in each node it placed. Recording the composed
      // subtree under the branch's ROOT instead writes a digest for a node with
      // no rendering of its own, so nothing can ever resolve it and the fill's
      // members go unfingerprinted.
      def structure(l: FragmentLog): FragmentLog = {
        val withGone = departed.foldLeft(l)(_.removed(gid, _, at))
        now.foldLeft(withGone)((acc, sid) =>
          acc.placed(
            gid,
            MemberKey.Surface(sid),
            renderer.surfaceContentId(sid),
            at
          )
        )
      }
      // Nothing is rendered HERE. The shared pass evicts (a fill with no
      // arrival) and records where the branch went; the render is deferred, one
      // per distinct selection. A branch nobody's selection reaches inside
      // resolves to the empty key, so "one rendering serves every viewer" is
      // that case of the same mechanism rather than a second path — and it is
      // no longer rendered at all when nobody is connected to receive it.
      val (evicted, _) =
        fillHost(renderer, log, host, None, states, Map.empty, at.version)
      val logged = structure(evicted)
      now match {
        case Some(sid) =>
          (
            logged,
            Nil,
            Some(
              Pending(
                surface,
                // No version prune for a fill: what it writes is every
                // own-rendering node in the composed subtree, which is not
                // knowable until it is rendered (`surfaceNodeIds` is the wrong
                // set — it counts bare containers, which never carry an entry,
                // so the prune could only ever answer "no"). Nothing is lost:
                // the supersede check drops a fill a later flip replaced, and
                // the memo collapses viewers who share a selection, which
                // together are every case a repeated fill arises from.
                Nil,
                Some(renderer.surfaceContentId(sid)),
                renderer.selectionsUnder(sid, _),
                (sel, now) =>
                  renderer
                    .renderSurfaceTraced(sid, now, uiFrom(sel))
                    .map(t =>
                      Rendered(
                        Patch.Insert(t.html, PatchMode.Inner, host),
                        t.own
                      )
                    )
              )
            )
          )
        // No member holds: nothing to render, just the departure.
        case None =>
          (logged, branchPatch(renderer, gid, None, departed), None)
      }
    }
  }

  /** Put `content` in a STATE group's mount — one patch, whatever the client's
    * DOM currently holds there.
    *
    * The mount takes at most one member, so `Inner` is both the delta (no
    * siblings exist to preserve) and idempotent (it lands the same on the old
    * branch, the new one, or an empty host). `departed` is only consulted when
    * nothing holds now: an `Inner` of empty content is not a well-formed patch,
    * so the emptying case stays a `remove` of the branch that left.
    *
    * Shared by the live flip and the resume replay, so a client that missed a
    * flip gets byte-identical treatment to one that did not.
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

  /** Patch one affected dynamic group, for the whole frame.
    *
    * The membership question is asked ONCE, at the frame boundary: the group's
    * rendered members before vs. after. Unmoved means every entity the frame
    * touched here is still exactly where it was, so each gets an in-place morph
    * of its own card. Moved means reconcile ([[renderMembershipChange]]) —
    * once, however many entities did the moving.
    *
    * Per entity, this could not be answered: two entities can cross the query
    * boundary in opposite directions in one tick, and each single-entity view
    * of that reports a membership change the frame did not make.
    */
  private def renderDynamicGroup(
      renderer: Renderer,
      log: FragmentLog,
      gid: NodeId,
      touched: List[String],
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      at: Stamp
  ): (FragmentLog, List[Patch]) = {
    val membersBefore = renderer.dynamicMembers(gid, before)
    val membersAfter = renderer.dynamicMembers(gid, states)
    if (membersBefore != membersAfter)
      renderMembershipChange(
        renderer,
        log,
        gid,
        membersBefore,
        membersAfter,
        states,
        at
      )
    else
      touched.foldLeft((log, List.empty[Patch])) { case ((c, acc), entityId) =>
        renderer.renderDynamicChild(gid, entityId, states) match {
          case None       => (c, acc) // not a current member
          case Some(html) =>
            val cid = renderer.dynamicChildId(gid, entityId)
            if (c.holds(cid, html)) (c, acc)
            else (c.set(cid, html, at.version), acc :+ Patch.Morph(html))
        }
      }
  }

  /** Apply a membership change to a dynamic group. When the churn (entities
    * added + removed) is a small enough fraction of the group's rendered size
    * ([[Server.MaxChurnFraction]]) AND the group is already established in the
    * cache, patch the delta per-entity: a `remove` patch per departed member
    * and an `insert` (`before` its successor in DOM order, or `append` into the
    * group) per new member. Otherwise — heavy churn, an empty/last-member
    * group, or a group not yet in the cache (post-reload) — repaint the whole
    * group and prune its child cache entries, so a client re-establishes from a
    * known base.
    *
    * Resume bookkeeping: departures are tombstoned (they replay verbatim), an
    * arrival marks the group structural (its `insert` cannot be replayed) — see
    * [[FragmentLog]].
    *
    * Idempotency: the per-entity path fires only for an ESTABLISHED group, so
    * the first membership change after a renderer reload (fresh cache) always
    * repaints; a `remove` of an already-absent id is a no-op (see
    * [[Datastar.remove]]). Residual race: a client that missed an `insert` in
    * the connect gap (subscribed to the shared topic just after the patch) will
    * lack that child until the next whole-group repaint — an in-place morph
    * can't heal an id absent from that client's DOM. Bounded and self-healing;
    * whole-group repaints (heavy churn / reload) re-sync every client.
    */
  private def renderMembershipChange(
      renderer: Renderer,
      log: FragmentLog,
      gid: NodeId,
      membersBefore: List[String],
      membersAfter: List[String],
      states: Map[String, EntityState],
      at: Stamp
  ): (FragmentLog, List[Patch]) = {
    val beforeSet = membersBefore.toSet
    val afterSet = membersAfter.toSet
    val added = membersAfter.filterNot(beforeSet)
    val removed = membersBefore.filterNot(afterSet)
    val churn = added.size + removed.size
    val shown = membersBefore.size
    // Per-entity pays off only when the churn is a MINORITY of the group: at the
    // boundary (e.g. 1 of 2 members, or the last member) a whole-group repaint
    // is cheaper than juggling insert/remove patches. Strict `<` so exactly half
    // repaints. `MaxChurnFraction` is tunable.
    val perEntity = churn > 0 && churn < Server.MaxChurnFraction * shown
    val established = log.hasChildOf(gid)
    // The query boundary moved but the RENDERED membership did not — an entity
    // matching the query but no case is not a member either way, so there is
    // nothing to send and nothing to fill.
    if (churn == 0) (log, Nil)
    else if (!perEntity || !established)
      fillGroup(renderer, log, gid, states, at)
    else {
      val (afterRemoves, removePatches) =
        removed.foldLeft((log, List.empty[Patch])) { case ((c, acc), e) =>
          val cid = renderer.dynamicChildId(gid, e)
          (
            c.removed(gid, cid, at),
            acc :+ Patch.Remove(renderer.elementId(cid))
          )
        }
      val (afterAdds, addPatches) =
        added.sorted.foldLeft((afterRemoves, List.empty[Patch])) {
          case ((c, acc), e) =>
            renderer.renderDynamicChild(gid, e, states) match {
              case None       => (c, acc) // defensive: not renderable, skip
              case Some(html) =>
                val cid = renderer.dynamicChildId(gid, e)
                // Anchor only on members ALREADY in the client's DOM, i.e.
                // pre-change ones: a co-arrival may not be inserted yet.
                val patch =
                  insertInto(renderer, gid, membersAfter, e, beforeSet, html)
                (
                  c.placed(gid, MemberKey.Entity(e), cid, html, at),
                  acc :+ patch
                )
            }
        }
      (afterAdds, removePatches ++ addPatches)
    }
  }

  /** Fill a dynamic group's mount with its CURRENT members — the wholesale
    * fallback, and the last place a patch carried other nodes.
    *
    * A group's root element IS its mount, so the content goes out as an `Inner`
    * fill and no container-level fragment is written at all. Outer-morphing the
    * root and logging that HTML under `gid` would instead make a container's
    * fragment contain its children.
    *
    * '''The fill writes each member's fingerprint.''' It re-supplies the
    * mount's contents wholesale, so without that the next live diff would
    * compare against a baseline the client never had and suppress a real
    * change. Which is also why `Inner` and not something partial: it is
    * all-or-nothing over the mount's children — a named-but-empty child is
    * wiped, an omitted one deleted (`DatastarMorphContractSuite`) — so a fill
    * cannot preserve siblings and is reached only where the knowledge for a
    * delta is gone.
    */
  private def fillGroup(
      renderer: Renderer,
      log: FragmentLog,
      gid: NodeId,
      states: Map[String, EntityState],
      at: Stamp
  ): (FragmentLog, List[Patch]) = {
    val members = renderer.renderDynamicMembers(gid, states)
    // Prune first: a member that LEFT must not keep an entry, and its stale
    // mutation must not replay against a mount this fill just re-supplied.
    val pruned = log.invalidateWhere(k => k == gid || k.startsWith(gid + "_"))
    val stamped = members.foldLeft(pruned) { case (l, (cid, html)) =>
      l.set(cid, html, at.version)
    }
    (
      stamped,
      List(
        Patch.Insert(
          members.map(_._2).mkString,
          PatchMode.Inner,
          renderer.mountId(gid)
        )
      )
    )
  }
}
