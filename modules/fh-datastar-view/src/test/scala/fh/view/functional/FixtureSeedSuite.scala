package fh.view.functional

import cats.effect.{IO, Resource}
import fh.view.runtime.{HaFeed, StateStore}
import fh.view.testkit.{FakeHomeAssistant, HouseFixture}

import scala.concurrent.duration.*

/** The fixture's builders are the foundation the whole functional suite trusts,
  * so pin the round-trip: [[HouseFixture]] -> `FakeHomeAssistant`'s
  * `subscribe_entities` opening frame -> the real [[StateStore]] reproduces
  * each entity's state and attributes exactly. If this drifts, every downstream
  * behaviour test is suspect.
  *
  * Driven through the real [[HaFeed]] (over a never-closing connection), since
  * the store is a passive sink whose sole production driver is that feed — and
  * a HaFeed exists only once the opening full-set frame has been applied, so
  * the snapshot can be read straight off it.
  */
class FixtureSeedSuite extends munit.CatsEffectSuite {

  test(
    "StateStore filled from the fake's feed reproduces every fixture entity"
  ) {
    FakeHomeAssistant
      .create(HouseFixture.all)
      .flatMap { fake =>
        val connect: HaFeed.Connect = Resource.pure((fake, IO.never))
        HaFeed
          .resource(connect)
          .use(_.store.snapshot)
      }
      .timeout(30.seconds)
      // Timestamps come from the feed, not the fixture, so compare the values a
      // dashboard actually renders.
      .map(_.view.mapValues(s => (s.state, s.attributes)).toMap)
      .assertEquals(
        HouseFixture.all.map(e => e.entityId -> (e.state, e.attributes)).toMap
      )
  }
}
