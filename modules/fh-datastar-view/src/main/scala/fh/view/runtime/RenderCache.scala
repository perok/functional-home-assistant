package fh.view.runtime

import cats.effect.IO
import fh.view.model.{NodeId, SlotValue}

import java.util.concurrent.ConcurrentHashMap

/** Node bytes shared by the sessions one tick wakes, keyed by the values they
  * were rendered from ([[Renderer.byteSlotValues]], ADR 0012). Per slug; it
  * outlives a renderer swap and invalidates by renderer identity.
  *
  * '''One entry per node, replaced in place.''' A selection is not in the key:
  * only structure reads one, and structure is never cached. A fetch answer or a
  * variable reaches the bytes through a slot's value, so viewers on one value
  * share the entry and two values replace each other's.
  *
  * '''A memo, not single-flight.''' Sessions that miss together each render,
  * and a later install replaces an earlier one. A `Deferred` and a CAS per miss
  * cost 1.5 kB a node against a 3.2 kB render, while sessions woken by one ring
  * rarely miss together (`RenderCacheContentionSuite` holds one render a
  * frame). Bytes are a function of their key, so a race or a straggler costs a
  * render, never wrong bytes.
  */
private[runtime] final class RenderCache(
    live: ConcurrentHashMap[NodeId, RenderCache.Entry]
) {

  /** A failed `render` installs nothing, so the next caller renders again. */
  def apply(id: NodeId, renderer: Renderer, values: Map[String, SlotValue])(
      render: => String
  ): String =
    live.get(id) match {
      case e: RenderCache.Entry
          if (e.renderer eq renderer) && e.values == values =>
        e.html
      case _ =>
        val html = render
        val _ = live.put(id, RenderCache.Entry(renderer, values, html))
        html
    }

  // Test seam.
  def size: IO[Int] = IO(live.size)
}

private[runtime] object RenderCache {

  private[runtime] case class Entry(
      renderer: Renderer,
      values: Map[String, SlotValue],
      html: String
  )

  def create: IO[RenderCache] =
    IO(new RenderCache(new ConcurrentHashMap[NodeId, Entry]()))
}
