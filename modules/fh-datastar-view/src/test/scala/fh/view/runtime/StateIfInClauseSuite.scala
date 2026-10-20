package fh.view.runtime

import fh.view.testkit.FixtureEntity
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** An `If` on live state inside a candidate set's clause, reading an entity
  * that is not the member.
  */
class StateIfInClauseSuite extends CatsEffectSuite {

  private def light(id: String, state: String) = FixtureEntity(id, state)

  // `light.c` stands in for a window; `light.d` is read only inside the
  // `then` branch, so a tick on it is silent while the branch is hidden.
  private val entry =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/query.pkl" as q
      |import "@fh-home/dump.pkl" as dump
      |
      |card = (c.column) {
      |  children {
      |    q.from(List(dump.lights.a, dump.lights.b))
      |      .where(q.eq(q.stateProp, "on"))
      |      .render((l) -> (c.column) {
      |        children {
      |          c.entityCard(l)
      |          c.iff(q.entity(dump.lights.c).stateIs("on"))
      |            .then((c.column) { children { c.title("Open"); c.entityCard(dump.lights.d) } })
      |            .`else`(c.title("Shut"))
      |        }
      |      })
      |      .build()
      |  }
      |}
      |""".stripMargin

  private val start =
    List(
      light("light.a", "on"),
      light("light.b", "on"),
      light("light.c", "off"),
      light("light.d", "off")
    )

  private def text(frames: List[SseFrame]) =
    frames.flatMap(_.data).mkString("\n")
  private def count(s: String, in: String) = s.r.findAllMatchIn(in).size

  test(
    "a flip sends each present member's branch, and nothing for an absent one"
  ) {
    TestServer
      .fromWorkspace("state-if-in-clause", entry, start)
      .use { ts =>
        for {
          viewer <- ts.viewer()
          opened <- viewer.step(light("light.c", "on"))
          _ <- viewer.step(light("light.b", "off"))
          shut <- viewer.step(light("light.c", "off"))
        } yield {
          val page = viewer.document.html
          assertEquals(count("Shut", page), 2, clue = page)
          assertEquals(count("Open", text(opened)), 2, clue = text(opened))
          // light.b has left the set: only light.a's branch moves.
          assertEquals(count("Shut", text(shut)), 1, clue = text(shut))
          assert(text(shut).contains("c_0_light_a"), text(shut))
          assert(!text(shut).contains("c_0_light_b"), text(shut))
        }
      }
      .timeout(60.seconds)
  }

  test("an arriving member shows the branch the state picks") {
    TestServer
      .fromWorkspace("state-if-in-clause", entry, start)
      .use { ts =>
        for {
          viewer <- ts.viewer()
          _ <- viewer.step(light("light.b", "off"))
          _ <- viewer.step(light("light.c", "on"))
          back <- viewer.step(light("light.b", "on"))
        } yield {
          val arrived = text(back)
          assert(arrived.contains("c_0_light_b"), arrived)
          assert(arrived.contains("Open") && !arrived.contains("Shut"), arrived)
        }
      }
      .timeout(60.seconds)
  }

  // The If is in one clause only: switching into it shows its branch.
  private val byClause =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/query.pkl" as q
      |import "@fh-home/dump.pkl" as dump
      |
      |card = (c.column) {
      |  children {
      |    q.from(List(dump.lights.a))
      |      .caseOf(q.eq(q.stateProp, "on"), (l) -> (c.column) {
      |        children {
      |          c.entityCard(l)
      |          c.iff(q.entity(dump.lights.c).stateIs("on")).then(c.title("Open")).`else`(c.title("Shut"))
      |        }
      |      })
      |      .`else`(c.entityCard)
      |      .build()
      |  }
      |}
      |""".stripMargin

  test("a member switching clause shows the branch the state picks") {
    TestServer
      .fromWorkspace(
        "state-if-by-clause",
        byClause,
        List(light("light.a", "off"), light("light.c", "on"))
      )
      .use { ts =>
        for {
          viewer <- ts.viewer()
          switched <- viewer.step(light("light.a", "on"))
          shut <- viewer.step(light("light.c", "off"))
        } yield {
          assert(text(switched).contains("Open"), text(switched))
          assert(!text(switched).contains("Shut"), text(switched))
          assert(text(shut).contains("Shut"), text(shut))
        }
      }
      .timeout(60.seconds)
  }

  test("an entity read only in a hidden branch is silent until it shows") {
    TestServer
      .fromWorkspace("state-if-in-clause", entry, start)
      .use { ts =>
        for {
          viewer <- ts.viewer()
          hidden <- viewer.step(light("light.d", "on"))
          _ <- viewer.step(light("light.c", "on"))
          shown <- viewer.step(light("light.d", "off"))
        } yield {
          // `light.d`'s card shows its state through a signal.
          val dSignal = "\"d\":{"
          assert(!text(hidden).contains(dSignal), text(hidden))
          assert(!text(hidden).contains("elements"), text(hidden))
          assert(text(shown).contains(dSignal), text(shown))
        }
      }
      .timeout(60.seconds)
  }
}
