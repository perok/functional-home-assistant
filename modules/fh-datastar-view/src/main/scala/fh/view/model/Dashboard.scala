package fh.view.model

import api.homeassistant.ServiceTarget
import cats.syntax.all.*
import fh.view.FHError
import fh.view.fetch.FetchRequest
import fh.view.history.Window
import fh.view.runtime.{Cel, Templates}
import io.circe.{Decoder, DecodingFailure, JsonObject}
import io.circe.derivation.{Configuration, ConfiguredDecoder}

import scala.collection.immutable.ListMap

// The wire names each case exactly as here (`WireShapeSuite` checks Pkl's
// literals), `SetNode` included: `LayoutNode.Set` would shadow `scala.Set`.
given Configuration =
  Configuration.default.withDefaults.withDiscriminator("kind")

/** A template slot: what it shows, and how the card places it (Pkl's `Slot`).
  * The wire is flat, the [[ValueExpr]]'s fields beside these, or a bare
  * literal.
  *
  *   - `valueWhile`: a bool over the same subject; while it is not `true` the
  *     slot is `""`, decided before its steps run or any of its fetches is
  *     read.
  *   - `signal`: carry the value as a Datastar signal, so a change is one
  *     signal frame rather than a re-rendered card (ADR 0017). The card puts
  *     `{{{<slot>.bind}}}` on the element beside its `{{<slot>}}` hole.
  *   - `type`: what the card reads the value AS, which the binding is checked
  *     against: a `Class` needs a bool, a `Text` or `Style` text.
  */
final case class TemplateSlot(
    expr: ValueExpr,
    valueWhile: Option[ValueExpr] = None,
    signal: Option[SignalBind] = None,
    `type`: SlotType = SlotType.Text
):
  def literal: Option[String] = expr.literal

  /** The named inputs its CEL declares, beside its node's. */
  def values: Map[String, ValueExpr] = expr.celInputs

  def placement: Placement = Placement(signal, `type`)

object TemplateSlot:
  private val Fields =
    ValueExpr.fieldNames ++ Set("valueWhile", "signal", "type")

  given Decoder[TemplateSlot] = Decoder.instance(c =>
    if (!c.value.isObject) c.as[ValueExpr].map(TemplateSlot(_))
    else
      Strict.only(c, Fields) *> (
        ValueExpr.fields(c),
        c.getOrElse[Option[ValueExpr]]("valueWhile")(None),
        c.getOrElse[Option[SignalBind]]("signal")(None),
        c.getOrElse[SlotType]("type")(SlotType.Text)
      ).mapN(TemplateSlot.apply)
  )

/** How a card places a slot: the signal it is bound as, and what it reads the
  * value as. A template slot's comes off the wire, a cell slot's from its key
  * ([[CellKey.placement]]).
  */
final case class Placement(signal: Option[SignalBind], `type`: SlotType)

object Placement:
  /** A `valueWhile`: decided before the slot it gates, never bound. */
  val Bool: Placement = Placement(None, SlotType.Bool)

/** A slot with its subject named and the expression values its CEL names, the
  * node's and its own: the build's bind pass, run lazily on the node
  * ([[LayoutNode.Component.bound]]). It fixes each name's definition and type,
  * not its value, which is resolved per render.
  *
  * @param subject
  *   the node's: what a condition that names no entity compares.
  */
final case class BoundSlot(
    expr: ValueExpr,
    values: Map[String, ValueExpr],
    valueWhile: Option[BoundSlot] = None,
    signal: Option[SignalBind] = None,
    `type`: SlotType = SlotType.Text,
    subject: Option[String] = None
):
  def literal: Option[String] = expr.literal

  def constant: Option[SlotValue] = expr.constant

  def default: Option[Lit] = expr.default

  /** Its value is classes the theme swaps ([[Step.ThemeClasses]]). */
  lazy val themed: Boolean = expr.steps.lastOption.contains(Step.ThemeClasses)

  def fetch: Option[FetchTemplate] = expr.source match
    case Value.Fetch(f) => Some(f)
    case _              => None

  /** The entity it reads, or a condition is decided over. */
  def entityId: Option[String] = expr.source match
    case Value.Entity(of, _, _, _) => of
    case _ if expr.isCondition     => subject
    case _                         => None

  /** A fetch's answer is never pushed, and a CEL source with no live input is a
    * constant, held like a `once` read.
    */
  def reads: Reads = expr.source match
    case e: Value.Entity                                 => e.reads
    case _: Value.Fetch                                  => Reads.OnRender
    case _: Value.Cel if !values.values.exists(_.isLive) => Reads.Once
    case _                                               => Reads.Live

  def bypassUnavailable: Boolean = expr.source match
    case e: Value.Entity => e.bypassUnavailable
    case _               => false

  def stage: Option[Step.FetchStage] =
    expr.steps.collectFirst { case s: Step.FetchStage => s }

  def isBool: Boolean = `type` == SlotType.Bool

  /** Its type checked in order, and the key of each CEL it compiles: one source
    * compiles differently against other typed names or `value`.
    */
  lazy val typed: Either[ValueExpr.Mistyped, ValueExpr.Typed] =
    expr.typed(ValueExpr.env(values))

  def celKeys: List[Cel.Key] =
    typed.fold(_ => Nil, _.keys) ++ valueWhile.toList.flatMap(_.celKeys)

  /** Its [[valueWhile]] over the entity it is decided over. The one rule the
    * pre-read and the paint share.
    */
  def gate: Option[Fetching.Gate] =
    valueWhile.map(w => Fetching.Gate(w, w.entityId))

  /** Every entity that can move this slot besides its own: those its source and
    * its steps name, those of the live values it reads, and its
    * [[valueWhile]]'s.
    */
  def valueEntities: List[String] =
    (expr.source match
      case _: Value.Entity => expr.operandEntities
      case _               => expr.entities
    ) ++ values.values.toList.flatMap(_.entities) ++
      valueWhile.toList.flatMap(w => w.entityId.toList ++ w.valueEntities)

  def shape: SlotShape =
    fetch.fold(SlotShape.State(this))(q => SlotShape.Fetch(SlotAsk(q, stage)))

  /** What a render must answer before it reads this slot: its own fetch, or the
    * fetches among the values its CEL reads, as their raw answers.
    */
  def fetches: List[SlotAsk] = shape match
    case SlotShape.Fetch(ask) => List(ask)
    case SlotShape.State(_)   =>
      values.values.toList.map(_.source).collect { case Value.Fetch(f) =>
        SlotAsk(f, None)
      }

  /** What the renderer keys a value by (signal names, the once-cache): two
    * nodes saying the same thing over the same values share a signal. A themed
    * value's key has its [[Step.ThemeClasses]], so it never shares with text.
    */
  lazy val valueKey: String =
    expr.key + values.toList
      .sortBy(_._1)
      .map((n, v) => s"|$n=${v.inputKey}")
      .mkString + valueWhile.fold("")(w =>
      s"|while=${w.entityId.getOrElse("")}:${w.valueKey}"
    )

object BoundSlot:
  def bind(values: Map[String, ValueExpr], subject: Option[String])(
      s: TemplateSlot
  ): BoundSlot =
    bindAt(values, s.placement, subject)(s.expr, s.valueWhile)

  /** By the identifiers the expression PARSES to, so a name inside a string
    * literal is not a read.
    */
  def bindAt(
      values: Map[String, ValueExpr],
      at: Placement,
      subject: Option[String]
  )(e: ValueExpr, valueWhile: Option[ValueExpr] = None): BoundSlot =
    val expr = e.about(subject)
    val named = expr.celSources match
      case Nil  => Map.empty[String, ValueExpr]
      case srcs =>
        // The slot's own win a clash, which `validate` refuses anyway.
        val all = values ++ expr.celInputs
        if (all.isEmpty) Map.empty[String, ValueExpr]
        else
          val read = srcs.flatMap(Cel.identifiers).toSet
          all.filter((n, _) => read.contains(n))
    BoundSlot(
      expr,
      named,
      valueWhile.map(bindAt(values, Placement.Bool, subject)(_)),
      at.signal,
      at.`type`,
      subject
    )

/** A value written down, or picked by a node variable from a table the build
  * checked whole (issue #209). A variable's value lives in a URL and a signal,
  * so it is a key rather than the value: a key the table lacks is refused at
  * the write ([[fh.view.runtime.Renderer.refusals]]), never defaulted.
  */
enum Choice[+A] derives CanEqual:
  case Fixed(value: A)
  case Chosen(by: String, options: Map[String, A])

  def variable: Option[String] = this match
    case Fixed(_)      => None
    case Chosen(by, _) => Some(by)

  def all: List[A] = this match
    case Fixed(a)           => List(a)
    case Chosen(_, options) => options.values.toList

  def resolve(env: Map[String, String]): Either[String, A] = this match
    case Fixed(a)            => Right(a)
    case Chosen(by, options) =>
      env
        .get(by)
        .toRight(s"the variable '$by' has no value here")
        .flatMap(value =>
          options
            .get(value)
            .toRight(
              s"'$value' is not one of ${options.keys.toList.sorted.mkString(", ")}"
            )
        )

  def key(of: A => String): String = this match
    case Fixed(a)            => of(a)
    case Chosen(by, options) =>
      s"$$$by{" + options.toList
        .sortBy(_._1)
        .map((k, a) => s"${k.length}:$k=${of(a)}")
        .mkString(";") + "}"

object Choice:

  /** `{"kind": "Chosen", "by": {"var": …}, "options": {…}}`, else the value. */
  given [A: Decoder]: Decoder[Choice[A]] = Decoder.instance { c =>
    c.get[String]("kind") match
      case Right("Chosen") =>
        (c.downField("by").get[String]("var"), c.get[Map[String, A]]("options"))
          .mapN(Chosen(_, _))
      case _ => c.as[A].map(Fixed(_))
  }

/** A history fetch as authored, its window written down or chosen. `history` is
  * the one provider; a second makes this a sum, as [[FetchRequest]] is.
  */
case class FetchTemplate(entity: String, window: Choice[Window])
    derives CanEqual:

  def references: List[String] = window.variable.toList

  def resolve(env: Map[String, String]): Either[String, FetchRequest] =
    window.resolve(env).map(FetchRequest.History(entity, _))

  def key: String =
    s"${FetchTemplate.Provider}($entity,${window.key(_.key)})"

object FetchTemplate:

  val Provider: String = "history"

  given Decoder[FetchTemplate] = Decoder.instance { c =>
    c.get[String]("provider").flatMap {
      case Provider =>
        (c.get[String]("entity"), c.get[Choice[Window]]("window"))
          .mapN(FetchTemplate(_, _))
      case other =>
        Left(
          DecodingFailure(
            s"unknown fetch provider '$other' — one of $Provider",
            c.history
          )
        )
    }
  }

/** Decided once from the slot's source. The match makes a `live`, `once` or
  * signal fetch unreachable.
  */
enum SlotShape derives CanEqual:
  case State(slot: BoundSlot)
  case Fetch(ask: SlotAsk)

/** What a slot asks, unresolved; [[SlotRead]] is it resolved for one render.
  * Its answer fills the hole as `stage` makes it, or as JSON with none.
  */
case class SlotAsk(fetch: FetchTemplate, stage: Option[Step.FetchStage])
    derives CanEqual:

  /** `validate` proved the declared values resolve, and the write refuses a
    * chosen one that does not, so a `Left` here is a bug.
    */
  def resolve(env: Map[String, String]): SlotRead =
    SlotRead(
      fetch
        .resolve(env)
        .fold(
          e => throw FHError.internal(s"${fetch.key} reached a render: $e"),
          identity
        ),
      stage
    )

/** One slot's asks, read only while `gate` holds. */
final case class Fetching(asks: List[SlotAsk], gate: Option[Fetching.Gate])

object Fetching:
  /** A slot's `valueWhile` over `entity` ([[BoundSlot.gate]]). */
  final case class Gate(valueWhile: BoundSlot, entity: Option[String]):
    /** By content, so two slots gated alike share one decision. */
    lazy val key: String = s"${entity.getOrElse("")}|${valueWhile.valueKey}"

/** Fetch and stage: two sizes of one chart share a fetch, not bytes. */
case class SlotRead(fetch: FetchRequest, stage: Option[Step.FetchStage])
    derives CanEqual

/** When a slot is read, and whether that is a reason to re-render:
  *
  *   - `live`: every render, and a change is a render (joins the reverse index
  *     and the render key).
  *   - `onRender`: every render, never a reason for one — a name, a unit. The
  *     only mode structure may use on an entity.
  *   - `once`: memoized per (entity, transform) for the renderer's life, for a
  *     pure function of which entity it is. A rename would not show until
  *     rebuild.
  *
  * Three values, not two flags: `(wake me, never re-read)` is incoherent.
  */
enum Reads(val wire: String) derives CanEqual:
  case Live extends Reads("live")
  case OnRender extends Reads("onRender")
  case Once extends Reads("once")

object Reads:
  given Decoder[Reads] = Decoder[String].emap(s =>
    values
      .find(_.wire == s)
      .toRight(
        s"unknown reads '$s' — one of ${values.map(_.wire).mkString(", ")}"
      )
  )

/** What a card reads a slot's value as. A `Bool` slot decides a class or an
  * attribute by truthiness, where the string `"false"` is ON, so the build
  * refuses a reading on one that does not produce a bool.
  */
enum SlotType derives CanEqual:
  case Text, Bool

object SlotType:
  given Decoder[SlotType] = Decoder[String].emap {
    case "text" => Right(Text)
    case "bool" => Right(Bool)
    case other  => Left(s"unknown slot type: $other")
  }

/** Where a signal slot's value lands (ADR 0017). The renderer emits the
  * attribute, not the card, so the plain form (no binding, no seed) stays one
  * predicate away. On the wire an object: `{"kind": "Style", "property":
  * "--_end"}`.
  *
  *   - `Text`: `data-text`, the element's whole text content.
  *   - `Style`: `data-style:<property>`; the value carries its own unit.
  *   - `Attr`: `data-attr:<name>`; a boolean value sets or removes it
  *     ([[SlotValue]]). The attribute, not the property: for `checked` use
  *     `Bind`.
  *   - `Class`: `data-class:<name>`, present while truthy: `false` and `""` are
  *     off, and `"false"` reads as on.
  *   - `Bind`: two-way `data-bind` on a form control; such a card needs a
  *     client signal in any form.
  *   - `Handler`: no attribute; the value is read by an event handler via
  *     `{{{<slot>.jsValue}}}` or `{{<slot>.signal}}`.
  */
enum SignalBind derives CanEqual:
  case Text
  case Bind
  case Style(property: String)
  case Attr(name: String)
  case Class(name: String)
  case Handler

object SignalBind:

  /** Strict: a typo turned into `data-text` would put a colour in an element's
    * text and look like a rendering bug.
    */
  given Decoder[SignalBind] =
    ConfiguredDecoder.derived(using Strict.configuration)

/** A card in the shared library. What it reads is its `template`'s names
  * ([[fh.view.runtime.Templates.card]]), which `validate` holds every node of
  * it to.
  *
  *   - `wrapAsCell`: off only for a card whose root must stay a direct child of
  *     a structural parent (the tab anchors under `.tabs`). Such a card is
  *     never its own morph target, so `validate` rejects live slots, `cell` and
  *     set-clause use on it.
  *   - `regions`: a card is a leaf (none) or structure (some). Every region is
  *     filled by nodes with ids of their own, so a node's patch never carries a
  *     region's contents and a host changing cannot re-render what it hosts
  *     (ADR 0012). Markup that should move goes in a region as a node; a live
  *     bytes slot on structure is a build error, a signal slot is fine.
  *   - `css`: the card's own structure, after [[Dashboard.css]] and before
  *     `theme.styles` in the cascade (ADR 0020).
  *   - `script`: a classic script the card's markup needs, inlined once per
  *     page ahead of the theme's ([[Dashboard.cardScripts]]); a theme cannot
  *     drop it.
  */
case class CardDef(
    template: String,
    wrapAsCell: Boolean = true,
    regions: Map[String, Region] = Map.empty,
    css: String = "",
    script: Option[String] = None
) derives ConfiguredDecoder {

  /** Eager and baked regions are spelled alike, which lets the document walk
    * thread both into one buffer (issue #237).
    */
  def holeOf(name: String): String = "{{{" + name + "}}}"

  def isStructure: Boolean = regions.nonEmpty

  def bakedRegions: Map[String, Region] =
    regions.filter(_._2.fill == Region.Baked)
}

/** `eager`: the node's own children, in the same render. `baked`: a surface
  * selected per viewer, rendered only while shown.
  */
case class Region(fill: String = Region.Eager) derives ConfiguredDecoder

object Region {
  val Eager: String = "eager"
  val Baked: String = "baked"
}

/** The node's cell slots, which the renderer places on its `.fh-cell` wrapper
  * ([[CellKey]]): classes by name, `"1"` for one always there, and custom
  * properties. In wire order, which is the class attribute's. An object so it
  * can grow `grid_options`-style fields without a wire break.
  */
case class Cell(
    classes: ListMap[String, ValueExpr] = ListMap.empty,
    styles: ListMap[String, ValueExpr] = ListMap.empty
) derives ConfiguredDecoder:
  // Keyed once for the render walk: a `ListMap` iterates by building a list,
  // and each wrapper of each paint walks both.
  lazy val classSlots: List[Cell.Entry] =
    classes.toList.map((n, s) => Cell.Entry(n, CellKey.Class(n), s.constant))
  lazy val styleSlots: List[Cell.Entry] =
    styles.toList.map((p, s) => Cell.Entry(p, CellKey.Style(p), s.constant))

  /** The entries that read: a literal class the renderer writes as it is. */
  def live: List[(CellKey, ValueExpr)] =
    classes.toList.collect {
      case (n, s) if s.constant.isEmpty => CellKey.Class(n) -> s
    } ++ styles.toList.collect {
      case (p, s) if s.constant.isEmpty => CellKey.Style(p) -> s
    }

object Cell:
  final case class Entry(
      name: String,
      key: CellKey,
      constant: Option[SlotValue]
  )

  private given Decoder[ListMap[String, ValueExpr]] = Decoder.instance(c =>
    c.as[JsonObject]
      .flatMap(
        _.keys.toList.traverse(k => c.downField(k).as[ValueExpr].map(k -> _))
      )
      .map(ListMap.from)
  )

/** Where a slot is placed: a template slot by its name, a cell slot by its
  * [[CellKey]]. The name stays a bare `String` so a template's lookup allocates
  * nothing, and a cell key never equals one.
  */
type SlotKey = String | CellKey

enum CellKey:
  case Class(name: String)
  case Style(property: String)

  /** A class is on while its bool holds; a style carries its text. */
  def placement: Placement = this match
    case Class(n) => Placement(Some(SignalBind.Class(n)), SlotType.Bool)
    case Style(p) => Placement(Some(SignalBind.Style(p)), SlotType.Text)

object CellKey:
  given CanEqual[SlotKey, SlotKey] = CanEqual.derived

object SlotKey:
  /** For messages; nothing reads it back. */
  def label(k: SlotKey): String = k match
    case name: String            => name
    case CellKey.Class(name)     => s"cell.classes.$name"
    case CellKey.Style(property) => s"cell.styles.$property"

enum Op:
  case Eq, Ne, Lt, Lte, Gt, Gte

object Op:
  given Decoder[Op] = Decoder[String].emap(s =>
    values
      .find(_.toString.equalsIgnoreCase(s))
      .toRight(s"unknown op: $s")
  )

/** How a [[Surface]] becomes visible.
  *
  *   - `User`: a popup, opened by a tap, optionally from first paint. The
  *     selection is per connection (ADR 0005). Never a baked member.
  *   - `Var`: the member the node variable `name` selects, as seen from the
  *     `bakeInto` node (ADR 0033), at the values [[VarAt]] names. Per viewer,
  *     and declared, so the server knows what selects it.
  *   - `State`: while a subject-free `condition` holds — server truth, the same
  *     for every viewer, so never in a session's open set. First match in
  *     `bakeIndex` order wins; an "else" has none, and always holds.
  *
  * A bake group must be one mode, `Var` or `State` (`validate`), so any member
  * decides it.
  */
enum Activation derives ConfiguredDecoder:
  case User(defaultOpen: Boolean = false)
  case Var(name: String, at: VarAt = VarAt.Index)
  case State(condition: Option[ValueExpr] = None)

/** Which values of an [[Activation.Var]]'s variable select its member: one (a
  * slider's main controls, a `c.select`'s options), its `bakeIndex` as a number
  * (a tab bar's panel), or every value no other member of the group names (an
  * If's else).
  */
enum VarAt derives ConfiguredDecoder, CanEqual:
  case Value(value: String)
  case Index
  case Otherwise

sealed trait LayoutNode derives ConfiguredDecoder {

  /** Only a component: a tab bar's id reaches users through `ui.<id>`, while
    * set members are addressed by entity.
    */
  def authoredId: Option[String] = this match {
    case c: LayoutNode.Component => c.id
    case _: LayoutNode.SetNode   => None
  }
}

object LayoutNode:

  /** Pkl's bare `children { … }` normalises to this before emitting, so no
    * region is nameless downstream. Same string as `core/node.pkl`'s
    * `defaultRegion`.
    */
  val DefaultRegion: String = "children"

  def kids(cs: LayoutNode*): Map[String, List[LayoutNode]] =
    if cs.isEmpty then Map.empty else Map(DefaultRegion -> cs.toList)

  /** Leaves and containers alike: a container is a template splicing its
    * regions, so a new kind needs no Scala. The injected vars are `id`,
    * `hostId` and `dashboardSlug`.
    */
  case class Component(
      card: String,
      templateSlots: Map[String, TemplateSlot] = Map.empty,
      regions: Map[String, List[LayoutNode]] = Map.empty,
      cell: Option[Cell] = None,
      // Replaces the position-derived id and roots its descendants' — see
      // `authoredIdErrors` for why it is checked.
      id: Option[String] = None,
      // Node variables (issue #209), resolved by name up the ancestors,
      // nearest wins. No allowed-values list: the write refuses what a reader
      // cannot parse.
      vars: List[Dashboard.Declaration] = Nil,
      // Expression values, this node's alone: never resolved up the
      // ancestors, and bound to the slots that read them ([[bound]]).
      values: Map[String, ValueExpr] = Map.empty
  ) extends LayoutNode:

    /** [[templateSlots]] and the [[cell]]'s live ones, each with the expression
      * values its CEL names; a cell slot is placed by its key.
      */
    lazy val bound: Map[SlotKey, BoundSlot] =
      templateSlots.map((n, s) =>
        (n: SlotKey) -> BoundSlot.bind(values, subjectEntity)(s)
      ) ++
        cell.toList
          .flatMap(_.live)
          .map((k, s) =>
            k -> BoundSlot.bindAt(values, k.placement, subjectEntity)(s)
          )

    /** Name order, for a reproducible walk; ids do not come from this. */
    def allChildren: List[LayoutNode] =
      regions.toList.sortBy(_._1).flatMap(_._2)

    /** Each declared name and its default. A repeated name keeps the last;
      * `validate` rejects one.
      */
    lazy val defaults: Map[String, String] =
      vars.map(d => d.name -> d.default).toMap

    /** `validate` holds `entity_id` to a literal. */
    lazy val subjectEntity: Option[String] =
      templateSlots.get(Dashboard.SubjectSlot).flatMap(_.literal)

    /** The reverse index. Empty means static HTML, never patched. */
    lazy val liveEntities: List[String] =
      stateSlots
        .filter(s => s.reads == Reads.Live && s.constant.isEmpty)
        .flatMap(s => s.entityId.toList ++ s.valueEntities)
        .distinct

    /** Minus entities reached only through signal slots: what the render key
      * and the structure rule use. The two fail in opposite directions if
      * swapped — this list in the reverse index silently stops signal frames.
      */
    lazy val liveEntitiesAsBytes: List[String] =
      stateSlots
        .filter(s =>
          s.reads == Reads.Live && s.constant.isEmpty && s.signal.isEmpty
        )
        .flatMap(s => s.entityId.toList ++ s.valueEntities)
        .distinct

    /** Every ask, whatever the `valueWhile`s decide: what a variable's value is
      * checked against (ADR 0023). The pre-read and the render key take
      * [[fetching]] as the decisions filter it.
      */
    lazy val fetches: List[SlotAsk] = fetching.flatMap(_.asks).distinct

    /** Per slot, each with the `valueWhile` it waits for. In the render key but
      * not [[liveEntities]]: otherwise a sensor ticking every second would
      * refetch its history every second. Ungated without an entity to decide
      * over: asking too much is safe where too little raises.
      */
    lazy val fetching: List[Fetching] =
      bound.values.toList.flatMap { s =>
        val asks = s.shape match
          case SlotShape.State(src) => src.fetches
          case SlotShape.Fetch(q)   => List(q)
        Option.when(asks.nonEmpty)(
          Fetching(
            asks,
            s.gate.filter(_.entity.isDefined)
          )
        )
      }

    private lazy val stateSlots: List[BoundSlot] =
      bound.values.toList.flatMap(_.shape match
        case SlotShape.State(src) => Some(src)
        case SlotShape.Fetch(_)   => None)

  /** A set over a static candidate list: the runtime decides only presence and
    * order (ADR 0003). An empty `orderBy` means `candidates` is already in
    * order. `limit` applies after ordering.
    */
  case class SetNode(
      candidates: List[String] = Nil,
      members: Map[String, SetMember] = Map.empty,
      orderBy: List[SortTerm] = Nil,
      limit: Option[Int] = None,
      cell: Option[Cell] = None
  ) extends LayoutNode:
    /** Candidates plus any entity a guard names. */
    def liveEntities: List[String] =
      (candidates ++ members.values
        .flatMap(_.clauses)
        .flatMap(_.when.toList.flatMap(_.entities))).distinct

  /** The first clause whose `when` holds decides; none means not rendered. */
  case class SetMember(clauses: List[SetClause] = Nil) derives ConfiguredDecoder

  /** A guard and the complete node it renders; nothing is shared between
    * clauses. An unnamed read in the guard reads the member; none holds.
    */
  case class SetClause(
      when: Option[ValueExpr] = None,
      node: LayoutNode
  ) derives ConfiguredDecoder

  case class Step(region: String, index: Int)

  /** The default region contributes only its index, keeping a tab URL's
    * `ui.<id>` short. `validate` rejects an all-digit region name, so the two
    * shapes cannot be confused. Public because the build's inline-surface hoist
    * must reach the same ids; a second spelling fails silently, with
    * `@@NODE_ID@@` reaching the DOM.
    */
  def segment(s: Step): String =
    if s.region == DefaultRegion then s.index.toString
    else s"${sanitize(s.region)}_${s.index}"

  /** Without the `c` root, for ids nested under something else
    * ([[MemberGraph.innerSetId]]).
    */
  def segments(path: List[Step]): String = path.map(segment).mkString("_")

  /** `c_1_0`; underscore-joined, so also a signal-name fragment. */
  def pathId(path: List[Step]): NodeId =
    NodeId.derived(if path.isEmpty then "c" else s"c_${segments(path)}")

  def rootId(prefix: String, node: LayoutNode): NodeId =
    NodeId.derived(prefix + node.authoredId.getOrElse("c"))

  /** An authored id still carries `prefix`: the runtime attributes a node to
    * its surface by prefix, and two surfaces could otherwise share a name.
    */
  def childId(
      prefix: String,
      parent: NodeId,
      step: Step,
      child: LayoutNode
  ): NodeId =
    child.authoredId.fold(NodeId.derived(s"${parent}_${segment(step)}"))(a =>
      NodeId.derived(prefix + a)
    )

  def nodeId(prefix: String, path: List[Step]): NodeId =
    NodeId.derived(prefix + pathId(path))

  /** Name order, for a reproducible walk; each step names its own region. */
  def steps(children: Map[String, List[LayoutNode]]): List[(Step, LayoutNode)] =
    children.toList.sortBy(_._1).flatMap { case (region, nodes) =>
      nodes.zipWithIndex.map { case (n, i) => Step(region, i) -> n }
    }

  /** An HTML id and signal-name fragment. Hand-rolled, not `replaceAll`, which
    * compiles its pattern per call: 13.4% of a candidate-set page open
    * (`RenderBench.pageSet`). Returns `s` itself when nothing changes.
    */
  def sanitize(s: String): String = {
    var i = 0
    while (i < s.length && safeIdChar(s.charAt(i))) i += 1
    if (i == s.length) s
    else {
      val out = new java.lang.StringBuilder(s.length)
      val _ = out.append(s, 0, i)
      while (i < s.length) {
        val c = s.charAt(i)
        if (safeIdChar(c)) {
          val _ = out.append(c)
          i += 1
        } else {
          val _ = out.append('_')
          // A surrogate pair is one `_`, as it was under the regex.
          i +=
            (if (
               Character.isHighSurrogate(c) && i + 1 < s.length &&
               Character.isLowSurrogate(s.charAt(i + 1))
             ) 2
             else 1)
        }
      }
      out.toString
    }
  }

  private def safeIdChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
      (c >= '0' && c <= '9') || c == '_'

  /** Also spelled by the build's inline-surface hoist, which sees JSON; no
    * clause index, since one clause of a member renders.
    */
  def memberSegment(setId: String, key: String): String =
    s"${setId}_${sanitize(key)}"

  /** A set nested below a set member: `<member>_<clause>_<path>`. The clause
    * keeps two clauses' sets apart, since their member ids are one. Read by the
    * runtime's member graph and walk, and the model's scope walk.
    */
  def innerSetId(member: NodeId, clause: Int, path: List[Step]): NodeId =
    NodeId.derived(s"${member}_${clause}_${segments(path)}")

  /** A child's id, given where a walk is below a set member: a nested set's is
    * [[innerSetId]], everything else's [[childId]].
    */
  def childIdWithin(
      prefix: String,
      parent: NodeId,
      step: Step,
      child: LayoutNode,
      within: Option[(NodeId, Int, List[Step])]
  ): NodeId =
    (child, within) match {
      case (_: SetNode, Some((member, clause, path))) =>
        innerSetId(member, clause, path :+ step)
      case _ => childId(prefix, parent, step, child)
    }

  def surfaceRootId(surfaceId: String): String = s"s_${sanitize(surfaceId)}"

  /** `s_<id>__c_0`; the same scheme as the build-phase hoist. */
  def surfacePrefix(surfaceId: String): String =
    s"${surfaceRootId(surfaceId)}__"

/** All presentation, so the server is tied to no CSS framework.
  *
  *   - `tokens`/`tokensDark`: HA theme variables as `--<name>`; the dark set
  *     applies under `prefers-color-scheme: dark`.
  *   - `stylesheets`: render-blocking.
  *   - `deferredStylesheets`: loaded without blocking first paint, so only for
  *     what the layout survives without (an icon font).
  *   - `scripts`: module scripts, run after first paint.
  *   - `inlineScripts`: classic, run before first paint (document listeners the
  *     first element needs). Emitted verbatim, like `styles` and `chrome`.
  *   - `chrome`: the frame, with the `#dashboard` swap target around
  *     `{{{body}}}` (checked by `validate`) and any popup host. Empty falls
  *     back to a bare `<main id="dashboard">`.
  *   - `classes`: what the theme adds to, or puts in place of, an `fh-` class
  *     in card and cell markup ([[ThemeClasses]]).
  */
case class Theme(
    tokens: Map[String, String] = Map.empty,
    tokensDark: Map[String, String] = Map.empty,
    stylesheets: List[String] = Nil,
    deferredStylesheets: List[String] = Nil,
    scripts: List[String] = Nil,
    inlineScripts: List[String] = Nil,
    styles: String = "",
    chrome: String = "",
    classes: ThemeClasses = ThemeClasses.empty
) derives ConfiguredDecoder

/** A popup, tab panel or `If` branch, rendered only while shown. Chrome-less:
  * the frame around its host lives in the theme or card.
  *
  *   - `bakeInto`/`bakeAs`: the node whose `bakeAs` region receives the
  *     selected member at first paint, so the shown panel needs no round trip.
  *   - `bakeIndex`: the order a user selection indexes and a state selection
  *     walks first-match.
  *   - `activation`: absent means a plain popup.
  *   - `clause`: set by the build on a baked surface inside a set member's
  *     clause ([[group]]).
  */
case class Surface(
    content: LayoutNode,
    bakeInto: Option[NodeId] = None,
    bakeAs: Option[String] = None,
    bakeIndex: Option[Int] = None,
    activation: Activation = Activation.User(false),
    clause: Option[Int] = None
) derives ConfiguredDecoder:

  /** Its bake group. Not `bakeInto` alone: every clause of a member gives its
    * nodes the same ids, so two clauses' sliders would pool their heads into
    * one group (#556).
    */
  def group: Option[NodeId] = bakeInto.map(Surface.groupId(_, clause))

  /** The live-patch target and eviction group: `<bakeInto>_<bakeAs>`, or the
    * popup host. Once: a flip scans every surface for its host
    * (`SurfaceGraph.surfacesAt`), which was most of
    * `RenderBench.publishSetIfsFlip`'s allocation.
    */
  lazy val hostId: DomId = (bakeInto, bakeAs) match
    case (Some(into), Some(as)) => DomId.derived(s"${into}_${as}")
    case _                      => Dashboard.PopupHostId

  /** The value of its variable that selects this member ([[Activation.Var]]).
    */
  def selectedAt: Option[String] = activation match
    case Activation.Var(_, VarAt.Value(v)) => Some(v)
    case Activation.Var(_, VarAt.Index)    => bakeIndex.map(_.toString)
    case _                                 => None

  /** Selected at every value no other member of its group names. */
  def isOtherwise: Boolean = activation match
    case Activation.Var(_, VarAt.Otherwise) => true
    case _                                  => false

object Surface:

  /** `<host>_k<clause>`: no derived node id has a lone `k<n>` segment, since a
    * step is an index or `<region>_<index>` ([[LayoutNode.segment]]) and an
    * entity id carries a `.`.
    */
  def groupId(host: NodeId, clause: Option[Int]): NodeId =
    clause.fold(host)(k => NodeId.derived(s"${host}_k$k"))

  /** What a variable-selected group's members are selected at, for a message:
    * an index group as the range an author thinks of.
    */
  def membersText(values: List[String]): String =
    if (values == values.indices.map(_.toString).toList)
      s"a member index (0..${values.size - 1})"
    else s"one of its members' values (${values.mkString(", ")})"

/** The build phase's artifact.
  *
  *   - `slug`: the site's `dashboards` key, applied by `DashboardBuild.decode`
  *     before validation.
  *   - `css`: the base every dashboard gets, here rather than on [[Theme]] so a
  *     theme cannot drop it, only override it (ADR 0020).
  *   - `title`: `None` falls back to the slug.
  *   - `access`: `None` until `Site.decode` folds in the site default.
  */
case class Dashboard(
    cards: Map[String, CardDef],
    card: LayoutNode,
    theme: Theme = Theme(),
    surfaces: Map[String, Surface] = Map.empty,
    slug: String = "dashboard",
    title: Option[String] = None,
    css: String = "",
    access: Option[Access] = None
):

  /** Every registered card, not only those used: pruning would have to account
    * for surfaces and set clauses (ADR 0020's open work).
    */
  lazy val cardCss: String =
    cards.toList.sortBy(_._1).map(_._2.css).filter(_.nonEmpty).mkString("\n")

  /** Same selection as [[cardCss]]. Distinct, so cards sharing one script run
    * it once.
    */
  lazy val cardScripts: List[String] =
    cards.toList.sortBy(_._1).flatMap(_._2.script).distinct

  /** Every entity this dashboard can ever address — what a viewer's variable
    * value may make a fetch read (ADR 0023). Static and sound: a set's
    * candidate list is fixed at build time (ADR 0003). A tap is bounded by
    * [[calls]] instead.
    */
  lazy val referencedEntities: Set[String] = {
    def fromSlots(
        slots: Map[SlotKey, BoundSlot],
        subject: Option[String]
    ): List[String] =
      slots.values.toList.flatMap(s => s.entityId.toList ++ s.valueEntities) ++
        subject.toList

    def walk(n: LayoutNode): List[String] = n match {
      case c: LayoutNode.Component =>
        fromSlots(c.bound, c.subjectEntity) ++ c.allChildren.flatMap(walk)
      case set: LayoutNode.SetNode =>
        set.candidates ++ set.members.values.toList
          .flatMap(_.clauses)
          .flatMap(cl => walk(cl.node))
    }

    (walk(card) ++ surfaces.values.toList.flatMap(s => walk(s.content))).toSet
  }

  /** Every call a tap of this dashboard declares, as the build spelled it, and
    * what it reaches: the allowlist for the call route (ADR 0023). Read off
    * `tap.pkl`'s route slots. A service the target's state picks declares every
    * arm of its match, since which one is posted is the click's to decide. A
    * call over a set reaches its tally, which the press narrows; one whose card
    * dropped the tally is left out, and `validate` says why.
    */
  lazy val calls: Map[TapCall, CallReach] =
    declaredCalls.collect { case Dashboard.Declared(call, _, Some(reach)) =>
      call -> reach
    }.toMap

  private[model] lazy val declaredCalls: List[Dashboard.Declared] = {
    def services(s: ValueExpr): List[String] =
      s.literal.toList ++ s.steps.flatMap {
        case Step.Match(cases, otherwise) =>
          (otherwise match
            case Value.Literal(Lit.Text(v)) => List(v)
            case _                          => Nil
          ) ++ cases.values.toList.collect { case Lit.Text(v) => v }
        case _ => Nil
      }

    def of(c: LayoutNode.Component): List[Dashboard.Declared] = {
      def lit(name: String) = c.templateSlots.get(name).flatMap(_.literal)
      def reachOf(target: ServiceTarget | CallSet): Option[CallReach] =
        target match {
          case t: ServiceTarget => Some(t)
          case _: CallSet       =>
            lit(Dashboard.TargetSetSlot).flatMap(
              io.circe.parser
                .decode[Value](_)
                .toOption
                .collect { case p: Value.Present => p }
            )
        }
      for {
        kind <- lit(Dashboard.TargetKindSlot).toList
        id <- lit(Dashboard.TargetIdSlot).toList
        target <- TapCall.targetOf(kind, id).toList
        service <- c.templateSlots
          .get(Dashboard.ServiceSlot)
          .toList
          .flatMap(s => services(s.expr))
      } yield Dashboard.Declared(
        TapCall(service, target, lit(Dashboard.DataKeySlot)),
        c.card,
        reachOf(target)
      )
    }

    def walk(n: LayoutNode): List[Dashboard.Declared] = n match {
      case c: LayoutNode.Component =>
        of(c) ++ c.allChildren.flatMap(walk)
      case set: LayoutNode.SetNode =>
        set.members.values.toList
          .flatMap(_.clauses)
          .flatMap(cl => walk(cl.node))
    }

    walk(card) ++ surfaces.toList.sortBy(_._1).flatMap(s => walk(s._2.content))
  }

  /** What the live subscription asks HA for: [[referencedEntities]] plus what
    * only decides — clause guards and state conditions, whose entity may be
    * rendered nowhere. With the narrower set a dashboard paints right and then
    * never reacts.
    */
  lazy val watchedEntities: Set[String] = {
    def deciders(n: LayoutNode): List[String] = n match {
      case c: LayoutNode.Component =>
        c.vars.flatMap(_.initialTransform).map(_.entityId) ++ c.allChildren
          .flatMap(
            deciders
          )
      case set: LayoutNode.SetNode =>
        set.members.values.toList
          .flatMap(_.clauses)
          .flatMap(cl =>
            cl.when.toList.flatMap(_.entities) ++
              deciders(cl.node)
          )
    }

    // A set call's guards are read at the press, and an entity only a guard
    // names would otherwise be missing from the store, failing its guard.
    referencedEntities ++ calls.values.flatMap {
      case p: Value.Present => ValueExpr(p).entities
      case _: ServiceTarget => Nil
    } ++
      deciders(card) ++ surfaces.values.toList.flatMap(s =>
        deciders(s.content) ++ (s.activation match {
          case Activation.State(c) => c.toList.flatMap(_.entities)
          case _: Activation.User | _: Activation.Var => Nil
        })
      )
  }

  /** Human-readable errors, empty when valid. `locateTransform` maps a
    * transform to a source location for the message.
    */
  def validate(
      locateTransform: String => Option[String] = _ => None
  ): List[String] = Validate.errors(this, locateTransform, compiledCards)

  // Each template once, theme classes applied, so what validation reads is
  // what renders.
  private def compiledCards: Map[String, Either[String, Templates.Card]] =
    cards.map((name, cd) =>
      name -> Templates.card(
        name,
        theme.classes.rewriteTemplate(cd.template),
        cd.regions.keySet
      )
    )

  /** Problems that still build but are silent in the browser, both about the
    * popup host only the theme can place (ADR 0002).
    */
  def warnings: List[String] = {
    val popupSurfaces = surfaces.toList.collect {
      case (sid, s) if s.hostId == Dashboard.PopupHostId => sid
    }.sorted
    val host = s"id=\"${Dashboard.PopupHostId}\""
    if (popupSurfaces.isEmpty) Nil
    else if (!theme.chrome.contains(host))
      List(
        s"theme.chrome has no <div $host> host, so these popup surfaces can " +
          s"never be shown: ${popupSurfaces.mkString(", ")}"
      )
    else if (!theme.chrome.contains("{{{popups}}}"))
      List(
        s"theme.chrome's <div $host> host has no {{{popups}}} hole, so a popup " +
          "restored on a refresh arrives only once the stream connects"
      )
    else Nil
  }

  def allFetches: List[SlotRead] =
    (fetchesIn(card) ++ surfaces.values.toList.flatMap(s =>
      fetchesIn(s.content, scopeOf(s))
    )).distinct

  /** Node variables in scope at every node, by name (issue #209). A popup is
    * its own scope root, since one content may be opened from many places. An
    * owned surface (a tab panel, an `If` branch) has exactly one host, so it
    * starts with the scope at its `bakeInto` node: a chooser above a tab bar
    * reaches the charts in its panels. A set member is walked under its own id
    * ([[LayoutNode.memberSegment]], static since the candidates are), starting
    * from the set's scope, so a clause's declaration is that member's own
    * (#556). Every clause of a candidate shares the id, so their scopes merge:
    * a choice survives the member moving to another clause.
    */
  lazy val varScopes: Map[NodeId, Map[String, Dashboard.InScope]] = {
    type Scope = Map[String, Dashboard.InScope]
    // `within`: the member a walk is below, its clause and the steps down.
    def walk(
        node: LayoutNode,
        prefix: String,
        id: NodeId,
        scope: Scope,
        within: Option[(NodeId, Int, List[LayoutNode.Step])]
    ): List[(NodeId, Scope)] =
      node match
        case c: LayoutNode.Component =>
          // A nested declaration replacing the entry is shadowing.
          val here = scope ++ c.vars.map { d =>
            d.name -> Dashboard.InScope(id, d)
          }
          (id -> here) :: LayoutNode.steps(c.regions).flatMap {
            case (step, ch) =>
              walk(
                ch,
                prefix,
                LayoutNode.childIdWithin(prefix, id, step, ch, within),
                here,
                within.map((m, cl, path) => (m, cl, path :+ step))
              )
          }
        case s: LayoutNode.SetNode =>
          (id -> scope) :: s.members.toList.flatMap { (entityId, m) =>
            val member = NodeId.derived(LayoutNode.memberSegment(id, entityId))
            m.clauses.zipWithIndex.flatMap((clause, ci) =>
              walk(clause.node, prefix, member, scope, Some((member, ci, Nil)))
            )
          }
    def merged(xs: List[(NodeId, Scope)]): Map[NodeId, Scope] =
      xs.groupMapReduce(_._1)(_._2)(_ ++ _)
    def tree(sid: String, s: Surface, start: Scope): List[(NodeId, Scope)] = {
      val p = LayoutNode.surfacePrefix(sid)
      walk(s.content, p, LayoutNode.rootId(p, s.content), start, None)
    }
    // A surface waits for the tree holding its host; one whose host is in no
    // tree (`danglingBakes` reports it) starts empty.
    @scala.annotation.tailrec
    def owned(
        done: Map[NodeId, Scope],
        pending: List[(String, Surface)]
    ): Map[NodeId, Scope] = {
      val (ready, waiting) =
        pending.partition(_._2.bakeInto.forall(done.contains))
      if (pending.isEmpty) done
      else if (ready.isEmpty)
        done ++ merged(waiting.flatMap((sid, s) => tree(sid, s, Map.empty)))
      else
        owned(
          done ++ merged(ready.flatMap { (sid, s) =>
            tree(sid, s, s.bakeInto.flatMap(done.get).getOrElse(Map.empty))
          }),
          waiting
        )
    }
    owned(
      merged(walk(card, "", LayoutNode.rootId("", card), Map.empty, None)),
      surfaces.toList.sortBy(_._1)
    )
  }

  /** The declared values a surface's content starts with ([[varScopes]]). */
  def scopeOf(s: Surface): Map[String, String] =
    s.bakeInto
      .flatMap(varScopes.get)
      .fold(Map.empty)(_.view.mapValues(_.declaration.default).toMap)

  /** At declared values. A clause that will not match still contributes: the
    * snapshot is resolved before the walk decides.
    */
  def fetchesIn(
      n: LayoutNode,
      start: Map[String, String] = Map.empty
  ): List[SlotRead] =
    scopedSlots(n, start).flatMap { case (s, scope) =>
      s.fetches.map(_.resolve(scope))
    }.distinct

  private def slotSources(n: LayoutNode): List[BoundSlot] =
    scopedSlots(n, Map.empty).map(_._1)

  /** `scope` is where a tree starts: empty, or a surface's [[scopeOf]]. */
  private def scopedSlots(
      n: LayoutNode,
      scope: Map[String, String]
  ): List[(BoundSlot, Map[String, String])] = n match
    case c: LayoutNode.Component =>
      val here = scope ++ c.defaults
      c.bound.values.toList.map(_ -> here) ++
        c.allChildren.flatMap(scopedSlots(_, here))
    case s: LayoutNode.SetNode =>
      s.members.values.toList
        .flatMap(_.clauses)
        .flatMap(c => scopedSlots(c.node, scope))

  /** The CEL expressions to compile, each with the names it is typed against.
    */
  private def celKeys: List[Cel.Key] =
    ((slotSources(card) ++ surfaces.values.flatMap(s =>
      slotSources(s.content)
    )).toList
      .flatMap(_.celKeys) ++
      (initialKeys(card) ++ surfaces.values.flatMap(s =>
        initialKeys(s.content)
      ))).distinct

  private def initialKeys(n: LayoutNode): List[Cel.Key] = n match
    case c: LayoutNode.Component =>
      c.vars.flatMap(_.initialTransform).map(_.celKey) ++
        c.allChildren.flatMap(initialKeys)
    case s: LayoutNode.SetNode =>
      s.members.values.toList
        .flatMap(_.clauses)
        .flatMap(cl => initialKeys(cl.node))

  /** [[validate]] plus the compiled transforms and templates, so the renderer
    * never recompiles or defends against a bad expression.
    */
  def validated(
      locateTransform: String => Option[String] = _ => None
  ): Either[List[String], Dashboard.Validated] =
    val compiled = compiledCards
    Validate.errors(this, locateTransform, compiled) match
      case Nil =>
        Right(
          Dashboard.Validated(
            this,
            compileTransforms,
            Templates(compiled.collect { case (n, Right(c)) => n -> c })
          )
        )
      case errs => Left(errs)

  // Only after `validate` proved each compiles, so a `Left` cannot occur.
  private def compileTransforms: Map[Cel.Key, Cel.Program] =
    celKeys.flatMap(k => Cel.parse(k).toOption.map(k -> _)).toMap

object Dashboard:

  /** A node variable as seen from a node: who declared it, and how. */
  final case class InScope(declarer: NodeId, declaration: Declaration)

  /** One node's declaration of a node variable: its default, whether a choice
    * is mirrored into the URL (ADR 0005), and where a session opens it instead
    * of at the default, evaluated once as the session opens and never followed,
    * so a head does not move under the viewer's finger.
    */
  final case class Declaration(
      name: String,
      default: String,
      inUrl: Boolean = true,
      initialTransform: Option[InitialTransform] = None
  ) derives ConfiguredDecoder

  /** Where a session opens a node variable: CEL over one entity's state. */
  final case class InitialTransform(entityId: String, transform: String)
      derives ConfiguredDecoder:
    /** The one spelling of its key: validated, compiled and run by it, as a
      * slot's transform is by [[BoundSlot.celKey]].
      */
    def celKey: Cel.Key = Cel.Key(transform)

  /** The slot naming a card's subject, which Pkl names on every other slot that
    * names no entity. Not HA's `entity_id` field or `fh.Entity`'s, which share
    * only the spelling.
    */
  val SubjectSlot: String = "entity_id"

  /** A CEL identifier. A reserved word fails the expression's compile. */
  val ExpressionValueName: scala.util.matching.Regex =
    "[A-Za-z_][A-Za-z0-9_]*".r

  /** A CSS custom property name; a plain property would fight the theme. */
  val CustomProperty: scala.util.matching.Regex = "--[A-Za-z0-9_-]+".r

  given Decoder[Dashboard] = ConfiguredDecoder.derived[Dashboard]

  /** `tap.pkl`'s route slots that [[Dashboard.calls]] reads. */
  val ServiceSlot: String = "service"
  val TargetKindSlot: String = "targetKind"
  val TargetIdSlot: String = "targetId"
  val DataKeySlot: String = "dataKey"
  val TargetSetSlot: String = "targetSet"

  /** A call a node's tap declares; `reach` is `None` where a set call's card
    * placed the route without its tally.
    */
  private[model] final case class Declared(
      call: TapCall,
      card: String,
      reach: Option[CallReach]
  )

  /** Constructed only by [[Dashboard.validated]]: the type is the proof, so its
    * constructor, `apply` and `copy` are private to the model.
    */
  case class Validated private[model] (
      dashboard: Dashboard,
      transforms: Map[Cel.Key, Cel.Program],
      templates: Templates,
      // Resolved once by `Site.decode` ([[withAccess]]). The default is the
      // restrictive one, so forgetting to resolve demands a login.
      access: Access = Access.default
  ):
    def withAccess(siteDefault: Access): Validated =
      copy(access = dashboard.access.getOrElse(siteDefault))

  /** The theme's popup host. The dialog is a `popup` card composed into the
    * surface's content, so the backend renders every surface bare.
    */
  val PopupHostId: DomId = DomId.derived("popups")

  /** The vars the renderer gives every node, a pure function of its id. Here
    * rather than in `Renderer` so [[injectedStatic]] is derived from it.
    */
  def structuralVars(
      id: NodeId,
      hostId: DomId,
      slug: String
  ): Map[String, String] =
    Map(
      "id" -> id,
      "hostId" -> hostId,
      // For a URL built in a template (the slider's commit); a transform
      // reads the same fact as `dashboard_slug`.
      "dashboardSlug" -> slug
    )

  /** The backend-injected vars a template may read without the node setting
    * them.
    */
  val injectedStatic: Set[String] =
    structuralVars(NodeId.derived(""), DomId.derived(""), "").keySet

  /** The chosen member's index, which the renderer gives a bake owner. */
  val BakeIndexVar: String = "bakeIndex"

  /** How a template places a slot's view. */
  def spell(slot: String, view: Templates.View): String = view match
    case Templates.View.Has    => s"{{#$slot.has}}…{{/$slot.has}}"
    case Templates.View.Signal => s"{{$slot.signal}}"
    case v                     => s"{{{$slot.${v.spelling}}}}"

  /** Slots the server reads itself, so no template need name them: the subject
    * and a tap's route ([[calls]]).
    */
  def readByRenderer(slot: String): Boolean =
    slot == SubjectSlot || routeSlots(slot)

  private val routeSlots: Set[String] =
    Set(ServiceSlot, TargetKindSlot, TargetIdSlot, DataKeySlot, TargetSetSlot)
