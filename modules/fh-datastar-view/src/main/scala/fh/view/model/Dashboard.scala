package fh.view.model

import io.circe.{Decoder, Json}
import io.circe.derivation.{Configuration, ConfiguredDecoder}

/** Where a single mustache slot gets its value at runtime.
  *
  * A slot's value is the [[Transform]] JSONata expression `transform`,
  * evaluated by the renderer against the producing entity. The entity's full
  * context is bound: `$state` (raw state String), `$attr` (its attribute
  * object, e.g. `$attr.brightness`), `$domain` (the entity-id prefix) and
  * `$entity_id` (the id). So selecting a value *is* the transform — `"$state"`
  * (the default) shows the state, `"$attr.brightness"` an attribute,
  * `"$lookup(…, $domain)"` an identity-derived value like a service action. No
  * other entity is reachable.
  *
  * `default` applies when the transform yields an empty string (e.g.
  * `$attr.brightness` when a light is off). `bypassUnavailable` (ON by default)
  * makes an `"unavailable"`/`"unknown"` entity show its raw state *instead of*
  * running the transform — what keeps a value-display readable when its
  * transform would otherwise error on a non-numeric state (`$number($state)`).
  * Set it to `false` on the slots that must run their transform regardless of
  * availability: identity-derived slots (an action resolves from `$domain`, not
  * state), labels (keep the friendly_name rather than showing `"unavailable"`),
  * and a slider's numeric position (fall back to its `default`, not the literal
  * `"unavailable"` string).
  *
  * `entityId` is the slot's OWN entity. When `None`, the slot INHERITS the
  * component's `entity_id` param (the card's one entity) — so a card binds its
  * entity once and every slot reads it, while a slot that names a different
  * `entityId` overrides the inheritance (the multi-entity card). With neither
  * (no slot `entityId`, no `entity_id` param) the transform runs against an
  * empty state — the constant case (e.g. a `"Hi"` JSONata literal).
  *
  * `reactive` (ON by default) is whether a state change of this slot's entity
  * should re-render the component: a reactive slot's entity joins
  * [[LayoutNode.Component.liveEntities]] (the reverse index + morph-wrapper
  * decision). Turn it OFF for a slot that reads its entity for IDENTITY only —
  * an onclick/action resolving `$entity_id`/`$domain`, whose value never
  * changes with state — so it does not register a needless live dependency. A
  * literal slot carries no entity and is excluded regardless.
  *
  * `literal` is the cheapest slot: a hardcoded value used verbatim — no entity,
  * no JSONata, no compilation. A label like `"Kitchen"` or a constant action
  * URL is this, not a `"Kitchen"` JSONata string-literal `transform`. It is
  * authored as a bare JSON string rather than an object; when set, every other
  * field is unused. Only a value that varies with live state needs the
  * object/`transform` form.
  *
  * `signal` (absent by default) carries this slot's value to the browser as a
  * Datastar SIGNAL — `_<nodeId>__<slotName>` — instead of as bytes inside the
  * node's element, so a change to it costs a `datastar-patch-signals` frame
  * rather than the whole re-rendered card (ADR 0017). Its value says WHERE the
  * value lands in the DOM ([[SignalBind]]) — the one thing the renderer cannot
  * infer, since a reading is text, a track fill is a style property and a range
  * input's position is a two-way binding.
  *
  * The renderer hands the card one extra template var, `<slot>__bind`, the
  * whole binding attribute; the card places it beside the ordinary `{{<slot>}}`
  * hole, on the element whose text it is (a `data-text` patch replaces that
  * element's whole content, so a wrapper would lose whatever else it holds):
  *
  * {{{<span class="fh-text-run" {{{value__bind}}}>{{value}}</span> }}}
  *
  * The value still renders inline on a wholesale render, which is what a
  * JS-less browser gets and all it ever gets. Incompatible with [[literal]] (a
  * constant never moves) and pointless on a non-reactive slot (an
  * identity-derived value never moves either) — [[Dashboard.validate]] rejects
  * the first, and the renderer simply ignores the second.
  */
given Configuration =
  Configuration.default.withDefaults
    .withDiscriminator("kind")
    .withTransformConstructorNames {
      // `set` on the wire. The Scala name carries the `Node` suffix only
      // because `LayoutNode.Set` would shadow `scala.Set` inside this file.
      case "SetNode" => "set"
      case other     => other.toLowerCase
    }

case class SlotSource(
    // This slot's OWN entity, or `None` to inherit the component's `entity_id`
    // param (the card's one entity). An explicit value overrides the inheritance
    // — the multi-entity card. With neither, the transform runs against an empty
    // state (the constant case).
    entityId: Option[String] = None,
    // The value expression — JSONata over $state/$attr/$domain/$entity_id, compiled
    // at build time (validated below) and reused by the renderer. Defaults to the
    // entity's raw state.
    transform: String = "$state",
    // Used when the transform yields "" (e.g. brightness when a light is off).
    // Keeps numeric signal initialisers like `{bri: {{x}}}` valid.
    default: Option[String] = None,
    // When the entity is unavailable/unknown, show its raw state (the literal
    // "unavailable"/"unknown") and skip the transform — keeps a value-display
    // readable. ON by default; opt OUT (false) on slots that must still run
    // their transform: identity slots (actions), labels, a slider's position.
    bypassUnavailable: Boolean = true,
    // A hardcoded value used verbatim: no entity, no JSONata. When set, the
    // fields above are unused. Authored (and decoded) as a bare JSON string
    // rather than an object — see the decoder below.
    literal: Option[String] = None,
    // Whether a state change of this slot's entity re-renders the component (so
    // its entity joins Component.liveEntities). ON by default; turn OFF for an
    // identity-only slot (an onclick/action reading $entity_id/$domain) that
    // binds an entity but never varies with its state. `reactive = false`
    // carries a second guarantee the renderer relies on: the value is a pure
    // function of the entity's identity, so it is resolved ONCE per
    // (entity, transform) and memoized (never re-evaluated per render) — keep
    // it off only for slots that truly read no live state.
    reactive: Boolean = true,
    // Carry this slot's value as a Datastar SIGNAL rather than as bytes in the
    // element, so a change to it costs a signals frame instead of a card
    // re-render (ADR 0017). The value says WHERE it lands — see [[SignalBind]]
    // — and the card's template must place `{{{<slot>__bind}}}`.
    signal: Option[SignalBind] = None
)

object SlotSource:
  // The object form (a live-expression slot) — the standard configured decoder.
  private val objDecoder: Decoder[SlotSource] = ConfiguredDecoder.derived

  /** A slot is either a bare JSON string (a constant [[literal]]) or an object
    * (a live-expression slot). Decoding accepts both forms.
    */
  given Decoder[SlotSource] =
    Decoder[String].map(s => SlotSource(literal = Some(s))).or(objDecoder)

/** WHERE a signal slot's value lands in the DOM — the Datastar attribute the
  * renderer emits for it (ADR 0017).
  *
  * A renderer-side enumeration rather than an attribute the card writes, and
  * that is the load-bearing choice: the renderer decides whether a binding
  * exists at all, which is what keeps the PLAIN form (no binding, no seed — the
  * bytes this renderer emitted before signal slots) reachable behind one
  * predicate for a future morph-only client. A card that wrote `data-text`
  * itself could not be un-written.
  *
  * Encoded on the wire as one string, so the authoring layer names a binding
  * rather than building a class: `"text"`, `"bind"`, `"style:--_end"`,
  * `"attr:title"`.
  *
  *   - [[Text]] — `data-text`, the element's whole text content. The common
  *     case: a reading, a label, a state.
  *   - [[Style]] — `data-style:<property>`, one CSS property (custom properties
  *     included). The VALUE carries its own unit, so the expression is a bare
  *     signal read and the authoring layer decides whether a fill is a
  *     percentage or a colour.
  *   - [[Attr]] — `data-attr:<name>`, one attribute. Note this sets the
  *     ATTRIBUTE, which for a form control is not the property the browser
  *     reads after load (`checked` is the classic trap) — reach for [[Bind]]
  *     there instead.
  *   - [[Class]] — `data-class:<name>`, one class present while the value is
  *     truthy. A boolean state, where the value is `""` for off and anything
  *     for on: an empty string is the only falsy thing a slot can produce, so
  *     `"false"` would read as ON.
  *   - [[Bind]] — `data-bind`, TWO-WAY on a form control: the server writes the
  *     signal and the user's input writes it back. What a range input's
  *     position and a checkbox's `checked` PROPERTY want, and the one kind
  *     whose card is therefore not plain-form-capable — an interactive control
  *     needs a client signal whatever this setting says.
  */
enum SignalBind derives CanEqual:
  case Text
  case Bind
  case Style(property: String)
  case Attr(name: String)
  case Class(name: String)

object SignalBind:

  /** `"style:--_end"` -> `Style("--_end")`. Unknown spellings decode to `None`
    * rather than a default: a typo that silently became `data-text` would put a
    * colour in an element's text content and look like a rendering bug.
    */
  def parse(s: String): Option[SignalBind] = s.split(":", 2).toList match
    case "text" :: Nil          => Some(Text)
    case "bind" :: Nil          => Some(Bind)
    case "style" :: prop :: Nil => Option.when(prop.nonEmpty)(Style(prop))
    case "attr" :: name :: Nil  => Option.when(name.nonEmpty)(Attr(name))
    case "class" :: name :: Nil => Option.when(name.nonEmpty)(Class(name))
    case _                      => None

  given Decoder[SignalBind] =
    Decoder[String].emap(s => parse(s).toRight(s"unknown signal binding: $s"))

/** A reusable card in the shared library (a node references one by name).
  *
  *   - `template`: a Mustache string. Escaped `{{slot}}` values are HTML-safe;
  *     raw author values (action URLs, ids) use `{{{...}}}`.
  *   - `slots`: every required template var, each filled from a [[SlotSource]]
  *     — a live entity transform OR a constant literal. This is the *one*
  *     vocabulary: a card's subject is the magical `entity_id` slot, a constant
  *     like a `label`/`min` is a literal slot, a live value is a transform
  *     slot. The only non-slot template var is the backend-*injected* `id`
  *     ([[Dashboard.injectedStatic]]), which the author never supplies and so
  *     needs no entry. Optional pieces (a tap `action`, a `secondary` line)
  *     need no entry either — [[Dashboard.validate]] only flags missing
  *     *required* slots and ignores extra ones.
  *   - `wrapAsCell`: whether the renderer wraps this card's HTML in the id'd
  *     `.fh-cell` layout/morph wrapper (see `Renderer.render`). ON by default —
  *     every node is a cell, so containers lay their children out uniformly and
  *     every node is an addressable Datastar morph target. Turn it OFF only for
  *     a card whose root element must remain a *direct* child of a
  *     framework-structural parent (e.g. the tab anchors under BeerCSS's
  *     `.tabs > a`); such a card is never wrapped, never a morph target of its
  *     own, and must not be used as a set clause (whose per-entity children are
  *     always wrapped — they ARE the patch targets). [[Dashboard.validate]]
  *     rejects the wrapper-dependent shapes on such a card: live-entity slots,
  *     `cell` params, and set-clause use.
  *   - `regions` / `self`: what a card that HOLDS other nodes declares — see
  *     below.
  *
  * '''Regions and the self.''' A card is a LEAF (no regions — its whole
  * `template` is what a patch renders) or STRUCTURE (regions — it holds content
  * it does not own). A [[Region]] is one named hole in `template`:
  *
  *   - `eager` — the node's own children, composed with the card in one render.
  *     A `Row`'s children, a slider's member rows.
  *   - `baked` — a hole a SURFACE fills per viewer, lazily and as its own
  *     operation: a tab panel, an `If` branch, a popup. Its element carries the
  *     `{{hostId}}` something addresses to fill it.
  *
  * `self` is the card's own presentation — a slider head, a frame — and is what
  * the patch path renders and diffs, under the DOM id `<nodeId>-self`.
  *
  * '''`self` and every region are disjoint parts of the template''', which is
  * the whole mechanism: a top-level patch matches only the element carrying its
  * own id, so a patch at `#c_2-self` cannot reach `#c_2_panel`. Hence the
  * design's first rule: '''a node's patch carries its own rendering and never
  * the contents of a region''', so a host changing cannot re-render what it
  * hosts (docs/adr/0012-each-session-renders-what-it-is-owed.md). Enforced by
  * [[Dashboard.validate]], and by a type refinement in the authoring layer.
  *
  * A leaf sets no regions and usually no `self`. A structural card with NO
  * `self` (`Grid`, `Row`, `Column`, `If`) has only its children to show, so its
  * whole HTML contains them — it must never be patched, which the authoring
  * layer enforces by rejecting a *live* slot on exactly that shape.
  *
  * '''`css`''' is the structure the card's own markup needs — its class names,
  * their box, flow and spacing — authored beside the template it belongs to
  * (ADR 0020). Every registered card's `css` is concatenated into the page's
  * `<style>` after [[Dashboard.css]] and before `theme.styles`, so a theme
  * overrides any of it by ordinary cascade order.
  */
case class CardDef(
    template: String,
    slots: List[String] = Nil,
    wrapAsCell: Boolean = true,
    regions: Map[String, Region] = Map.empty,
    css: String = ""
) derives ConfiguredDecoder {

  /** The hole this card's template places for `name` — a section for an eager
    * region (its children are spliced), a raw var for a baked one (a surface's
    * bytes are substituted whole).
    */
  def holeOf(name: String, r: Region): String =
    if (r.fill == Region.Baked) "{{{" + name + "}}}" else "{{#" + name + "}}"

  /** Whether this card holds content it does not own — the LEAF/STRUCTURE
    * split. Structure is never a patch target.
    */
  def isStructure: Boolean = regions.nonEmpty

  /** The regions a SURFACE can fill — what a `bakeAs` may name. */
  def bakedRegions: Map[String, Region] =
    regions.filter(_._2.fill == Region.Baked)
}

/** One named hole in a card's [[CardDef.template]]. `fill` says who puts
  * content there and when: the node's own children, composed in the same render
  * (`eager`), or a surface selected per viewer and rendered only while shown
  * (`baked`).
  */
case class Region(fill: String = Region.Eager) derives ConfiguredDecoder

object Region {
  val Eager: String = "eager"
  val Baked: String = "baked"
}

/** Per-node layout-cell parameters, rendered by the Renderer as extra CSS
  * classes on the node's `.fh-cell` wrapper (`<div class="fh-cell fh-cols-3"
  * id=…>`). Theme-agnostic: the class names are the `fh-` layout contract
  * (`fh-cols-<1..12>`, `fh-cols-full`, …) every theme's CSS implements — see
  * `lib/theme.pkl`. The authoring layer emits them from the
  * HA-`grid_options`-flavored builders (`columns(n)`, `fullWidth()`, …).
  *
  * An object rather than a bare list so it can grow further
  * HA-`grid_options`-style fields (rows, dense packing) without a wire break.
  * [[Dashboard.validate]] rejects a class that is not a plain CSS class token
  * (the renderer string-interpolates them into a `class` attribute).
  */
case class Cell(classes: List[String] = Nil) derives ConfiguredDecoder

/** Comparison operators for the query AST. Encoded as lowercase strings. */
enum Op:
  case Eq, Ne, Lt, Lte, Gt, Gte

object Op:
  given Decoder[Op] = Decoder[String].emap(s =>
    values
      .find(_.toString.equalsIgnoreCase(s))
      .toRight(s"unknown op: $s")
  )

/** A simple property-query AST evaluated at runtime against live entity state.
  *
  * `property` is one of `"domain"`, `"state"`, or `"attr:<name>"` (so
  * `device_class` is `"attr:device_class"`). Example "sensor batteries under
  * 20%":
  * `And([Cmp("domain", Eq, "sensor"), Cmp("attr:battery_level", Lt, 20)])`.
  */
sealed trait Predicate derives ConfiguredDecoder
object Predicate:
  case class And(items: List[Predicate]) extends Predicate
  case class Or(items: List[Predicate]) extends Predicate
  case class Not(item: Predicate) extends Predicate

  /** `entity` absent means "the subject" — the set member this guard is
    * attached to, which is the common case and leaves every existing predicate
    * unchanged. Present names a DIFFERENT entity, resolved at BUILD time ("show
    * each light while its own room's motion sensor is on"), and those ids must
    * reach the reverse index or the node is never woken by them — see
    * [[referencedEntities]].
    */
  case class Cmp(
      property: String,
      op: Op,
      value: Json,
      entity: Option[String] = None
  ) extends Predicate

  /** How many of a STATIC candidate list are present, compared against a
    * number: "more than two lights in here are on".
    *
    * There is no quantifier and no query. Presence is per-candidate — `when`
    * holds the guard for the candidates that have one, and a candidate absent
    * from it is unconditionally present — which is the same shape a
    * [[LayoutNode.SetMember]]'s clauses carry, and for the same reason: a
    * statically-true term short-circuits a disjunction, so residuals diverge
    * across the candidates of one set.
    *
    * That is also what retires the quantifiers: over a known list, `any` is
    * `count > 0`, `none` is `count == 0`, and `all` is `count == length`. A
    * comparison on a count is an ORDINARY predicate, so it composes with
    * everything — a member's guard, a surface condition, an `and`/`or`.
    *
    * Subject-independent by construction: it reads the named candidates, never
    * the entity it is attached to.
    */
  case class Count(
      candidates: List[String] = Nil,
      when: Map[String, Predicate] = Map.empty,
      op: Op,
      value: Json
  ) extends Predicate

  /** Every entity a predicate names besides its subject. */
  def referencedEntities(p: Predicate): List[String] = p match
    case Cmp(_, _, _, e) => e.toList
    case And(items)      => items.flatMap(referencedEntities)
    case Or(items)       => items.flatMap(referencedEntities)
    case Not(item)       => referencedEntities(item)
    // A count reads entities the node it guards may not render at all, so all
    // of them are references — without this the node is never woken by the
    // thing it counts.
    case Count(candidates, when, _, _) =>
      candidates ++ when.values.flatMap(referencedEntities)

  /** Does this read an entity it does not name? True for a `Cmp` with no
    * `entity`, which only means something where a SUBJECT is supplied — a set
    * member's guard, a set clause. A [[Activation.State]] supplies none, so one
    * there used to mean "some entity in the house" and is now rejected.
    *
    * A count's guards are excluded deliberately: each is evaluated against its
    * own candidate, so an unnamed subject inside one is bound.
    */
  def hasFreeSubject(p: Predicate): Boolean = p match
    case Cmp(_, _, _, entity) => entity.isEmpty
    case And(items)           => items.exists(hasFreeSubject)
    case Or(items)            => items.exists(hasFreeSubject)
    case Not(item)            => hasFreeSubject(item)
    case _: Count             => false

/** How a [[Surface]] becomes visible — its activation MODE, a sum so the
  * invalid combination (a default-open flag AND a state condition on one
  * member) is unrepresentable:
  *
  *   - [[User]] (`{kind:"user", defaultOpen}`): shown by a user action (a popup
  *     open, a tab click), optionally from the first paint (`defaultOpen`).
  *     Which member the client sees is per-connection state (uiState — ADR
  *     0005), so these surfaces render per session.
  *   - [[State]] (`{kind:"state", condition}`): shown while its `condition`
  *     holds over live entity state (an If/else branch). The condition is
  *     SUBJECT-FREE — every comparison in it names its own entity and a
  *     [[Predicate.Count]] carries its own candidates — so evaluating it is a
  *     handful of lookups, not a scan. [[Dashboard.validate]] rejects one that
  *     reads an unnamed subject. The choice is server truth — a pure function
  *     of entity state, identical for every viewer — so these surfaces ride the
  *     SHARED per-slug render pass and never enter a session's open set. An
  *     "else" member is simply `State(condition = Predicate.And(Nil))` — an
  *     empty conjunction is vacuously true and reads nothing — at a later
  *     `bakeIndex` (selection is first-match in `bakeIndex` order — see
  *     `SurfaceGraph.resolveActiveByState`); no member matching bakes empty
  *     content.
  *
  * Kind-discriminated on the wire like [[Predicate]]/[[LayoutNode]]. A bake
  * group must be mode-homogeneous — mixing kinds among one `bakeInto`'s members
  * is a [[Dashboard.validate]] error — so any one member decides its group's
  * mode.
  */
enum Activation derives ConfiguredDecoder:
  case User(defaultOpen: Boolean = false)
  case State(condition: Predicate)

/** A node in the recursive dashboard layout tree. */
sealed trait LayoutNode derives ConfiguredDecoder {

  /** The id this node was GIVEN, as opposed to the one its position would
    * derive. Only a [[LayoutNode.Component]] can carry one today: the case that
    * wants it is a tab bar, whose id reaches users through `ui.<id>`, and a
    * candidate set's own id is not user-visible the same way — its MEMBERS are
    * addressed by entity, which is already position-independent.
    */
  def authoredId: Option[String] = this match {
    case c: LayoutNode.Component => c.id
    case _: LayoutNode.SetNode   => None
  }
}

object LayoutNode:

  /** The region a card's children go to when nobody names one — the hole every
    * container template has spelled `{{#children}}` since before regions
    * existed.
    */
  val DefaultRegion: String = "children"

  /** `children` on the wire is either a bare ARRAY — the default region, which
    * is what the authoring layer emits for a card with one hole — or an object
    * keyed by region name.
    *
    * Two forms for the same reason [[SlotSource]] has two: the common case
    * should not have to name itself. `Grid { children { … } }` stays what an
    * author writes and what Pkl emits, and gains a name only where a card
    * genuinely has more than one hole to choose between.
    */
  given Decoder[Map[String, List[LayoutNode]]] =
    Decoder[List[LayoutNode]]
      .map(cs => if cs.isEmpty then Map.empty else Map(DefaultRegion -> cs))
      .or(Decoder.decodeMap[String, List[LayoutNode]])

  /** Children in the default region — the one-hole case, spelled for the many
    * construction sites that predate regions.
    */
  def kids(cs: LayoutNode*): Map[String, List[LayoutNode]] =
    if cs.isEmpty then Map.empty else Map(DefaultRegion -> cs.toList)

  /** A node referencing a shared template by name. Both leaves and containers
    * are Components — a container is simply a Component whose template splices
    * its rendered `children` via `{{#children}}{{{html}}}{{/children}}` (e.g.
    * the `fhrow`/`fhcol` templates), so new container kinds are added as
    * templates with no Scala change.
    *
    *   - `slots`: every template var, each a [[SlotSource]] (a live transform
    *     or a constant literal). There is no `params` map — the card's subject
    *     is the magical [[subjectEntity]] slot named `entity_id`, constants are
    *     literal slots, and the only non-slot vars are backend-*injected* (`id`
    *     and, for tabs, `panel` — see `Renderer`); the `id` is NOT authored.
    *   - `children`: nested nodes, rendered first and exposed to the template
    *     as a `children` list of `{html}` (empty for leaves).
    *
    * The live-dependency entities are DERIVED from the slots
    * ([[liveEntities]]), not authored — so adding a live slot is all it takes
    * to make a component track an entity.
    */
  case class Component(
      card: String,
      slots: Map[String, SlotSource] = Map.empty,
      // Child nodes BY REGION — the holes the card declares (`CardDef.regions`).
      // Decoded from either a bare ARRAY (the default region, which is what the
      // authoring layer emits today) or an object keyed by region name; see the
      // decoder below.
      children: Map[String, List[LayoutNode]] = Map.empty,
      // Layout-cell classes for this node's `.fh-cell` wrapper (see [[Cell]]).
      cell: Option[Cell] = None,
      // An AUTHORED id, replacing the position-derived one for this node and
      // rooting its descendants. Node ids reach users (a tab bar mirrors its own
      // into `ui.<id>`) and name every signal a card owns, so an author can pin
      // one rather than have layout decide it. `Dashboard.validate` checks it —
      // see `authoredIdErrors`, which owns the reason it needs checking at all.
      id: Option[String] = None
  ) extends LayoutNode:

    /** Every child, in one list — what a traversal that only needs to REACH
      * every node wants, which is most of them.
      *
      * Regions in name order so a walk is reproducible. Ids no longer come from
      * here — each step names its own region ([[LayoutNode.steps]]) — so this
      * order is nobody's contract, which is what lets a card have a second
      * region at all.
      */
    def allChildren: List[LayoutNode] =
      children.toList.sortBy(_._1).flatMap(_._2)

    /** The card's subject entity — the `entity_id` slot's value when it is a
      * constant `literal` (the common case). A *transform* `entity_id`
      * (indirection) resolves only at render time, so it contributes no static
      * subject here; its inheriting slots then track the `entity_id` slot's own
      * source instead. `None` ⇒ no subject (a container, a button with no
      * entity).
      */
    def subjectEntity: Option[String] =
      slots.get("entity_id").flatMap(_.literal)

    /** The entities whose live state this component depends on. A slot
      * contributes when it is reactive and not a constant literal; its source
      * is its own `entityId`, or the [[subjectEntity]] when the slot leaves it
      * unset (slot-level inheritance). Drives the reverse index and the
      * morph-wrapper decision (see `Renderer`). Empty ⇒ static HTML, never
      * patched.
      */
    def liveEntities: List[String] =
      slots.values.toList
        .filter(s => s.reactive && s.literal.isEmpty)
        .flatMap(s => s.entityId.orElse(subjectEntity))
        .distinct

  /** A set over a STATICALLY KNOWN candidate list.
    *
    * The candidates are decided at build time from the typed dump, so the
    * runtime never invents a member: it decides only PRESENCE (which candidates
    * render) and ORDER. See `docs/adr/0003-candidate-sets.md`.
    *
    *   - `candidates`: entity ids, in render order. When the ordering folded to
    *     registry facts this list is already sorted and [[orderBy]] is empty —
    *     the runtime filters and preserves it, with no comparisons.
    *   - `members`: per candidate, its guarded renderings.
    *   - `limit`: at most this many PRESENT members, applied after ordering.
    */
  case class SetNode(
      candidates: List[String] = Nil,
      members: Map[String, SetMember] = Map.empty,
      orderBy: List[SortTerm] = Nil,
      limit: Option[Int] = None,
      cell: Option[Cell] = None
  ) extends LayoutNode:
    /** Every entity that can wake this set: its candidates, plus any entity a
      * guard NAMES besides the member ("show while the hall sensor is on").
      */
    def liveEntities: List[String] =
      (candidates ++ members.values
        .flatMap(_.clauses)
        .flatMap(c =>
          c.when.toList.flatMap(Predicate.referencedEntities)
        )).distinct

  /** One candidate's renderings, tried in order. The first whose `when` holds
    * decides; falling off the end means the member is NOT RENDERED — which is
    * why there is no separate presence field.
    */
  case class SetMember(clauses: List[SetClause] = Nil) derives ConfiguredDecoder

  /** A guard plus the COMPLETE node it renders. Nothing is shared between
    * clauses or members, so a clause cannot be wrong about which member it
    * belongs to — see the "Rejected: the compressed format" note in the plan.
    */
  case class SetClause(
      when: Option[Predicate] = None,
      node: LayoutNode
  ) derives ConfiguredDecoder

  /** One lexicographic ordering position, most significant first.
    *
    * Present ONLY when some position needs live state. An ordering that folded
    * entirely to registry facts left [[SetNode.candidates]] pre-sorted and this
    * list empty, so the runtime filters without comparing anything.
    */
  case class SortTerm(
      by: SortKey,
      dir: String = "asc"
  ) derives ConfiguredDecoder:
    def descending: Boolean = dir == "desc"

  /** What an ordering position reads. Two kinds because "brightest first" and
    * "the ones that are on first" are both orderings and neither expresses the
    * other: a value has an order, a predicate has only true/false.
    */
  sealed trait SortKey derives ConfiguredDecoder
  object SortKey:
    /** Sort by a property's VALUE — `state`, `attr:<name>`, `reg:<name>`, the
      * same vocabulary a [[Predicate.Cmp]] names.
      */
    case class Prop(property: String) extends SortKey

    /** Sort by whether a predicate HOLDS, true first under `asc`. Lets one
      * vocabulary serve filtering and ordering, instead of a second notion of
      * "key" that only ordering understands.
      */
    case class Holds(predicate: Predicate) extends SortKey

  /** Stable, location-based id for an addressable node, derived from its index
    * path in the layout tree (e.g. `[1, 0]` -> `c_1_0`). Backend-generated, so
    * authors never invent ids; underscore-joined so it is also a valid signal
    * name (`_val_{{id}}`).
    */
  /** One step down the tree: which region the child sits in, and where in it.
    */
  case class Step(region: String, index: Int)

  /** A step's id segment. The DEFAULT region contributes only its index, so
    * every id a dashboard had before regions existed is byte-identical — no
    * snapshot moves, and a bookmarked `ui.<id>` tab URL still resolves. Only a
    * card with a second, NAMED region pays for one.
    *
    * The grammar therefore has two shapes, and the thing that keeps them apart
    * is that a region name can never look like an index: [[Dashboard.validate]]
    * rejects an all-digit one, so `headActions_0` cannot be read as the pair
    * `headActions`, `0` — nor `0_0` as region `0`, index `0`.
    */
  private def segment(s: Step): String =
    if s.region == DefaultRegion then s.index.toString
    else s"${sanitize(s.region)}_${s.index}"

  /** A path as id segments, without the `c` root — for the id schemes that nest
    * under something else ([[MemberGraph.innerSetId]]). ONE encoding, so a
    * child of a member and a child of the static tree cannot disagree about
    * what a region contributes.
    */
  def segments(path: List[Step]): String = path.map(segment).mkString("_")

  def pathId(path: List[Step]): NodeId =
    NodeId.derived(if path.isEmpty then "c" else s"c_${segments(path)}")

  /** A tree root's id: the author's, or the derived `c`, inside `prefix` (empty
    * for the main page, `s_<id>__` for a surface).
    */
  def rootId(prefix: String, node: LayoutNode): NodeId =
    NodeId.derived(prefix + node.authoredId.getOrElse("c"))

  /** The id one step down: the child's OWN authored id when it has one,
    * otherwise its parent's plus this step's segment.
    *
    * An authored id still carries `prefix`. It has to: the runtime works out
    * which surface a node belongs to from its id prefix
    * (`Renderer.prefixToRoot`), so an id that dropped it would be
    * unattributable — and two surfaces would be free to use the same name.
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

  /** [[pathId]] inside an id namespace — the main page's is empty, a surface's
    * is [[surfacePrefix]]. Named rather than left as `prefix + pathId(path)` at
    * four call sites, because the concatenation is what actually produces a
    * [[NodeId]] and the prefix alone is not one.
    */
  def nodeId(prefix: String, path: List[Step]): NodeId =
    NodeId.derived(prefix + pathId(path))

  /** Every child paired with the step that reaches it — what a walk that
    * assigns ids needs, as opposed to [[Component.allChildren]], which is for
    * walks that only need to reach every node.
    *
    * Regions in name order so the traversal is a function of the value rather
    * than of `Map` iteration. Order no longer decides ids — each step names its
    * own region — so this is now only about a walk being reproducible.
    */
  def steps(children: Map[String, List[LayoutNode]]): List[(Step, LayoutNode)] =
    children.toList.sortBy(_._1).flatMap { case (region, nodes) =>
      nodes.zipWithIndex.map { case (n, i) => Step(region, i) -> n }
    }

  /** Slug an arbitrary string (an entity id, a surface id) into a valid HTML id
    * fragment — also a valid Datastar signal-name fragment.
    */
  def sanitize(s: String): String = s.replaceAll("[^A-Za-z0-9_]", "_")

  /** A surface's mount/root element id (`s_<id>`) — the live-patch target and
    * the `remove` selector on close.
    */
  def surfaceRootId(surfaceId: String): String = s"s_${sanitize(surfaceId)}"

  /** Id prefix for a surface's inner nodes, so they never collide with the main
    * page (`s_<id>__` + [[pathId]] ⇒ `s_<id>__c_0`). This is the SAME scheme
    * the build-phase hoist uses to name a surface's content, so a node's
    * build-time id namespace and its render-time `{{id}}` are one story.
    */
  def surfacePrefix(surfaceId: String): String =
    s"${surfaceRootId(surfaceId)}__"

/** The dashboard's presentation, owned entirely by the theme (so the app isn't
  * tied to any particular CSS framework — e.g. BeerCSS is just a `stylesheets`
  * entry here, not baked into the server).
  *
  *   - `tokens`: design tokens — Home Assistant frontend theme variable name ->
  *     value (e.g. `"primary-color" -> "#03a9f4"`). Injected as CSS custom
  *     properties `--<name>` so the component CSS can `var(--…)`.
  *   - `tokensDark`: token overrides applied under
  *     `prefers-color-scheme: dark`, so the dashboard follows the browser's
  *     light/dark setting.
  *   - `stylesheets`: external CSS URLs to `<link>` (e.g. the BeerCSS CDN).
  *     RENDER-BLOCKING — the page waits for every one of them.
  *   - `deferredStylesheets`: the same thing for a sheet the first paint does
  *     not need, loaded without blocking it (`rel=preload` swapped to
  *     `stylesheet` on load, with a `<noscript>` fallback —
  *     https://web.dev/articles/defer-non-critical-css). The trade is that what
  *     it styles arrives a beat late, so this is for a sheet whose absence
  *     leaves the layout intact: an icon font, not a grid system.
  *   - `scripts`: external JS URLs, `<script type="module" src>`-injected in
  *     the document head after the stylesheets (ES modules — deferred, run
  *     after first paint). For framework helpers the theme's CSS needs (e.g.
  *     BeerCSS's slider fill); dashboard *behavior* stays with Datastar.
  *   - `inlineScripts`: classic `<script>` bodies, inlined in the head and run
  *     BEFORE first paint (so they can register document-level listeners that
  *     the first rendered element already needs). The gesture half of a CSS
  *     interaction — the counterpart of `styles`, for what CSS alone cannot
  *     express (see `theme.sliderHoldScript`). Trusted authored text, emitted
  *     verbatim like `styles` and `chrome`.
  *   - `styles`: inline CSS — framework→token mapping plus the rules that style
  *     the component classes (`.card`, `.fh-row`, …) from the tokens.
  *   - `chrome`: the dashboard-frame Mustache template — a single `{{{body}}}`
  *     hole. Owns the `#dashboard` swap target (the `renderBody` container that
  *     navigate/reload inner-patch into) and, for a dashboard that uses popups,
  *     the popup overlay host (the `<dialog>` + ✕ + close-`@post`), inlined in
  *     the theme (which imports no component library). EMPTY (`""`) falls back
  *     to the minimal
  *     `<main class="container" id="dashboard">{{{body}}}</main>` (no popup
  *     host) — see [[Renderer.renderPage]]. A non-empty `chrome` MUST contain
  *     an element `id="dashboard"` wrapping `{{{body}}}` — checked by
  *     [[Dashboard.validate]].
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

/** A lazily-activated render subtree mounted on demand — a popup or a tab
  * panel. Registered in [[Dashboard.surfaces]] keyed by id; a component's click
  * action (`surface/open/<id>`) opens it. The backend renders + streams it only
  * while a connection has it open (see `Renderer.renderSurface` and the
  * per-connection session in `Server`). Every surface is chrome-less — its
  * content renders straight into whatever host it swaps into; the frame around
  * that host (the popup overlay's `<dialog>`, inlined in `theme.chrome`, or a
  * `tabs` card's panel) lives in the theme/card, not per-surface.
  *
  *   - `content`: the surface's own layout tree (same node vocabulary as
  *     [[Dashboard.card]]).
  *   - The host — the live-patch target and eviction group — is DERIVED, not
  *     authored; see [[hostId]].
  *   - `bakeInto`/`bakeAs`: first-paint baking — the Component whose id equals
  *     `bakeInto` receives the SELECTED member's rendered content under the
  *     template var named `bakeAs` (e.g. a `tabs` card's `{{{panel}}}`),
  *     keeping the shown panel/branch in the initial HTML (no round-trip). The
  *     `_panel` suffix + `panel` name live only in the authoring layer.
  *   - `activation`: HOW this surface becomes visible — see [[Activation]].
  *     User-activated surfaces are opened by clicks / ui-state selection (per
  *     session); state-activated ones are selected by a condition over live
  *     entity state (shared, server truth). Absent on the wire ⇒
  *     `User(defaultOpen = false)`, so a plain popup declares nothing.
  */
case class Surface(
    content: LayoutNode,
    bakeInto: Option[NodeId] = None,
    bakeAs: Option[String] = None,
    // A surface's position within its `bakeInto` group: the ordered member
    // list a ui-state index (user mode) selects among, and the first-match
    // order (then, elseif…, else) state selection walks.
    bakeIndex: Option[Int] = None,
    activation: Activation = Activation.User(false)
) derives ConfiguredDecoder:

  /** The surface's host element id: the live-patch target AND the eviction
    * group. Derived — a baked surface (tab panel) hosts at
    * `<bakeInto>_<bakeAs>` (enforcing the `id="{{bakeInto}}_{{bakeAs}}"` host
    * convention); an unbaked surface (a popup) hosts at the overlay
    * [[Dashboard.PopupHostId]].
    */
  def hostId: DomId = (bakeInto, bakeAs) match
    case (Some(into), Some(as)) => DomId.derived(s"${into}_${as}")
    case _                      => Dashboard.PopupHostId

/** The `dashboard.json` build artifact produced by the build phase.
  *
  *   - `slug`: the dashboard's stable id (its route is `/d/<slug>`; navigation
  *     targets it). ServerApp defaults it from the entry filename.
  *   - `cards`: `cardName -> CardDef` (shared, reused library of templates).
  *   - `css`: the base stylesheet every dashboard gets whatever its theme — the
  *     `fh-` layout contract, the `--fh-*` variables the cards read, and the
  *     classes the runtime itself emits (banners, toast, the busy states). It
  *     sits here rather than on the [[Theme]] precisely so a theme cannot drop
  *     it: it is emitted FIRST, and a theme may only override it (ADR 0020).
  *     Authored in `lib/core/css.pkl`, assigned by `lib/entry.pkl`.
  *   - `theme`: all presentation (tokens + stylesheets + CSS); see [[Theme]].
  *   - `card`: the root of the recursive layout tree (itself a card, usually a
  *     container). Component HTML is composed in Scala (see `Renderer`), not
  *     via mustache layout placeholders.
  *   - `surfaces`: the popup/tab subtrees, keyed by id (see [[Surface]]).
  *   - `title`: the page `<title>` — an optional top-level authoring field
  *     (`None` when the key is absent); the Server falls back to the [[slug]]
  *     when it is `None`.
  *   - `access`: who may see it (issue #89) — the same optional-field shape as
  *     `title`. `None` means "whatever the site says"; `Site.decode` folds the
  *     site default in, so nothing downstream sees the `None`.
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

  /** Every registered card's own CSS, in card-name order so the emitted
    * stylesheet is a pure function of the model.
    *
    * All registered cards, not only the ones this tree uses: the registry is
    * one library's worth (a handful of KB), and pruning it to the cards
    * actually rendered would have to account for surfaces and set clauses too.
    * The renderer has what it would need — see ADR 0020's open work, alongside
    * minifying the whole block at runtime instead of by hand in Pkl.
    */
  lazy val cardCss: String =
    cards.toList.sortBy(_._1).map(_._2.css).filter(_.nonEmpty).mkString("\n")

  /** Every entity this dashboard can EVER address — the main layout and every
    * surface, candidate sets included.
    *
    * Static, and soundly so: a candidate set's membership is decided live but
    * its candidate LIST is fixed at build time (ADR 0003), so this does not
    * depend on the current state and cannot grow while the dashboard runs.
    *
    * That is what makes it usable as an authorisation bound (issue #89, ADR
    * 0023): an action POST may only touch an entity its own dashboard names, so
    * admission to a dashboard is not admission to the whole house.
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
        // A clause node is an ordinary component, so its own slots and children
        // are reached by the same walk — and its candidate is named in a
        // literal `entity_id` slot rather than injected per match.
        set.candidates ++ set.members.values.toList
          .flatMap(_.clauses)
          .flatMap(cl => walk(cl.node))
    }

    (walk(card) ++ surfaces.values.toList.flatMap(s => walk(s.content))).toSet
  }

  /** Validate that every card reference resolves, supplies the params/slots the
    * card's template declares, and that each slot's `transform` is compilable
    * JSONata. Returns human-readable errors (empty = valid).
    *
    * A transform that fails to compile is a **hard** error: the dashboard does
    * not load (the build/reload fails with the message, and live-reload keeps
    * the previous working renderer) — better than swapping in a dashboard whose
    * values silently blank out. `locateTransform` maps a transform back to a
    * source location (e.g. `site.pkl:42`) for a friendlier error; the default
    * ignores it (the model stays source-agnostic).
    */
  def validate(
      locateTransform: String => Option[String] = _ => None
  ): List[String] =
    // Every required template var is a slot, satisfied by an authored slot OR a
    // backend-`injected` name: `id`/`panel` always, plus the matched `entity_id`
    // inside a set clause (where the case strips the build-time one).
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

    // A live-expression slot's value is a `transform`, which must be parseable
    // JSONata. A constant `literal` slot has no transform, so nothing to check.
    def slotErrors(
        nodeId: String,
        cardName: String,
        slots: Map[String, SlotSource]
    ): List[String] =
      slots.toList.sortBy(_._1).flatMap { case (name, src) =>
        val transformError =
          if (src.literal.isDefined) None
          else
            Transform.parse(src.transform).left.toOption.map { err =>
              val at =
                locateTransform(src.transform).fold("")(loc => s" (at $loc)")
              s"$nodeId: slot '$name' has an invalid transform$at: $err"
            }
        transformError.toList ++ signalErrors(nodeId, cardName, name, src)
      }

    // A signal slot's value leaves the element's HTML on the patch path — a
    // `datastar-patch-signals` frame carries it instead (ADR 0017). Both checks
    // are for failures that are otherwise SILENT: the card renders, the patches
    // get smaller, and the value simply stops updating.
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
      else
        // The card must PLACE the binding, via the `<slot>__bind` var the
        // renderer injects. Without it the patch form withholds the value and
        // nothing in the DOM puts it back.
        cards
          .get(cardName)
          .toList
          .filterNot(cd => cd.template.contains(s"{{{${name}__bind}}}"))
          .map(_ =>
            s"$nodeId: card '$cardName' has slot '$name' marked as a signal " +
              s"slot, but no part of its template places {{{${name}__bind}}} " +
              "— the value would stop updating"
          )

    def children(
        kids: Map[String, List[LayoutNode]],
        id: NodeId
    ): List[String] =
      LayoutNode.steps(kids).flatMap { case (step, n) =>
        walk(n, LayoutNode.childId("", id, step, n))
      }

    // Cell classes are string-interpolated into the wrapper's `class`
    // attribute, so each must be a plain CSS class token — reject anything
    // else loudly at build time rather than emitting broken/injectable markup.
    def cellErrors(nodeId: String, cell: Option[Cell]): List[String] =
      cell.toList.flatMap(_.classes).collect {
        case cls if !cls.matches("[A-Za-z0-9_-]+") =>
          s"$nodeId: cell class '$cls' is not a plain CSS class token " +
            "([A-Za-z0-9_-]+)"
      }

    // A `wrapAsCell = false` card renders bare — no id'd `.fh-cell` wrapper —
    // so everything that rides on the wrapper is unusable with it, and
    // silently so at render time. Reject the combinations loudly instead:
    // live-entity slots (the pushed morphs would never match an element in the
    // DOM), cell params (there is no wrapper to carry the classes), and
    // set clauses (every member IS its wrapped per-entity patch target —
    // Renderer.renderCase wraps unconditionally).
    def noWrap(cardName: String): Boolean =
      cards.get(cardName).exists(!_.wrapAsCell)

    def walk(node: LayoutNode, nodeId: NodeId): List[String] =
      node match
        case c @ LayoutNode.Component(card, slots, _, cell, _) =>
          val wrapErrors =
            if (!noWrap(card)) Nil
            else
              Option
                .when(c.liveEntities.nonEmpty)(
                  s"$nodeId: card '$card' has wrapAsCell=false but binds live " +
                    s"entities (${c.liveEntities.mkString(", ")}) — an " +
                    "unwrapped node has no morph target, so its live updates " +
                    "would never apply; make those slots literal / " +
                    "reactive=false or drop the opt-out"
                )
                .toList ++
                Option
                  .when(cell.isDefined)(
                    s"$nodeId: card '$card' has wrapAsCell=false but carries " +
                      "cell params — they ride on the .fh-cell wrapper this " +
                      "card opts out of"
                  )
                  .toList
          checkRef(
            nodeId,
            card,
            Dashboard.injectedStatic,
            slots.keySet
          ) ++ slotErrors(nodeId, card, slots) ++ cellErrors(nodeId, cell) ++
            wrapErrors ++ children(c.children, nodeId)
        // A set's clauses carry COMPLETE nodes — their own card, slots (the
        // candidate's `entity_id` among them) and cell — so each one validates
        // as the ordinary node it is. `noWrap` is rejected because every member
        // is its own per-candidate patch target.
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
                  )
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

    // A STRUCTURAL card may bind no live entity slot. Its element contains the
    // regions, so a patch aimed at it would carry back everything it holds —
    // which is the whole reason structure is never a patch target. A card that
    // wants its OWN markup to move puts that markup in a region as a node, or
    // moves the value to a signal slot (ADR 0017), which changes without a
    // re-render.
    //
    // The authoring layer says the same on `Node.slots`, but `cards` is decoded
    // from JSON, so the model has to say it too or the guarantee stops at the
    // Pkl boundary.
    val structureLiveSlotErrors: List[String] = {
      def walk(node: LayoutNode): List[String] = node match {
        case c: LayoutNode.Component =>
          val here = cards
            .get(c.card)
            .filter(_.isStructure)
            .toList
            .filter(_ => c.liveEntities.nonEmpty)
            .map(_ =>
              s"card '${c.card}' holds regions and so is never a patch " +
                s"target, but this node binds live entities " +
                s"(${c.liveEntities.sorted.mkString(", ")}) — they would never " +
                "reach the DOM. Put the live markup in a region as a node, or " +
                "make the slot a signal slot"
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

    // A template that SPLICES CHILDREN into a section must declare that section
    // as a region. Without this the leaf/structure split is not decidable from
    // the card: such a template reads as a leaf — no regions — while its bytes
    // carry its children, so it would be cached and patched, and a patch would
    // re-send everything under it.
    //
    // A runtime walk used to catch it (`carriesMount`, asking whether anything
    // BELOW a node held a mount). That was a check on the tree standing in for
    // a fact about the card; this is the fact.
    //
    // The signature is `{{{html}}}` inside a section: that is what splicing a
    // child's rendering looks like and the only thing it looks like.
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

    // A region nobody can fill and a hole nobody declared are the same defect
    // seen from two sides, and both are silent: the region renders nothing, or
    // the hole renders empty. Disjointness only means something if the holes
    // are the ones the card said it had.
    val regionHoleErrors: List[String] =
      cards.toList.sortBy(_._1).flatMap { case (name, cd) =>
        cd.regions.toList.sortBy(_._1).collect {
          case (r, region) if !cd.template.contains(cd.holeOf(r, region)) =>
            s"card '$name': declares region '$r' (${region.fill}) but its " +
              s"`template` places no ${cd.holeOf(r, region)} hole for it — " +
              "nothing would ever appear there"
        }
      }

    // What keeps the id grammar's two shapes apart. A step in the DEFAULT
    // region contributes only its index (`c_0_1`), so every id that existed
    // before regions is unchanged; a NAMED region contributes `<name>_<index>`.
    // The two can never be confused as long as a name cannot look like an
    // index — so an all-digit name is rejected, and with it the only way
    // `0_0` could mean either "region 0, index 0" or "index 0, index 0".
    //
    // Empty is rejected for the same reason (it would contribute a bare `_`),
    // and a non-token name because ids are interpolated into `id` attributes
    // and signal names — the rule cell classes already get.
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
      * There used to be a fourth rule — that an id must not READ as another
      * node's descendant, because the runtime decided ancestry by string prefix
      * and `detail_0` looks like a child of `detail`. That rule was a prop
      * under an encoding, not a constraint authors could learn anything from,
      * and it is gone: ancestry comes from [[fh.view.runtime.NodeAncestry]],
      * which asks the tree. Two nodes may now be called `detail` and `detail_0`
      * and simply be unrelated, which is what they are.
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
            LayoutNode.steps(c.children).flatMap { case (step, ch) =>
              walkIds(
                ch,
                prefix,
                LayoutNode.childId(prefix, id, step, ch),
                inSet
              )
            }
          // A clause node is instantiated once PER MEMBER, so anything it names
          // would be claimed by every member at once. Flagged rather than
          // walked for ids.
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
      val authored = all.filter(_._3.isDefined)
      val ids = all.map(_._1)

      val shape = authored.flatMap { case (id, inSet, Some(name)) =>
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

    // A non-empty theme.chrome MUST wrap {{{body}}} in an element carrying
    // id="dashboard" — that's the navigate/reload swap target. An empty chrome
    // is fine (Renderer falls back to the minimal default). Fail loudly here
    // rather than silently breaking navigation at render time.
    val chromeErrors: List[String] =
      Option
        .when(
          theme.chrome.nonEmpty && !theme.chrome.contains("id=\"dashboard\"")
        )(
          "theme.chrome must contain an element with id=\"dashboard\" wrapping {{{body}}}"
        )
        .toList

    // A `bakeInto` must name a node that EXISTS. It is the one relation an
    // author never writes — the hoist mints it — so a mismatch means the
    // build's id derivation and the renderer's have drifted, and the symptom is
    // silent: the host renders its wrapper with an empty hole, the way a
    // state group with no matching branch legitimately does. Checking it here
    // is what turns "the panel is blank" into a build error naming the surface.
    val danglingBakes: List[String] = {
      // The card name at `target`, if the walk reaches it. Same traversal as
      // [[idsOf]], stopping at the node asked about.
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
                .steps(c.children)
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
            LayoutNode.steps(c.children).flatMap { case (step, ch) =>
              idsOf(ch, prefix, LayoutNode.childId(prefix, id, step, ch))
            }
          // Neither member container hosts a bake: a member renders with no
          // children and no bake group.
          case _: LayoutNode.SetNode => Nil
        })
      val known: Set[NodeId] =
        (idsOf(card, "", LayoutNode.rootId("", card)) ++
          surfaces.toList.flatMap { case (sid, s) =>
            val p = LayoutNode.surfacePrefix(sid)
            idsOf(s.content, p, LayoutNode.rootId(p, s.content))
          }).toSet
      // The node a surface bakes into, when it names one that exists — needed
      // twice below, and `known` alone cannot supply the card.
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
            // ...and that node's card must actually have a BAKED region by
            // this name. `bakeAs` names the template var the content is
            // substituted into, which since regions IS a region name — so the
            // two can be checked against each other instead of agreeing by
            // convention. Getting it wrong is the silent failure this section
            // already describes: the host renders its wrapper with an empty
            // hole, exactly as a legitimately unmatched state group does.
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
              case _ => Nil
            }
        }
      }
    }

    // A bake group's activation mode must be homogeneous: the runtime decides
    // per GROUP whether selection is user truth (per session) or server
    // truth (state condition, shared) — a group mixing both has no coherent
    // owner for that choice, so it is rejected here rather than half-working.
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

    // A state activation has no subject to supply, so every comparison in its
    // condition must name its own entity. Before candidate sets an unnamed one
    // was quantified over the whole state map, which cost a scan and never said
    // what the author meant ("some entity is both light.x and on").
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

    // The main layout, then every surface's content tree (so card refs / params
    // / slots / transforms inside popups are checked too). Surface errors are
    // prefixed with the surface id for locatability.
    authoredIdErrors ++
      structureLiveSlotErrors ++
      undeclaredHoleErrors ++
      regionHoleErrors ++
      regionNameErrors ++
      chromeErrors ++
      danglingBakes ++
      activationErrors ++
      unboundConditions ++
      walk(card, LayoutNode.rootId("", card)) ++
      surfaces.toList.sortBy(_._1).flatMap { case (sid, surface) =>
        walk(surface.content, LayoutNode.rootId("", surface.content))
          .map(err => s"surface '$sid': $err")
      }

  /** Non-fatal problems worth telling the author about: unlike [[validate]]'s
    * errors the dashboard still builds and serves, it just misbehaves in a way
    * that is hard to attribute from the browser.
    *
    * Both are about the popup mount, which only the THEME can place (ADR 0002),
    * and both are silent at render time — which is why they are reported at
    * all:
    *
    *   - no `id="popups"` host in the chrome and there is nowhere to patch a
    *     popup into, so every popup tap appears to do nothing. An empty chrome
    *     counts: the fallback frame has no host either.
    *   - a host but no `{{{popups}}}` hole and popups work, but one being
    *     RESTORED on a refresh cannot be baked into the first paint, so it pops
    *     in once the stream connects.
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

  /** Every distinct live-slot transform string in the layout and its surfaces
    * (constant `literal` slots carry no transform and are excluded). These are
    * exactly the expressions the renderer compiles — the single source for both
    * [[validated]]'s compile and `Transforms.from`.
    */
  def transformStrings: List[String] =
    def slotsOf(n: LayoutNode): List[SlotSource] = n match
      case c: LayoutNode.Component =>
        c.slots.values.toList ++ c.allChildren.flatMap(slotsOf)
      case s: LayoutNode.SetNode =>
        s.members.values.toList
          .flatMap(_.clauses)
          .flatMap(c => slotsOf(c.node))
    (slotsOf(card) ++ surfaces.values.flatMap(s => slotsOf(s.content))).toList
      .filter(_.literal.isEmpty)
      .map(_.transform)
      .distinct

  /** Parse this dashboard into a [[Dashboard.Validated]] proof: the same checks
    * as [[validate]], and — on success — the COMPILED transforms carried along,
    * so validation is the one gate and the renderer never recompiles or defends
    * against an uncompilable expression. `Left` carries the human-readable
    * errors (always non-empty; it is exactly `validate`'s output). The default
    * `locateTransform` keeps the model source-agnostic (see [[validate]]).
    */
  def validated(
      locateTransform: String => Option[String] = _ => None
  ): Either[List[String], Dashboard.Validated] =
    validate(locateTransform) match
      case Nil  => Right(Dashboard.Validated(this, compileTransforms))
      case errs => Left(errs)

  /** Compile every [[transformStrings]] expression. Total by contract: only
    * [[validated]] calls it, and only after [[validate]] proved each
    * compilable, so a `Left` from the parser cannot occur here (a bad one would
    * have failed validation) and is silently dropped rather than defended
    * against.
    */
  private def compileTransforms: Map[String, Transform.Compiled] =
    transformStrings.flatMap(t => Transform.parse(t).toOption.map(t -> _)).toMap

object Dashboard:
  /** A dashboard PROVEN valid: every card reference resolves, every slot is
    * satisfied, and every slot transform compiled (kept in `transforms`, so the
    * renderer looks them up instead of recompiling or defending against a bad
    * one). Constructed only by [[Dashboard.validated]] — the type is the proof.
    */
  case class Validated(
      dashboard: Dashboard,
      transforms: Map[String, Transform.Compiled],
      // The RESOLVED access rule (issue #89) — the dashboard's own if it named
      // one, else its site's. Resolved once by `Site.decode` via [[withAccess]]
      // rather than left as the model's `Option`, so no gate has to re-derive
      // "and what does the site say" on every request.
      //
      // The default is deliberately the restrictive one: a construction path
      // that forgets to resolve demands a login rather than serving the
      // dashboard to the world.
      access: Access = Access.default
  ):
    /** Fold the site-wide default in: the dashboard's own rule wins, the site's
      * applies otherwise.
      */
    def withAccess(siteDefault: Access): Validated =
      copy(access = dashboard.access.getOrElse(siteDefault))

  /** The theme's popup overlay mount — the `<div id="popups">` a popup's
    * (dialog-wrapped) content is patched into (and cleared from on close). The
    * dialog itself is NOT here and NOT backend chrome: it is a plain `popup`
    * container card composed into the surface's content by
    * `openPopup`/`c.popup` (see lib/components.pkl), so the backend renders
    * every surface bare. `Surface.hostId` derives to this for an unbaked
    * surface (a popup); `Server.swapHost` uses it as both the eviction group
    * and the patch target for `POST /sse/surface/:slug/open/:id` and
    * `POST /sse/popup/:slug/close`.
    */
  val PopupHostId: DomId = DomId.derived("popups")

  /** Backend-injected template vars available to a *static* component (the
    * author never supplies them): the stable location-based `id`.
    * (Default-panel baking moved to the `Mount` node, so there is no longer an
    * injected `panel`.)
    */
  val injectedStatic: Set[String] = Set("id")
