package ha.runtime.definitions

import io.circe.Json
// TODO chimney for conversion https://chimney.readthedocs.io/en/stable/supported-transformations/?h=method#reading-from-methods
// https://scastie.scala-lang.org/v3puHEM6RYSMYrEib4WTFQ

// We have Is* here
// Every api allows only these
// Internally we use chimney to convert back to internal versions

trait IsManifest {
  def domain: ManifestDomain
  def name: String
  def integration_type: Option[String]
}
trait IsConfigEntry {
  def entry_id: EntryId
  def domain: ManifestDomain
  def title: String
  def source: String
  def state: String
  def supported_options: Option[String]
  def disabled_by: Option[String]
}

// platform device_id entity_id
// has_entity_name name
// original_name
// TODO type up entity value type
trait IsEntity {
  def area_id: Option[String]
  def categories: Json
  def config_entry_id: Option[String]
  def created_at: Double
  def device_id: Option[DeviceId]
  def disabled_by: Option[String]
  def entity_category: Option[String]
  def entity_id: ReadableEntityId
  def has_entity_name: Boolean
  def hidden_by: Option[Json]
  def icon: Option[Json]
  def id: EntityId
  def labels: List[Json]
  def modified_at: Json
  def name: Option[String]
  def options: Option[Json]
  def original_name: Option[String]
  def platform: String
  def translation_key: Option[String]
  def unique_id: String
  lazy val domain: String = ReadableEntityId.domain(entity_id)
}

trait IsDevice {
  def area_id: Option[String]
  def configuration_url: Option[String]
  def config_entries: List[EntryId]
  def connections: List[List[String]]
  def created_at: Double
  def disabled_by: Option[String]
  def entry_type: Option[String]
  def hw_version: Option[String]
  def id: DeviceId
  def identifiers: List[List[String]]
  def labels: List[Json]
  def manufacturer: Option[String]
  def model: Option[String]
  def model_id: Option[String]
  def serial_number: Option[String]
  def modified_at: Json
  def name_by_user: Option[String]
  def name: String
  def primary_config_entry: Option[EntryId]
  def serial_numer: Option[String]
  def sw_version: Option[String]
  def via_device_id: Option[String]
}

trait IsDeviceTrigger {
  def platform: "device"
  def `type`: String
  def device_id: Option[DeviceId]
  def entity_id: Option[EntityId]
  def domain: String
  def subtype: Option[String]
  def metadata: Json
}
