package fh.view.runtime

import fh.view.testkit.TestCard
import cats.effect.unsafe.implicits.global
import fh.view.model.{Dashboard, LayoutNode, NodeId, SlotKey, SlotValue}
import fh.view.testkit.TestIds.given

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/** The per-slug render cache (ADR 0012): one entry per node, a failure never
  * installed.
  */
class RenderCacheSuite extends munit.FunSuite {

  /** Renderers compare by identity, so only distinct instances matter. */
  private def aRenderer: Renderer =
    Renderer.create(
      Dashboard(
        Map("card" -> TestCard("<i></i>")),
        LayoutNode.Component("card")
      )
    )

  private val r = aRenderer

  private val id: NodeId = "c_0"
  private def of(slots: (SlotKey, SlotValue)*) = ByteValues(slots.toMap, None)
  private val v1 = of("v" -> "1")
  private val v2 = of("v" -> "2")

  private def fresh: RenderCache = RenderCache.create.unsafeRunSync()

  test("a second call for the same key does not render again") {
    val cache = fresh
    val runs = new AtomicInteger(0)
    val a = cache(id, r, v1) { runs.incrementAndGet(); "<i>1</i>" }
    val b = cache(id, r, v1) { runs.incrementAndGet(); "<i>2</i>" }

    assertEquals(runs.get(), 1)
    // The second call's bytes never ran.
    assertEquals(a, "<i>1</i>")
    assertEquals(b, "<i>1</i>")
    assertEquals(cache.size.unsafeRunSync(), 1)
  }

  test("new values REPLACE a node's entry rather than adding one") {
    // Keyed by (node, values), the map would grow with every value a node
    // ever showed.
    val cache = fresh
    val a = cache(id, r, v1)("<i>1</i>")
    val n1 = cache.size.unsafeRunSync()
    val b = cache(id, r, v2)("<i>2</i>")

    assertEquals(a, "<i>1</i>")
    assertEquals(b, "<i>2</i>")
    assertEquals((n1, cache.size.unsafeRunSync()), (1, 1))
  }

  test("a renderer swap invalidates every key, unchanged values included") {
    // Keyed on the renderer: a dashboard edit changes markup while the values
    // stay put, so values alone would serve the old dashboard's bytes after a
    // push.
    val cache = fresh
    val before = cache(id, r, v1)("<i>old</i>")
    val after = cache(id, aRenderer, v1)("<i>new</i>")

    assertEquals(before, "<i>old</i>")
    assertEquals(after, "<i>new</i>")
    assertEquals(cache.size.unsafeRunSync(), 1)
  }

  test("a failed render leaves the entry that is there") {
    val cache = fresh
    val _ = cache(id, r, v2)("<i>current</i>")
    val failed = Try(cache(id, r, v1)(throw new RuntimeException("late")))
    val still = cache(id, r, v2)("never runs")

    assert(failed.isFailure)
    assertEquals(still, "<i>current</i>")
    assertEquals(cache.size.unsafeRunSync(), 1)
  }

  test("the next caller after a failure renders again and succeeds") {
    val cache = fresh
    val failed = Try(cache(id, r, v1)(throw new RuntimeException("transient")))
    val good = cache(id, r, v1)("<b>recovered</b>")

    assert(failed.isFailure)
    assertEquals(good, "<b>recovered</b>")
  }

  /** Two viewers on two windows over one sensor: the chart slot's value is each
    * one's answer.
    */
  private val day = of("chart" -> "<svg>24h</svg>")
  private val week = of("chart" -> "<svg>7d</svg>")

  test("viewers on two windows evict each other — a render each, every pull") {
    // ADR 0031's prediction, measured: one generation per node means
    // alternating asks never hit. The cost is renders, not retained HTML.
    val cache = fresh
    val renders = new AtomicInteger(0)
    val served = List(day, week, day, week).map { inputs =>
      val html = if (inputs == day) "<i>24h</i>" else "<i>7d</i>"
      cache(id, r, inputs) { renders.incrementAndGet(); html }
    }

    assertEquals(
      served,
      List("<i>24h</i>", "<i>7d</i>", "<i>24h</i>", "<i>7d</i>")
    )
    assertEquals(renders.get(), 4, "each ask rendered: neither entry survives")
    assertEquals(cache.size.unsafeRunSync(), 1, "one entry per node")
  }

  test("viewers on the SAME window still share one render") {
    // The control: otherwise "4 renders" could mean the key broke sharing
    // outright.
    val cache = fresh
    val renders = new AtomicInteger(0)
    val served = List(day, day, day).map { inputs =>
      cache(id, r, inputs) { renders.incrementAndGet(); "<i>24h</i>" }
    }

    assertEquals(served.distinct, List("<i>24h</i>"))
    assertEquals(renders.get(), 1)
  }

  /** Entries of a node the new dashboard no longer has are never rendered
    * again, so only the swap can drop them.
    */
  test("a swap empties the cache, and no entry holds its renderer") {
    val live =
      Server.LiveSlug.of(Server.RendererState.Ready(r)).unsafeRunSync()
    val _ = live.cache(id, r, v1)("<i>1</i>")
    val _ = live.cache("c_gone", r, v1)("<i>gone</i>")
    live.install(Server.RendererState.Ready(aRenderer)).unsafeRunSync()
    assertEquals(live.cache.size.unsafeRunSync(), 0)

    val entry = RenderCache.Entry(r.generation, v1, "<i>1</i>")
    assert(!entry.productIterator.exists(_.isInstanceOf[Renderer]))
  }
}
