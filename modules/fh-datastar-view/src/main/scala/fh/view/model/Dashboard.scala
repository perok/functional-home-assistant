package fh.view.model

import api.homeassistant.ServiceTarget
import cats.syntax.all.*
import fh.view.FHError
import fh.view.fetch.FetchRequest
import fh.view.history.Window
import fh.view.runtime.{Cel, Templates}
import io.circe.{Decoder, DecodingFailure, Json, JsonObject}
import io.circe.derivation.{Configuration, ConfiguredDecoder}

import scala.collection.immutable.ListMap

// The wire names each case exactly as here (`WireShapeSuite` checks Pkl's
// literals), `SetNode` included: `LayoutNode.Set` would shadow `scala.Set`.
given Configuration =
  Configuration.default.withDefaults.withDiscriminator("kind")

/** Where one mustache slot gets its value.
  *
  *   - `transform`: a CEL string or a [[Transform.Simple]] object, a condition
  *     included — the form is the tier (ADR 0028) — or, on a fetch slot, a
  *     [[Transform.Stage]]. CEL reaches the slot's own entity and the
  *     expression values it names, the node's and its own ([[BoundSlot]]); a
  *     `Simple` read may name any entity.
  *   - `default`: used when the transform yields `""`, which keeps a numeric
  *     initialiser like `{bri: {{x}}}` valid while a light is off.
  *   - `bypassUnavailable`: show an unavailable entity's raw state instead of
  *     running the transform. Off for an action, a label or a slider position.
  *   - `entityId`: `None` inherits the card's `entity_id`.
  *   - `literal`: a verbatim value, authored as a bare string, or as
  *     `{literal, type}` when typed (a `cssClass`); all else unused.
  *   - `fetch`: a fetch slot, which ignores `reads` and `signal`
  *     ([[SlotShape]]).
  *   - `values`: expression values of this slot alone, beside the node's
  *     ([[BoundSlot]]); a name in both is a build error.
  */
case class SlotSource(
    entityId: Option[String] = None,
    transform: String | Transform.Simple | Transform.Stage =
      Transform.Simple.State(),
    default: Option[String] = None,
    bypassUnavailable: Boolean = true,
    literal: Option[String] = None,
    reads: String = Reads.Live,
    fetch: Option[FetchTemplate] = None,
    values: Map[String, ExprValue] = Map.empty,
    // A `bool` slot over the same subject: while it is not `true` this slot is
    // `""`, decided before its transform runs or any of its fetches is read.
    valueWhileSlot: Option[SlotSource] = None
) {

  /** Total so `validate` can call it; `validate` rejects a fetch slot whose
    * transform is not a stage, so the fallback never renders.
    */
  def stage: Transform.Stage = transform match {
    case st: Transform.Stage => st
    case _                   => Transform.Stage.Passthrough
  }

  /** The transform's own key; [[BoundSlot.valueKey]] adds the values it reads.
    * Lazy: the `Simple` arm builds a string, 4.3% of a signals tick's
    * allocation as a `def` (`RenderBench.resumeSignals`).
    */
  lazy val valueKey: String = transform match {
    case s: String            => s
    case sm: Transform.Simple => Transform.Simple.key(sm)
    case st: Transform.Stage  => Transform.Stage.key(st)
  }
}

/** A template slot: what to read, and how the card places it (Pkl's `Slot`).
  * The wire is flat, the reading's fields beside these two.
  *
  *   - `signal`: carry the value as a Datastar signal, so a change is one
  *     signal frame rather than a re-rendered card (ADR 0017). The card puts
  *     `{{{<slot>.bind}}}` on the element beside its `{{<slot>}}` hole.
  *   - `type`: what the card reads the value AS. Declared where the binding
  *     cannot say it: a `class:` binding is a bool by kind.
  */
case class TemplateSlot(
    source: SlotSource,
    signal: Option[SignalBind] = None,
    `type`: SlotType = SlotType.Text
):
  export source.{
    entityId,
    transform,
    default,
    bypassUnavailable,
    literal,
    reads,
    fetch,
    values,
    valueWhileSlot,
    valueKey
  }

object TemplateSlot:
  given Decoder[TemplateSlot] = Decoder.instance(c =>
    if (c.value.isString) c.as[SlotSource].map(TemplateSlot(_))
    else
      (
        c.as[SlotSource],
        c.getOrElse[Option[SignalBind]]("signal")(None),
        c.getOrElse[SlotType]("type")(SlotType.Text)
      ).mapN(TemplateSlot.apply)
  )

/** A slot with the expression values its CEL names, the node's and its own: the
  * build's bind pass, run lazily on the node ([[LayoutNode.Component.bound]]).
  * It fixes each name's definition and type, not its value, which is resolved
  * per render.
  */
final case class BoundSlot(
    source: SlotSource,
    values: Map[String, ExprValue],
    valueWhile: Option[BoundSlot] = None,
    signal: Option[SignalBind] = None,
    `type`: SlotType = SlotType.Text
):
  export source.{
    entityId,
    transform,
    default,
    bypassUnavailable,
    literal,
    reads,
    fetch,
    stage
  }

  def isBool: Boolean =
    `type` == SlotType.Bool || signal.exists(_.isInstanceOf[SignalBind.Class])

  /** What the compiled program is keyed by: one source compiles differently
    * against a different set of typed names. None for a literal, which runs no
    * program.
    */
  lazy val celKey: Option[Transform.CelKey] = transform match
    case t: String if literal.isEmpty =>
      Some(Transform.CelKey(t, ExprValue.env(values)))
    case _ => None

  def celKeys: List[Transform.CelKey] =
    celKey.toList ++ valueWhile.toList.flatMap(_.celKeys)

  /** Its [[valueWhile]] over the entity it is decided over: its own, else the
    * slot's. The one rule the pre-read and the paint share.
    */
  def gate(slotEntity: Option[String]): Option[Fetching.Gate] =
    valueWhile.map(w => Fetching.Gate(w, w.entityId.orElse(slotEntity)))

  /** Every entity that can move this slot besides its own: those its transform
    * names, those of the live values it reads, and its [[valueWhile]]'s.
    */
  def valueEntities: List[String] =
    (transform match
      case s: Transform.Simple => Transform.Simple.referencedEntities(s)
      case _                   => Nil
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
      values.values.toList.collect { case ExprValue.Fetched(f) =>
        SlotAsk(f, Transform.Stage.Passthrough)
      }

  /** What the renderer keys a value by (signal names, the once-cache): two
    * nodes saying the same thing over the same values share a signal. A
    * `CssClass` value is the theme's swap of it, so it never shares with text.
    */
  lazy val valueKey: String =
    source.valueKey + values.toList
      .sortBy(_._1)
      .map((n, v) => s"|$n=${ExprValue.key(v)}")
      .mkString + valueWhile.fold("")(w =>
      s"|while=${w.entityId.getOrElse("")}:${w.valueKey}"
    ) + (if (`type` == SlotType.CssClass) "|cssClass" else "")

object BoundSlot:
  /** By the identifiers the expression PARSES to, so a name inside a string
    * literal is not a read.
    */
  def bind(values: Map[String, ExprValue])(s: TemplateSlot): BoundSlot =
    bindAs(values, s.signal, s.`type`)(s.source)

  /** `s` placed as `signal` and read as `tpe`: a template slot's own, or what
    * its position says, which has nothing on the wire to disagree with.
    */
  def bindAs(
      values: Map[String, ExprValue],
      signal: Option[SignalBind],
      tpe: SlotType
  )(s: SlotSource): BoundSlot =
    // The slot's own win a clash, which `validate` refuses anyway.
    val all = values ++ s.values
    val valueWhile =
      s.valueWhileSlot.map(bindAs(values, None, SlotType.Bool))
    val named = s.transform match
      case t: String if all.nonEmpty && s.literal.isEmpty =>
        val read = Cel.identifiers(t)
        all.filter((n, _) => read.contains(n))
      case _ => Map.empty[String, ExprValue]
    BoundSlot(s, named, valueWhile, signal, tpe)

/** A named input of a node's or a slot's expressions (`expressionValues`): a
  * literal the build wrote down, a [[Predicate.Tally]] counted live, a
  * condition decided live — `c.iff`'s, read as a `bool` — or a fetch, read as
  * its provider's answer.
  */
enum ExprValue derives CanEqual:
  case Text(value: String)
  // A Pkl `Int`; a `Float` is `Real` even when whole, decoded by spelling.
  case Whole(value: Long)
  case Real(value: Double)
  case Flag(value: Boolean)
  case Count(tally: Predicate.Tally)
  case Holds(condition: Predicate)
  case Fetched(fetch: FetchTemplate)

  def kind: ExprValue.Kind = this match
    case _: Text             => ExprValue.Kind.Str
    case _: Whole | _: Count => ExprValue.Kind.Int
    case _: Real             => ExprValue.Kind.Dbl
    case _: Flag | _: Holds  => ExprValue.Kind.Bool
    // `history` is the one provider (`FetchTemplate`).
    case _: Fetched => ExprValue.Kind.Series

  /** Live: what it answers moves, so a once-read cannot hold it. */
  def isLive: Boolean = this match
    case _: Count | _: Holds | _: Fetched => true
    case _                                => false

  /** Every entity that can move it. */
  def entities: List[String] = this match
    case Count(t) => t.referencedEntities
    case Holds(p) => Predicate.referencedEntities(p)
    case _        => Nil

object ExprValue:

  /** The CEL type a value is declared as. `Series` is a history answer's
    * points, `list(list(double))` of `[epochMillis, value]`.
    */
  enum Kind derives CanEqual:
    case Str, Int, Dbl, Bool, Series

  def env(values: Map[String, ExprValue]): List[(String, Kind)] =
    values.toList.sortBy(_._1).map((n, v) => n -> v.kind)

  /** Canonical, unlike `toString`: a small `Map` keeps insertion order. */
  def key(v: ExprValue): String = v match
    case Text(s)    => s"s:$s"
    case Whole(n)   => s"i:$n"
    case Real(d)    => s"d:$d"
    case Flag(b)    => s"b:$b"
    case Count(t)   => s"t:${Predicate.tallyKey(t)}"
    case Holds(p)   => s"p:${Predicate.key(p)}"
    case Fetched(f) => s"f:${f.key}"

  given Decoder[ExprValue] = Decoder.instance { c =>
    c.value.fold(
      Left(DecodingFailure("an expression value cannot be null", c.history)),
      b => Right(Flag(b)),
      // By SPELLING, as Pkl wrote it: a `Float` 2.0 and an `Int` 2 have one
      // value, and an expression typed against the wrong one fails its build.
      n =>
        Right(
          if (n.toString.exists(ch => ch == '.' || ch == 'e' || ch == 'E'))
            Real(n.toDouble)
          else n.toLong.fold(Real(n.toDouble))(Whole(_))
        ),
      s => Right(Text(s)),
      _ =>
        Left(
          DecodingFailure("an expression value cannot be a list", c.history)
        ),
      // A condition carries its `kind`, a fetch its `provider`; a tally neither.
      o =>
        if (o.contains("kind")) c.as[Predicate].map(Holds(_))
        else if (o.contains("provider")) c.as[FetchTemplate].map(Fetched(_))
        else c.as[Predicate.Tally].map(Count(_))
    )
  }

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

/** Decided once from the wire form, which keeps one `SlotSource` so snapshots
  * do not churn. The match makes a `live`, `once` or signal fetch unreachable.
  */
enum SlotShape derives CanEqual:
  case State(slot: BoundSlot)
  case Fetch(ask: SlotAsk)

/** What a slot asks, unresolved; [[SlotRead]] is it resolved for one render. */
case class SlotAsk(fetch: FetchTemplate, stage: Transform.Stage)
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
case class SlotRead(fetch: FetchRequest, stage: Transform.Stage)
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
object Reads:
  val Live: String = "live"
  val OnRender: String = "onRender"
  val Once: String = "once"

  val All: Set[String] = Set(Live, OnRender, Once)

object SlotSource:
  // Lazy and by name: `valueWhileSlot` makes the derivation recursive.
  private lazy val objDecoder: Decoder[SlotSource] = ConfiguredDecoder.derived

  given Decoder[SlotSource] = Decoder.instance(c =>
    c.as[String].map(s => SlotSource(literal = Some(s))).orElse(objDecoder(c))
  )

  /** Chosen by key, not by trying each arm, so a bad chart param reports the
    * stage's error rather than the last arm's.
    */
  given Decoder[String | Transform.Simple | Transform.Stage] =
    Decoder.instance { c =>
      if (c.value.isString) c.as[String]
      else if (c.downField("stage").succeeded) c.as[Transform.Stage]
      else c.as[Transform.Simple]
    }

/** What a card reads a slot's value as. A `Bool` slot decides a class or an
  * attribute by truthiness, where the string `"false"` is ON, so the build
  * refuses a reading on one that does not produce a bool. A `CssClass` slot
  * sits in a `class` attribute, so the renderer passes its value through the
  * theme's class rules (ADR 0020).
  */
enum SlotType derives CanEqual:
  case Text, Bool, CssClass

object SlotType:
  given Decoder[SlotType] = Decoder[String].emap {
    case "text"     => Right(Text)
    case "bool"     => Right(Bool)
    case "cssClass" => Right(CssClass)
    case other      => Left(s"unknown slot type: $other")
  }

/** Where a signal slot's value lands (ADR 0017). The renderer emits the
  * attribute, not the card, so the plain form (no binding, no seed) stays one
  * predicate away. One wire string: `"text"`, `"bind"`, `"style:--_end"`,
  * `"attr:title"`.
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

  /** No default for an unknown spelling: a typo turned into `data-text` would
    * put a colour in an element's text and look like a rendering bug.
    */
  def parse(s: String): Option[SignalBind] = s.split(":", 2).toList match
    case "text" :: Nil          => Some(Text)
    case "bind" :: Nil          => Some(Bind)
    case "style" :: prop :: Nil => Option.when(prop.nonEmpty)(Style(prop))
    case "attr" :: name :: Nil  => Option.when(name.nonEmpty)(Attr(name))
    case "class" :: name :: Nil => Option.when(name.nonEmpty)(Class(name))
    case "handler" :: Nil       => Some(Handler)
    case _                      => None

  given Decoder[SignalBind] =
    Decoder[String].emap(s => parse(s).toRight(s"unknown signal binding: $s"))

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
    classes: ListMap[String, SlotSource] = ListMap.empty,
    styles: ListMap[String, SlotSource] = ListMap.empty
) derives ConfiguredDecoder:
  // Keyed once for the render walk: a `ListMap` iterates by building a list,
  // and each wrapper of each paint walks both.
  lazy val classSlots: List[Cell.Entry] =
    classes.toList.map((n, s) => Cell.Entry(n, CellKey.Class(n), s.literal))
  lazy val styleSlots: List[Cell.Entry] =
    styles.toList.map((p, s) => Cell.Entry(p, CellKey.Style(p), s.literal))

object Cell:
  final case class Entry(name: String, key: CellKey, literal: Option[String])

  private given Decoder[ListMap[String, SlotSource]] = Decoder.instance(c =>
    c.as[JsonObject]
      .flatMap(
        _.keys.toList.traverse(k => c.downField(k).as[SlotSource].map(k -> _))
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

/** `property` is `"domain"`, `"state"` or `"attr:<name>"`. */
sealed trait Predicate derives ConfiguredDecoder
object Predicate:
  case class And(items: List[Predicate]) extends Predicate
  case class Or(items: List[Predicate]) extends Predicate
  case class Not(item: Predicate) extends Predicate

  /** `entity` absent means the subject. A named one must reach the reverse
    * index ([[referencedEntities]]) or the node is never woken by it.
    */
  case class Cmp(
      property: String,
      op: Op,
      value: Json,
      entity: Option[String] = None
  ) extends Predicate

  /** How many of a static candidate list are present, against a number — which
    * retires the quantifiers (`any` is `> 0`, `all` is `== length`). `when`
    * guards per candidate, as a [[LayoutNode.SetMember]] does, because
    * residuals diverge across one set's candidates. Subject-independent.
    */
  case class Count(
      candidates: List[String] = Nil,
      when: Map[String, Predicate] = Map.empty,
      op: Op,
      value: Json
  ) extends Predicate:
    def tally: Tally = Tally(candidates, when)

  /** Whether `entity` is the first present candidate under `orderBy`, ties
    * going to candidate order. Subject-independent, as a [[Count]] is.
    */
  case class First(
      candidates: List[String] = Nil,
      when: Map[String, Predicate] = Map.empty,
      orderBy: List[LayoutNode.SortTerm] = Nil,
      entity: String
  ) extends Predicate:
    def tally: Tally = Tally(candidates, when)

    /** The sort reads every candidate's state, so a holds-key's own entities
      * can move the leader too.
      */
    def referencedEntities: List[String] =
      tally.referencedEntities ++ orderBy.flatMap(_.by match
        case LayoutNode.SortKey.Holds(p) => Predicate.referencedEntities(p)
        case _: LayoutNode.SortKey.Prop  => Nil)

  /** A static candidate list and which of it is present: a [[Count]] before the
    * comparison.
    */
  case class Tally(
      candidates: List[String] = Nil,
      when: Map[String, Predicate] = Map.empty
  ) derives ConfiguredDecoder:
    def referencedEntities: List[String] =
      candidates ++ when.values.flatMap(Predicate.referencedEntities)

  def referencedEntities(p: Predicate): List[String] = p match
    case Cmp(_, _, _, e) => e.toList
    case And(items)      => items.flatMap(referencedEntities)
    case Or(items)       => items.flatMap(referencedEntities)
    case Not(item)       => referencedEntities(item)
    case c: Count        => c.tally.referencedEntities
    case f: First        => f.referencedEntities

  def tallyKey(t: Predicate.Tally): String =
    t.candidates.mkString(",") + "/" +
      t.when.toList
        .sortBy(_._1)
        .map((id, p) => s"$id=${key(p)}")
        .mkString(";")

  /** Canonical, unlike `toString`: a small `Map` keeps insertion order. */
  def key(p: Predicate): String = p match
    case Predicate.Cmp(prop, op, v, e) =>
      s"cmp($prop,$op,${v.noSpaces},${e.getOrElse("")})"
    case Predicate.And(xs)  => xs.map(key).mkString("and(", ",", ")")
    case Predicate.Or(xs)   => xs.map(key).mkString("or(", ",", ")")
    case Predicate.Not(x)   => s"not(${key(x)})"
    case c: Predicate.Count =>
      s"count(${tallyKey(c.tally)},${c.op},${c.value.noSpaces})"
    case f: Predicate.First =>
      s"first(${tallyKey(f.tally)},${f.orderBy.map(sortKey).mkString(";")},${f.entity})"

  private def sortKey(t: LayoutNode.SortTerm): String = t.by match
    case LayoutNode.SortKey.Prop(p)  => s"${t.dir}:prop($p)"
    case LayoutNode.SortKey.Holds(p) => s"${t.dir}:holds(${key(p)})"

  /** Rejected under an [[Activation.State]], which supplies no subject. The
    * guards of a count or a leader are bound to their own candidates, so they
    * do not count.
    */
  def hasFreeSubject(p: Predicate): Boolean = p match
    case Cmp(_, _, _, entity) => entity.isEmpty
    case And(items)           => items.exists(hasFreeSubject)
    case Or(items)            => items.exists(hasFreeSubject)
    case Not(item)            => hasFreeSubject(item)
    case _: Count | _: First  => false

/** How a [[Surface]] becomes visible.
  *
  *   - `User`: a popup, opened by a tap, optionally from first paint. The
  *     selection is per connection (ADR 0005). Never a baked member.
  *   - `Var`: the member the node variable `name` selects, as seen from the
  *     `bakeInto` node (ADR 0033), at the values [[VarAt]] names. Per viewer,
  *     and declared, so the server knows what selects it.
  *   - `State`: while a subject-free `condition` holds — server truth, the same
  *     for every viewer, so never in a session's open set. First match in
  *     `bakeIndex` order wins; an "else" is `And(Nil)`.
  *
  * A bake group must be one mode, `Var` or `State` (`validate`), so any member
  * decides it.
  */
enum Activation derives ConfiguredDecoder:
  case User(defaultOpen: Boolean = false)
  case Var(name: String, at: VarAt = VarAt.Index)
  case State(condition: Predicate)

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
      slots: Map[String, TemplateSlot] = Map.empty,
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
      values: Map[String, ExprValue] = Map.empty
  ) extends LayoutNode:

    /** [[slots]] and the [[cell]]'s, each with the expression values its CEL
      * names. A cell slot's binding and type are its key's: a class is a bool,
      * bound by name unless it is a literal, which is always there.
      */
    lazy val bound: Map[SlotKey, BoundSlot] =
      val cellSlots = cell.toList.flatMap { cl =>
        def at(signal: SignalBind, tpe: SlotType)(s: SlotSource) =
          BoundSlot.bindAs(values, Option.when(s.literal.isEmpty)(signal), tpe)(
            s
          )
        cl.classes.toList.map((name, s) =>
          CellKey.Class(name) -> at(SignalBind.Class(name), SlotType.Bool)(s)
        ) ++ cl.styles.toList.map((p, s) =>
          CellKey.Style(p) -> at(SignalBind.Style(p), SlotType.Text)(s)
        )
      }
      slots.map((n, s) => (n: SlotKey) -> BoundSlot.bind(values)(s)) ++
        cellSlots

    /** Name order, for a reproducible walk; ids do not come from this. */
    def allChildren: List[LayoutNode] =
      regions.toList.sortBy(_._1).flatMap(_._2)

    /** Each declared name and its default. A repeated name keeps the last;
      * `validate` rejects one.
      */
    lazy val defaults: Map[String, String] =
      vars.map(d => d.name -> d.default).toMap

    /** Only a literal `entity_id`: a transform one resolves at render time. */
    lazy val subjectEntity: Option[String] =
      slots.get(Dashboard.SubjectSlot).flatMap(_.literal)

    /** The reverse index. Empty means static HTML, never patched. */
    lazy val liveEntities: List[String] =
      stateSlots
        .filter(s => s.reads == Reads.Live && s.literal.isEmpty)
        .flatMap(s =>
          s.entityId.orElse(subjectEntity).toList ++ s.valueEntities
        )
        .distinct

    /** Minus entities reached only through signal slots: what the render key
      * and the structure rule use. The two fail in opposite directions if
      * swapped — this list in the reverse index silently stops signal frames.
      */
    lazy val liveEntitiesAsBytes: List[String] =
      stateSlots
        .filter(s =>
          s.reads == Reads.Live && s.literal.isEmpty && s.signal.isEmpty
        )
        .flatMap(s =>
          s.entityId.orElse(subjectEntity).toList ++ s.valueEntities
        )
        .distinct

    /** Every ask, whatever the `valueWhile`s decide: what a variable's value is
      * checked against (ADR 0023). The pre-read and the render key take
      * [[fetching]] as the decisions filter it.
      */
    lazy val fetches: List[SlotAsk] = fetching.flatMap(_.asks).distinct

    /** Per slot, each with the `valueWhile` it waits for. In the render key but
      * not [[liveEntities]]: otherwise a sensor ticking every second would
      * refetch its history every second. Ungated without an entity to decide
      * over: a dynamic subject is known only per paint, and asking too much is
      * safe where too little raises.
      */
    lazy val fetching: List[Fetching] =
      bound.values.toList.flatMap { s =>
        val asks = s.shape match
          case SlotShape.State(src) => src.fetches
          case SlotShape.Fetch(q)   => List(q)
        Option.when(asks.nonEmpty)(
          Fetching(
            asks,
            s.gate(s.entityId.orElse(subjectEntity)).filter(_.entity.isDefined)
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
        .flatMap(c =>
          c.when.toList.flatMap(Predicate.referencedEntities)
        )).distinct

  /** The first clause whose `when` holds decides; none means not rendered. */
  case class SetMember(clauses: List[SetClause] = Nil) derives ConfiguredDecoder

  /** A guard and the complete node it renders; nothing is shared between
    * clauses.
    */
  case class SetClause(
      when: Option[Predicate] = None,
      node: LayoutNode
  ) derives ConfiguredDecoder

  /** Present only when ordering needs live state; otherwise
    * [[SetNode.candidates]] is pre-sorted.
    */
  case class SortTerm(
      by: SortKey,
      dir: String = "asc"
  ) derives ConfiguredDecoder:
    def descending: Boolean = dir == "desc"

  /** A value has an order, a predicate only true/false; neither expresses the
    * other.
    */
  sealed trait SortKey derives ConfiguredDecoder
  object SortKey:
    /** `state`, `attr:<name>`, `reg:<name>`, as in [[Predicate.Cmp]]. */
    case class Prop(property: String) extends SortKey

    /** True first under `asc`. */
    case class Holds(predicate: Predicate) extends SortKey

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
    * popup host.
    */
  def hostId: DomId = (bakeInto, bakeAs) match
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

  private lazy val declaredCalls: List[Dashboard.Declared] = {
    def services(s: SlotSource): List[String] =
      s.literal.toList ++ (s.transform match {
        case Transform.Simple.Match(cases, otherwise, _, _) =>
          (otherwise :: cases.values.toList).collect { case v: String => v }
        case _ => Nil
      })

    def of(c: LayoutNode.Component): List[Dashboard.Declared] = {
      def lit(name: String) = c.slots.get(name).flatMap(_.literal)
      def reachOf(target: ServiceTarget | CallSet): Option[CallReach] =
        target match {
          case t: ServiceTarget => Some(t)
          case _: CallSet       =>
            lit(Dashboard.TargetSetSlot).flatMap(
              io.circe.parser.decode[Predicate.Tally](_).toOption
            )
        }
      for {
        kind <- lit(Dashboard.TargetKindSlot).toList
        id <- lit(Dashboard.TargetIdSlot).toList
        target <- TapCall.targetOf(kind, id).toList
        service <- c.slots
          .get(Dashboard.ServiceSlot)
          .toList
          .flatMap(s => services(s.source))
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
            cl.when.toList.flatMap(Predicate.referencedEntities) ++
              deciders(cl.node)
          )
    }

    // A set call's guards are read at the press, and an entity only a guard
    // names would otherwise be missing from the store, failing its guard.
    referencedEntities ++ calls.values.flatMap {
      case t: Predicate.Tally => t.referencedEntities
      case _: ServiceTarget   => Nil
    } ++
      deciders(card) ++ surfaces.values.toList.flatMap(s =>
        deciders(s.content) ++ (s.activation match {
          case Activation.State(c) => Predicate.referencedEntities(c)
          case _: Activation.User | _: Activation.Var => Nil
        })
      )
  }

  /** Human-readable errors, empty when valid. `locateTransform` maps a
    * transform to a source location for the message.
    */
  def validate(
      locateTransform: String => Option[String] = _ => None
  ): List[String] = errors(locateTransform, compiledCards)

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

  private def errors(
      locateTransform: String => Option[String],
      compiled: Map[String, Either[String, Templates.Card]]
  ): List[String] =
    val cardReads: Map[String, Either[String, Templates.Reads]] =
      compiled.view.mapValues(_.map(_.reads)).toMap

    // A section marks its names optional, and the library guards one name by
    // another's section (`{{#service}}…{{targetId}}`), so only a name outside
    // every section must be set; a misspelt section shows as its slot unread.
    def templateErrors(
        nodeId: String,
        cardName: String,
        slotNames: Set[String],
        filled: Set[String]
    ): List[String] =
      cards.get(cardName) match
        case None =>
          List(s"$nodeId: references unknown card '$cardName'")
        case Some(cd) =>
          val undeclared = (filled -- cd.regions.keySet).toList.sorted.map(r =>
            s"$nodeId: fills region '$r', which card '$cardName' does not " +
              "declare — its children would never render"
          ) ++ slotNames
            .intersect(cd.regions.keySet)
            .toList
            .sorted
            .map(r =>
              s"$nodeId: slot '$r' is named like card '$cardName''s region, " +
                "whose children its hole shows instead — rename the slot"
            )
          val names = cardReads.get(cardName).flatMap(_.toOption)
          val known = slotNames ++ Dashboard.injectedStatic ++
            cd.regions.keySet ++
            Option.when(cd.bakedRegions.nonEmpty)(Dashboard.BakeIndexVar)
          val unset = names.toList
            .flatMap(_.required)
            // An old spelling is the card's error ([[viewErrors]]), once.
            .filterNot(n => known(n) || Dashboard.oldSpelling(n).isDefined)
            .sorted
          val read = names.toList.flatMap(_.names).toSet
          val unread = names.toList.flatMap(_ =>
            slotNames.toList.sorted.filterNot(s =>
              read(s) || Dashboard.readByRenderer(s)
            )
          )
          val dotted = slotNames
            .filter(_.contains('.'))
            .toList
            .sorted
            .map(s =>
              s"$nodeId: slot '$s' has a '.' in its name, which a template " +
                "reads as one of its views ({{{x.bind}}}) — rename the slot"
            )
          undeclared ++ dotted ++
            Option
              .when(unset.nonEmpty)(
                s"$nodeId: card '$cardName' missing slots: " +
                  unset.mkString(", ") +
                  " — its template reads them; set \"\" for nothing"
              )
              .toList ++
            Option
              .when(unread.nonEmpty)(
                s"$nodeId: card '$cardName' never reads slots: " +
                  unread.mkString(", ") +
                  " — its template names none of them; drop them"
              )
              .toList

    def slotErrors(
        nodeId: String,
        cardName: String,
        slots: Map[SlotKey, BoundSlot],
        scope: Map[String, String]
    ): List[String] =
      labelled(slots).flatMap { case (key, name, src) =>
        val transformError =
          // A fetch slot's transform is `fetchErrors`' to check.
          if (src.literal.isDefined || src.fetch.isDefined) None
          else
            src.transform match {
              case p: Transform.Simple.Percent if p.max == p.min =>
                Some(
                  s"$nodeId: slot '$name' has a degenerate percent range " +
                    s"(${p.min}..${p.max}) — it would divide by zero"
                )
              case f: Transform.Simple.Fill if f.max == f.min =>
                Some(
                  s"$nodeId: slot '$name' has a degenerate fill range " +
                    s"(${f.min}..${f.max}) — it would divide by zero"
                )
              // Only a hand-written scale gets here (the Pkl helper answers
              // null for a unit it cannot scale).
              case d: Transform.Simple.Duration if d.scale <= 0 =>
                Some(
                  s"$nodeId: slot '$name' has a non-positive duration scale " +
                    s"(${d.scale}) — every reading would render '0s'"
                )
              // CEL needs one type across the arms, so a mixed Match has no
              // CEL equivalent (ADR 0028).
              // A read as `otherwise` is text.
              case m: Transform.Simple.Match
                  if (m.otherwise :: m.cases.values.toList)
                    .map(_.isInstanceOf[Boolean])
                    .distinct
                    .sizeIs > 1 =>
                Some(
                  s"$nodeId: slot '$name' has a match with both string and " +
                    "boolean arms — pick one; a boolean arm is for a boolean " +
                    "attribute (disabled, hidden), a string for everything else"
                )
              case t: String =>
                Transform
                  .parse(Transform.CelKey(t, ExprValue.env(src.values)))
                  .left
                  .toOption
                  .map { err =>
                    val at =
                      locateTransform(t).fold("")(loc => s" (at $loc)")
                    s"$nodeId: slot '$name' has an invalid transform$at: $err"
                  }
              case _: Transform.Simple => None
              // Only a hand-written wire gets here.
              case st: Transform.Stage =>
                Some(
                  s"$nodeId: slot '$name' has a '${Transform.Stage.key(st)}' " +
                    "transform but reads no fetch — a stage turns a " +
                    "provider's answer into the hole's content, and a state " +
                    "slot has no answer to turn"
                )
            }
        transformError.toList ++ signalErrors(nodeId, cardName, key, src) ++
          readErrors(nodeId, cardName, name, src) ++
          fetchErrors(nodeId, cardName, name, src, scope) ++
          src.values.values.toList.collect { case ExprValue.Fetched(f) =>
            askErrors(nodeId, name, f, scope)
          }.flatten
      }

    /** A fetch's references are declared above it, its declared values pick an
      * option, and every option it could pick is a window HA answers; a
      * viewer's value is checked at the write.
      */
    def askErrors(
        nodeId: String,
        name: String,
        template: FetchTemplate,
        scope: Map[String, String]
    ): List[String] =
      val refErrors = template.references.distinct.sorted
        .filterNot(scope.contains)
        .map(v =>
          s"$nodeId: slot '$name' reads the variable '$v', which no " +
            "ancestor declares — declare it on the node that owns the " +
            "choice, or write the value down"
        )
      if (refErrors.nonEmpty) refErrors
      else
        (template
          .resolve(scope)
          .left
          .toOption
          .map(e =>
            s"$nodeId: slot '$name' reads the variable " +
              s"'${template.references.mkString}' at its declared value: $e"
          ) ++ template.window.all
          .flatMap(_.problem)
          .distinct
          .map(p => s"$nodeId: slot '$name' $p")).toList

    /** Otherwise a bad fetch renders blank forever with nothing saying why. */
    def fetchErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: BoundSlot,
        scope: Map[String, String]
    ): List[String] =
      src.fetch.toList.flatMap { template =>
        val untruthfulReads = Option.when(src.reads != Reads.OnRender)(
          s"$nodeId: slot '$name' reads a fetch, so its 'reads' must be " +
            s"'${Reads.OnRender}' (it says '${src.reads}') — a provider's " +
            "answer is never pushed, so nothing about it is a reason to render"
        )
        val parseErrors = askErrors(nodeId, name, template, scope)
        val stageError = src.transform match {
          case _: Transform.Stage => None
          case _                  =>
            Some(
              s"$nodeId: slot '$name' reads a fetch, so its transform must " +
                "be a STAGE (a chart, or passthrough) — a CEL expression and " +
                "a simple transform both read an entity's state, and a fetch " +
                "slot has none"
            )
        }
        val rawHole =
          cardReads.get(cardName).flatMap(_.toOption).flatMap { reads =>
            val hasRaw = reads.raw(name)
            src.stage match {
              case Transform.Stage.Passthrough if hasRaw =>
                Some(
                  s"$nodeId: card '$cardName' places slot '$name' in a RAW " +
                    "hole, but its transform is passthrough, whose value is " +
                    s"DATA — write {{$name}}, or the page emits unescaped JSON"
                )
              case Transform.Stage.Chart(_) if !hasRaw =>
                Some(
                  s"$nodeId: card '$cardName' places slot '$name' in an " +
                    "ESCAPED hole, but its transform draws markup — write " +
                    s"{{{$name}}}, or the page shows the markup as text"
                )
              case _ => None
            }
          }
        untruthfulReads.toList ++ parseErrors ++ stageError.toList ++
          rawHole.toList
      }

    /** At the declaration, so an unreferenced variable is still wrong loudly.
      */
    // Bound to every slot that reads one, so "read by no slot" is the
    // binding's absence.
    def valueErrors(nodeId: String, c: LayoutNode.Component): List[String] =
      val read = c.bound.values.flatMap(_.values.keySet).toSet
      c.values.keys.toList.sorted.flatMap { name =>
        if (!Dashboard.ExpressionValueName.matches(name))
          List(
            s"$nodeId: expression value '$name' is not a name an expression " +
              "can read — letters, digits and '_', not starting with a digit"
          )
        else if (Cel.fixedNames.contains(name))
          List(
            s"$nodeId: expression value '$name' would hide the '$name' every " +
              "expression already reads — rename it"
          )
        else if (!read.contains(name))
          List(
            s"$nodeId: expression value '$name' is read by no expression on " +
              "this node — names are the node's own and are not inherited"
          )
        else
          c.values(name) match
            // A value is shared by content across nodes, so it has no subject.
            case ExprValue.Holds(p) if Predicate.hasFreeSubject(p) =>
              List(
                s"$nodeId: expression value '$name' compares an entity it " +
                  "does not name — name it with q.entity(e)"
              )
            case _ => Nil
      } ++ c.slots.toList.sortBy(_._1).flatMap { (slot, src) =>
        val read = c.bound.get(slot).fold(Set.empty[String])(_.values.keySet)
        src.values.keys.toList.sorted.flatMap { name =>
          if (
            !Dashboard.ExpressionValueName.matches(name) ||
            Cel.fixedNames.contains(name)
          )
            List(
              s"$nodeId: slot '$slot' has an expression value '$name' its " +
                "expression cannot read — letters, digits and '_', and not " +
                s"one of ${Cel.fixedNames.toList.sorted.mkString(", ")}"
            )
          else if (c.values.contains(name))
            List(
              s"$nodeId: slot '$slot' has an expression value '$name', and " +
                "so does its node — rename one"
            )
          else if (!read.contains(name))
            List(
              s"$nodeId: slot '$slot' has an expression value '$name' its " +
                "expression does not read"
            )
          else Nil
        }
      } ++ labelled(c.bound).map((_, n, s) => (n, s)).collect {
        // The once-cache keys by what a value IS, not by what it counts now.
        case (slot, src)
            if src.reads == Reads.Once && src.values.values.exists(_.isLive) =>
          s"$nodeId: slot '$slot' reads a live count, condition or fetch but is " +
            s"read '${Reads.Once}' — it would show the first count forever; " +
            s"read it '${Reads.Live}'"
        case (slot, src)
            if src.reads == Reads.Once && src.valueEntities.nonEmpty =>
          s"$nodeId: slot '$slot' reads another entity but is read " +
            s"'${Reads.Once}' — it would show its first state forever; read " +
            s"it '${Reads.Live}'"
        case (slot, src)
            if src.entityId.isEmpty &&
              !c.slots.contains(Dashboard.SubjectSlot) &&
              (src.transform match
                case p: Predicate => Predicate.hasFreeSubject(p)
                case _ => false) =>
          s"$nodeId: slot '$slot' compares an entity it does not name, and " +
            "has no entity of its own — name it with q.entity(e)"
      }

    def valueWhileErrors(
        nodeId: String,
        slots: Map[SlotKey, BoundSlot],
        hasSubject: Boolean
    ): List[String] =
      labelled(slots).flatMap { (_, name, src) =>
        src.valueWhile.toList.flatMap { w =>
          Option
            .when(w.fetch.isDefined || w.fetches.nonEmpty)(
              s"$nodeId: slot '$name' has a valueWhile that reads a fetch — " +
                "it is decided before any fetch is read"
            )
            .toList ++
            Option
              .when(src.reads == Reads.Once)(
                s"$nodeId: slot '$name' is read '${Reads.Once}' and has a " +
                  "valueWhile — it would keep its first answer forever"
              )
              .toList ++
            Option
              .when(
                src
                  .gate(src.entityId)
                  .exists(_.entity.isEmpty) && !hasSubject &&
                  (w.transform match
                    case p: Predicate => Predicate.hasFreeSubject(p)
                    case _            => false)
              )(
                s"$nodeId: slot '$name' has a valueWhile comparing an entity " +
                  "it does not name, and has no entity of its own — name it " +
                  "with q.entity(e)"
              )
              .toList
        }
      }

    def typeErrors(
        nodeId: String,
        slots: Map[SlotKey, BoundSlot]
    ): List[String] =
      labelled(slots).collect {
        case (_, name, src)
            if src.isBool && src.literal.isEmpty && src.fetch.isEmpty &&
              !Dashboard.yieldsBool(src) =>
          s"$nodeId: slot '$name' is a boolean slot, but its reading does not " +
            "produce a bool — compare it (`on > 0`), or use a condition"
      } ++ labelled(slots).flatMap { case (_, name, src) =>
        Option
          .when(!src.isBool && src.literal.isEmpty && src.fetch.isEmpty)(
            src.celKey.flatMap(Transform.nonTextType)
          )
          .flatten
          .map(t =>
            s"$nodeId: slot '$name' yields a $t, which a card cannot show — " +
              "make it text, `str(…)` or `string(…)`"
          )
      }

    def varErrors(
        nodeId: String,
        vars: List[Dashboard.Declaration]
    ): List[String] =
      vars
        .groupBy(_.name)
        .collect {
          case (name, ds) if ds.sizeIs > 1 =>
            s"$nodeId: declares the variable '$name' more than once"
        }
        .toList
        .sorted ++ vars.sortBy(_.name).flatMap { d =>
        Option
          .when(!d.name.matches("[A-Za-z][A-Za-z0-9_]*"))(
            s"$nodeId: variable '${d.name}' is not a plain name " +
              "([A-Za-z][A-Za-z0-9_]*) — it is spelled into signal names and " +
              "the URL mirror"
          )
          .toList ++ d.initialTransform.flatMap(t =>
          Transform
            .parse(t.celKey)
            .left
            .toOption
            .map(e =>
              s"$nodeId: the initial transform for '${d.name}' does not " +
                s"compile: $e"
            )
        )
      }

    /** `<slot>.jsValue` has no answer for a live non-signal slot (ADR 0017);
      * without this the var renders empty and the handler is silently
      * malformed.
      */
    def readErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: BoundSlot
    ): List[String] =
      if (
        src.literal.isDefined || src.signal.isDefined ||
        src.reads == Reads.Once
      ) Nil
      else
        viewsOf(cardName)
          .filter(_((name, Templates.View.JsValue)))
          .map(_ =>
            s"$nodeId: card '$cardName' reads slot '$name' as {{{$name.jsValue}}}, " +
              "but the slot is live and not a signal — its value moves in the " +
              "element's bytes, so make it a signal slot or a literal"
          )

    /** `x.signal` prints nothing on a node where `x` is not a signal, so a
      * write like `$ = …` would ship. A section the node settles (a `has`, an
      * absent or non-empty literal) decides whether the placement renders; one
      * only state decides could open.
      */
    def signalPlacementErrors(
        nodeId: String,
        cardName: String,
        bound: Map[SlotKey, BoundSlot]
    ): List[String] =
      def opens(sec: Templates.Section): Boolean =
        val truthy = Templates.View.parse(sec.tag) match
          case Some(Right((y, Templates.View.Has))) => Some(bound.contains(y))
          case Some(_)                              => None
          case None                                 =>
            bound.get(sec.tag) match
              case None if Dashboard.injectedStatic(sec.tag) => None
              case None                                      => Some(false)
              case Some(src)                                 =>
                Option.when(src.literal.exists(_.nonEmpty))(true)
        truthy.forall(_ != sec.inverted)
      cardReads
        .get(cardName)
        .flatMap(_.toOption)
        .toList
        .flatMap(_.signalPlacements)
        .collect {
          case (x, sections)
              if !bound.get(x).exists(_.signal.isDefined) &&
                sections.forall(opens) =>
            val under =
              if sections.isEmpty then ""
              else
                " under " + sections.reverse
                  .map(s => s"{{${if s.inverted then "^" else "#"}${s.tag}}}")
                  .mkString(" ")
            s"$nodeId: card '$cardName' places {{$x.signal}}$under, which " +
              s"renders on this node, but slot '$x' is not a signal here — it " +
              "would print nothing, leaving a bare `$`"
        }
        .distinct
        .sorted

    def viewsOf(cardName: String): List[Set[(String, Templates.View)]] =
      cardReads.get(cardName).flatMap(_.toOption).map(_.views).toList

    // Each of these otherwise fails silently: the value stops updating.
    def signalErrors(
        nodeId: String,
        cardName: String,
        key: SlotKey,
        src: BoundSlot
    ): List[String] = key match
      case CellKey.Class(cls) => cellClassErrors(nodeId, cls, src)
      case CellKey.Style(p)   => cellStyleErrors(nodeId, p, src)
      case name: String => templateSignalErrors(nodeId, cardName, name, src)

    def templateSignalErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: BoundSlot
    ): List[String] =
      if (src.signal.isEmpty) Nil
      else if (src.literal.isDefined)
        List(
          s"$nodeId: slot '$name' is a constant literal and cannot be a " +
            "signal slot — a value that never moves has nothing to patch"
        )
      else if (name == Dashboard.SubjectSlot)
        // A signal moves only in the browser, so the server would resolve the
        // card against one subject while the DOM claimed another.
        List(
          s"$nodeId: slot '${Dashboard.SubjectSlot}' cannot be a signal " +
            "slot — it names " +
            "the entity the card's other slots read, which is a build-time " +
            "fact, not a value that moves"
        )
      else
        // The card must place the value's consumer, or the patch form
        // withholds it and nothing puts it back. A `Handler` has no binding, so
        // one of its two read forms must appear instead.
        val placed =
          if (src.signal.contains(SignalBind.Handler))
            List(Templates.View.JsValue, Templates.View.Signal)
          else List(Templates.View.Bind)
        viewsOf(cardName)
          .filterNot(views => placed.exists(v => views((name, v))))
          .map(_ =>
            s"$nodeId: card '$cardName' has slot '$name' marked as a signal " +
              s"slot, but no part of its template places " +
              placed.map(Dashboard.spell(name, _)).mkString(" or ") +
              " — the value would stop updating"
          )

    def childErrors(
        kids: Map[String, List[LayoutNode]],
        prefix: String,
        id: NodeId,
        scope: Map[String, String]
    ): List[String] =
      LayoutNode.steps(kids).flatMap { case (step, n) =>
        walk(n, prefix, LayoutNode.childId(prefix, id, step, n), scope)
      }

    // Interpolated into a `class` attribute unescaped.
    def classTokenErrors(nodeId: String, cls: String): List[String] =
      Option
        .when(!cls.matches("[A-Za-z0-9_-]+"))(
          s"$nodeId: cell class '$cls' is not a plain CSS class token " +
            "([A-Za-z0-9_-]+)"
        )
        .toList

    def cellErrors(nodeId: String, cell: Option[Cell]): List[String] =
      cell.toList.flatMap(_.classes.keys).flatMap(classTokenErrors(nodeId, _))

    // A set has no slots to bind a reading with; Pkl's builders refuse one too.
    def setCellErrors(setId: String, cell: Option[Cell]): List[String] =
      cellErrors(setId, cell) ++ cell.toList
        .flatMap(cl =>
          cl.classes.collect {
            case (cls, s) if s.literal.isEmpty => s"cell class '$cls'"
          } ++ cl.styles.keys.map(p => s"cell style '$p'")
        )
        .map(what =>
          s"$setId: a set's $what reads state, which only a card node can " +
            "bind — put it on the clause's node"
        )

    // The renderer places the binding itself, so only the shape can be wrong —
    // and each wrong shape is a class that silently never moves.
    def cellClassErrors(
        nodeId: String,
        cls: String,
        src: BoundSlot
    ): List[String] =
      Option
        .when(src.literal.isEmpty && src.reads != Reads.Live)(
          s"$nodeId: live cell class '$cls' must read live state — anything " +
            "else is never bound"
        )
        .toList

    def cellStyleErrors(
        nodeId: String,
        property: String,
        src: BoundSlot
    ): List[String] =
      Option
        .when(!Dashboard.CustomProperty.matches(property))(
          s"$nodeId: cellStyle '$property' is not a custom property " +
            "(`--name`) — a plain property would fight the theme's framework"
        )
        .toList ++
        Option
          .when(src.literal.isDefined || src.reads != Reads.Live)(
            s"$nodeId: cellStyle '$property' must read live state — " +
              "anything else is never bound"
          )
          .toList ++
        Option
          .when(Dashboard.onlyBool(src))(
            s"$nodeId: cellStyle '$property' reads a bool, which is no CSS " +
              "value — use classWhen for an on/off look"
          )
          .toList

    // In label order, for messages that read the same on every build.
    def labelled(
        slots: Map[SlotKey, BoundSlot]
    ): List[(SlotKey, String, BoundSlot)] =
      slots.toList.map((k, s) => (k, SlotKey.label(k), s)).sortBy(_._2)

    def noWrap(cardName: String): Boolean =
      cards.get(cardName).exists(!_.wrapAsCell)

    def walk(
        node: LayoutNode,
        prefix: String,
        nodeId: NodeId,
        scope: Map[String, String]
    ): List[String] =
      node match
        case c @ LayoutNode.Component(card, slots, _, cell, _, vars, _) =>
          val wrapErrors =
            if (!noWrap(card)) Nil
            else
              Option
                .when(c.liveEntities.nonEmpty)(
                  s"$nodeId: card '$card' has wrapAsCell=false but binds live " +
                    s"entities (${c.liveEntities.mkString(", ")}) — an " +
                    "unwrapped node has no morph target, so its live updates " +
                    "would never apply; make those slots literal or " +
                    s"reads='${Reads.OnRender}', or drop the opt-out"
                )
                .toList ++
                Option
                  .when(cell.isDefined)(
                    s"$nodeId: card '$card' has wrapAsCell=false but carries " +
                      "cell params — they ride on the .fh-cell wrapper this " +
                      "card opts out of"
                  )
                  .toList
          val here = scope ++ c.defaults
          // Each `valueWhile` is checked as the slot it is, under its own name.
          val checked = c.bound ++ c.bound.flatMap((k, s) =>
            s.valueWhile.map((SlotKey.label(k) + ".valueWhile": SlotKey) -> _)
          )
          templateErrors(
            nodeId,
            card,
            slots.keySet,
            c.regions.keySet
          ) ++ slotErrors(nodeId, card, checked, here) ++
            signalPlacementErrors(nodeId, card, c.bound) ++
            varErrors(nodeId, vars) ++
            valueErrors(nodeId, c) ++
            valueWhileErrors(
              nodeId,
              c.bound,
              c.slots.contains(Dashboard.SubjectSlot)
            ) ++
            typeErrors(nodeId, checked) ++
            cellErrors(nodeId, cell) ++
            wrapErrors ++ childErrors(c.regions, prefix, nodeId, here)
        // Clauses carry complete nodes, validated as ordinary ones.
        case s: LayoutNode.SetNode =>
          val setId = nodeId
          setCellErrors(setId, s.cell) ++
            s.candidates.filterNot(s.members.contains).map { c =>
              s"$setId: candidate '$c' has no member entry — it could never " +
                "render, so the build dropped it inconsistently"
            } ++
            s.members.toList.sortBy(_._1).flatMap { case (candidate, m) =>
              m.clauses.zipWithIndex.flatMap { case (clause, i) =>
                walk(
                  clause.node,
                  prefix,
                  LayoutNode.childId(
                    prefix,
                    nodeId,
                    LayoutNode.Step(LayoutNode.DefaultRegion, i),
                    clause.node
                  ),
                  scope
                ) ++ (clause.node match {
                  case c: LayoutNode.Component if noWrap(c.card) =>
                    List(
                      s"$setId/$candidate: card '${c.card}' has " +
                        "wrapAsCell=false and cannot be a set clause — every " +
                        "member is wrapped as its own patch target"
                    )
                  case _ => Nil
                })
              }
            }

    // Structure is never a patch target, so a live bytes slot on it never
    // reaches the DOM; a signal slot needs no patch target (ADR 0017). The Pkl
    // layer checks this too, but a pushed wire bypasses it.
    val structureLiveSlotErrors: List[String] = {
      def walk(node: LayoutNode): List[String] = node match {
        case c: LayoutNode.Component =>
          val asBytes = c.liveEntitiesAsBytes
          val here = cards
            .get(c.card)
            .filter(_.isStructure)
            .toList
            .filter(_ => asBytes.nonEmpty)
            .map(_ =>
              s"card '${c.card}' holds regions and so is never a patch " +
                s"target, but this node binds live entities " +
                s"(${asBytes.sorted.mkString(", ")}) as BYTES — they would " +
                "never reach the DOM. Put the live markup in a region as a " +
                "node, or make the slot a signal slot"
            )
          here ++ c.allChildren.flatMap(walk)
        case s: LayoutNode.SetNode =>
          s.members.toList
            .sortBy(_._1)
            .flatMap(_._2.clauses)
            .flatMap(cl => walk(cl.node))
      }
      (walk(card) ++ surfaces.toList
        .sortBy(_._1)
        .flatMap(s => walk(s._2.content))).distinct
    }

    // A region renders its children under fixed ids, so it is placed exactly
    // once and unconditionally: twice duplicates the ids, inside a section the
    // server patches ids the page may not have, and with no hole nothing shows.
    val regionHoleErrors: List[String] =
      cards.toList.sortBy(_._1).flatMap { case (name, cd) =>
        cardReads(name) match
          case Left(e) =>
            List(s"card '$name': its template does not compile: $e")
          case Right(reads) =>
            cd.regions.toList.sortBy(_._1).flatMap { case (r, region) =>
              val hole = cd.holeOf(r)
              if reads.misplacedRegions(r) then
                List(
                  s"card '$name': region '$r' is placed other than as $hole " +
                    "outside every section (escaped, as a section, or " +
                    s"inside one) — write $hole, where {{#$r}}{{{html}}}{{/$r}} " +
                    "was"
                )
              else
                reads.regionHoles.getOrElse(r, 0) match
                  case 0 =>
                    List(
                      s"card '$name': declares region '$r' (${region.fill}) " +
                        s"but its `template` places no $hole hole for it — " +
                        "nothing would ever appear there"
                    )
                  case 1 => Nil
                  case n =>
                    List(
                      s"card '$name': places region '$r' $n times — its " +
                        "children would render twice under the same ids"
                    )
            }
      }

    val viewErrors: List[String] =
      cards.keys.toList.sorted.flatMap { name =>
        cardReads(name).toOption.toList.flatMap { reads =>
          reads.unknownViews.toList.sorted.map(t =>
            s"card '$name': its template reads '$t', but a slot's views are " +
              Templates.View.values.map("." + _.spelling).mkString(", ")
          ) ++ reads.names.toList.sorted.flatMap(n =>
            Dashboard
              .oldSpelling(n)
              .map(fix => s"card '$name': its template reads '$n' — write $fix")
          ) ++ reads.boundInOwnSection.toList.sorted.map(s =>
            // ADR 0017: the patch form withholds a signal's value, a false
            // section, which would take its element and binding with it.
            s"card '$name': places {{{$s.bind}}} inside a section on '$s' " +
              s"itself, which the first patch closes — guard it with " +
              s"{{#$s.has}}"
          )
        }
      }

    // Keeps the id grammar's two shapes apart ([[LayoutNode.segment]]): an
    // all-digit name would read as an index.
    val regionNameErrors: List[String] =
      cards.toList.sortBy(_._1).flatMap { case (name, cd) =>
        cd.regions.keys.toList.sorted.collect {
          case r if r.isEmpty || !r.matches("[A-Za-z0-9_]+") =>
            s"card '$name': region name '$r' is not a plain token " +
              "([A-Za-z0-9_]+) — region names enter node ids"
          case r if r.forall(_.isDigit) =>
            s"card '$name': region name '$r' is all digits, which a node id " +
              "cannot tell from a child index — name it something a number " +
              "could not be"
        }
      }

    /** What an authored `id` has to satisfy: a plain token, used once, and not
      * inside a candidate set's clause.
      *
      * Deliberately NOT a rule: that an id must not read as another node's
      * descendant. Ancestry comes from [[fh.view.runtime.NodeAncestry]], which
      * asks the tree, so `detail` and `detail_0` may be unrelated.
      */
    val authoredIdErrors: List[String] = {
      def walkIds(
          node: LayoutNode,
          prefix: String,
          id: NodeId,
          inSet: Boolean
      ): List[(NodeId, Boolean, Option[String])] =
        (id, inSet, node.authoredId) :: (node match {
          case c: LayoutNode.Component =>
            LayoutNode.steps(c.regions).flatMap { case (step, ch) =>
              walkIds(
                ch,
                prefix,
                LayoutNode.childId(prefix, id, step, ch),
                inSet
              )
            }
          // A clause node renders once per member, so every member would
          // claim its id.
          case s: LayoutNode.SetNode =>
            s.members.toList.sortBy(_._1).flatMap { case (_, m) =>
              m.clauses.flatMap(cl => walkIds(cl.node, prefix, id, true))
            }
        })

      val all =
        walkIds(card, "", LayoutNode.rootId("", card), false) ++
          surfaces.toList.sortBy(_._1).flatMap { case (sid, s) =>
            val p = LayoutNode.surfacePrefix(sid)
            walkIds(s.content, p, LayoutNode.rootId(p, s.content), false)
          }
      val authored = all.collect { case (id, inSet, Some(name)) =>
        (id, inSet, name)
      }
      val ids = all.map(_._1)

      val shape = authored.flatMap { case (id, inSet, name) =>
        if (inSet)
          List(
            s"node id '$name' is inside a candidate set's clause, which is " +
              "rendered once per member — every member would claim the name"
          )
        else if (!name.matches("[A-Za-z0-9_]+"))
          List(
            s"node id '$name' is not a plain token ([A-Za-z0-9_]+) — ids are " +
              "interpolated into `id` attributes and signal names"
          )
        else if (ids.count(_ == id) > 1)
          List(s"node id '$name' is used more than once")
        else Nil
      }

      shape.distinct
    }

    val chromeErrors: List[String] =
      Option
        .when(
          theme.chrome.nonEmpty && !theme.chrome.contains("id=\"dashboard\"")
        )(
          "theme.chrome must contain an element with id=\"dashboard\" wrapping {{{body}}}"
        )
        .toList

    // The hoist mints `bakeInto`, so a dangling one means the build's and the
    // renderer's id derivations drifted; otherwise the symptom is a blank
    // panel, indistinguishable from an unmatched state group.
    val danglingBakes: List[String] = {
      // Every node under the id the runtime gives it, set members' included:
      // a member's nodes own bake groups too, a slider's heads (#556).
      def nodesIn(
          node: LayoutNode,
          prefix: String,
          id: NodeId,
          within: Option[(NodeId, Int, List[LayoutNode.Step])]
      ): List[(NodeId, LayoutNode, Boolean)] =
        (id, node, within.isDefined) :: (node match {
          case c: LayoutNode.Component =>
            LayoutNode.steps(c.regions).flatMap { case (step, ch) =>
              nodesIn(
                ch,
                prefix,
                LayoutNode.childIdWithin(prefix, id, step, ch, within),
                within.map((m, cl, path) => (m, cl, path :+ step))
              )
            }
          case set: LayoutNode.SetNode =>
            set.members.toList.flatMap { (entityId, m) =>
              val member =
                NodeId.derived(LayoutNode.memberSegment(id, entityId))
              m.clauses.zipWithIndex.flatMap((clause, ci) =>
                nodesIn(clause.node, prefix, member, Some((member, ci, Nil)))
              )
            }
        })
      val walked =
        nodesIn(card, "", LayoutNode.rootId("", card), None) ++
          surfaces.toList.flatMap { case (sid, s) =>
            val p = LayoutNode.surfacePrefix(sid)
            nodesIn(s.content, p, LayoutNode.rootId(p, s.content), None)
          }
      val nodes: Map[NodeId, LayoutNode] =
        walked.map((id, n, _) => id -> n).toMap
      val inMember: Set[NodeId] =
        walked.collect { case (id, _, true) => id }.toSet
      def hostCard(gid: NodeId): Option[CardDef] =
        nodes
          .get(gid)
          .collect { case c: LayoutNode.Component => c.card }
          .flatMap(cards.get)

      surfaces.toList.sortBy(_._1).flatMap { case (sid, s) =>
        s.bakeInto.toList.flatMap { gid =>
          if (!nodes.contains(gid))
            List(
              s"surface '$sid' bakes into '$gid', which is not a node in this " +
                "dashboard (main tree or any surface's content)"
            )
          // Every clause of a candidate mints the same ids below the member, so
          // only the clause tells two clauses' groups apart (`Surface.group`).
          else if (inMember(gid) && s.clause.isEmpty)
            List(
              s"surface '$sid' bakes into '$gid', inside a candidate set's " +
                "clause, but names no clause"
            )
          else
            // `bakeAs` must be a baked region of that node's card.
            (s.bakeAs, hostCard(gid)) match {
              case (Some(region), Some(cd))
                  if !cd.regions.get(region).exists(_.fill == Region.Baked) =>
                val baked = cd.bakedRegions.keys.toList.sorted
                List(
                  s"surface '$sid' bakes into '$gid' as '$region', but that " +
                    s"node's card declares no baked region '$region'" +
                    (if (baked.isEmpty) " (it declares none)"
                     else s" — it has ${baked.mkString(", ")}")
                )
              // Incoherent: the node owns a bake group while `hostId` falls to
              // the popup host. Also what keeps "a bake owner is structure"
              // true, which the render cache relies on.
              case (None, _) =>
                List(
                  s"surface '$sid' bakes into '$gid' but names no 'bakeAs' — " +
                    "a baked surface must name the region it fills"
                )
              case _ => Nil
            }
        }
      }
    }

    // Selection is per session or server truth per group, never both.
    val activationErrors: List[String] =
      surfaces.toList
        .flatMap { case (sid, s) => s.group.map((_, sid, s.activation)) }
        .groupBy(_._1)
        .toList
        .sortBy(_._1)
        .flatMap { case (gid, members) =>
          val kinds = members.map {
            case (_, _, _: Activation.User)  => "user"
            case (_, _, _: Activation.Var)   => "var"
            case (_, _, _: Activation.State) => "state"
          }.distinct
          val names = members.collect { case (_, _, Activation.Var(n, _)) =>
            n
          }
          // A baked member has a host, and what fills a host is a choice the
          // server knows: a viewer's variable or a condition (ADR 0033).
          val unchosen = members.collect { case (_, sid, _: Activation.User) =>
            sid
          }.sorted
          Option
            .when(unchosen.nonEmpty)(
              s"bake group '$gid' has members nothing selects (" +
                unchosen.mkString(", ") + "): a baked surface is chosen by a " +
                "node variable (activation kind \"var\") or a condition " +
                "(\"state\")"
            )
            .toList ++ Option
            .when(kinds.size > 1 && unchosen.isEmpty)(
              s"bake group '$gid' mixes var- and state-activated members: " +
                members.map(_._2).sorted.mkString(", ")
            )
            .toList ++ names.distinct.match {
            case List(name) =>
              // In [[SurfaceGraph.bakeGroup]]'s order.
              val inOrder = members
                .map(_._2)
                .sorted
                .flatMap(surfaces.get)
                .sortBy(_.bakeIndex.getOrElse(Int.MaxValue))
              varSelectionErrors(
                gid,
                inOrder.flatMap(_.bakeInto).headOption.getOrElse(gid),
                name,
                inOrder.flatMap(_.selectedAt),
                inOrder.count(_.isOtherwise)
              )
            case Nil  => Nil
            case many =>
              List(
                s"bake group '$gid' is selected by more than one variable: " +
                  many.sorted.mkString(", ")
              )
          }
        }

    val unboundConditions: List[String] =
      surfaces.toList.sortBy(_._1).flatMap { case (sid, s) =>
        s.activation match
          case Activation.State(c) if Predicate.hasFreeSubject(c) =>
            List(
              s"surface '$sid' is shown by a condition that compares an " +
                "unnamed entity; a state condition must name the entity each " +
                "comparison reads"
            )
          case _ => Nil
      }

    // Without its tally a press would have nothing to filter. `tapRoute` always
    // writes one, so this is a node built some other way.
    val callSetErrors: List[String] =
      declaredCalls.collect {
        case Dashboard.Declared(TapCall(_, CallSet(id), _), card, None) =>
          s"card '$card' places a call over the set '$id' without a " +
            s"readable '${Dashboard.TargetSetSlot}' slot — build its route " +
            "with tapMod.tapRoute"
      }.distinct ++ declaredCalls
        .collect {
          case Dashboard.Declared(
                TapCall(_, CallSet(id), _),
                _,
                Some(t: Predicate.Tally)
              ) =>
            id -> t
        }
        .groupMap(_._1)(_._2)
        .toList
        .sortBy(_._1)
        .collect {
          case (id, ts) if ts.distinct.size > 1 =>
            s"two different sets share the call id '$id'"
        }

    authoredIdErrors ++
      callSetErrors ++
      structureLiveSlotErrors ++
      regionHoleErrors ++
      viewErrors ++
      regionNameErrors ++
      chromeErrors ++
      theme.classes.errors ++
      danglingBakes ++
      activationErrors ++
      unboundConditions ++
      walk(card, "", LayoutNode.rootId("", card), Map.empty) ++
      surfaces.toList.sortBy(_._1).flatMap { case (sid, surface) =>
        val p = LayoutNode.surfacePrefix(sid)
        walk(
          surface.content,
          p,
          LayoutNode.rootId(p, surface.content),
          scopeOf(surface)
        ).map(err => s"surface '$sid': $err")
      }

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

  /** A variable-selected group needs its variable in scope at the host, and a
    * declared value that is a member index: the declared value is what bakes
    * before anyone chooses, and only a viewer's choice is checked at the write.
    */
  private def varSelectionErrors(
      gid: NodeId,
      host: NodeId,
      name: String,
      values: List[String],
      fallbacks: Int
  ): List[String] =
    Option
      .when(values.distinct.size != values.size)(
        s"bake group '$gid' has two members selected at one value of '$name': " +
          values.mkString(", ")
      )
      .toList ++ Option
      .when(fallbacks > 1)(
        s"bake group '$gid' has $fallbacks members selected at every other " +
          s"value of '$name'; one can be"
      )
      .toList ++ (varScopes.get(host).flatMap(_.get(name)) match {
      case None =>
        List(
          s"bake group '$gid' is selected by the variable '$name', which no " +
            "node at or above it declares"
        )
      case Some(in)
          if fallbacks == 0 && !values.contains(in.declaration.default) =>
        List(
          s"bake group '$gid' is selected by '$name', declared '${in.declaration.default}' " +
            s"on ${in.declarer}, which is not ${Surface.membersText(values)}"
        )
      case Some(_) => Nil
    })

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
  private def celKeys: List[Transform.CelKey] =
    ((slotSources(card) ++ surfaces.values.flatMap(s =>
      slotSources(s.content)
    )).toList
      .flatMap(_.celKeys) ++
      (initialKeys(card) ++ surfaces.values.flatMap(s =>
        initialKeys(s.content)
      ))).distinct

  private def initialKeys(n: LayoutNode): List[Transform.CelKey] = n match
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
    errors(locateTransform, compiled) match
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
  private def compileTransforms: Map[Transform.CelKey, Transform.Compiled] =
    celKeys.flatMap(k => Transform.parse(k).toOption.map(k -> _)).toMap

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
    def celKey: Transform.CelKey = Transform.CelKey(transform)

  /** The slot naming a card's subject, which every other slot inherits. Not
    * HA's `entity_id` field or the CEL `entity_id` binding, which share only
    * the spelling.
    */
  val SubjectSlot: String = "entity_id"

  /** A CEL identifier. A reserved word fails the expression's compile. */
  val ExpressionValueName: scala.util.matching.Regex =
    "[A-Za-z_][A-Za-z0-9_]*".r

  /** Per tier: CEL by its checked type, `Simple` exactly — a condition, or a
    * `match` whose every outcome is a Boolean; every other shape is text.
    */
  def yieldsBool(src: BoundSlot): Boolean = src.transform match
    case _: String =>
      src.celKey.forall(Transform.yieldsBool)
    case m: Transform.Simple.Match => allBoolean(m)
    case _: Predicate              => true
    case _: Transform.Simple       => false
    case _: Transform.Stage        => true

  /** Certainly a bool, so never a CSS value: CEL typed `bool` (not `dyn`), a
    * condition, or a `match` whose every outcome is a Boolean.
    */
  def onlyBool(src: BoundSlot): Boolean = src.transform match
    case _: String =>
      src.celKey.exists(Transform.isBoolTyped)
    case m: Transform.Simple.Match => allBoolean(m)
    case _: Predicate              => true
    case _                         => false

  private def allBoolean(m: Transform.Simple.Match): Boolean =
    m.otherwise.isInstanceOf[Boolean] &&
      m.cases.values.forall(_.isInstanceOf[Boolean])

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
  private final case class Declared(
      call: TapCall,
      card: String,
      reach: Option[CallReach]
  )

  /** Constructed only by [[Dashboard.validated]]: the type is the proof, so its
    * constructor, `apply` and `copy` are private to the model.
    */
  case class Validated private[model] (
      dashboard: Dashboard,
      transforms: Map[Transform.CelKey, Transform.Compiled],
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

  // The `__` spellings views replaced, named in the error that meets one.
  private val oldSuffixes = Map(
    "__bind" -> Templates.View.Bind,
    "__has" -> Templates.View.Has,
    "__read" -> Templates.View.JsValue,
    "__signal" -> Templates.View.Signal
  )

  /** The view an old `label__bind` meant, spelled as it is now. */
  def oldSpelling(name: String): Option[String] =
    oldSuffixes.collectFirst {
      case (s, view) if name.endsWith(s) && name.length > s.length =>
        spell(name.dropRight(s.length), view)
    }

  /** Slots the server reads itself, so no template need name them: the subject
    * and a tap's route ([[calls]]).
    */
  def readByRenderer(slot: String): Boolean =
    slot == SubjectSlot || routeSlots(slot)

  private val routeSlots: Set[String] =
    Set(ServiceSlot, TargetKindSlot, TargetIdSlot, DataKeySlot, TargetSetSlot)
