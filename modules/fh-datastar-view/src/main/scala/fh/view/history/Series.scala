package fh.view.history

import api.homeassistant.ws.domain.{HistoryPoint, StatisticPoint}
import io.circe.{Decoder, Json}

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

  val empty: Series = Series(Vector.empty, 0)

  /** The wire form, and the third-party contract: what passthrough puts in the
    * hole and what the chart stage reads.
    *
    * `[[epochMillis, value], …]` rather than `[{t, v}, …]`: this can ride a
    * Datastar signal, where a null object FIELD deletes the signal but a null
    * array entry is a value.
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

  /** [[toJson]]'s inverse. A stage decodes the JSON rather than taking a typed
    * value because the JSON is the provider's contract.
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
