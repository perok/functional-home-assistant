package fh.view.runtime

import cats.effect.IO
import fh.view.model.NodeId

import java.util.concurrent.ConcurrentHashMap

/** One node's bytes with their digest, so no HTML is hashed twice. */
private[runtime] case class NodeBytes(html: String, digest: Digest)

private[runtime] object NodeBytes {
  def of(html: String): NodeBytes = NodeBytes(html, Digest.of(html))
}

/** Node bytes shared by the sessions one tick wakes, keyed by what the render
  * reads ([[Renderer.renderInputs]], ADR 0012). Per slug; it outlives a
  * renderer swap and invalidates by renderer identity.
  *
  * '''One generation per node, replaced in place.''' Keying by `(node, inputs)`
  * would grow forever: every frame mints new entity versions and old ones are
  * never asked for again. A selection is not in the key: only structure reads
  * one, and structure is never cached. Fetch reads and the variable values a
  * node read are per viewer: viewers on one value share the entry, and two
  * values replace each other's, so a node keeps one entry however many values
  * are posted.
  *
  * '''A memo, not single-flight.''' Sessions that miss together each render,
  * and a later install replaces an earlier one. A `Deferred` and a CAS per miss
  * cost 1.5 kB a node against a 3.2 kB render, while sessions woken by one ring
  * rarely miss together (`RenderCacheContentionSuite` holds one render a
  * frame); a race costs a render, never wrong bytes.
  *
  * '''A straggler never displaces a newer generation''': an install is skipped
  * when what is there is at or ahead of the caller
  * ([[RenderInputs.isAtLeast]]). Unlocked, so a race can still displace one,
  * which costs a render.
  */
private[runtime] final class RenderCache(
    live: ConcurrentHashMap[NodeId, RenderCache.Entry]
) {

  /** A failed `render` installs nothing, so the next caller renders again. */
  def apply(id: NodeId, renderer: Renderer, inputs: RenderInputs)(
      render: => String
  ): NodeBytes =
    live.get(id) match {
      case e: RenderCache.Entry
          if (e.renderer eq renderer) && e.inputs == inputs =>
        e.bytes
      case _ =>
        val bytes = NodeBytes.of(render)
        val overtaken = live.get(id) match {
          case e: RenderCache.Entry =>
            (e.renderer eq renderer) && e.inputs.isAtLeast(inputs)
          case null => false
        }
        if (!overtaken) {
          val _ = live.put(id, RenderCache.Entry(renderer, inputs, bytes))
        }
        bytes
    }

  // Test seam.
  def size: IO[Int] = IO(live.size)
}

private[runtime] object RenderCache {

  private[runtime] case class Entry(
      renderer: Renderer,
      inputs: RenderInputs,
      bytes: NodeBytes
  )

  def create: IO[RenderCache] =
    IO(new RenderCache(new ConcurrentHashMap[NodeId, Entry]()))
}
