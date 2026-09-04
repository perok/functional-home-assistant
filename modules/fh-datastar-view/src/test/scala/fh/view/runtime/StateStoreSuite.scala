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
    } yield out.flatten).timeout(10.seconds).unsafeRunSync()

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
    } yield out.flatten).timeout(10.seconds).unsafeRunSync()

    assertEquals(changes.map(_.entityId), List("b", "c"))
    // The change carries the pre-outage value, so a candidate set can tell it
    // crossed a boundary.
    assertEquals(changes.head.previous.map(_.state), Some("on"))
    assertEquals(changes.head.current.state, "off")
    assertEquals(changes(1).previous, None) // c was newly seen
  }

  // `version` stamps rendered fragments (docs/adr/0011-the-live-connection.md), so what it
  // must guarantee is: it moves iff something a client could see moved, and it
  // moves ONCE per batch.

  test("an idle re-seed does not move the version") {
    val version = (for {
      s <- StateStore.inMemory(Map("a" -> st("a", "1")))
      _ <- s.update(st("a", "1")) // deduped: nothing a client could care about
      v <- s.version
    } yield v).timeout(10.seconds).unsafeRunSync()

    // A reconnect's full set is all dedup, so every fragment stamped before it
    // is still current and no client is told to catch up on nothing.
    assertEquals(version, 0L)
  }

  test("a batch is one version, however many entities it carries") {
    val version = (for {
      s <- StateStore.inMemory(Map.empty)
      _ <- s.update(
        List(Ingest.Replace(st("a", "1")), Ingest.Replace(st("b", "1")))
      )
      v <- s.version
    } yield v).timeout(10.seconds).unsafeRunSync()

    // One coalesced frame -> one version, so fragments rendered from the same
    // HA tick share a stamp.
    assertEquals(version, 1L)
  }

  test("a removal moves the version even though it publishes no change") {
    val version = (for {
      s <- StateStore.inMemory(Map("a" -> st("a", "1")))
      _ <- s.update(List(Ingest.Remove("a")))
      v <- s.version
    } yield v).timeout(10.seconds).unsafeRunSync()

    // An `r` frame does not always have a registry event behind it, so the
    // clock must record it even though no node is re-rendered for it.
    assertEquals(version, 1L)
  }

  test("contentVersion moves only for the entities a batch actually moved") {
    val (a, b) = (for {
      s <- StateStore.inMemory(Map("a" -> st("a", "1"), "b" -> st("b", "1")))
      _ <- s.update(
        List(Ingest.Replace(st("a", "2")), Ingest.Replace(st("b", "1")))
      )
      snap <- s.snapshot
    } yield (snap("a"), snap("b"))).timeout(10.seconds).unsafeRunSync()

    // `a` takes the batch's version; `b` was deduped and keeps its old stamp,
    // which is the whole point — a render keyed on `b` must still hit.
    assertEquals(a.contentVersion, 1L)
    assertEquals(b.contentVersion, 0L)
  }

  test("a timestamp-only bump keeps the contentVersion") {
    val stamp = (for {
      s <- StateStore.inMemory(
        Map("a" -> st("a", "1").copy(contentVersion = 7L))
      )
      _ <- s.update(
        st("a", "1").copy(lastUpdated = Some(java.time.Instant.now()))
      )
      snap <- s.snapshot
    } yield snap("a").contentVersion).timeout(10.seconds).unsafeRunSync()

    // The stored value advances (recency), the stamp does not: nothing a
    // template could print has changed, so a cached render is still valid.
    assertEquals(stamp, 7L)
  }

  test("a full state the store already holds is not re-stored at all") {
    val held = st("a", "1").copy(
      lastUpdated = Some(java.time.Instant.parse("2026-09-04T06:00:00Z")),
      contentVersion = 7L
    )
    val (before, after) = (for {
      s <- StateStore.inMemory(Map("a" -> held))
      // Byte for byte what a reconnect's full set hands back on a steady HA:
      // same content, same instant, and no stamp of its own yet.
      _ <- s.update(held.copy(contentVersion = 0L))
      snap <- s.snapshot
    } yield (held, snap("a"))).timeout(10.seconds).unsafeRunSync()

    // Identity, not equality: `stale` treats an equal instant as not newer, so
    // the Replace is dropped a level ABOVE the dedup and the stored value is
    // untouched. An equal copy would pass `==` while still having cost a map
    // node, an EntityState and its recomputed attribute caches — which is what
    // this asserts is not happening on the reconnect path.
    assert(before eq after)
  }

  test("a stale change cannot slip in behind a timestamp-only bump") {
    val t10 = java.time.Instant.parse("2026-09-04T06:00:10Z")
    val t12 = java.time.Instant.parse("2026-09-04T06:00:12Z")
    val t15 = java.time.Instant.parse("2026-09-04T06:00:15Z")

    val state = (for {
      s <- StateStore.inMemory(
        Map("a" -> st("a", "1").copy(lastUpdated = Some(t10)))
      )
      // Same content, later instant: publishes nothing, but the stored value
      // MUST advance to t15 anyway.
      _ <- s.update(st("a", "1").copy(lastUpdated = Some(t15)))
      // A reconnect's full set, from before t15 and disagreeing about content.
      _ <- s.update(st("a", "2").copy(lastUpdated = Some(t12)))
      snap <- s.snapshot
    } yield snap("a").state).timeout(10.seconds).unsafeRunSync()

    // Had the bump been skipped as a no-op, `a` would still read t10, `stale`
    // would wave t12 through, and the store would end up holding "2" — content
    // HA superseded three seconds before that frame was sent.
    assertEquals(state, "1")
  }

  test("a published change carries the same stamp the store kept") {
    val (published, stored) = (for {
      s <- StateStore.inMemory(Map("a" -> st("a", "1")))
      collected <- s.changes.take(1).compile.toList.start
      _ <- s.changeSubscribers.filter(_ >= 1).head.compile.drain
      _ <- s.update(st("a", "2"))
      out <- collected.joinWithNever
      snap <- s.snapshot
    } yield (out.flatten.head.current.contentVersion, snap("a").contentVersion))
      .timeout(10.seconds)
      .unsafeRunSync()

    // A StateChange whose `current` disagreed with the snapshot would key a
    // render to a version that never existed in the store.
    assertEquals(published, stored)
    assertEquals(published, 1L)
  }
}
