package fh.view.history

import cats.effect.IO
import cats.syntax.all.*
import fh.view.fetch.{Answer, FetchIdentity, FetchRequest, SharedCache}
import io.circe.syntax.*

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** Parsing a history fetch — pure, so `Dashboard.validate` can reject a bad one
  * with nothing wired.
  */
object HistoryFetch {

  val Name: String = "history"

  /** `entity`, `window`, and `points = "all"` for every reading rather than
    * about 300: a chart wants few, a computation (a countdown's start) every
    * one. Size and unit are how the answer is drawn (the stage), so two sizes
    * of one chart are one request and one fetch.
    */
  def parse(params: Map[String, String]): Either[String, FetchRequest] =
    for {
      entityId <- params
        .get("entity")
        .toRight(s"fetch '$Name' needs an 'entity' parameter")
      name <- params
        .get("window")
        .toRight(s"fetch '$Name' needs a 'window' parameter")
      window <- Window
        .byName(name)
        .toRight(
          s"fetch '$Name' has unknown window '$name' — one of " +
            Window.values.map(_.name).mkString(", ")
        )
      everyPoint <- params.get("points") match {
        case None        => Right(false)
        case Some("all") => Right(true)
        case Some(other) =>
          Left(s"fetch '$Name' has points '$other' — 'all', or none for ~300")
      }
    } yield FetchRequest.History(entityId, window, everyPoint)
}

/** `bucket` is the window's floor of "now", so an entry expires by its bucket
  * rolling rather than by a timer. Identity is in the key because HA scopes
  * recorder data by user.
  */
final case class SeriesKey(
    identity: FetchIdentity,
    entityId: String,
    window: Window,
    bucket: Instant,
    everyPoint: Boolean = false
) {
  def expiresAt: Instant = bucket.plusSeconds(window.bucket.toSeconds)
}

/** The `history` provider: one entity's readings over one window, fetched once
  * per bucket and shared by everyone who asks, answered as DATA.
  */
final class History private (
    source: SeriesSource,
    cache: SharedCache[SeriesKey, Answer],
    target: Int,
    now: IO[Instant]
) {

  /** The bucket is the version, and it works as one because the past is
    * immutable: two renders inside it read the same points. The clock is read
    * here rather than handed in, because a bucket is this provider's policy — a
    * provider whose data is pushed has no use for one.
    */
  def answer(
      identity: FetchIdentity,
      entityId: String,
      window: Window,
      everyPoint: Boolean = false
  ): IO[Answer] =
    now.flatMap(answerAt(identity, entityId, window, _, everyPoint))

  // Encoded once, on the fetch: re-encoding per ask was 5 µs a read, all of a
  // warm chart pull's cost over a plain one (`FetchBench.pullChart`).
  private[history] def answerAt(
      identity: FetchIdentity,
      entityId: String,
      window: Window,
      asOf: Instant,
      everyPoint: Boolean = false
  ): IO[Answer] = {
    val bucket = window.bucketOf(asOf)
    cache.get(SeriesKey(identity, entityId, window, bucket, everyPoint))(
      fetch(entityId, window, asOf, everyPoint)
        .map(s => Answer(bucket.getEpochSecond, s.asJson))
    )
  }

  private[history] def series(
      identity: FetchIdentity,
      entityId: String,
      window: Window,
      asOf: Instant
  ): IO[Series] =
    answerAt(identity, entityId, window, asOf)
      .flatMap(_.data.as[Series].liftTo[IO])

  private[history] def keys: IO[Set[SeriesKey]] = cache.keys

  /** Statistics first, where the window has a period and every reading was not
    * asked for: a day of a meter's raw rows is 850 KB to draw 300 points. The
    * newest bucket lags its period by minutes, so raw rows after it carry the
    * line to now. In sequence, because the tail starts where the buckets end.
    * An entity with no `state_class` has no statistics and reads raw.
    */
  private def fetch(
      entityId: String,
      window: Window,
      asOf: Instant,
      everyPoint: Boolean
  ) = {
    def raw(from: Instant) =
      source.raw(from, asOf, entityId).map(Series.fromHistory)
    val start = window.startAt(asOf)
    window.statistics
      .filterNot(_ => everyPoint)
      .fold(raw(start))(period =>
        source
          .statistics(start, asOf, entityId, period)
          .map(Series.fromStatistics)
          .flatMap(buckets =>
            buckets.points.lastOption.fold(raw(start))(last =>
              raw(last.at).map(tail =>
                Series(
                  buckets.points ++ tail.points.filter(_.at.isAfter(last.at))
                )
              )
            )
          )
      )
      .map(s =>
        if (everyPoint) s.heldUntil(asOf)
        else s.copy(points = Downsample.lttb(s.heldUntil(asOf).points, target))
      )
  }
}

object History {

  def create(
      source: SeriesSource,
      target: Int = Downsample.DefaultTarget,
      failureTtl: FiniteDuration = SharedCache.FailureTtl,
      onFailure: SharedCache.OnFailure[SeriesKey] = SharedCache.ignore,
      now: IO[Instant] = IO.realTimeInstant
  ): IO[History] =
    // Expiry is asked PER KEY: windows bucket at different sizes, and sweeping
    // by the newest key's bucket evicted live 30 d entries whenever a 1 h one
    // landed.
    SharedCache
      .create[SeriesKey, Answer](
        (added, other) => other.expiresAt.isAfter(added.bucket),
        failureTtl,
        onFailure
      )
      .map(new History(source, _, target, now))
}
