package fh.view.testkit

import fh.view.model.{DomId, LayoutNode, NodeId, SetId}

/** Node ids as literals, a blanket conversion rather than a wrapper at ~130
  * call sites. [[NodeId]] stops a DOM id being stored as a log key in the
  * server; a test has no such confusion available, since the literal is the
  * spec.
  */
object TestIds {
  given Conversion[String, NodeId] = NodeId.derived(_)

  /** munit's [[munit.Compare]] wants the expected type a subtype of the
    * obtained, and `Set` is invariant. Narrow on purpose: a blanket `Compare[A,
    * B]` would switch off type-safe equality everywhere.
    */
  given munit.Compare[Set[NodeId], Set[String]] = (a, b) => a == b

  /** Same reason, for a DOM id against its literal. */
  given munit.Compare[DomId, String] = (a, b) => a == b

  /** Named helpers, not a conversion: a `SetId` asserts the graph knows this
    * container, a runtime fact a suite can be wrong about, and a conversion
    * would make every `String` silently claim it. The `SetNode()` below is a
    * stand-in.
    */
  def setId(s: String): SetId =
    SetId.of(NodeId.derived(s), LayoutNode.SetNode())
}
