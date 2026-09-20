package fh.view.history

import api.homeassistant.ws.domain.{HistoryPoint, StatisticPoint}
import io.circe.{Decoder, Json}

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

  /** The oldest point, which is what says how far back the data actually goes —
    * as opposed to how far back it was asked to go.
    */
  def oldest: Option[Instant] = points.headOption.map(_.at)

  /** How much time the data actually covers. `None` when there is nothing to
    * measure; zero for a single point.
    */
  def span: Option[java.time.Duration] =
    for {
      a <- points.headOption; b <- points.lastOption
    } yield java.time.Duration.between(a.at, b.at)
}

object Series {

  final case class Point(at: Instant, value: Double)

  val empty: Series = Series(Vector.empty, 0)

  /** The wire form of a series, and the whole third-party contract: what a
    * passthrough transform puts in the hole, and what the chart stage consumes.
    *
    * `[[epochMillis, value], …]` rather than `[{t, v}, …]`, and that is a rule
    * rather than a preference. A gap in recorder data is genuinely null-valued,
    * and this payload can ride a Datastar signal — where a JSON null DELETES
    * the signal, orphaning every binding on it with no error anywhere. As a
    * positional array entry a null is a value; as an object field it is a
    * deletion.
    *
    * Milliseconds because that is what the chart option already uses, so the
    * passthrough consumer and the built-in drawing read the same numbers.
    */
  def toJson(s: Series): Json =
    Json.obj(
      "points" -> Json.arr(
        s.points.map(p =>
          Json.arr(
            Json.fromLong(p.at.toEpochMilli),
            Json.fromDoubleOrNull(p.value)
          )
        )*
      ),
      "unavailable" -> Json.fromInt(s.unavailable)
    )

  /** [[toJson]]'s inverse, for a stage reading back what a provider answered.
    *
    * The round trip is deliberate rather than an oversight: the provider's
    * contract IS the JSON, so a stage that decoded something else would be
    * reading a payload no third party could produce. It costs one decode per
    * drawing, which the stage cache makes once per bucket per style.
    */
  given Decoder[Series] = Decoder.instance { c =>
    for {
      pts <- c.get[Vector[(Long, Option[Double])]]("points")
      un <- c.getOrElse[Int]("unavailable")(0)
    } yield Series(
      pts.collect { case (at, Some(v)) =>
        Point(Instant.ofEpochMilli(at), v)
      },
      un
    )
  }

  /** Raw recorder rows. Non-numeric states are dropped and counted; ordering is
    * imposed rather than assumed, because nothing in the WS contract promises
    * it and a chart drawn from unsorted points is nonsense rather than an
    * error.
    */
  def fromHistory(rows: List[HistoryPoint]): Series = {
    val numeric = rows.flatMap(r => r.state.toDoubleOption.map(Point(r.at, _)))
    Series(
      numeric.sortBy(p => (p.at.getEpochSecond, p.at.getNano)).toVector,
      rows.size - numeric.size
    )
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
    Series(
      numeric.sortBy(p => (p.at.getEpochSecond, p.at.getNano)).toVector,
      buckets.size - numeric.size
    )
  }
}
