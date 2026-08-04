package fh.view.runtime

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.std.MapRef
import fh.view.model.NodeId

import java.util.concurrent.ConcurrentHashMap

/** One rendered node: its bytes and their digest, computed together so nothing
  * hashes the same HTML twice. Distinct from [[Rendered]], which is a PATCH
  * plus what it placed — this is the bytes of one node, before any of that.
  */
private[runtime] case class NodeBytes(html: String, digest: Digest)

/** Single-flight cache of rendered node bytes, keyed by what the render READS
  * ([[Renderer.renderInputs]]) — docs/plan-session-pulled-changelog.md.
  *
  * PER SLUG, living and dying with the dashboard's renderer, because a node id
  * is only meaningful within one renderer. A hot-swap drops the whole map,
  * which is the correctness story and the eviction story in one.
  *
  * The value is a [[Deferred]] rather than the bytes: the first caller to want
  * a key inserts an empty one and renders, everyone else finds it and waits, so
  * N sessions wanting the same node at the same instant cost one render.
  *
  * The map is a [[MapRef]] rather than a `Ref[IO, Map[…]]` so that a `modify`
  * retries `putIfAbsent`/`replace` for ONE key (the `ConcurrentHashMap` under
  * it) instead of CAS-ing the whole map: contention on one node never makes
  * another node's caller retry.
  */
private[runtime] final class RenderCache(
    entries: MapRef[IO, RenderCache.Key, Option[RenderCache.Slot]],
    live: ConcurrentHashMap[RenderCache.Key, RenderCache.Slot]
) {

  /** The bytes for `key`, rendering them only if nobody else already is.
    *
    * `render` is by-name and must be PURE and CPU-BOUND — it is the renderer's
    * own walk. It may be run zero times (a hit) or once, never twice for one
    * key. That it has no async boundary is load-bearing, not incidental; see
    * the cancellation note below before changing it.
    *
    * '''Uncancelable, and `guarantee` would not do instead.''' There are two
    * windows, and completing the slot from a `guaranteeCase` only covers one:
    *
    *   - between the CAS winning and [[fill]] STARTING, `mine` is in the map,
    *     uncompleted, and nothing has run — so a `guarantee` on `fill` never
    *     fires either, and waiters block forever. This window exists whatever
    *     the render does;
    *   - during the render, `guaranteeCase` does work — but it can only
    *     complete with an ERROR, so one cancelled fiber fails every waiter
    *     attached to it. Avoiding that needs a "producer cancelled" sentinel
    *     and a retry in the waiters: a second mechanism to survive a state,
    *     where being uncancelable means never entering it.
    *
    * The cost is bounded by one render, and cats-effect checks cancellation
    * BEFORE a delay rather than inside one, so a pure walk is uninterruptible
    * mid-way regardless — this buys the invariant for almost nothing.
    *
    * '''The condition to re-check:''' that argument holds because the render is
    * pure CPU. Move it to a blocking pool, or split it with `IO.cede` for
    * fairness on a large dashboard, and the second window comes back — then
    * `guaranteeCase` plus a retrying waiter IS the right answer.
    */
  def apply(key: RenderCache.Key)(render: => String): IO[NodeBytes] =
    Deferred[IO, Either[Throwable, NodeBytes]].flatMap { mine =>
      // Only a WAITER is cancelable, and that is what `poll` marks. Note what
      // the critical section does NOT contain: `fill` is an IO VALUE here,
      // built and discarded if this attempt loses. A losing CAS costs an
      // Option match, never a render.
      IO.uncancelable { poll =>
        entries(key)
          .modify {
            case taken @ Some(existing) => (taken, poll(existing.get))
            case None                   => (Some(mine), fill(key, mine, render))
          }
          .flatten
          .rethrow
      }
    }

  private def fill(
      key: RenderCache.Key,
      mine: RenderCache.Slot,
      render: => String
  ): IO[Either[Throwable, NodeBytes]] =
    IO(render)
      .map(html => NodeBytes(html, Digest.of(html)))
      .attempt
      // A failure must not stay in the map: a `Left` left behind poisons that
      // node for the life of the renderer. Evicting first and completing after
      // is the order that matters — the next caller retries, while the waiters
      // already holding this slot still see the error rather than hanging.
      .flatTap {
        case Left(_)  => entries(key).set(None)
        case Right(_) => IO.unit
      }
      .flatTap(mine.complete(_).void)

  /** Entry count — the seam for asserting eviction, since a `MapRef` has no
    * size of its own.
    */
  def size: IO[Int] = IO(live.size)
}

private[runtime] object RenderCache {

  /** A node id alone is not enough: the same node renders differently for
    * different inputs, and both together are what one entry describes.
    */
  type Key = (NodeId, RenderInputs)

  private type Slot = Deferred[IO, Either[Throwable, NodeBytes]]

  def create: IO[RenderCache] =
    IO(new ConcurrentHashMap[Key, Slot]()).map(chm =>
      new RenderCache(MapRef.fromConcurrentHashMap(chm), chm)
    )
}
