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
    // An empty `Inner` is not a well-formed patch.
    case Patch.Fill(_, target, "", _) =>
      Datastar.remove(s"${target.selector} > *")
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
      // Whose keys moved; a member that ticked is in `staticIds`.
      sets: List[SetId],
      flips: List[NodeId],
      prev: Shared,
      next: Shared
  ):
    // The frame's own, so nothing claims a version its HTML lacks.
    def at: Long = next.version

  /** What one frame touches for every client, rendering nothing. `visible`
    * bounds what is recorded: wide records a node nobody pulls, narrow would
    * drop an update someone needed.
    *
    * A flipped state pick is logged by its host and its arriving case, and its
    * own id is left out below. A surface binding a changed entity contributes
    * only if it is shown from the page or a visible surface
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
    val sets = renderer.members.deltas(
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
    DiffRequest(staticIds, sets, flips, prev, next)
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

  /** The changelog, and nothing else: each node whose bytes moved, each host
    * whose keys moved, and the root of each key that arrived, each at its
    * place. An id in several clauses of a member is logged at the one `next`
    * shows ([[Renderer.ixAt]]), so two clauses are logged apart, and not at all
    * if its member left: its host's entry takes it from a viewer's list. A key
    * that left leaves its entries behind, harmless: a pull reads them only
    * where they are visible and outside any range it sends whole.
    */
  def record(
      renderer: Renderer,
      log: FragmentLog,
      req: DiffRequest
  ): FragmentLog = {
    val at = req.at
    // Only a clause-split id needs its member decided: deciding every touched
    // node was +21% B/op on RenderBench.publishSet.
    def touch(l: FragmentLog, id: NodeId): FragmentLog =
      renderer.index.ixsOf(id) match
        case ix :: Nil => l.touched(ix, at)
        case _         => renderer.ixAt(id, req.next).fold(l)(l.touched(_, at))
    val nodes = req.staticIds.foldLeft(log)(touch)
    val sets = req.sets.foldLeft(nodes) { (l, gid) =>
      val was = renderer.members.keysOf(gid, req.prev).toSet
      renderer.members
        .membersOf(gid, req.next)
        .foldLeft(touch(l, gid))((acc, m) =>
          if (was(m.node.ix)) acc else acc.touched(m.node.ix, at)
        )
    }
    req.flips.foldLeft(sets) { (l, gid) =>
      renderer.surfaces
        .decideByState(gid, req.next)
        .flatMap(renderer.surfaces.cases(gid).lift)
        .foldLeft(touch(l, renderer.surfaces.hostOf(gid)))((acc, c) =>
          touch(acc, NodeId.derived(c))
        )
    }
  }

  /** Everything a client at cursor `v` is owed (ADR 0011) — also the live path,
    * since a pull is a resume from `position + 1`.
    *
    * Candidates are the nodes logged at `>= v`, where the log's place is the
    * one shown now, and under [[Coverage.Swept]] every node and host of a
    * container on the viewer's screen, a popup open or a case its values pick.
    * A node is sent when its byte-slot values differ from what this viewer
    * holds. A host's keys are decided now and planned against the list it holds
    * ([[planHost]]); a key held and decided is sent again when its root was
    * logged and it has no rendering of its own, since it left and came back
    * while the viewer was away (A → B → A). Nothing inside a range sent whole
    * or leaving is patched as well. The cursor selects ids, never content.
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
      selections: Selections = Selections.none,
      coverage: Coverage = Coverage.Swept
  ): IO[List[Addressed]] =
    renderer
      .decide(decisions) { run =>
        val at = shared.asking(run).viewing(env)
        val o = owing(renderer, log, holds, at, v, open, coverage)
        (
          at,
          o,
          renderer.readsForPull(o.touched, o.filled, at, selections, env)
        )
      }
      .flatMap { (at, o, reads) =>
        answers(reads).flatMap(snapshot =>
          patches(renderer, cache, holds, at, Viewing(selections, snapshot), o)
        )
      }

  /** What the log is trusted to name for a resume's viewer. */
  enum Coverage {

    /** Every frame since the cursor was recorded against this viewer's screen,
      * so the log names all it is owed: a live session's pull, since its page
      * registers before it renders ([[Server.recordFrame]]) and a tab press or
      * a popup renders what arrives itself. A node it misses is a recorder bug,
      * which [[Swept]] would hide; `OracleSuite` is what catches one.
      */
    case Logged

    /** The screen may be one no frame was recorded against (a reconnect, an
      * adopted session), so every node on it is a candidate too.
      */
    case Swept
  }

  /** A host's steps, `host` as `shared` has it inside a member. */
  private[runtime] final case class HostPlan(
      gid: NodeId,
      host: Ix,
      steps: List[HostStep]
  )

  /** What a pull or a variable write owes one client, as `shared` decides it.
    */
  private[runtime] final case class Owing(
      morphs: List[NodeId],
      plans: List[HostPlan],
      // From the candidates, not the patches: a signal-only change emits none.
      touched: List[NodeId],
      filled: List[NodeId]
  )

  private def owing(
      renderer: Renderer,
      log: FragmentLog,
      holds: Map[Ix, Held],
      shared: Shared,
      v: Long,
      spelled: Set[String],
      coverage: Coverage
  ): Owing = {
    val index = renderer.index
    val open = renderer.surfaces.screen(spelled, shared)
    val logged = log.since(v)
    val fromOpen = coverage match
      case Coverage.Logged => Iterator.empty
      case Coverage.Swept  =>
        open.iterator
          .map(Ix(_))
          // `open` includes a tab panel inside a hidden `If`.
          .filter(renderer.surfaces.visibleContainer(_, open, shared))
          .flatMap(renderer.containerNodeIds)
    // Only what this client can see; the rest would cost only bytes.
    // A logged place of another clause is not what this viewer is shown; an
    // id with one place needs no decision.
    val loggedNow = logged.iterator
      .filter { i =>
        val id = index.id(Ix(i))
        index.ixsOf(id).sizeIs == 1 ||
        renderer.ixAt(id, shared).exists(_.toInt == i)
      }
      .map(i => index.id(Ix(i)))
    val candidates = (loggedNow ++ fromOpen).distinct
      .filter(renderer.surfaces.visibleNode(_, open, shared))
      .toList
      // Document order among siblings, since ids are location-derived.
      .sorted
    owe(
      renderer,
      holds,
      shared,
      candidates,
      candidates.flatMap(renderer.hostsAt(_, shared)).distinct,
      k => logged(k.toInt) && !renderer.hasOwnRendering(k)
    )
  }

  /** A variable write's owing: `hosts`, the picks it selects, planned against
    * `holds` with no key sent again, since no log is read; `readers` morphed.
    */
  private[runtime] def written(
      renderer: Renderer,
      holds: Map[Ix, Held],
      shared: Shared,
      readers: List[NodeId],
      hosts: List[NodeId]
  ): Owing =
    owe(renderer, holds, shared, readers, hosts, _ => false)

  private def owe(
      renderer: Renderer,
      holds: Map[Ix, Held],
      shared: Shared,
      candidates: List[NodeId],
      hosts: List[NodeId],
      again: Ix => Boolean
  ): Owing = {
    val index = renderer.index
    val planned = hosts
      .flatMap { gid =>
        val host = renderer.hostIx(gid, shared)
        planHost(
          holds.get(host).flatMap(_.shows),
          renderer.decidedKeys(gid, shared),
          again
        ) match
          case Nil   => None
          case steps => Some(HostPlan(gid, host, steps))
      }
    // A fill sends what is under its host, a place its key's subtree, and a
    // leave takes it away: every clause's, since the client may hold either.
    val whole: List[(Ix, Boolean)] = planned.flatMap(p =>
      p.steps.flatMap {
        case HostStep.Fill(_)     => List(p.host -> false)
        case HostStep.Leave(k)    => index.ixsOf(index.id(k)).map(_ -> true)
        case HostStep.Place(k, _) => index.ixsOf(index.id(k)).map(_ -> true)
      }
    )
    def covered(ix: Ix): Boolean =
      whole.exists((root, itself) =>
        index.contains(root, ix) && (itself || root != ix)
      )
    val plans = planned.filterNot(p => covered(p.host))
    val morphs = candidates.filterNot(
      renderer.nodeAt(_, shared).exists(n => covered(n.ix))
    )
    val placed = plans.flatMap(_.steps.collect { case HostStep.Place(k, _) =>
      index.id(k)
    })
    Owing(
      morphs,
      plans,
      (morphs ++ placed).distinct,
      plans.collect {
        case p if p.steps.exists {
              case HostStep.Fill(_) => true; case _ => false
            } =>
          p.gid
      }
    )
  }

  /** The batch's frame first, then the morphs, every leave, and each host's
    * fill or places: an anchor or a host is on the page when its patch runs.
    */
  private[runtime] def patches(
      renderer: Renderer,
      cache: RenderCache,
      holds: Map[Ix, Held],
      shared: Shared,
      viewing: Viewing,
      o: Owing
  ): IO[List[Addressed]] = {
    val index = renderer.index
    def rendered(key: Ix): IO[Option[(String, Map[Ix, Held])]] =
      renderer.surfaces.caseOf(key) match
        case Some((gid, i)) =>
          IO(
            renderer
              .renderCaseTraced(gid, i, shared, viewing)
              .map((_, t) => t.html -> t.claims)
          )
        case None => placed(renderer, cache, index.id(key), shared, viewing)
    def structural(p: HostPlan): IO[List[Addressed]] =
      p.steps.flatTraverse {
        case HostStep.Fill(_) =>
          IO {
            val content = renderer.renderHost(p.gid, shared, viewing)
            List(
              Addressed(
                Patch.Fill(
                  p.host,
                  renderer.hostId(p.gid),
                  content.parts.map(_._2).mkString,
                  Some(content.shown)
                ),
                content.claims
              )
            )
          }
        case HostStep.Place(key, before) =>
          rendered(key).map(_.toList.map { (html, claims) =>
            Addressed(
              Patch.Place(
                p.host,
                before.fold(renderer.hostId(p.gid))(b =>
                  renderer.elementId(index.id(b))
                ),
                html,
                key,
                before
              ),
              claims
            )
          })
        case HostStep.Leave(_) => IO.pure(Nil)
      }
    val leaves = o.plans.flatMap(p =>
      p.steps.collect { case HostStep.Leave(k) =>
        Addressed(Patch.Leave(p.host, index.id(k)))
      }
    )
    for {
      morphs <- o.morphs.traverseFilter(
        morph(renderer, cache, holds, shared, viewing, _)
      )
      placing <- o.plans.flatTraverse(structural)
    } yield {
      // Every one, held or not: a fill morphs onto what the page has, and the
      // morph re-applies a seed only where its text changed.
      val seeded = placing
        .collect { case Addressed(_: Patch.Fill, claims, _) => claims }
        .flatMap(_.valuesIterator.flatMap(_.signals))
        .map((name, value) => name -> SlotValue.json(value))
        .toMap
      withSignals(
        signalFrame(renderer, holds, shared, viewing.fragments, o.touched),
        seeded
      ) ++ morphs ++ leaves ++ placing
    }
  }

  private def withSignals(
      frame: List[Addressed],
      values: Map[SignalId, Json]
  ): List[Addressed] =
    if (values.isEmpty) frame
    else
      frame match
        case Addressed(Patch.Signals(diffed), held, _) :: Nil =>
          List(Addressed(Patch.Signals(diffed ++ values), held))
        case _ => List(Addressed(Patch.Signals(values)))

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

  /** A popup open: one client's choice, so it touches only that session's
    * `holds`. `#popups` holds no list, so every popup's nodes are evicted: a
    * stale claim would suppress a real change on its return (R5 makes it an
    * ordinary host).
    */
  private[runtime] def popupFill(
      renderer: Renderer,
      arriving: Option[String],
      shared: Shared,
      viewing: Viewing
  ): Option[Addressed] =
    arriving
      .flatMap(renderer.surfaces.popupNamed)
      .flatMap(renderer.renderSurfaceTraced(_, shared, viewing))
      .map(t =>
        Addressed(
          Patch.Insert(t.html, PatchMode.Inner, Dashboard.PopupHostId),
          t.claims,
          popupEvicts(renderer)
        )
      )

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
    val popup = popupFill(
      renderer,
      viewing.selections.popup.flatMap(renderer.surfaces.popupSpelling),
      shared,
      viewing
    )
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

  /** Every popup's nodes: what an open or a close evicts. */
  private[runtime] def popupEvicts(renderer: Renderer): Set[NodeId] =
    renderer.surfaces
      .surfacesAt(Dashboard.PopupHostId)
      .flatMap(renderer.surfaceNodeIds)

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

}
