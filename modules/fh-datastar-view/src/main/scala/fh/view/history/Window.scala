package fh.view.history

import api.homeassistant.ws.domain.StatisticsPeriod
import io.circe.Decoder
import io.circe.derivation.{Configuration, ConfiguredDecoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.numeric.Positive

import java.time.Instant
import scala.concurrent.duration.*

/** What a history fetch reads over its span: the recorder's raw rows, or HA's
  * statistics at one period. The author picks; nothing here picks for them.
  *
  * `fallback` reads raw when there are no statistics: only an entity with a
  * `state_class` has any, and without it such a chart draws nothing.
  */
enum Window derives CanEqual {
  case Raw(span: Window.Span)
  case Statistics(
      span: Window.Span,
      period: StatisticsPeriod,
      fallback: Boolean = false
  )

  def span: Window.Span

  def length: FiniteDuration = (span: Long).seconds

  /** How long one answer is current: its version and its expiry, so viewers
    * inside one bucket share one fetch. A statistics answer moves by its
    * period; a raw one by a 720th of its span, at least a minute.
    */
  def bucket: FiniteDuration = this match {
    case Statistics(_, period, _) => Window.lengthOf(period)
    case Raw(_)                   => (length / 720).max(1.minute)
  }

  def startAt(asOf: Instant): Instant = asOf.minusSeconds(length.toSeconds)

  /** A statistics bucket rolls a minute after its period ends: HA writes the
    * period's row seconds after the boundary, and a fetch in between would miss
    * it for a whole period.
    */
  def bucketOf(asOf: Instant): Instant = {
    val step = bucket.toSeconds
    val late = this match {
      case Statistics(_, _, _) => Window.StatisticsLag.toSeconds
      case Raw(_)              => 0L
    }
    Instant.ofEpochSecond(
      Math.floorDiv(asOf.getEpochSecond - late, step) * step + late
    )
  }

  /** A rule across two fields, so `validate`'s rather than the decoder's. */
  def problem: Option[String] = this match {
    case Statistics(_, period, _) if Window.lengthOf(period) >= length =>
      Some(
        s"reads statistics at '${period.wire}' over $length, which answers " +
          "nothing: the period must be shorter than the span"
      )
    case _ => None
  }

  def key: String = this match {
    case Raw(span)                     => s"raw:$span"
    case Statistics(span, p, fallback) =>
      s"statistics:$span:${p.wire}:$fallback"
  }
}

object Window {

  val StatisticsLag: FiniteDuration = 1.minute

  /** Seconds. Pkl already refuses a span under a second. */
  type Span = Long :| DescribedAs[Positive, "a window's span must be positive"]

  // A month is a bucket length here, not a calendar month: it only sets how
  // long an answer stays current.
  def lengthOf(period: StatisticsPeriod): FiniteDuration = period match {
    case StatisticsPeriod.FiveMinute => 5.minutes
    case StatisticsPeriod.Hour       => 1.hour
    case StatisticsPeriod.Day        => 1.day
    case StatisticsPeriod.Week       => 7.days
    case StatisticsPeriod.Month      => 30.days
    case StatisticsPeriod.Year       => 365.days
  }

  given Decoder[StatisticsPeriod] = Decoder[String].emap(w =>
    StatisticsPeriod.values
      .find(_.wire == w)
      .toRight(
        s"unknown statistics period '$w' — one of " +
          StatisticsPeriod.values.map(_.wire).mkString(", ")
      )
  )

  given Decoder[Window] = ConfiguredDecoder.derived[Window](using
    Configuration.default.withDefaults.withDiscriminator("kind")
  )
}
