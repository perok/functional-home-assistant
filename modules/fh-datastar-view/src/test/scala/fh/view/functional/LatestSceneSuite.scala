package fh.view.functional

import cats.effect.IO
import fh.view.runtime.TestServer
import fh.view.testkit.FixtureEntity

import scala.concurrent.duration.*

/** A fixed row of scene pills lighting the one activated last, from a real Pkl
  * entry: `first()` over a live ordering, read as each pill's `active`.
  */
class LatestSceneSuite extends munit.CatsEffectSuite {

  private val kveld = FixtureEntity("scene.kveld", "2026-10-12T16:03:11+00:00")
  private val morgen =
    FixtureEntity("scene.morgen", "2026-10-12T05:30:00+00:00")
  private val film = FixtureEntity("scene.film", "unknown")
  private val scenes = List(film, kveld, morgen)

  private val entry =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/query.pkl" as q
      |import "@fh-home/dump.pkl" as dump
      |
      |local latest = q.from(dump.scenes.all).orderBy(q.desc(q.stateProp)).first()
      |
      |card = (c.row) {
      |  children {
      |    for (s in dump.scenes.all) {
      |      c.pill(c.tap.activate(s)).label(s.entity_id).active(latest.eq(s))
      |    }
      |  }
      |}
      |""".stripMargin

  private def withServer[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("latest-scene", entry, scenes)
      .use(f)
      .timeout(60.seconds)

  /** Each pill's `fh-active` signal, in entity-id order (the dump's). */
  private def signals(html: String): List[String] =
    """data-class:fh-active="\$([^"]+)"""".r
      .findAllMatchIn(html)
      .map(_.group(1).split('.').last)
      .toList

  /** The pill whose cell is lit in the document. */
  private def lit(html: String): List[String] =
    """class="fh-cell[^"]*fh-active"[^>]*>(?:(?!</button>).)*?>(scene\.[a-z]+)<""".r
      .findAllMatchIn(html)
      .map(_.group(1))
      .toList

  test("the scene activated last is lit, and one never activated is not") {
    withServer { ts =>
      ts.page().map { html =>
        assertEquals(signals(html).length, 3, clue = html)
        assertEquals(lit(html), List("scene.kveld"), clue = html)
      }
    }
  }

  test("activating another scene moves the light by a frame") {
    withServer { ts =>
      for {
        html <- ts.page()
        sigs = signals(html)
        sent <- ts.sentAfter(
          ts.change(morgen.entityId, "2026-10-12T19:00:00+00:00")
        )
      } yield {
        // Entity-id order: film, kveld, morgen.
        assert(sent.contains(s"\"${sigs(1)}\":false"), clue = sent)
        assert(sent.contains(s"\"${sigs(2)}\":true"), clue = sent)
        assert(!sent.contains("fh-cell"), clue = sent)
      }
    }
  }

  test("a scene becoming unavailable gives up the lead") {
    withServer { ts =>
      for {
        html <- ts.page()
        sigs = signals(html)
        sent <- ts.sentAfter(ts.change(kveld.entityId, "unavailable"))
      } yield {
        assert(sent.contains(s"\"${sigs(1)}\":false"), clue = sent)
        assert(sent.contains(s"\"${sigs(2)}\":true"), clue = sent)
      }
    }
  }
}
