package fh.view.history

import api.homeassistant.ws.domain.{HistoryPoint, StatisticPoint}

import io.github.iltotore.iron.*

import java.time.Instant

/** The pure half of the series pipeline: what a window means, what a recorder
  * answer turns into, and what survives downsampling.
  */
class SeriesSuite extends munit.FunSuite {

  private val t0 = Instant.parse("2026-09-19T12:00:00Z")
  private def at(secs: Long) = t0.plusSeconds(secs)

  test("a bucket is floored, so the key does not move under a request") {
    val w = Window.Raw(86400L) // 2-minute bucket
    assertEquals(
      w.bucketOf(Instant.parse("2026-09-19T12:03:59Z")),
      Instant.parse("2026-09-19T12:02:00Z")
    )
    assertEquals(
      w.bucketOf(Instant.parse("2026-09-19T12:04:00Z")),
      Instant.parse("2026-09-19T12:04:00Z")
    )
  }

  test("a statistics bucket rolls a minute after its period ends") {
    // HA writes the 12:00–12:05 row seconds after 12:05; a bucket rolling at
    // 12:05 sharp would cache a series without it until 12:10.
    val w = Windows.day // 5-minute statistics
    assertEquals(
      w.bucketOf(Instant.parse("2026-09-19T12:05:59Z")),
      Instant.parse("2026-09-19T12:01:00Z")
    )
    assertEquals(
      w.bucketOf(Instant.parse("2026-09-19T12:06:00Z")),
      Instant.parse("2026-09-19T12:06:00Z")
    )
  }

  test("a non-numeric row is dropped, not charted as zero") {
    val s = Series.fromHistory(
      List(
        HistoryPoint("1.5", at(0)),
        HistoryPoint("unavailable", at(1)),
        HistoryPoint("unknown", at(2)),
        HistoryPoint("2.5", at(3))
      )
    )
    assertEquals(s.points.map(_.value), Vector(1.5, 2.5))
  }

  test("a non-finite state is dropped, like a non-numeric one") {
    // Each parses as a Double, and would reach the wire as a null.
    val s = Series.fromHistory(
      List(
        HistoryPoint("NaN", at(0)),
        HistoryPoint("Infinity", at(1)),
        HistoryPoint("1e400", at(2)),
        HistoryPoint("2.5", at(3))
      )
    )
    assertEquals(s.points.map(_.value), Vector(2.5))
  }

  test("ordering is imposed, not assumed") {
    val s = Series.fromHistory(
      List(HistoryPoint("2", at(10)), HistoryPoint("1", at(0)))
    )
    assertEquals(s.points.map(_.at), Vector(at(0), at(10)))
  }

  test("a statistics bucket is plotted at its END") {
    // A bucket describes [start, end) and its value is what the sensor had
    // done BY end. Plotting at `start` shifts the whole line one bucket early,
    // which is invisible on its own and obvious next to a raw series.
    val s = Series.fromStatistics(
      List(
        StatisticPoint(at(0), at(3600), mean = Some(2.0))
      )
    )
    assertEquals(s.points, Vector(Series.Point(at(3600), 2.0)))
  }

  test("a total sensor charts its meter READING, like its own state does") {
    // `state`, not `sum` and not `change`: that is the quantity the entity's
    // live state carries and the quantity `fromHistory` returns for the same
    // sensor, so a window crossing from one source to the other stays one
    // continuous line.
    val s = Series.fromStatistics(
      List(
        StatisticPoint(
          at(0),
          at(3600),
          state = Some(6.87),
          sum = Some(77770.16),
          change = Some(2.86)
        )
      )
    )
    assertEquals(s.points.map(_.value), Vector(6.87))
  }

  test("a value held for the whole window still draws a line") {
    // The recorder writes a row only when a value moves, so an hour of a
    // sensor sitting at 0 answers ONE row, and one point draws nothing.
    val held =
      Series.fromHistory(List(HistoryPoint("0.0", at(0)))).heldUntil(at(3600))
    assertEquals(
      held.points,
      Vector(Series.Point(at(0), 0.0), Series.Point(at(3600), 0.0))
    )
    // Nothing to carry, and nothing past the end.
    assertEquals(Series.empty.heldUntil(at(3600)), Series.empty)
    assertEquals(held.heldUntil(at(3600)), held)
  }

  private def ramp(n: Int): Vector[Series.Point] =
    (0 until n).map(i => Series.Point(at(i.toLong), i.toDouble)).toVector

  test("a series at or below the target is returned untouched") {
    val small = ramp(50)
    assertEquals(Downsample.lttb(small, 300), small)
    assertEquals(Downsample.lttb(ramp(300), 300), ramp(300))
  }

  test("downsampling hits the target exactly") {
    assertEquals(Downsample.lttb(ramp(5000), 300).length, 300)
    assertEquals(Downsample.lttb(ramp(301), 300).length, 300)
  }

  test("the ends are kept, so the axis ends where the data does") {
    val input = ramp(5000)
    val out = Downsample.lttb(input, 300)
    assertEquals(out.head, input.head)
    assertEquals(out.last, input.last)
  }

  test("the output stays in time order") {
    val out = Downsample.lttb(ramp(5000), 300)
    assert(out.sliding(2).forall {
      case Vector(a, b) => a.at.isBefore(b.at)
      case _            => true
    })
  }

  test("a single spike survives, which uniform sampling would drop") {
    // The whole reason for LTTB over `every Nth`: the spike is at an index no
    // uniform stride lands on.
    val flat = (0 until 2000).map(i => Series.Point(at(i.toLong), 1.0)).toVector
    val spikeAt = 977
    val input = flat.updated(spikeAt, Series.Point(at(spikeAt.toLong), 99.0))
    val out = Downsample.lttb(input, 100)
    assert(out.exists(_.value == 99.0), clue = out.map(_.value).max)
  }

  test("a degenerate target degrades rather than throwing") {
    assertEquals(Downsample.lttb(ramp(100), 0), ramp(100))
    assertEquals(Downsample.lttb(ramp(100), 2), ramp(100))
    assertEquals(Downsample.lttb(Vector.empty, 300), Vector.empty)
  }
}
