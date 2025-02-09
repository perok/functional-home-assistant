package api.homeassistant.ws.domain

import fh.codegen.utils.{StaticCode, ToCode}
import fh.domain.utils.Helper
import ha.runtime.definitions.*
import io.circe.{Codec, Decoder, Encoder, Json}

given ToCode[Json] = in => "io.circe.Json.obj()" // TODO
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
) extends IsEntity derives StaticCode {
  def bestName: String = name
    .flatMap(_.asString)
    .orElse(original_name)
    .getOrElse(EntityId.toString(id))
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
    name: String,
    primary_config_entry: Option[String],
    serial_numer: Option[String],
    sw_version: Option[String],
    via_device_id: Option[String]
) extends IsDevice derives StaticCode

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
) extends IsDeviceTrigger derives Encoder, StaticCode

object DeviceTrigger {
  import io.scalaland.chimney.dsl._

  given Decoder[DeviceTrigger] = Helper.derived
  given Conversion[IsDeviceTrigger, DeviceTrigger] = trigger =>
    trigger
      .into[DeviceTrigger]
      .enableInheritedAccessors
      .enableMethodAccessors
      .transform

}
