package fh.view.history

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*

import java.time.Instant

/** One value per key, computed once however many callers ask for it, dropped
  * once it can no longer be current.
  *
  * Private to the history provider, and deliberately not offered to any other:
  * expiring by a bucket rolling works because recorder data is append-only —
  * the past is immutable and only the tail grows — which is a property of THIS
  * data, not of queries. A forecast changes in the future, a camera still
  * changes continuously, and neither could use this.
  *
  * A `Deferred` per key rather than a plain value, so the SECOND caller of a
  * cold key waits for the first caller's work instead of starting its own.
  * Concurrent page loads are the case that matters — ten open tabs are already
  * deduped by the per-slug `RenderCache`, but ten browsers reaching a cold
  * add-on inside one bucket are not, and fan-out is the expensive axis (ten
  * chatty sensors over 24 h measured at 910 KB and 2.3 s).
  *
  * A failure is not cached. It is removed on completion, so the next asker
  * retries — otherwise a brief HA outage would blank a 30-day chart for the
  * full hour until its bucket rolled.
  *
  * '''Expiry is per key, not "newest insert wins".''' Keys from different
  * windows expire on different schedules: a 1 h read buckets by the minute, a
  * 30 d read by the hour. Sweeping everything older than the newest inserted
  * bucket therefore evicted a 30 d entry that was valid for another 59 minutes
  * every time a 1 h entry landed — measured at 3 drawings where 2 was correct,
  * which destroyed exactly the sharing this exists for. Asking each key when IT
  * stops being current is what makes that unwritable.
  */
final class BucketCache[K, V] private (
    entries: Ref[IO, Map[K, Deferred[IO, Either[Throwable, V]]]],
    expiresAt: K => Instant
) {

  def get(key: K, now: Instant)(compute: => IO[V]): IO[V] =
    Deferred[IO, Either[Throwable, V]].flatMap { slot =>
      entries
        .modify { current =>
          current.get(key) match {
            case Some(existing) => (current, Left(existing))
            case None           =>
              // Swept here rather than on a schedule because this is the only
              // moment the map is already being written.
              val live =
                current.filter { case (k, _) => expiresAt(k).isAfter(now) }
              (live + (key -> slot), Right(slot))
          }
        }
        .flatMap {
          case Left(existing) => existing.get.flatMap(IO.fromEither)
          case Right(mine)    =>
            compute.attempt
              .flatTap(result =>
                mine.complete(result) *>
                  entries.update(_ - key).whenA(result.isLeft)
              )
              .flatMap(IO.fromEither)
        }
    }

  /** What is currently held, for tests and diagnostics. */
  def keys: IO[Set[K]] = entries.get.map(_.keySet)
}

object BucketCache {

  /** `expiresAt` answers, for one key, the instant it stops being current —
    * which for a bucketed key is its bucket plus that window's own bucket
    * length.
    */
  def create[K, V](expiresAt: K => Instant): IO[BucketCache[K, V]] =
    Ref[IO]
      .of(Map.empty[K, Deferred[IO, Either[Throwable, V]]])
      .map(new BucketCache(_, expiresAt))
}
