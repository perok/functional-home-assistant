package fh.view.model

import io.circe.Decoder

/** The kinds of id the runtime moves around, separated so one cannot be
  * mistaken for another.
  *
  * They were both `String` until the self/mount split
  * (docs/adr/0012-each-session-renders-what-it-is-owed.md) made the distinction
  * load-bearing: a container card's patch targets `<nodeId>-self`, a mount is
  * `<nodeId>_panel`, and neither is the node id the
  * [[fh.view.runtime.FragmentLog]] is keyed by. The ledger renders content FROM
  * its keys, so a DOM id stored as a key is a fragment that can never be
  * rendered again — a silent, permanent hole. That is a type error now rather
  * than a test's obligation.
  *
  * [[NodeId]] -> [[DomId]] is one-way, through
  * [[fh.view.runtime.Renderer.patchTargetId]]. Nothing travels back.
  *
  * Both are `<: String` deliberately: a node id IS a string for interpolation,
  * prefix tests and sanitising, and widening at those uses costs nothing. What
  * the bound does NOT allow is the direction that matters — a bare `String`, or
  * a `DomId`, where a `NodeId` is expected.
  */
opaque type NodeId <: String = String

object NodeId {

  /** Mint a node id. Deliberately awkward to reach: the derivations are
    * [[LayoutNode.pathId]], [[LayoutNode.surfacePrefix]] and
    * [[fh.view.runtime.Renderer.dynamicChildId]], and a node id that came from
    * anywhere else is a bug.
    */
  private[view] def derived(s: String): NodeId = s

  /** `Surface.bakeInto` names a node id on the wire. Parsed at the boundary
    * rather than re-wrapped at each use — `dashboard.json` is an internal
    * format, and `Dashboard.validate` is what checks the relation resolves.
    */
  given Decoder[NodeId] = Decoder[String].map(derived)
}

/** The id of an element a patch TARGETS — `c_2-self`, `c_2_panel`, `popups`.
  * Derived from a [[NodeId]] or authored by the theme; never a log key.
  */
opaque type DomId <: String = String

object DomId {

  private[view] def derived(s: String): DomId = s

  extension (d: DomId) {

    /** The CSS selector for this element, which is what the wire wants. */
    def selector: String = "#" + d
  }
}

/** The name of a Datastar SIGNAL a slot's value lives in — `_c_1__value` (ADR
  * 0017). Neither an element nor a log key: it addresses a slot of a node
  * inside the client's signal store, which is a third space again.
  *
  * Named for the same reason [[NodeId]] and [[DomId]] are. It threads through
  * the renderer, the session record, the SSE frame and the inline seed, and as
  * a bare `String` it reads exactly like the node id it is derived from — which
  * is precisely the confusion that would put one in the other's map.
  */
opaque type SignalId <: String = String

object SignalId {

  /** Mint one. Deliberately awkward to reach: the ONE derivation is
    * [[fh.view.runtime.Renderer.signalName]], and a signal id from anywhere
    * else is a card binding a signal nothing will ever patch.
    */
  private[view] def derived(s: String): SignalId = s
}
