package fh.view.model

import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.derivation.{Configuration, ConfiguredCodec}

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
  * `$attr.brightness` when a light is off). `bypassUnavailable` (off by
  * default) makes an `"unavailable"`/`"unknown"` entity show its raw state
  * *instead of* running the transform — set it on value-display slots whose
  * transform would error on a non-numeric state (`$number($state)`); leave it
  * off for identity-derived slots (an action must resolve regardless of
  * availability).
  *
  * In a [[LayoutNode.Dynamic]] case, `entity` is a placeholder (e.g.
  * `"$self"`); the renderer rebinds it to each matched entity.
  */
given Configuration =
  Configuration.default.withDefaults
    .withDiscriminator("kind")
    .withTransformConstructorNames(_.toLowerCase)

case class SlotSource(
    entity: String,
    // The value expression — JSONata over $state/$attr/$domain/$entity_id, compiled
    // at build time (validated below) and reused by the renderer. Defaults to the
    // entity's raw state.
    transform: String = "$state",
    // Used when the transform yields "" (e.g. brightness when a light is off).
    // Keeps numeric signal initialisers like `{bri: {{x}}}` valid.
    default: Option[String] = None,
    // When the entity is unavailable/unknown, show its raw state and skip the
    // transform (keeps a numeric value-display readable). Off for identity slots.
    bypassUnavailable: Boolean = false
) derives ConfiguredCodec

/** A reusable card in the shared library (a node references one by name).
  *
  *   - `template`: a Mustache string. Escaped `{{slot}}` values are HTML-safe;
  *     raw author values (action URLs, ids) use `{{{...}}}`.
  *   - `params`: required *static* template vars — supplied by the author at
  *     build time or backend-injected (`id`; `entity_id`/`label` in a dynamic
  *     case).
  *   - `slots`: required *live* template vars — bound per render from entity
  *     state (a [[SlotSource]] transform). Optional pieces (a tap `action`, a
  *     `secondary` line) need no entry — [[Dashboard.validate]] only flags
  *     missing *required* inputs and ignores extra ones.
  */
case class CardDef(
    template: String,
    params: List[String] = Nil,
    slots: List[String] = Nil
) derives ConfiguredCodec

/** Comparison operators for the query AST. Encoded as lowercase strings. */
enum Op:
  case Eq, Ne, Lt, Lte, Gt, Gte

object Op:
  given Codec[Op] = Codec.from(
    Decoder[String].emap(s =>
      values
        .find(_.toString.equalsIgnoreCase(s))
        .toRight(s"unknown op: $s")
    ),
    Encoder[String].contramap(_.toString.toLowerCase)
  )

/** A simple property-query AST evaluated at runtime against live entity state.
  *
  * `property` is one of `"domain"`, `"state"`, or `"attr:<name>"` (so
  * `device_class` is `"attr:device_class"`). Example "sensor batteries under
  * 20%":
  * `And([Cmp("domain", Eq, "sensor"), Cmp("attr:battery_level", Lt, 20)])`.
  */
sealed trait Predicate derives ConfiguredCodec
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
    params: Map[String, String] = Map.empty,
    slots: Map[String, SlotSource] = Map.empty
) derives ConfiguredCodec

/** A node in the recursive dashboard layout tree. */
sealed trait LayoutNode derives ConfiguredCodec
object LayoutNode:
  /** A node referencing a shared template by name. Both leaves and containers
    * are Components — a container is simply a Component whose template splices
    * its rendered `children` via `{{#children}}{{{html}}}{{/children}}` (e.g.
    * the `fhrow`/`fhcol` templates), so new container kinds are added as
    * templates with no Scala change.
    *
    *   - `params`: static, author-known values (label, entity_id, min/max…),
    *     injected into the template alongside resolved slots. The `id` is NOT
    *     authored — the renderer derives a stable, location-based id and
    *     injects it as the `id` param (see [[pathId]]).
    *   - `entities`: runtime deps (drives the reverse index `entityId -> ids`).
    *   - `slots`: dynamic state bindings.
    *   - `children`: nested nodes, rendered first and exposed to the template
    *     as a `children` list of `{html}` (empty for leaves).
    */
  case class Component(
      card: String,
      params: Map[String, String] = Map.empty,
      entities: List[String] = Nil,
      slots: Map[String, SlotSource] = Map.empty,
      children: List[LayoutNode] = Nil
  ) extends LayoutNode

  /** A runtime-resolved group with per-entity template dispatch.
    *
    *   - `query`: overall membership filter (absent = match all entities).
    *   - `cases`: each matched entity renders with the first case whose `when`
    *     matches (skipped if none). The renderer auto-injects `id`,
    *     `entity_id`, and `label` params per matched entity and rebinds each
    *     case's slot entities to the match. The group's own id is
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
  */
case class Theme(
    tokens: Map[String, String] = Map.empty,
    tokensDark: Map[String, String] = Map.empty,
    stylesheets: List[String] = Nil,
    styles: String = ""
) derives ConfiguredCodec

/** The `dashboard.json` build artifact produced by the jsonnet build phase.
  *
  *   - `cards`: `cardName -> CardDef` (shared, reused library of templates).
  *   - `theme`: all presentation (tokens + stylesheets + CSS); see [[Theme]].
  *   - `card`: the root of the recursive layout tree (itself a card, usually a
  *     container). Component HTML is composed in Scala (see `Renderer`), not
  *     via mustache layout placeholders.
  */
case class Dashboard(
    cards: Map[String, CardDef],
    card: LayoutNode,
    theme: Theme = Theme()
) derives ConfiguredCodec:

  /** Validate that every card reference resolves and supplies the inputs the
    * card's template declares. Returns human-readable errors (empty = valid).
    *
    * `locateTransform` maps a transform expression back to a source location
    * (e.g. `dashboard.jsonnet:42`) for friendlier errors. Jsonnet evaluation
    * erases positions, so the build phase supplies a best-effort locator that
    * greps the jsonnet sources for the literal; the default ignores it (the
    * model stays pure and source-agnostic).
    */
  def validate(
      locateTransform: String => Option[String] = _ => None
  ): List[String] =
    // A required param is satisfied by an author/injected param; a required slot
    // only by a slot (a live value can't come from a static param). `injected`
    // names are backend-supplied: `id` always, plus `entity_id`/`label` per
    // matched entity inside a dynamic case.
    def checkRef(
        nodeId: String,
        cardName: String,
        injected: Set[String],
        paramNames: Set[String],
        slotNames: Set[String]
    ): List[String] =
      cards.get(cardName) match
        case None =>
          List(s"$nodeId: references unknown card '$cardName'")
        case Some(cd) =>
          val missingParams = cd.params.toSet -- injected -- paramNames
          val missingSlots = cd.slots.toSet -- slotNames
          List(
            Option.when(missingParams.nonEmpty)(
              s"$nodeId: card '$cardName' missing params: " +
                missingParams.toList.sorted.mkString(", ")
            ),
            Option.when(missingSlots.nonEmpty)(
              s"$nodeId: card '$cardName' missing slots: " +
                missingSlots.toList.sorted.mkString(", ")
            )
          ).flatten

    // Every slot's value is a `transform`, which must be parseable JSONata.
    def slotErrors(
        nodeId: String,
        slots: Map[String, SlotSource]
    ): List[String] =
      slots.toList.flatMap { case (name, src) =>
        Transform.parse(src.transform).left.toOption.map { err =>
          val at = locateTransform(src.transform).fold("")(loc => s" (at $loc)")
          s"$nodeId: slot '$name' has an invalid transform$at: $err"
        }
      }

    def children(nodes: List[LayoutNode], path: List[Int]): List[String] =
      nodes.zipWithIndex.flatMap { case (n, i) => walk(n, path :+ i) }

    def walk(node: LayoutNode, path: List[Int]): List[String] =
      node match
        case LayoutNode.Component(card, params, _, slots, kids) =>
          val nodeId = LayoutNode.pathId(path)
          checkRef(
            nodeId,
            card,
            Dashboard.injectedStatic,
            params.keySet,
            slots.keySet
          ) ++ slotErrors(nodeId, slots) ++ children(kids, path)
        case LayoutNode.Dynamic(_, cases) =>
          cases.flatMap { c =>
            val nodeId = s"${LayoutNode.pathId(path)}/${c.card}"
            checkRef(
              nodeId,
              c.card,
              Dashboard.injectedDynamic,
              c.params.keySet,
              c.slots.keySet
            ) ++ slotErrors(nodeId, c.slots)
          }

    walk(card, Nil)

object Dashboard:
  /** Backend-injected param names available to a *static* component: only the
    * stable location-based `id`.
    */
  val injectedStatic: Set[String] = Set("id")

  /** Backend-injected param names available inside a *dynamic* case: `id` plus
    * the matched entity's `entity_id` and `label` (friendly name).
    */
  val injectedDynamic: Set[String] = injectedStatic ++ Set("entity_id", "label")
