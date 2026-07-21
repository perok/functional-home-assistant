package fh.view.functional

import cats.effect.IO
import fh.view.runtime.TestServer
import fh.view.testkit.{FixtureEntity, HouseFixture}

import scala.concurrent.duration.*

/** The Tier-A capstone (ADR 0009): the SAME end-to-end behaviour as
  * [[DashboardBehaviourSuite]], but the dashboard is a real Pkl entry evaluated
  * through the GENUINE server build path — `TestServer.fromWorkspace` runs
  * `ServerApp.prepareRenderers` (discover -> `prepareDumps` -> `buildEntry`)
  * and `liveServer`, the exact sequence production's `run` uses. Nothing is
  * stubbed but the HA socket: the dump is FETCHED from the fake's
  * `render_template` (same fixtures `get_states` serves), so the Pkl track and
  * the runtime track meet with no shortcut through a pre-built `Dashboard`.
  *
  * The entry is authored against `dump.entities.<key>` for the fixture
  * entities; because the served dump and the seeded state both derive from the
  * SAME [[FixtureEntity]] set, the two cannot drift.
  */
class PklDashboardBehaviourSuite extends munit.CatsEffectSuite {

  /** Every entity the entry references — also the fake's seed and the source of
    * the dump it serves. One declaration feeds all three.
    */
  private val entities: List[FixtureEntity] =
    List(HouseFixture.outsideTemp, HouseFixture.kitchenLight)

  /** A minimal real entry over two fixture entities: a numeric sensor (whose
    * `entityCard` value auto-appends the unit) and the kitchen light. Authored
    * exactly as a hand-written dashboard would be — `amends
    * "@fh-dashboard/entry.pkl"`, referencing entities by their generated dump
    * keys.
    */
  private val entrySource =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |title = "Fixture Home"
       |
       |card = (c.column) {
       |  children {
       |    c.title("Fixture Home")
       |    c.entityCard(dump.entities.${HouseFixture.outsideTemp.dumpKey})
       |    c.entityCard(dump.entities.${HouseFixture.kitchenLight.dumpKey})
       |  }
       |}
       |""".stripMargin

  private def withServer[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("fixture-home", entrySource, entities)
      .use(f)
      .timeout(60.seconds)

  test("a Pkl-built dashboard renders the seeded live state") {
    withServer(_.page).map { html =>
      // entityCard label = the live friendly_name; value = $state + unit.
      assert(html.contains("Outside Temperature"), clue = html)
      assert(html.contains("12.4"), clue = html)
      assert(html.contains("°C"), clue = html)
      // The kitchen light card: its friendly_name label and its "on" state.
      assert(html.contains("Kitchen"), clue = html)
      assert(html.contains(">on<"), clue = html)
    }
  }

  test("a state change streams a fragment through the Pkl-built dashboard") {
    withServer { ts =>
      ts.observePatch(
        marker = "13.1",
        trigger = ts.fake.emit(
          HouseFixture.outsideTemp.entityId,
          "13.1",
          HouseFixture.outsideTemp.attributes
        )
      )
    }
  }
}
