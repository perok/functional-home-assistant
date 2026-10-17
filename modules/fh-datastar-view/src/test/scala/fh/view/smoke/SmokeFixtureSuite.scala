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
    // Lock and Unlock, and for a lock reporting OPEN the latch, as its tile's
    // features, outside the tile's tappable row: inside it, a press would also
    // fire the tile's tap.
    val withFeatures = nodes.filter(_.regions.contains("features"))
    assertEquals(withFeatures.map(_.card), List("tile"))
    assert(
      withFeatures.head.subjectEntity.contains(HouseFixture.frontLock.entityId),
      clue = withFeatures.map(n => n.card -> n.subjectEntity)
    )
    val features = withFeatures.flatMap(_.regions("features").flatMap(walk))
    assertEquals(
      features.map(_.card),
      List("fhrow", "button", "button", "button")
    )
  }

  test("the layout-fill fixture builds, its frame on the cells") {
    List(2, 60).foreach { rows =>
      val d = SmokeDashboard.layoutFill(rows)
      d.validated().left.foreach(e => fail(s"${d.slug}: ${e.mkString("; ")}"))
      val cells = walk(d.card).flatMap(_.cell.toList.flatMap(_.classes))
      assertEquals(
        cells.filter(_.startsWith("fh-stick-")).sorted,
        List("fh-stick-bottom", "fh-stick-top"),
        clue = cells
      )
      assert(cells.count(_ == "fh-grow") == 2, clue = cells)
    }
  }
}
