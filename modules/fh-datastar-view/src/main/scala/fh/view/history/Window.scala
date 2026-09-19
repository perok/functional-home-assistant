package fh.view.history

import api.homeassistant.ws.domain.StatisticsPeriod

import java.time.Instant
import scala.concurrent.duration.*

/** How far back a chart looks, and how coarsely it is allowed to go stale.
  *
  * A closed set rather than an arbitrary duration, because `bucket` is what
  * makes the cache work: every viewer asking for the same window inside the
  * same bucket shares one fetch and one rendered chart, and the entry expires
  * by the bucket rolling over rather than by a timer. An arbitrary window would
  * give every viewer their own key and the sharing would quietly stop.
  *
  * `statistics` is the period to ask for when the window reaches past what HA's
  * recorder still holds. It is deliberately FINER than `bucket` for the short
  * windows — five-minute buckets for a one-hour view — because the statistics
  * table only has what it has, and asking for `hour` on a one-hour window
  * returns nothing at all (measured).
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

  /** `asOf` floored to this window's bucket — the time component of a cache
    * key. Floored rather than rounded so the bucket a series was fetched in is
    * the one it is looked up in; rounding would move the boundary under a
    * request that arrived just before it.
    */
  def bucketOf(asOf: Instant): Instant = {
    val step = bucket.toSeconds
    Instant.ofEpochSecond(Math.floorDiv(asOf.getEpochSecond, step) * step)
  }
}

object Window {

  /** The name a dashboard author writes and a URL carries. */
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
