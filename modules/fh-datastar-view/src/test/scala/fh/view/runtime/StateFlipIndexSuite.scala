package fh.view.runtime

import fh.view.model.{Dashboard, Ix, NodeId, Ready}
import fh.view.testkit.{FixtureEntity, HouseFixture, PklFixture}
import io.circe.parser.parse

import scala.collection.immutable.BitSet
import scala.util.Random

/** [[PickGraph.affectedStateGroups]] finds flips from the changed entities. The
  * reference here walks every group down from the page instead, through
  * branches that are shown and did not move and into the visible cases they
  * hold: for random frames the two must name the same groups.
  */
class StateFlipIndexSuite extends munit.FunSuite {

  private def fixture(name: String): Dashboard = {
    val wire = os.read(
      os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "resources" /
        "snapshots" / s"$name.json"
    )
    parse(wire)
      .fold(e => fail(s"$name: $e"), identity)
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
      ready: Ready.Dashboard,
      g: PickGraph,
      before: Shared,
      now: Shared,
      visible: BitSet
  ): List[NodeId] = {
    val index = ready.index
    def containerOf(gid: NodeId) = index.container(ready.picks(gid).node.ix)
    def at(root: Ix) =
      g.statePicks.toList.filter(containerOf(_) == root)
    // A popup opens on the page; a case is open where its pick is shown.
    def openAt(root: Ix) = visible.toList.map(Ix(_)).filter { c =>
      if (index.parent(c).isEmpty) root == g.pageRoot
      else
        g.pickShowing(index.id(c), now)
          .exists((gid, _) => containerOf(gid) == root)
    }
    def walk(root: Ix): List[NodeId] = at(root).flatMap { gid =>
      val sel = g.decideByState(gid, now)
      if (!g.groupShown(gid, now)) Nil
      else if (g.groupShown(gid, before) && g.decideByState(gid, before) != sel)
        List(gid)
      else sel.toList.flatMap(i => walk(ready.picks(gid).choice.cases(i).ix))
    } ++ openAt(root).flatMap(walk)
    walk(g.pageRoot).distinct.sorted
  }

  private val alphabet = List("on", "off", "locked", "unlocked")

  private def agree(name: String, d: Dashboard): Unit = {
    val validated = d.validated().fold(e => fail(e.mkString("; ")), identity)
    val g = Renderer(validated).picks
    assert(g.statePicks.nonEmpty, s"$name has no state group to flip")
    val userCases =
      g.openOf(g.varPicks.flatMap(g.cases(_)).toSet)
    val rnd = new Random(556)
    val ids = HouseFixture.all.map(_.entityId)
    val start = HouseFixture.all.map(e => e.entityId -> e.toEntityState).toMap
    val (_, flipped) = (1 to 300).foldLeft((start, Set.empty[NodeId])) {
      case ((states, seen), frame) =>
        val moved = rnd.shuffle(ids).take(1 + rnd.nextInt(3))
        val now = moved.foldLeft(states) { (acc, id) =>
          acc.updated(id, acc(id).copy(state = alphabet(rnd.nextInt(4))))
        }
        val changes =
          moved.map(id => StateChange.Moved(id, states.get(id), now(id)))
        val found = List(BitSet.empty, userCases).flatMap { visible =>
          val flips = g.affectedStateGroups(
            changes,
            Shared.of(states),
            Shared.of(now),
            visible
          )
          assertEquals(
            flips,
            reference(
              validated.ready,
              g,
              Shared.of(states),
              Shared.of(now),
              visible
            ),
            clue = s"$name, frame $frame, moved $moved, visible $visible"
          )
          flips
        }
        (now, seen ++ found)
    }
    // Not vacuous: every group flipped in some frame.
    assertEquals(flipped, g.statePicks, clue = name)
  }

  test(
    "the flip index agrees with walking every group: the branches dashboard"
  ) {
    agree("branches", fixture("branches"))
  }

  test("the flip index agrees with walking every group: Ifs in clauses") {
    agree("in-clauses", inClauses)
  }
}
