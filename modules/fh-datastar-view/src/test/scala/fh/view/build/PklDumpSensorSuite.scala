package fh.view.build

import io.circe.Json

/** The `sensor`/`binary_sensor` dispatch: the registry attributes that say what
  * a reading MEANS move onto the domain class, and one of them is allowed to be
  * dropped.
  */
class PklDumpSensorSuite extends munit.FunSuite {

  private def entity(id: String, domain: String, attrs: (String, Json)*): Json =
    Json.obj(
      "entity_id" -> Json.fromString(id),
      "domain" -> Json.fromString(domain),
      "members" -> Json.arr(),
      "attributes" -> Json.obj(attrs*)
    )

  private def render(entities: Json*): String =
    PklDump.render(
      RegistryDump.transform(
        Json.obj(
          "areas" -> Json.arr(),
          "floors" -> Json.arr(),
          "entities" -> Json.fromValues(entities)
        )
      )
    )

  private def s(v: String): Json = Json.fromString(v)

  test("a sensor extends SensorEntity and carries its registry meaning") {
    val src = render(
      entity(
        "sensor.timetoend",
        "sensor",
        "device_class" -> s("duration"),
        "unit_of_measurement" -> s("min"),
        "state_class" -> s("measurement")
      )
    )
    assert(src.contains("extends hass.SensorEntity"), clue = src)
    assert(src.contains("""device_class = "duration""""), clue = src)
    assert(src.contains("""unit_of_measurement = "min""""), clue = src)
    assert(src.contains("""state_class = "measurement""""), clue = src)
  }

  test("an enum sensor's options land on the schema field") {
    val src = render(
      entity(
        "sensor.status",
        "sensor",
        "device_class" -> s("enum"),
        "options" -> Json.arr(s("idle"), s("running"))
      )
    )
    assert(
      src.contains("""options = new Listing { "idle"; "running" }"""),
      clue = src
    )
  }

  test("a binary sensor extends BinarySensorEntity") {
    val src =
      render(
        entity(
          "binary_sensor.door",
          "binary_sensor",
          "device_class" -> s("door")
        )
      )
    assert(src.contains("extends hass.BinarySensorEntity"), clue = src)
    assert(src.contains("""device_class = "door""""), clue = src)
  }

  test("an UNVENDORED device class is dropped, not assigned") {
    // The first-boot case: HA grows `SensorDeviceClass` most releases, and a
    // home running a newer one than this lib was synced against must still
    // evaluate its dashboards. Assigning it would be `Cannot assign` at eval,
    // over a reading no shipped card knows how to render anyway.
    val src = render(
      entity("sensor.x", "sensor", "device_class" -> s("flux_capacitance"))
    )
    assert(!src.contains("""device_class = "flux_capacitance""""), clue = src)
    assert(
      src.contains("flux_capacitance is not in the vendored union"),
      clue = src
    )
  }

  test("a schema-modelled sensor attribute is not ALSO a per-entity property") {
    // Declaring it twice would shadow the typed schema field with an untyped
    // String, and the branch a card writes would compile against the wrong one.
    val src = render(
      entity(
        "sensor.timetoend",
        "sensor",
        "device_class" -> s("duration"),
        "unit_of_measurement" -> s("min")
      )
    )
    assert(!src.contains("device_class: String"), clue = src)
    assert(!src.contains("unit_of_measurement: String"), clue = src)
  }

  test("a dropped device class does not take the rest of the row with it") {
    // The drop is per-attribute: an unknown class must not cost the unit, which
    // is what a countdown actually needs.
    val src = render(
      entity(
        "sensor.x",
        "sensor",
        "device_class" -> s("flux_capacitance"),
        "unit_of_measurement" -> s("min")
      )
    )
    assert(src.contains("""unit_of_measurement = "min""""), clue = src)
  }
}
