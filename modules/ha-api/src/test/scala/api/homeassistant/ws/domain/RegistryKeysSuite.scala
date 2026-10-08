package api.homeassistant.ws.domain

import scala.compiletime.constValueTuple
import scala.deriving.Mirror

/** A key HA sends that the case class lacks is not a decode failure: it is
  * one `<Class> missing: …` line per registry row on every fetch, which is how
  * HA 2026.10's `next_name_part` surfaced. The key lists are HA's own
  * serializers, copied from `device_registry.py` (`DeviceEntry.dict_repr`) and
  * `entity_registry.py` (`RegistryEntry.as_partial_dict`) on 2026.10.
  */
class RegistryKeysSuite extends munit.FunSuite:

  private inline def fieldsOf[A](using m: Mirror.ProductOf[A]): Set[String] =
    constValueTuple[m.MirroredElemLabels].toList.map(_.toString).toSet

  test("every key HA 2026.10 sends for a device is a field of Device") {
    val sent = Set(
      "area_id", "configuration_url", "config_entries",
      "config_entries_subentries", "config_entry_id", "config_subentry_id",
      "connections", "created_at", "disabled_by", "entry_type", "hw_version",
      "id", "identifiers", "labels", "manufacturer", "model", "model_id",
      "modified_at", "name_by_user", "name", "next_name_part",
      "parent_device_id", "primary_config_entry", "serial_number",
      "sw_version", "via_device_id"
    )
    assertEquals(sent -- fieldsOf[Device], Set.empty[String])
  }

  test("every key HA 2026.10 sends for an entity is a field of Entity") {
    val sent = Set(
      "area_id", "categories", "config_entry_id", "config_subentry_id",
      "created_at", "device_id", "disabled_by", "entity_category", "entity_id",
      "has_entity_name", "hidden_by", "icon", "id", "labels", "modified_at",
      "name", "next_name_part", "options", "original_name", "platform",
      "translation_key", "unique_id"
    )
    assertEquals(sent -- fieldsOf[Entity], Set.empty[String])
  }
