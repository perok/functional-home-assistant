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
    * themselves. Everywhere else the digest arrives already computed, so no
    * HTML is hashed twice — once to ask the log whether it holds it, again to
    * record that it now does.
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
  * next ask for that node replaces the whole entry, every selection of it at
  * once — which is what reclaims the space without a sweep. Rotating the map
  * instead would leave a window where a pull that read the previous renderer
  * writes its bytes into the fresh cache.
  *
  * The value is a [[Deferred]] rather than the bytes: the first caller to want
  * a key inserts an empty one and renders, everyone else finds it and waits, so
  * N sessions wanting the same node at the same instant cost one render.
  *
  * '''ONE GENERATION PER NODE, and that bound is not optional.''' Keying by
  * `(nodeId, inputs)` outright would grow without bound: the shared pass
  * selects exactly the nodes binding an entity that just moved
  * (`Renderer.componentsFor`), so every batch mints new ENTITY VERSIONS and the
  * old ones are never asked for again — unbounded retention of HTML in exchange
  * for hits that do not happen.
  *
  * One generation per node, replaced in place. `RenderInputs` is entity
  * versions and nothing else, and those CHURN — every frame moves one, so the
  * generation for the previous version is dead the moment it is replaced.
  *
  * A SELECTION is not part of the key, and does not need to be: a bake owner
  * holds its content in regions, which makes it structure, and structure is
  * never a patch target and so never cached. What renders per frame is the leaf
  * beside it, whose bytes mention no selection at all — so two viewers on two
  * tabs are owed the same bytes and share one render.
  * `RenderCacheContentionSuite` holds that at 1.0 renders a frame however many
  * viewers and however many tabs.
  *
  * '''A STRAGGLER NEVER DISPLACES THE CURRENT GENERATION.''' Sessions pull in
  * parallel and read the store when they get there, so they do not all render
  * from one snapshot. Three racing (newest, laggard, newest) would otherwise
  * cost three renders: the laggard's install evicts bytes the third is about to
  * hit. The waste is never the laggard's own render — it needs that — but what
  * installing it THROWS AWAY.
  *
  * So an install is refused when the generation present was rendered from a
  * snapshot at or ahead of the caller's on every entity it reads
  * ([[RenderInputs.isAtLeast]]): that caller renders, is served, and the map is
  * left holding the newer bytes. Neither bucketing nor keeping N generations
  * addresses this — the stragglers agree on the selection and differ on the
  * entity half, so they compete for one bucket however many there are.
  *
  * '''The limitation that buys.''' A CLUSTER of stragglers at one older version
  * no longer shares: they each render, where before the first would install and
  * the rest hit it. That is the deliberate trade — the newest snapshot is the
  * one more arrivals are coming for, so it is what the single slot should hold.
  * It is bounded by how long sessions stay skewed, which is one frame's
  * fan-out, and it costs renders and never wrong bytes. If a real deployment
  * ever shows a persistent skew wide enough for that to matter, the answer is
  * to measure it before widening the bound — not to widen it first.
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
    * is and the entry on hand is not already for these inputs. `render` runs
    * zero times (a hit) or once, never twice for one generation.
    *
    * '''Uncancelable, and `guarantee` would not do instead.''' The hazard is a
    * waiter blocked forever on a slot nobody completes, and there are two
    * windows a finalizer does not cover:
    *
    *   - between the CAS winning and [[fill]] STARTING, `mine` is in the map,
    *     uncompleted, and nothing has run — so a `guarantee` on `fill` never
    *     fires either;
    *   - during the render, `guaranteeCase` does fire, but can only complete
    *     with an ERROR, so one cancelled fiber fails every waiter attached to
    *     it. Avoiding that needs a "producer cancelled" sentinel and a retry in
    *     the waiters: a second mechanism to survive a state, where being
    *     uncancelable means never entering it.
    *
    * Only `poll(e.slot.get)` — the waiter — is cancelable, so a cancellation
    * anywhere in the producer path is deferred until the slot is completed.
    *
    * '''What the caller owes: a render that is BOUNDED.''' It runs inside the
    * mask, so however long it takes is how long a cancellation waits — and
    * these calls are on session fibers (`Server.pull` -> `Patches.resume`),
    * cancelled on disconnect and displacement, with the linger, the reap, the
    * deregistration and the changelog's pruning floor behind that teardown.
    *
    * That obligation is NOT expressible here, and an `IO[String]` is the honest
    * shape for it. A by-name `String` would LOOK like it forbade suspending and
    * would not: a thunk can `Thread.sleep`, take a lock or await a latch just
    * as easily, and the runtime cannot see any of it. All it would buy is
    * making the effect UNTYPED, which is the worse half of the trade —
    * `IO.blocking` and `IO.cede` become inexpressible precisely where they
    * would be the answer.
    *
    * '''So bound the work, do not hide it.''' Today the only caller is
    * `Renderer.renderNodeById` — one node's own markup, children excluded by
    * construction ([[Renderer.renderInputs]] refuses a node whose bytes carry
    * them), so a small walk. If that stops being true the tools are a
    * `Semaphore` sized to the cores, `IO.evalOn` onto a sized pool, or
    * `IO.cede` to break it up — see the `scala-fp` skill. What is not a tool is
    * a signature that makes the cost invisible.
    *
    * @param byteValues
    *   the slots that travel as BYTES, resolved ([[Renderer.byteSlotValues]]).
    *   Equal values mean equal bytes, so an entry carrying the same ones is
    *   reused even though its `inputs` differ — which is every node of a
    *   signal-only tick. `None` where the caller could not answer cheaply, and
    *   the behaviour is then exactly what it was before.
    */
  def apply(
      id: NodeId,
      renderer: Renderer,
      inputs: RenderInputs,
      byteValues: Option[Map[String, String]] = None
  )(
      render: IO[String]
  ): IO[NodeBytes] =
    Deferred[IO, Either[Throwable, NodeBytes]].flatMap { mine =>
      // Only a WAITER is cancelable, and that is what `poll` marks. Note what
      // the critical section does NOT contain: `fill` is an IO VALUE here,
      // built and discarded if this attempt loses — as is `render` itself now
      // that it is one. A losing CAS costs an equality check, never a render.
      IO.uncancelable { poll =>
        entries(id)
          .modify { current =>
            // A renderer swap invalidates every selection at once, so the
            // whole entry goes rather than a bucket of it: nothing a previous
            // dashboard rendered is worth keeping under any selection.
            val here = current.filter(_.renderer eq renderer).map(_.gen)
            // Equal BYTE VALUES mean equal bytes, whatever the entity versions
            // say — which is the whole of a signal-only tick, where the key
            // moved and the bytes did not. Compared only where both sides could
            // answer: a `None` on either is "unknown", never "equal".
            val sameBytes = here.filter(g =>
              byteValues.isDefined && g.byteValues == byteValues
            )
            here.filter(_.inputs == inputs) match {
              case Some(gen) => (current, poll(gen.slot.get))
              // The bytes are known-identical, so the entry's slot is served
              // rather than a render started. A STRAGGLER still installs
              // nothing: re-stamping under its older `inputs` would downgrade
              // the generation and hand the next caller a key that looks stale
              // — the eviction the rule below exists to prevent — and it is
              // served the same bytes either way.
              case None if sameBytes.isDefined =>
                val gen = sameBytes.get
                if (gen.inputs.isAtLeast(inputs)) (current, poll(gen.slot.get))
                else
                  (
                    Some(
                      RenderCache.Entry(renderer, gen.copy(inputs = inputs))
                    ),
                    poll(gen.slot.get)
                  )
              case None if here.exists(_.inputs.isAtLeast(inputs)) =>
                // A STRAGGLER: what is here was rendered from a snapshot at or
                // ahead of this caller's on every entity it reads. Installing
                // would evict bytes other sessions are about to want in order
                // to cache bytes already superseded. So render, serve, and
                // leave the map alone — cancelable, since nothing waits on it.
                (current, poll(fresh(render)))
              case None =>
                val gen = RenderCache.Gen(inputs, byteValues, mine)
                (Some(RenderCache.Entry(renderer, gen)), fill(id, gen, render))
            }
          }
          .flatten
          .rethrow
      }
    }

  /** A render that reaches its one caller and is never cached. */
  private def fresh(render: IO[String]): IO[Either[Throwable, NodeBytes]] =
    render.map(html => NodeBytes(html, Digest.of(html))).attempt

  private def fill(
      id: NodeId,
      gen: RenderCache.Gen,
      render: IO[String]
  ): IO[Either[Throwable, NodeBytes]] =
    render
      .map(html => NodeBytes(html, Digest.of(html)))
      .attempt
      // A failure must not stay in the map: a `Left` left behind poisons that
      // node until its inputs move. Evicting first and completing after is the
      // order that matters — the next caller retries, while the waiters already
      // holding this slot still see the error rather than hanging.
      //
      // Only if it is still OURS: a newer generation may have replaced it
      // while this one rendered, and dropping that would evict a live entry on
      // the strength of a stale failure. Identity, not equality.
      .flatTap {
        case Left(_) =>
          entries(id).update(_.filterNot(_.gen eq gen))
        case Right(_) => IO.unit
      }
      .flatTap(gen.slot.complete(_).void)

  /** Node count — the seam for asserting the bound, since a `MapRef` has no
    * size of its own.
    */
  def size: IO[Int] = IO(live.size)

  /** Generations held across every node — one each, so equal to [[size]]. Named
    * separately because a test asserting the bound should keep asking the
    * question rather than assume the answer.
    */
  def generations: IO[Int] = IO(live.size)
}

private[runtime] object RenderCache {

  /** One node under one renderer: the bytes it currently holds.
    *
    * The renderer is held here rather than on the generation because a swap
    * invalidates the node outright — nothing a previous dashboard rendered is
    * worth keeping.
    */
  private[runtime] case class Entry(renderer: Renderer, gen: Gen)

  /** A node's current generation: the inputs it was rendered from, and the slot
    * its bytes arrive in.
    */
  private[runtime] case class Gen(
      inputs: RenderInputs,
      // The byte slots these bytes were rendered from, where the caller could
      // resolve them. Two generations carrying the same ones carry the same
      // bytes, which is what lets a signal-only tick reuse an entry whose
      // `inputs` have moved. `None` disables the reuse for that generation
      // rather than asserting anything.
      byteValues: Option[Map[String, String]],
      slot: Deferred[IO, Either[Throwable, NodeBytes]]
  )

  def create: IO[RenderCache] =
    IO(new ConcurrentHashMap[NodeId, Entry]()).map(chm =>
      new RenderCache(MapRef.fromConcurrentHashMap(chm), chm)
    )
}
