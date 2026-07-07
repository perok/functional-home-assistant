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
  * In a [[LayoutNode.Dynamic]] case the matched entity is injected as the
  * `entity_id` param per match, so an inheriting (`entityId = None`) slot binds
  * to each match automatically — no per-slot placeholder.
  *
  * `literal` is the cheapest slot: a hardcoded value used verbatim — no entity,
  * no JSONata, no compilation. A label like `"Kitchen"` or a constant action
  * URL is this, not a `"Kitchen"` JSONata string-literal `transform`. It is
  * authored as a bare JSON string rather than an object; when set, every other
  * field is unused. Only a value that varies with live state needs the
  * object/`transform` form.
  */
given Configuration =
  Configuration.default.withDefaults
    .withDiscriminator("kind")
    .withTransformConstructorNames(_.toLowerCase)

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
    reactive: Boolean = true
)

object SlotSource:
  // The object form (a live-expression slot) — the standard configured decoder.
  private val objDecoder: Decoder[SlotSource] = ConfiguredDecoder.derived

  /** A slot is either a bare JSON string (a constant [[literal]]) or an object
    * (a live-expression slot). Decoding accepts both forms.
    */
  given Decoder[SlotSource] =
    Decoder[String].map(s => SlotSource(literal = Some(s))).or(objDecoder)

/** A reusable card in the shared library (a node references one by name).
  *
  *   - `template`: a Mustache string. Escaped `{{slot}}` values are HTML-safe;
  *     raw author values (action URLs, ids) use `{{{...}}}`.
  *   - `slots`: every required template var, each filled from a [[SlotSource]]
  *     — a live entity transform OR a constant literal. This is the *one*
  *     vocabulary: a card's subject is the magical `entity_id` slot, a constant
  *     like a `label`/`min` is a literal slot, a live value is a transform
  *     slot. The only non-slot template vars are backend-*injected* ones the
  *     author never supplies (`id`, and the matched `entity_id` inside a
  *     dynamic case — see
  *     [[Dashboard.injectedStatic]]/[[Dashboard.injectedDynamic]]), so they
  *     need no entry. Optional pieces (a tap `action`, a `secondary` line) need
  *     no entry either — [[Dashboard.validate]] only flags missing *required*
  *     slots and ignores extra ones.
  */
case class CardDef(
    template: String,
    slots: List[String] = Nil
) derives ConfiguredDecoder

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
  case class Cmp(property: String, op: Op, value: Json) extends Predicate

/** One branch of a [[LayoutNode.Dynamic]] group: entities matching the group's
  * query are rendered with the first case whose `when` predicate matches.
  */
case class DynamicCase(
    when: Predicate,
    card: String,
    slots: Map[String, SlotSource] = Map.empty
) derives ConfiguredDecoder

/** A node in the recursive dashboard layout tree. */
sealed trait LayoutNode derives ConfiguredDecoder
object LayoutNode:
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
      children: List[LayoutNode] = Nil
  ) extends LayoutNode:
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

  /** A runtime-resolved group with per-entity template dispatch.
    *
    *   - `query`: overall membership filter (absent = match all entities).
    *   - `cases`: each matched entity renders with the first case whose `when`
    *     matches (skipped if none). The renderer injects `id` and sets the
    *     matched entity as the `entity_id` slot per match, so every inheriting
    *     slot (the `label`'s `$attr.friendly_name`, value/action slots)
    *     resolves against the match; a slot that names its own entity, or a
    *     constant literal, is left untouched. The group's own id is
    *     location-derived.
    */
  case class Dynamic(
      query: Option[Predicate] = None,
      cases: List[DynamicCase] = Nil
  ) extends LayoutNode

  /** Stable, location-based id for an addressable node, derived from its index
    * path in the layout tree (e.g. `[1, 0]` -> `c_1_0`). Backend-generated, so
    * authors never invent ids; underscore-joined so it is also a valid signal
    * name (`val_{{id}}`).
    */
  def pathId(path: List[Int]): String =
    if path.isEmpty then "c" else path.mkString("c_", "_", "")

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
  * tied to any particular CSS framework — e.g. Pico is just a `stylesheets`
  * entry here, not baked into the server).
  *
  *   - `tokens`: design tokens — Home Assistant frontend theme variable name ->
  *     value (e.g. `"primary-color" -> "#03a9f4"`). Injected as CSS custom
  *     properties `--<name>` so the component CSS can `var(--…)`.
  *   - `tokensDark`: token overrides applied under
  *     `prefers-color-scheme: dark`, so the dashboard follows the browser's
  *     light/dark setting.
  *   - `stylesheets`: external CSS URLs to `<link>` (e.g. the Pico CDN).
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
  *   - `bakeInto`/`bakeAs`: first-paint baking — when `defaultOpen`, the
  *     Component whose id equals `bakeInto` receives this surface's rendered
  *     content under the template var named `bakeAs` (e.g. a `tabs` card's
  *     `{{{panel}}}`), keeping the default panel in the initial HTML (no
  *     round-trip). The `_panel` suffix + `panel` name live only in the
  *     jsonnet.
  *   - `defaultOpen`: shown from the first paint without a user action — a tabs
  *     default panel (or a popup open on load). The connection seeds its open
  *     set with every default-open surface (so it receives live patches
  *     immediately), and it is baked via `bakeInto`/`bakeAs` above. This is the
  *     ONLY surface-level "shown by default" signal the backend reads.
  */
case class Surface(
    content: LayoutNode,
    bakeInto: Option[String] = None,
    bakeAs: Option[String] = None,
    // A surface's position within its `bakeInto` group, so a cookie value
    // (parsed to an int at the point of use) selects a member without parsing
    // surface-id suffixes. `defaultOpen` stays for the fallback when no cookie /
    // no match; it is effectively `bakeIndex == 0`.
    bakeIndex: Option[Int] = None,
    defaultOpen: Boolean = false
) derives ConfiguredDecoder:

  /** The surface's host element id: the live-patch target AND the eviction
    * group. Derived — a baked surface (tab panel) hosts at
    * `<bakeInto>_<bakeAs>` (enforcing the `id="{{bakeInto}}_{{bakeAs}}"` host
    * convention); an unbaked surface (a popup) hosts at the overlay
    * [[Dashboard.PopupHostId]].
    */
  def hostId: String = (bakeInto, bakeAs) match
    case (Some(into), Some(as)) => s"${into}_${as}"
    case _                      => Dashboard.PopupHostId

/** The `dashboard.json` build artifact produced by the jsonnet build phase.
  *
  *   - `slug`: the dashboard's stable id (its route is `/d/<slug>`; navigation
  *     targets it). ServerApp defaults it from the entry filename.
  *   - `cards`: `cardName -> CardDef` (shared, reused library of templates).
  *   - `theme`: all presentation (tokens + stylesheets + CSS); see [[Theme]].
  *   - `card`: the root of the recursive layout tree (itself a card, usually a
  *     container). Component HTML is composed in Scala (see `Renderer`), not
  *     via mustache layout placeholders.
  *   - `surfaces`: the popup/tab subtrees, keyed by id (see [[Surface]]).
  *   - `title`: the page `<title>` — an optional top-level authoring field
  *     (`None` when the key is absent, e.g. every jsonnet entry); the Server
  *     falls back to the [[slug]] when it is `None`.
  */
case class Dashboard(
    cards: Map[String, CardDef],
    card: LayoutNode,
    theme: Theme = Theme(),
    surfaces: Map[String, Surface] = Map.empty,
    slug: String = "dashboard",
    title: Option[String] = None
) derives ConfiguredDecoder:

  /** Validate that every card reference resolves, supplies the params/slots the
    * card's template declares, and that each slot's `transform` is compilable
    * JSONata. Returns human-readable errors (empty = valid).
    *
    * A transform that fails to compile is a **hard** error: the dashboard does
    * not load (the build/reload fails with the message, and live-reload keeps
    * the previous working renderer) — better than swapping in a dashboard whose
    * values silently blank out. `locateTransform` maps a transform back to a
    * source location (e.g. `dashboard.jsonnet:42`) for a friendlier error; the
    * default ignores it (the model stays source-agnostic).
    */
  def validate(
      locateTransform: String => Option[String] = _ => None
  ): List[String] =
    // Every required template var is a slot, satisfied by an authored slot OR a
    // backend-`injected` name: `id`/`panel` always, plus the matched `entity_id`
    // inside a dynamic case (where the case strips the build-time one).
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
        slots: Map[String, SlotSource]
    ): List[String] =
      slots.toList.flatMap { case (name, src) =>
        if (src.literal.isDefined) None
        else
          Transform.parse(src.transform).left.toOption.map { err =>
            val at =
              locateTransform(src.transform).fold("")(loc => s" (at $loc)")
            s"$nodeId: slot '$name' has an invalid transform$at: $err"
          }
      }

    def children(nodes: List[LayoutNode], path: List[Int]): List[String] =
      nodes.zipWithIndex.flatMap { case (n, i) => walk(n, path :+ i) }

    def walk(node: LayoutNode, path: List[Int]): List[String] =
      node match
        case LayoutNode.Component(card, slots, kids) =>
          val nodeId = LayoutNode.pathId(path)
          checkRef(
            nodeId,
            card,
            Dashboard.injectedStatic,
            slots.keySet
          ) ++ slotErrors(nodeId, slots) ++ children(kids, path)
        case LayoutNode.Dynamic(_, cases) =>
          cases.flatMap { c =>
            val nodeId = s"${LayoutNode.pathId(path)}/${c.card}"
            checkRef(
              nodeId,
              c.card,
              Dashboard.injectedDynamic,
              c.slots.keySet
            ) ++ slotErrors(nodeId, c.slots)
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

    // The main layout, then every surface's content tree (so card refs / params
    // / slots / transforms inside popups are checked too). Surface errors are
    // prefixed with the surface id for locatability.
    chromeErrors ++
      walk(card, Nil) ++
      surfaces.toList.sortBy(_._1).flatMap { case (sid, surface) =>
        walk(surface.content, Nil).map(err => s"surface '$sid': $err")
      }

object Dashboard:
  /** The theme's popup overlay mount — the `<div id="popups">` a popup's
    * (dialog-wrapped) content is patched into (and cleared from on close). The
    * dialog itself is NOT here and NOT backend chrome: it is a plain `popup`
    * container card composed into the surface's content by
    * `openPopup`/`c.popup` (see components.libsonnet), so the backend renders
    * every surface bare. `Surface.hostId` derives to this for an unbaked
    * surface (a popup); `Server.swapHost` uses it as both the eviction group
    * and the patch target for `POST /sse/surface/open/:id` and
    * `POST /sse/popup/close`.
    */
  val PopupHostId: String = "popups"

  /** Backend-injected template vars available to a *static* component (the
    * author never supplies them): the stable location-based `id`.
    * (Default-panel baking moved to the `Mount` node, so there is no longer an
    * injected `panel`.)
    */
  val injectedStatic: Set[String] = Set("id")

  /** Backend-injected vars inside a *dynamic* case: the static set plus the
    * matched entity's `entity_id` (the case strips the build-time `entity_id`
    * slot; the renderer sets the matched one per render).
    */
  val injectedDynamic: Set[String] = injectedStatic ++ Set("entity_id")
