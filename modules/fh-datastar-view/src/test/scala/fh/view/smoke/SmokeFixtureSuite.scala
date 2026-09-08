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
    // reporting OPEN gets a latch as well as its tile.
    //
    // Asserted through the WRAPPER rather than by the lock's entity id: the
    // latch names no entity of its own any more, because it opens a
    // confirmation rather than calling `lock/open` itself. What still has to
    // hold is that the two are SIBLINGS — a latch nested inside the tappable
    // tile would fire the tile's own lock/unlock on the same press.
    val features = nodes.filter(_.card == "cardFeatures")
    assertEquals(features.map(_.card), List("cardFeatures"))
    val inside = features.flatMap(_.allChildren.flatMap(walk))
    assertEquals(inside.map(_.card).sorted, List("button", "entityCard"))
    assert(
      inside.exists(n =>
        n.card == "entityCard" &&
          n.subjectEntity.contains(HouseFixture.frontLock.entityId)
      ),
      clue = inside.map(n => n.card -> n.subjectEntity)
    )
  }
}
