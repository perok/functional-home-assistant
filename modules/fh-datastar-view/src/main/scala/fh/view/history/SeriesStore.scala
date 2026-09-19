package fh.view.history

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*

import java.time.Instant

/** What a cached series is keyed by.
  *
  * `bucket` is the window's own floor of "now", so the entry expires by time
  * moving rather than by a timer: every viewer asking for the same window
  * inside the same bucket gets one fetch, and the moment the bucket rolls the
  * old key is simply never asked for again.
  *
  * `identity` is first because it is the one component whose omission would be
  * a permission leak rather than a performance bug — see [[SeriesIdentity]].
  */
final case class SeriesKey(
    identity: SeriesIdentity,
    entityId: String,
    window: Window,
    bucket: Instant
)

/** The pull-side counterpart of `StateStore`: one fetch per key, shared by
  * everyone who asks for it, dropped when its bucket rolls.
  *
  * A `Deferred` per key rather than a plain value, so the SECOND caller of a
  * cold key waits for the first caller's fetch instead of starting its own. A
  * chart appearing on ten open tabs at once is the normal case, not the
  * exceptional one, and ten parallel history calls for one series would be the
  * default behaviour without it.
  *
  * A failure is not cached. It is removed on completion, so the next asker
  * retries — the opposite of the memoized `None` that suits a one-shot lookup,
  * because a series that failed because HA was briefly down should come back
  * when it is up rather than at the next bucket.
  */
final class SeriesStore private (
    entries: Ref[IO, Map[SeriesKey, Deferred[IO, Either[Throwable, Series]]]],
    provider: SeriesProvider
) {

  def get(
      identity: SeriesIdentity,
      entityId: String,
      window: Window,
      asOf: Instant
  ): IO[Series] = {
    val key = SeriesKey(identity, entityId, window, window.bucketOf(asOf))
    Deferred[IO, Either[Throwable, Series]].flatMap { slot =>
      entries
        .modify { current =>
          current.get(key) match {
            case Some(existing) => (current, Left(existing))
            case None           =>
              // Everything for an older bucket is unreachable — a key names
              // its bucket, so nothing will ask for it again. Swept here
              // rather than on a schedule because this is the only moment the
              // map is already being written.
              val live = current.filter { case (k, _) =>
                k.bucket.compareTo(key.bucket) >= 0
              }
              (live + (key -> slot), Right(slot))
          }
        }
        .flatMap {
          case Left(existing) => existing.get.flatMap(IO.fromEither)
          case Right(mine)    =>
            provider
              .series(identity, entityId, window, asOf)
              .attempt
              .flatTap(result =>
                mine.complete(result) *>
                  entries.update(_ - key).whenA(result.isLeft)
              )
              .flatMap(IO.fromEither)
        }
    }
  }

  /** What is currently held, for tests and diagnostics. */
  def keys: IO[Set[SeriesKey]] = entries.get.map(_.keySet)
}

object SeriesStore {
  def create(provider: SeriesProvider): IO[SeriesStore] =
    Ref[IO]
      .of(Map.empty[SeriesKey, Deferred[IO, Either[Throwable, Series]]])
      .map(new SeriesStore(_, provider))
}
