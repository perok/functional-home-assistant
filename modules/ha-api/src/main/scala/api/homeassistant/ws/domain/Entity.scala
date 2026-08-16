package api.homeassistant.ws.domain

import fh.codegen.utils.{StaticCode, ToCode}
import fh.domain.utils.DecoderWithWarnMissing
import ha.runtime.definitions.*
import io.circe.{Codec, Decoder, Encoder, Json}
// TODO make these into package api.homeassistant.ws private?

// TODO neotypes? https://github.com/kitlangton/neotype/blob/main/modules/neotype-circe/shared/src/main/scala/neotype/interop/circe/Main.scala

case class Manifest(
    domain: ManifestDomain,
    name: String,
    integration_type: Option[String]
) extends IsManifest derives Encoder, Decoder, StaticCode

case class ConfigEntry(
    entry_id: EntryId,
    domain: ManifestDomain,
    title: String,
    source: String,
    state: String, // loaded not_loaded
    supported_options: Option[String],
    disabled_by: Option[String]
) extends IsConfigEntry derives Encoder, Decoder, StaticCode

given ToCode[Json] = in =>
  s"io.circe.Json.obj(${
      if in != Json.obj() then s"/*${in.spaces4}*/" else ""
    })" // TODO

// platform, device_id, entity_id
// has_entity_name, name
// original_name
case class Entity(
    area_id: Option[String],
    categories: Json,
    config_entry_id: Option[String], // TODO entryid?
    config_subentry_id: Option[String],
    created_at: Double,
    device_id: Option[DeviceId],
    disabled_by: Option[String],
    entity_category: Option[String],
    entity_id: ReadableEntityId,
    has_entity_name: Boolean,
    hidden_by: Option[Json],
    icon: Option[Json],
    id: EntityId,
    labels: List[Json],
    modified_at: Json,
    name: Option[String],
    options: Option[Json],
    original_name: Option[String],
    platform: String,
    translation_key: Option[String],
    unique_id: String
) extends IsEntity derives StaticCode {
  def bestName: String = name
    .orElse(original_name)
    .getOrElse(ReadableEntityId.toString(entity_id))
}

object Entity {
  given Decoder[Entity] = DecoderWithWarnMissing.derived
}
case class Device(
    area_id: Option[String],
    configuration_url: Option[String],
    config_entries: List[EntryId],
    config_entries_subentries: Option[Json],
    // HA 2026.8 tied a device to a single config entry: the registry list now
    // also carries these two keys (deprecating `config_entries` /
    // `primary_config_entry`). `Option` so a pre-2026.8 HA — which lacks the
    // keys entirely — still decodes.
    config_entry_id: Option[EntryId],
    config_subentry_id: Option[String],
    connections: List[List[String]],
    created_at: Double,
    disabled_by: Option[String],
    entry_type: Option[String],
    hw_version: Option[String],
    id: DeviceId,
    identifiers: List[List[String]],
    labels: List[Json],
    manufacturer: Option[String],
    model: Option[String],
    model_id: Option[String],
    serial_number: Option[String],
    modified_at: Json,
    name_by_user: Option[String],
    name: String,
    primary_config_entry: Option[EntryId],
    serial_numer: Option[String],
    sw_version: Option[String],
    via_device_id: Option[String]
) extends IsDevice derives StaticCode

object Device {
  given Decoder[Device] = DecoderWithWarnMissing.derived
}

/** `config/area_registry/list`. The authoritative area list — unlike the Jinja
  * `areas()`/`area_name()` pair it carries `floor_id` directly, so the
  * area->floor edge needs no second lookup.
  */
case class Area(
    aliases: List[String],
    area_id: String,
    created_at: Double,
    floor_id: Option[String],
    humidity_entity_id: Option[String],
    icon: Option[String],
    labels: List[String],
    modified_at: Json,
    name: String,
    picture: Option[String],
    temperature_entity_id: Option[String]
)

object Area {
  given Decoder[Area] = DecoderWithWarnMissing.derived
}

/** `config/floor_registry/list`. `level` orders floors vertically (basement is
  * negative), which the Jinja `floors()` list does not expose at all.
  */
case class Floor(
    aliases: List[String],
    created_at: Double,
    floor_id: String,
    icon: Option[String],
    level: Option[Int],
    modified_at: Json,
    name: String
)

object Floor {
  given Decoder[Floor] = DecoderWithWarnMissing.derived
}

case class DeviceTrigger(
    platform: "device",
    `type`: String,
    device_id: Option[DeviceId],
    entity_id: Option[EntityId],
    domain: String,
    subtype: Option[String],
    metadata: Json
) extends IsDeviceTrigger derives Encoder, StaticCode

object DeviceTrigger {
  import io.scalaland.chimney.dsl._

  given Decoder[DeviceTrigger] = DecoderWithWarnMissing.derived
  given Conversion[IsDeviceTrigger, DeviceTrigger] = trigger =>
    trigger
      .into[DeviceTrigger]
      .enableMethodAccessors
      .transform

}
