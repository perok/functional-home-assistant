package api.homeassistant.ws

import api.homeassistant.ws.protocol.client.CommandPhase
import api.homeassistant.ws.protocol.client.CommandPhase.subscribe_entities
import io.circe.syntax.*

/** The wire form of `subscribe_entities`, which HA validates strictly enough
  * that the difference between an ABSENT field and a null one decides whether
  * the subscription works at all.
  */
class SubscribeEntitiesSuite extends munit.FunSuite {

  private def wire(c: subscribe_entities): String =
    (c: CommandPhase).asJson.noSpaces

  test("an unfiltered subscription omits entity_ids rather than nulling it") {
    // `vol.Optional("entity_ids"): cv.entity_ids` rejects null, so a derived
    // encoder's `"entity_ids":null` would fail the whole command — and the
    // dashboard would go dark with an error that names the filter, not the
    // encoder.
    assertEquals(
      wire(subscribe_entities(None)),
      """{"type":"subscribe_entities"}"""
    )
  }

  test("a filtered subscription sends the ids") {
    assertEquals(
      wire(subscribe_entities(Some(List("light.a", "sensor.b")))),
      """{"entity_ids":["light.a","sensor.b"],"type":"subscribe_entities"}"""
    )
  }

  test("an EMPTY id list is still encodable, and means the whole house") {
    // Pinned because it is a trap, not because it is wanted: HA reads the list
    // as `set(...) or None`, so `[]` falls back to unfiltered. Callers must
    // decline to subscribe instead of sending this.
    assertEquals(
      wire(subscribe_entities(Some(Nil))),
      """{"entity_ids":[],"type":"subscribe_entities"}"""
    )
  }
}
