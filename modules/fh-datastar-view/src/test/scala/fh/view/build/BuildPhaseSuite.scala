package fh.view.build

import fh.view.model.Dashboard
import io.circe.parser
import io.circe.syntax.*

class BuildPhaseSuite extends munit.FunSuite {

  test("DataDump.transform keys lists by sanitized id and adds a '*' member") {
    val raw = parser
      .parse("""
        {
          "areas": [],
          "floors": [],
          "entities": [
            { "entity_id": "sensor.temp", "friendly_name": "Temp", "domain": "sensor" },
            { "entity_id": "light.kitchen", "friendly_name": "Kitchen", "domain": "light" }
          ]
        }
      """)
      .toOption
      .get

    val entities = DataDump.transform(raw).hcursor.downField("entities")

    // dotless, sanitized keys
    assert(entities.downField("sensor_temp").succeeded)
    assert(entities.downField("light_kitchen").succeeded)
    assertEquals(
      entities.downField("sensor_temp").get[String]("friendly_name").toOption,
      Some("Temp")
    )

    // "*" lists the original ids
    val all = entities.get[List[String]]("*").toOption.get.toSet
    assertEquals(all, Set("sensor.temp", "light.kitchen"))
  }

  test(
    "JsonnetBuild evaluates the example dashboard into a valid Dashboard model"
  ) {
    val resources =
      os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards"

    val tmp = os.temp.dir()
    os.copy.into(resources / "components.libsonnet", tmp)
    os.copy.into(resources / "dashboard.jsonnet", tmp)

    // Minimal fake dump standing in for the build-phase HA dump.
    val fakeDump = io.circe.Json
      .obj(
        "areas" -> io.circe.Json.obj("*" -> List.empty[String].asJson),
        "floors" -> io.circe.Json.obj("*" -> List.empty[String].asJson),
        "entities" -> io.circe.Json.obj(
          "sensor_temp" -> io.circe.Json.obj(
            "entity_id" -> "sensor.temp".asJson,
            "friendly_name" -> "Temperature".asJson,
            "domain" -> "sensor".asJson
          ),
          "*" -> List("sensor.temp").asJson
        )
      )
    os.write(tmp / "dump.libsonnet", fakeDump.spaces2)

    val result = JsonnetBuild.eval(tmp, "dashboard.jsonnet")
    assert(result.isRight, clue = result)

    val dashboard = result.flatMap(_.as[Dashboard].left.map(_.getMessage))
    assert(dashboard.isRight, clue = dashboard)

    val d = dashboard.toOption.get
    assert(d.templates.contains("sensor_temp"), clue = d.templates.keySet)
    assertEquals(d.registry("sensor_temp").entities, List("sensor.temp"))
    assertEquals(
      d.registry("sensor_temp").slots("unit").attribute,
      Some("unit_of_measurement")
    )
    assert(d.layout.contains("{{{sensor_temp}}}"), clue = d.layout)
  }
}
