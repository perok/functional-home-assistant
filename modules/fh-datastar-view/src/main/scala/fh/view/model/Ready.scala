package fh.view.model

import api.homeassistant.ServiceTarget
import cats.syntax.all.*
import fh.view.runtime.{Cel, EntityState, Evaluator}

/** The compiled model: what `Dashboard.validated` walks the wire into, every
  * expression beside its run, and all the runtime reads. A class here stands in
  * for a wire class that holds an expression or a node; the rest are shared.
  */
object Ready:

  /** Shared with [[LayoutNode]], so one id scheme mints both trees' ids. */
  sealed trait Node extends LayoutNode.Identified

  final case class Component(
      card: String,
      slots: Map[SlotKey, Slot],
      regions: Map[String, List[Node]],
      cell: Option[Cell],
      authoredId: Option[String],
      vars: List[fh.view.model.Dashboard.Declaration],
      subjectEntity: Option[String]
  ) extends Node:

    /** Name order, for a reproducible walk; ids do not come from this. */
    def allChildren: List[Node] = regions.toList.sortBy(_._1).flatMap(_._2)

    /** The reverse index. Empty means static HTML, never patched. */
    lazy val liveEntities: List[String] =
      BoundSlot.liveEntities(slots.values.map(_.bound))

    /** [[liveEntities]] without signal slots: the render key's. */
    lazy val liveEntitiesAsBytes: List[String] =
      BoundSlot.liveEntitiesAsBytes(slots.values.map(_.bound))

    /** Per slot, each with the `valueWhile` it waits for. In the render key but
      * not [[liveEntities]]: otherwise a sensor ticking every second would
      * refetch its history every second. Ungated without an entity to decide
      * over: asking too much is safe where too little raises.
      */
    lazy val fetching: List[Fetching] =
      slots.values.toList.flatMap { s =>
        val asks = s.shape match
          case SlotShape.State(src) => src.fetches
          case SlotShape.Fetch(q)   => List(q)
        Option.when(asks.nonEmpty)(
          Fetching(asks, s.gate.filter(_.entity.isDefined))
        )
      }

    /** Every ask, whatever the `valueWhile`s decide: what a variable's value is
      * checked against (ADR 0023).
      */
    lazy val fetches: List[SlotAsk] = fetching.flatMap(_.asks).distinct

  /** A set: `order` is `None` where the candidates are already in order. Not a
    * case class: `MemberGraph`'s sources hold it, and hashing one walked every
    * clause (`publishSet` 11 µs -> 1.4 ms).
    */
  final class SetNode(
      val candidates: List[String],
      val members: Map[String, SetMember],
      val order: Option[Evaluator.Keys],
      val limit: Option[Int],
      val cell: Option[Cell]
  ) extends Node:
    def authoredId: Option[String] = None

  final case class SetMember(clauses: List[SetClause])

  final case class SetClause(when: Option[Condition], node: Node)

  /** A guard or a state surface's condition, and the entities it reads. */
  final case class Condition(run: Evaluator.Run, entities: List[String])

  /** A slot's run, and its bind pass's facts, each read once: a paint reads
    * them per slot, and several are `def`s on [[BoundSlot]] (`shape`
    * allocates).
    */
  final case class Slot(
      bound: BoundSlot,
      run: Evaluator.Run,
      valueWhile: Option[Slot]
  ):
    val entityId: Option[String] = bound.entityId
    val reads: Reads = bound.reads
    val constant: Option[SlotValue] = bound.constant
    val default: Option[Lit] = bound.default
    val themed: Boolean = bound.themed
    val fetch: Option[FetchTemplate] = bound.fetch
    val shape: SlotShape = bound.shape
    val valueKey: String = bound.valueKey
    val signal: Option[SignalBind] = bound.signal
    val isBool: Boolean = bound.isBool
    val bypassUnavailable: Boolean = bound.bypassUnavailable
    val gate: Option[Fetching.Gate] =
      valueWhile.map(w => Fetching.Gate(w, w.entityId))

  /** A cell's entries, keyed once for the render walk; its live slots are its
    * node's [[Component.slots]].
    */
  final case class Cell(
      classSlots: List[fh.view.model.Cell.Entry],
      styleSlots: List[fh.view.model.Cell.Entry]
  )

  enum Activation:
    case User(defaultOpen: Boolean)
    case Var(name: String, at: VarAt)
    case State(condition: Option[Condition])

  final case class Surface(
      content: Node,
      bakeInto: Option[NodeId],
      bakeAs: Option[String],
      bakeIndex: Option[Int],
      activation: Activation,
      clause: Option[Int]
  ) extends fh.view.model.Surface.Placed:
    def selects: Option[VarAt] = activation match
      case Activation.Var(_, at) => Some(at)
      case _                     => None

  /** What a declared call acts on: its fixed target, or a set a press narrows
    * to the candidates present then.
    */
  type Reach = ServiceTarget | SetCall

  final class SetCall(
      val present: (
          Map[String, EntityState],
          FetchTemplate => Object
      ) => List[String]
  )

  /** The compile walk, from `validate`'s typing of each slot; a `Left` is an
    * expression `validate` passed but that does not compile.
    */
  final class Compile(scope: Evaluator.Scope):
    private type Built[A] = Evaluator.Built[A]

    def dashboard(d: fh.view.model.Dashboard): Built[Dashboard] =
      (
        node(d.card),
        d.surfaces.toList.traverse((k, s) => surface(s).map(k -> _)),
        d.calls.toList.traverse((call, r) => reach(r).map(call -> _)),
        d.varScopes.values.toList
          .flatMap(_.values.flatMap(_.declaration.initialTransform))
          .map(_.celKey)
          .distinct
          .traverse(k => scope.program(k).map(k -> _))
      ).mapN((card, surfaces, calls, initials) =>
        Dashboard(
          d.cards,
          card,
          d.theme,
          surfaces.toMap,
          d.slug,
          d.title,
          d.css,
          d.cardCss,
          d.cardScripts,
          d.referencedEntities,
          d.watchedEntities,
          calls.toMap,
          initials.toMap,
          d.varScopes,
          d.warnings,
          // `validate` refused a variable here, so each resolves bare.
          d.decisionFetches
            .map(_._2)
            .distinct
            .map(SlotAsk(_, None).resolve(Map.empty))
        )
      )

    def node(n: LayoutNode): Built[Node] = n match
      case c: LayoutNode.Component => component(c)
      case s: LayoutNode.SetNode   => set(s)

    def component(c: LayoutNode.Component): Built[Component] =
      (
        c.bound.toList.traverse((k, b) => slot(b).map(k -> _)),
        c.regions.toList.traverse((r, ns) => ns.traverse(node).map(r -> _))
      ).mapN((slots, regions) =>
        Component(
          c.card,
          slots.toMap,
          regions.toMap,
          c.cell.map(_.ready),
          c.id,
          c.vars,
          c.subjectEntity
        )
      )

    def set(s: LayoutNode.SetNode): Built[SetNode] =
      (
        s.members.toList.traverse((e, m) =>
          m.clauses
            .traverse(cl =>
              (cl.when.traverse(condition), node(cl.node)).mapN(SetClause(_, _))
            )
            .map(e -> SetMember(_))
        ),
        s.orderBy match
          case Nil   => Right(None)
          case terms => Evaluator.Keys.of(terms, scope).map(Some(_))
      ).mapN((members, order) =>
        SetNode(
          s.candidates,
          members.toMap,
          order,
          s.limit,
          s.cell.map(_.ready)
        )
      )

    def surface(s: fh.view.model.Surface): Built[Surface] =
      (node(s.content), activation(s.activation)).mapN((content, a) =>
        Surface(content, s.bakeInto, s.bakeAs, s.bakeIndex, a, s.clause)
      )

    private def activation(a: fh.view.model.Activation): Built[Activation] =
      a match
        case fh.view.model.Activation.User(d)    => Right(Activation.User(d))
        case fh.view.model.Activation.Var(n, at) => Right(Activation.Var(n, at))
        case fh.view.model.Activation.State(c)   =>
          c.traverse(condition).map(Activation.State(_))

    private def reach(r: CallReach): Built[Reach] = r match
      case t: ServiceTarget => Right(t)
      case p: Value.Present => Evaluator.presentOf(p, scope).map(SetCall(_))

    private def slot(b: BoundSlot): Built[Slot] =
      for
        typed <- b.typed
        named <- Evaluator.named(b.values, scope)
        c <- Evaluator.compiled(b.expr, typed, scope, named)
        valueWhile <- b.valueWhile.traverse(slot)
      yield Slot(b, c.run, valueWhile)

    private def condition(e: ValueExpr): Built[Condition] =
      Evaluator.compile(e, scope).map(c => Condition(c.run, e.entities))

  /** The wire's whole-dashboard facts, read off it once by the walk. */
  final case class Dashboard(
      cards: Map[String, CardDef],
      card: Node,
      theme: Theme,
      surfaces: Map[String, Surface],
      slug: String,
      title: Option[String],
      css: String,
      cardCss: String,
      cardScripts: List[String],
      referencedEntities: Set[String],
      watchedEntities: Set[String],
      calls: Map[TapCall, Reach],
      // Where a session opens a node variable, by its transform.
      initials: Map[Cel.Key, Cel.Program],
      varScopes: Map[NodeId, Map[String, fh.view.model.Dashboard.InScope]],
      warnings: List[String],
      // What every decision reads, answered before any is made.
      decisionReads: List[SlotRead]
  )
