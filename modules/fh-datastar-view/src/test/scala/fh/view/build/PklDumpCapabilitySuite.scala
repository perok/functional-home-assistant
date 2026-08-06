package fh.view.build

import io.circe.Json

/** Capability GROUPS: a co-occurring set of values becomes one nullable object,
  * so the group's presence is the predicate and one guard covers every value in
  * it. The generator's job is to emit a group only when it is COMPLETE — Pkl
  * would not catch a half-filled one until someone read the missing field.
  */
class PklDumpCapabilitySuite extends munit.FunSuite {

  private def light(attrs: (String, Json)*): Json =
    Json.obj(
      "entity_id" -> Json.fromString("light.a"),
      "domain" -> Json.fromString("light"),
      "members" -> Json.arr(),
      "attributes" -> Json.obj(attrs*)
    )

  private def dump(entities: Json*): Json =
    DataDump.transform(
      Json.obj(
        "areas" -> Json.arr(),
        "floors" -> Json.arr(),
        "entities" -> Json.fromValues(entities)
      )
    )

  private val kelvinRange = List(
    "min_color_temp_kelvin" -> Json.fromInt(2000),
    "max_color_temp_kelvin" -> Json.fromInt(6535)
  )

  test("a complete colour-temperature range becomes one group assignment") {
    val src = PklDump.render(dump(light(kelvinRange*)))
    assert(
      src.contains(
        "colourTemp = new hass.ColourTemp { min_kelvin = 2000; max_kelvin = 6535 }"
      ),
      clue = src
    )
  }

  test("a HALF-reported range emits no group, and warns") {
    val half = dump(light("min_color_temp_kelvin" -> Json.fromInt(2000)))
    val src = PklDump.render(half)
    assert(!src.contains("colourTemp ="), clue = src)
    val warns = PklDump.warnings(half)
    assert(warns.exists(_.contains("half-reported")), clue = warns)
  }

  test("claiming color_temp with no range warns") {
    val claimed = dump(
      light(
        "supported_color_modes" -> Json.arr(Json.fromString("color_temp"))
      )
    )
    assert(
      PklDump.warnings(claimed).exists(_.contains("no kelvin range")),
      clue = PklDump.warnings(claimed)
    )
  }

  test("a consistent light produces no warnings") {
    val fine = dump(
      light(
        (("supported_color_modes" -> Json.arr(Json.fromString("color_temp"))) ::
          kelvinRange)*
      )
    )
    assertEquals(PklDump.warnings(fine), Nil)
  }

  test("effect_list becomes the Effects group") {
    val src = PklDump.render(
      dump(light("effect_list" -> Json.arr(Json.fromString("colorloop"))))
    )
    assert(
      src.contains(
        "effects = new hass.Effects { list = new Listing { \"colorloop\" } }"
      ),
      clue = src
    )
  }

  test("schema-modelled attributes are not ALSO declared on the entity class") {
    val src = PklDump.render(
      dump(
        light(
          (("supported_color_modes" -> Json.arr(Json.fromString("xy"))) ::
            ("icon" -> Json.fromString("mdi:bulb")) :: kelvinRange)*
        )
      )
    )
    // the light schema owns these — they must appear once, as assignments
    assert(!src.contains("min_color_temp_kelvin:"), clue = src)
    assert(!src.contains("supported_color_modes:"), clue = src)
    assert(src.contains("colourModes = new Listing { \"xy\" }"), clue = src)
    // ...while an UNMODELLED attribute still falls through to the class
    assert(src.contains("icon: String = \"mdi:bulb\""), clue = src)
  }

  test("a non-light domain is untouched by the light schema") {
    val sensor = Json.obj(
      "entity_id" -> Json.fromString("sensor.a"),
      "domain" -> Json.fromString("sensor"),
      "members" -> Json.arr(),
      "attributes" -> Json.obj("device_class" -> Json.fromString("power"))
    )
    val src = PklDump.render(dump(sensor))
    assert(src.contains("device_class: String = \"power\""), clue = src)
    assert(!src.contains("colourModes"), clue = src)
  }
}
