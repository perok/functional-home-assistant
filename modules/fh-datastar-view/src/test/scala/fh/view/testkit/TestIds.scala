package fh.view.testkit

import fh.view.model.{DomId, MemberId, NodeId, SetId}

/** Node ids as literals, for suites that hand-build a
  * [[fh.view.runtime.FragmentLog]] or assert on generated ids.
  *
  * A blanket conversion rather than a wrapper at ~130 call sites, and that is a
  * deliberate trade. [[NodeId]] exists to stop a DOM id (`c_2-self`,
  * `c_2_panel`) from being stored as a log key in the SERVER — a silent,
  * permanent hole, invisible at the time of the mistake. A test writing
  * `log.set("a", …)` has no such confusion available to it: the literal IS the
  * spec. What the type still buys inside a suite is the production signatures
  * it appears in, which no import can loosen.
  */
object TestIds {
  given Conversion[String, NodeId] = NodeId.derived(_)

  /** `assertEquals(renderer.componentsFor(e), Set("c_0"))`: munit's default
    * [[munit.Compare]] wants the expected type to be a subtype of the obtained
    * one, and `Set` is invariant, so the element conversion above does not
    * reach inside the literal. Narrow on purpose — one container, one element
    * type — rather than a blanket `Compare[A, B]`, which would switch off
    * munit's type-safe equality for every other assertion in the suite.
    */
  given munit.Compare[Set[NodeId], Set[String]] = (a, b) => a == b

  /** Same reason, for a DOM id asserted against the literal it must equal
    * (`patchTargetId("c") == "c-self"`).
    */
  given munit.Compare[DomId, String] = (a, b) => a == b

  /** Same trade again, for the two refinements. [[fh.view.model.SetId]] exists
    * to stop a caller reaching a membership question with an id it got from the
    * static index — a distinction that only exists at RUNTIME, between two
    * indexes. A suite naming `"c_0"` as the set it just authored is stating the
    * spec, and has no wrong index to reach for.
    */
  given Conversion[String, SetId] = s => SetId.of(NodeId.derived(s))
  given Conversion[String, MemberId] = s => MemberId.of(NodeId.derived(s))
}
