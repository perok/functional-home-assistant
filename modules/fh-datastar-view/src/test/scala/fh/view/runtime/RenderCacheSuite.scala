package fh.view.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.parallel.*
import fh.view.model.{Dashboard, LayoutNode, NodeId}
import fh.view.testkit.TestIds.given

import java.util.concurrent.{CountDownLatch, Executors}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.concurrent.duration.*

/** The per-slug render cache (ADR 0012).
  *
  * The cache's job is easy; the two ways this pattern breaks are not, and they
  * are what most of this suite is about. A producer that fails or is cancelled
  * must not leave waiters blocked on a `Deferred` nobody will ever complete,
  * and a failure must not stay in the map poisoning that node for the life of
  * the renderer.
  */
class RenderCacheSuite extends munit.FunSuite {

  /** The cache compares generations by IDENTITY, so what a renderer contains is
    * irrelevant here — only that these are two different instances.
    */
  private def aRenderer: Renderer =
    Renderer.create(Dashboard(Map.empty, LayoutNode.Component("card")))

  private val r = aRenderer

  /** Where a [[Gated]] render is allowed to block: an unbounded pool of its
    * own, so parking a thread there can never starve the compute pool the rest
    * of the test runs on.
    */
  private val gated: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  override def afterAll(): Unit = {
    gated.shutdown()
    super.afterAll()
  }

  extension [A](io: IO[A])
    /** Run this on [[gated]] — for any call whose render thunk can park. */
    private def blocking: IO[A] = io.evalOn(gated)

  private val id: NodeId = "c_0"
  private val v1 = RenderInputs(Map("sensor.t" -> 1L), Map.empty)
  private val v2 = RenderInputs(Map("sensor.t" -> 2L), Map.empty)

  /** A render that counts its runs and blocks until the test releases it — so
    * waiters genuinely pile up behind a producer rather than arriving after it
    * finished, which would make single-flight untestable.
    *
    * '''It BLOCKS A THREAD, so it must not block a compute worker.'''
    * [[RenderCache]] documents that its render thunk must be pure and
    * CPU-bound, and a `CountDownLatch.await()` inside an uncancelable region is
    * neither — but the thunk is a by-name `String`, so there is no way to
    * suspend it without changing the shape under test.
    *
    * So every caller whose thunk can park is shifted onto [[gated]] with
    * [[blocking]]. Left on the compute pool it holds a worker for the whole
    * test, and with sbt running suites in parallel that was enough to starve
    * the fibers the test waits for: `concurrent callers for one key cost
    * exactly one render` timed out roughly one run in ten.
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
      *
      * It SLEEPS rather than spinning. `iterateUntil` on a pure `IO` never
      * yields, so on a small pool it can starve the very fiber it is waiting
      * for — which is a hang, not a slow test.
      */
    def started: IO[Unit] =
      (IO.sleep(1.millis) *> IO(runs.get())).iterateUntil(_ > 0).void
  }

  test("concurrent callers for one key cost exactly one render") {
    val g = new Gated("<b>x</b>")
    val out = (for {
      cache <- RenderCache.create
      fibers <- List
        .fill(5)(cache(id, r, v1)(g.render).blocking)
        .parSequence
        .start
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
      a <- cache(id, r, v1) { runs.incrementAndGet(); "<i>1</i>" }
      b <- cache(id, r, v1) { runs.incrementAndGet(); "<i>2</i>" }
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
      a <- cache(id, r, v1)("<i>1</i>")
      n1 <- cache.size
      b <- cache(id, r, v2)("<i>2</i>")
      n2 <- cache.size
    } yield (a.html, b.html, (n1, n2))).timeout(10.seconds).unsafeRunSync()

    assertEquals(a, "<i>1</i>")
    assertEquals(b, "<i>2</i>")
    assertEquals(sizes, (1, 1))
  }

  test("a renderer swap invalidates every key, unchanged inputs included") {
    // The reason the generation is the RENDERER and not the inputs alone: a
    // dashboard edit changes the markup while the entity versions it reads stay
    // exactly where they were. Keyed on inputs only, the first viewer after a
    // push would be served the OLD dashboard's bytes, and would keep them until
    // some entity happened to move.
    val (before, after, size) = (for {
      cache <- RenderCache.create
      before <- cache(id, r, v1)("<i>old</i>")
      after <- cache(id, aRenderer, v1)("<i>new</i>")
      size <- cache.size
    } yield (before.html, after.html, size)).timeout(10.seconds).unsafeRunSync()

    assertEquals(before, "<i>old</i>")
    assertEquals(after, "<i>new</i>")
    // ...and the replaced generation is gone, not held alongside.
    assertEquals(size, 1)
  }

  test("a superseded generation does not evict the one that replaced it") {
    // A slow render that FAILS while a newer generation has already taken the
    // node. Evicting on the strength of that stale failure would drop a live
    // entry — hence the identity check before removing.
    val g = new Gated("unused")
    val (n, html) = (for {
      cache <- RenderCache.create
      doomed <- cache(id, r, v1) {
        val _ = g.render; throw new RuntimeException("late")
      }.blocking.attempt.start
      // The doomed generation must OWN the key before the newer one takes it,
      // or this tests nothing: a sleep says when, `started` says what.
      _ <- g.started
      _ <- cache(id, r, v2)("<i>current</i>")
      _ <- IO(g.release())
      _ <- doomed.joinWithNever
      n <- cache.size
      still <- cache(id, r, v2)("never runs")
    } yield (n, still.html)).timeout(10.seconds).unsafeRunSync()

    assertEquals(n, 1)
    assertEquals(html, "<i>current</i>")
  }

  test("a failed render reaches its waiters instead of stranding them") {
    val g = new Gated("unused")
    val boom = new RuntimeException("render blew up")
    val late = new AtomicInteger(0)
    val (results, n) = (for {
      cache <- RenderCache.create
      // One producer that fails, four waiters queued behind it.
      producer <- cache(id, r, v1) {
        val _ = g.render; throw boom
      }.blocking.attempt.start
      // BEFORE the waiters exist. Without it they race the producer for the
      // key, and a "waiter" that wins the CAS renders its own string — which
      // is instant, where the producer's is gated — so all five succeed and
      // the failure under test never happens. That was a real intermittent
      // failure, not a hypothetical.
      _ <- g.started
      waiters <- List
        .fill(4)(cache(id, r, v1) {
          late.incrementAndGet(); throw boom
        }.attempt)
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
    // The waiters WAITED: only the producer's render ran. `late` is what makes
    // that a fact — a caller arriving after the eviction would render for
    // itself and still fail, which is correct and would otherwise be
    // indistinguishable from waiting.
    assertEquals(late.get(), 0)
    assertEquals(g.runs.get(), 1)
  }

  test("the next caller after a failure renders again and succeeds") {
    val out = (for {
      cache <- RenderCache.create
      _ <- cache(id, r, v1)(throw new RuntimeException("transient")).attempt
      good <- cache(id, r, v1)("<b>recovered</b>")
    } yield good.html).timeout(10.seconds).unsafeRunSync()

    assertEquals(out, "<b>recovered</b>")
  }

  test("cancelling a waiter leaves the producer and the entry intact") {
    val g = new Gated("<b>survived</b>")
    val (out, again, n) = (for {
      cache <- RenderCache.create
      producer <- cache(id, r, v1)(g.render).blocking.start
      _ <- g.started
      waiter <- cache(id, r, v1)("never runs").start
      _ <- IO.sleep(100.millis)
      _ <- waiter.cancel
      _ <- IO(g.release())
      p <- producer.joinWithNever
      // The cancelled waiter must not have taken the entry with it.
      again <- cache(id, r, v1)("never runs either")
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
      producer <- cache(id, r, v1)(g.render).blocking.start
      _ <- g.started
      waiter <- cache(id, r, v1)("never runs").start
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
