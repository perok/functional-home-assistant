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
    // HA 2026.8 (deprecating `config_entries`/`primary_config_entry`); older
    // HA lacks the keys.
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
    // HA 2026.9: one outlet of a power strip, one level deep. Not
    // `via_device_id`, which is "reached through", not "part of".
    parent_device_id: Option[DeviceId],
    via_device_id: Option[String]
) extends IsDevice derives StaticCode

object Device {
  given Decoder[Device] = DecoderWithWarnMissing.derived
}

/** Unlike Jinja's `areas()`, carries `floor_id`. */
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

/** `level` orders floors (a basement is negative); Jinja's `floors()` lacks it.
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

/** The whole identity and role source for dashboard access (issue #89). Not
  * [[DecoderWithWarnMissing]]: it warns and carries on, so a missing `is_admin`
  * would fail open.
  */
case class HaUser(
    id: String,
    name: String,
    is_admin: Boolean,
    is_owner: Boolean
) derives Decoder,
      Encoder.AsObject,
      CanEqual

/** Not [[HaUser]]: `config/auth/list` has no `is_admin`, the role is
  * `system-admin` group membership (verified against HA 2026.8.2).
  * `system_generated` accounts (Supervisor, Cast) are not people, and one of
  * them is an admin.
  */
case class HaAccount(
    id: String,
    name: String,
    group_ids: List[String],
    system_generated: Boolean,
    is_active: Boolean,
    is_owner: Boolean
) derives Decoder,
      CanEqual {

  def isAdmin: Boolean = group_ids.contains(HaAccount.AdminGroup)

  def isPerson: Boolean = !system_generated && is_active
}

object HaAccount {
  val AdminGroup: String = "system-admin"
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
