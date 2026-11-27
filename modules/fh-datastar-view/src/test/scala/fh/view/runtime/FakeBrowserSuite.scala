package fh.view.runtime

import cats.effect.IO
import fh.view.testkit.{FakePage, FixtureEntries, FixtureEntity, PklFixture}

/** The oracle's own controls: a page that was sent everything matches a fresh
  * one, and one that missed a frame does not, so a green oracle run means
  * something.
  */
class FakeBrowserSuite extends munit.CatsEffectSuite {

  private val js = ResourceSuiteLocalFixture("js", FakePage.runtime)

  override def munitFixtures = List(js)

  private lazy val sets = {
    val e = FixtureEntries.sets
    PklFixture.buildDashboard(e.slug, e.source, e.dump)
  }

  private def browser =
    TestServer
      .resource(sets, FixtureEntries.sets.entities)
      .flatMap(ts => FakeBrowser.open(ts, js()).map(ts -> _))

  private def failed(r: hedgehog.Result): Boolean = r match
    case hedgehog.Result.Failure(_) => true
    case hedgehog.Result.Success    => false

  test("a page sent every frame, a reconnect included, matches a fresh one") {
    browser.use { (ts, a) =>
      for {
        _ <- ts.record(FixtureEntity("light.kitchen", "off"))
        _ <- a.deliver
        _ <- a.drop
        _ <- ts.record(FixtureEntity("light.kitchen", "on"))
        _ <- a.connect *> a.deliver
        r <- a.check
      } yield assert(!failed(r), r)
    }
  }

  test("an entity leaving HA reaches the page") {
    browser.use { (ts, a) =>
      for {
        before <- IO(a.page.view)
        _ <- ts.recordRemoval("light.kitchen")
        _ <- a.deliver
        r <- a.check
      } yield {
        assert(!failed(r), r)
        assertNotEquals(a.page.view, before)
      }
    }
  }

  test("a tap that cannot reach the server fails at once, and says so") {
    browser.use { (_, a) =>
      IO {
        val path = a.page.presses.find(_.contains("sse/call/")).get
        val node = path.split("node=")(1)
        a.page.press(path)
        val List(r) = a.page.requests: @unchecked
        a.page.unreachable(r.id)
        assertEquals(a.page.requests, Nil, "retried")
        assertEquals(
          a.page.signal(s"_${node}__error").flatMap(_.asString),
          Some("Could not be sent")
        )
        assertEquals(
          a.page.signal(s"_${node}__busy").flatMap(_.asBoolean),
          Some(false)
        )
        assertEquals(a.page.misses, Nil)
      }
    }
  }

  test("a page that missed a frame does not") {
    browser.use { (ts, a) =>
      for {
        _ <- ts.record(FixtureEntity("light.kitchen", "off"))
        r <- a.check
      } yield assert(failed(r), r)
    }
  }
}
