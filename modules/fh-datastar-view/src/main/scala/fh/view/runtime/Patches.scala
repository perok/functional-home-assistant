package fh.view.runtime

import cats.effect.IO
import cats.syntax.traverse.*
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
  *
  * `establishes` is what this patch's BYTES put in the client's DOM: one entry
  * per node the patch renders, digest included. Today the shared
  * [[FragmentLog]] already knows — [[Patches.diff]] wrote it in the same step
  * that produced the patch — so nothing reads this yet. It is here because that
  * ceases to be true one step from now: when a session decides against its OWN
  * `holds` rather than the shared log, the only thing that can tell it what it
  * just sent is the patch it sent (docs/plan-session-pulled-changelog.md).
  *
  * `invalidates` names mounts this patch RE-SUPPLIED without a per-node trace:
  * its bytes replaced everything under them, so any digest still claimed for
  * those nodes describes a DOM that no longer exists. Dropping is the safe
  * direction (absent reads as "send it"), and it is not optional for a fill — a
  * node whose claimed digest happens to come round again would otherwise be
  * suppressed while the client shows the fill's version. This is the same prune
  * [[FragmentLog.invalidateWhere]] does for the shared log.
  *
  * A [[Patch.Remove]] needs neither: it places no bytes, and a stale claim for
  * an element that is GONE costs at most a morph at a missing id, which the
  * client silently ignores. What brings it back is an insert, which establishes
  * afresh.
  */
private[runtime] case class Addressed(
    surface: Option[String],
    patch: Patch,
    establishes: Map[NodeId, Digest] = Map.empty,
    // Roots, applied by prefix — a mount and everything under it.
    invalidates: Set[NodeId] = Set.empty
) extends Directed

/** One diff pass's output, with the store version it was diffed at.
  *
  * The version travels WITH the items rather than as one of them: a session
  * advances its `position` to what it was just served, and digging that out of
  * an encoded cursor signal at the end of the list would be reading the wire
  * format back.
  */
private[runtime] case class Batch(version: Long, items: List[Directed]) {
  def nonEmpty: Boolean = items.nonEmpty
}

/** Something already in wire form, and so never merged with anything: the
  * resume cursor's signal. Distinct from [[Addressed]] because the difference
  * is exactly whether the send path may still combine it with its neighbour.
  */
private[runtime] case class Encoded(
    surface: Option[String],
    event: ServerSentEvent
) extends Directed

/** One item of a batch that survived a connection's filters, on its way to the
  * wire — the input to [[Patches.encode]].
  */
private[runtime] enum Step {

  /** A patch that may still be combined with an adjacent one. */
  case Mergeable(surface: Option[String], patch: Patch)

  /** Bytes: a resolved [[Varying]] or an [[Encoded]]. A barrier by nature. */
  case Ready(event: ServerSentEvent)
}

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

/** Every rendering a [[Patches.diff]] pass is expected to ask for, produced by
  * [[Patches.prepare]] BEFORE the log is touched.
  *
  * '''Why this exists''': rendering is the expensive half of a diff (a mustache
  * template per node, then a SHA-256 over the bytes) and it does not depend on
  * the log at all — every one of these is a pure function of `(renderer,
  * states)`. The log only decides whether the RESULT is worth sending. Leaving
  * the renders where the log is read makes them run inside `Ref.modify`, whose
  * function a CAS loop may run several times, so a writer that loses the race
  * re-renders everything to throw it away.
  *
  * So the renders happen here, once, outside; [[Patches.diff]] then does
  * lookups and map updates, which is cheap enough that losing a CAS costs
  * nothing worth avoiding.
  *
  * '''Misses fall through to the renderer''' rather than failing, and that is
  * deliberate: it keeps this a cache rather than a contract, so a branch whose
  * choice of what to render depends on the log (there is exactly one — see
  * [[Patches.renderMembershipChange]]) stays correct without having to
  * pre-render both of its alternatives. Such a miss renders inside the section,
  * exactly as the whole pass used to.
  *
  * '''This renders no more eagerly than the pass already did.''' The shared
  * pass never consulted the log to decide WHETHER to render — it rendered every
  * affected node and asked the log only whether the bytes were worth sending —
  * so moving those renders earlier changes when they run, not how many. The
  * genuinely lazy renders are elsewhere and stay there: a `Pending` variant is
  * rendered only for a selection some connection actually holds
  * (`Server.varyingPatches` via `Memo`), guarded by [[FragmentLog.atLeast]],
  * which skips even that on a version it already has. Neither passes through
  * here, and flips render nothing at all.
  *
  * The one case that does MORE work than before: a membership change where the
  * churn heuristic says "arrivals" but the group turns out not to be
  * established renders the arrivals here and then the fill inside the section.
  * Bounded (arrivals are a minority by definition of that heuristic) and it
  * cannot repeat — see [[Patches.renderMembershipChange]].
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

/** The pure diff core, lifted out of [[Server]] so it is testable without a
  * booted server (no HA stub, no `Supervisor`, no SSE plumbing). Three entry
  * points:
  *
  *   - [[plan]] SELECTS what one state change touches — the affected static
  *     component ids, dynamic groups, and flipped state groups — for every
  *     client at once.
  *   - [[prepare]] RENDERS what that selection will need, outside the log and
  *     through the per-slug [[RenderCache]].
  *   - [[diff]] DIFFS the renders against a cache, returning the updated cache
  *     and what to emit.
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

  /** Render everything [[diff]] will ask for, so it can run outside the log's
    * critical section. See [[Renders]] for why.
    *
    * This mirrors [[diff]]'s decisions, but only the ones derivable from STATE
    * — which is all of them bar one. A group whose membership did not move
    * renders its touched children; one whose membership moved renders either
    * the arrivals or the whole mount, and the churn heuristic that chooses
    * between them is pure state ([[Server.MaxChurnFraction]] over the member
    * counts). The single decision that also reads the log — `hasChildOf`, which
    * can downgrade an arrivals-only patch to a whole-mount fill — is left to
    * [[diff]], and its fill renders there on the rare occasions it fires.
    *
    * Flips render nothing at all (see [[flipStateGroup]]), and `varyingIds`
    * render lazily per variant, so neither appears here.
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
        cache(id, inputs)(
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
    * Named because [[prepare]] and [[renderMembershipChange]] must agree on it:
    * one decides what to render, the other what to send.
    */
  private def perEntityChurn(churn: Int, shown: Int): Boolean =
    churn > 0 && churn < Server.MaxChurnFraction * shown

  /** '''Flips run FIRST.''' Their prune must precede any diff that could
    * suppress a member fragment against a pre-flip entry.
    *
    * Pure over `states`; the caller wraps it in the log Ref's `modify`. Takes
    * [[Renders]] rather than a bare `Renderer` so the expensive half has
    * already happened — everything below is lookups, digests of already-built
    * strings, and map updates.
    */
  def diff(
      renders: Renders,
      log: FragmentLog,
      req: DiffRequest
  ): (FragmentLog, List[Directed], List[Pending]) = {
    val renderer = renders.renderer
    val at = req.stamp
    // Each stage carries its own patches' tag through, so a patch's audience is
    // decided once — where the node was SELECTED — and never re-derived.
    val (logAfterFlips, flipPatches, flipPending) =
      req.flips.foldLeft(
        (log, List.empty[Addressed], List.empty[Pending])
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
        (c2, acc ++ ps, deferred ++ pending)
      }
    val rendered =
      req.staticIds.flatMap { case (id, surface) =>
        renders.node(id).map(bytes => (id, surface, bytes))
      }
    val (logAfterStatic, staticPatches) =
      rendered.foldLeft((logAfterFlips, List.empty[Addressed])) {
        case ((c, acc), (id, surface, bytes)) =>
          if (c.holds(id, bytes.digest)) (c, acc)
          else
            (
              c.set(id, bytes.digest, at.version),
              acc :+ Addressed(
                surface,
                Patch.Morph(bytes.html),
                Map(id -> bytes.digest)
              )
            )
      }
    val (finalLog, dynPatches) =
      req.dynamics.foldLeft((logAfterStatic, List.empty[Addressed])) {
        case ((c, acc), (gid, surface, touched)) =>
          val (c2, ps) =
            renderDynamicGroup(renders, c, gid, surface, touched, at)
          (c2, acc ++ ps)
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
      flipPatches ++ staticPatches ++ dynPatches,
      flipPending ++ pending
    )
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
      holds: Map[NodeId, Digest],
      states: Map[String, EntityState],
      v: Long,
      open: Set[String] = Set.empty,
      uiState: Map[String, String] = Map.empty
  ): List[Addressed] = {
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
        ).map(Addressed(None, _, invalidates = Set(gid)))
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
                  Addressed(None, Patch.Remove(renderer.elementId(nodeId))),
                  Addressed(
                    None,
                    insertInto(
                      renderer,
                      gid,
                      members,
                      entityId,
                      _ => true,
                      html
                    ),
                    Map(nodeId -> Digest.of(html))
                  )
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
      Addressed(
        None,
        Patch.Insert(
          renderer.renderMount(gid, states, uiState).map(_._2).mkString,
          PatchMode.Inner,
          renderer.mountId(gid)
        ),
        invalidates = Set(gid)
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
          // Compared against what THIS viewer holds — another viewer's digest
          // says nothing about this DOM, which is why `holds` arrives already
          // narrowed to one client rather than as the shared log.
          // A MISSING entry counts as "send": unknown, so tell the client.
          val digest = Digest.of(html)
          Option.when(!holds.get(id).contains(digest))(
            Addressed(None, Patch.Morph(html), Map(id -> digest))
          )
        }
      )
    owed.nodes
      // The cursor names every node that changed, across every surface — it
      // knows nothing about who is looking. A morph at an id this client's DOM
      // lacks is a silent no-op, so this only ever cost bytes; it is still one
      // client's worth of another client's tab on every reconnect.
      .filter(renderer.visibleNode(_, open, states))
      .flatMap(id =>
        renderer
          .renderLogged(id, states, uiState)
          .map(html =>
            Addressed(None, Patch.Morph(html), Map(id -> Digest.of(html)))
          )
      ) ++
      fromOpen ++
      gone.toList.sorted.map(id =>
        Addressed(None, Patch.Remove(renderer.elementId(id)))
      ) ++
      branchFills ++ places ++ refills
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
          l.set(id, Digest.of(html), version, renderer.variantOf(id, uiState))
        }
        (recorded, Some(t.html))
    }
  }

  /** Combine what a connection is actually sending, then put it on the wire.
    *
    * '''This runs per connection, after its filters, and that is the point.'''
    * Merging is a property of one client's outgoing stream: which patches a
    * client keeps is its own answer, so a merge performed before that decision
    * would bake one client's choices into everyone's bytes.
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
    *     silent no-op. A [[Step.Ready]] is a barrier for the same reason.
    */
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

  def encode(steps: List[Step]): List[ServerSentEvent] =
    steps
      .foldLeft(List.empty[Step]) {
        case (
              Step.Mergeable(prevTag, Patch.Morph(before)) :: rest,
              Step.Mergeable(tag, Patch.Morph(next))
            ) if prevTag == tag =>
          Step.Mergeable(tag, Patch.Morph(before + next)) :: rest
        case (acc, one) => one :: acc
      }
      .reverse
      .map {
        case Step.Mergeable(_, p) => p.toSse
        case Step.Ready(event)    => event
      }

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
  ): (FragmentLog, List[Addressed], Option[Pending]) = {
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
        // No member holds: nothing to render, just the departure — which
        // establishes nothing, since it places no bytes.
        case None =>
          (
            logged,
            branchPatch(renderer, gid, None, departed)
              .map(Addressed(surface, _)),
            None
          )
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
      renders: Renders,
      log: FragmentLog,
      gid: NodeId,
      surface: Option[String],
      touched: List[String],
      at: Stamp
  ): (FragmentLog, List[Addressed]) = {
    val membersBefore = renders.membersBefore(gid)
    val membersAfter = renders.membersAfter(gid)
    if (membersBefore != membersAfter)
      renderMembershipChange(
        renders,
        log,
        gid,
        surface,
        membersBefore,
        membersAfter,
        at
      )
    else
      touched.foldLeft((log, List.empty[Addressed])) {
        case ((c, acc), entityId) =>
          renders.child(gid, entityId) match {
            case None        => (c, acc) // not a current member
            case Some(bytes) =>
              val cid = renders.renderer.dynamicChildId(gid, entityId)
              if (c.holds(cid, bytes.digest)) (c, acc)
              else
                (
                  c.set(cid, bytes.digest, at.version),
                  acc :+ Addressed(
                    surface,
                    Patch.Morph(bytes.html),
                    Map(cid -> bytes.digest)
                  )
                )
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
    *
    * ==The one render this pass cannot decide in advance==
    *
    * [[prepare]] renders everything else before the log is read, because
    * nothing else needs the log to know WHAT to render. This branch does. It
    * picks between two different renderings —
    *
    *   - '''arrivals only''' (cheap: one card per entity that joined), or
    *   - '''the whole mount''' ([[fillGroup]], one card per CURRENT member)
    *
    * — using two conditions, and they do not come from the same place:
    *
    *   - `perEntity` — is the churn a minority of the group? Pure state, so
    *     [[prepare]] evaluates the same [[perEntityChurn]] and pre-renders
    *     whichever side it names.
    *   - `established` — does the log already hold children for this group?
    *     Only the log knows, and it is asked here.
    *
    * `established` is false exactly when this group's children are not in the
    * log: the first membership change after a renderer swap or a reload (the
    * log is minted fresh), or after a fill pruned them. Sending arrivals then
    * would patch against a DOM baseline the server cannot vouch for, so it
    * repaints instead.
    *
    * When `perEntity` says "arrivals" and `established` says "no", the fill has
    * not been pre-rendered and is built HERE, inside the critical section —
    * i.e. exactly the behaviour this file had before [[prepare]] existed. That
    * is a deliberate trade rather than an oversight: pre-rendering both sides
    * would make every membership change pay for the whole mount, to spare a
    * case that needs a swap/reload first and then cannot repeat (the fill it
    * runs establishes the group). Rare, self-limiting, and correct either way —
    * whereas guessing `established` wrong in the other direction would send a
    * client a delta against markup it does not have.
    */
  private def renderMembershipChange(
      renders: Renders,
      log: FragmentLog,
      gid: NodeId,
      surface: Option[String],
      membersBefore: List[String],
      membersAfter: List[String],
      at: Stamp
  ): (FragmentLog, List[Addressed]) = {
    val renderer = renders.renderer
    val beforeSet = membersBefore.toSet
    val afterSet = membersAfter.toSet
    val added = membersAfter.filterNot(beforeSet)
    val removed = membersBefore.filterNot(afterSet)
    val churn = added.size + removed.size
    // The same heuristic `prepare` used to decide what to pre-render.
    val perEntity = perEntityChurn(churn, membersBefore.size)
    // The only input to this pass that comes from the log rather than from
    // state, and so the only reason a render can be needed that `prepare` did
    // not do. See the class doc above.
    val established = log.hasChildOf(gid)
    // The query boundary moved but the RENDERED membership did not — an entity
    // matching the query but no case is not a member either way, so there is
    // nothing to send and nothing to fill.
    if (churn == 0) (log, Nil)
    else if (!perEntity || !established)
      fillGroup(renders, log, gid, surface, at)
    else {
      val (afterRemoves, removePatches) =
        removed.foldLeft((log, List.empty[Addressed])) { case ((c, acc), e) =>
          val cid = renderer.dynamicChildId(gid, e)
          (
            c.removed(gid, cid, at),
            acc :+ Addressed(surface, Patch.Remove(renderer.elementId(cid)))
          )
        }
      val (afterAdds, addPatches) =
        added.sorted.foldLeft((afterRemoves, List.empty[Addressed])) {
          case ((c, acc), e) =>
            renders.child(gid, e) match {
              case None        => (c, acc) // defensive: not renderable, skip
              case Some(bytes) =>
                val cid = renderer.dynamicChildId(gid, e)
                // Anchor only on members ALREADY in the client's DOM, i.e.
                // pre-change ones: a co-arrival may not be inserted yet.
                val patch = insertInto(
                  renderer,
                  gid,
                  membersAfter,
                  e,
                  beforeSet,
                  bytes.html
                )
                (
                  c.placed(gid, MemberKey.Entity(e), cid, bytes.digest, at),
                  acc :+ Addressed(surface, patch, Map(cid -> bytes.digest))
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
      renders: Renders,
      log: FragmentLog,
      gid: NodeId,
      surface: Option[String],
      at: Stamp
  ): (FragmentLog, List[Addressed]) = {
    val renderer = renders.renderer
    val members = renders.fill(gid)
    // Prune first: a member that LEFT must not keep an entry, and its stale
    // mutation must not replay against a mount this fill just re-supplied.
    val pruned = log.invalidateWhere(k => k == gid || k.startsWith(gid + "_"))
    val stamped = members.foldLeft(pruned) { case (l, (cid, bytes)) =>
      l.set(cid, bytes.digest, at.version)
    }
    (
      stamped,
      List(
        Addressed(
          surface,
          Patch.Insert(
            members.map(_._2.html).mkString,
            PatchMode.Inner,
            renderer.mountId(gid)
          ),
          // A fill is the one patch that places SEVERAL nodes, which is why
          // `establishes` is a map rather than a single entry.
          members.map { case (cid, bytes) => cid -> bytes.digest }.toMap,
          // ...and the one that also UNPLACES: a member that left is inside the
          // mount this `Inner` just overwrote, so its claim has to go with it.
          Set(gid)
        )
      )
    )
  }
}
