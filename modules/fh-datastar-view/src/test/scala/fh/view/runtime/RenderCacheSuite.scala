package fh.view.runtime

import cats.effect.IO
import cats.effect.std.CountDownLatch
import cats.effect.unsafe.implicits.global
import cats.syntax.parallel.*
import cats.syntax.traverse.*
import fh.view.model.{
  Dashboard,
  LayoutNode,
  NodeId,
  SlotQuery,
  SlotRead,
  Transform
}
import fh.view.testkit.TestIds.given

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** The per-slug render cache (ADR 0012). Most of this is the two ways the
  * pattern breaks: a failed or cancelled producer must not strand waiters on a
  * `Deferred` nobody completes, and a failure must not stay in the map
  * poisoning the node.
  */
class RenderCacheSuite extends munit.FunSuite {

  /** Generations compare by identity, so only distinct instances matter. */
  private def aRenderer: Renderer =
    Renderer.create(Dashboard(Map.empty, LayoutNode.Component("card")))

  private val r = aRenderer

  private val id: NodeId = "c_0"
  private val v1 = RenderInputs(Map("sensor.t" -> 1L))
  private val v2 = RenderInputs(Map("sensor.t" -> 2L))

  /** Counts its runs and suspends until released, so waiters pile up behind a
    * producer. It holds no thread: a `java.util.concurrent.CountDownLatch` here
    * parked a compute worker, which with parallel suites starved the fibers
    * under test about one run in ten.
    */
  private class Gated(html: String, latch: CountDownLatch[IO]) {
    val runs = new AtomicInteger(0)

    def render: IO[String] = IO(runs.incrementAndGet()) *> latch.await.as(html)
    def release: IO[Unit] = latch.release

    /** Either fiber can win the CAS, so waiting for the producer to be inside
      * `render` is what makes which-is-which a fact. Sleeps rather than spins:
      * `iterateUntil` on a pure `IO` never yields, and can starve the fiber it
      * waits for.
      */
    def started: IO[Unit] =
      (IO.sleep(1.millis) *> IO(runs.get())).iterateUntil(_ > 0).void
  }

  private def gated(html: String): IO[Gated] =
    CountDownLatch[IO](1).map(new Gated(html, _))

  test("concurrent callers for one key cost exactly one render") {
    val (out, renders) = (for {
      g <- gated("<b>x</b>")
      cache <- RenderCache.create
      fibers <- List.fill(5)(cache(id, r, v1)(g.render)).parSequence.start
      _ <- g.started
      _ <- g.release
      got <- fibers.joinWithNever
    } yield (got, g.runs.get())).timeout(10.seconds).unsafeRunSync()

    assertEquals(renders, 1)
    assertEquals(out.map(_.html).distinct, List("<b>x</b>"))
    assertEquals(out.head.digest, Digest.of("<b>x</b>"))
  }

  test("a second call for the same key does not render again") {
    val runs = new AtomicInteger(0)
    val (a, b, n) = (for {
      cache <- RenderCache.create
      a <- cache(id, r, v1)(IO { runs.incrementAndGet(); "<i>1</i>" })
      b <- cache(id, r, v1)(IO { runs.incrementAndGet(); "<i>2</i>" })
      n <- cache.size
    } yield (a, b, n)).timeout(10.seconds).unsafeRunSync()

    assertEquals(runs.get(), 1)
    // The second call's bytes never ran.
    assertEquals(a.html, "<i>1</i>")
    assertEquals(b.html, "<i>1</i>")
    assertEquals(n, 1)
  }

  test("new inputs REPLACE a node's entry rather than adding one") {
    // Every batch brings new inputs, so keyed by (node, inputs) the map would
    // grow forever for hits that never come.
    val (a, b, sizes) = (for {
      cache <- RenderCache.create
      a <- cache(id, r, v1)(IO.pure("<i>1</i>"))
      n1 <- cache.size
      b <- cache(id, r, v2)(IO.pure("<i>2</i>"))
      n2 <- cache.size
    } yield (a.html, b.html, (n1, n2))).timeout(10.seconds).unsafeRunSync()

    assertEquals(a, "<i>1</i>")
    assertEquals(b, "<i>2</i>")
    assertEquals(sizes, (1, 1))
  }

  test("moved inputs with UNCHANGED byte values do not render again") {
    // The common tick: a signal-only change moves `inputs` while the
    // bytes-slots do not. Re-rendering to find identical bytes is what the
    // pre-check skips.
    val name = Some(Map("name" -> "Lamp"))
    val (a, b, renders, size) = (for {
      runs <- IO(new AtomicInteger(0))
      cache <- RenderCache.create
      count = (html: String) => IO(runs.incrementAndGet()).as(html)
      a <- cache(id, r, v1, name)(count("<i>1</i>"))
      b <- cache(id, r, v2, name)(count("<i>2</i>"))
      n <- cache.size
    } yield (a.html, b.html, runs.get(), n)).timeout(10.seconds).unsafeRunSync()

    assertEquals(renders, 1)
    // Equal byte values mean equal bytes, so the first generation's are served.
    assertEquals(a, "<i>1</i>")
    assertEquals(b, "<i>1</i>")
    assertEquals(size, 1)
  }

  test("moved inputs with MOVED byte values render, as before") {
    // The pre-check must not swallow a real change.
    val (a, b, renders) = (for {
      runs <- IO(new AtomicInteger(0))
      cache <- RenderCache.create
      count = (html: String) => IO(runs.incrementAndGet()).as(html)
      a <- cache(id, r, v1, Some(Map("name" -> "Lamp")))(count("<i>1</i>"))
      b <- cache(id, r, v2, Some(Map("name" -> "Lantern")))(count("<i>2</i>"))
    } yield (a.html, b.html, runs.get())).timeout(10.seconds).unsafeRunSync()

    assertEquals(renders, 2)
    assertEquals(a, "<i>1</i>")
    assertEquals(b, "<i>2</i>")
  }

  test("byte values are compared only when BOTH sides have them") {
    // `None` means "could not answer cheaply", never "unchanged", so either
    // side absent falls back to rendering.
    val (renders, htmls) = (for {
      runs <- IO(new AtomicInteger(0))
      cache <- RenderCache.create
      count = (html: String) => IO(runs.incrementAndGet()).as(html)
      a <- cache(id, r, v1, None)(count("<i>1</i>"))
      b <- cache(id, r, v2, None)(count("<i>2</i>"))
      c <- cache(id, r, v1, Some(Map("k" -> "v")))(count("<i>3</i>"))
    } yield (runs.get(), (a.html, b.html, c.html)))
      .timeout(10.seconds)
      .unsafeRunSync()

    assertEquals(renders, 3)
    assertEquals(htmls, ("<i>1</i>", "<i>2</i>", "<i>3</i>"))
  }

  test("a STRAGGLER with matching byte values is served without installing") {
    // Branch 2a: the straggler renders nothing, and must not install:
    // re-stamping under older inputs would hand the next caller a key that
    // looks stale.
    val vals = Some(Map("name" -> "Lamp"))
    val (renders, straggler, afterwards) = (for {
      runs <- IO(new AtomicInteger(0))
      cache <- RenderCache.create
      count = (html: String) => IO(runs.incrementAndGet()).as(html)
      _ <- cache(id, r, v2, vals)(count("<i>current</i>"))
      old <- cache(id, r, v1, vals)(count("<i>stale</i>"))
      // Had the straggler installed, this would miss and render.
      now <- cache(id, r, v2, vals)(count("<i>never runs</i>"))
    } yield (runs.get(), old.html, now.html))
      .timeout(10.seconds)
      .unsafeRunSync()

    assertEquals(renders, 1)
    assertEquals(straggler, "<i>current</i>")
    assertEquals(afterwards, "<i>current</i>")
  }

  test("a renderer swap invalidates every key, unchanged inputs included") {
    // Keyed on the renderer: a dashboard edit changes markup while entity
    // versions stay put, so inputs alone would serve the old dashboard's bytes
    // after a push.
    val (before, after, size) = (for {
      cache <- RenderCache.create
      before <- cache(id, r, v1)(IO.pure("<i>old</i>"))
      after <- cache(id, aRenderer, v1)(IO.pure("<i>new</i>"))
      size <- cache.size
    } yield (before.html, after.html, size)).timeout(10.seconds).unsafeRunSync()

    assertEquals(before, "<i>old</i>")
    assertEquals(after, "<i>new</i>")
    assertEquals(size, 1)
  }

  test("a superseded generation does not evict the one that replaced it") {
    // Evicting on a stale failure would drop a newer generation's live entry,
    // hence the identity check.
    val (n, html) = (for {
      g <- gated("unused")
      cache <- RenderCache.create
      doomed <- cache(id, r, v1)(
        g.render *> IO.raiseError[String](new RuntimeException("late"))
      ).attempt.start
      // The doomed generation must own the key first, or this tests nothing.
      _ <- g.started
      _ <- cache(id, r, v2)(IO.pure("<i>current</i>"))
      _ <- g.release
      _ <- doomed.joinWithNever
      n <- cache.size
      still <- cache(id, r, v2)(IO.pure("never runs"))
    } yield (n, still.html)).timeout(10.seconds).unsafeRunSync()

    assertEquals(n, 1)
    assertEquals(html, "<i>current</i>")
  }

  test("a failed render reaches its waiters instead of stranding them") {
    val boom = new RuntimeException("render blew up")
    val late = new AtomicInteger(0)
    val (results, n, renders) = (for {
      g <- gated("unused")
      cache <- RenderCache.create
      producer <- cache(id, r, v1)(
        g.render *> IO.raiseError[String](boom)
      ).attempt.start
      // Before the waiters exist, or one wins the CAS, renders its own ungated
      // string, and all five succeed: a real intermittent failure.
      _ <- g.started
      waiters <- List
        .fill(4)(
          cache(id, r, v1)(
            IO(late.incrementAndGet()) *> IO.raiseError[String](boom)
          ).attempt
        )
        .parSequence
        .start
      _ <- IO.sleep(150.millis) *> g.release
      p <- producer.joinWithNever
      w <- waiters.joinWithNever
      n <- cache.size
    } yield (p :: w, n, g.runs.get())).timeout(10.seconds).unsafeRunSync()

    // All complete with the error, none hang.
    assertEquals(results.length, 5)
    assert(results.forall(_.left.exists(_.getMessage == "render blew up")))
    // The key is gone, so the failure is not permanent.
    assertEquals(n, 0)
    // Only the producer's render ran. A caller arriving after eviction would
    // render and fail too, indistinguishable from waiting without `late`.
    assertEquals(late.get(), 0)
    assertEquals(renders, 1)
  }

  test("the next caller after a failure renders again and succeeds") {
    val out = (for {
      cache <- RenderCache.create
      _ <- cache(id, r, v1)(
        IO.raiseError[String](new RuntimeException("transient"))
      ).attempt
      good <- cache(id, r, v1)(IO.pure("<b>recovered</b>"))
    } yield good.html).timeout(10.seconds).unsafeRunSync()

    assertEquals(out, "<b>recovered</b>")
  }

  test("cancelling a waiter leaves the producer and the entry intact") {
    val (out, again, n) = (for {
      g <- gated("<b>survived</b>")
      cache <- RenderCache.create
      producer <- cache(id, r, v1)(g.render).start
      _ <- g.started
      waiter <- cache(id, r, v1)(IO.pure("never runs")).start
      _ <- IO.sleep(100.millis)
      _ <- waiter.cancel
      _ <- g.release
      p <- producer.joinWithNever
      again <- cache(id, r, v1)(IO.pure("never runs either"))
      n <- cache.size
    } yield (p.html, again.html, n)).timeout(10.seconds).unsafeRunSync()

    assertEquals(out, "<b>survived</b>")
    assertEquals(again, "<b>survived</b>")
    assertEquals(n, 1)
  }

  test("cancelling the PRODUCER still completes its waiters") {
    // The production path is uncancelable, so a cancelled producer finishes and
    // completes the slot before observing cancellation: no waiter is stranded
    // and no onCancel has to unblock them. Here it is parked on a latch inside
    // the masked region, the state the mask exists to survive.
    val (waited, n, renders) = (for {
      g <- gated("<b>finished anyway</b>")
      cache <- RenderCache.create
      producer <- cache(id, r, v1)(g.render).start
      _ <- g.started
      waiter <- cache(id, r, v1)(IO.pure("never runs")).start
      _ <- IO.sleep(100.millis)
      _ <- producer.cancel.start
      _ <- IO.sleep(50.millis) *> g.release
      w <- waiter.joinWithNever
      n <- cache.size
    } yield (w.html, n, g.runs.get())).timeout(10.seconds).unsafeRunSync()

    assertEquals(waited, "<b>finished anyway</b>")
    assertEquals(renders, 1)
    assertEquals(n, 1)
  }

  /** Two windows over one sensor: reads that are not comparable, as two viewers
    * on different node-variable values produce.
    */
  private def readOf(window: String): SlotRead =
    SlotRead(
      SlotQuery("history", Map("entity" -> "sensor.t", "window" -> window)),
      Transform.Stage.Passthrough
    )

  private val day =
    RenderInputs(Map("sensor.t" -> 1L), Map(readOf("24h") -> 9L))
  private val week =
    RenderInputs(Map("sensor.t" -> 1L), Map(readOf("7d") -> 9L))

  test("two windows are UNORDERED, which is what stops the wrong span") {
    // If either direction were true, the straggler rule would serve one viewer
    // the other's span, a defect rather than a cost.
    assert(!day.isAtLeast(week))
    assert(!week.isAtLeast(day))
    assert(day.isAtLeast(day))
  }

  test("viewers on two windows evict each other — a render each, every pull") {
    // ADR 0031's prediction, measured: one generation per node means
    // alternating asks never hit. The cost is renders, not retained HTML.
    val renders = new AtomicInteger(0)
    val (served, n, count) = (for {
      cache <- RenderCache.create
      one <- List(day, week, day, week).traverse { inputs =>
        val html = if (inputs == day) "<i>24h</i>" else "<i>7d</i>"
        cache(id, r, inputs)(IO(renders.incrementAndGet()).as(html))
      }
      n <- cache.generations
    } yield (one.map(_.html), n, renders.get()))
      .timeout(10.seconds)
      .unsafeRunSync()

    assertEquals(
      served,
      List("<i>24h</i>", "<i>7d</i>", "<i>24h</i>", "<i>7d</i>")
    )
    assertEquals(count, 4, "each ask rendered: neither generation survives")
    assertEquals(n, 1, "one generation per node, whatever the read")
  }

  test("viewers on the SAME window still share one render") {
    // The control: otherwise "4 renders" could mean the query half of the key
    // broke sharing outright.
    val renders = new AtomicInteger(0)
    val (served, count) = (for {
      cache <- RenderCache.create
      out <- List(day, day, day).traverse { inputs =>
        cache(id, r, inputs)(IO(renders.incrementAndGet()).as("<i>24h</i>"))
      }
    } yield (out.map(_.html).distinct, renders.get()))
      .timeout(10.seconds)
      .unsafeRunSync()

    assertEquals(served, List("<i>24h</i>"))
    assertEquals(count, 1)
  }
}
