package fh.view.model

import io.circe.Decoder

/** The kinds of id the runtime moves around, separated so one cannot be
  * mistaken for another. `docs/adr/0022-ids-carry-what-they-name.md` owns the
  * decision, the three failures that motivated it, and what the mint does and
  * does not guarantee.
  *
  * They were both `String` until
  * docs/adr/0012-each-session-renders-what-it-is-owed.md made the distinction
  * load-bearing: a bake host is `<nodeId>_<bakeAs>` and the popup host is the
  * page-level `popups`, and neither is the node id the
  * [[fh.view.runtime.FragmentLog]] is keyed by. The ledger renders content FROM
  * its keys, so a DOM id stored as a key is a fragment that can never be
  * rendered again — a silent, permanent hole. That is a type error now rather
  * than a test's obligation.
  *
  * [[NodeId]] -> [[DomId]] is one-way, through
  * [[fh.view.runtime.Renderer.elementId]] — a node's patch target is its own
  * `.fh-cell`. Nothing travels back.
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
    * [[fh.view.runtime.MemberGraph.memberIdOf]], and a node id that came from
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
  * So the answer is a VALUE now. The way to get one for an id that arrives from
  * somewhere else — a log key, a mutation's container — is
  * [[fh.view.runtime.MemberGraph.setContainer]], which looks the node up and
  * hands back `None` when there is none.
  *
  * '''Where the strength actually is.''' Every CONSUMER is protected: a
  * signature taking a `SetId` cannot be satisfied by an id straight out of the
  * static index, so the bug above is not reachable by accident. The MINT is a
  * guardrail rather than a proof — [[SetId.of]] narrows it to callers holding a
  * `SetNode`, which is a real narrowing (all four production mints have one in
  * hand for an honest reason) but not an impossibility: `LayoutNode.SetNode` is
  * an ordinary case class with all-default parameters, so anything inside
  * `fh.view` can fabricate one. `TestIds.setId` does exactly that,
  * deliberately. Closing that would mean no public constructor here at all,
  * with minting folded into `MemberGraph` — worth doing only if a wrong mint
  * ever actually happens.
  */
opaque type SetId <: NodeId = String

object SetId {

  /** Mint one. `set` is not read; it is EVIDENCE, and asking for it is what
    * keeps the mint at sites that have a reason to be minting — see the note on
    * [[SetId]] for what that does and does not guarantee.
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

/** The id of an element a patch TARGETS — a node's own `.fh-cell`, a bake
  * host's `c_2_panel`, the theme's `popups`. Derived from a [[NodeId]] or
  * authored by the theme; never a log key.
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

  extension (id: SignalId) {

    /** The name as a PATH. A dot is a separator, never a character in a
      * segment: the pinned bundle rewrites `$_e.light.a.state` into bracket
      * indexing, and `datastar-patch-signals` deep-merges an object, so a frame
      * has to carry the nesting rather than one dotted key.
      *
      * Lives here so the format has ONE owner. `Renderer.signalName` builds it
      * and `Datastar` nests by it; with the split spelled out at the far end,
      * the two could drift and the failure would be silent — a signal patched
      * under a key nothing is bound to.
      *
      * An `Array`, not a `List`: `Datastar.pathsOf` sorts these and indexes
      * into them, and this runs once per signal per node.
      */
    def segments: Array[String] = (id: String).split('.')
  }
}
