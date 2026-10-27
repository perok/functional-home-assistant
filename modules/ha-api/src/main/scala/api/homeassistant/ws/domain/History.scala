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

/** One long-term-statistics bucket. A value is present when the call asked for
  * its type ([[StatisticType]]) and the statistic has it: a measurement has
  * `mean`/`min`/`max`, a total `state`/`sum`/`change`. Each is its own option,
  * because a call may ask for any subset.
  */
case class StatisticPoint(
    start: Instant,
    end: Instant,
    mean: Option[Double] = None,
    min: Option[Double] = None,
    max: Option[Double] = None,
    state: Option[Double] = None,
    sum: Option[Double] = None,
    change: Option[Double] = None,
    lastReset: Option[Instant] = None
)

object StatisticPoint {

  private def epochMillis(c: io.circe.ACursor, key: String) =
    c.get[Long](key).map(Instant.ofEpochMilli)

  given Decoder[StatisticPoint] = Decoder.instance { c =>
    for {
      start <- epochMillis(c, "start")
      end <- epochMillis(c, "end")
      mean <- c.get[Option[Double]]("mean")
      min <- c.get[Option[Double]]("min")
      max <- c.get[Option[Double]]("max")
      state <- c.get[Option[Double]]("state")
      sum <- c.get[Option[Double]]("sum")
      change <- c.get[Option[Double]]("change")
      reset <- c.get[Option[Long]]("last_reset")
    } yield StatisticPoint(
      start,
      end,
      mean,
      min,
      max,
      state,
      sum,
      change,
      reset.map(Instant.ofEpochMilli)
    )
  }
}

/** What a statistics call asks for, spelled HA's way. HA answers every type
  * when none is named.
  */
enum StatisticType(val wire: String) {
  case Change extends StatisticType("change")
  case LastReset extends StatisticType("last_reset")
  case Max extends StatisticType("max")
  case Mean extends StatisticType("mean")
  case Min extends StatisticType("min")
  case State extends StatisticType("state")
  case Sum extends StatisticType("sum")
}

object StatisticType {
  given Encoder[StatisticType] = Encoder.instance(t => Json.fromString(t.wire))
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
