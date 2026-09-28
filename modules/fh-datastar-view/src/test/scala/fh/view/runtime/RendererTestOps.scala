package fh.view.runtime

import fh.view.query.QuerySnapshot

/** The render entry points returning a `String`, outside [[Renderer]] since no
  * production caller uses them. They wrap the production walk but buffer where
  * a page open streams; `SinkStreamingSuite` pins the two sinks to the same
  * bytes and trace.
  */
private[runtime] object RendererTestOps {

  extension (r: Renderer) {

    /** Without the page shell and without the theme: what a repaint or navigate
      * `inner`-patches into `#dashboard`.
      */
    def renderBody(
        states: Map[String, EntityState],
        uiState: Map[String, String] = Map.empty
    ): String =
      r.renderBodyTraced(states, uiState, fragments = QuerySnapshot.empty).html

    def renderPage(
        states: Map[String, EntityState],
        uiState: Map[String, String] = Map.empty,
        popup: Option[String] = None
    ): String = r.renderPageTraced(states, uiState, popup).html

    /** The whole document into a buffer, bytes and trace both. */
    def renderPageTraced(
        states: Map[String, EntityState],
        uiState: Map[String, String] = Map.empty,
        popup: Option[String] = None
    ): r.Traced = {
      val out = Sink.buffer(r.pageBytesHint)
      val own = r.renderPageInto(
        out,
        states,
        uiState,
        popup,
        fragments = QuerySnapshot.empty
      )
      // The whole page is never a patch target — a repaint replaces
      // `#dashboard` wholesale — so it has no second form of its own. Its
      // NODES do, and those are in `own`.
      r.Traced(out.result, own)
    }
  }
}
