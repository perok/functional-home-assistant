package api.homeassistant

import cats.data.NonEmptyList
import io.circe.Json
import io.circe.parser.parse

class ServiceTargetSuite extends munit.FunSuite {

  private def json(s: String): Json =
    parse(s).fold(e => fail(e.toString), identity)

  test("a lone entity, an area and a floor encode as one id list") {
    assertEquals(
      ServiceTarget.Entity("light.a").json,
      json("""{"entity_id": ["light.a"]}""")
    )
    assertEquals(
      ServiceTarget.Area("stue").json,
      json("""{"area_id": ["stue"]}""")
    )
    assertEquals(
      ServiceTarget.Floor("g").json,
      json("""{"floor_id": ["g"]}""")
    )
  }

  test("entities keep their order") {
    assertEquals(
      ServiceTarget.Entities(NonEmptyList.of("light.b", "light.a")).json,
      json("""{"entity_id": ["light.b", "light.a"]}""")
    )
  }

  test("keys combine, and exclude has the same shape") {
    val t = ServiceTarget
      .of(
        TargetIds(areaIds = List("stue", "kjokken")),
        TargetIds(entityIds = List("light.x"), areaIds = List("soverom"))
      )
      .getOrElse(fail("valid target"))
    assertEquals(
      t.json,
      json(
        """{"area_id": ["stue", "kjokken"],
          | "exclude": {"entity_id": ["light.x"], "area_id": ["soverom"]}}""".stripMargin
      )
    )
  }

  test("an empty exclude is omitted") {
    val t = ServiceTarget
      .of(TargetIds(entityIds = List("light.a")))
      .getOrElse(fail("valid target"))
    assertEquals(t.json.hcursor.downField("exclude").succeeded, false)
  }

  test("an exclude-only or empty target is invalid") {
    assertEquals(
      ServiceTarget.of(TargetIds(), TargetIds(entityIds = List("light.a"))),
      None
    )
    assertEquals(ServiceTarget.of(TargetIds()), None)
  }
}
