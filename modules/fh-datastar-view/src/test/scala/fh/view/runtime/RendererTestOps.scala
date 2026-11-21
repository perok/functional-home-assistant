package fh.view.runtime

import fh.view.model.{Ix, NodeId}
import fh.view.fetch.FetchSnapshot

import scala.jdk.CollectionConverters.*

/** A standalone [[Shared]] for a suite outside the package, which cannot name
  * the type.
  */
def sharedOf(states: Map[String, EntityState]) = Shared.of(states)

/** For a run over a dashboard whose decisions read no fetch. */
val noDecisionFetches: Set[fh.view.model.SlotRead] => cats.effect.IO[
  FetchSnapshot
] = reads =>
  cats.effect.IO.raiseError(AssertionError(s"a decision asked for $reads"))

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

  extension (t: Renderer#Traced) {

    /** What each own-rendering node painted, by the id a suite names it with.
      */
    def own: Map[NodeId, Painted] =
      t.painted.map { case (ix, p) => t.index.id(ix) -> p }
  }

  extension (r: Renderer) {

    /** Without the page shell and without the theme: what a repaint or navigate
      * `inner`-patches into `#dashboard`. `vars` are the viewer's values, which
      * a node reading a variable outside a pick needs (the shipped tab bar's
      * buttons do).
      */
    def renderBody(
        states: Map[String, EntityState],
        selections: Selections = Selections.none,
        vars: VarEnv = Map.empty
    ): String =
      r.renderBodyTraced(
        Shared.of(states),
        Viewing(selections, FetchSnapshot.of(Map.empty, vars))
      ).html

    def renderPage(
        states: Map[String, EntityState],
        selections: Selections = Selections.none
    ): String = r.renderPageTraced(states, selections).html

    /** What a client holds for `id` once sent that node's patch alone: the
      * byte-slot values its bytes came from.
      */
    def heldFor(id: NodeId, states: Map[String, EntityState]): Option[Held] =
      r.byteSlotValues(id, Shared.of(states), FetchSnapshot.empty)
        .map(v => Held(Some(v)))

    /** Where the node a suite names by id sits in the tree, which is what a
      * session's `holds` is keyed by. The one node, not a member's clause.
      */
    def ixOf(id: NodeId): Ix = r.index.ixsOf(id) match
      case List(only) => only
      case other      => throw AssertionError(s"'$id' is not one node: $other")

    /** What keys a node's render in the cache: its place and its byte-slot
      * values, which alone cannot tell two clauses of one member apart.
      */
    def keyOf(
        id: NodeId,
        shared: Shared,
        fragments: FetchSnapshot
    ): Option[(Ix, ByteValues)] =
      r.ownValues(id, shared, fragments).map(o => (o.ix, o.values))

    /** The whole document into a buffer, bytes and trace both. */
    def renderPageTraced(
        states: Map[String, EntityState],
        selections: Selections = Selections.none
    ): r.Traced = {
      val out = Sink.buffer(r.pageBytesHint)
      val trace = r.pageInto(
        out,
        Shared.of(states),
        Viewing(selections, FetchSnapshot.empty)
      )
      // The whole page is never a patch target — a repaint replaces
      // `#dashboard` wholesale — so it has no second form of its own. Its
      // NODES do, and those are in `own`.
      r.Traced(
        out.result,
        r.index.roots.head,
        trace.own.asScala.toMap,
        trace.seeded.asScala.toMap,
        trace.shown.asScala.toMap
      )
    }
  }
}
