package fh.view.functional

import api.homeassistant.ws.domain.HistoryPoint
import cats.effect.{IO, Ref}
import fh.view.runtime.TestServer
import fh.view.testkit.{FakeConfig, HouseFixture}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** A countdown with nothing to divide it by gets a bar out of where it started:
  * its highest reading since it last read 0, from the recorder, read in the
  * bar's CEL as a fetch and sent as a signal (#541 E3b).
  */
class SinceStartBarSuite extends CatsEffectSuite {

  private val left = HouseFixture.washerRemaining

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) { children { c.bar.remainingOfPeak(dump.entities.${left.dumpKey}) } }
       |""".stripMargin

  /** Idle at 0, then a wash that started at 101 minutes. */
  private def served[A](f: (TestServer, IO[Int]) => IO[A]): IO[A] =
    Ref[IO].of(0).flatMap { asked =>
      TestServer
        .fromWorkspace(
          "since-start",
          entry,
          List(left),
          FakeConfig(recorder =
            Some((from, _, _) =>
              asked
                .update(_ + 1)
                .as(
                  List(
                    HistoryPoint("0", from),
                    HistoryPoint("101", from.plusSeconds(60)),
                    HistoryPoint("90", from.plusSeconds(720))
                  )
                )
            )
          )
        )
        .use(ts => f(ts, asked.get))
        .timeout(60.seconds)
    }

  test("the bar's whole is where the countdown started, from the recorder") {
    served { (ts, _) =>
      ts.page().map(html => assert(html.contains("101"), clue = html))
    }
  }

  test("a tick moves the reading by a signal; the whole is read again, unmoved") {
    served { (ts, asked) =>
      for {
        client <- ts.connect()
        _ <- client.drain
        before <- asked
        _ <- ts.change(left.entityId, "46")
        sent <- client.drain
        after <- asked
      } yield {
        assert(
          !sent.exists(_.eventType.contains("datastar-patch-elements")),
          clue = sent
        )
        val data = sent.flatMap(_.data).mkString
        assert(data.contains("\"46\""), clue = data)
        // Re-read for the frame (a read missing from it raises), from the
        // cache, and unmoved, so not re-sent.
        assert(!data.contains("101"), clue = data)
        assertEquals(after, before)
      }
    }
  }
}
