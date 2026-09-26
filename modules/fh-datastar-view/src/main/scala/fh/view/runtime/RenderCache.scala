package fh.view.runtime

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.std.MapRef
import fh.view.model.NodeId

import java.util.concurrent.ConcurrentHashMap

/** One node's bytes with their digest, so no HTML is hashed twice. */
private[runtime] case class NodeBytes(html: String, digest: Digest)

private[runtime] object NodeBytes {
  def of(html: String): NodeBytes = NodeBytes(html, Digest.of(html))
}

/** Single-flight cache of node bytes, keyed by what the render reads
  * ([[Renderer.renderInputs]], ADR 0012). Per slug; it outlives a renderer swap
  * and invalidates by renderer identity, since a rotated map would let a pull
  * that read the old renderer write into the new one.
  *
  * '''One generation per node, replaced in place.''' Keying by `(node, inputs)`
  * would grow forever: every frame mints new entity versions and old ones are
  * never asked for again. A selection is not in the key: only structure reads
  * one, and structure is never cached (`RenderCacheContentionSuite` holds 1.0
  * renders a frame across viewers and tabs). Query reads are per viewer, so two
  * windows evict each other (issue #209).
  *
  * '''A straggler never displaces the current generation''': an install is
  * refused when what is there is at or ahead of the caller
  * ([[RenderInputs.isAtLeast]]). The cost is that stragglers at one older
  * version each render — bounded by one frame's skew, never wrong bytes.
  * Measure before widening the bound.
  *
  * A [[MapRef]], so contention on one node never retries another's caller.
  */
private[runtime] final class RenderCache(
    entries: MapRef[IO, NodeId, Option[RenderCache.Entry]],
    live: ConcurrentHashMap[NodeId, RenderCache.Entry]
) {

  /** `render` runs zero times or once per generation.
    *
    * '''Uncancelable; `guarantee` would not do''': between the CAS and [[fill]]
    * starting nothing has run, and during the render `guaranteeCase` could only
    * fail every waiter. Only the waiter (`poll`) is cancelable.
    *
    * '''So the caller owes a bounded render''': a cancellation (disconnect,
    * displacement) waits for it. `IO[String]`, not a by-name `String`, which
    * would hide the effect without forbidding it. Today it is one node's own
    * markup; if that grows, bound it (a `Semaphore`, `evalOn`, `cede`).
    *
    * @param byteValues
    *   [[Renderer.byteSlotValues]]: equal values reuse an entry whose `inputs`
    *   moved, as on a signal-only tick. `None` disables that.
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
      // `fill` is only built here; a losing CAS costs an equality check.
      IO.uncancelable { poll =>
        entries(id)
          .modify { current =>
            val here = current.filter(_.renderer eq renderer).map(_.gen)
            // `None` on either side is unknown, never equal.
            val sameBytes = here.filter(g =>
              byteValues.isDefined && g.byteValues == byteValues
            )
            here.filter(_.inputs == inputs) match {
              case Some(gen) => (current, poll(gen.slot.get))
              // Same bytes: serve the slot. A straggler does not re-stamp, which
              // would downgrade the generation.
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
              // A straggler: render and serve, leaving the newer entry.
              // Cancelable, since nothing waits on it.
              case None if here.exists(_.inputs.isAtLeast(inputs)) =>
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
      // Evict a failure before completing, so the next caller retries while
      // current waiters see the error. Only our own generation, by identity:
      // a newer one may have replaced it.
      .flatTap {
        case Left(_) =>
          entries(id).update(_.filterNot(_.gen eq gen))
        case Right(_) => IO.unit
      }
      .flatTap(gen.slot.complete(_).void)

  // Test seams: a `MapRef` has no size of its own.
  def size: IO[Int] = IO(live.size)

  def generations: IO[Int] = IO(live.size)
}

private[runtime] object RenderCache {

  private[runtime] case class Entry(renderer: Renderer, gen: Gen)

  private[runtime] case class Gen(
      inputs: RenderInputs,
      byteValues: Option[Map[String, String]],
      slot: Deferred[IO, Either[Throwable, NodeBytes]]
  )

  def create: IO[RenderCache] =
    IO(new ConcurrentHashMap[NodeId, Entry]()).map(chm =>
      new RenderCache(MapRef.fromConcurrentHashMap(chm), chm)
    )
}
