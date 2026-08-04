package fh.view.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.parallel.*
import fh.view.model.NodeId
import fh.view.testkit.TestIds.given

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** Phase 1 of docs/plan-session-pulled-changelog.md.
  *
  * The cache's job is easy; the two ways this pattern breaks are not, and they
  * are what most of this suite is about. A producer that fails or is cancelled
  * must not leave waiters blocked on a `Deferred` nobody will ever complete,
  * and a failure must not stay in the map poisoning that node for the life of
  * the renderer.
  */
class RenderCacheSuite extends munit.FunSuite {

  private val id: NodeId = "c_0"
  private val v1 = RenderInputs(Map("sensor.t" -> 1L), Map.empty)
  private val v2 = RenderInputs(Map("sensor.t" -> 2L), Map.empty)

  /** A render that counts its runs and blocks until the test releases it — so
    * waiters genuinely pile up behind a producer rather than arriving after it
    * finished, which would make single-flight untestable.
    */
  private class Gated(html: String) {
    private val gate = new CountDownLatch(1)
    val runs = new AtomicInteger(0)
    def render: String = { runs.incrementAndGet(); gate.await(); html }
    def release(): Unit = gate.countDown()

    /** Both fibers race for the same key, and the one a test calls "the waiter"
      * can perfectly well win the CAS and render ITS string. Waiting for the
      * producer to be inside `render` is what makes which-is-which a fact
      * rather than a hope.
      */
    def started: IO[Unit] =
      IO(runs.get()).iterateUntil(_ > 0).void
  }

  test("concurrent callers for one key cost exactly one render") {
    val g = new Gated("<b>x</b>")
    val out = (for {
      cache <- RenderCache.create
      fibers <- List.fill(5)(cache(id, v1)(g.render)).parSequence.start
      _ <- IO.sleep(150.millis) *> IO(g.release())
      got <- fibers.joinWithNever
    } yield got).timeout(10.seconds).unsafeRunSync()

    assertEquals(g.runs.get(), 1)
    assertEquals(out.map(_.html).distinct, List("<b>x</b>"))
    assertEquals(out.head.digest, Digest.of("<b>x</b>"))
  }

  test("a second call for the same key does not render again") {
    val runs = new AtomicInteger(0)
    val (a, b, n) = (for {
      cache <- RenderCache.create
      a <- cache(id, v1) { runs.incrementAndGet(); "<i>1</i>" }
      b <- cache(id, v1) { runs.incrementAndGet(); "<i>2</i>" }
      n <- cache.size
    } yield (a, b, n)).timeout(10.seconds).unsafeRunSync()

    assertEquals(runs.get(), 1)
    // The SECOND call's bytes never ran, so a hit returns the first's.
    assertEquals(a.html, "<i>1</i>")
    assertEquals(b.html, "<i>1</i>")
    assertEquals(n, 1)
  }

  test("new inputs REPLACE a node's entry rather than adding one") {
    // The bound that makes this safe to leave running for days: the shared pass
    // selects exactly the nodes whose entity just moved, so every batch brings
    // new inputs. Keyed by (node, inputs) this map would grow forever, for hits
    // that never come.
    val (a, b, sizes) = (for {
      cache <- RenderCache.create
      a <- cache(id, v1)("<i>1</i>")
      n1 <- cache.size
      b <- cache(id, v2)("<i>2</i>")
      n2 <- cache.size
    } yield (a.html, b.html, (n1, n2))).timeout(10.seconds).unsafeRunSync()

    assertEquals(a, "<i>1</i>")
    assertEquals(b, "<i>2</i>")
    assertEquals(sizes, (1, 1))
  }

  test("a superseded generation does not evict the one that replaced it") {
    // A slow render that FAILS while a newer generation has already taken the
    // node. Evicting on the strength of that stale failure would drop a live
    // entry — hence the identity check before removing.
    val g = new Gated("unused")
    val (n, html) = (for {
      cache <- RenderCache.create
      doomed <- cache(id, v1) {
        val _ = g.render; throw new RuntimeException("late")
      }.attempt.start
      _ <- IO.sleep(100.millis)
      _ <- cache(id, v2)("<i>current</i>")
      _ <- IO(g.release())
      _ <- doomed.joinWithNever
      n <- cache.size
      still <- cache(id, v2)("never runs")
    } yield (n, still.html)).timeout(10.seconds).unsafeRunSync()

    assertEquals(n, 1)
    assertEquals(html, "<i>current</i>")
  }

  test("a failed render reaches its waiters instead of stranding them") {
    val g = new Gated("unused")
    val boom = new RuntimeException("render blew up")
    val (results, n) = (for {
      cache <- RenderCache.create
      // One producer that fails, four waiters queued behind it.
      producer <- cache(id, v1) { val _ = g.render; throw boom }.attempt.start
      waiters <- List
        .fill(4)(cache(id, v1)("never runs").attempt)
        .parSequence
        .start
      _ <- IO.sleep(150.millis) *> IO(g.release())
      p <- producer.joinWithNever
      w <- waiters.joinWithNever
      n <- cache.size
    } yield (p :: w, n)).timeout(10.seconds).unsafeRunSync()

    // Every one of them completes — with the error, not by hanging.
    assertEquals(results.length, 5)
    assert(results.forall(_.left.exists(_.getMessage == "render blew up")))
    // And the key is gone, so the failure is not permanent.
    assertEquals(n, 0)
  }

  test("the next caller after a failure renders again and succeeds") {
    val out = (for {
      cache <- RenderCache.create
      _ <- cache(id, v1)(throw new RuntimeException("transient")).attempt
      good <- cache(id, v1)("<b>recovered</b>")
    } yield good.html).timeout(10.seconds).unsafeRunSync()

    assertEquals(out, "<b>recovered</b>")
  }

  test("cancelling a waiter leaves the producer and the entry intact") {
    val g = new Gated("<b>survived</b>")
    val (out, again, n) = (for {
      cache <- RenderCache.create
      producer <- cache(id, v1)(g.render).start
      _ <- g.started
      waiter <- cache(id, v1)("never runs").start
      _ <- IO.sleep(100.millis)
      _ <- waiter.cancel
      _ <- IO(g.release())
      p <- producer.joinWithNever
      // The cancelled waiter must not have taken the entry with it.
      again <- cache(id, v1)("never runs either")
      n <- cache.size
    } yield (p.html, again.html, n)).timeout(10.seconds).unsafeRunSync()

    assertEquals(out, "<b>survived</b>")
    // The later caller HIT the entry the cancelled waiter was waiting on.
    assertEquals(again, "<b>survived</b>")
    assertEquals(n, 1)
  }

  test("cancelling the PRODUCER still completes its waiters") {
    // The invariant the whole design rests on. Production is uncancelable, so
    // a cancelled producer finishes its render and completes the slot before
    // it observes the cancellation — waiters cannot be stranded, and no
    // onCancel has to remember to unblock them.
    val g = new Gated("<b>finished anyway</b>")
    val (waited, n) = (for {
      cache <- RenderCache.create
      producer <- cache(id, v1)(g.render).start
      _ <- g.started
      waiter <- cache(id, v1)("never runs").start
      _ <- IO.sleep(100.millis)
      _ <- producer.cancel.start
      _ <- IO.sleep(50.millis) *> IO(g.release())
      w <- waiter.joinWithNever
      n <- cache.size
    } yield (w.html, n)).timeout(10.seconds).unsafeRunSync()

    assertEquals(waited, "<b>finished anyway</b>")
    assertEquals(g.runs.get(), 1)
    assertEquals(n, 1)
  }
}
