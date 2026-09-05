package fh.view.smoke

import fh.view.model.LayoutNode
import fh.view.testkit.{HouseFixture, SmokeDashboard}

/** The smoke fixtures without a browser.
  *
  * [[SmokeDashboard]] is real Pkl, and every suite that evaluates it is `Slow`
  * — so a typo in the entry, or a composition that stops composing, is
  * invisible where there is no browser driver and reaches CI as six red suites
  * rather than one named failure. This is that named failure, and it is what
  * makes the fixture editable from a machine that cannot run Playwright.
  */
class SmokeFixtureSuite extends munit.FunSuite {

  private def walk(node: LayoutNode): List[LayoutNode.Component] =
    node match {
      case c: LayoutNode.Component => c :: c.allChildren.flatMap(walk)
      case _: LayoutNode.SetNode   => Nil
    }

  private lazy val nodes: List[LayoutNode.Component] =
    walk(SmokeDashboard.dashboard.card)

  test("the smoke dashboard builds") {
    assert(nodes.sizeIs > 1, clue = nodes.map(_.card))
  }

  test("the lock composition places both of its controls") {
    // What `c.lock.controls` decides, and the only thing it decides: a lock
    // reporting OPEN gets a latch button as well as its tile. The visual
    // baseline shoots the pair; this says the pair exists.
    val onTheLock = nodes
      .filter(_.subjectEntity.contains(HouseFixture.frontLock.entityId))
      .map(_.card)
    assertEquals(onTheLock.sorted, List("button", "entityCard"))
  }
}
