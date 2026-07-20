package fh.view.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json

import scala.concurrent.duration.*

/** Covers the state-store dedup that backs reconnect recovery ([[HaFeed]]):
  * when the supervisor re-seeds the store after a dropped connection, unchanged
  * entities must NOT re-publish (no churn on every browser), while entities
  * that changed or appeared during the outage MUST publish so connected clients
  * catch up over their live SSE stream. Re-seeding is `snapshot |> update` per
  * entity, so exercising [[StateStore.update]] directly validates that contract
  * without standing up a fake REST endpoint.
  */
class StateStoreSuite extends munit.FunSuite {

  private def st(id: String, state: String): EntityState =
    EntityState(id, state, Map.empty)

  private def attrs(id: String, state: String, k: String, v: String) =
    EntityState(id, state, Map(k -> Json.fromString(v)))

  test("re-applying identical state is deduped (no StateChange published)") {
    val changes = (for {
      store <- StateStore.inMemory(Map("a" -> st("a", "1")))
      collected <- store.changes.take(1).compile.toList.start
      _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
      // A re-seed that observed no change for `a`, plus a genuinely new entity
      // `b` so the collector has exactly one real change to terminate on.
      _ <- store.update(st("a", "1"))
      _ <- store.update(st("b", "on"))
      out <- collected.joinWithNever
    } yield out).timeout(10.seconds).unsafeRunSync()

    // Only `b` (new) came through; the identical re-apply of `a` was dropped.
    assertEquals(changes.map(_.entityId), List("b"))
    assertEquals(changes.head.previous, None)
  }

  test(
    "re-seed publishes exactly the entities that changed while disconnected"
  ) {
    val changes = (for {
      store <- StateStore.inMemory(
        Map("a" -> st("a", "1"), "b" -> attrs("b", "on", "brightness", "10"))
      )
      // Expect two deltas: b's value change and the newly-seen c.
      collected <- store.changes.take(2).compile.toList.start
      _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
      // Simulate the post-reconnect snapshot fold:
      _ <- store.update(st("a", "1")) // unchanged -> deduped
      _ <- store.update(attrs("b", "off", "brightness", "0")) // changed
      _ <- store.update(st("c", "42")) // appeared during the outage
      out <- collected.joinWithNever
    } yield out).timeout(10.seconds).unsafeRunSync()

    assertEquals(changes.map(_.entityId), List("b", "c"))
    // The change carries the pre-outage value, so a dynamic group can tell it
    // crossed a boundary.
    assertEquals(changes.head.previous.map(_.state), Some("on"))
    assertEquals(changes.head.current.state, "off")
    assertEquals(changes(1).previous, None) // c was newly seen
  }
}
