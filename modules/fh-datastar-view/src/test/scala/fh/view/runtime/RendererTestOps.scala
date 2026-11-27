package fh.view.runtime

import fh.view.model.{Ix, NodeId}
import fh.view.fetch.FetchSnapshot

import scala.jdk.CollectionConverters.*

/** A standalone [[Shared]] for a suite outside the package, which cannot name
  * the type.
  */
def sharedOf(states: Map[String, EntityState]) = Shared.of(states)

/** The values of a viewer who has chosen `value` of `name` at the node
  * `declarer`, over the declared ones; for a suite outside the package.
  */
def choosing(
    r: Renderer,
    declarer: String,
    name: String,
    value: String
): VarEnv =
  r.vars.env(Map(VarKey(fh.view.model.NodeId.derived(declarer), name) -> value))

/** [[sharedOf]], read as a viewer holding `env`'s values. */
def viewedOf(states: Map[String, EntityState], env: VarEnv) =
  Shared.of(states).viewing(env)

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

  extension (t: Renderer#Traced) {

    /** What each own-rendering node painted, by the id a suite names it with.
      */
    def own: Map[NodeId, Painted] =
      t.painted.map { case (ix, p) => t.index.id(ix) -> p }
  }

  extension (r: Renderer) {

    /** Without the page shell and without the theme: the root a repaint morphs.
      * `vars` are the viewer's values, the declared ones unless given: they
      * decide a pick over a variable and what a node reading one outside a pick
      * shows.
      */
    def renderBody(
        states: Map[String, EntityState],
        vars: VarEnv = r.vars.env(Map.empty)
    ): String =
      r.renderBodyTraced(
        Shared.of(states).viewing(vars),
        Viewing(FetchSnapshot.of(Map.empty, vars))
      ).html

    def renderPage(
        states: Map[String, EntityState],
        vars: VarEnv = r.vars.env(Map.empty)
    ): String = r.renderPageTraced(states, vars).html

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
        vars: VarEnv = r.vars.env(Map.empty)
    ): r.Traced = {
      val out = Sink.buffer(r.pageBytesHint)
      val trace = r.pageInto(
        out,
        Shared.of(states).viewing(vars),
        Viewing(FetchSnapshot.empty)
      )
      // The whole page is never a patch target — a repaint morphs the root
      // wholesale — so it has no second form of its own. Its NODES do, and
      // those are in `own`.
      r.Traced(
        out.result,
        r.index.root,
        trace.own.asScala.toMap,
        trace.seeded.asScala.toMap,
        trace.shown.asScala.toMap
      )
    }
  }
}
