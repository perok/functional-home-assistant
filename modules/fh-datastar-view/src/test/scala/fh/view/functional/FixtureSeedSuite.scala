package fh.view.functional

import cats.effect.{IO, Resource}
import fh.view.runtime.HaFeed
import fh.view.testkit.{FakeHomeAssistant, HouseFixture}

import scala.concurrent.duration.*

/** The round trip every functional suite trusts: [[HouseFixture]] through the
  * fake's opening frame into the real [[StateStore]], via the real [[HaFeed]].
  * A feed exists only once that frame is applied, so its snapshot is read
  * directly.
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
      // Timestamps come from the feed, so compare what a dashboard renders.
      .map(_.view.mapValues(s => (s.state, s.attributes)).toMap)
      .assertEquals(
        HouseFixture.all.map(e => e.entityId -> (e.state, e.attributes)).toMap
      )
  }
}
