package fh.view.history

import api.homeassistant.ws.domain.{HistoryPoint, StatisticPoint}

import java.time.Instant

/** A numeric series ready to draw: ascending in time, already downsampled.
  *
  * No unit and no name. Those come from the entity's own state, which the
  * render path already has — carrying a copy here would be a second source for
  * a fact that moves (a user renaming an entity) and could disagree with the
  * card beside it.
  *
  * `unavailable` counts the rows dropped for not being numbers. HA's recorder
  * stores `"unavailable"` and `"unknown"` as ordinary rows, so a sensor that
  * dropped out for an hour produces a series that simply skips that hour — the
  * line is drawn straight across it. That is a real loss of meaning, and the
  * count is here so a card can say so rather than the gap being invisible.
  * Representing it as a true gap needs nullable points through the downsampler
  * and into the chart option object; it is deliberately not done yet.
  */
final case class Series(points: Vector[Series.Point], unavailable: Int) {
  def isEmpty: Boolean = points.isEmpty

  /** The oldest point, which is what says how far back the data actually goes
    * — as opposed to how far back it was asked to go.
    */
  def oldest: Option[Instant] = points.headOption.map(_.at)

  /** How much time the data actually covers. `None` when there is nothing to
    * measure; zero for a single point.
    */
  def span: Option[java.time.Duration] =
    for { a <- points.headOption; b <- points.lastOption }
      yield java.time.Duration.between(a.at, b.at)
}

object Series {

  final case class Point(at: Instant, value: Double)

  val empty: Series = Series(Vector.empty, 0)

  /** Raw recorder rows. Non-numeric states are dropped and counted; ordering is
    * imposed rather than assumed, because nothing in the WS contract promises
    * it and a chart drawn from unsorted points is nonsense rather than an
    * error.
    */
  def fromHistory(rows: List[HistoryPoint]): Series = {
    val numeric = rows.flatMap(r => r.state.toDoubleOption.map(Point(r.at, _)))
    Series(numeric.sortBy(p => (p.at.getEpochSecond, p.at.getNano)).toVector, rows.size - numeric.size)
  }

  /** Statistics buckets, read at the bucket's END: a bucket describes the
    * interval `[start, end)`, and its value is what the sensor had done BY
    * `end`, so plotting it at `start` shifts every point one bucket early.
    *
    * `mean` where there is one, otherwise the `state` of a total sensor — the
    * meter READING, not its `sum` or `change`. That is the quantity the
    * entity's own state carries and the quantity [[fromHistory]] returns for
    * the same sensor, so a window crossing from one source to the other plots
    * one continuous line rather than two unrelated ones.
    */
  def fromStatistics(buckets: List[StatisticPoint]): Series = {
    val numeric = buckets.flatMap(b =>
      b.mean.map(_.mean).orElse(b.sum.map(_.state)).map(Point(b.end, _))
    )
    Series(numeric.sortBy(p => (p.at.getEpochSecond, p.at.getNano)).toVector, buckets.size - numeric.size)
  }
}
