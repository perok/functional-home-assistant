package fh.view.history

import api.homeassistant.ws.domain.StatisticsPeriod

import java.time.Instant
import scala.concurrent.duration.*

/** How far back a chart looks, and how stale it may go.
  *
  * A closed set because `bucket` is the cache key's time part: viewers inside
  * one bucket share one fetch and one drawing. An arbitrary duration would give
  * each viewer their own key.
  *
  * `statistics` is finer than `bucket` for short windows because `hour` on a
  * one-hour window returns nothing (measured).
  */
enum Window(
    val span: FiniteDuration,
    val bucket: FiniteDuration,
    val statistics: StatisticsPeriod
) derives CanEqual {

  case LastHour extends Window(1.hour, 1.minute, StatisticsPeriod.FiveMinute)
  case LastDay extends Window(24.hours, 5.minutes, StatisticsPeriod.FiveMinute)
  case LastWeek extends Window(7.days, 1.hour, StatisticsPeriod.Hour)
  case LastMonth extends Window(30.days, 1.hour, StatisticsPeriod.Hour)

  def startAt(asOf: Instant): Instant = asOf.minusSeconds(span.toSeconds)

  def bucketOf(asOf: Instant): Instant = {
    val step = bucket.toSeconds
    Instant.ofEpochSecond(Math.floorDiv(asOf.getEpochSecond, step) * step)
  }
}

object Window {

  def byName(name: String): Option[Window] =
    values.find(_.name == name)

  extension (w: Window)
    def name: String = w match {
      case LastHour  => "1h"
      case LastDay   => "24h"
      case LastWeek  => "7d"
      case LastMonth => "30d"
    }
}
