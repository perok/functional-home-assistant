package fh.view.history

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*

import scala.concurrent.duration.*

/** One value per key, computed once however many callers ask — the series cache
  * and the drawing cache both.
  *
  * The computation runs on a fiber of its own, never the first asker's. An
  * asker is cancelled whenever its page is abandoned, and a computation that
  * died with it would leave the slot empty for every later asker of that key,
  * for as long as the key lives. It is bounded by [[SharedCache.Timeout]]
  * instead.
  *
  * A failure is kept for `failureTtl` and then retried, so a recorder that is
  * down costs each render its error card rather than a fresh wait. It is
  * reported once, to `onFailure`, rather than by every render that shows it.
  *
  * `keep(inserted, other)` says whether `other` survives `inserted` being
  * added: a series retires when its bucket rolls, a drawing when its version
  * moves.
  */
final class SharedCache[K, V] private (
    entries: Ref[IO, Map[K, SharedCache.Entry[V]]],
    keep: (K, K) => Boolean,
    failureTtl: FiniteDuration,
    onFailure: (K, Throwable) => IO[Unit]
) {
  import SharedCache.*

  // A live entry needs no clock and no new slot: that is every warm render,
  // so it is kept to one map lookup. By-name, because building a drawing's
  // `compute` decodes its series — +45 µs a page when this was by-value
  // (`QueryBench.pagePrepass`).
  def get(key: K)(compute: => IO[V]): IO[V] =
    entries.get.flatMap(_.get(key) match {
      case Some(Entry(result, None)) => result.get.rethrow
      case _                         => claim(key, compute)
    })

  private def claim(key: K, compute: => IO[V]): IO[V] =
    (Deferred[IO, Either[Throwable, V]], IO.monotonic).flatMapN { (slot, t) =>
      IO.uncancelable { poll =>
        entries.modify { current =>
          current.get(key) match {
            case Some(e) if e.retryAt.forall(t < _) =>
              (current, poll(e.result.get))
            case _ =>
              (
                current.filter { case (k, _) => keep(key, k) } +
                  (key -> Entry(slot, None)),
                own(key, slot, compute) *> poll(slot.get)
              )
          }
        }.flatten
      }.rethrow
    }

  private def own(
      key: K,
      slot: Deferred[IO, Either[Throwable, V]],
      compute: IO[V]
  ): IO[Unit] =
    compute
      .timeout(Timeout)
      .attempt
      .flatMap { result =>
        // Marked BEFORE the slot completes: an asker woken by the completion
        // may ask again at once, and must already see when to retry.
        val mark = IO.whenA(result.isLeft)(
          IO.monotonic.flatMap(t =>
            entries.update(m =>
              m.get(key) match {
                case Some(e) if e.result eq slot =>
                  m.updated(key, e.copy(retryAt = Some(t + failureTtl)))
                case _ => m
              }
            )
          )
        )
        mark *> slot.complete(result) *>
          result.left.toOption.traverse_(onFailure(key, _).handleError(_ => ()))
      }
      .start
      .void

  def keys: IO[Set[K]] = entries.get.map(_.keySet)
}

object SharedCache {

  /** How long one fetch or one drawing may take before it is that read's
    * failure (architecture §0: a bound is an error, not a fallback).
    */
  val Timeout: FiniteDuration = 10.seconds

  val FailureTtl: FiniteDuration = 30.seconds

  private final case class Entry[V](
      result: Deferred[IO, Either[Throwable, V]],
      retryAt: Option[FiniteDuration]
  )

  type OnFailure[K] = (K, Throwable) => IO[Unit]

  def ignore[K]: OnFailure[K] = (_, _) => IO.unit

  def create[K, V](
      keep: (K, K) => Boolean,
      failureTtl: FiniteDuration = FailureTtl,
      onFailure: OnFailure[K] = ignore[K]
  ): IO[SharedCache[K, V]] =
    Ref[IO]
      .of(Map.empty[K, Entry[V]])
      .map(new SharedCache(_, keep, failureTtl, onFailure))
}
