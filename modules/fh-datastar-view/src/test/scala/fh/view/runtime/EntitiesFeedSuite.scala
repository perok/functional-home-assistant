package fh.view.runtime

import api.homeassistant.ws.domain.EntitiesEvent
import cats.effect.IO
import fs2.Chunk
import io.circe.Json
import io.circe.parser.parse

/** Pins HA's `subscribe_entities` wire format and how the store folds it in.
  *
  * The frames below are VERBATIM captures from a live instance (HA 2026.7.2) —
  * the feed is frontend-facing rather than formally documented, so a captured
  * payload is the only honest specification we have. Everything else in the suite
  * drives the fake, which cannot catch a decoder that disagrees with real HA.
  */
class EntitiesFeedSuite extends munit.CatsEffectSuite {

  private def decode(raw: String): EntitiesEvent =
    parse(raw)
      .flatMap(_.hcursor.downField("event").as[EntitiesEvent])
      .fold(throw _, identity)

  // The opening frame: complete states. `lu` is ABSENT here (HA omits it when it
  // equals `lc`), which must not read as "no timestamp".
  private val fullFrame = """
    {"id":1,"type":"event","event":{"a":{
      "update.supervisor":{"s":"off","a":{"title":"Supervisor","friendly_name":"Supervisor Update","supported_features":5},"c":"01KXWRZ0G8PNZW3HDVNNBHDTR1","lc":1784450875.9129083},
      "sensor.ams":{"s":"61","a":{"unit_of_measurement":"W"},"c":"01KXWRZ0GC0TVZQY7WBWYEWQJF","lc":1784450875.9167974,"lu":1784884842.228848}
    }}}"""

  // A state-only change: no `a`, so stored attributes must survive it.
  private val stateOnlyFrame = """
    {"type":"event","event":{"c":{"sensor.ams":{"+":{"s":"62","lc":1785013461.3749237,"c":"01KYDHFRBYWEE0GD32G3XKR3Y0"}}}},"id":1}"""

  // An attribute-only change carrying ONLY the attributes that moved — the case
  // that makes this a MERGE and not a replace.
  private val attrDeltaFrame = """
    {"type":"event","event":{"c":{"update.supervisor":{"+":{"lu":1785013569.422556,"c":"01KYDHK1WEQZTZA6NVRAMDCAN7","a":{"supported_features":7}}}}},"id":1}"""

  test("the opening frame decodes every entity, with lu defaulting to lc") {
    val event = decode(fullFrame)
    assertEquals(event.added.keySet, Set("update.supervisor", "sensor.ams"))
    assertEquals(event.changed, Map.empty)

    val supervisor = event.added("update.supervisor")
    assertEquals(supervisor.state, "off")
    assertEquals(supervisor.attributes("title"), Json.fromString("Supervisor"))
    assertEquals(supervisor.lastChanged, Some(1784450875.9129083))
    assertEquals(supervisor.lastUpdated, None)
    // What `StateStore.ingests` reads: absent `lu` falls back to `lc` rather
    // than leaving the state timestamp-less.
    assertEquals(
      StateStore
        .ingests(event)
        .collect { case Ingest.Replace(s) if s.entityId == "update.supervisor" =>
          s.lastUpdated
        },
      List(Some(EntityState.fromEpoch(1784450875.9129083)))
    )
  }

  test("a delta decodes to a Merge, carrying only what moved") {
    val event = decode(attrDeltaFrame)
    assertEquals(event.added, Map.empty)
    val delta = event.changed("update.supervisor")
    assertEquals(delta.plus.flatMap(_.state), None)
    assertEquals(
      delta.plus.map(_.attributes),
      Some(Map("supported_features" -> Json.fromInt(7)))
    )
    assertEquals(delta.minus, None)
    assertEquals(
      StateStore.ingests(event).map(_.entityId),
      List("update.supervisor")
    )
  }

  test("attributes merge across a delta; unrelated ones survive") {
    for {
      store <- StateStore.empty
      _ <- store.applyEntities(Chunk(decode(fullFrame)))
      _ <- store.applyEntities(Chunk(decode(attrDeltaFrame)))
      snapshot <- store.snapshot
    } yield {
      val supervisor = snapshot("update.supervisor")
      // The moved attribute is new...
      assertEquals(
        supervisor.attributes("supported_features"),
        Json.fromInt(7)
      )
      // ...and the ones the delta never mentioned are untouched.
      assertEquals(supervisor.attributes("title"), Json.fromString("Supervisor"))
      assertEquals(supervisor.state, "off")
      assertEquals(
        supervisor.lastUpdated,
        Some(EntityState.fromEpoch(1785013569.422556))
      )
    }
  }

  test("a state-only delta leaves attributes alone") {
    for {
      store <- StateStore.empty
      _ <- store.applyEntities(Chunk(decode(fullFrame)))
      _ <- store.applyEntities(Chunk(decode(stateOnlyFrame)))
      snapshot <- store.snapshot
    } yield {
      val sensor = snapshot("sensor.ams")
      assertEquals(sensor.state, "62")
      assertEquals(
        sensor.attributes("unit_of_measurement"),
        Json.fromString("W")
      )
    }
  }

  test("`-` drops the named attributes, `r` drops the entity") {
    val unset = decode(
      """{"type":"event","event":{"c":{"update.supervisor":{"+":{"lu":1785013570.1},"-":{"a":["title"]}}}},"id":1}"""
    )
    val removal =
      decode("""{"type":"event","event":{"r":["sensor.ams"]},"id":1}""")
    for {
      store <- StateStore.empty
      _ <- store.applyEntities(Chunk(decode(fullFrame)))
      _ <- store.applyEntities(Chunk(unset, removal))
      snapshot <- store.snapshot
    } yield {
      assert(!snapshot("update.supervisor").attributes.contains("title"))
      assert(snapshot("update.supervisor").attributes.contains("friendly_name"))
      assertEquals(snapshot.get("sensor.ams"), None)
    }
  }

  test("a delta for an entity we do not hold is dropped, not invented") {
    for {
      store <- StateStore.empty
      _ <- store.applyEntities(Chunk(decode(attrDeltaFrame)))
      snapshot <- store.snapshot
    } yield assertEquals(snapshot, Map.empty[String, EntityState])
  }

  test("a reconnect's full set republishes only what actually changed") {
    // The same opening frame twice is what a reconnect looks like when nothing
    // moved: no change may reach the SSE stream. Then one that DID move must.
    val moved = decode(
      """{"id":1,"type":"event","event":{"a":{
           "sensor.ams":{"s":"99","a":{"unit_of_measurement":"W"},"c":"01KY","lc":1785099999.0}
         }}}"""
    )
    for {
      store <- StateStore.empty
      _ <- store.applyEntities(Chunk(decode(fullFrame)))
      published <- store.changes
        .take(1)
        .compile
        .toList
        .background
        .use { joined =>
          store.changeSubscribers.find(_ > 0).head.compile.drain *>
            store.applyEntities(Chunk(decode(fullFrame))) *>
            store.applyEntities(Chunk(moved)) *>
            joined
        }
        .flatMap(_.embedNever)
    } yield assertEquals(
      published.map(c => (c.entityId, c.current.state)),
      List(("sensor.ams", "99"))
    )
  }
}
