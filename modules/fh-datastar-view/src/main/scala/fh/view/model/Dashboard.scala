package fh.view.model

import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.derivation.{Configuration, ConfiguredCodec}

/** Where a single mustache slot pulls its value from at runtime.
  *
  * `attribute = None` means the entity's `state`; otherwise the named attribute
  * (e.g. `unit_of_measurement`, `brightness`).
  *
  * `transform` is an optional [[Transform]] JSONata expression applied to the
  * resolved value before display (e.g. `$round($number($), 1) & " kW"`). It
  * runs on present values only — an absent value falls back to `default`,
  * untransformed.
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
    attribute: Option[String] = None,
    // Used when the entity/attribute is absent or empty (e.g. brightness when a
    // light is off). Keeps numeric signal initialisers like `{bri: {{x}}}` valid.
    default: Option[String] = None,
    // A `Transform` JSONata expression applied to the resolved value, compiled
    // at build time (validated below) and reused by the renderer.
    transform: Option[String] = None
) derives ConfiguredCodec

/** A reusable card in the shared library (a node references one by name).
  *
  *   - `template`: a Mustache string. Escaped `{{slot}}` values are HTML-safe;
  *     raw author values (action URLs, ids) use `{{{...}}}`.
  *   - `inputs`: the param/slot names the template references — used by
  *     [[Dashboard.validate]] to check every instance supplies them.
  */
case class CardDef(
    template: String,
    inputs: List[String] = Nil
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
    *   - `params`: static, author-known values (label, entity, service…),
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
    *     matches (skipped if none). The renderer auto-injects `id`, `entity`,
    *     and `label` params per matched entity and rebinds each case's slot
    *     entities to the match. The group's own id is location-derived.
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
    */
  def validate: List[String] =
    def checkRef(
        nodeId: String,
        cardName: String,
        available: Set[String]
    ): List[String] =
      cards.get(cardName) match
        case None =>
          List(s"$nodeId: references unknown card '$cardName'")
        case Some(cd) =>
          val missing = cd.inputs.toSet -- available
          if missing.isEmpty then Nil
          else
            List(
              s"$nodeId: card '$cardName' missing inputs: " +
                missing.toList.sorted.mkString(", ")
            )

    // Each slot's optional `transform` must be a parseable pipe pipeline.
    def slotErrors(
        nodeId: String,
        slots: Map[String, SlotSource]
    ): List[String] =
      slots.toList.flatMap { case (name, src) =>
        src.transform.flatMap(t => Transform.parse(t).left.toOption).map {
          err =>
            s"$nodeId: slot '$name' has an invalid transform: $err"
        }
      }

    def children(nodes: List[LayoutNode], path: List[Int]): List[String] =
      nodes.zipWithIndex.flatMap { case (n, i) => walk(n, path :+ i) }

    def walk(node: LayoutNode, path: List[Int]): List[String] =
      node match
        case LayoutNode.Component(card, params, _, slots, kids) =>
          // `id` is always backend-injected, so it counts as available.
          checkRef(
            LayoutNode.pathId(path),
            card,
            Set("id") ++ params.keySet ++ slots.keySet
          ) ++ slotErrors(LayoutNode.pathId(path), slots) ++
            children(kids, path)
        case LayoutNode.Dynamic(_, cases) =>
          cases.flatMap { c =>
            val nodeId = s"${LayoutNode.pathId(path)}/${c.card}"
            checkRef(
              nodeId,
              c.card,
              Set("id", "entity", "label") ++ c.params.keySet ++ c.slots.keySet
            ) ++ slotErrors(nodeId, c.slots)
          }

    walk(card, Nil)
