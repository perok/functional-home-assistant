package fh.view.build

import fh.view.testkit.PklWorkspace

/** The registry facts that say which part an entity plays on its device (#541):
  * carried into the typed dump and readable from a dashboard, null where HA has
  * none.
  */
class DumpRoleFactsSuite extends munit.FunSuite {

  private val transformed = io.circe.parser
    .parse("""
      {
        "areas": {},
        "floors": {},
        "entities": {
          "sensor_left": {
            "entity_id": "sensor.left", "domain": "sensor",
            "platform": "bambu_lab",
            "translation_key": "remaining_time",
            "original_name": "Remaining time",
            "attributes": { "platform": "an attribute of the same name" }
          },
          "sensor_bare": {
            "entity_id": "sensor.bare", "domain": "sensor",
            "attributes": {}
          }
        }
      }
    """)
    .toOption
    .get

  test("platform, translation_key and original_name read from the dump") {
    val tmp = os.temp.dir()
    val _ = PklWorkspace.bootstrap(tmp, PklDump.render(transformed))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-home/dump.pkl" as dump
        |
        |local left = dump.entities.sensor_left
        |local bare = dump.entities.sensor_bare
        |
        |platform = left.platform
        |key = left.translation_key
        |original = left.original_name
        |bareKey = bare.translation_key
        |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    // The registry's, not the attribute that shares the name.
    assertEquals(c.get[String]("platform").toOption, Some("bambu_lab"))
    assertEquals(c.get[String]("key").toOption, Some("remaining_time"))
    assertEquals(c.get[String]("original").toOption, Some("Remaining time"))
    assert(c.downField("bareKey").focus.forall(_.isNull), clue = c.focus)
  }

  test("c.roles.progress finds a generated device's parts by those facts") {
    def sensor(id: String, key: String, attrs: String) =
      s""""sensor_$id": { "entity_id": "sensor.$id", "domain": "sensor",
         |  "device_id": "p1", "platform": "bambu_lab", "translation_key": "$key",
         |  "attributes": { $attrs } }""".stripMargin
    val dump = io.circe.parser
      .parse(s"""{
        "areas": {}, "floors": {},
        "devices": { "printer": { "device_id": "p1", "device_name": "P1S" } },
        "entities": {
          ${sensor(
          "left",
          "remaining_time",
          """"device_class": "duration", "unit_of_measurement": "min""""
        )},
          ${sensor("pct", "print_progress", """"unit_of_measurement": "%"""")},
          ${sensor("stage", "current_stage", """"device_class": "enum"""")}
        }
      }""")
      .fold(e => fail(e.toString), identity)
    val tmp = os.temp.dir()
    val _ = PklWorkspace.bootstrap(tmp, PklDump.render(dump))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-dashboard/components.pkl" as c
        |import "@fh-home/dump.pkl" as dump
        |
        |local p = c.roles.progress(dump.devices.printer)!!
        |found = List(p.left?.entity_id, p.percent?.entity_id, p.status?.entity_id)
        |label = c.progress(dump.devices.printer).slots["label"]
        |""".stripMargin
    )
    val c = SourceEval
      .eval(tmp, "probe.pkl")
      .fold(e => fail(e), identity)
      .value
      .hcursor
    assertEquals(
      c.get[List[String]]("found").toOption,
      Some(List("sensor.left", "sensor.pct", "sensor.stage"))
    )
    assertEquals(c.get[String]("label").toOption, Some("P1S"))
  }
}
