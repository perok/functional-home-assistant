package fh.view.runtime

import fh.view.model.NodeId
import fh.view.query.QuerySnapshot

/** The render entry points returning a `String`, outside [[Renderer]] since no
  * production caller uses them. They wrap the production walk but buffer where
  * a page open streams; `SinkStreamingSuite` pins the two sinks to the same
  * bytes and trace.
  */
private[runtime] object RendererTestOps {

  /** Each named group showing that member, as a suite writes it. */
  def picking(panels: (String, Int)*): Selections =
    Selections(None, panels.map((gid, i) => NodeId.derived(gid) -> i).toMap)

  def popupOpen(sid: String): Selections = Selections(Some(sid), Map.empty)

  extension (r: Renderer) {

    /** Without the page shell and without the theme: what a repaint or navigate
      * `inner`-patches into `#dashboard`.
      */
    def renderBody(
        states: Map[String, EntityState],
        selections: Selections = Selections.none
    ): String =
      r.renderBodyTraced(states, selections, fragments = QuerySnapshot.empty)
        .html

    def renderPage(
        states: Map[String, EntityState],
        selections: Selections = Selections.none
    ): String = r.renderPageTraced(states, selections).html

    /** The whole document into a buffer, bytes and trace both. */
    def renderPageTraced(
        states: Map[String, EntityState],
        selections: Selections = Selections.none
    ): r.Traced = {
      val out = Sink.buffer(r.pageBytesHint)
      val own = r.renderPageInto(
        out,
        states,
        selections,
        fragments = QuerySnapshot.empty
      )
      // The whole page is never a patch target — a repaint replaces
      // `#dashboard` wholesale — so it has no second form of its own. Its
      // NODES do, and those are in `own`.
      r.Traced(out.result, own)
    }
  }
}
