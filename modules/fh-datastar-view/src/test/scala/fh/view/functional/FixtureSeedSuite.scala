package fh.view.functional

import cats.effect.unsafe.implicits.global
import fh.view.runtime.StateStore
import fh.view.testkit.{FakeHomeAssistant, HouseFixture}

import scala.concurrent.duration.*

/** The fixture's builders are the foundation the whole functional suite trusts,
  * so pin the round-trip: [[HouseFixture]] -> `FakeHomeAssistant.getStates` ->
  * the real [[StateStore]] seed reproduces each entity's state and attributes
  * exactly. If this drifts, every downstream behaviour test is suspect.
  */
class FixtureSeedSuite extends munit.FunSuite {

  test("StateStore seeded from the fake reproduces every fixture entity") {
    val snapshot = FakeHomeAssistant
      .create(HouseFixture.all)
      .flatMap(fake => StateStore.create(fake).use(_.snapshot))
      .timeout(30.seconds)
      .unsafeRunSync()

    val expected = HouseFixture.all.map(e => e.entityId -> e.toEntityState).toMap
    assertEquals(snapshot, expected)
  }
}
