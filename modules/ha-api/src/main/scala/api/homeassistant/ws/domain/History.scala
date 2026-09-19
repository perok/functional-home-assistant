package api.homeassistant.ws.domain

import io.circe.{Decoder, Encoder, Json}

import java.time.Instant

/** One recorder row: what an entity's state was, and when it became that.
  *
  * `state` stays a STRING because that is what the recorder stores — a numeric
  * sensor's history contains `"unavailable"` and `"unknown"` rows like any
  * other, and turning them into `None` here would lose the difference between
  * "the sensor said nothing" and "the sensor was not reporting".
  *
  * Only the shape produced by `minimal_response` + `no_attributes` is decoded,
  * because that is the only shape [[client.CommandPhase]] asks for. Without
  * those flags every row repeats the entity's full attribute map: the same 223
  * points measured 8 KB with them and 37 KB without.
  */
case class HistoryPoint(state: String, at: Instant)

object HistoryPoint {

  /** `lu` is epoch SECONDS with a fractional part — a different unit from the
    * milliseconds [[StatisticPoint]] uses, which is why neither type carries a
    * raw number. `lc` is the fallback HA's compressed encoding implies: a
    * timestamp equal to the other one may be omitted.
    */
  given Decoder[HistoryPoint] = Decoder.instance(c =>
    for {
      s <- c.get[String]("s")
      lu <- c.get[Option[BigDecimal]]("lu")
      lc <- c.get[Option[BigDecimal]]("lc")
      at <- lu
        .orElse(lc)
        .toRight(
          io.circe
            .DecodingFailure("history point has neither lu nor lc", c.history)
        )
    } yield HistoryPoint(s, epochSeconds(at))
  )

  /** `BigDecimal`, not `Double`, and the difference is visible at the precision
    * HA actually sends: `1789755910.543` is not representable as a double, so
    * going through one yields `…:10.542999983Z`. Decoding the literal keeps the
    * digits the recorder wrote.
    */
  private def epochSeconds(d: BigDecimal): Instant = {
    val whole = d.setScale(0, BigDecimal.RoundingMode.FLOOR).toLong
    Instant.ofEpochSecond(whole, ((d - whole) * 1000000000L).toLong)
  }
}

/** One long-term-statistics bucket.
  *
  * The two value groups are **separate options, not a sum type**, because HA
  * derives them from two independent flags (`has_mean`, `has_sum` in
  * `recorder/list_statistic_ids`). On a real instance they happen never to
  * overlap — 205 mean-only, 134 sum-only, 0 both, 0 neither — because a
  * recorder-sourced sensor gets them from its single `state_class`. That is an
  * observation about one instance's sensors, though, not a guarantee the wire
  * format makes, so a point that carried both would keep both here rather than
  * silently lose half.
  *
  * Grouped rather than six loose `Option[Double]`s so a consumer that wants the
  * mean band gets all three bounds or none, which is the only way they are
  * meaningful.
  */
case class StatisticPoint(
    start: Instant,
    end: Instant,
    mean: Option[StatisticPoint.Mean],
    sum: Option[StatisticPoint.Sum]
)

object StatisticPoint {

  /** A `measurement` sensor's bucket: the band the value moved through. */
  case class Mean(min: Double, mean: Double, max: Double)

  /** A `total`/`total_increasing` sensor's bucket. `lastReset` is the meter's
    * own reset point, which is what makes `sum` comparable across a
    * replacement; `change` is the bucket's own delta.
    */
  case class Sum(
      state: Double,
      sum: Double,
      change: Option[Double],
      lastReset: Option[Instant]
  )

  /** Unlike the history feed, these timestamps are epoch MILLISECONDS. */
  private def epochMillis(c: io.circe.ACursor, key: String) =
    c.get[Long](key).map(Instant.ofEpochMilli)

  given Decoder[StatisticPoint] = Decoder.instance { c =>
    for {
      start <- epochMillis(c, "start")
      end <- epochMillis(c, "end")
      min <- c.get[Option[Double]]("min")
      mean <- c.get[Option[Double]]("mean")
      max <- c.get[Option[Double]]("max")
      state <- c.get[Option[Double]]("state")
      total <- c.get[Option[Double]]("sum")
      change <- c.get[Option[Double]]("change")
      reset <- c.get[Option[Long]]("last_reset")
      meanGroup =
        for { lo <- min; mid <- mean; hi <- max } yield Mean(lo, mid, hi)
      sumGroup =
        for { s <- state; t <- total } yield Sum(
          s,
          t,
          change,
          reset.map(Instant.ofEpochMilli)
        )
      point <- Either.cond(
        meanGroup.isDefined || sumGroup.isDefined,
        StatisticPoint(start, end, meanGroup, sumGroup),
        io.circe.DecodingFailure(
          "statistics bucket carries neither a mean band nor a sum",
          c.history
        )
      )
    } yield point
  }
}

/** The bucket size `recorder/statistics_during_period` aggregates into.
  *
  * Encoded as the bare string HA expects, not as a discriminated object: this
  * is a command FIELD, and the module's `Configuration` puts a `type`
  * discriminator on derived sum types.
  */
enum StatisticsPeriod(val wire: String) {
  case FiveMinute extends StatisticsPeriod("5minute")
  case Hour extends StatisticsPeriod("hour")
  case Day extends StatisticsPeriod("day")
  case Week extends StatisticsPeriod("week")
  case Month extends StatisticsPeriod("month")
  case Year extends StatisticsPeriod("year")
}

object StatisticsPeriod {
  given Encoder[StatisticsPeriod] =
    Encoder.instance(p => Json.fromString(p.wire))
}
