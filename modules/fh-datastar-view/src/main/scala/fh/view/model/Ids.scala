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
  * [[SetId]] and [[MemberId]] refine it further: same string, but the type says
  * WHICH KIND of node it names, so "this container is a candidate set" is a
  * value obtained once rather than a question each caller must remember to ask
  * of the right index.
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
    * [[fh.view.runtime.Renderer.memberIdOf]], and a node id that came from
    * anywhere else is a bug.
    */
  private[view] def derived(s: String): NodeId = s

  /** `Surface.bakeInto` names a node id on the wire. Parsed at the boundary
    * rather than re-wrapped at each use — `dashboard.json` is an internal
    * format, and `Dashboard.validate` is what checks the relation resolves.
    */
  given Decoder[NodeId] = Decoder[String].map(derived)
}

/** A node id KNOWN to name a candidate-set container ([[LayoutNode.SetNode]]),
  * at any nesting depth.
  *
  * Every node id is a string and they all read alike, so "is this container a
  * set?" used to be a question each caller had to remember to ask — of the
  * right index. Getting it wrong was silent in the worst way: an inner set is
  * NOT in the static index (it hangs off a member), so selecting from the index
  * gave correct ids and correct markup and emitted no patches at all, forever.
  *
  * So the answer is a VALUE now, and **the constructor asks for the proof**:
  * you cannot mint a `SetId` from an id alone, only from an id together with
  * the [[LayoutNode.SetNode]] it names. A signature taking a `SetId` therefore
  * cannot be satisfied by anything the static index handed back, and the bug
  * above stops being expressible rather than merely discouraged.
  *
  * The way to get one for an id that arrives from somewhere else — a log key, a
  * mutation's container — is [[fh.view.runtime.MemberGraph.setContainer]],
  * which looks the node up and hands back `None` when there is none.
  */
opaque type SetId <: NodeId = String

object SetId {

  /** Mint one. `set` is not read; it is the EVIDENCE, and demanding it is the
    * whole mechanism — every call site holds a `SetNode` it matched on or
    * looked up, so passing it costs nothing and forging one is not possible.
    */
  private[view] def of(
      id: NodeId,
      @annotation.unused set: LayoutNode.SetNode
  ): SetId = id
}

/** A node id KNOWN to name a MATERIALISED member of a candidate set.
  *
  * Minted only by [[fh.view.runtime.MemberGraph.memberIdOf]]. What it buys is
  * the other end of the nested-set id scheme: `<member>_<clause>_<child path>`
  * is only meaningful under a member, and
  * [[fh.view.runtime.MemberGraph.innerSetId]] now says so in its signature
  * rather than in a comment.
  */
opaque type MemberId <: NodeId = String

object MemberId {
  private[view] def of(id: NodeId): MemberId = id
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
