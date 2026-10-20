package fh.view.runtime

import fh.view.build.DashboardBuild
import fh.view.model.{Activation, Dashboard, NodeId}
import fh.view.testkit.{FixtureEntity, HouseFixture, PklFixture}
import io.circe.parser.parse

import scala.util.Random

/** [[SurfaceGraph.affectedStateGroups]] finds flips from the changed entities.
  * The reference here walks every group from the roots instead, descending
  * only through branches that are shown and did not move: for random frames
  * the two must name the same groups.
  */
class StateFlipIndexSuite extends munit.FunSuite {

  private def graphOf(d: Dashboard): SurfaceGraph =
    Renderer
      .fromValidated(d.validated().fold(e => fail(e.mkString("; ")), identity))
      .surfaces

  private def fixture(name: String): Dashboard = {
    val wire = os.read(
      os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "resources" /
        "snapshots" / s"$name.json"
    )
    DashboardBuild
      .hoistInlineSurfaces(parse(wire).fold(e => fail(s"$name: $e"), identity))
      .as[Dashboard]
      .fold(e => fail(s"$name: $e"), identity)
  }

  private def key(e: FixtureEntity) = s"dump.entities.${e.dumpKey}"
  private val kitchen = key(HouseFixture.kitchenLight)
  private val living = key(HouseFixture.livingRoomLight)
  private val door = key(HouseFixture.frontDoor)
  private val tv = key(HouseFixture.tv)
  private val lock = key(HouseFixture.frontLock)

  // Both clauses hold an If, one of them nested, each reading an entity that
  // is not the member.
  private lazy val inClauses: Dashboard = PklFixture.buildDashboard(
    "flip-index",
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
       |import "@fh-dashboard/theme.pkl" as th
       |import "@fh-home/dump.pkl" as dump
       |
       |theme = ${PklFixture.dummyTheme}
       |
       |card = (c.column) {
       |  children {
       |    q.from(List($kitchen, $living))
       |      .caseOf(q.eq(q.stateProp, "on"), (l) -> (c.column) {
       |        children {
       |          c.entityCard(l)
       |          c.iff(q.entity($door).stateIs("on"))
       |            .then((c.column) { children {
       |              c.iff(q.entity($tv).stateIs("on"))
       |                .then(c.title("tv")).`else`(c.title("no tv"))
       |            } })
       |            .`else`(c.title("shut"))
       |        }
       |      })
       |      .`else`((l) -> (c.column) {
       |        children {
       |          c.iff(q.entity($lock).stateIs("locked"))
       |            .then(c.title("locked"))
       |        }
       |      })
       |      .build()
       |  }
       |}
       |""".stripMargin
  )

  private def reference(
      g: SurfaceGraph,
      before: Map[String, EntityState],
      now: Map[String, EntityState],
      visible: Set[String]
  ): List[NodeId] = {
    def at(root: String) =
      g.stateBakeOwnerIds.toList.filter(g.rootOf(_).contains(root))
    def walk(root: String): List[NodeId] = at(root).flatMap { gid =>
      val sel = g.resolveActiveByState(gid, now)
      if (!g.groupShown(gid, now)) Nil
      else if (
        g.groupShown(gid, before) && g.resolveActiveByState(gid, before) != sel
      ) List(gid)
      else sel.toList.flatMap(i => walk(g.bakeGroup(gid)(i)))
    }
    (walk("") ++ visible.toList.flatMap(walk)).distinct.sorted
  }

  private val alphabet = List("on", "off", "locked", "unlocked")

  private def agree(name: String, d: Dashboard): Unit = {
    val g = graphOf(d)
    assert(g.stateBakeOwnerIds.nonEmpty, s"$name has no state group to flip")
    val userSurfaces = d.surfaces.collect {
      case (sid, s) if !s.activation.isInstanceOf[Activation.State] => sid
    }.toSet
    val rnd = new Random(556)
    val ids = HouseFixture.all.map(_.entityId)
    val start = HouseFixture.all.map(e => e.entityId -> e.toEntityState).toMap
    val (_, flipped) = (1 to 300).foldLeft((start, Set.empty[NodeId])) {
      case ((states, seen), frame) =>
        val moved = rnd.shuffle(ids).take(1 + rnd.nextInt(3))
        val now = moved.foldLeft(states) { (acc, id) =>
          acc.updated(id, acc(id).copy(state = alphabet(rnd.nextInt(4))))
        }
        val changes = moved.map(id => StateChange(id, states.get(id), now(id)))
        val found = List(Set.empty[String], userSurfaces).flatMap { visible =>
          val flips = g.affectedStateGroups(changes, states, now, visible)
          assertEquals(
            flips,
            reference(g, states, now, visible),
            clue = s"$name, frame $frame, moved $moved, visible $visible"
          )
          flips
        }
        (now, seen ++ found)
    }
    // Not vacuous: every group flipped in some frame.
    assertEquals(flipped, g.stateBakeOwnerIds, clue = name)
  }

  test("the flip index agrees with walking every group: shipped fixture") {
    agree("fixture-surfaces", fixture("fixture-surfaces"))
  }

  test("the flip index agrees with walking every group: Ifs in clauses") {
    agree("in-clauses", inClauses)
  }
}
