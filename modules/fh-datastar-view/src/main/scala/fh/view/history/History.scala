package fh.view.history

import cats.effect.IO
import cats.syntax.all.*
import fh.view.fetch.{Answer, FetchIdentity, SharedCache}
import io.circe.syntax.*

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** `bucket` is the window's floor of "now", so an entry expires by its bucket
  * rolling rather than by a timer. Identity is in the key because HA scopes
  * recorder data by user.
  */
final case class SeriesKey(
    identity: FetchIdentity,
    entityId: String,
    window: Window,
    bucket: Instant
) {
  def expiresAt: Instant = bucket.plusSeconds(window.bucket.toSeconds)
}

/** The `history` provider: one entity's readings over one window, fetched once
  * per bucket and shared by everyone who asks, answered as DATA — every row of
  * a raw read, every bucket of a statistics one. Thinning is the chart stage's.
  */
final class History private (
    source: SeriesSource,
    cache: SharedCache[SeriesKey, Answer],
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
      window: Window
  ): IO[Answer] =
    now.flatMap(answerAt(identity, entityId, window, _))

  // Encoded once, on the fetch: re-encoding per ask was 5 µs a read, all of a
  // warm chart pull's cost over a plain one (`FetchBench.pullChart`).
  private[history] def answerAt(
      identity: FetchIdentity,
      entityId: String,
      window: Window,
      asOf: Instant
  ): IO[Answer] = {
    val bucket = window.bucketOf(asOf)
    cache.get(SeriesKey(identity, entityId, window, bucket))(
      fetch(entityId, window, asOf)
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

  /** The newest bucket ends minutes before now, and `heldUntil` carries it
    * there, as HA's own chart does.
    */
  private def fetch(entityId: String, window: Window, asOf: Instant) = {
    val start = window.startAt(asOf)
    val raw = source.raw(start, asOf, entityId).map(Series.fromHistory)
    (window match {
      case Window.Raw(_)                          => raw
      case Window.Statistics(_, period, fallback) =>
        source
          .statistics(start, asOf, entityId, period)
          .map(Series.fromStatistics)
          .flatMap(s => if (s.isEmpty && fallback) raw else IO.pure(s))
    })
      .map(_.heldUntil(asOf))
  }
}

object History {

  def create(
      source: SeriesSource,
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
      .map(new History(source, _, now))
}
