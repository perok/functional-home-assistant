package fh.view.functional

import api.homeassistant.HomeAssistantApi
import fh.view.runtime.StateStore
import fh.view.testkit.{FakeHomeAssistant, HouseFixture}

import scala.concurrent.duration.*

/** The fixture's builders are the foundation the whole functional suite trusts,
  * so pin the round-trip: [[HouseFixture]] -> `FakeHomeAssistant` `get_states`
  * -> the real [[StateStore]] seed ([[StateStore.reseed]]) reproduces each
  * entity's state and attributes exactly. If this drifts, every downstream
  * behaviour test is suspect.
  *
  * The store is a passive sink (it never subscribes for itself — [[HaFeed]] is
  * its sole driver in production), so the seed is exercised the same way the
  * feed does it: an empty store re-seeded from the API's snapshot.
  */
class FixtureSeedSuite extends munit.CatsEffectSuite {

  test("StateStore seeded from the fake reproduces every fixture entity") {
    FakeHomeAssistant
      .create(HouseFixture.all)
      .flatMap { fake =>
        val api = HomeAssistantApi.fromWs(fake)
        StateStore.empty.flatMap(store => store.reseed(api) *> store.snapshot)
      }
      .timeout(30.seconds)
      .assertEquals(
        HouseFixture.all.map(e => e.entityId -> e.toEntityState).toMap
      )
  }
}
