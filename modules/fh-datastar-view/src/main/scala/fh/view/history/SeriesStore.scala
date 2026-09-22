package fh.view.history

import cats.effect.IO
import fh.view.query.QueryIdentity

import java.time.Instant

/** `bucket` is the window's floor of "now", so an entry expires by its bucket
  * rolling rather than by a timer.
  */
final case class SeriesKey(
    identity: QueryIdentity,
    entityId: String,
    window: Window,
    bucket: Instant
) {

  /** Per key: a 1 h and a 30 d read expire on different schedules. */
  def expiresAt: Instant = bucket.plusSeconds(window.bucket.toSeconds)
}

/** One fetch per key, shared by everyone who asks, dropped when its bucket
  * rolls.
  */
final class SeriesStore private (
    cache: BucketCache[SeriesKey, Series],
    provider: SeriesProvider
) {

  def get(
      identity: QueryIdentity,
      entityId: String,
      window: Window,
      asOf: Instant
  ): IO[Series] = {
    val key = SeriesKey(identity, entityId, window, window.bucketOf(asOf))
    cache.get(key, asOf)(provider.series(identity, entityId, window, asOf))
  }

  def keys: IO[Set[SeriesKey]] = cache.keys
}

object SeriesStore {
  def create(provider: SeriesProvider): IO[SeriesStore] =
    BucketCache
      .create[SeriesKey, Series](_.expiresAt)
      .map(new SeriesStore(_, provider))
}
