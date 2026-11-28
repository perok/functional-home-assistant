package fh.view.runtime

import fh.view.build.RegistryDump
import fh.view.testkit.FixtureEntity
import io.circe.Json

/** A synthetic house with the maintainer's SHAPE and none of its names: 1116
  * entities in the same per-domain counts, three floors of 5/6/5 areas, about
  * 58% of entities in an area, the same mix of light colour modes, and 22
  * battery sensors of which five are low. Evening-like: a third of the lights
  * and a quarter of the switches on.
  */
object HouseShape {

  private val DomainCounts: List[(String, Int)] = List(
    "sensor" -> 484,
    "update" -> 127,
    "binary_sensor" -> 88,
    "button" -> 80,
    "number" -> 65,
    "switch" -> 62,
    "select" -> 53,
    "light" -> 48,
    "automation" -> 29,
    "device_tracker" -> 19,
    "calendar" -> 17,
    "image" -> 8,
    "person" -> 4,
    "notify" -> 4,
    "media_player" -> 4,
    "event" -> 4,
    "climate" -> 4,
    "fan" -> 3,
    "zone" -> 2,
    "time" -> 2,
    "scene" -> 2,
    "weather" -> 1,
    "vacuum" -> 1,
    "sun" -> 1,
    "lock" -> 1,
    "lawn_mower" -> 1,
    "conversation" -> 1,
    "camera" -> 1
  )

  private case class Floor(id: String, level: Int, rooms: List[String]) {
    def areas: List[String] = rooms.map(r => s"${id}_$r")
  }

  private val Floors = List(
    Floor("basement", 0, List("hall", "workshop", "den", "utility", "cellar")),
    Floor(
      "ground",
      1,
      List("hall", "guest_room", "mudroom", "bedroom", "porch", "kids_room")
    ),
    Floor("upper", 2, List("library", "hall", "kitchen", "living", "terrace"))
  )

  private val Areas: Vector[(String, String)] =
    Floors.flatMap(f => f.areas.map(_ -> f.id)).toVector

  private def s(v: String): Json = Json.fromString(v)
  private def n(v: Int): Json = Json.fromInt(v)

  /** 28 colour-temperature + xy, 8 colour-temperature, 8 brightness, 3 on/off
    * and 1 rgb, as the house's lights are.
    */
  private def lightModes(i: Int): List[String] =
    if (i < 28) List("color_temp", "xy")
    else if (i < 36) List("color_temp")
    else if (i < 44) List("brightness")
    else if (i < 47) List("onoff")
    else List("rgb")

  private def light(id: String, i: Int): FixtureEntity = {
    val modes = lightModes(i)
    val on = i % 3 == 0
    val effects = i % 5 < 3
    val ct = modes.contains("color_temp")
    val registry = Map(
      "supported_color_modes" -> Json.arr(modes.map(s)*),
      "supported_features" -> n(if (effects) 44 else 40)
    ) ++ Option
      .when(effects)("effect_list" -> Json.arr(s("off"), s("colorloop")))
      .toList ++ Option
      .when(ct)(
        List(
          "min_color_temp_kelvin" -> n(2000),
          "max_color_temp_kelvin" -> n(6535)
        )
      )
      .toList
      .flatten
    val live =
      if (!on) Map.empty
      else
        Map("color_mode" -> s(modes.head)) ++
          Option
            .when(modes != List("onoff"))(
              "brightness" -> n(1 + (i * 37) % 254)
            )
            .toList ++
          Option
            .when(ct)("color_temp_kelvin" -> n(2200 + (i * 97) % 4000))
            .toList
    FixtureEntity(
      id,
      if (on) "on" else "off",
      Map("friendly_name" -> s(s"Light $i")) ++ registry ++ live
    )
  }

  private def sensor(id: String, i: Int): FixtureEntity = {
    def m(dc: String, unit: String, state: String) = FixtureEntity(
      id,
      state,
      Map(
        "friendly_name" -> s(s"Sensor $i"),
        "device_class" -> s(dc),
        "unit_of_measurement" -> s(unit),
        "state_class" -> s("measurement")
      )
    )
    if (i < 22) m("battery", "%", ((i * 37) % 100).toString)
    else if (i < 40) m("temperature", "°C", f"${15 + i % 10}.${i % 10}")
    else if (i < 120) m("monetary", "NOK", f"${i % 7}.${i % 100}%02d")
    else if (i < 152) m("energy", "kWh", s"${i * 13}.${i % 10}")
    else if (i < 183) m("duration", "min", (i % 90).toString)
    else if (i < 199) m("power", "W", (i * 7 % 900).toString)
    else
      FixtureEntity(
        id,
        (i % 50).toString,
        Map("friendly_name" -> s(s"Sensor $i"))
      )
  }

  private def other(domain: String, id: String, i: Int): FixtureEntity = {
    val name = "friendly_name" -> s(s"${domain.capitalize} $i")
    domain match
      case "switch" =>
        FixtureEntity(id, if (i % 4 == 0) "on" else "off", Map(name))
      case "binary_sensor" =>
        val dc = List("problem", "door", "window", "running", "connectivity")
        FixtureEntity(
          id,
          if (i % 6 == 0) "on" else "off",
          Map(name, "device_class" -> s(dc(i % dc.size)))
        )
      case "number" =>
        FixtureEntity(
          id,
          (i % 100).toString,
          Map(
            name,
            "min" -> n(0),
            "max" -> n(100),
            "step" -> n(1),
            "mode" -> s("auto")
          )
        )
      case "select" =>
        FixtureEntity(
          id,
          "a",
          Map(name, "options" -> Json.arr(s("a"), s("b"), s("c")))
        )
      case "climate" =>
        FixtureEntity(
          id,
          "heat",
          Map(
            name,
            "hvac_modes" -> Json.arr(s("off"), s("heat")),
            "min_temp" -> n(5),
            "max_temp" -> n(30),
            "current_temperature" -> Json.fromDoubleOrNull(20.5),
            "temperature" -> n(21)
          )
        )
      case "update" =>
        FixtureEntity(id, if (i % 9 == 0) "on" else "off", Map(name))
      case _ => FixtureEntity(id, "unknown", Map(name))
  }

  val entities: List[FixtureEntity] =
    DomainCounts.flatMap { (domain, count) =>
      List.tabulate(count) { i =>
        val id = s"$domain.${domain}_$i"
        domain match
          case "light"  => light(id, i)
          case "sensor" => sensor(id, i)
          case _        => other(domain, id, i)
      }
    }

  val states: Map[String, EntityState] =
    entities.map(e => e.entityId -> e.toEntityState).toMap

  /** [[RegistryDump.transform]] over the shape `RegistryDump.build` emits. */
  val transformedDump: Json = {
    val entityJson = entities.zipWithIndex.map { (e, j) =>
      val area = Option.when(j % 12 < 7)(Areas(j % Areas.size))
      Json.obj(
        "entity_id" -> s(e.entityId),
        "domain" -> s(e.domain),
        "friendly_name" -> e.attributes.getOrElse("friendly_name", Json.Null),
        "area_id" -> area.fold(Json.Null)(a => s(a._1)),
        "floor_id" -> area.fold(Json.Null)(a => s(a._2)),
        "attributes" -> Json.fromFields(
          e.attributes.filter((k, _) =>
            RegistryDump.CapabilityAttributes.contains(k)
          )
        )
      )
    }
    RegistryDump.transform(
      Json.obj(
        "floors" -> Json.fromValues(Floors.map { f =>
          Json.obj(
            "floor_id" -> s(f.id),
            "floor_name" -> s(f.id.capitalize),
            "level" -> n(f.level)
          )
        }),
        "areas" -> Json.fromValues(Areas.map { (a, f) =>
          Json.obj(
            "area_id" -> s(a),
            "area_name" -> s(a.replace('_', ' ').capitalize),
            "floor_id" -> s(f)
          )
        }),
        "entities" -> Json.fromValues(entityJson)
      )
    )
  }
}
