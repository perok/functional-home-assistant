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
  * is only meaningful within one renderer. A hot-swap drops the whole map, which
  * is the correctness story and the eviction story in one.
  *
  * The value is a [[Deferred]] rather than the bytes: the first caller to want a
  * key inserts an empty one and renders, everyone else finds it and waits, so N
  * sessions wanting the same node at the same instant cost one render. Insertion
  * is per key ([[MapRef]]) rather than a CAS over the whole map, so a slow render
  * never makes another key's caller retry.
  */
private[runtime] final class RenderCache(
    entries: MapRef[IO, RenderCache.Key, Option[RenderCache.Slot]],
    live: ConcurrentHashMap[RenderCache.Key, RenderCache.Slot]
) {

  /** The bytes for `key`, rendering them only if nobody else already is.
    *
    * `render` is by-name and must be PURE — it is the renderer's own pure walk,
    * and it may be run zero times (a hit) or once, never twice for one key.
    */
  def apply(key: RenderCache.Key)(render: => String): IO[NodeBytes] =
    Deferred[IO, Either[Throwable, NodeBytes]].flatMap { mine =>
      // Uncancelable around the claim: cancelled between inserting `mine` and
      // starting the render, the key would hold a Deferred nobody ever
      // completes and every later caller would block on it forever. Production
      // itself is a pure CPU walk with no async boundary to interrupt, so
      // making it uncancelable costs nothing and removes the failure mode by
      // construction rather than patching it in an onCancel. Only a WAITER is
      // cancelable, and that is `poll`ed.
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
