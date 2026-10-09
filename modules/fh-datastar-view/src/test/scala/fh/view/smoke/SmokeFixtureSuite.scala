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
    // A lock reporting OPEN gets a latch as its tile's feature, outside the
    // tile's tappable row: inside it, the latch would also fire lock/unlock.
    val withFeatures = nodes.filter(_.regions.contains("features"))
    assertEquals(withFeatures.map(_.card), List("tile"))
    assert(
      withFeatures.head.subjectEntity.contains(HouseFixture.frontLock.entityId),
      clue = withFeatures.map(n => n.card -> n.subjectEntity)
    )
    val features = withFeatures.flatMap(_.regions("features").flatMap(walk))
    assertEquals(features.map(_.card), List("button"))
  }
}
