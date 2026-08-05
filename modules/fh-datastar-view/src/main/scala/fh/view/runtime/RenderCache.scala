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

private[runtime] object NodeBytes {

  /** For the paths that render OUTSIDE the cache and so must hash for
    * themselves. Everywhere else the digest arrives already computed, which is
    * the point: the same HTML used to be hashed once to ask the log whether it
    * held it and again to record that it now does.
    */
  def of(html: String): NodeBytes = NodeBytes(html, Digest.of(html))
}

/** Single-flight cache of rendered node bytes, keyed by what the render READS
  * ([[Renderer.renderInputs]]) —
  * docs/adr/0012-each-session-renders-what-it-is-owed.md.
  *
  * PER SLUG, because a node id is only meaningful within one dashboard. It
  * OUTLIVES a renderer swap rather than being rotated with one: each entry
  * names the renderer that filled it, so a swap invalidates by identity and the
  * one-generation-per-node bound reclaims the space on the next ask. Rotating
  * the map instead would leave a window where a pull that read the previous
  * renderer writes its bytes into the fresh cache.
  *
  * The value is a [[Deferred]] rather than the bytes: the first caller to want
  * a key inserts an empty one and renders, everyone else finds it and waits, so
  * N sessions wanting the same node at the same instant cost one render.
  *
  * '''ONE GENERATION PER NODE, and that bound is not optional.''' The map is
  * keyed by [[NodeId]] and each entry remembers the renderer and inputs it was
  * rendered for; a render for a different generation REPLACES it. Keying by
  * `(nodeId, inputs)` instead would grow without bound: the shared pass selects
  * exactly the nodes binding an entity that just moved
  * (`Renderer.componentsFor`), so every batch mints new keys and old ones are
  * never asked for again. That is unbounded retention of HTML in exchange for
  * hits that do not happen. Bounded by the dashboard's node count needs no
  * timer and no sweep.
  *
  * What it gives up is a laggard: two readers at different versions evict each
  * other. Nothing does that yet — the publisher is the only caller and it holds
  * one snapshot. When sessions pull at their own positions (phase 3), the fix
  * is a small fixed number of generations per node, still bounded — NOT a
  * return to unbounded keys.
  *
  * The map is a [[MapRef]] rather than a `Ref[IO, Map[…]]` so that a `modify`
  * retries `putIfAbsent`/`replace` for ONE key (the `ConcurrentHashMap` under
  * it) instead of CAS-ing the whole map: contention on one node never makes
  * another node's caller retry.
  */
private[runtime] final class RenderCache(
    entries: MapRef[IO, NodeId, Option[RenderCache.Entry]],
    live: ConcurrentHashMap[NodeId, RenderCache.Entry]
) {

  /** The bytes for `id` at `inputs`, rendering them only if nobody else already
    * is and the entry on hand is not already for these inputs.
    *
    * `render` is by-name and must be PURE and CPU-BOUND — it is the renderer's
    * own walk. It may be run zero times (a hit) or once, never twice for one
    * generation. That it has no async boundary is load-bearing, not incidental;
    * see the cancellation note below before changing it.
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
  def apply(id: NodeId, renderer: Renderer, inputs: RenderInputs)(
      render: => String
  ): IO[NodeBytes] =
    Deferred[IO, Either[Throwable, NodeBytes]].flatMap { mine =>
      // Only a WAITER is cancelable, and that is what `poll` marks. Note what
      // the critical section does NOT contain: `fill` is an IO VALUE here,
      // built and discarded if this attempt loses. A losing CAS costs an
      // equality check, never a render.
      IO.uncancelable { poll =>
        entries(id)
          .modify {
            case hit @ Some(e)
                if (e.renderer eq renderer) && e.inputs == inputs =>
              (hit, poll(e.slot.get))
            case _ =>
              val entry = RenderCache.Entry(renderer, inputs, mine)
              (Some(entry), fill(id, entry, render))
          }
          .flatten
          .rethrow
      }
    }

  private def fill(
      id: NodeId,
      entry: RenderCache.Entry,
      render: => String
  ): IO[Either[Throwable, NodeBytes]] =
    IO(render)
      .map(html => NodeBytes(html, Digest.of(html)))
      .attempt
      // A failure must not stay in the map: a `Left` left behind poisons that
      // node until its inputs move. Evicting first and completing after is the
      // order that matters — the next caller retries, while the waiters already
      // holding this slot still see the error rather than hanging.
      //
      // Only if the entry is still OURS: a newer generation may have replaced
      // it while this one rendered, and dropping that would evict a live entry
      // on the strength of a stale failure.
      .flatTap {
        case Left(_) =>
          entries(id).update(_.filterNot(_ eq entry))
        case Right(_) => IO.unit
      }
      .flatTap(entry.slot.complete(_).void)

  /** Entry count — the seam for asserting the bound, since a `MapRef` has no
    * size of its own.
    */
  def size: IO[Int] = IO(live.size)
}

private[runtime] object RenderCache {

  /** One node's current generation: the bytes, the inputs they are for, and the
    * renderer that produced them. Carrying both is what makes a stale
    * generation detectable rather than needing its own key.
    *
    * The renderer is compared by IDENTITY (`eq`). Inputs alone are not enough
    * to survive a hot swap: a dashboard edit changes the MARKUP while the
    * entity versions it reads stay exactly where they were, so an unchanged key
    * would answer with the previous dashboard's bytes.
    */
  private[runtime] case class Entry(
      renderer: Renderer,
      inputs: RenderInputs,
      slot: Deferred[IO, Either[Throwable, NodeBytes]]
  )

  def create: IO[RenderCache] =
    IO(new ConcurrentHashMap[NodeId, Entry]()).map(chm =>
      new RenderCache(MapRef.fromConcurrentHashMap(chm), chm)
    )
}
