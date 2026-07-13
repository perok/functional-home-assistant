package fh.view.functional

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.runtime.TestServer
import fh.view.testkit.{HouseFixture, PklFixture}

import scala.concurrent.duration.*

/** The Tier-A capstone of `plan-functional-e2e-tests.md`: the SAME end-to-end
  * behaviour as [[DashboardBehaviourSuite]], but the dashboard is a real Pkl
  * entry evaluated through the genuine authoring pipeline (Pkl -> model ->
  * renderer -> SSE) rather than a hand-built [[fh.view.model.Dashboard]]. It
  * pins that the Pkl track and the runtime track meet: an entry authored against
  * a [[HouseFixture]]-derived `lib/dump.pkl` renders and streams the live state
  * the fake serves — with no live HA.
  *
  * The entry is authored against `dump.entities.<key>` for the fixture entities;
  * because the dump and the seeded state both derive from [[HouseFixture]], the
  * two cannot drift.
  */
class PklDashboardBehaviourSuite extends munit.FunSuite {

  /** A minimal real entry over two fixture entities: a numeric sensor (whose
    * `entityCard` value auto-appends the unit) and the kitchen light. Authored
    * exactly as a hand-written dashboard would be — `amends "lib/entry.pkl"`,
    * referencing entities by their generated dump keys.
    */
  private val entrySource =
    s"""amends "lib/entry.pkl"
       |
       |import "lib/components.pkl" as c
       |import "lib/dump.pkl" as dump
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

  private val dashboard =
    PklFixture.buildDashboard("fixture-home", entrySource)
  private val house = HouseFixture.all

  private def withServer[A](f: TestServer => IO[A]): A =
    TestServer
      .resource(dashboard, house)
      .use(f)
      .timeout(45.seconds)
      .unsafeRunSync()

  test("a Pkl-built dashboard renders the seeded live state") {
    val html = withServer(_.page)
    // entityCard label = the live friendly_name; value = $state + unit.
    assert(html.contains("Outside Temperature"), clue = html)
    assert(html.contains("12.4"), clue = html)
    assert(html.contains("°C"), clue = html)
    // The kitchen light card: its friendly_name label and its "on" state.
    assert(html.contains("Kitchen"), clue = html)
    assert(html.contains(">on<"), clue = html)
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
