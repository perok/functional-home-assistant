package fh.view.build

import io.circe.Json

/** The generated-source half of the group work: a member edge must come out as
  * a REFERENCE to the other entity's `e_*` const, because that is what makes
  * `e.members[0].members` walk a nested group instead of handing the author a
  * string to look up again.
  */
class PklDumpMembersSuite extends munit.FunSuite {

  private def entity(
      entityId: String,
      domain: String,
      members: List[String] = Nil,
      deviceId: Option[String] = None
  ): Json =
    Json.obj(
      "entity_id" -> Json.fromString(entityId),
      "domain" -> Json.fromString(domain),
      "members" -> Json.fromValues(members.map(Json.fromString)),
      "device_id" -> deviceId.fold(Json.Null)(Json.fromString),
      "attributes" -> Json.obj()
    )

  private def dump(entities: Json*): Json =
    RegistryDump.transform(
      Json.obj(
        "areas" -> Json.arr(),
        "floors" -> Json.arr(),
        "entities" -> Json.fromValues(entities)
      )
    )

  test("members render as references to the member entities' consts") {
    val src = PklDump.render(
      dump(
        entity("light.group", "light", members = List("light.a", "light.b")),
        entity("light.a", "light"),
        entity("light.b", "light")
      )
    )
    assert(src.contains("members = List(e_light_a, e_light_b)"), clue = src)
  }

  test("a member that is not in the dump is dropped, not left dangling") {
    val src = PklDump.render(
      dump(
        entity("light.group", "light", members = List("light.a", "light.gone")),
        entity("light.a", "light")
      )
    )
    assert(src.contains("members = List(e_light_a)"), clue = src)
    assert(!src.contains("e_light_gone"), clue = src)
  }

  test("an entity with no members emits no members assignment") {
    val src = PklDump.render(dump(entity("light.a", "light")))
    assert(!src.contains("members ="), clue = src)
  }

  test("number and select entities get their own typed classes") {
    val src = PklDump.render(
      dump(entity("number.a", "number"), entity("select.b", "select"))
    )
    assert(
      src.contains("class E_number_a extends hass.NumberEntity"),
      clue = src
    )
    assert(
      src.contains("class E_select_b extends hass.SelectEntity"),
      clue = src
    )
    assert(src.contains("const hidden e_number_a: E_number_a"), clue = src)
    assert(src.contains("const hidden e_select_b: E_select_b"), clue = src)
  }

  test("no devices in the dump means no Devices namespace at all") {
    val src = PklDump.render(dump(entity("light.a", "light")))
    assert(!src.contains("class Devices"), clue = src)
    assert(!src.contains("devices: Devices"), clue = src)
  }

  test("a device class references the entities that report it") {
    val transformed = dump(
      entity("light.a", "light", deviceId = Some("d1")),
      entity("sensor.b", "sensor", deviceId = Some("d1")),
      entity("light.c", "light", deviceId = Some("d2"))
    ).deepMerge(
      Json.obj(
        "devices" -> Json.obj(
          "bulb" -> Json.obj(
            "device_id" -> Json.fromString("d1"),
            "device_name" -> Json.fromString("Bulb")
          )
        )
      )
    )
    val src = PklDump.render(transformed)
    assert(src.contains("class Device_bulb extends hass.Device"), clue = src)
    assert(src.contains("entities = List(light_a, sensor_b)"), clue = src)
    assert(
      !src.contains("light_c: hass.LightEntity = e_light_c\n  entities"),
      clue = src
    )
  }
}
