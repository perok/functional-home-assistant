package fh.view.runtime

import cats.effect.IO
import cats.effect.std.CountDownLatch
import cats.effect.unsafe.implicits.global
import cats.syntax.parallel.*
import fh.view.model.{Dashboard, LayoutNode, NodeId}
import fh.view.testkit.TestIds.given

import java.util.concurrent.atomic.AtomicInteger
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

  private val id: NodeId = "c_0"
  private val v1 = RenderInputs(Map("sensor.t" -> 1L))
  private val v2 = RenderInputs(Map("sensor.t" -> 2L))

  /** A render that counts its runs and suspends until the test releases it — so
    * waiters genuinely pile up behind a producer rather than arriving after it
    * finished, which would make single-flight untestable.
    *
    * '''It holds no thread.''' The latch is the cats-effect one, so a producer
    * parked here is a suspended fiber. It used to be a
    * `java.util.concurrent.CountDownLatch` awaited inside a by-name `String`,
    * which parked a compute worker for the length of the test — and with sbt
    * running suites in parallel that starved the fibers the test was waiting
    * for, timing out about one run in ten.
    *
    * That fixture was also the standing disproof of what `RenderCache`'s doc
    * used to claim for its by-name thunk: it blocked, from inside a
    * `=> String`, and neither the type nor the runtime could tell.
    */
  private class Gated(html: String, latch: CountDownLatch[IO]) {
    val runs = new AtomicInteger(0)

    def render: IO[String] = IO(runs.incrementAndGet()) *> latch.await.as(html)
    def release: IO[Unit] = latch.release

    /** Both fibers race for the same key, and the one a test calls "the waiter"
      * can perfectly well win the CAS and render ITS string. Waiting for the
      * producer to be inside `render` is what makes which-is-which a fact
      * rather than a hope.
      *
      * It SLEEPS rather than spinning: `iterateUntil` on a pure `IO` never
      * yields, so on a small pool it can starve the very fiber it waits for.
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
    // The thread-C case, and the common tick: a signal-only change moves the
    // entity's contentVersion — so `inputs` move — while the slots that travel
    // as BYTES do not. Re-rendering to discover the bytes are identical is
    // exactly what the pre-check exists to skip.
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
    // The FIRST generation's bytes, not a re-render: equal byte values mean
    // equal bytes, so the second call is served what the first produced.
    assertEquals(a, "<i>1</i>")
    assertEquals(b, "<i>1</i>")
    assertEquals(size, 1)
  }

  test("moved inputs with MOVED byte values render, as before") {
    // The guard on the test above: the pre-check must not swallow a real
    // change. Same moved inputs, different byte values.
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
    // `None` means "could not answer cheaply", never "unchanged" — a node with
    // a dynamic subject, or one under a bake selection. Either side absent must
    // fall back to the old behaviour rather than treat two unknowns as equal.
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
    // Branch 2a. The straggler is owed the same bytes — equal values, equal
    // bytes — so it renders nothing. But it must not INSTALL: re-stamping the
    // entry under its older inputs would downgrade the generation and hand the
    // next caller a key that looks stale, which is the eviction the straggler
    // rule exists to prevent. Proven by the newer inputs still hitting after.
    val vals = Some(Map("name" -> "Lamp"))
    val (renders, straggler, afterwards) = (for {
      runs <- IO(new AtomicInteger(0))
      cache <- RenderCache.create
      count = (html: String) => IO(runs.incrementAndGet()).as(html)
      _ <- cache(id, r, v2, vals)(count("<i>current</i>"))
      // Arrives late, from an older snapshot.
      old <- cache(id, r, v1, vals)(count("<i>stale</i>"))
      // If the straggler had installed, this would be a miss and render.
      now <- cache(id, r, v2, vals)(count("<i>never runs</i>"))
    } yield (runs.get(), old.html, now.html))
      .timeout(10.seconds)
      .unsafeRunSync()

    assertEquals(renders, 1)
    assertEquals(straggler, "<i>current</i>")
    assertEquals(afterwards, "<i>current</i>")
  }

  test("a renderer swap invalidates every key, unchanged inputs included") {
    // The reason the generation is the RENDERER and not the inputs alone: a
    // dashboard edit changes the markup while the entity versions it reads stay
    // exactly where they were. Keyed on inputs only, the first viewer after a
    // push would be served the OLD dashboard's bytes, and would keep them until
    // some entity happened to move.
    val (before, after, size) = (for {
      cache <- RenderCache.create
      before <- cache(id, r, v1)(IO.pure("<i>old</i>"))
      after <- cache(id, aRenderer, v1)(IO.pure("<i>new</i>"))
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
    val (n, html) = (for {
      g <- gated("unused")
      cache <- RenderCache.create
      doomed <- cache(id, r, v1)(
        g.render *> IO.raiseError[String](new RuntimeException("late"))
      ).attempt.start
      // The doomed generation must OWN the key before the newer one takes it,
      // or this tests nothing.
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
      // One producer that fails, four waiters queued behind it.
      producer <- cache(id, r, v1)(
        g.render *> IO.raiseError[String](boom)
      ).attempt.start
      // BEFORE the waiters exist. Without it they race the producer for the
      // key, and a "waiter" that wins the CAS renders its own string — which
      // completes at once, where the producer's is gated — so all five succeed
      // and the failure under test never happens. That was a real intermittent
      // failure, not a hypothetical.
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
      // The cancelled waiter must not have taken the entry with it.
      again <- cache(id, r, v1)(IO.pure("never runs either"))
      n <- cache.size
    } yield (p.html, again.html, n)).timeout(10.seconds).unsafeRunSync()

    assertEquals(out, "<b>survived</b>")
    // The later caller HIT the entry the cancelled waiter was waiting on.
    assertEquals(again, "<b>survived</b>")
    assertEquals(n, 1)
  }

  test("cancelling the PRODUCER still completes its waiters") {
    // The invariant the whole design rests on. Production is uncancelable, so a
    // cancelled producer finishes its render and completes the slot before it
    // observes the cancellation — waiters cannot be stranded, and no onCancel
    // has to remember to unblock them.
    //
    // Sharper now that the render is an `IO`: the producer is parked on a latch
    // INSIDE the masked region, which is the state the mask exists to survive,
    // and one a by-name thunk could only reach by blocking a thread.
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
}
