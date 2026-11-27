package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.testkit.{FakePage, FixtureEntries, FixtureEntity, PklFixture}

/** What a tab switch costs: the stream drops and the page reconnects with its
  * cursor, within the linger. Pins today's behaviour, including the two cases
  * `docs/plan-after-regions-perf.md` ("The reconnect") sets out to change: a
  * frame lost in flight repaints the body, and a lost choice leaves the page
  * wrong.
  */
class ReconnectSuite extends munit.CatsEffectSuite {

  private val js = ResourceSuiteLocalFixture("js", FakePage.runtime)

  override def munitFixtures = List(js)

  private def served(e: FixtureEntries.Entry) =
    TestServer
      .resource(
        PklFixture.buildDashboard(e.slug, e.source, e.dump),
        e.entities
      )
      .flatMap(ts => FakeBrowser.open(ts, js()).map(ts -> _))

  private def repainted(a: FakeBrowser): Boolean =
    a.lastOpening.exists(_.data.exists(_.startsWith("selector #root")))

  private def elements(a: FakeBrowser): Int =
    a.lastOpening.count(_.eventType.contains("datastar-patch-elements"))

  /** The server pulled and wrote to a stream the page no longer reads. */
  private def lostInFlight(ts: TestServer, a: FakeBrowser): IO[Unit] =
    ts.sessions
      .get(a.conn)
      .flatMap(_.traverse_ { s =>
        ts.server
          .liveSlug(ts.slug)
          .flatMap(live =>
            live.doorbell.get.flatMap(ts.server.pull(live, s, _))
          ) *> s.takeBacklog.void
      })

  test("nothing moved while down: the opening is its signals alone") {
    served(FixtureEntries.sets).use { (ts, a) =>
      for {
        _ <- ts.record(FixtureEntity("light.kitchen", "off"))
        _ <- a.deliver
        _ <- a.drop
        _ <- a.connect
        r <- a.check
      } yield {
        assertEquals(elements(a), 0, a.lastOpening)
        assert(r == hedgehog.Result.Success, r)
      }
    }
  }

  test("a tick while down resumes: only what it moved") {
    served(FixtureEntries.sets).use { (ts, a) =>
      for {
        _ <- ts.record(FixtureEntity("light.kitchen", "off"))
        _ <- a.deliver
        _ <- a.drop
        _ <- ts.record(FixtureEntity("light.kitchen", "on"))
        _ <- a.connect
        r <- a.check
      } yield {
        assert(!repainted(a), a.lastOpening)
        assert(r == hedgehog.Result.Success, r)
      }
    }
  }

  // Correct, and the cost the plan's B removes: `holds` is what was sent.
  test("a tick lost in flight repaints the whole body") {
    served(FixtureEntries.sets).use { (ts, a) =>
      for {
        _ <- ts.record(FixtureEntity("light.kitchen", "off"))
        _ <- a.deliver
        _ <- ts.record(FixtureEntity("light.kitchen", "on"))
        _ <- lostInFlight(ts, a)
        _ <- a.drop
        _ <- a.connect
        r <- a.check
      } yield {
        assert(repainted(a), a.lastOpening)
        assert(r == hedgehog.Result.Success, r)
      }
    }
  }

  // The plan's A: a choice's batch carries no cursor, so `told` does not move,
  // the reconnect resumes over a DOM that never got the panel, and nothing
  // later corrects it.
  test("a choice lost in flight is resent".fail) {
    served(FixtureEntries.branches).use { (ts, a) =>
      for {
        live <- ts.server.liveSlug(ts.slug)
        tab <- live.renderer.get.map(
          _.rendererOf.get.vars.defaults.keys.find(_.name == "tab").get
        )
        _ <- a.choose(tab, Some("1"))
        _ <- ts.sessions.get(a.conn).flatMap(_.traverse_(_.takeBacklog.void))
        _ <- a.drop
        _ <- a.connect *> a.deliver
        r <- a.check
      } yield assert(r == hedgehog.Result.Success, r)
    }
  }
}
