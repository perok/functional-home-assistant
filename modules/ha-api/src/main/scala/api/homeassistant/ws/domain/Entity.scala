package api.homeassistant.ws.domain

import io.circe.{Codec, Decoder, Json, Encoder}

opaque type DeviceId = String
object DeviceId {
  def of(in: String): DeviceId = in
  given Codec[DeviceId] = Codec.from(Decoder[String], Encoder[String])
}

opaque type EntityId = String

object EntityId {
  given Codec[EntityId] = Codec.from(Decoder[String], Encoder[String])
}

// platform, device_id, entity_id
// has_entity_name, name
// original_name
case class Entity(
    area_id: Option[String],
    categories: Json,
    config_entry_id: Option[String],
    created_at: Double,
    device_id: Option[DeviceId],
    disabled_by: Option[String],
    entity_category: Option[String],
    entity_id: String,
    has_entity_name: Boolean,
    hidden_by: Option[Json],
    icon: Option[Json],
    id: EntityId,
    labels: List[Json],
    modified_at: Json,
    name: Option[Json],
    options: Option[Json],
    original_name: Option[String],
    platform: String,
    translation_key: Option[String],
    unique_id: String
) {
  lazy val domain: String = entity_id.split('.')(0)
}

object Entity {
  given Decoder[Entity] = Helper.derived
}
case class Device(
    area_id: Option[String],
    configuration_url: Option[String],
    config_entries: List[String],
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
    name: Option[Json],
    primary_config_entry: Option[String],
    serial_numer: Option[String],
    sw_version: Option[String],
    via_device_id: Option[String]
)

object Device {
  given Decoder[Device] = Helper.derived
}

case class DeviceTrigger(
    platform: "device",
    `type`: String,
    device_id: Option[DeviceId],
    entity_id: Option[EntityId],
    domain: String,
    subtype: Option[String],
    metadata: Json
) derives Encoder

object DeviceTrigger {
  given Decoder[DeviceTrigger] = Helper.derived
}
