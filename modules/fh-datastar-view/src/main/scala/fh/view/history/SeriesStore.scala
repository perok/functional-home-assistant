package fh.view.history

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*

import java.time.Instant

/** `bucket` is the window's floor of "now", so an entry expires by its bucket
  * rolling rather than by a timer.
  */
final case class SeriesKey(
    identity: SeriesIdentity,
    entityId: String,
    window: Window,
    bucket: Instant
)

/** One fetch per key, shared by everyone who asks, dropped when its bucket
  * rolls.
  *
  * A `Deferred` per key so concurrent callers of a cold key wait on one fetch.
  * A failure is removed rather than cached, so the next asker retries instead
  * of waiting for the next bucket.
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
              // Each key against its OWN window's current bucket: windows
              // bucket at different sizes, so comparing with `key.bucket`
              // would sweep a live 7d entry whenever a 1h one is asked for.
              val live = current.filter { case (k, _) =>
                k.bucket.compareTo(k.window.bucketOf(asOf)) >= 0
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

  def keys: IO[Set[SeriesKey]] = entries.get.map(_.keySet)
}

object SeriesStore {
  def create(provider: SeriesProvider): IO[SeriesStore] =
    Ref[IO]
      .of(Map.empty[SeriesKey, Deferred[IO, Either[Throwable, Series]]])
      .map(new SeriesStore(_, provider))
}
