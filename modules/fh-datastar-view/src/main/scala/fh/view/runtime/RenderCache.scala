package fh.view.runtime

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.std.MapRef
import fh.view.model.NodeId

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

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
  * next ask for that node replaces the whole entry, every selection of it at
  * once — which is what reclaims the space without a sweep. Rotating the map
  * instead would leave a window where a pull that read the previous renderer
  * writes its bytes into the fresh cache.
  *
  * The value is a [[Deferred]] rather than the bytes: the first caller to want
  * a key inserts an empty one and renders, everyone else finds it and waits, so
  * N sessions wanting the same node at the same instant cost one render.
  *
  * '''ONE GENERATION PER (NODE, SELECTION), and that bound is not optional.'''
  * Keying by `(nodeId, inputs)` outright would grow without bound: the shared
  * pass selects exactly the nodes binding an entity that just moved
  * (`Renderer.componentsFor`), so every batch mints new ENTITY VERSIONS and the
  * old ones are never asked for again — unbounded retention of HTML in exchange
  * for hits that do not happen.
  *
  * But the two halves of a [[RenderInputs]] behave nothing alike, and that is
  * what this splits on:
  *
  *   - `entities` CHURNS. Every frame moves a version, so a generation for the
  *     previous one is dead the moment it is replaced. One per bucket, replaced
  *     in place, exactly as before.
  *   - `vars` DOES NOT. It is the resolved structural selection (`bakeIndex`),
  *     which ranges over a bake group's members — a small finite set fixed by
  *     the dashboard, not by traffic. Viewers on different tabs differ HERE and
  *     nowhere else.
  *
  * So a node holds one generation per selection it has been asked for, and the
  * map is bounded by nodes × that node's bake group size. Still no timer and no
  * sweep.
  *
  * '''What that buys, measured.''' Before it, two viewers on different tabs
  * evicted each other's entry on every frame, and viewers beyond the first on
  * each tab then missed a cache their own neighbour had just filled: 3+3
  * viewers across two tabs cost ~3.5 renders a frame against a floor of 2, and
  * the drift went the way of one render per VIEWER. Bucketed, the cost is the
  * number of distinct selections in flight — the floor, which is irreducible
  * because those viewers are owed genuinely different bytes.
  * `RenderCacheContentionSuite` holds both numbers.
  *
  * '''Still UNFIXED: an older generation displaces a newer one.''' Nothing here
  * compares versions — a key matches or it does not — so a straggler pulling at
  * an older snapshot installs its generation over the current one. Three
  * sessions racing (newest, laggard, newest) cost three renders where two would
  * do: the laggard's install throws away bytes the third still wanted. The
  * waste is not the laggard's own render, which it needed; it is what that
  * install DISCARDS.
  *
  * Bucketing on `vars` does not help — the stragglers share a selection and
  * differ on the entity half — and neither does keeping N generations, which
  * would only widen the window. The candidate fix is a dominance rule: decline
  * to install a generation every one of whose versions is at or behind the
  * entry already there, and render for that caller without caching. Its cost is
  * that a CLUSTER of laggards at one old version stops sharing with each other.
  * Not measured, and this suite's harness cannot see it — `LiveWorld.change`
  * waits for every session before the next frame, so version skew across
  * sessions never arises there.
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
    * That obligation is NOT expressible here and never was. This took an
    * `IO[String]` because it used to take a by-name `String`, which looked like
    * it forbade suspending and did nothing of the kind: a thunk can
    * `Thread.sleep`, take a lock or await a latch just as easily — the suite's
    * own `Gated` fixture did exactly that — and the runtime cannot see any of
    * it. All the by-name bought was making the effect UNTYPED, which is the
    * worse half of the trade: `IO.blocking` and `IO.cede` become inexpressible
    * precisely where they would be the answer.
    *
    * '''So bound the work, do not hide it.''' Today the only caller is
    * `Renderer.renderNodeById` — one node's own markup, children excluded by
    * construction ([[Renderer.renderInputs]] refuses a node whose bytes carry
    * them), so a small walk. If that stops being true the tools are a
    * `Semaphore` sized to the cores, `IO.evalOn` onto a sized pool, or
    * `IO.cede` to break it up — see the `scala-fp` skill. What is not a tool is
    * a signature that makes the cost invisible.
    */
  def apply(id: NodeId, renderer: Renderer, inputs: RenderInputs)(
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
            val ours = current.filter(_.renderer eq renderer)
            ours
              .flatMap(_.gens.get(inputs.vars))
              .filter(_.inputs == inputs) match {
              case Some(gen) => (current, poll(gen.slot.get))
              case None      =>
                val gen = RenderCache.Gen(inputs, mine)
                val gens = ours.fold(RenderCache.NoGens)(_.gens)
                (
                  Some(
                    RenderCache.Entry(renderer, gens.updated(inputs.vars, gen))
                  ),
                  fill(id, gen, render)
                )
            }
          }
          .flatten
          .rethrow
      }
    }

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
      // Only the bucket that is still OURS: a newer generation may have
      // replaced it while this one rendered, and dropping that would evict a
      // live entry on the strength of a stale failure. Identity, not the key —
      // a bucket refilled for the same selection is still not this generation.
      .flatTap {
        case Left(_) =>
          entries(id).update(
            _.map(e => e.copy(gens = e.gens.filterNot((_, g) => g eq gen)))
              .filter(_.gens.nonEmpty)
          )
        case Right(_) => IO.unit
      }
      .flatTap(gen.slot.complete(_).void)

  /** Node count — the seam for asserting the bound, since a `MapRef` has no
    * size of its own.
    */
  def size: IO[Int] = IO(live.size)

  /** Generations held across every node: nodes × selections, the bound this
    * widened to. [[size]] alone can no longer see it.
    */
  def generations: IO[Int] =
    IO(live.values.iterator.asScala.map(_.gens.size).sum)
}

private[runtime] object RenderCache {

  /** One node under one renderer: its generations, bucketed by the SELECTION
    * they were rendered for ([[RenderInputs.vars]]).
    *
    * The renderer is held here rather than per generation because a swap
    * invalidates all of them together, and it is compared by IDENTITY (`eq`).
    * Inputs alone are not enough to survive a hot swap: a dashboard edit
    * changes the MARKUP while the entity versions it reads stay exactly where
    * they were, so an unchanged key would answer with the previous dashboard's
    * bytes.
    */
  private[runtime] case class Entry(
      renderer: Renderer,
      gens: Map[Map[String, String], Gen]
  )

  /** One selection's current generation: the inputs in full — the entity
    * versions as well as the selection that buckets it — and the slot its bytes
    * arrive in.
    */
  private[runtime] case class Gen(
      inputs: RenderInputs,
      slot: Deferred[IO, Either[Throwable, NodeBytes]]
  )

  private val NoGens: Map[Map[String, String], Gen] = Map.empty

  def create: IO[RenderCache] =
    IO(new ConcurrentHashMap[NodeId, Entry]()).map(chm =>
      new RenderCache(MapRef.fromConcurrentHashMap(chm), chm)
    )
}
