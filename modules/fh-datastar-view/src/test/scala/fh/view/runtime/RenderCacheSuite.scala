package fh.view.runtime

import fh.view.testkit.TestCard
import cats.effect.unsafe.implicits.global
import fh.view.model.{Dashboard, LayoutNode, NodeId, SlotRead, Step}
import fh.view.fetch.FetchRequest
import fh.view.history.Windows
import fh.view.testkit.TestIds.given

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/** The per-slug render cache (ADR 0012): one generation per node, a failure
  * never installed.
  */
class RenderCacheSuite extends munit.FunSuite {

  /** Generations compare by identity, so only distinct instances matter. */
  private def aRenderer: Renderer =
    Renderer.create(
      Dashboard(
        Map("card" -> TestCard("<i></i>")),
        LayoutNode.Component("card")
      )
    )

  private val r = aRenderer

  private val id: NodeId = "c_0"
  private val v1 = RenderInputs(Map("sensor.t" -> 1L))
  private val v2 = RenderInputs(Map("sensor.t" -> 2L))

  private def fresh: RenderCache = RenderCache.create.unsafeRunSync()

  test("a second call for the same key does not render again") {
    val cache = fresh
    val runs = new AtomicInteger(0)
    val a = cache(id, r, v1) { runs.incrementAndGet(); "<i>1</i>" }
    val b = cache(id, r, v1) { runs.incrementAndGet(); "<i>2</i>" }

    assertEquals(runs.get(), 1)
    // The second call's bytes never ran.
    assertEquals(a.html, "<i>1</i>")
    assertEquals(b.html, "<i>1</i>")
    assertEquals(a.digest, Digest.of("<i>1</i>"))
    assertEquals(cache.size.unsafeRunSync(), 1)
  }

  test("new inputs REPLACE a node's entry rather than adding one") {
    // Every batch brings new inputs, so keyed by (node, inputs) the map would
    // grow forever for hits that never come.
    val cache = fresh
    val a = cache(id, r, v1)("<i>1</i>")
    val n1 = cache.size.unsafeRunSync()
    val b = cache(id, r, v2)("<i>2</i>")

    assertEquals(a.html, "<i>1</i>")
    assertEquals(b.html, "<i>2</i>")
    assertEquals((n1, cache.size.unsafeRunSync()), (1, 1))
  }

  test("a renderer swap invalidates every key, unchanged inputs included") {
    // Keyed on the renderer: a dashboard edit changes markup while entity
    // versions stay put, so inputs alone would serve the old dashboard's bytes
    // after a push.
    val cache = fresh
    val before = cache(id, r, v1)("<i>old</i>")
    val after = cache(id, aRenderer, v1)("<i>new</i>")

    assertEquals(before.html, "<i>old</i>")
    assertEquals(after.html, "<i>new</i>")
    assertEquals(cache.size.unsafeRunSync(), 1)
  }

  test("a failed render leaves the entry that is there") {
    val cache = fresh
    val _ = cache(id, r, v2)("<i>current</i>")
    val failed = Try(cache(id, r, v1)(throw new RuntimeException("late")))
    val still = cache(id, r, v2)("never runs")

    assert(failed.isFailure)
    assertEquals(still.html, "<i>current</i>")
    assertEquals(cache.size.unsafeRunSync(), 1)
  }

  test("the next caller after a failure renders again and succeeds") {
    val cache = fresh
    val failed = Try(cache(id, r, v1)(throw new RuntimeException("transient")))
    val good = cache(id, r, v1)("<b>recovered</b>")

    assert(failed.isFailure)
    assertEquals(good.html, "<b>recovered</b>")
  }

  /** Two windows over one sensor: reads that are not comparable, as two viewers
    * on different node-variable values produce.
    */
  private def readOf(window: String): SlotRead =
    SlotRead(
      FetchRequest.History("sensor.t", Windows.presets(window)),
      Step.Json
    )

  private val day =
    RenderInputs(Map("sensor.t" -> 1L), Map(readOf("24h") -> 9L))
  private val week =
    RenderInputs(Map("sensor.t" -> 1L), Map(readOf("7d") -> 9L))

  test("two windows are UNORDERED, which is what stops the wrong span") {
    // If either direction were true, the straggler rule would keep one
    // viewer's span where the other's belongs.
    assert(!day.isAtLeast(week))
    assert(!week.isAtLeast(day))
    assert(day.isAtLeast(day))
  }

  test("viewers on two windows evict each other — a render each, every pull") {
    // ADR 0031's prediction, measured: one generation per node means
    // alternating asks never hit. The cost is renders, not retained HTML.
    val cache = fresh
    val renders = new AtomicInteger(0)
    val served = List(day, week, day, week).map { inputs =>
      val html = if (inputs == day) "<i>24h</i>" else "<i>7d</i>"
      cache(id, r, inputs) { renders.incrementAndGet(); html }.html
    }

    assertEquals(
      served,
      List("<i>24h</i>", "<i>7d</i>", "<i>24h</i>", "<i>7d</i>")
    )
    assertEquals(renders.get(), 4, "each ask rendered: neither entry survives")
    assertEquals(cache.size.unsafeRunSync(), 1, "one entry per node")
  }

  test("viewers on the SAME window still share one render") {
    // The control: otherwise "4 renders" could mean the fetch half of the key
    // broke sharing outright.
    val cache = fresh
    val renders = new AtomicInteger(0)
    val served = List(day, day, day).map { inputs =>
      cache(id, r, inputs) { renders.incrementAndGet(); "<i>24h</i>" }.html
    }

    assertEquals(served.distinct, List("<i>24h</i>"))
    assertEquals(renders.get(), 1)
  }
}
