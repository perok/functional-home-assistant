package fh.view.testkit

import fh.view.model.NodeId

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
}
