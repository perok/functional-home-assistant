package api.homeassistant.ws

import api.homeassistant.ws.domain.Entity
import io.circe.Json
import io.circe.parser.decode

/** `config/entity_registry/list` entries, keyed as HA core's
  * `RegistryEntry.as_partial_dict` writes them: 2026.11 dropped
  * `has_entity_name`, and a decoder requiring it failed the whole startup.
  */
class RegistryEntitySuite extends munit.FunSuite {

  private val since2026_11 = Json.obj(
    "area_id" -> Json.Null,
    "categories" -> Json.obj(),
    "config_entry_id" -> Json.fromString("01JAAAAAAAAAAAAAAAAAAAAAAA"),
    "config_subentry_id" -> Json.Null,
    "created_at" -> Json.fromDoubleOrNull(1.7e9),
    "device_id" -> Json.fromString("dev1"),
    "disabled_by" -> Json.Null,
    "entity_category" -> Json.Null,
    "entity_id" -> Json.fromString("light.kitchen"),
    "hidden_by" -> Json.Null,
    "icon" -> Json.Null,
    "id" -> Json.fromString("abc123"),
    "labels" -> Json.arr(),
    "modified_at" -> Json.fromDoubleOrNull(1.7e9),
    "name" -> Json.Null,
    "next_name_part" -> Json.fromString("device"),
    "options" -> Json.obj(),
    "original_name" -> Json.fromString("Ceiling"),
    "platform" -> Json.fromString("hue"),
    "translation_key" -> Json.Null,
    "unique_id" -> Json.fromString("u1")
  )

  test("an entry from HA 2026.11, without has_entity_name, decodes") {
    val e = decode[Entity](since2026_11.noSpaces)
      .fold(err => fail(err.toString), identity)
    assertEquals(e.bestName, "Ceiling")
  }

  test("an entry from HA 2026.10, with has_entity_name, still decodes") {
    val older = since2026_11.deepMerge(Json.obj("has_entity_name" -> Json.True))
    assert(decode[Entity](older.noSpaces).isRight)
  }
}
