package fh.view.history

import cats.effect.IO
import fh.view.query.QueryIdentity

import java.time.Instant

/** What a cached series is keyed by.
  *
  * `bucket` is the window's own floor of "now", so the entry expires by time
  * moving rather than by a timer: every viewer asking for the same window
  * inside the same bucket gets one fetch, and the moment the bucket rolls the
  * old key is simply never asked for again.
  *
  * `identity` is first because it is the one component whose omission would be
  * a permission leak rather than a performance bug — see [[QueryIdentity]].
  */
final case class SeriesKey(
    identity: QueryIdentity,
    entityId: String,
    window: Window,
    bucket: Instant
) {

  /** When this entry stops being current: its bucket plus its OWN window's
    * bucket length. Per key, because a 1 h read and a 30 d read do not expire
    * on the same schedule — see [[BucketCache]].
    */
  def expiresAt: Instant = bucket.plusSeconds(window.bucket.toSeconds)
}

/** The pull-side counterpart of `StateStore`: one fetch per key, shared by
  * everyone who asks for it, dropped when its bucket rolls.
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

  /** What is currently held, for tests and diagnostics. */
  def keys: IO[Set[SeriesKey]] = cache.keys
}

object SeriesStore {
  def create(provider: SeriesProvider): IO[SeriesStore] =
    BucketCache
      .create[SeriesKey, Series](_.expiresAt)
      .map(new SeriesStore(_, provider))
}
