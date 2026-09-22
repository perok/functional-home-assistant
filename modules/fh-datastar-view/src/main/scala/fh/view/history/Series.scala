package fh.view.history

import api.homeassistant.ws.domain.{HistoryPoint, StatisticPoint}

import java.time.Instant

/** A numeric series ready to draw: ascending in time, already downsampled.
  *
  * No unit or name: those come from the entity's live state, and a copy here
  * could disagree with the card beside it.
  *
  * `unavailable` counts dropped non-numeric rows. The line is drawn straight
  * across them; a real gap would need nullable points through the downsampler
  * and the chart, which is not done yet.
  */
final case class Series(points: Vector[Series.Point], unavailable: Int) {
  def isEmpty: Boolean = points.isEmpty

  def oldest: Option[Instant] = points.headOption.map(_.at)

  def span: Option[java.time.Duration] =
    for {
      a <- points.headOption; b <- points.lastOption
    } yield java.time.Duration.between(a.at, b.at)
}

object Series {

  final case class Point(at: Instant, value: Double)

  val empty: Series = Series(Vector.empty, 0)

  /** Sorted here: the WS contract does not promise row order. */
  def fromHistory(rows: List[HistoryPoint]): Series = {
    val numeric = rows.flatMap(r => r.state.toDoubleOption.map(Point(r.at, _)))
    Series(
      numeric.sortBy(p => (p.at.getEpochSecond, p.at.getNano)).toVector,
      rows.size - numeric.size
    )
  }

  /** Plotted at the bucket's END, which is when its value was reached.
    *
    * `mean`, else a total sensor's `state` (the meter reading, not `sum` or
    * `change`): the same quantity [[fromHistory]] returns, so a window crossing
    * from one source to the other stays one line.
    */
  def fromStatistics(buckets: List[StatisticPoint]): Series = {
    val numeric = buckets.flatMap(b =>
      b.mean.map(_.mean).orElse(b.sum.map(_.state)).map(Point(b.end, _))
    )
    Series(
      numeric.sortBy(p => (p.at.getEpochSecond, p.at.getNano)).toVector,
      buckets.size - numeric.size
    )
  }
}
