package fh.view.model

import io.circe.{Decoder, Encoder}
import io.circe.derivation.{Configuration, ConfiguredCodec}

/** Where a single mustache slot pulls its value from at runtime.
  *
  * `attribute = None` means the entity's `state`; otherwise the named attribute
  * (e.g. `unit_of_measurement`, `brightness`).
  */
given Configuration = Configuration.default.withDefaults

case class SlotSource(
    entity: String,
    attribute: Option[String] = None,
    // Used when the entity/attribute is absent or empty (e.g. brightness when a
    // light is off). Keeps numeric signal initialisers like `{bri: {{x}}}` valid.
    default: Option[String] = None
) derives ConfiguredCodec

/** A component instance's runtime dependencies.
  *
  *   - `entities`: which entities this component re-renders for (drives the
  *     reverse index `entityId -> Set[componentId]`).
  *   - `slots`: how each mustache slot in the component's template is filled
  *     from live state.
  */
case class ComponentDef(
    entities: List[String],
    slots: Map[String, SlotSource]
) derives ConfiguredCodec

/** The `dashboard.json` build artifact produced by the jsonnet build phase.
  *
  *   - `templates`: `componentId -> mustache template string`. Each template's
  *     root element carries `id="<componentId>"` so Datastar can patch it.
  *   - `registry`: `componentId -> ComponentDef` (entity deps + slot mapping).
  *   - `layout`: the page shell. Component HTML is included unescaped via
  *     `{{{componentId}}}` placeholders.
  */
case class Dashboard(
    templates: Map[String, String],
    registry: Map[String, ComponentDef],
    layout: String
) derives ConfiguredCodec
