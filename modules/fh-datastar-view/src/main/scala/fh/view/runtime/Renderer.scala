package fh.view.runtime

import api.homeassistant.TargetIds
import cats.effect.IO
import cats.syntax.all.*
import com.github.mustachejava.Mustache
import fh.view.build.LibPackage
import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}
import fh.view.history.Series
import fh.view.model.{
  Access,
  CardDef,
  Cell,
  Choice,
  ChromeColors,
  Dashboard,
  DomId,
  FetchTemplate,
  Fetching,
  IdKind,
  Ix,
  LayoutNode,
  MemberId,
  NodeId,
  Reads,
  Ready,
  SlotAsk,
  SlotKey,
  SlotRead,
  SlotShape,
  Step,
  Lit,
  SetId,
  SignalBind,
  SignalId,
  SlotValue,
  Surface,
  TreeIndex,
  CallDecl,
  CallField,
  CallId,
  ThemeClasses,
  VarValue
}
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*

/** What a node's patch form is rendered from, and so, beside the node's `Ix`,
  * the render cache's key and what a session's `holds` compares (ADR 0012):
  * each byte slot's value. The clause is not here: every clause of a candidate
  * mints the same ids, but each has its own `Ix`.
  */
private[runtime] final case class ByteValues(
    slots: Map[SlotKey, SlotValue]
) derives CanEqual

/** One node as a wholesale render left it: the byte-slot values its patch form
  * was rendered from (what [[Renderer.byteSlotValues]] answers for it) and the
  * signals its seed set.
  */
private[runtime] case class Painted(
    values: ByteValues,
    signals: Map[SignalId, SlotValue]
) {
  def held: Held = Held(Some(values), signals)
}

/** A walk's accumulator: what each own-rendering node painted, and the signals
  * a structural node's root seeded. Structure has no bytes of its own, but the
  * client holds its seed all the same; left out, a node's first frame after a
  * page load re-sent every value it already had.
  */
private[runtime] final class Trace {
  val own = new java.util.HashMap[Ix, Painted]()
  val seeded = new java.util.HashMap[Ix, Map[SignalId, SlotValue]]()
  // Each live hole's host, with the root of what the walk put in it.
  val shown = new java.util.HashMap[Ix, IArray[Ix]]()
  def holds: Map[Ix, Held] =
    Trace.held(own.asScala, seeded.asScala, shown.asScala)
}

private[runtime] object Trace {

  /** A host seeds signals and shows roots in one entry. */
  def held(
      painted: collection.Map[Ix, Painted],
      seeded: collection.Map[Ix, Map[SignalId, SlotValue]],
      shown: collection.Map[Ix, IArray[Ix]]
  ): Map[Ix, Held] = {
    val nodes =
      painted.view.map { case (ix, p) => ix -> p.held }.toMap ++
        seeded.view.map { case (ix, s) => ix -> Held(signals = s) }
    shown.foldLeft(nodes) { case (acc, (ix, roots)) =>
      acc.updated(ix, acc.getOrElse(ix, Held()).copy(shows = Some(roots)))
    }
  }
}

/** A host's parts, the root of each, and what each node inside them now holds:
  * a part per set member, or a state group's branch as one walk under a root
  * with no rendering of its own ([[Renderer.Traced]]).
  */
private[runtime] case class HostContent(
    parts: List[(NodeId, String)],
    shown: IArray[Ix],
    claims: Map[Ix, Held]
)

/** A node with a rendering of its own, as `shared` has it: where it is in the
  * tree and what its patch form is rendered from.
  */
private[runtime] final class Own(val ix: Ix, val values: ByteValues)

/** A signal slot's two renderings (ADR 0017). `Document` has the value inline
  * plus its `data-signals` seed, so a first paint or fill needs no frame.
  * `Patch` has neither, so a node's byte-slot values do not move when only a
  * signal slot does and one signal frame carries the value — the point of the
  * feature. Without a signal slot both forms are the same `String`, which
  * [[Renderer.Traced]] relies on.
  */
private[runtime] enum SlotForm derives CanEqual {
  case Document, Patch

  def isPatch: Boolean = this == SlotForm.Patch
}

/** Per render, never cached on the renderer ([[VarGraph.env]]). */
type VarEnv = Map[NodeId, Map[String, String]]

/** Container kinds are templates splicing `children`, not cases here. A popup
  * is a separate tree whose ids are namespaced (`s_<id>__…`); a choice's case
  * is a node of the tree holding it.
  */
class Renderer(validated: Dashboard.Validated) {

  private val dashboard = validated.ready
  private val templates = validated.templates
  // Here, not beside the renderer, so a reload swaps rule and dashboard as one
  // value.
  val access: Access = validated.access

  val themeClasses: ThemeClasses = dashboard.theme.classes
  // A cell's classes are fixed, so each is swapped once per renderer rather
  // than per paint: `expand` allocates for every class the theme leaves alone.
  private val cellClassTexts =
    new java.util.concurrent.ConcurrentHashMap[String, String]()
  private val expandCell: java.util.function.Function[String, String] =
    cls => themeClasses.expand(cls).map(" " + _).mkString

  /** One tree's static nodes, the body's or a popup's, under their ids, a
    * choice's every case included; a set's members are its own. Each is in its
    * container ([[TreeIndex.container]]).
    */
  private class Index(root: Ready.Node) {

    private val walked: List[(NodeId, Ready.Node, Option[NodeId], Ix)] = {
      def walk(
          node: Ready.Node,
          parent: Option[NodeId]
      ): List[(NodeId, Ready.Node, Option[NodeId], Ix)] =
        (node.id, node, parent, dashboard.index.container(node.ix)) ::
          (node match {
            case c: Ready.Component =>
              c.content match
                case Ready.Content.Live(_, _: Ready.LiveFill.Set) => Nil
                case _ => c.allChildren.flatMap(walk(_, Some(c.id)))
          })
      walk(root, None)
    }

    val indexed: Map[NodeId, Ready.Node] =
      walked.map((id, n, _, _) => id -> n).toMap

    /** Each container's nodes, in id order. */
    val inContainer: Map[Ix, List[NodeId]] =
      walked
        .groupMap(_._4)(_._1)
        .view
        .mapValues(_.sorted)
        .toMap

    /** container -> entity -> its nodes binding it. */
    val byEntity: Map[Ix, Map[String, Set[NodeId]]] =
      walked
        .collect { case (id, c: Ready.Component, _, in) =>
          c.liveEntities.map(e => (in, e, id))
        }
        .flatten
        .groupMap(_._1)(t => t._2 -> t._3)
        .view
        .mapValues(_.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap)
        .toMap
  }

  private val mainIndex = new Index(dashboard.card)

  /** The head's unpatchable part — linked stylesheets, scripts, `chrome` — so a
    * mismatch reloads the page (ADR 0011); the patchable rest is [[styleHash]].
    * Not the whole dashboard: a body edit would reload for nothing. Stable
    * across restarts, so an add-on restart does not refresh every browser —
    * which is why it cannot double as `logId`.
    */
  val headHash: String = Renderer.headFingerprint(dashboard)

  /** Tokens, inline CSS ([[themeStyleTag]]) and `<title>`: a mismatch costs two
    * element patches, not a reload.
    */
  val styleHash: String = Renderer.styleFingerprint(dashboard)

  val warnings: List[String] = dashboard.warnings

  /** `reactive: false` slot values by `(entityId, transform)`: they read only
    * identity, so they never change for this renderer's life. A racing fill
    * computes the same value. Typed, or a `bool` slot's `false` reaches a
    * section as the truthy string `"false"`.
    */
  private val identityCache =
    new java.util.concurrent.ConcurrentHashMap[(String, String), SlotValue]()

  // Its marks are read only once the graphs are built.
  private val decided =
    Decided(Shared.Marked(gid => surfaces.marked(gid), markedGates))

  /** `store`'s version: what every reader of it decides from. */
  def shared(store: StoreState): Shared = decided.at(store)

  /** The recorder's step: `frame`'s version, carrying what `prev` decided that
    * the frame's changes left alone, at the values `live` sessions hold.
    */
  def advance(
      prev: Shared,
      frame: Frame,
      live: Set[Map[VarKey, String]] = Set.empty
  ): Shared =
    decided.advance(prev, frame, dirtied(frame.changes), liveChange(live))

  // The last live values carried at: an unchanged set prunes nothing.
  @volatile private var lastLive: Set[Map[VarKey, String]] = Set.empty

  private def liveChange(live: Set[Map[VarKey, String]]): Shared.Live =
    if (live == lastLive) Shared.Live.Unchanged
    else {
      lastLive = live
      Shared.Live.Now(live.toList.map(vars.env))
    }

  private def dirtied(changes: List[StateChange]): Shared.Dirty = {
    val (sets, memberIds) = members.dirtied(changes)
    Shared.Dirty(
      sets,
      memberIds,
      surfaces.dirtied(changes),
      changes.iterator
        .flatMap(c => gatesReading.getOrElse(c.entityId, Set.empty))
        .toSet
    )
  }

  /** entity -> the `valueWhile`s it marks dirty: one decided over it, or
    * reading it.
    */
  private lazy val gatesReading: Map[String, Set[String]] = {
    def components(n: Ready.Node): Iterator[Ready.Component] = n match
      case c: Ready.Component =>
        Iterator(c) ++ (c.content match
          case Ready.Content.Live(_, Ready.LiveFill.Set(s)) =>
            s.members.values.iterator.flatMap(_.nodes)
          case _ => c.allChildren.iterator
        ).flatMap(components)
    (components(dashboard.card) ++
      dashboard.surfaces.values.iterator.flatMap(s => components(s.content)))
      .flatMap(_.slots.values.flatMap(_.gate))
      .flatMap(g =>
        (g.entity.toList ++ g.valueWhile.bound.valueEntities).map(_ -> g.key)
      )
      .toList
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap
  }

  private lazy val markedGates: Set[String] = gatesReading.values.flatten.toSet

  private val indexes: List[Index] =
    mainIndex :: dashboard.surfaces.values.toList.map(s => new Index(s.content))

  private val allIndexed: Map[NodeId, Ready.Node] =
    indexes.flatMap(_.indexed).toMap

  /** Each container's static nodes, in id order: a popup's or a case's own, not
    * those of the containers inside it.
    */
  private val containerNodes: Map[Ix, List[NodeId]] =
    indexes.flatMap(_.inContainer).toMap

  /** container -> entity -> its static nodes binding it. */
  private val containerEntities: Map[Ix, Map[String, Set[NodeId]]] =
    indexes.flatMap(_.byEntity).toMap

  /** Lazy: the member graph, the ancestry and the surface graph are built
    * below.
    */
  private[runtime] lazy val vars: VarGraph =
    new VarGraph(
      // A group inside a member's clause has an id of its own, and sees its
      // host's scope.
      scopes = {
        val byNode = dashboard.varScopes.filter(_._2.nonEmpty)
        byNode ++ surfaces.varPicks.flatMap(gid =>
          byNode.get(surfaces.hostOf(gid)).map(gid -> _)
        )
      },
      panelsSelected = surfaces.varPicks.toList
        .flatMap(gid => surfaces.varSelecting(gid).map(gid -> _))
        .toMap,
      readsAt = id =>
        fetchesForNode(id).flatMap(_.fetch.variables).toSet ++
          variablesForNode(id)
    )

  /** Where a session opening on `states` opens each variable with an initial
    * transform: its result, where every reader takes it. Otherwise the default
    * stands, as for a stale link.
    */
  private lazy val initialPrograms: List[(VarKey, String, Cel.Program)] =
    vars.initialTransforms.toList.map((key, t) =>
      (key, t.entityId, dashboard.initials(t.celKey))
    )

  def initialValuesFor(
      states: Map[String, EntityState]
  ): Map[VarKey, String] =
    initialPrograms.flatMap { (key, entityId, program) =>
      states
        .get(entityId)
        .map(Cel.run(program, _, dashboard.slug))
        .filter(v =>
          VarValue.of(v).isDefined && refusals(Map(key -> v)).isEmpty
        )
        .map(key -> _)
    }.toMap

  /** One line per refused choice: every fetch reading the variable must find a
    * case for the value, and every panel it selects must have a member for it.
    */
  def refusals(choices: Map[VarKey, String]): List[String] = {
    val env = vars.env(choices)
    choices.toList.flatMap { (key, value) =>
      val why = (vars
        .readersOf(key)
        .flatMap(id =>
          fetchesForNode(id)
            .flatMap(a => refusedAt(a.fetch, env.getOrElse(id, Map.empty)))
        ) ++
        vars.panelsSelectedBy(key).flatMap { gid =>
          Option.unless(surfaces.memberAt(gid, value).isDefined)(
            s"$gid takes ${Surface.membersText(surfaces.memberValues(gid))}"
          )
        }).distinct
      Option.when(why.nonEmpty)(
        s"'$value' is not a value '${key.name}' can take: ${why.mkString("; ")}"
      )
    }
  }

  /** A value no case takes, with no `otherwise`: decided over the viewer's
    * values alone, so a `by` that reads state is not refused for it.
    */
  private def refusedAt(
      f: FetchTemplate,
      vars: Map[String, String]
  ): Option[String] = f.window match
    case c @ Choice.Chosen(by, cases, _) if c.refusesUntaken =>
      val read = dashboard.answer(Map.empty, Evaluator.Lookup.of(vars))(by)
      Option.unless(c.takes(read))(
        s"'${Steps.text(read)}' is not one of " +
          cases.map(k => Steps.text(k.is.raw)).sorted.mkString(", ")
      )
    case _ => None

  /** An ask at a node: its window's `by` over `states` and the node's
    * variables, none where it decides no case.
    */
  private def readOf(
      ask: SlotAsk,
      states: Map[String, EntityState],
      vars: Map[String, String]
  ): Option[SlotRead] = ask.fetch.window match
    case Choice.Fixed(w) =>
      Some(SlotRead(FetchRequest.History(ask.fetch.entity, w), ask.stage))
    case _ => ask.resolve(dashboard.answer(states, Evaluator.Lookup.of(vars)))

  /** `offered` on top of `kept`, each under [[refusals]] on its own, so one
    * stale value costs only its own variable. In key order, so which of two
    * clashing values wins does not depend on a map's.
    */
  def adopt(
      kept: Map[VarKey, String],
      offered: Map[VarKey, String]
  ): (Map[VarKey, String], List[String]) =
    offered.toList
      .sortBy((key, _) => (key.declarer, key.name))
      .foldLeft((kept, List.empty[String])) {
        case ((acc, refused), (key, value)) =>
          refusals(acc + (key -> value)) match {
            case Nil  => (acc + (key -> value), refused)
            case more => (acc, refused ++ more)
          }
      }

  /** `s` with its values as this renderer takes them, checked once per
    * renderer: a dashboard edit can refuse a value the old one took (a removed
    * tab), and a refused or no longer declared value falls back to its
    * declaration, as a stale link does. The refusals, to log.
    */
  def recheck(s: SessionState): (SessionState, List[String]) =
    if (s.checkedBy.exists(_ eq generation)) (s, Nil)
    else
      def kept(m: Map[VarKey, String]) =
        adopt(Map.empty, m.filter((k, _) => vars.defaults.contains(k)))
      val (initial, early) = kept(s.initialValues)
      val (chosen, late) = kept(s.vars)
      (
        s.copy(
          initialValues = initial,
          vars = chosen,
          checkedBy = Some(generation)
        ),
        early ++ late
      )

  /** `walk` until it reaches no decision whose fetches are unanswered: each
    * round answers what the last one found it needs, in one batch, and the
    * decisions made are kept, so the next walk only reads them. A walk reads
    * each [[Shared]] it is given through [[Shared.asking]] its run.
    */
  def decide[A](answer: Set[SlotRead] => IO[FetchSnapshot])(
      walk: Shared.Run => A
  ): IO[A] = {
    def round(have: FetchSnapshot): IO[A] =
      IO {
        val run = Shared.Run(have)
        val a = walk(run)
        if (run.needs.isEmpty) run.close()
        a -> run.needs
      }.flatMap { (a, needs) =>
        if (needs.isEmpty) IO.pure(a)
        else
          answer(needs).flatMap { more =>
            val next = have ++ more
            // Or the next round asks again, for ever.
            if (needs.forall(next.has)) round(next)
            else
              IO.raiseError(
                fh.view.FHError
                  .internal("a decision's fetches came back unanswered")
              )
          }
      }
    round(FetchSnapshot.empty)
  }

  /** `id` as it stands now: a set member is its current clause. */
  def componentAt(id: NodeId, shared: Shared): Option[Ready.Component] =
    nodeAt(id, shared).collect { case c: Ready.Component => c }

  /** `call` run now, over `shared` with `posted` in hand: every field, and each
    * field `node` sees as its slot would show it. `Left` names a field that
    * gives no value.
    */
  def press(
      call: Ready.ServiceCall,
      seen: List[(CallField, String)],
      node: Ready.Component,
      shared: Shared,
      posted: Map[String, Any]
  ): Either[String, Renderer.Pressed] =
    shared
      .answered(call.reads)
      .toRight("its fetches are not answered")
      .flatMap { answers =>
        val values = posted
        val in = new Evaluator.Lookup:
          def fetched(f: FetchTemplate): Object = answers.fetched(f)
          def variable(name: String): String = answers.variable(name)
          override def posted(key: String): Any = values.getOrElse(key, null)
        def run(r: Evaluator.Run): Any = r(null, shared.states, in)
        def targetIds(m: ListMap[IdKind, List[Evaluator.Run]]) =
          def ids(k: IdKind) =
            m.getOrElse(k, Nil).flatMap(r => Renderer.ids(run(r)))
          TargetIds(ids(IdKind.Entity), ids(IdKind.Area), ids(IdKind.Floor))
        def valued(field: String, r: Evaluator.Run): Either[String, Any] =
          Option(run(r)).toRight(s"$field gives no value")
        for
          service <- valued("service", call.service)
          data <- call.data.toList
            .traverse((k, r) => valued(s"data.$k", r).map(k -> _))
        yield Renderer.Pressed(
          Steps.text(service),
          targetIds(call.target),
          targetIds(call.exclude),
          ListMap.from(data),
          seen.map((field, slot) =>
            field -> slotValueOf(node.slots(slot), run(node.slots(slot).run))
          )
        )
      }

  /** A target has its own rendering ([[ownRenderingOf]]), so its descendants
    * are not asked, except a member's: a member is placed whole.
    */
  def readsForPull(
      targets: List[NodeId],
      hosts: List[NodeId],
      shared: Shared,
      selections: Selections,
      env: VarEnv
  ): List[SlotRead] =
    ask(
      targets.flatMap(presentAt(_, shared)) ++
        hosts.flatMap { gid =>
          members.setContainer(gid) match {
            case Some(setId) => setAsks(setId, shared)
            case None        =>
              pickedCase(gid, shared)
                .flatMap(surfaces.cases(gid).lift)
                .toList
                .flatMap(surfaceAsks(_, shared, selections))
          }
        },
      shared,
      env
    )

  /** Every ask whose slot has a value now, at this viewer's values. */
  private def ask(
      asks: List[(NodeId, Fetching)],
      shared: Shared,
      env: VarEnv
  ): List[SlotRead] =
    asks.flatMap { (id, f) =>
      if (f.gate.forall(gateHolds(_, shared)))
        f.asks.flatMap(readOf(_, shared.states, env.getOrElse(id, Map.empty)))
      else Nil
    }.distinct

  /** Whether a `valueWhile` holds, decided once per snapshot by the pre-read,
    * the render key and the paint alike; only a real `true` holds.
    */
  private def gateHolds(g: Fetching.Gate, shared: Shared): Boolean =
    shared.holds(g.key, g.reads) { f =>
      resolveStateSlotValue(
        g.entity,
        gateRuns.computeIfAbsent(g.key, _ => live(g.valueWhile)),
        shared,
        f
      ) match
        case b: Boolean => b
        case _          => false
    }

  /** What renders at `id` now: a static node's own, a member's whole subtree in
    * its current clause, a node below one in that clause, and nothing for a
    * member that is not present.
    */
  private def presentAt(
      id: NodeId,
      shared: Shared
  ): List[(NodeId, Fetching)] =
    allIndexed.get(id) match {
      case Some(c: Ready.Component) => c.fetching.map(id -> _)
      case None                     =>
        members.nodeAt(id, shared) match {
          case Some((m, _)) if m.id == id => memberAsks(m, shared)
          case Some((_, c))               => c.fetching.map(id -> _)
          case None                       => Nil
        }
    }

  /** A present member's nodes', and its nested sets' present members'. */
  private def memberAsks(
      m: Member,
      shared: Shared
  ): List[(NodeId, Fetching)] =
    m.fetching ++ members.setsIn(m).flatMap(setAsks(_, shared))

  private def setAsks(
      gid: SetId,
      shared: Shared
  ): List[(NodeId, Fetching)] =
    members.membersOf(gid, shared).toList.flatMap(memberAsks(_, shared))

  /** Everything a member's subtree can ask, every clause's: the bound a
    * variable is held to, not what a render reads.
    */
  private lazy val everyClauseAsks: Map[NodeId, List[SlotAsk]] =
    members.memberIds.toList.flatMap { m =>
      val asks = members.clauseNodesOf(m).flatMap(Renderer.subtreeAsks).distinct
      Option.when(asks.nonEmpty)(m -> asks)
    }.toMap

  /** Test seam: the press of every call a node outside a set places, for a test
    * to post one the page has not opened.
    */
  private[runtime] def presses(slug: String): List[(CallDecl, String)] =
    allIndexed.toList.sortBy(_._1).flatMap { case (id, c: Ready.Component) =>
      c.calls.values.toList.map(p =>
        dashboard.calls(p.id).decl -> s"sse/call/$slug/${p.id}?node=$id"
      )
    }

  /** Test seam: the press of the choice of `value` for `key` (`None` clears
    * it), as the first node placing it over `shared` posts it, a set member's
    * current clause included.
    */
  private[runtime] def choicePress(
      slug: String,
      key: VarKey,
      value: Option[VarValue],
      shared: Shared
  ): Option[String] = {
    val id = CallDecl.SetVar(key.name, value).id
    vars.nodes
      .sortBy(n => n: String)
      .find(n =>
        vars.keyAt(n, key.name).contains(key) &&
          componentAt(n, shared).exists(_.calls.values.exists(_.id == id))
      )
      .map(n => s"sse/call/$slug/$id?node=$n")
  }

  /** Every read a node can make, whatever its `valueWhile`s decide, over no
    * state.
    */
  private[runtime] def readsAt(id: NodeId, env: VarEnv): List[SlotRead] =
    fetchesForNode(id).flatMap(
      readOf(_, Map.empty, env.getOrElse(id, Map.empty))
    )

  /** A node of the static tree, or a member or a node below one as the member's
    * current clause has it.
    */
  private[runtime] def nodeAt(id: NodeId, shared: Shared): Option[Ready.Node] =
    allIndexed.get(id).orElse(members.nodeAt(id, shared).map(_._2))

  /** Where `id` sits as `shared` shows it. A set nested in a member is no node
    * of the member, but its id carries the clause, so it has the one place.
    */
  private[runtime] def ixAt(id: NodeId, shared: Shared): Option[Ix] =
    nodeAt(id, shared)
      .map(_.ix)
      .orElse(
        if members.setContainer(id).isDefined then
          dashboard.index.ixsOf(id).headOption
        else None
      )

  /** The dashboard's tree, which a session's `holds` is keyed into. */
  private[runtime] def index: TreeIndex = dashboard.index

  /** Every node spelled any of `ids`: a static node has one. */
  private[runtime] def ixsOf(ids: Iterable[NodeId]): Set[Ix] =
    ids.iterator.flatMap(dashboard.index.ixsOf).toSet

  /** Presence and order in every candidate set. Exposed rather than exported,
    * so a call site says `renderer.members.…` and never reads as though the
    * renderer decided membership.
    */
  private[runtime] val members: MemberGraph = new MemberGraph(
    allIndexed.collect(Function.unlift { case (id, c: Ready.Component) =>
      c.content match
        case Ready.Content.Live(_, Ready.LiveFill.Set(s)) => Some(id -> s)
        case _                                            => None
    }),
    dashboard.index
  )

  /** Selection and visibility, exposed on the same terms as [[members]]. */
  private[runtime] val surfaces: SurfaceGraph =
    new SurfaceGraph(
      dashboard.index,
      dashboard.surfaces,
      dashboard.picks,
      members
    )

  /** This renderer's identity, which a session's `holds` and a [[RenderCache]]
    * entry are stamped with. Not the renderer itself: either would pin a whole
    * replaced dashboard.
    */
  private[runtime] val generation: Renderer.Stamp =
    Renderer.Stamp(headHash, styleHash, surfaces.popupBySpelling)

  /** The page's own static nodes, in no popup or case: which members bind an
    * entity depends on a version's membership ([[MemberGraph.binding]]).
    */
  def componentsFor(entityId: String): Set[NodeId] =
    surfaceComponentsFor(surfaces.pageRoot, entityId)

  /** Empty for a candidate set (its members have ids of their own). */
  def entitiesForNode(
      id: NodeId,
      shared: Shared
  ): List[String] =
    nodeAt(id, shared) match {
      case Some(c: Ready.Component) => c.liveEntities
      case _                        => Nil
    }

  /** A member's are its whole subtree's ([[everyClauseAsks]]); a node below one
    * asks its own in every clause.
    */
  private def fetchesForNode(id: NodeId): List[SlotAsk] =
    allIndexed.get(id) match {
      case Some(c: Ready.Component) =>
        c.fetches
      case _ =>
        everyClauseAsks.getOrElse(id, belowMemberAsks.getOrElse(id, Nil))
    }

  // Built once: a variable's bound asks it of every reader.
  private lazy val belowMemberAsks: Map[NodeId, List[SlotAsk]] =
    members.memberIdsBelow.flatMap { id =>
      val asks = members.clauseNodesAt(id).flatMap(_.fetches).distinct
      Option.when(asks.nonEmpty)(id -> asks)
    }.toMap

  /** The variables a node's own slots read, a member's whole subtree's in every
    * clause, as [[fetchesForNode]] asks.
    */
  private def variablesForNode(id: NodeId): Set[String] =
    allIndexed.get(id) match {
      case Some(c: Ready.Component) => c.variables
      case _                        =>
        variablesBelow.getOrElse(id, Set.empty)
    }

  private lazy val variablesBelow: Map[NodeId, Set[String]] =
    (members.memberIds.toList.map(m =>
      m -> members.clauseNodesOf(m).flatMap(Renderer.subtreeVariables).toSet
    ) ++ members.memberIdsBelow.map(id =>
      id -> members.clauseNodesAt(id).flatMap(_.variables).toSet
    )).filter(_._2.nonEmpty).toMap

  /** The bound a variable write is held to (ADR 0023). The model's static walk,
    * not [[Index.byEntity]], which stops at a set: this must answer for
    * entities nothing renders yet.
    */
  def references(entityId: String): Boolean =
    dashboard.referencedEntities.contains(entityId)

  def callOf(id: CallId): Option[Ready.Call] = dashboard.calls.get(id)

  /** Wider than [[references]] — see [[Dashboard.watchedEntities]]. */
  def watchedEntities: Set[String] = dashboard.watchedEntities

  /** The containers, popups and cases, whose static nodes bind `entityId`: the
    * candidates a tick asks [[SurfaceGraph.visibleContainer]] of, rather than
    * every shown one.
    */
  def surfacesBinding(entityId: String): List[Ix] =
    surfacesByEntity.getOrElse(entityId, Nil)

  private lazy val surfacesByEntity: Map[String, List[Ix]] =
    containerEntities.toList
      .filter(_._1 != surfaces.pageRoot)
      .flatMap((c, byEntity) => byEntity.keys.map(_ -> c))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.sortBy(_.toInt))
      .toMap

  /** A container's own static nodes binding `entityId`. */
  def surfaceComponentsFor(container: Ix, entityId: String): Set[NodeId] =
    containerEntities
      .get(container)
      .fold(Set.empty)(_.getOrElse(entityId, Set.empty))

  def surface(surfaceId: String): Option[Ready.Surface] =
    dashboard.surfaces.get(surfaceId)

  def stylesheets: List[String] = dashboard.theme.stylesheets

  def offlineBanners: String = dashboard.theme.offlineBanners

  def deferredStylesheets: List[String] = dashboard.theme.deferredStylesheets

  val themeColorTags: String = Renderer.themeColorTags(dashboard)

  val chromeColors: Option[ChromeColors] = ChromeColors.from(dashboard.theme)

  def scripts: List[String] = dashboard.theme.scripts

  /** Cards first, as in the cascade [[themeStyleTag]] writes. */
  def inlineScripts: List[String] =
    dashboard.cardScripts ++ dashboard.theme.inlineScripts

  def title: Option[String] = dashboard.title

  /** Tokens, then `dashboard.css`, card css, theme styles: document order is
    * the cascade (ADR 0020). Outside `#dashboard` because it is most of a
    * repaint's bytes (7.7 of 9.6 KB on a small demo); it is patched by id only
    * when [[styleHash]] moves.
    */
  val themeStyleTag: String = {
    val theme = dashboard.theme
    def vars(tokens: Map[String, String]): String =
      tokens.toList
        .sortBy(_._1)
        .map { case (name, value) => s"--$name:$value;" }
        .mkString

    val parts = List(
      if (theme.tokens.isEmpty) ""
      else s":root{color-scheme:light dark;${vars(theme.tokens)}}",
      if (theme.tokensDark.isEmpty) ""
      else
        s"@media (prefers-color-scheme:dark){:root{${vars(theme.tokensDark)}}}",
      dashboard.css,
      dashboard.cardCss,
      theme.styles
    ).filter(_.nonEmpty)

    // Always the element, even empty: a later head patch needs a target.
    parts.mkString(
      s"""<style id="${Renderer.ThemeStyleId}">""",
      "",
      "</style>"
    )
  }

  private val chromeTemplate: Mustache = {
    val chrome =
      if (dashboard.theme.chrome.nonEmpty) dashboard.theme.chrome
      else """<main class="container" id="dashboard">{{{body}}}</main>"""
    Templates.compile("chrome", chrome)
  }

  /** What a repaint `inner`-patches into `#dashboard`, with the per-node trace
    * of everything baked into it.
    */
  private[runtime] def renderBodyTraced(
      shared: Shared,
      viewing: Viewing
  ): Traced =
    traced(dashboard.card, shared, viewing)

  private[runtime] def pageBytesHint: Int =
    themeStyleTag.length + Renderer.NodeBytesHint * nodeCount(dashboard.card) +
      4096

  /** The document path: the page walked straight into `out`, returning the
    * per-node trace. A restored popup is baked into the chrome's `popups` hole,
    * so a refresh does not paint the dialog late.
    */
  private[runtime] def renderPageInto(
      out: Sink,
      shared: Shared,
      viewing: Viewing
  ): Map[NodeId, Painted] =
    pageInto(out, shared, viewing).own.asScala.map { (ix, p) =>
      dashboard.index.id(ix) -> p
    }.toMap

  /** [[renderPageInto]], returning what the client now holds, structure's seeds
    * included: what a page load commits as the session's `holds`.
    */
  private[runtime] def renderPageHolds(
      out: Sink,
      shared: Shared,
      viewing: Viewing
  ): Map[Ix, Held] =
    pageInto(out, shared, viewing).holds

  private[runtime] def pageInto(
      out: Sink,
      shared: Shared,
      viewing: Viewing
  ): Trace = {
    val own = new Trace
    // Writer holes, not Strings: building them first cost a full copy of the
    // document each (128 kB of a page open's ~3.8 MB).
    val root = dashboard.card
    val bodyInto: java.io.Writer => Unit = _ =>
      tracedInto(out, root, shared, viewing, own)
    val dialogInto: Option[java.io.Writer => Unit] =
      viewing.selections.popup.flatMap(sid =>
        surfaceWalk(out, sid, shared, viewing, own)
      )
    // Not mustache's `execute(ctx)`, whose `StringWriter` grows from 16 chars
    // and copies the page twice more.
    val _ = out.append(themeStyleTag)
    val scope = new Renderer.PageScope(
      Map("body" -> bodyInto) ++ dialogInto.map("popups" -> _)
    )
    Templates.run(chromeTemplate, out, scope)
    own
  }

  /** [[renderSurfaceTraced]] as a writer hole into the page's buffer. */
  private def surfaceWalk(
      out: Sink,
      surfaceId: String,
      shared: Shared,
      viewing: Viewing,
      trace: Trace
  ): Option[java.io.Writer => Unit] =
    // The writer is ignored: mustache's writer is `out`.
    dashboard.surfaces.get(surfaceId).map { sfc => (_: java.io.Writer) =>
      tracedInto(out, sfc.content, shared, viewing, trace)
    }

  /** Bare content: the host and any frame live in `theme.chrome`. */
  private[runtime] def renderSurfaceTraced(
      surfaceId: String,
      shared: Shared,
      viewing: Viewing
  ): Option[Traced] =
    dashboard.surfaces
      .get(surfaceId)
      .map(s => traced(s.content, shared, viewing))

  /** Every log key must resolve here: a resume re-renders. `None` means the key
    * names nothing that exists now, and is dropped rather than failing the
    * resume.
    */
  def renderNodeById(
      id: NodeId,
      shared: Shared,
      viewing: Viewing,
      form: SlotForm = SlotForm.Patch
  ): Option[String] =
    nodeAt(id, shared)
      .filter(ownRenderingOf)
      .flatMap(render(_, shared, viewing, form))

  /** Whether a member is its own patch target in every clause: a leaf card. A
    * member that can hold nodes is patched through them, and placed whole.
    */
  def memberIsTarget(id: NodeId): Boolean =
    members.clauseNodesAt(id).forall(ownRenderingOf)

  /** A member's whole element in document form, with what it claims for the
    * record: what an arrival or a clause switch inserts. Per viewer, since a
    * member holding regions can show a viewer's choice.
    */
  def renderMemberTraced(
      id: NodeId,
      shared: Shared,
      viewing: Viewing
  ): Option[Traced] =
    members
      .memberAt(id, shared)
      .map(memberTraced(_, shared, viewing))

  private def memberTraced(
      m: Member,
      shared: Shared,
      viewing: Viewing
  ): Traced =
    traced(m.node, shared, viewing)

  /** What a container's own static nodes ask, and its sets' present members: a
    * nested container's are asked as it is shown.
    */
  private def asksOfSurface(
      container: Ix,
      shared: Shared
  ): List[(NodeId, Fetching)] =
    val ids = containerNodes.getOrElse(container, Nil)
    ids.flatMap(id =>
      allIndexed.get(id).toList.flatMap { case c: Ready.Component =>
        c.fetching.map(id -> _)
      }
    ) ++ ids.flatMap(
      members.setContainer(_).toList.flatMap(setAsks(_, shared))
    )

  /** The surfaces shown inside it included. */
  def fetchesForSurface(
      surfaceId: String,
      shared: Shared,
      selections: Selections,
      env: VarEnv
  ): List[SlotRead] =
    ask(surfaceAsks(surfaceId, shared, selections), shared, env)

  private def surfaceAsks(
      surfaceId: String,
      shared: Shared,
      selections: Selections
  ): List[(NodeId, Fetching)] =
    surfaces
      .shownWithin(surfaceId, shared, selections)
      .toList
      .flatMap(i => asksOfSurface(Ix(i), shared))

  /** The body, this viewer's open surfaces and each state group's branch. An
    * unselected tab is fetched when switched to.
    */
  def fetchesForPage(
      open: Set[String],
      shared: Shared,
      env: VarEnv
  ): List[SlotRead] =
    ask(
      asksOfSurface(surfaces.pageRoot, shared) ++
        surfaces
          .shownSurfaces(surfaces.screen(open, shared), shared)
          .toList
          .flatMap(i => asksOfSurface(Ix(i), shared)),
      shared,
      env
    )

  /** The resume's second candidate set: nothing may have recorded an open
    * surface while nobody viewed it, so only re-rendering can tell.
    */
  def containerNodeIds(container: Ix): List[NodeId] =
    containerNodes.getOrElse(container, Nil)

  /** [[containerNodeIds]] of every container `spelled` names. */
  def surfaceNodeIds(spelled: String): Set[NodeId] =
    surfaces.containersSpelled(spelled).flatMap(containerNodeIds).toSet

  /** A fill of either container: a set's members, in DOM order, or a state
    * group's branch.
    */
  private[runtime] def renderHost(
      container: NodeId,
      shared: Shared,
      viewing: Viewing
  ): HostContent =
    members.setContainer(container) match {
      case Some(setId) =>
        val traced = members
          .membersOf(setId, shared)
          .toList
          .map(m => m.id -> memberTraced(m, shared, viewing))
        HostContent(
          traced.map((id, t) => id -> t.html),
          IArray.from(traced.map(_._2.root)),
          traced.flatMap(_._2.claims).toMap
        )
      case None =>
        pickedCase(container, shared)
          .flatMap(renderCaseTraced(container, _, shared, viewing))
          .fold(HostContent(Nil, IArray.empty, Map.empty))((id, t) =>
            HostContent(List(id -> t.html), IArray(t.root), t.claims)
          )
    }

  /** The node a pick's or a set's patches go into, as `shared` has it where it
    * is inside a member.
    */
  private[runtime] def hostIx(container: NodeId, shared: Shared): Ix = {
    val host = surfaces.hostOf(container)
    nodeAt(host, shared).fold(index.ixsOf(host).head)(_.ix)
  }

  /** What `gid` shows as `shared` decides for its viewer, each key the root of
    * a shown member or case: what its host's list is planned against.
    */
  private[runtime] def decidedKeys(
      gid: NodeId,
      shared: Shared
  ): IArray[Ix] =
    members.setContainer(gid) match
      case Some(setId) => members.keysOf(setId, shared)
      case None        =>
        pickedCase(gid, shared)
          .flatMap(i =>
            dashboard.picks.get(gid).flatMap(_.choice.cases.lift(i))
          )
          .fold(IArray.empty[Ix])(c => IArray(c.ix))

  /** The sets and picks a logged or static node hosts: a set by its own id, a
    * pick in a member's clause only while the member is in that clause.
    */
  private[runtime] def hostsAt(id: NodeId, shared: Shared): List[NodeId] =
    members.setContainer(id) match
      case Some(setId) => List(setId)
      case None => surfaces.picksAt(id).filter(surfaces.groupShown(_, shared))

  /** A key's root is patched by its own bytes, or only ever sent whole. */
  private[runtime] def hasOwnRendering(key: Ix): Boolean =
    !dashboard.cards.get(index.node(key).card).exists(_.isStructure)

  /** Case `i` of the pick `gid`, under its node id, as the host's walk would
    * place it.
    */
  private[runtime] def renderCaseTraced(
      gid: NodeId,
      i: Int,
      shared: Shared,
      viewing: Viewing
  ): Option[(NodeId, Traced)] =
    dashboard.picks
      .get(gid)
      .flatMap(_.choice.cases.lift(i))
      .map(node => node.id -> traced(node, shared, viewing))

  /** One derivation for both id sources (the static tree and set members), so a
    * var added to [[Dashboard.structuralVars]] reaches both.
    */
  private def structuralVars(id: NodeId): Map[String, String] =
    Dashboard.structuralVars(id, dashboard.slug)

  /** Whether a node may be a log key or a patch target: decided by its card
    * alone. Structure's element contains its regions, and a set's members are
    * its card's one region, so patching it would re-send them. It keeps its
    * [[elementId]] for structural patches. `Dashboard.validate` enforces the
    * same rule when it rejects a live bytes slot on structure.
    */
  private def ownRenderingOf(node: Ready.Node): Boolean = node match {
    case c: Ready.Component =>
      !dashboard.cards.get(c.card).exists(_.isStructure)
  }

  /** The node's `.fh-cell` root: the one crossing from node id to DOM id. */
  def elementId(id: NodeId): DomId = DomId.derived(id)

  /** The element a pick's case or a set's members are patched into: its node's
    * root, the live fill being its card's whole template.
    */
  def hostId(gid: NodeId): DomId = elementId(surfaces.hostOf(gid))

  /** The index of the case `gid`'s pick shows, rendered lazily when the
    * template reaches the slot's hole. `None` for a node owning no pick, or one
    * showing nothing: the hole renders empty.
    */
  private def pickedCase(gid: NodeId, shared: Shared): Option[Int] =
    if (surfaces.cases(gid).isEmpty) None
    else if (surfaces.isStatePick(gid)) surfaces.decideByState(gid, shared)
    else surfaces.decideByVariable(gid, shared)

  /** What a node's patch form is rendered from ([[ByteValues]]). A node's other
    * inputs are fixed per renderer (template, constants, `id`,
    * `dashboardSlug`), and a variable or a fetch reaches the bytes only through
    * a slot. Signal slots are left out: the patch form does not carry their
    * values (ADR 0017).
    *
    * Values, not their text: a section reads `false` and `"false"` differently.
    *
    * `None`: the node has no rendering of its own. A pick's host raises
    * instead: it is structure, and a quiet `None` would leave it unpatched.
    */
  private[runtime] def byteSlotValues(
      id: NodeId,
      shared: Shared,
      fragments: FetchSnapshot
  ): Option[ByteValues] =
    ownValues(id, shared, fragments).map(_.values)

  /** [[byteSlotValues]] with the node's place, which is what a session's
    * `holds` is keyed by.
    */
  private[runtime] def ownValues(
      id: NodeId,
      shared: Shared,
      fragments: FetchSnapshot
  ): Option[Own] =
    allIndexed.get(id) match {
      case Some(c: Ready.Component) if ownRenderingOf(c) =>
        if (surfaces.cases(id).nonEmpty)
          sys.error(
            s"node $id has its own rendering AND a pick; ADR 0012 holds " +
              "these are exclusive (a pick's host holds regions, so it is " +
              "structure). A session's byte-value check cannot see a " +
              "selection, so this must be fixed rather than tolerated."
          )
        Some(Own(c.ix, valuesOf(planOf(id, c, shared), shared, fragments)))
      case Some(_) => None
      case None    =>
        members.nodeAt(id, shared).collect {
          case (_, c) if ownRenderingOf(c) =>
            Own(c.ix, valuesOf(planOf(id, c, shared), shared, fragments))
        }
    }

  private def valuesOf(
      plan: NodePlan,
      shared: Shared,
      fragments: FetchSnapshot
  ): ByteValues = {
    val in = lookupAt(fragments, plan.id, shared.states)
    byteValues(plan)(s =>
      resolveSlotValue(s.entity, s.live, shared, fragments, plan.id, in)
    )
  }

  /** The one place a node's byte-slot values are put together, for the walk's
    * `holds` and for the live path that compares with them: two builders could
    * drift, and a session would then skip a node whose bytes moved.
    */
  private def byteValues(plan: NodePlan)(
      valueOf: ByteSlot => SlotValue
  ): ByteValues = {
    val b = Map.newBuilder[SlotKey, SlotValue]
    plan.byteSlots.foreach(s => b += ((s.key, valueOf(s))))
    ByteValues(b.result())
  }

  /** A node's patch form, painted from the values [[byteSlotValues]] answered
    * at this `shared`: the live path resolves nothing twice, and the bytes are
    * a function of their cache key by construction. `None` as
    * [[renderNodeById]].
    */
  private[runtime] def renderPatch(
      id: NodeId,
      shared: Shared,
      values: ByteValues
  ): Option[String] =
    nodeAt(id, shared).collect {
      case c: Ready.Component if ownRenderingOf(c) =>
        patchOf(planOf(id, c, shared), values)
    }

  /** A leaf's patch form is written directly, not taken from a [[traced]] walk,
    * which builds the document form and the trace maps a tick throws away. A
    * leaf has no children and no pick (its card declares no region), so this is
    * its whole body; its root's holes are the walk's
    * ([[NodeContext.writeHole]]). The patch form reads no signal value and no
    * at-render class, so only the byte slots are painted.
    */
  private def patchOf(plan: NodePlan, values: ByteValues): String =
    Sink.scratched { buf =>
      Templates.run(
        plan.tpl,
        buf,
        NodeContext(
          Resolved(plan, values.slots, Map.empty),
          SlotForm.Patch,
          buf
        )
      )
      buf.result
    }

  private def render(
      node: Ready.Node,
      shared: Shared,
      viewing: Viewing,
      form: SlotForm
  ): Option[String] = node match {
    case c: Ready.Component if form.isPatch =>
      val plan = planOf(c.id, c, shared)
      Some(patchOf(plan, valuesOf(plan, shared, viewing.fragments)))
    case _ =>
      Some(traced(node, shared, viewing).html)
  }

  /** The composed rendering and what each node in it now holds, kept from the
    * one walk so a fill or page load need not re-walk the subtree. `painted`
    * holds exactly the nodes with their own rendering.
    */
  private[runtime] case class Traced(
      html: String,
      root: Ix,
      painted: Map[Ix, Painted],
      seededAt: Map[Ix, Map[SignalId, SlotValue]] = Map.empty,
      shownAt: Map[Ix, IArray[Ix]] = Map.empty
  ) {

    def claims: Map[Ix, Held] = Trace.held(painted, seededAt, shownAt)

    /** The tree its [[Ix]] keys are in. */
    private[runtime] def index: TreeIndex = Renderer.this.index
  }

  // A static floor for sizing a buffer; a set's live membership is bounded by
  // its candidates and members.
  private def nodeCount(node: Ready.Node): Int = node match {
    case c: Ready.Component =>
      1 + (c.content match
        case Ready.Content.Fixed(r) =>
          r.values.iterator.flatten.map(nodeCount).sum
        // One case renders.
        case Ready.Content.Live(_, Ready.LiveFill.Pick(cases, _, _, _, _)) =>
          cases.map(nodeCount).maxOption.getOrElse(0)
        case Ready.Content.Live(_, Ready.LiveFill.Set(s)) =>
          math.max(s.candidates.size, s.members.size))
  }

  private def traced(
      node: Ready.Node,
      shared: Shared,
      viewing: Viewing
  ): Traced = {
    val trace = new Trace
    val html = tracedHtml(node, shared, viewing, trace)
    Traced(
      html,
      node.ix,
      trace.own.asScala.toMap,
      trace.seeded.asScala.toMap,
      trace.shown.asScala.toMap
    )
  }

  /** A subtree's document bytes, its trace into the caller's accumulator. The
    * buffer is presized: growing it is the copy this walk exists to remove.
    */
  private def tracedHtml(
      node: Ready.Node,
      shared: Shared,
      viewing: Viewing,
      trace: Trace
  ): String = {
    val out = Sink.buffer(
      Renderer.NodeBytesHint * (node match {
        case c: Ready.Component => planOf(c.id, c, shared).nodeCount
      })
    )
    tracedInto(out, node, shared, viewing, trace)
    out.result
  }

  /** A node's document bytes appended to `out`, and every own-rendering node's
    * byte-slot values into `trace`. An accumulator, not per-level maps merged
    * upward: those allocated per node per paint and re-copied every ancestor's.
    */
  private def tracedInto(
      out: Sink,
      node: Ready.Node,
      shared: Shared,
      viewing: Viewing,
      trace: Trace
  ): Unit =
    node match {
      case c: Ready.Component =>
        val id = c.id
        val gid = c.group
        // Resolved once for both forms; what a paint cannot change comes from
        // the plan ([[NodePlan]]).
        val plan = planOf(id, c, shared)
        val resolved = resolvePlanned(plan, shared, viewing.fragments)
        val tpl = plan.tpl

        def bodyInto(
            buf: Sink,
            form: SlotForm
        ): Unit = {
          def child(node: Ready.Node): Unit =
            tracedInto(buf, node, shared, viewing, trace)
          def hole(slot: String)(fill: => Unit) =
            Map(slot -> { (_: java.io.Writer) => fill })
          val holes: Map[String, java.io.Writer => Unit] = c.content match
            case Ready.Content.Fixed(r) =>
              r.map((region, nodes) =>
                region -> { (_: java.io.Writer) => nodes.foreach(child) }
              )
            case Ready.Content
                  .Live(slot, Ready.LiveFill.Pick(cases, _, _, _, _)) =>
              hole(slot) {
                val shown = pickedCase(gid, shared)
                  .flatMap(cases.lift)
                val _ = trace.shown.put(c.ix, IArray.from(shown.map(_.ix)))
                shown.foreach(child)
              }
            // Each member is walked like any node, so the trace holds each
            // patch target inside it; the set's root has no rendering.
            case Ready.Content.Live(slot, Ready.LiveFill.Set(s)) =>
              hole(slot) {
                val present = members.membersOf(SetId.of(id, s), shared)
                val _ =
                  trace.shown.put(c.ix, IArray.from(present.map(_.node.ix)))
                present.foreach(m => child(m.node))
              }
          if holes.isEmpty then
            Templates.run(tpl, buf, NodeContext(resolved, form, buf))
          else Templates.run(tpl, buf, NodeContext(resolved, form, buf, holes))
        }

        bodyInto(out, SlotForm.Document)

        val own = Option.when(plan.ownRendering) {
          Painted(
            byteValues(plan)(s => resolved.paint(s.key)),
            resolved.signals
          )
        }
        own match
          case Some(p) => val _ = trace.own.put(c.ix, p)
          case None if resolved.signals.nonEmpty =>
            val _ = trace.seeded.put(c.ix, resolved.signals)
          case None => ()
    }

  /** The root's `{{{cell}}}` hole, after its `class`: a live style inline in
    * the document form only, then the id, the one `data-signals` seed for the
    * whole node (ADR 0017; document form only, since a second `data-signals` on
    * an element is silently dropped), the cell bindings and the URL mirror.
    */
  private def cellInto(
      buf: Sink,
      resolved: Resolved,
      form: SlotForm
  ): Unit = {
    val plan = resolved.plan
    val c = plan.node
    c.cell match
      case Some(cell) if !form.isPatch && cell.styleSlots.nonEmpty =>
        val props = cell.styleSlots.flatMap(e =>
          resolved.paint.get(e.key).map(v => e.name + ":" + SlotValue.text(v))
        )
        if (props.nonEmpty)
          val _ = buf
            .append(""" style="""")
            .append(Server.escapeHtml(props.mkString(";")))
            .append('"')
      case _ => ()
    buf
      .append(""" id="""")
      .append(plan.id)
      .append('"')
    if (!form.isPatch)
      Datastar.seedAttrInto(buf, plan.signalSeed, resolved.signals)
    cellBindingsInto(buf, c.cell, resolved)
    // On the declarer, so a param lives as long as what it chooses for, not
    // as long as a control choosing it (ADR 0005).
    c.vars.filter(_.inUrl).sortBy(_.name).foreach { d =>
      val _ = buf
        .append(' ')
        .append(
          VarKey(plan.id, d.name).urlMirrorAttr(d)
        )
    }
  }

  def renderMemberById(
      setId: SetId,
      entityId: String,
      shared: Shared,
      fragments: FetchSnapshot
  ): Option[String] =
    members
      .membersOf(setId, shared)
      .find(_.entity == entityId)
      .map(memberTraced(_, shared, Viewing(Selections.none, fragments)).html)

  private def templateOf(cardName: String): Mustache =
    templates.components.getOrElse(
      cardName,
      throw new IllegalStateException(
        s"unknown card '$cardName' — validate should have rejected this dashboard"
      )
    )

  /** One paint's resolution as layers, not a merged map: the plan's constant
    * layers are read through the plan, one reference rather than one per layer
    * (each node allocates one of these per paint), and [[NodeContext.fhGet]]
    * reads them in precedence order.
    *
    * @param plan
    *   what the paint cannot change: the node's position (`structural`), its
    *   literal and `once` slot values and their `jsValue`s, its signal slots'
    *   views, every slot it sets (what `x.has` asks), and the slots the patch
    *   form withholds
    * @param paint
    *   the live slot values — empty when the card has none
    * @param signals
    *   the seed values, captured as they resolve
    */
  private case class Resolved(
      plan: NodePlan,
      paint: Map[SlotKey, SlotValue],
      signals: Map[SignalId, SlotValue]
  ) {
    def structural: Map[String, String] = plan.structural
    def constants: Map[SlotKey, SlotValue] = plan.constants
    def jsConstants: Map[SlotKey, String] = plan.jsConstants
    def signalled: Map[SlotKey, Signalled] = plan.signalled
    def slots: Map[SlotKey, Planned] = plan.slots
    def signalSlots: Set[SlotKey] = plan.signalSlots
  }

  /** A signal slot's views. `bind` is in both forms: a morph that dropped it
    * would leave the element inert; only the value is withheld.
    */
  private final case class Signalled(
      bind: String,
      signal: Option[SignalId],
      jsValue: String
  )

  private def signalledOf(signal: SignalId, kind: SignalBind): Signalled =
    Signalled(
      Datastar.binding(signal, kind, themeClasses),
      Some(signal),
      // The value as a JS expression whatever the tier, so one template serves
      // a static tap and a state-dependent one (issue #133).
      s"$$$signal"
    )

  /** `v.valueIs(at)` on the variable's own signals: the ask while one is out,
    * else the commit, as every selection display reads (ADR 0025). Compared as
    * strings, since `'' == 0` and `Number('')` is 0, and a token needs no
    * escaping.
    */
  private def chosenSignalled(
      key: VarKey,
      at: String,
      kind: SignalBind
  ): Signalled = {
    val read =
      s"String(($$${key.pendingSignal} || $$${key.committedSignal})) === '$at'"
    Signalled(Datastar.painting(read, kind, themeClasses), None, read)
  }

  /** The root's `{{{cellClasses}}}` hole, and a set's: each with its leading
    * space; `validate` checks they are plain class tokens. A live class only
    * where `paint` is given (the document form) and holds it truthy; its
    * binding is [[cellBindingsInto]].
    */
  private def cellClassesInto(
      buf: Sink,
      cell: Option[Ready.Cell],
      paint: Map[SlotKey, SlotValue] | Null
  ): Unit = cell match
    case Some(c) =>
      c.classSlots.foreach { e =>
        val on = e.constant.exists(SlotValue.truthy) || (paint != null && {
          val v: SlotValue | Null = paint.getOrElse(e.key, null)
          v != null && SlotValue.truthy(v)
        })
        if (on) {
          val _ = buf.append(cellClassTexts.computeIfAbsent(e.name, expandCell))
        }
      }
    case None => ()

  /** In both forms, and AFTER the root's own `data-signals`: the bundle applies
    * an element's attributes in order, so a binding ahead of the seed reads a
    * signal that does not exist yet (`Cannot read properties of undefined`, the
    * first `LiveCellClassSmokeSuite` run).
    */
  private def cellBindingsInto(
      buf: Sink,
      cell: Option[Ready.Cell],
      r: Resolved
  ): Unit = {
    def bind(e: Cell.Entry): Unit =
      if (e.constant.isEmpty) {
        val s: Signalled | Null = r.signalled.getOrElse(e.key, null)
        if (s != null) {
          val _ = buf.append(' ').append(s.bind)
        }
      }
    cell match
      case Some(c) =>
        c.classSlots.foreach(bind)
        c.styleSlots.foreach(bind)
      case None => ()
  }

  /** The template context, read in place. Not a `java.util.Map`: mustache.java
    * resolves those through `entrySet`, so a get-only map answers every name
    * empty (that cost a probe suite to find). Also where the patch form
    * withholds signal values, instead of a new map per signal slot per node. An
    * unknown name answers `null`, which `defaultValue("")` renders empty.
    */
  private case class NodeContext(
      resolved: Resolved,
      form: SlotForm,
      // What the root's holes and the regions write into.
      out: Sink,
      // Each region the walk fills, written at its `{{{name}}}`.
      override val writerHoles: Map[String, java.io.Writer => Unit] = Map.empty
  ) extends Templates.FhScope {

    override def writeHole(name: String, writer: java.io.Writer): Boolean =
      if (name == CardDef.CellClassesHole) {
        cellClassesInto(
          out,
          resolved.plan.node.cell,
          if (form.isPatch) null else resolved.paint
        )
        true
      } else if (name == CardDef.CellHole) {
        cellInto(out, resolved, form)
        true
      } else super.writeHole(name, writer)

    def fhGet(name: String): AnyRef =
      if (form.isPatch && resolved.signalSlots.contains(name)) ""
      else {
        // Flat, not nested `getOrElse`s: those defaults were capturing
        // lambdas, 5.7% of a page open (`RenderBench.page`); a constant
        // `null` default is not. No `Option` either, for the same reason.
        //
        // `AnyRef` because `paint` can hold a boxed `Boolean`, which must
        // reach mustache as one: the string "false" is a truthy section.
        var v: AnyRef | Null = null
        val p: SlotValue | Null = resolved.paint.getOrElse(name, null)
        if (p != null) v = SlotValue.scoped(p)
        if (v == null) {
          val c: SlotValue | Null = resolved.constants.getOrElse(name, null)
          if (c != null) v = SlotValue.scoped(c)
        }
        if (v == null) v = resolved.structural.getOrElse(name, null)
        v
      }

    override def fhView(slot: String, view: Templates.View): AnyRef =
      view match
        case Templates.View.Has =>
          java.lang.Boolean.valueOf(
            resolved.slots.contains(slot) || resolved.plan.calls.contains(slot)
          )
        case Templates.View.CallId =>
          resolved.plan.calls.get(slot).map(_.id).orNull
        case Templates.View.Seen =>
          resolved.plan.calls.get(slot).map(_.seen).orNull
        case Templates.View.Pending =>
          resolved.plan.calls.get(slot).map(_.pending).orNull
        case Templates.View.Bind =>
          val s: Signalled | Null = resolved.signalled.getOrElse(slot, null)
          if (s == null) null else s.bind
        case Templates.View.Signal =>
          val s: Signalled | Null = resolved.signalled.getOrElse(slot, null)
          if (s == null) null else s.signal.orNull
        case Templates.View.JsValue =>
          val s: Signalled | Null = resolved.signalled.getOrElse(slot, null)
          if (s != null) s.jsValue
          else {
            val c: String | Null = resolved.jsConstants.getOrElse(slot, null)
            if (c != null) c
            else resolved.paint.get(slot).map(Datastar.jsValue).orNull
          }
  }

  /** What a paint cannot change about one node, derived once per renderer so a
    * paint resolves only live slot values and signal values.
    */
  private case class NodePlan(
      node: Ready.Component,
      tpl: Mustache,
      structural: Map[String, String],
      constants: Map[SlotKey, SlotValue],
      jsConstants: Map[SlotKey, String],
      dynamic: List[(SlotKey, Option[String], Planned.Live)],
      // The live slots the patch form carries: [[byteValues]]'s.
      byteSlots: List[ByteSlot],
      // Every slot: what `x.has` asks.
      slots: Map[SlotKey, Planned],
      signalled: Map[SlotKey, Signalled],
      signalNameBySlot: Map[SlotKey, SignalId],
      // The address variables are looked up by, never their values: a plan is
      // shared across sessions.
      id: NodeId,
      signalSeed: Datastar.SignalSeed,
      ownRendering: Boolean,
      nodeCount: Int,
      calls: Map[String, Renderer.PlacedCall]
  ) {
    // Not `keySet`: that view filters through a builder, per cell, per paint.
    val signalSlots: Set[SlotKey] = signalNameBySlot.keysIterator.toSet
  }

  private final case class ByteSlot(
      key: SlotKey,
      entity: Option[String],
      live: Planned.Live
  )

  /** A slot as its node's plan holds it: a value that never moves, folded once
    * with its JS spelling, or a live one, its source and steps composed into
    * one function so a paint walks no step list and looks nothing up.
    */
  private enum Planned:
    case Constant(value: SlotValue, js: String)
    case Live(source: Ready.Slot)

  private def live(source: Ready.Slot): Planned.Live = Planned.Live(source)

  /** `once` reads only identity, so the plan holds it; the memo is keyed by
    * entity, which is why a fetch slot never enters it.
    */
  private def plan(
      source: Ready.Slot,
      shared: Shared
  ): Planned = source.constant match
    case Some(flag: Boolean) => Planned.Constant(flag, Datastar.jsValue(flag))
    case Some(n: Double)     => Planned.Constant(n, Datastar.jsValue(n))
    case Some(literal: String) if source.isNumber =>
      val n = slotValueOf(source, literal)
      Planned.Constant(n, Datastar.jsValue(n))
    case Some(literal: String) =>
      val text = classed(source, literal)
      Planned.Constant(text, Datastar.jsLiteral(text))
    case None if source.fetch.isEmpty && source.reads == Reads.Once =>
      val once = identityCache.computeIfAbsent(
        (source.entityId.getOrElse(""), source.valueKey),
        _ =>
          // A fetch is live, so a `once` value reads none.
          resolveStateSlotValue(
            source.entityId,
            live(source),
            shared,
            Evaluator.unanswered
          )
      )
      Planned.Constant(once, Datastar.jsValue(once))
    case None => live(source)

  /** A `valueWhile` planned once per renderer, by what it decides. */
  private val gateRuns =
    new java.util.concurrent.ConcurrentHashMap[String, Planned.Live]()

  private def withheld(
      planned: Planned.Live,
      shared: Shared
  ): Boolean =
    planned.source.gate.exists(!gateHolds(_, shared))

  /** One plan per node an id shows, matched by identity: bounded only because
    * every node is built once (a member's with the graph, see the pipeline
    * doc), so a node built per paint would grow its list forever.
    */
  private val nodePlans =
    new java.util.concurrent.ConcurrentHashMap[String, List[NodePlan]]()

  private def planOf(
      id: NodeId,
      node: Ready.Component,
      shared: Shared
  ): NodePlan = {
    val cached = nodePlans.getOrDefault(id, Nil)
    cached.find(_.node eq node).getOrElse {
      val p = buildPlan(id, node, shared)
      nodePlans.put(id, p :: cached)
      p
    }
  }

  private def buildPlan(
      id: NodeId,
      c: Ready.Component,
      shared: Shared
  ): NodePlan = {
    val slots = c.slots.view.mapValues(plan(_, shared)).toMap
    // A signal slot is always `live` and non-literal, so its name is constant
    // per node.
    val named = c.slots.toList.flatMap { case (slot, src) =>
      Renderer.signalBind(src).filter(_ => src.chosenSignal.isEmpty).map {
        kind =>
          (
            slot,
            kind,
            Renderer.signalName(
              id,
              SlotKey.label(slot),
              src.entityId,
              src.valueKey,
              kind
            )
          )
      }
    }
    // No signal of its own to seed or send: it reads its declarer's. Missed,
    // it would fall into the byte slots and read the variable in a patch.
    val chosen = c.slots.toList.flatMap { case (slot, src) =>
      for {
        kind <- Renderer.signalBind(src)
        (name, at) <- src.chosenSignal
      } yield slot -> chosenSignalled(
        vars
          .keyAt(id, name)
          .getOrElse(
            throw fh.view.FHError.internal(
              s"$id reads the variable '$name', which validate found declared"
            )
          ),
        at,
        kind
      )
    }
    val signalled = named.map { case (slot, kind, signal) =>
      slot -> signalledOf(signal, kind)
    }.toMap ++ chosen
    NodePlan(
      node = c,
      tpl = templateOf(c.card),
      structural = structuralVars(id),
      constants = slots.collect { case (k, Planned.Constant(v, _)) => k -> v },
      jsConstants =
        slots.collect { case (k, Planned.Constant(_, js)) => k -> js },
      dynamic = slots.toList.collect { case (k, l: Planned.Live) =>
        (k, l.source.entityId, l)
      },
      // A live cell slot is always a signal, so only names are left.
      byteSlots = slots.toList.collect {
        case (k, l: Planned.Live) if !signalled.contains(k) =>
          ByteSlot(k, l.source.entityId, l)
      },
      slots = slots,
      signalled = signalled,
      signalNameBySlot = named.map { case (slot, _, signal) =>
        slot -> signal
      }.toMap,
      id = id,
      signalSeed = Datastar.seedFor(named.map(_._3)),
      ownRendering = ownRenderingOf(c),
      // Once, not per paint: it walks the subtree.
      nodeCount = nodeCount(c),
      calls = c.calls.map((name, p) =>
        name -> Renderer.PlacedCall(
          p.id,
          seenOf(p, signalled),
          pendingOf(p, id)
        )
      )
    )
  }

  /** The ask a choice writes as its press starts, on the pending signal of the
    * declaration the node reads: the assignment `tap.pending` fills. A clear
    * asks nothing, since `pending || committed` cannot hold "nothing".
    */
  private def pendingOf(p: Ready.Placed, id: NodeId): String =
    dashboard.calls.get(p.id).map(_.decl) match
      case Some(CallDecl.SetVar(name, Some(value))) =>
        vars.keyAt(id, name).fold("")(k => s"$$${k.pendingSignal} = '$value'; ")
      case _ => ""

  /** What the press confirms, `{service: $…, data: {'k': $…}}`: each seen
    * field's signal, read as the page holds it.
    */
  private def seenOf(
      p: Ready.Placed,
      signalled: Map[SlotKey, Signalled]
  ): String = {
    def js(slot: String) = signalled.get(slot).fold("null")(_.jsValue)
    val service = p.seen.collectFirst { case (CallField.Service, s) =>
      s"service: ${js(s)}"
    }
    val data = p.seen.collect { case (CallField.Data(k), s) =>
      s"'$k': ${js(s)}"
    }
    (service.toList ++ Option.when(data.nonEmpty)(
      data.mkString("data: {", ", ", "}")
    ))
      .mkString("{", ", ", "}")
  }

  private def resolvePlanned(
      plan: NodePlan,
      shared: Shared,
      fragments: FetchSnapshot
  ): Resolved = {
    // The only map a paint builds.
    val paintB = Map.newBuilder[SlotKey, SlotValue]
    paintB.sizeHint(plan.dynamic.size)
    val signalB =
      if (plan.signalNameBySlot.isEmpty) None
      else Some(Map.newBuilder[SignalId, SlotValue])
    // One per node: its slots read the same answers and variables.
    val in = lookupAt(fragments, plan.id, shared.states)
    plan.dynamic.foreach { case (slot, srcEntity, source) =>
      val value =
        resolveSlotValue(srcEntity, source, shared, fragments, plan.id, in)
      paintB += ((slot, value))
      plan.signalNameBySlot
        .get(slot)
        .foreach(sig => signalB.foreach(_ += ((sig, value))))
    }
    Resolved(
      plan,
      paint = paintB.result(),
      signalB.fold(Map.empty[SignalId, SlotValue])(_.result())
    )
  }

  /** The signal values one node carries (ADR 0017), never its children's:
    * descending would name a child's signal under its parent and leave the
    * child's binding on a signal nothing writes.
    */
  def signalsFor(
      id: NodeId,
      shared: Shared,
      fragments: FetchSnapshot
  ): Map[SignalId, SlotValue] =
    nodeAt(id, shared)
      .map(signalsOf(id, _, shared, fragments))
      .getOrElse(Map.empty)

  /** [[signalsFor]] for a node [[nodeAt]] already found. */
  private[runtime] def signalsOf(
      id: NodeId,
      node: Ready.Node,
      shared: Shared,
      fragments: FetchSnapshot
  ): Map[SignalId, SlotValue] =
    // Not gated on own rendering: structure's signal slots would be seeded
    // once and then never move.
    node match {
      // A fetch's answer is the viewer's; a variable cannot reach a signal
      // (validate refuses it).
      case c: Ready.Component if c.fetching.isEmpty =>
        shared.signals(id)(signalsOfSlots(id, c, shared, fragments))
      case node => signalsOfSlots(id, node, shared, fragments)
    }

  /** Through the plan: re-deriving the names per tick was 9.4% of a signals
    * tick's allocation (`RenderBench.resumeSignals`).
    */
  private def signalsOfSlots(
      id: NodeId,
      node: Ready.Node,
      shared: Shared,
      fragments: FetchSnapshot
  ): Map[SignalId, SlotValue] = node match {
    case c: Ready.Component =>
      val plan = planOf(id, c, shared)
      if (plan.signalNameBySlot.isEmpty) Map.empty
      else {
        val b = Map.newBuilder[SignalId, SlotValue]
        b.sizeHint(plan.signalNameBySlot.size)
        val in = lookupAt(fragments, id, shared.states)
        plan.dynamic.foreach { case (slot, srcEntity, source) =>
          plan.signalNameBySlot
            .get(slot)
            .foreach(sig =>
              b += ((
                sig,
                resolveStateSlotValue(srcEntity, source, shared, in)
              ))
            )
        }
        b.result()
      }
  }

  /** Resolves before any state arrives, so a `domain` action still works. Keeps
    * a boolean boolean ([[SlotValue]]); `false` never falls through to
    * `default`.
    */
  private def resolveSlotValue(
      srcEntity: Option[String],
      source: Planned.Live,
      shared: Shared,
      fragments: FetchSnapshot,
      node: NodeId,
      in: Evaluator.Lookup
  ): SlotValue = source.source.shape match {
    case SlotShape.Fetch(_) if withheld(source, shared) =>
      ""
    // Answered before the walk began; `FetchSnapshot` raises on a miss. A
    // choice that decides no case asks nothing, and reads as no value.
    case SlotShape.Fetch(ask) =>
      readOf(ask, shared.states, fragments.varsAt(node)) match
        case Some(read) =>
          val answer = fragments.staged(read)
          if (answer.version == Staged.FailedVersion)
            failedLabelText(answer.value)
          else answer.value
        case None =>
          source.source.default match
            case Some(Lit.Bool(b)) => b
            case d                 => d.fold("")(l => Steps.text(l.raw))
    case SlotShape.State(_) =>
      resolveStateSlotValue(srcEntity, source, shared, in)
  }

  /** The dashboard's `failedLabel` filled in; nothing for a failure with no
    * text to show.
    */
  private def failedLabelText(text: String): String =
    if text.isEmpty then ""
    else {
      val out = new java.io.StringWriter()
      Templates.run(
        templates.failedLabel,
        out,
        Renderer.FailedLabelScope(Map("text" -> text))
      )
      out.toString
    }

  /** What `node`'s slots read besides state: a fetch value's answer as CEL
    * reads it, and the node's variables at the values the answers were fetched
    * for.
    */
  private def lookupAt(
      fragments: FetchSnapshot,
      node: NodeId,
      states: Map[String, EntityState]
  ): Evaluator.Lookup = new Evaluator.Lookup:
    def fetched(f: FetchTemplate): Object =
      readOf(SlotAsk(f, Step.Json), states, fragments.varsAt(node))
        .fold(null)(r => Series.celPoints(fragments.staged(r).value))
    def variable(name: String): String =
      fragments.varsAt(node).getOrElse(name, Evaluator.noVariable(name))

  private def resolveStateSlotValue(
      srcEntity: Option[String],
      planned: Planned.Live,
      shared: Shared,
      fetched: Evaluator.Lookup
  ): SlotValue = {
    val source = planned.source
    val st: EntityState | Null =
      srcEntity.fold(null)(shared.states.getOrElse(_, null))
    if (withheld(planned, shared)) ""
    // The bypass, not the transform, keeps an unavailable entity readable.
    else if (source.bypassUnavailable && st != null && st.unavailable) st.state
    else {
      slotValueOf(source, planned.source.run(st, shared.states, fetched))
    }
  }

  /** Where a value leaves the language: a bool slot is `true` only for `true`,
    * a text slot with nothing to show takes its default.
    */
  private def slotValueOf(source: Ready.Slot, v: Any): SlotValue =
    if (source.isNumber)
      Renderer
        .number(v)
        .orElse(source.default.flatMap(l => Renderer.number(l.raw)))
        .getOrElse("")
    else if (source.isBool)
      v match
        case b: Boolean => b
        case _          =>
          source.default match
            case Some(Lit.Bool(b)) => b
            case _                 => false
    else
      v match
        case b: Boolean => b
        case _          =>
          val s = Steps.text(v)
          if (s.nonEmpty) classed(source, s)
          else
            source.default match
              case Some(Lit.Bool(b)) => b
              case d                 =>
                classed(source, d.fold("")(l => Steps.text(l.raw)))

  /** The one swap of a themed value's classes, wherever it resolves. */
  private def classed(source: Ready.Slot, value: String): String =
    if (source.themed) themeClasses.expandAll(value)
    else value
}

object Renderer {

  /** What painted a session's `holds`, compared by reference: they are read
    * only under it ([[Painter]]). It keeps what a swap's answer reads of the
    * renderer it replaces: whether the head moved, and which popup `open`
    * named, since the new renderer cannot read a popup it lacks.
    */
  private[runtime] final class Stamp(
      val headHash: String,
      val styleHash: String,
      popups: Map[String, String]
  ) {
    def popupIn(open: Set[String]): Option[String] =
      SurfaceGraph.popupIn(popups, open)
  }

  /** A declared call as the press found it ([[Renderer.press]]). */
  final case class Pressed(
      service: String,
      target: TargetIds,
      exclude: TargetIds,
      data: ListMap[String, Any],
      seen: List[(CallField, SlotValue)]
  )

  /** A call as a node's plan fills `tapMod.post`: its id, and its `seen` object
    * and pending ask as JS.
    */
  final case class PlacedCall(id: String, seen: String, pending: String)

  /** A number slot's value: a number, or text that is one. */
  def number(v: Any): Option[Double] = v match
    case n: Long   => Some(n.toDouble)
    case n: Int    => Some(n.toDouble)
    case d: Double => Option.when(!d.isNaN && !d.isInfinite)(d)
    case s: String => s.trim.toDoubleOption
    case _         => None

  /** A target entry's ids: one, a list of them, or none. */
  def ids(v: Any): List[String] = v match
    case null            => Nil
    case xs: Iterable[?] => xs.toList.map(Steps.text).filter(_.nonEmpty)
    case x               => List(Steps.text(x)).filter(_.nonEmpty)

  /** A subtree's asks, which stop at a nested set: its members ask their own.
    */
  private[runtime] def subtreeAsks(node: Ready.Node): List[SlotAsk] =
    node match {
      case c: Ready.Component =>
        c.fetches ++ c.allChildren.flatMap(subtreeAsks)
    }

  /** [[subtreeAsks]]' twin for the variables its slots read. */
  private def subtreeVariables(node: Ready.Node): Set[String] =
    node match {
      case c: Ready.Component =>
        c.variables ++ c.allChildren.flatMap(subtreeVariables)
    }

  private final class FailedLabelScope(values: Map[String, String])
      extends Templates.FhScope {
    def fhGet(name: String): AnyRef = values.getOrElse(name, null)
  }

  /** The chrome's scope: no names, only the body and dialog holes. */
  private final class PageScope(holes: Map[String, java.io.Writer => Unit])
      extends Templates.FhScope {
    def fhGet(name: String): AnyRef = null
    override def writerHoles: Map[String, java.io.Writer => Unit] = holes
  }

  /** For tests and benchmarks: a dashboard the build would refuse throws. */
  def create(dashboard: Dashboard): Renderer =
    dashboard.validated() match
      case Right(v)   => Renderer(v)
      case Left(errs) =>
        throw IllegalArgumentException(
          errs.mkString("invalid dashboard:\n", "\n", "")
        )

  /** [[Renderer.headHash]]. Not the `<base href>`, which is per request. Over
    * the model, not the JSON text: `toString` is deterministic, since the maps
    * are `String`-keyed and hold no identity hashes.
    */
  private[runtime] def headFingerprint(dashboard: Ready.Dashboard): String =
    fingerprint(
      (
        dashboard.theme.stylesheets,
        dashboard.theme.deferredStylesheets,
        dashboard.theme.scripts,
        dashboard.theme.inlineScripts,
        dashboard.cardScripts,
        dashboard.theme.chrome,
        dashboard.theme.offlineBanners,
        // The one token-derived head part a style patch cannot repair.
        themeColorTags(dashboard)
      ).toString
    )

  /** Follows the device's scheme outside the installed app; Chrome ignores it
    * inside one ([[PwaAssets]]).
    */
  private[runtime] def themeColorTags(dashboard: Ready.Dashboard): String =
    ChromeColors.from(dashboard.theme).fold("") { c =>
      List("light" -> c.light, "dark" -> c.dark)
        .map { case (scheme, color) =>
          s"""<meta name="theme-color" media="(prefers-color-scheme: $scheme)" content="$color">"""
        }
        .mkString("\n  ")
    }

  private[runtime] def styleFingerprint(dashboard: Ready.Dashboard): String =
    fingerprint(
      (
        dashboard.theme.tokens,
        dashboard.theme.tokensDark,
        dashboard.theme.styles,
        // The same `<style>` element; leaving these out lets a stale sheet
        // hash equal.
        dashboard.css,
        dashboard.cardCss,
        dashboard.title
      ).toString
    )

  private def fingerprint(s: String): String =
    LibPackage.sha256(s.getBytes("UTF-8")).take(12)

  val ThemeStyleId: String = "fh-theme"

  /** One derivation for both ends — the binding the card places and the name
    * the pull patches — since a drift would freeze the value silently (ADR
    * 0017). `_`-prefixed so live values never ride a request.
    *
    * A display signal is keyed by what it reads, `_e.<domain>.<object>.
    * <transform>`, so one entity on three cards is one signal (issue #134). The
    * pinned bundle's reference regex needs `\w+` segments; HA slugifies entity
    * ids, and anything else is hashed, since a stray character would silently
    * parse as another path.
    */
  def signalName(
      id: NodeId,
      slot: String,
      entity: Option[String],
      transform: String,
      kind: SignalBind
  ): SignalId = kind match {
    // Interaction state, scoped to its control: shared, one card's drag would
    // drive another's readout (ADR 0025).
    case SignalBind.Bind => SignalId.derived(s"_${id}__$slot")
    case _               => displayPath(entity, transform)
  }

  /** Memoised: it runs per signal slot per render and may hash. Static, since
    * it reads no dashboard; a reload leaves a few short, still-correct entries.
    */
  private def displayPath(entity: Option[String], transform: String): SignalId =
    displayPaths.computeIfAbsent(
      (entity, transform),
      key =>
        SignalId.derived(
          s"_e.${entitySegments(key._1)}.${transformSegment(key._2)}"
        )
    )

  private val displayPaths =
    new java.util.concurrent.ConcurrentHashMap[
      (Option[String], String),
      SignalId
    ]()

  private def isWord(s: String): Boolean =
    s.nonEmpty && s.forall(c => c.isLetterOrDigit || c == '_')

  private def entitySegments(entity: Option[String]): String = entity match {
    case Some(id) =>
      id.split('.') match {
        case Array(domain, obj) if isWord(domain) && isWord(obj) => id
        case _                                                   =>
          s"x${shortHash(id)}"
      }
    // No entity reads an empty state, so such slots genuinely share one.
    case None => "_x"
  }

  /** Not the slot name, which would break sharing or collide. The two forms'
    * prefixes are disjoint, so the mapping stays injective.
    */
  private def transformSegment(transform: String): String = transform match {
    case "state" => "state"
    case other   => s"t${shortHash(other)}"
  }

  private def shortHash(s: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .take(4)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** Never for a literal (`validate` rejects it) or a non-live slot, whose
    * inline seed is the whole story. Answering `None` everywhere yields the
    * plain form (ADR 0017).
    */
  def signalBind(src: Ready.Slot): Option[SignalBind] =
    src.signal.filter(_ => src.constant.isEmpty && src.reads == Reads.Live)

  def isSignalSlot(src: Ready.Slot): Boolean = signalBind(src).isDefined

  private[runtime] def perRegion[A](children: Map[String, List[LayoutNode]])(
      f: (LayoutNode, LayoutNode.Step) => A
  ): Map[String, List[A]] =
    children.map { case (region, nodes) =>
      region -> nodes.zipWithIndex.map { case (n, i) =>
        f(n, LayoutNode.Step(region, i))
      }
    }

  /** A shipped leaf is 1-2 kB with its root and seed; 512 made every leaf grow
    * twice.
    */
  private[runtime] val NodeBytesHint = 1024

}
