package fh.view.model

import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.derivation.{Configuration, ConfiguredCodec}

/** Where a single mustache slot pulls its value from at runtime.
  *
  * `attribute = None` means the entity's `state`; otherwise the named attribute
  * (e.g. `unit_of_measurement`, `brightness`).
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
    default: Option[String] = None
) derives ConfiguredCodec

/** A reusable template in the shared library.
  *
  *   - `template`: a Mustache string. Escaped `{{slot}}` values are HTML-safe;
  *     raw author values (action URLs, ids) use `{{{...}}}`.
  *   - `inputs`: the param/slot names the template references — used by
  *     [[Dashboard.validate]] to check every instance supplies them.
  */
case class TemplateDef(
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
    template: String,
    params: Map[String, String] = Map.empty,
    slots: Map[String, SlotSource] = Map.empty
) derives ConfiguredCodec

/** A node in the recursive dashboard layout tree. */
sealed trait LayoutNode derives ConfiguredCodec
object LayoutNode:
  /** Horizontal container. */
  case class Row(children: List[LayoutNode]) extends LayoutNode

  /** Vertical container. */
  case class Column(children: List[LayoutNode]) extends LayoutNode

  /** A leaf instance referencing a shared template by name.
    *
    *   - `params`: static, author-known values (id, label, entity, signal
    *     names…), injected into the template alongside resolved slots.
    *   - `entities`: runtime deps (drives the reverse index `entityId -> ids`).
    *   - `slots`: dynamic state bindings.
    */
  case class Component(
      id: String,
      template: String,
      params: Map[String, String] = Map.empty,
      entities: List[String] = Nil,
      slots: Map[String, SlotSource] = Map.empty
  ) extends LayoutNode

  /** A runtime-resolved group with per-entity template dispatch.
    *
    *   - `query`: overall membership filter (absent = match all entities).
    *   - `cases`: each matched entity renders with the first case whose `when`
    *     matches (skipped if none). The renderer auto-injects `id`, `entity`,
    *     and `label` params per matched entity and rebinds each case's slot
    *     entities to the match.
    */
  case class Dynamic(
      id: String,
      query: Option[Predicate] = None,
      cases: List[DynamicCase] = Nil
  ) extends LayoutNode

/** The `dashboard.json` build artifact produced by the jsonnet build phase.
  *
  *   - `templates`: `templateName -> TemplateDef` (shared, reused library).
  *   - `layout`: the recursive layout tree. Component HTML is composed in Scala
  *     (see `Renderer`), not via mustache layout placeholders.
  */
case class Dashboard(
    templates: Map[String, TemplateDef],
    layout: LayoutNode
) derives ConfiguredCodec:

  /** Validate that every template reference resolves and supplies the inputs
    * the template declares. Returns human-readable errors (empty = valid).
    */
  def validate: List[String] =
    def checkRef(
        nodeId: String,
        templateName: String,
        available: Set[String]
    ): List[String] =
      templates.get(templateName) match
        case None =>
          List(s"$nodeId: references unknown template '$templateName'")
        case Some(td) =>
          val missing = td.inputs.toSet -- available
          if missing.isEmpty then Nil
          else
            List(
              s"$nodeId: template '$templateName' missing inputs: " +
                missing.toList.sorted.mkString(", ")
            )

    def walk(node: LayoutNode): List[String] =
      node match
        case LayoutNode.Row(children)    => children.flatMap(walk)
        case LayoutNode.Column(children) => children.flatMap(walk)
        case LayoutNode.Component(id, template, params, _, slots) =>
          checkRef(id, template, params.keySet ++ slots.keySet)
        case LayoutNode.Dynamic(id, _, cases) =>
          cases.flatMap { c =>
            checkRef(
              s"$id/${c.template}",
              c.template,
              Set("id", "entity", "label") ++ c.params.keySet ++ c.slots.keySet
            )
          }

    walk(layout)
