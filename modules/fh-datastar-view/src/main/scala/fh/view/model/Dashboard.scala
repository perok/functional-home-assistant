package fh.view.model

import fh.view.query.{Queries, QueryRequest}
import io.circe.{Decoder, Json}
import io.circe.derivation.{Configuration, ConfiguredDecoder}

given Configuration =
  Configuration.default.withDefaults
    .withDiscriminator("kind")
    .withTransformConstructorNames {
      // `SetNode`, because `LayoutNode.Set` would shadow `scala.Set` here.
      case "SetNode" => "set"
      case other     => other.toLowerCase
    }

/** Where one mustache slot gets its value.
  *
  *   - `transform`: a CEL string or a [[Transform.Simple]] object — the form is
  *     the tier (ADR 0028) — or, on a query slot, a [[Transform.Stage]]. Only
  *     the slot's own entity is reachable.
  *   - `default`: used when the transform yields `""`, which keeps a numeric
  *     initialiser like `{bri: {{x}}}` valid while a light is off.
  *   - `bypassUnavailable`: show an unavailable entity's raw state instead of
  *     running the transform. Off for an action, a label or a slider position.
  *   - `entityId`: `None` inherits the card's `entity_id`.
  *   - `literal`: a verbatim value, authored as a bare string; all else unused.
  *   - `signal`: carry the value as a Datastar signal, so a change is one
  *     signal frame rather than a re-rendered card (ADR 0017). The card puts
  *     `{{{<slot>__bind}}}` on the element beside its `{{<slot>}}` hole.
  *   - `query`: a query slot, which ignores `reads` and `signal`
  *     ([[SlotShape]]).
  */
case class SlotSource(
    entityId: Option[String] = None,
    transform: String | Transform.Simple | Transform.Stage = "state",
    default: Option[String] = None,
    bypassUnavailable: Boolean = true,
    literal: Option[String] = None,
    reads: String = Reads.Live,
    signal: Option[SignalBind] = None,
    query: Option[QueryTemplate] = None
) {

  def shape: SlotShape =
    query.fold(SlotShape.State(this))(q => SlotShape.Query(SlotAsk(q, stage)))

  /** Total so `validate` can call it; `validate` rejects a query slot whose
    * transform is not a stage, so the fallback never renders.
    */
  def stage: Transform.Stage = transform match {
    case st: Transform.Stage => st
    case _                   => Transform.Stage.Passthrough
  }

  /** What the renderer keys a value by (signal names, the once-cache). Lazy:
    * the `Simple` arm builds a string, 4.3% of a signals tick's allocation as a
    * `def` (`RenderBench.resumeSignals`).
    */
  lazy val valueKey: String = transform match {
    case s: String            => s
    case sm: Transform.Simple => Transform.Simple.key(sm)
    case st: Transform.Stage  => Transform.Stage.key(st)
  }
}

/** Untyped params, so the model knows no provider (`Queries.parse` types them).
  */
case class SlotQuery(provider: String, params: Map[String, String])
    derives CanEqual

/** A literal, or a node variable from this node or an ancestor (issue #209),
  * bounded at the write ([[fh.view.runtime.Renderer.refusals]]).
  */
enum Ref derives CanEqual:
  case Literal(value: String)
  case Var(name: String)

object Ref:

  /** A bare string is a literal, `{"var": …}` a reference. */
  given Decoder[Ref] =
    Decoder[String]
      .map[Ref](Ref.Literal.apply)
      .or(Decoder.instance(_.get[String]("var").map(Ref.Var.apply)))

/** A query as authored; [[SlotQuery]] is the resolved form. */
case class QueryTemplate(provider: String, params: Map[String, Ref])
    derives CanEqual,
      ConfiguredDecoder:

  def references: List[String] = params.values.toList.collect {
    case Ref.Var(n) => n
  }

  def resolve(env: Map[String, String]): SlotQuery =
    SlotQuery(
      provider,
      params.view.mapValues {
        case Ref.Literal(v) => v
        // Unresolved only if validation was bypassed; the name then fails the
        // provider's parse rather than passing as empty.
        case Ref.Var(n) => env.getOrElse(n, n)
      }.toMap
    )

/** Decided once from the wire form, which keeps one `SlotSource` so snapshots
  * do not churn. The match makes a `live`, `once` or signal query unreachable.
  */
enum SlotShape derives CanEqual:
  case State(source: SlotSource)
  case Query(ask: SlotAsk)

/** What a slot asks, unresolved; [[SlotRead]] is it resolved for one render. */
case class SlotAsk(query: QueryTemplate, stage: Transform.Stage)
    derives CanEqual:

  def resolve(env: Map[String, String]): SlotRead =
    SlotRead(query.resolve(env), stage)

/** Query and stage: two sizes of one chart share a fetch, not bytes. */
case class SlotRead(query: SlotQuery, stage: Transform.Stage) derives CanEqual

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
  private val objDecoder: Decoder[SlotSource] = ConfiguredDecoder.derived

  given Decoder[SlotSource] =
    Decoder[String].map(s => SlotSource(literal = Some(s))).or(objDecoder)

  /** Chosen by key, not by trying each arm, so a bad chart param reports the
    * stage's error rather than the last arm's.
    */
  given Decoder[String | Transform.Simple | Transform.Stage] =
    Decoder.instance { c =>
      if (c.value.isString) c.as[String]
      else if (c.downField("stage").succeeded) c.as[Transform.Stage]
      else c.as[Transform.Simple]
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
  *   - `Class`: `data-class:<name>`, present while truthy. `""` is the only
  *     falsy slot value, so `"false"` reads as on.
  *   - `Bind`: two-way `data-bind` on a form control; such a card needs a
  *     client signal in any form.
  *   - `Handler`: no attribute; the value is read by an event handler via
  *     `{{<slot>__signal}}`.
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

/** A card in the shared library.
  *
  *   - `slots`: the required template vars; [[Dashboard.validate]] flags only
  *     missing required ones. The injected `id`, `hostId` and `dashboardSlug`
  *     need no entry.
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
  */
case class CardDef(
    template: String,
    slots: List[String] = Nil,
    wrapAsCell: Boolean = true,
    regions: Map[String, Region] = Map.empty,
    css: String = ""
) derives ConfiguredDecoder {

  /** Eager and baked regions are spelled alike, which lets the document walk
    * thread both into one buffer (issue #237).
    */
  def holeOf(name: String): String =
    "{{#" + name + "}}{{{html}}}{{/" + name + "}}"

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

/** `fh-` layout classes on the node's `.fh-cell` wrapper. An object so it can
  * grow `grid_options`-style fields without a wire break.
  */
case class Cell(classes: List[String] = Nil) derives ConfiguredDecoder

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
  ) extends Predicate

  def referencedEntities(p: Predicate): List[String] = p match
    case Cmp(_, _, _, e)               => e.toList
    case And(items)                    => items.flatMap(referencedEntities)
    case Or(items)                     => items.flatMap(referencedEntities)
    case Not(item)                     => referencedEntities(item)
    case Count(candidates, when, _, _) =>
      candidates ++ when.values.flatMap(referencedEntities)

  /** Rejected under an [[Activation.State]], which supplies no subject. A
    * count's guards are bound to their own candidates, so they do not count.
    */
  def hasFreeSubject(p: Predicate): Boolean = p match
    case Cmp(_, _, _, entity) => entity.isEmpty
    case And(items)           => items.exists(hasFreeSubject)
    case Or(items)            => items.exists(hasFreeSubject)
    case Not(item)            => hasFreeSubject(item)
    case _: Count             => false

/** How a [[Surface]] becomes visible.
  *
  *   - `User`: a tap or tab click, optionally open from first paint. The
  *     selection is per connection (ADR 0005).
  *   - `State`: while a subject-free `condition` holds — server truth, the same
  *     for every viewer, so never in a session's open set. First match in
  *     `bakeIndex` order wins; an "else" is `And(Nil)`.
  *
  * A bake group must be one mode (`validate`), so any member decides it.
  */
enum Activation derives ConfiguredDecoder:
  case User(defaultOpen: Boolean = false)
  case State(condition: Predicate)

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
      slots: Map[String, SlotSource] = Map.empty,
      regions: Map[String, List[LayoutNode]] = Map.empty,
      cell: Option[Cell] = None,
      // Replaces the position-derived id and roots its descendants' — see
      // `authoredIdErrors` for why it is checked.
      id: Option[String] = None,
      // Node variables (issue #209), resolved by name up the ancestors,
      // nearest wins. No allowed-values list: the write refuses what a reader
      // cannot parse.
      vars: Map[String, String] = Map.empty
  ) extends LayoutNode:

    /** Name order, for a reproducible walk; ids do not come from this. */
    def allChildren: List[LayoutNode] =
      regions.toList.sortBy(_._1).flatMap(_._2)

    /** Only a literal `entity_id`: a transform one resolves at render time. */
    lazy val subjectEntity: Option[String] =
      slots.get(Dashboard.SubjectSlot).flatMap(_.literal)

    /** The reverse index. Empty means static HTML, never patched. */
    lazy val liveEntities: List[String] =
      stateSlots
        .filter(s => s.reads == Reads.Live && s.literal.isEmpty)
        .flatMap(s => s.entityId.orElse(subjectEntity))
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
        .flatMap(s => s.entityId.orElse(subjectEntity))
        .distinct

    /** In the render key but not [[liveEntities]]: otherwise a sensor ticking
      * every second would refetch its history every second.
      */
    lazy val queries: List[SlotAsk] = shapes._2

    private def stateSlots: List[SlotSource] = shapes._1

    private lazy val shapes: (List[SlotSource], List[SlotAsk]) =
      slots.values.toList.foldRight(
        (List.empty[SlotSource], List.empty[SlotAsk])
      ) { case (s, (states, queries)) =>
        s.shape match
          case SlotShape.State(src) => (src :: states, queries)
          case SlotShape.Query(q)   => (states, q :: queries)
      } match
        case (states, queries) => (states, queries.distinct)

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
  */
case class Theme(
    tokens: Map[String, String] = Map.empty,
    tokensDark: Map[String, String] = Map.empty,
    stylesheets: List[String] = Nil,
    deferredStylesheets: List[String] = Nil,
    scripts: List[String] = Nil,
    inlineScripts: List[String] = Nil,
    styles: String = "",
    chrome: String = ""
) derives ConfiguredDecoder

/** A popup, tab panel or `If` branch, rendered only while shown. Chrome-less:
  * the frame around its host lives in the theme or card.
  *
  *   - `bakeInto`/`bakeAs`: the node whose `bakeAs` region receives the
  *     selected member at first paint, so the shown panel needs no round trip.
  *   - `bakeIndex`: the order a user selection indexes and a state selection
  *     walks first-match.
  *   - `activation`: absent means a plain popup.
  */
case class Surface(
    content: LayoutNode,
    bakeInto: Option[NodeId] = None,
    bakeAs: Option[String] = None,
    bakeIndex: Option[Int] = None,
    activation: Activation = Activation.User(false)
) derives ConfiguredDecoder:

  /** The live-patch target and eviction group: `<bakeInto>_<bakeAs>`, or the
    * popup host.
    */
  def hostId: DomId = (bakeInto, bakeAs) match
    case (Some(into), Some(as)) => DomId.derived(s"${into}_${as}")
    case _                      => Dashboard.PopupHostId

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
) derives ConfiguredDecoder:

  /** Every registered card, not only those used: pruning would have to account
    * for surfaces and set clauses (ADR 0020's open work).
    */
  lazy val cardCss: String =
    cards.toList.sortBy(_._1).map(_._2.css).filter(_.nonEmpty).mkString("\n")

  /** Every entity this dashboard can ever address — the action bound (issue
    * #89, ADR 0023). Static and sound: a set's candidate list is fixed at build
    * time (ADR 0003).
    */
  lazy val referencedEntities: Set[String] = {
    def fromSlots(
        slots: Map[String, SlotSource],
        subject: Option[String]
    ): List[String] =
      slots.values.toList.flatMap(_.entityId) ++ subject.toList

    def walk(n: LayoutNode): List[String] = n match {
      case c: LayoutNode.Component =>
        fromSlots(c.slots, c.subjectEntity) ++ c.allChildren.flatMap(walk)
      case set: LayoutNode.SetNode =>
        set.candidates ++ set.members.values.toList
          .flatMap(_.clauses)
          .flatMap(cl => walk(cl.node))
    }

    (walk(card) ++ surfaces.values.toList.flatMap(s => walk(s.content))).toSet
  }

  /** What the live subscription asks HA for: [[referencedEntities]] plus what
    * only decides — clause guards and state conditions, whose entity may be
    * rendered nowhere. With the narrower set a dashboard paints right and then
    * never reacts.
    */
  lazy val watchedEntities: Set[String] = {
    def deciders(n: LayoutNode): List[String] = n match {
      case c: LayoutNode.Component => c.allChildren.flatMap(deciders)
      case set: LayoutNode.SetNode =>
        set.members.values.toList
          .flatMap(_.clauses)
          .flatMap(cl =>
            cl.when.toList.flatMap(Predicate.referencedEntities) ++
              deciders(cl.node)
          )
    }

    referencedEntities ++ deciders(card) ++ surfaces.values.toList.flatMap(s =>
      deciders(s.content) ++ (s.activation match {
        case Activation.State(c) => Predicate.referencedEntities(c)
        case _: Activation.User  => Nil
      })
    )
  }

  /** Human-readable errors, empty when valid. `locateTransform` maps a
    * transform to a source location for the message.
    */
  def validate(
      locateTransform: String => Option[String] = _ => None
  ): List[String] =
    // A required var is satisfied by a slot or [[Dashboard.injectedStatic]].
    def checkRef(
        nodeId: String,
        cardName: String,
        injected: Set[String],
        slotNames: Set[String]
    ): List[String] =
      cards.get(cardName) match
        case None =>
          List(s"$nodeId: references unknown card '$cardName'")
        case Some(cd) =>
          val missingSlots = cd.slots.toSet -- slotNames -- injected
          Option
            .when(missingSlots.nonEmpty)(
              s"$nodeId: card '$cardName' missing slots: " +
                missingSlots.toList.sorted.mkString(", ")
            )
            .toList

    def slotErrors(
        nodeId: String,
        cardName: String,
        slots: Map[String, SlotSource],
        scope: Map[String, String],
        inSet: Boolean
    ): List[String] =
      slots.toList.sortBy(_._1).flatMap { case (name, src) =>
        val transformError =
          // A query slot's transform is `queryErrors`' to check.
          if (src.literal.isDefined || src.query.isDefined) None
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
              case m: Transform.Simple.Match
                  if (m.cases.values.toList :+ m.otherwise)
                    .map(_.isInstanceOf[Boolean])
                    .distinct
                    .sizeIs > 1 =>
                Some(
                  s"$nodeId: slot '$name' has a match with both string and " +
                    "boolean arms — pick one; a boolean arm is for a boolean " +
                    "attribute (disabled, hidden), a string for everything else"
                )
              case t: String =>
                Transform.parse(t).left.toOption.map { err =>
                  val at =
                    locateTransform(t).fold("")(loc => s" (at $loc)")
                  s"$nodeId: slot '$name' has an invalid transform$at: $err"
                }
              case _: Transform.Simple => None
              // Only a hand-written wire gets here.
              case st: Transform.Stage =>
                Some(
                  s"$nodeId: slot '$name' has a '${Transform.Stage.key(st)}' " +
                    "transform but reads no query — a stage turns a " +
                    "provider's answer into the hole's content, and a state " +
                    "slot has no answer to turn"
                )
            }
        transformError.toList ++ signalErrors(nodeId, cardName, name, src) ++
          readErrors(nodeId, cardName, name, src) ++
          queryErrors(nodeId, cardName, name, src, scope, inSet)
      }

    /** Otherwise a bad query renders blank forever with nothing saying why. */
    def queryErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: SlotSource,
        scope: Map[String, String],
        inSet: Boolean
    ): List[String] =
      src.query.toList.flatMap { template =>
        val untruthfulReads = Option.when(src.reads != Reads.OnRender)(
          s"$nodeId: slot '$name' reads a query, so its 'reads' must be " +
            s"'${Reads.OnRender}' (it says '${src.reads}') — a provider's " +
            "answer is never pushed, so nothing about it is a reason to render"
        )
        // A member's id is minted at run time, so it has no scope yet.
        val inSetErrors =
          Option.when(inSet && template.references.nonEmpty)(
            s"$nodeId: slot '$name' reads a variable from inside a candidate " +
              "set, which is not supported yet — a member's scope is not " +
              "resolved. Write the value down, or move the query out of the set"
          )
        val refErrors = template.references.distinct.sorted
          .filterNot(scope.contains)
          .map(v =>
            s"$nodeId: slot '$name' reads the variable '$v', which no " +
              "ancestor declares — declare it on the node that owns the " +
              "choice, or write the value down"
          )
        // At declared values; a viewer's value is checked at the write.
        val parseError =
          if (refErrors.nonEmpty) Nil
          else
            Queries
              .parse(template.resolve(scope))
              .left
              .toOption
              .map(e => s"$nodeId: slot '$name' $e")
              .toList
        val stageError = src.transform match {
          case _: Transform.Stage => None
          case _                  =>
            Some(
              s"$nodeId: slot '$name' reads a query, so its transform must " +
                "be a STAGE (a chart, or passthrough) — a CEL expression and " +
                "a simple transform both read an entity's state, and a query " +
                "slot has none"
            )
        }
        val rawHole =
          cards.get(cardName).flatMap { cd =>
            val hasRaw =
              Dashboard.rawHole(name).findFirstIn(cd.template).isDefined
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
        untruthfulReads.toList ++ inSetErrors.toList ++ refErrors ++
          parseError ++ stageError.toList ++ rawHole.toList
      }

    /** At the declaration, so an unreferenced variable is still wrong loudly.
      */
    def varErrors(nodeId: String, vars: Map[String, String]): List[String] =
      vars.keys.toList.sorted.flatMap { name =>
        Option
          .when(!name.matches("[A-Za-z][A-Za-z0-9_]*"))(
            s"$nodeId: variable '$name' is not a plain name " +
              "([A-Za-z][A-Za-z0-9_]*) — it is spelled into signal names and " +
              "the URL mirror"
          )
          .toList
      }

    /** `<slot>__read` has no answer for a live non-signal slot (ADR 0017);
      * without this the var renders empty and the handler is silently
      * malformed.
      */
    def readErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: SlotSource
    ): List[String] =
      if (
        src.literal.isDefined || src.signal.isDefined ||
        src.reads == Reads.Once
      ) Nil
      else
        cards
          .get(cardName)
          .toList
          .filter(_.template.contains(s"{{{${name}__read}}}"))
          .map(_ =>
            s"$nodeId: card '$cardName' reads slot '$name' as {{{${name}__read}}}, " +
              "but the slot is live and not a signal — its value moves in the " +
              "element's bytes, so make it a signal slot or a literal"
          )

    // Each of these otherwise fails silently: the value stops updating.
    def signalErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: SlotSource
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
            List(s"{{{${name}__read}}}", s"{{${name}__signal}}")
          else List(s"{{{${name}__bind}}}")
        cards
          .get(cardName)
          .toList
          .filterNot(cd => placed.exists(cd.template.contains))
          .map(_ =>
            s"$nodeId: card '$cardName' has slot '$name' marked as a signal " +
              s"slot, but no part of its template places " +
              placed.mkString(" or ") + " — the value would stop updating"
          )

    def childErrors(
        kids: Map[String, List[LayoutNode]],
        id: NodeId,
        scope: Map[String, String],
        inSet: Boolean
    ): List[String] =
      LayoutNode.steps(kids).flatMap { case (step, n) =>
        walk(n, LayoutNode.childId("", id, step, n), scope, inSet)
      }

    // Interpolated into a `class` attribute unescaped.
    def cellErrors(nodeId: String, cell: Option[Cell]): List[String] =
      cell.toList.flatMap(_.classes).collect {
        case cls if !cls.matches("[A-Za-z0-9_-]+") =>
          s"$nodeId: cell class '$cls' is not a plain CSS class token " +
            "([A-Za-z0-9_-]+)"
      }

    def noWrap(cardName: String): Boolean =
      cards.get(cardName).exists(!_.wrapAsCell)

    def walk(
        node: LayoutNode,
        nodeId: NodeId,
        scope: Map[String, String],
        inSet: Boolean
    ): List[String] =
      node match
        case c @ LayoutNode.Component(card, slots, _, cell, _, vars) =>
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
          val here = scope ++ vars
          checkRef(
            nodeId,
            card,
            Dashboard.injectedStatic,
            slots.keySet
          ) ++ slotErrors(nodeId, card, slots, here, inSet) ++
            varErrors(nodeId, vars) ++ cellErrors(nodeId, cell) ++
            wrapErrors ++ childErrors(c.regions, nodeId, here, inSet)
        // Clauses carry complete nodes, validated as ordinary ones.
        case s: LayoutNode.SetNode =>
          val setId = nodeId
          cellErrors(setId, s.cell) ++
            s.candidates.filterNot(s.members.contains).map { c =>
              s"$setId: candidate '$c' has no member entry — it could never " +
                "render, so the build dropped it inconsistently"
            } ++
            s.members.toList.sortBy(_._1).flatMap { case (candidate, m) =>
              m.clauses.zipWithIndex.flatMap { case (clause, i) =>
                walk(
                  clause.node,
                  LayoutNode.childId(
                    "",
                    nodeId,
                    LayoutNode.Step(LayoutNode.DefaultRegion, i),
                    clause.node
                  ),
                  scope,
                  inSet = true
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

    // A section splicing `{{{html}}}` must be a declared region, or the card
    // reads as a leaf while its bytes carry its children, and a patch re-sends
    // them.
    val undeclaredHoleErrors: List[String] = {
      val section =
        """\{\{#([A-Za-z0-9_]+)\}\}(?:(?!\{\{/\1\}\}).)*\{\{\{html\}\}\}""".r
      cards.toList.sortBy(_._1).flatMap { case (name, cd) =>
        section
          .findAllMatchIn(cd.template.replaceAll("\n", " "))
          .map(_.group(1))
          .toList
          .distinct
          .sorted
          .filterNot(cd.regions.contains)
          .map(r =>
            s"card '$name': its template splices children into '$r' but " +
              s"declares no region '$r' — the card would read as a leaf while " +
              "its bytes carry its children, so a patch would re-send them"
          )
      }
    }

    // The other side of the same defect; both are silent.
    val regionHoleErrors: List[String] =
      cards.toList.sortBy(_._1).flatMap { case (name, cd) =>
        cd.regions.toList.sortBy(_._1).collect {
          case (r, region) if !cd.template.contains(cd.holeOf(r)) =>
            s"card '$name': declares region '$r' (${region.fill}) but its " +
              s"`template` places no ${cd.holeOf(r)} hole for it — " +
              "nothing would ever appear there"
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
      def cardAt(
          node: LayoutNode,
          prefix: String,
          id: NodeId,
          target: NodeId
      ): Option[String] =
        node match {
          case c: LayoutNode.Component =>
            if (id == target) Some(c.card)
            else
              LayoutNode
                .steps(c.regions)
                .collectFirst(Function.unlift { case (step, ch) =>
                  cardAt(
                    ch,
                    prefix,
                    LayoutNode.childId(prefix, id, step, ch),
                    target
                  )
                })
          case _: LayoutNode.SetNode => None
        }

      def idsOf(
          node: LayoutNode,
          prefix: String,
          id: NodeId
      ): List[NodeId] =
        id :: (node match {
          case c: LayoutNode.Component =>
            LayoutNode.steps(c.regions).flatMap { case (step, ch) =>
              idsOf(ch, prefix, LayoutNode.childId(prefix, id, step, ch))
            }
          // A member owns no bake group.
          case _: LayoutNode.SetNode => Nil
        })
      val known: Set[NodeId] =
        (idsOf(card, "", LayoutNode.rootId("", card)) ++
          surfaces.toList.flatMap { case (sid, s) =>
            val p = LayoutNode.surfacePrefix(sid)
            idsOf(s.content, p, LayoutNode.rootId(p, s.content))
          }).toSet
      def hostCard(gid: NodeId): Option[CardDef] =
        cardAt(card, "", LayoutNode.rootId("", card), gid)
          .orElse(
            surfaces.collectFirst(Function.unlift { case (sid, s) =>
              val p = LayoutNode.surfacePrefix(sid)
              cardAt(s.content, p, LayoutNode.rootId(p, s.content), gid)
            })
          )
          .flatMap(cards.get)

      surfaces.toList.sortBy(_._1).flatMap { case (sid, s) =>
        s.bakeInto.toList.flatMap { gid =>
          if (!known(gid))
            List(
              s"surface '$sid' bakes into '$gid', which is not a node in this " +
                "dashboard (main tree or any surface's content)"
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
        .flatMap { case (sid, s) => s.bakeInto.map((_, sid, s.activation)) }
        .groupBy(_._1)
        .toList
        .sortBy(_._1)
        .flatMap { case (gid, members) =>
          val kinds = members.map {
            case (_, _, _: Activation.User)  => "user"
            case (_, _, _: Activation.State) => "state"
          }.distinct
          Option
            .when(kinds.size > 1)(
              s"bake group '$gid' mixes user- and state-activated members: " +
                members.map(_._2).sorted.mkString(", ")
            )
            .toList
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

    authoredIdErrors ++
      structureLiveSlotErrors ++
      undeclaredHoleErrors ++
      regionHoleErrors ++
      regionNameErrors ++
      chromeErrors ++
      danglingBakes ++
      activationErrors ++
      unboundConditions ++
      walk(card, LayoutNode.rootId("", card), Map.empty, inSet = false) ++
      // Each surface starts its own scope — see `scopedSlots`.
      surfaces.toList.sortBy(_._1).flatMap { case (sid, surface) =>
        walk(
          surface.content,
          LayoutNode.rootId("", surface.content),
          Map.empty,
          inSet = false
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

  def allQueries: List[SlotRead] =
    (queriesIn(card) ++ surfaces.values.toList.flatMap(s =>
      queriesIn(s.content)
    )).distinct

  /** Not in [[referencedEntities]]: showing a sensor's history is not leave to
    * act on it.
    */
  lazy val queriedEntities: Set[String] =
    allQueries
      .flatMap(r => Queries.parse(r.query).toOption)
      .flatMap(_.entities)
      .toSet

  /** At declared values. A clause that will not match still contributes: the
    * snapshot is resolved before the walk decides.
    */
  def queriesIn(n: LayoutNode): List[SlotRead] =
    scopedSlots(n, Map.empty).flatMap { case (s, scope) =>
      s.shape match
        case SlotShape.Query(ask) =>
          List(ask.resolve(scope))
        case SlotShape.State(_) => Nil
    }.distinct

  private def slotSources(n: LayoutNode): List[SlotSource] =
    scopedSlots(n, Map.empty).map(_._1)

  /** A surface is its own scope root: a baked one can go into any host. */
  private def scopedSlots(
      n: LayoutNode,
      scope: Map[String, String]
  ): List[(SlotSource, Map[String, String])] = n match
    case c: LayoutNode.Component =>
      val here = scope ++ c.vars
      c.slots.values.toList.map(_ -> here) ++
        c.allChildren.flatMap(scopedSlots(_, here))
    case s: LayoutNode.SetNode =>
      s.members.values.toList
        .flatMap(_.clauses)
        .flatMap(c => scopedSlots(c.node, scope))

  /** The CEL expressions to compile, for both [[validated]] and
    * `Transforms.from`.
    */
  def transformStrings: List[String] =
    (slotSources(card) ++ surfaces.values.flatMap(s =>
      slotSources(s.content)
    )).toList
      .filter(_.literal.isEmpty)
      .map(_.transform)
      .collect { case t: String => t }
      .distinct

  /** [[validate]] plus the compiled transforms and parsed queries, so the
    * renderer never recompiles or defends against a bad expression.
    */
  def validated(
      locateTransform: String => Option[String] = _ => None
  ): Either[List[String], Dashboard.Validated] =
    validate(locateTransform) match
      case Nil =>
        Right(Dashboard.Validated(this, compileTransforms, parseQueries))
      case errs => Left(errs)

  // Only after `validate` proved each parses, so a `Left` cannot occur.
  private def parseQueries: Map[SlotRead, QueryRequest] =
    allQueries.flatMap(r => Queries.parse(r.query).toOption.map(r -> _)).toMap

  // Only after `validate` proved each compiles, so a `Left` cannot occur.
  private def compileTransforms: Map[String, Transform.Compiled] =
    transformStrings.flatMap(t => Transform.parse(t).toOption.map(t -> _)).toMap

object Dashboard:

  /** The slot naming a card's subject, which every other slot inherits. Not
    * HA's `entity_id` field or the CEL `entity_id` binding, which share only
    * the spelling.
    */
  val SubjectSlot: String = "entity_id"

  private[model] def rawHole(name: String): scala.util.matching.Regex =
    ("\\{\\{\\{\\s*" + scala.util.matching.Regex.quote(
      name
    ) + "\\s*\\}\\}\\}").r

  /** Constructed only by [[Dashboard.validated]]: the type is the proof. */
  case class Validated(
      dashboard: Dashboard,
      transforms: Map[String, Transform.Compiled],
      queries: Map[SlotRead, QueryRequest] = Map.empty,
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

  /** The backend-injected vars a card may list in its `slots` without the node
    * authoring them.
    */
  val injectedStatic: Set[String] = Set("id")
