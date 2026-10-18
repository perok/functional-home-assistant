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
}
