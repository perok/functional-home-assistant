package fh.view.model

import cats.syntax.all.*
import fh.view.runtime.{Cel, EntityState, Evaluator}

import scala.collection.immutable.ListMap

/** The compiled model: what `Dashboard.validated` walks the wire into, every
  * expression beside its run, and all the runtime reads. A class here stands in
  * for a wire class that holds an expression or a node; the rest are shared.
  */
object Ready:

  /** Shared with [[LayoutNode]], so one id scheme mints both trees' ids. */
  sealed trait Node extends LayoutNode.Identified:
    def id: NodeId

  /** `id`, `clause` and `group` are its [[TreeIndex]] entry's: the render walk
    * reads them rather than minting ids per paint.
    */
  final case class Component(
      id: NodeId,
      clause: Option[Int],
      group: NodeId,
      card: String,
      slots: Map[SlotKey, Slot],
      regions: Map[String, List[Node]],
      cell: Option[Cell],
      authoredId: Option[String],
      vars: List[fh.view.model.Dashboard.Declaration],
      subjectEntity: Option[String],
      calls: Map[String, Placed] = Map.empty,
      set: Option[SetFill] = None,
      choices: Set[String] = Set.empty
  ) extends Node:

    /** Name order, for a reproducible walk; ids do not come from this. */
    def allChildren: List[Node] = regions.toList.sortBy(_._1).flatMap(_._2)

    /** Whether `region` is a choice's: its nodes are the cases, and only the
      * one its pick shows renders.
      */
    def isChoice(region: String): Boolean = choices.contains(region)

    /** The reverse index. Empty means static HTML, never patched. */
    lazy val liveEntities: List[String] =
      BoundSlot.liveEntities(slots.values.map(_.bound))

    /** [[liveEntities]] without signal slots: those that can move its bytes. */
    lazy val liveEntitiesAsBytes: List[String] =
      BoundSlot.liveEntitiesAsBytes(slots.values.map(_.bound))

    /** Per slot, each with the `valueWhile` it waits for. Not in
      * [[liveEntities]]: otherwise a sensor ticking every second would refetch
      * its history every second. Ungated without an entity to decide over:
      * asking too much is safe where too little raises.
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

    /** The node variables its slots read: a write re-renders it for that
      * viewer. Not a `chosenSignal`'s, which the browser follows itself.
      */
    lazy val variables: Set[String] =
      slots.values
        .filter(_.chosenSignal.isEmpty)
        .flatMap(_.bound.variables)
        .toSet

  /** A node's set, filling its nodes slot `region`: `order` is `None` where the
    * candidates are already in order. Not a case class: `MemberGraph`'s sources
    * hold it, and hashing one walked every member (`publishSet` 11 µs -> 1.4
    * ms).
    */
  final class SetFill(
      val region: String,
      val candidates: List[String],
      val members: Map[String, MemberChoice],
      val order: Option[Evaluator.Keys],
      val limit: Option[Int],
      val of: IdKind = IdKind.Entity
  )

  /** What renders for one candidate: `guard` read for it, and the case it reads
    * as, else nothing. `byKey` is each case's key as the evaluator reads it
    * ([[Lit.raw]]) to its index in `nodes`; an unguarded member is its one
    * node.
    */
  final case class MemberChoice(
      guard: Option[Condition],
      nodes: Vector[Component],
      byKey: Map[Any, Int]
  ):
    /** The index of the node `read` picks, else -1. Keyed by the raw value, not
      * [[Lit.key]]'s string: a member is decided on every change its guard
      * reads, and the string cost `publishSetNamedGuard` 17% more allocation.
      */
    def pick(read: Any): Int = read match
      case i: java.lang.Integer =>
        byKey.getOrElse(java.lang.Long.valueOf(i.longValue), -1)
      case _ => byKey.getOrElse(read, -1)

  /** A guard or a state surface's condition, and the entities and fetches it
    * reads.
    */
  final case class Condition(
      run: Evaluator.Run,
      entities: List[String],
      reads: Set[SlotRead]
  )

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
    val chosenSignal: Option[(String, String)] = bound.chosenSignal
    val isNumber: Boolean = bound.`type` == SlotType.Number
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

  /** A popup, `defaultOpen` from its activation. */
  final case class Surface(content: Node, defaultOpen: Boolean)

  /** A choice ready to decide: `by` read once and its case looked up
    * ([[decide]]). A `by` that is a node `variable` is decided per viewer, by a
    * lookup of its value; any other reads state, once per version. Where it is
    * as [[fh.view.model.Dashboard.Pick]] has it; `cases`: the cases' node ids,
    * the otherwise's last.
    */
  final case class Pick(
      host: NodeId,
      slot: String,
      prefix: String,
      within: fh.view.model.Dashboard.Within,
      by: Condition,
      variable: Option[String],
      cases: Vector[NodeId],
      byKey: Map[Any, Int],
      otherwise: Option[Int]
  ):
    def clause: Option[Int] = within.map(_._2)

    /** The member `read` picks: the case keyed by it, else the otherwise, else
      * none. Keyed by the raw value, as [[MemberChoice.pick]] is.
      */
    def decide(read: Any): Option[Int] =
      (read match
        case i: java.lang.Integer =>
          byKey.get(java.lang.Long.valueOf(i.longValue))
        case _ => byKey.get(read)
      ).orElse(otherwise)

  /** A declared call ready to run at the press. */
  sealed trait Call:
    def decl: CallDecl

  /** Every field ready to run, and the fetches any of them reads, which the
    * press answers first.
    */
  final class ServiceCall(
      val decl: CallDecl.Service,
      val service: Evaluator.Run,
      val target: ListMap[IdKind, List[Evaluator.Run]],
      val exclude: ListMap[IdKind, List[Evaluator.Run]],
      val data: ListMap[String, Evaluator.Run],
      val reads: Set[SlotRead]
  ) extends Call

  final case class SetVarCall(decl: CallDecl.SetVar) extends Call

  /** A call as a node places it: its id, and each field the node sees with the
    * slot the build added for it, a `Handler` signal holding the field's output
    * as the page shows it.
    */
  final case class Placed(id: CallId, seen: List[(CallField, String)])

  /** A seen field's slot: a name no Pkl slot can have, holding a `.`. */
  def seenSlot(call: String, field: CallField): String =
    s"$call.seen.${field.name}"

  /** The compile walk, from `validate`'s typing of each slot; a `Left` is an
    * expression `validate` passed but that does not compile.
    */
  final class Compile(scope: Evaluator.Scope):
    private type Built[A] = Evaluator.Built[A]

    def dashboard(d: fh.view.model.Dashboard): Built[Dashboard] =
      val i = d.index
      val roots = i.roots.map(r => i.tree(r) -> r).toMap
      (
        node(i, roots("")),
        d.surfaces.toList.traverse((k, s) =>
          surface(i, roots(k), s).map(k -> _)
        ),
        d.calls.toList.traverse((id, decl) => call(decl).map(id -> _)),
        d.varScopes.values.toList
          .flatMap(_.values.flatMap(_.declaration.initialTransform))
          .map(_.celKey)
          .distinct
          .traverse(k => scope.program(k).map(k -> _)),
        d.choiceBys.traverse(by =>
          Evaluator.compile(by, scope).map(by.inputKey -> _.run)
        ),
        d.picks.toList.traverse((gid, p) => pick(p).map(gid -> _))
      ).mapN((card, surfaces, calls, initials, choices, picks) =>
        Dashboard(
          d.cards,
          card,
          d.theme,
          surfaces.toMap,
          picks.toMap,
          d.slug,
          d.title,
          d.css,
          d.cardCss,
          d.cardScripts,
          d.referencedEntities,
          d.watchedEntities,
          calls.toMap,
          initials.toMap,
          choices.toMap,
          d.varScopes,
          d.warnings
        )
      )

    /** The node at `at`, its descendants found by step in the index. */
    def node(i: TreeIndex, at: Ix): Built[Component] =
      val c = i.node(at)
      val kids = i.children(at)
      val byStep = kids.flatMap(k => i.step(k).map(_ -> k)).toMap
      val placed = placedCalls(c)
      val seenSlots = placed.toList.flatMap((name, seen) =>
        seen.map((field, b) => (Ready.seenSlot(name, field): SlotKey) -> b)
      )
      (
        (c.bound.toList ++ seenSlots).traverse((k, b) => slot(b).map(k -> _)),
        c.slotNodes.toList.traverse((r, ns) =>
          ns.indices.toList
            .traverse(n => node(i, byStep(LayoutNode.Step(r, n))))
            .map(r -> _)
        ),
        c.set.traverse((region, s) => set(i, i.clauses(at), region, s))
      ).mapN((slots, regions, filled) =>
        Component(
          i.id(at),
          i.within(at).map(_._2),
          i.group(at),
          c.card,
          slots.toMap,
          regions.toMap,
          c.cell.map(_.ready),
          c.id,
          c.vars,
          c.subjectEntity,
          placed.map((name, seen) =>
            name -> Placed(
              c.calls(name).id,
              seen.map((field, _) => field -> Ready.seenSlot(name, field))
            )
          ),
          filled,
          c.chosen.keySet
        )
      )

    /** Per call the node places, each field of what it does that reads live
      * state, as the slot that holds it: the press is held to what the page
      * last knew, whether or not anything paints it, since a page that is
      * behind is behind everywhere. A fetch's answer moves between the render
      * and the press, and a posted value exists only at the press, so a field
      * reading either is decided there.
      */
    private def placedCalls(
        c: LayoutNode.Component
    ): Map[String, List[(CallField, BoundSlot)]] =
      if (c.calls.isEmpty) Map.empty
      else
        c.calls.map {
          // A choice reads nothing: the press is its value.
          case (name, _: CallDecl.SetVar)     => name -> Nil
          case (name, decl: CallDecl.Service) =>
            name -> decl.doing.collect {
              case (field, e)
                  if e.entityReads.nonEmpty && e.fetches.isEmpty &&
                    !e.readsPosted =>
                field -> BoundSlot(
                  e,
                  Map.empty,
                  signal = Some(SignalBind.Handler),
                  `type` = e.typed(Nil).map(_.out) match
                    case Right(ValueType.Bool)                 => SlotType.Bool
                    case Right(ValueType.Int | ValueType.Real) =>
                      SlotType.Number
                    case _ => SlotType.Text
                )
            }
        }

    def set(
        i: TreeIndex,
        clauses: Map[String, List[Ix]],
        region: String,
        s: Fill.Set
    ): Built[SetFill] =
      (
        s.members.toList.traverse((e, m) =>
          member(i, clauses.getOrElse(e, Nil), m).map(e -> _)
        ),
        s.orderBy match
          case Nil   => Right(None)
          case terms => Evaluator.Keys.of(terms, scope).map(Some(_))
      ).mapN((members, order) =>
        SetFill(region, s.candidates, members.toMap, order, s.limit, s.of)
      )

    private def member(
        i: TreeIndex,
        clauses: List[Ix],
        m: Choice[LayoutNode]
    ): Built[MemberChoice] = m match
      case Choice.Fixed(_) =>
        node(i, clauses.head).map(c => MemberChoice(None, Vector(c), Map.empty))
      case Choice.Chosen(by, cases, _) =>
        (
          condition(by),
          clauses.traverse(node(i, _))
        ).mapN((guard, nodes) =>
          MemberChoice(
            Some(guard),
            nodes.toVector,
            cases.zipWithIndex.map((k, i) => k.is.raw -> i).toMap
          )
        )
    def surface(
        i: TreeIndex,
        root: Ix,
        s: fh.view.model.Surface
    ): Built[Surface] =
      node(i, root).map(content =>
        Surface(
          content,
          s.activation match
            case fh.view.model.Activation.User(d) => d
        )
      )

    def pick(p: fh.view.model.Dashboard.Pick): Built[Pick] =
      condition(p.choice.by).map(by =>
        Pick(
          p.host,
          p.slot,
          p.prefix,
          p.within,
          by,
          Choice.plainVariable(p.choice.by),
          p.cases.toVector,
          p.choice.cases.zipWithIndex.map((k, i) => k.is.raw -> i).toMap,
          p.choice.otherwise.map(_ => p.choice.cases.size)
        )
      )

    private def call(decl: CallDecl): Built[Call] = decl match
      case d: CallDecl.Service => service(d)
      case d: CallDecl.SetVar  => Right(SetVarCall(d))

    private def service(d: CallDecl.Service): Built[ServiceCall] =
      def run(e: ValueExpr) = Evaluator.compile(e, scope.call).map(_.run)
      def keys(m: ListMap[IdKind, List[ValueExpr]]) =
        m.toList
          .traverse((k, es) => es.traverse(run).map(k -> _))
          .map(ListMap.from)
      (
        run(d.service),
        keys(d.target),
        keys(d.exclude),
        d.data.toList.traverse((k, e) => run(e).map(k -> _)).map(ListMap.from)
      ).mapN((service, target, exclude, data) =>
        ServiceCall(
          d,
          service,
          target,
          exclude,
          data,
          SlotAsk.decision(d.all.flatMap(_.fetches))
        )
      )

    private def slot(b: BoundSlot): Built[Slot] =
      for
        typed <- b.typed
        named <- Evaluator.named(b.inputs, scope)
        c <- Evaluator.compiled(b.expr, typed, scope, named)
        valueWhile <- b.valueWhile.traverse(slot)
      yield Slot(b, c.run, valueWhile)

    private def condition(e: ValueExpr): Built[Condition] =
      Evaluator
        .compile(e, scope)
        .map(c => Condition(c.run, e.entities, SlotAsk.decision(e.fetches)))

  /** The wire's whole-dashboard facts, read off it once by the walk. */
  final case class Dashboard(
      cards: Map[String, CardDef],
      card: Node,
      theme: Theme,
      surfaces: Map[String, Surface],
      picks: Map[NodeId, Pick],
      slug: String,
      title: Option[String],
      css: String,
      cardCss: String,
      cardScripts: List[String],
      referencedEntities: Set[String],
      watchedEntities: Set[String],
      calls: Map[CallId, Call],
      // Where a session opens a node variable, by its transform.
      initials: Map[Cel.Key, Cel.Program],
      // Each chosen window's `by`, by its `inputKey`.
      choices: Map[String, Evaluator.Run],
      varScopes: Map[NodeId, Map[String, fh.view.model.Dashboard.InScope]],
      warnings: List[String]
  ):
    /** What a chosen window's `by` reads over `states` and `in`. */
    def answer(
        states: Map[String, EntityState],
        in: Evaluator.Lookup
    ): ValueExpr => Any = by =>
      choices
        .getOrElse(
          by.inputKey,
          throw fh.view.FHError.internal(
            "a chosen window the compile did not see"
          )
        )(null, states, in)
