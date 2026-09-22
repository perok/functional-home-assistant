package api.homeassistant.ws.domain

import io.circe.{Decoder, Encoder, Json}

import java.time.Instant

/** One recorder row. `state` stays a string: history carries `"unavailable"`
  * and `"unknown"` rows, and those are not the same as no value.
  */
case class HistoryPoint(state: String, at: Instant)

object HistoryPoint {

  /** `lu` is epoch SECONDS (statistics use millis); `lc` stands in when HA
    * omits the equal one.
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

  // Not via Double: `1789755910.543` would come out as `…:10.542999983Z`.
  private def epochSeconds(d: BigDecimal): Instant = {
    val whole = d.setScale(0, BigDecimal.RoundingMode.FLOOR).toLong
    Instant.ofEpochSecond(whole, ((d - whole) * 1000000000L).toLong)
  }
}

/** One long-term-statistics bucket.
  *
  * Two options rather than a sum type: HA derives them from independent flags
  * (`has_mean`, `has_sum`). They never overlapped on the instance measured, but
  * the wire format does not promise that.
  */
case class StatisticPoint(
    start: Instant,
    end: Instant,
    mean: Option[StatisticPoint.Mean],
    sum: Option[StatisticPoint.Sum]
)

object StatisticPoint {

  case class Mean(min: Double, mean: Double, max: Double)

  case class Sum(
      state: Double,
      sum: Double,
      change: Option[Double],
      lastReset: Option[Instant]
  )

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

/** A bare string on the wire: the module's `Configuration` would otherwise put
  * a `type` discriminator on it.
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
