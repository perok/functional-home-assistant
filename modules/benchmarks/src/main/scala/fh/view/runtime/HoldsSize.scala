package fh.view.runtime

import fh.view.model.Dashboard
import fh.view.runtime.RendererTestOps.*
import org.openjdk.jol.info.GraphLayout

/** Retained bytes of one session's `holds` after its first paint, per fixture.
  * The renderer's own interned values are reachable from it too, so the second
  * column is what one more session costs: the holds minus what the renderer
  * already keeps alive.
  */
object HoldsSize {
  import RenderBench.*

  def main(args: Array[String]): Unit = {
    val st = states(Leaves)
    val fixtures = List(
      "tree" -> Dashboard(cards, tree(Leaves, 4, signals = true)),
      "set" -> Dashboard(cards, setTree(Leaves)),
      "setOrdered" -> Dashboard(cards, setTree(Leaves, orderBy = LitBrightest))
    )
    fixtures.foreach { (name, dash) =>
      val r = Renderer.create(dash)
      val holds = r.renderPageTraced(st).painted.map { case (ix, p) =>
        ix -> p.held
      }
      val total = GraphLayout.parseInstance(holds).totalSize()
      val own = GraphLayout
        .parseInstance(holds)
        .subtract(GraphLayout.parseInstance(r))
        .totalSize()
      println(s"$name\tentries=${holds.size}\ttotal=$total\tperSession=$own")
    }
  }
}
