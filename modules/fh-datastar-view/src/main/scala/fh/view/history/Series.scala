package fh.view.history

import api.homeassistant.ws.domain.{HistoryPoint, StatisticPoint}
import io.circe.{Codec, Decoder, Encoder}

import java.time.Instant

/** A numeric series ready to draw: ascending in time, already downsampled.
  *
  * No unit or name: those come from the entity's live state, and a copy here
  * could disagree with the card beside it.
  *
  * A row that is not a reading is dropped, and the line is drawn straight
  * across it. A real gap would be a null point, through the downsampler and the
  * chart, which is not done yet.
  *
  * The JSON is the history provider's answer, and the passthrough contract a
  * component's own chart library reads against; the chart stage decodes it
  * rather than taking a typed value so the two cannot drift.
  */
final case class Series(points: Vector[Series.Point]) derives Codec.AsObject {
  def isEmpty: Boolean = points.isEmpty

  def oldest: Option[Instant] = points.headOption.map(_.at)

  /** The last reading carried to `at`. The recorder writes a row only when a
    * value moves, so a sensor that held one value for the whole window answers
    * ONE row — the state at its start — and a line through one point draws
    * nothing.
    */
  def heldUntil(at: Instant): Series =
    points.lastOption match {
      case Some(last) if last.at.isBefore(at) =>
        copy(points = points :+ Series.Point(at, last.value))
      case _ => this
    }

  def span: Option[java.time.Duration] =
    for {
      a <- points.headOption; b <- points.lastOption
    } yield java.time.Duration.between(a.at, b.at)
}

object Series {

  final case class Point(at: Instant, value: Double)

  object Point {

    /** `[epochMillis, value]`: the `[x, y]` pair chart libraries take, ECharts
      * included.
      */
    given Codec[Point] = Codec.from(
      Decoder[(Long, Double)].map((at, v) =>
        Point(Instant.ofEpochMilli(at), v)
      ),
      Encoder[(Long, Double)].contramap(p => (p.at.toEpochMilli, p.value))
    )
  }

  val empty: Series = Series(Vector.empty)

  /** Sorted here: the WS contract does not promise row order. A `NaN` or
    * `Infinity` state parses as a double but is not a reading.
    */
  def fromHistory(rows: List[HistoryPoint]): Series =
    Series(
      rows
        .flatMap(r =>
          r.state.toDoubleOption.filter(_.isFinite).map(Point(r.at, _))
        )
        .sortBy(p => (p.at.getEpochSecond, p.at.getNano))
        .toVector
    )

  /** Plotted at the bucket's END, which is when its value was reached.
    *
    * `mean`, else a total sensor's `state` (the meter reading, not `sum` or
    * `change`): the same quantity [[fromHistory]] returns, so a window crossing
    * from one source to the other stays one line.
    */
  def fromStatistics(buckets: List[StatisticPoint]): Series =
    Series(
      buckets
        .flatMap(b =>
          b.mean.map(_.mean).orElse(b.sum.map(_.state)).map(Point(b.end, _))
        )
        .sortBy(p => (p.at.getEpochSecond, p.at.getNano))
        .toVector
    )
}
