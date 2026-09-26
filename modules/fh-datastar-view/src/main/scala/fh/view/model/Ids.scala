package fh.view.model

import io.circe.Decoder

/** The id kinds, kept apart by type (ADR 0022): the log renders from its keys,
  * so a DOM id stored as a key is a permanent, silent hole. [[NodeId]] ->
  * [[DomId]] is one-way ([[fh.view.runtime.Renderer.elementId]]).
  *
  * `<: String`, since interpolation and prefix tests are harmless; what the
  * bound forbids is a bare `String` or a `DomId` where a `NodeId` is wanted.
  */
opaque type NodeId <: String = String

object NodeId {

  /** The derivations are [[LayoutNode.pathId]], [[LayoutNode.surfacePrefix]]
    * and [[fh.view.runtime.MemberGraph.memberIdOf]]; any other source is a bug.
    */
  private[view] def derived(s: String): NodeId = s

  // `Surface.bakeInto` on the wire; `validate` checks it resolves.
  given Decoder[NodeId] = Decoder[String].map(derived)
}

/** A node known to be a candidate set, at any depth. The only way to get one
  * for an arbitrary id is [[fh.view.runtime.MemberGraph.setContainer]]: asking
  * the static index instead missed nested sets and silently emitted no patches.
  *
  * Consumers are protected; the mint is a guardrail, not a proof — a `SetNode`
  * is an ordinary case class, and `TestIds.setId` fabricates one on purpose.
  */
opaque type SetId <: NodeId = String

object SetId {

  /** `set` is not read: it is evidence the caller has a reason to mint. */
  private[view] def of(
      id: NodeId,
      @annotation.unused set: LayoutNode.SetNode
  ): SetId = id
}

/** A materialised member; minted only by
  * [[fh.view.runtime.MemberGraph.memberIdOf]], so
  * [[fh.view.runtime.MemberGraph.innerSetId]] can require one.
  */
opaque type MemberId <: NodeId = String

object MemberId {
  private[view] def of(id: NodeId): MemberId = id
}

/** A patch target: a node's `.fh-cell`, a bake host, the theme's `popups`.
  * Never a log key.
  */
opaque type DomId <: String = String

object DomId {

  private[view] def derived(s: String): DomId = s

  extension (d: DomId) {
    def selector: String = "#" + d
  }
}

/** A Datastar signal name (ADR 0017): neither an element nor a log key, and as
  * a bare `String` it reads like the node id it derives from.
  */
opaque type SignalId <: String = String

object SignalId {

  /** The one derivation is [[fh.view.runtime.Renderer.signalName]]; any other
    * is a binding nothing will patch.
    */
  private[view] def derived(s: String): SignalId = s

  extension (id: SignalId) {

    /** A dot always separates segments: frames nest by these. One owner for the
      * format, so builder and nester cannot drift. An `Array`, because
      * `Datastar.pathsOf` sorts and indexes it per signal per node.
      */
    def segments: Array[String] = (id: String).split('.')
  }
}

/** Bytes or a real boolean. Only a real `false` turns a boolean attribute off:
  * `""` sets one, and the string `"false"` is a truthy Mustache section, which
  * would disable the control in the plain form. A union rather than an ADT: no
  * allocation per value on every paint.
  */
type SlotValue = String | Boolean

object SlotValue {

  def text(v: SlotValue): String = v match
    case s: String  => s
    case b: Boolean => b.toString

  /** Boxed, not stringified: mustache.java drives a section off
    * `java.lang.Boolean`.
    */
  def scoped(v: SlotValue): AnyRef = v match
    case s: String  => s
    case b: Boolean => java.lang.Boolean.valueOf(b)
}
