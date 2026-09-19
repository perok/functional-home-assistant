package fh.view.history

import fh.view.model.{CardDef, Dashboard, LayoutNode, Reads, SeriesRead, SlotSource}
import fh.view.runtime.RenderInputs

import java.time.Instant

/** What a node that reads a series contributes to the pipeline: nothing to
  * candidacy, everything to the render key.
  */
class SeriesRenderInputsSuite extends munit.FunSuite {

  private val now = Instant.parse("2026-09-19T12:07:30Z")

  private def chartNode(window: String = "24h") =
    LayoutNode.Component(
      card = "historyChart",
      slots = Map(
        "entity_id" -> SlotSource(literal = Some("sensor.t")),
        "chart" -> SlotSource(reads = Reads.OnRender, series = Some(window)),
        "name" -> SlotSource(transform = "state")
      )
    )

  test("a series slot does not make its node a live candidate") {
    // The claim the whole design rests on: a chart is not re-rendered by a
    // state tick, so a sensor moving every second does not re-fetch its own
    // history every second. It costs nothing to get right because a series
    // slot is `onRender`, which `liveEntities` already filters out — but
    // nothing said so, and the next person to add a `reads` mode would not
    // know this was load-bearing.
    val node = chartNode()
    assertEquals(node.liveEntities, List("sensor.t")) // from `name`, not `chart`
    assertEquals(
      LayoutNode
        .Component(
          card = "historyChart",
          slots = Map(
            "entity_id" -> SlotSource(literal = Some("sensor.t")),
            "chart" -> SlotSource(reads = Reads.OnRender, series = Some("24h"))
          )
        )
        .liveEntities,
      Nil
    )
  }

  test("a series slot IS a series read, inheriting the subject") {
    assertEquals(chartNode().seriesReads, List(SeriesRead("sensor.t", "24h")))
  }

  test("a slot naming its own entity reads that one") {
    val node = LayoutNode.Component(
      card = "historyChart",
      slots = Map(
        "entity_id" -> SlotSource(literal = Some("sensor.a")),
        "chart" -> SlotSource(
          entityId = Some("sensor.b"),
          reads = Reads.OnRender,
          series = Some("7d")
        )
      )
    )
    assertEquals(node.seriesReads, List(SeriesRead("sensor.b", "7d")))
  }

  test("two slots on one window and entity are one read") {
    val node = LayoutNode.Component(
      card = "twoCharts",
      slots = Map(
        "entity_id" -> SlotSource(literal = Some("sensor.t")),
        "a" -> SlotSource(reads = Reads.OnRender, series = Some("1h")),
        "b" -> SlotSource(reads = Reads.OnRender, series = Some("1h")),
        "c" -> SlotSource(reads = Reads.OnRender, series = Some("30d"))
      )
    )
    assertEquals(
      node.seriesReads.toSet,
      Set(SeriesRead("sensor.t", "1h"), SeriesRead("sensor.t", "30d"))
    )
  }

  // --- Buckets -------------------------------------------------------------

  test("a read's bucket is its window's floor of now") {
    val reads = List(SeriesRead("sensor.t", "24h"), SeriesRead("sensor.t", "1h"))
    val buckets = SeriesBuckets.at(reads, now).buckets
    // 24h floors to 5 minutes, 1h to one minute.
    assertEquals(buckets(reads(0)), Instant.parse("2026-09-19T12:05:00Z"))
    assertEquals(buckets(reads(1)), Instant.parse("2026-09-19T12:07:00Z"))
  }

  test("an unknown window contributes no bucket rather than raising") {
    // `validate` is what rejects one; a render is the wrong place to find out.
    assertEquals(SeriesBuckets.at(List(SeriesRead("s.t", "nope")), now).buckets, Map.empty)
  }

  test("a read with no bucket is absent from the key, not zero") {
    // Absent and zero are different renders: one happened before the series
    // arrived, the other after it arrived empty. Collapsing them would serve
    // the empty chart to someone whose data had landed.
    val read = SeriesRead("sensor.t", "24h")
    assertEquals(SeriesBuckets.none.forReads(List(read)), Map.empty)
    assertEquals(
      SeriesBuckets.at(List(read), now).forReads(List(read)),
      Map(read -> Instant.parse("2026-09-19T12:05:00Z").getEpochSecond)
    )
  }

  // --- The partial order ---------------------------------------------------

  private def key(series: (SeriesRead, Long)*) =
    RenderInputs(Map("sensor.t" -> 1L), series.toMap)

  private val read24 = SeriesRead("sensor.t", "24h")
  private val read1 = SeriesRead("sensor.t", "1h")

  test("a later bucket is ahead of an earlier one") {
    assert(key(read24 -> 200L).isAtLeast(key(read24 -> 100L)))
    assert(!key(read24 -> 100L).isAtLeast(key(read24 -> 200L)))
  }

  test("the same bucket is at least itself, so an equal render still installs") {
    assert(key(read24 -> 100L).isAtLeast(key(read24 -> 100L)))
  }

  test("different windows are not ordered, so neither overwrites the other") {
    // Two viewers on different windows read different series. Unordered means
    // separate generations — the alternative is one of them being served a
    // chart of the wrong span, which is the silent failure `RenderInputs`
    // warns about in its own doc.
    assert(!key(read24 -> 100L).isAtLeast(key(read1 -> 100L)))
    assert(!key(read1 -> 100L).isAtLeast(key(read24 -> 100L)))
  }

  test("gaining a series read is not being ahead") {
    assert(!key(read24 -> 100L).isAtLeast(key()))
    assert(!key().isAtLeast(key(read24 -> 100L)))
  }

  test("the state half still decides on its own when no series is read") {
    assert(
      RenderInputs(Map("sensor.t" -> 2L)).isAtLeast(RenderInputs(Map("sensor.t" -> 1L)))
    )
    assert(
      !RenderInputs(Map("sensor.t" -> 1L)).isAtLeast(RenderInputs(Map("sensor.t" -> 2L)))
    )
  }

  test("a moved series alone moves the key, with state standing still") {
    // The point of the whole field: a chart whose bucket rolled must not be
    // served from the generation rendered in the previous one.
    assertNotEquals(key(read24 -> 100L), key(read24 -> 200L))
  }

  // --- Validation ----------------------------------------------------------

  private def dashboard(slots: Map[String, SlotSource]) =
    Dashboard(
      cards = Map("chart" -> CardDef("""<div id="{{id}}">{{chart}}</div>""")),
      card = LayoutNode.Component(card = "chart", slots = slots)
    )

  test("an unknown window is rejected, and names the ones that exist") {
    // Silent otherwise: no window, no bucket, so the slot renders empty
    // forever and never enters the render key — a blank chart with nothing
    // anywhere saying why.
    val errs = dashboard(
      Map(
        "entity_id" -> SlotSource(literal = Some("sensor.t")),
        "chart" -> SlotSource(reads = Reads.OnRender, series = Some("last-week"))
      )
    ).validate()
    assert(errs.exists(_.contains("unknown window 'last-week'")), clue = errs)
    assert(errs.exists(_.contains("24h")), clue = errs)
  }

  test("a series slot with no entity anywhere is rejected") {
    val errs = dashboard(
      Map("chart" -> SlotSource(reads = Reads.OnRender, series = Some("24h")))
    ).validate()
    assert(errs.exists(_.contains("names no entity")), clue = errs)
  }

  test("a slot naming its own entity needs no subject") {
    val errs = dashboard(
      Map(
        "chart" -> SlotSource(
          entityId = Some("sensor.b"),
          reads = Reads.OnRender,
          series = Some("24h")
        )
      )
    ).validate()
    assert(!errs.exists(_.contains("names no entity")), clue = errs)
  }
}
