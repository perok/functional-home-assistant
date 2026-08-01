package fh.view.runtime

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*

/** Compute a value at most once per key, sharing the result with every later
  * caller — the batch-scoped render memo.
  *
  * Two properties make it the right shape here. It is LAZY, so a key nobody
  * asks for is never computed: a tab variant no connected client holds costs
  * nothing. And it shares the RESULT, so several callers on one key all receive
  * the same answer rather than the first one consuming it — which is the whole
  * reason the thing memoised is a verdict ("send this patch" / "nothing to
  * send") and not merely a render.
  *
  * It needs no eviction policy. One of these belongs to one published batch and
  * dies with it, by ordinary reachability, so nothing it holds can go stale.
  * Deliberately NOT keyed on the store version, which is global — one sensor
  * would invalidate every entry for every dashboard.
  */
private[runtime] final class Memo[K, V] private (
    state: Ref[IO, Map[K, Deferred[IO, V]]],
    compute: K => IO[V]
) {

  def get(key: K): IO[V] =
    Deferred[IO, V].flatMap { fresh =>
      state
        .modify { m =>
          m.get(key) match {
            case Some(existing) => (m, Left(existing))
            case None           => (m.updated(key, fresh), Right(fresh))
          }
        }
        .flatMap {
          // Someone else got here first: wait for the answer they are computing.
          case Left(existing) => existing.get
          // We claimed it, so we owe everyone else the result — including on
          // failure, or a waiter would hang on a Deferred nobody completes.
          case Right(mine) =>
            compute(key).guaranteeCase(outcome =>
              outcome.fold(
                IO.unit,
                _ => state.update(_ - key),
                _.flatMap(mine.complete).void
              )
            )
        }
    }
}

private[runtime] object Memo {
  def create[K, V](compute: K => IO[V]): IO[Memo[K, V]] =
    Ref[IO].of(Map.empty[K, Deferred[IO, V]]).map(new Memo(_, compute))
}
