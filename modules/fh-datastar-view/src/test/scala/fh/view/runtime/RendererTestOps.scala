package fh.view.runtime

/** The convenience forms of the render entry points, for tests that want the
  * bytes back as a `String`.
  *
  * These used to live on [[Renderer]] itself and no production caller ever
  * reached them: the server streams the document through
  * [[Renderer.renderPageInto]] and patches through
  * [[Renderer.renderBodyTraced]]. Kept here so that stays visible — a method on
  * the renderer reads as part of the shipped path, and these are not.
  *
  * They wrap the production methods rather than re-implementing them, so a test
  * written against one is still exercising the walk the server runs. What it is
  * NOT exercising is the server's SINK: these buffer, and a page open streams
  * ([[Sink.Streaming]] differs from [[Sink.Buffer]] at `digesting`).
  * `SinkStreamingSuite` is what pins the two to the same bytes and the same
  * trace, so a test written here is not silently asserting about one of them.
  */
private[runtime] object RendererTestOps {

  extension (r: Renderer) {

    /** Without the page shell and without the theme: what a repaint or navigate
      * `inner`-patches into `#dashboard`.
      */
    def renderBody(
        states: Map[String, EntityState],
        uiState: Map[String, String] = Map.empty
    ): String = r.renderBodyTraced(states, uiState).html

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
      val own = r.renderPageInto(out, states, uiState, popup)
      // The whole page is never a patch target — a repaint replaces
      // `#dashboard` wholesale — so it has no second form of its own. Its
      // NODES do, and those are in `own`.
      r.Traced(out.result, own)
    }
  }
}
