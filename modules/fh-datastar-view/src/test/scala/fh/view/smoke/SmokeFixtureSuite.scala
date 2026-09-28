package fh.view.smoke

import fh.view.model.LayoutNode
import fh.view.testkit.{HouseFixture, SmokeDashboard}

/** The smoke fixtures without a browser. Every suite evaluating
  * [[SmokeDashboard]] is `Slow`, so a broken entry would otherwise reach CI as
  * six red suites rather than one named failure, invisible on a machine without
  * Playwright.
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
    // A lock reporting OPEN gets a latch beside its tile. Through the wrapper,
    // since the latch opens a confirmation and names no entity. They must be
    // siblings: nested in the tile, the latch would also fire lock/unlock.
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
