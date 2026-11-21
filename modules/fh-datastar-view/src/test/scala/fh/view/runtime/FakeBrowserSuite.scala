package fh.view.runtime

import cats.effect.IO
import fh.view.testkit.{FixtureEntries, FixtureEntity, PklFixture}

/** The oracle's own controls: a page that was sent everything matches a fresh
  * one, and one that missed a frame does not, so a green oracle run means
  * something.
  */
class FakeBrowserSuite extends munit.CatsEffectSuite {

  private lazy val sets = {
    val e = FixtureEntries.sets
    PklFixture.buildDashboard(e.slug, e.source, e.dump)
  }

  private def server = TestServer.resource(sets, FixtureEntries.sets.entities)

  private def failed(r: hedgehog.Result): Boolean = r match
    case hedgehog.Result.Failure(_) => true
    case hedgehog.Result.Success    => false

  test("a page sent every frame, a reconnect included, matches a fresh one") {
    server.use { ts =>
      for {
        a <- FakeBrowser.open(ts)
        _ <- ts.record(FixtureEntity("light.kitchen", "off"))
        _ <- a.deliver
        _ <- a.drop
        _ <- ts.record(FixtureEntity("light.kitchen", "on"))
        _ <- a.connect *> a.deliver
        r <- a.check
      } yield assert(!failed(r), r)
    }
  }

  test("a page that missed a frame does not") {
    server.use { ts =>
      for {
        a <- FakeBrowser.open(ts)
        _ <- ts.record(FixtureEntity("light.kitchen", "off"))
        r <- a.check
      } yield assert(failed(r), r)
    }
  }
}
