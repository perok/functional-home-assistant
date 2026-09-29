package fh.view.build

import fh.view.testkit.PklWorkspace

/** A home on a newer HA than the lib was synced against (ADR 0013): every
  * schema field typed by a vendored union carries a value that union lacks, and
  * the dump must still evaluate with each field's readers forced. Without the
  * generator's filter these fail with "Expected value of type ...".
  */
class UnvendoredValueSuite extends munit.FunSuite {

  private val newerHa = io.circe.parser
    .parse("""
      {
        "areas": {},
        "floors": {},
        "entities": {
          "light_future": {
            "entity_id": "light.future", "domain": "light",
            "attributes": { "supported_color_modes": ["brightness", "future_mode"] }
          },
          "sensor_future": {
            "entity_id": "sensor.future", "domain": "sensor",
            "attributes": {
              "device_class": "future_class",
              "state_class": "future_state_class",
              "unit_of_measurement": "min"
            }
          },
          "binary_sensor_future": {
            "entity_id": "binary_sensor.future", "domain": "binary_sensor",
            "attributes": { "device_class": "future_class" }
          }
        }
      }
    """)
    .toOption
    .get

  test(
    "a dump from a newer HA evaluates, readers of every vendored field included"
  ) {
    val tmp = os.temp.dir()
    val _ = PklWorkspace.bootstrap(tmp, PklDump.render(newerHa))
    os.write(
      tmp / "probe.pkl",
      """module probe
        |
        |import "@fh-home/dump.pkl" as dump
        |
        |local light = dump.entities.light_future
        |local sensor = dump.entities.sensor_future
        |local binary = dump.entities.binary_sensor_future
        |
        |modes = light.colourModes.toList()
        |dimmable = light.supportsBrightness
        |colour = light.supportsColour
        |live = light.volatileAttrs.keys.toList()
        |isEnum = sensor.isEnum
        |isNumeric = sensor.isNumeric
        |stateClass = sensor.state_class
        |unit = sensor.unit_of_measurement
        |binaryClass = binary.device_class
        |""".stripMargin
    )
    val result = SourceEval.eval(tmp, "probe.pkl")
    assert(result.isRight, clue = result)
    val c = result.toOption.get.value.hcursor
    assertEquals(
      c.get[List[String]]("modes").toOption,
      Some(List("brightness"))
    )
    assertEquals(c.get[Boolean]("dimmable").toOption, Some(true))
    assertEquals(c.get[Boolean]("colour").toOption, Some(false))
    assertEquals(c.get[Boolean]("isEnum").toOption, Some(false))
    assertEquals(c.get[String]("unit").toOption, Some("min"))
    assert(c.downField("stateClass").focus.forall(_.isNull), clue = c.focus)
    assert(c.downField("binaryClass").focus.forall(_.isNull), clue = c.focus)
  }
}
