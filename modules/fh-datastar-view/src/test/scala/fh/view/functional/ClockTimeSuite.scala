package fh.view.functional

import cats.effect.IO
import fh.view.runtime.TestServer
import fh.view.testkit.FixtureEntity

import java.time.{OffsetDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*

/** A scene's or a button's state is when it last fired, and its entity card
  * reads that out as a clock time in the server's zone, from a real Pkl entry.
  */
class ClockTimeSuite extends munit.CatsEffectSuite {

  private val activated = "2026-10-12T16:03:11.552123+00:00"
  private val pressed = "2026-01-12T07:05:00+00:00"

  private val kveld = FixtureEntity("scene.kveld", activated)
  private val never = FixtureEntity("scene.never", "unknown")
  private val restart = FixtureEntity("button.restart", pressed)
  private val alarm = FixtureEntity("datetime.alarm", activated)

  private def clock(iso: String): String =
    OffsetDateTime
      .parse(iso)
      .atZoneSameInstant(ZoneId.systemDefault)
      .format(DateTimeFormatter.ofPattern("HH:mm"))

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children {
       |    c.entityCard(dump.entities.${kveld.dumpKey})
       |    c.entityCard(dump.entities.${never.dumpKey})
       |    c.entityCard(dump.entities.${restart.dumpKey})
       |    c.entityCard(dump.entities.${alarm.dumpKey})
       |  }
       |}
       |""".stripMargin

  private def withServer[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("clock-time", entry, List(kveld, never, restart, alarm))
      .use(f)
      .timeout(60.seconds)

  test("a scene and a button read out the clock time they last fired") {
    withServer { ts =>
      ts.page().map { html =>
        assert(html.contains(s">${clock(activated)}<"), clue = html)
        assert(html.contains(s">${clock(pressed)}<"), clue = html)
        // A scene never activated says so rather than a time.
        assert(html.contains(">unknown<"), clue = html)
      }
    }
  }

  test("a datetime keeps its whole instant: its moment is chosen, not past") {
    withServer { ts =>
      ts.page().map(html => assert(html.contains(activated), clue = html))
    }
  }

  test("activating the scene again moves the time by a frame") {
    val again = "2026-10-12T19:45:00+00:00"
    withServer { ts =>
      ts.sentAfter(ts.change(kveld.entityId, again))
        .map(sent => assert(sent.contains(clock(again)), clue = sent))
    }
  }
}
