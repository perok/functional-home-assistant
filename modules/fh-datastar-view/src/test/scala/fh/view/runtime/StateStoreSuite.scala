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

  // The resume cursor (docs/plan-sse-resume.md). `changedSince` is pure, so the
  // interesting cases are asserted on the value, not through a server.
  private def store(entities: (String, EntityState)*) =
    StoreState(entities.toMap, 0L, Map.empty, 0L)

  test("an idle re-seed leaves every cursor resumable") {
    val (version, since) = (for {
      s <- StateStore.inMemory(Map("a" -> st("a", "1")))
      _ <- s.update(st("a", "1")) // deduped: nothing a client could care about
      v <- s.version
      r <- s.changedSince(0L)
    } yield (v, r)).timeout(10.seconds).unsafeRunSync()

    // No version movement, so the cursor a client is holding still names the
    // present and resumes to an empty delta rather than a repaint.
    assertEquals(version, 0L)
    assertEquals(since, StateStore.Since.Changed(Set.empty, 0L))
  }

  test("a cursor is told exactly which entities moved since it was issued") {
    val (mid, out) = (for {
      s <- StateStore.inMemory(Map("a" -> st("a", "1"), "b" -> st("b", "1")))
      _ <- s.update(st("a", "2"))
      mid <- s.version // the cursor a client would be holding here
      _ <- s.update(st("b", "2"))
      r <- s.changedSince(mid)
    } yield (mid, r)).timeout(10.seconds).unsafeRunSync()

    assertEquals(mid, 1L)
    // `a` moved BEFORE the cursor was issued, so it is not resent.
    assertEquals(out, StateStore.Since.Changed(Set("b"), 2L))
  }

  test("a batch is one version, however many entities it carries") {
    val out = (for {
      s <- StateStore.inMemory(Map.empty)
      _ <- s.update(
        List(Ingest.Replace(st("a", "1")), Ingest.Replace(st("b", "1")))
      )
      v <- s.version
      r <- s.changedSince(0L)
    } yield (v, r)).timeout(10.seconds).unsafeRunSync()

    // One coalesced frame -> one version covering both entities.
    assertEquals(out._1, 1L)
    assertEquals(out._2, StateStore.Since.Changed(Set("a", "b"), 1L))
  }

  test("a removal forces a repaint for cursors issued before it") {
    val state = store("a" -> st("a", "1"))
    val after = StateStore
      .changedSince(state.copy(version = 5L, lastRemoval = 5L), 4L)
    assertEquals(after, StateStore.Since.Repaint)
    // A cursor issued AT or after the removal is fine again.
    assertEquals(
      StateStore.changedSince(state.copy(version = 5L, lastRemoval = 5L), 5L),
      StateStore.Since.Changed(Set.empty, 5L)
    )
  }

  test("a cursor from ahead of the store is rejected, not trusted") {
    // A restarted server: the browser still holds a signal from the old store.
    assertEquals(
      StateStore.changedSince(store("a" -> st("a", "1")), 7L),
      StateStore.Since.Repaint
    )
  }

  test("a removed entity drops out of the change index") {
    val out = (for {
      s <- StateStore.inMemory(Map("a" -> st("a", "1")))
      _ <- s.update(st("a", "2")) // a changed at version 1
      _ <- s.update(List(Ingest.Remove("a"))) // ...then vanished at version 2
      r <- s.changedSince(2L)
    } yield r).timeout(10.seconds).unsafeRunSync()

    // The stale `changedAt` entry must not name an entity that no longer exists,
    // or a resume would try to re-render a node with no state behind it.
    assertEquals(out, StateStore.Since.Changed(Set.empty, 2L))
  }
}
