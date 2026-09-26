package fh.view.history

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*

import java.time.Instant

/** One value per key, computed once however many callers ask, dropped once it
  * can no longer be current.
  *
  * For history only: expiring by a bucket rolling is sound because recorder
  * data is append-only. A `Deferred` per key makes concurrent callers of a cold
  * key wait on one computation; a failure is removed so the next asker retries.
  *
  * Expiry is asked PER KEY ([[create]]'s `expiresAt`): windows bucket at
  * different sizes, and sweeping by the newest key's bucket evicted live 30 d
  * entries whenever a 1 h one landed.
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

  def keys: IO[Set[K]] = entries.get.map(_.keySet)
}

object BucketCache {

  def create[K, V](expiresAt: K => Instant): IO[BucketCache[K, V]] =
    Ref[IO]
      .of(Map.empty[K, Deferred[IO, Either[Throwable, V]]])
      .map(new BucketCache(_, expiresAt))
}
