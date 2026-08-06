package fh.view.build

import api.homeassistant.ws.domain.{
  Area,
  Device,
  EntitiesEvent,
  Entity as RegistryEntity,
  Floor
}
import ha.runtime.definitions.{DeviceId, EntityId, ReadableEntityId}
import io.circe.Json

/** [[RegistryDump.build]] is the pure join at the heart of the registry dump, so
  * it is exercised directly — no live HA, no WebSocket.
  */
class RegistryDumpSuite extends munit.FunSuite {

  private def full(
      attributes: (String, Json)*
  ): EntitiesEvent.Full =
    EntitiesEvent.Full(state = "on", attributes = attributes.toMap)

  private def registryEntity(
      entityId: String,
      deviceId: Option[String] = None,
      areaId: Option[String] = None,
      hidden: Boolean = false,
      category: Option[String] = None
  ): RegistryEntity =
    RegistryEntity(
      area_id = areaId,
      categories = Json.obj(),
      config_entry_id = None,
      config_subentry_id = None,
      created_at = 0d,
      device_id = deviceId.map(DeviceId.of),
      disabled_by = None,
      entity_category = category,
      entity_id = ReadableEntityId.of(entityId),
      has_entity_name = true,
      hidden_by = Option.when(hidden)(Json.fromString("user")),
      icon = None,
      id = EntityId.of(entityId),
      labels = Nil,
      modified_at = Json.Null,
      name = None,
      options = None,
      original_name = None,
      platform = "test",
      translation_key = None,
      unique_id = entityId
    )

  private def device(id: String, areaId: Option[String]): Device =
    Device(
      area_id = areaId,
      configuration_url = None,
      config_entries = Nil,
      config_entries_subentries = None,
      connections = Nil,
      created_at = 0d,
      disabled_by = None,
      entry_type = None,
      hw_version = None,
      id = DeviceId.of(id),
      identifiers = Nil,
      labels = Nil,
      manufacturer = Some("ACME"),
      model = Some("M1"),
      model_id = None,
      serial_number = None,
      modified_at = Json.Null,
      name_by_user = None,
      name = s"device $id",
      primary_config_entry = None,
      serial_numer = None,
      sw_version = None,
      via_device_id = None
    )

  private val kitchen =
    Area(Nil, "kitchen", 0d, Some("ground"), None, None, Nil, Json.Null, "Kitchen", None, None)
  private val ground =
    Floor(Nil, 0d, "ground", None, Some(1), Json.Null, "Ground")

  /** `build` emits the raw list shape; `RegistryDump.fetch` keys it through
    * [[DataDump.transform]] before anything reads it, so tests look at the same
    * keyed result callers do.
    */
  private def entityOf(dump: Json, key: String): Json =
    DataDump
      .transform(dump)
      .hcursor
      .downField("entities")
      .downField(key)
      .focus
      .getOrElse(Json.Null)

  private def field(dump: Json, key: String, name: String): Json =
    entityOf(dump, key).hcursor.downField(name).focus.getOrElse(Json.Null)

  test("an entity with state but no registry row is kept") {
    val dump = RegistryDump.build(
      states = Map("sun.sun" -> full()),
      registry = Nil,
      devices = Nil,
      areas = Nil,
      floors = Nil
    )
    assertEquals(field(dump, "sun_sun", "entity_id"), Json.fromString("sun.sun"))
    assertEquals(field(dump, "sun_sun", "domain"), Json.fromString("sun"))
  }

  test("a registry row with no state is dropped (disabled entities)") {
    val dump = RegistryDump.build(
      states = Map.empty,
      registry = List(registryEntity("light.disabled")),
      devices = Nil,
      areas = Nil,
      floors = Nil
    )
    assertEquals(entityOf(dump, "light_disabled"), Json.Null)
  }

  test("an entity inherits its device's area, and the area's floor") {
    val dump = RegistryDump.build(
      states = Map("light.a" -> full()),
      registry = List(registryEntity("light.a", deviceId = Some("d1"))),
      devices = List(device("d1", Some("kitchen"))),
      areas = List(kitchen),
      floors = List(ground)
    )
    assertEquals(field(dump, "light_a", "area_id"), Json.fromString("kitchen"))
    assertEquals(field(dump, "light_a", "floor_id"), Json.fromString("ground"))
    assertEquals(field(dump, "light_a", "device_id"), Json.fromString("d1"))
  }

  test("an entity's own area overrides its device's") {
    val dump = RegistryDump.build(
      states = Map("light.a" -> full()),
      registry = List(
        registryEntity("light.a", deviceId = Some("d1"), areaId = Some("attic"))
      ),
      devices = List(device("d1", Some("kitchen"))),
      areas = List(kitchen),
      floors = List(ground)
    )
    assertEquals(field(dump, "light_a", "area_id"), Json.fromString("attic"))
  }

  test("hidden_by and entity_category come off the registry") {
    val dump = RegistryDump.build(
      states = Map("switch.a" -> full(), "switch.b" -> full()),
      registry = List(
        registryEntity("switch.a", hidden = true, category = Some("config")),
        registryEntity("switch.b")
      ),
      devices = Nil,
      areas = Nil,
      floors = Nil
    )
    assertEquals(field(dump, "switch_a", "id_hidden"), Json.True)
    assertEquals(field(dump, "switch_a", "entity_category"), Json.fromString("config"))
    assertEquals(field(dump, "switch_b", "id_hidden"), Json.False)
    assertEquals(field(dump, "switch_b", "entity_category"), Json.Null)
  }

  test("members come from either group attribute") {
    val lightGroup =
      full("entity_id" -> Json.arr(Json.fromString("light.x")))
    val zigbeeGroup =
      full("group_entities" -> Json.arr(Json.fromString("light.y")))
    val dump = RegistryDump.build(
      states = Map("light.g1" -> lightGroup, "light.g2" -> zigbeeGroup),
      registry = Nil,
      devices = Nil,
      areas = Nil,
      floors = Nil
    )
    assertEquals(
      field(dump, "light_g1", "members"),
      Json.arr(Json.fromString("light.x"))
    )
    assertEquals(
      field(dump, "light_g2", "members"),
      Json.arr(Json.fromString("light.y"))
    )
  }

  test("only capability attributes survive; live values are dropped") {
    val dump = RegistryDump.build(
      states = Map(
        "light.a" -> full(
          "supported_color_modes" -> Json.arr(Json.fromString("xy")),
          "brightness" -> Json.fromInt(200),
          "color_temp_kelvin" -> Json.fromInt(3000),
          "rgb_color" -> Json.arr(Json.fromInt(1))
        )
      ),
      registry = Nil,
      devices = Nil,
      areas = Nil,
      floors = Nil
    )
    val attrs = entityOf(dump, "light_a").hcursor
      .downField("attributes")
      .focus
      .flatMap(_.asObject)
      .map(_.keys.toSet)
      .getOrElse(Set.empty)
    assertEquals(attrs, Set("supported_color_modes"))
  }

  test("devices are keyed by slug, and a repeated name is suffixed") {
    val dump = RegistryDump.build(
      states = Map.empty,
      registry = Nil,
      devices = List(
        device("d1", None).copy(name = "Hue bulb"),
        device("d2", None).copy(name = "Hue bulb")
      ),
      areas = Nil,
      floors = Nil
    )
    val keys = dump.hcursor
      .downField("devices")
      .focus
      .flatMap(_.asObject)
      .map(_.keys.toList)
      .getOrElse(Nil)
    assertEquals(keys, List("hue_bulb", "hue_bulb_2"))
  }
}
