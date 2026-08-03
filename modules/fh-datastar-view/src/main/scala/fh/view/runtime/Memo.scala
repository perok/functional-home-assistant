package fh.view.runtime

import cats.effect.{IO, Ref}

private[runtime] object Memo {

  /** Compute a value at most once per key, sharing the result with every later
    * caller — the batch-scoped render memo.
    *
    * Two properties make it the right shape here. It is LAZY, so a key nobody
    * asks for is never computed: a tab variant no connected client holds costs
    * nothing. And it shares the RESULT, so several callers on one key all
    * receive the same answer rather than the first one consuming it — which is
    * the whole reason the thing memoised is a verdict ("send this patch" /
    * "nothing to send") and not merely a render.
    *
    * It needs no eviction policy. One of these belongs to one published batch
    * and dies with it, by ordinary reachability, so nothing it holds can go
    * stale.
    *
    * Two callers racing a fresh key both build a memo cell and one is
    * discarded; a discarded cell is un-run, so it costs an allocation and never
    * a render.
    */
  def keyed[K, V](compute: K => IO[V]): IO[K => IO[V]] =
    Ref[IO].of(Map.empty[K, IO[V]]).map { state => key =>
      compute(key).memoize
        .flatMap(fresh =>
          state.modify(m =>
            m.get(key).fold(m.updated(key, fresh) -> fresh)(m -> _)
          )
        )
        .flatten
    }
}
