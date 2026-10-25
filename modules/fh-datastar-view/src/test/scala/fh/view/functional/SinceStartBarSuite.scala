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

  private def entry(card: String) =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |local left = dump.entities.${left.dumpKey}
       |card = (c.column) { children { $card } }
       |""".stripMargin

  /** Idle at 0, then a wash that started at 101 minutes. */
  private def served[A](
      card: String = "c.bar.remainingOfPeak(left)",
      now: String = left.state
  )(f: (TestServer, IO[Int]) => IO[A]): IO[A] =
    Ref[IO].of(0).flatMap { asked =>
      TestServer
        .fromWorkspace(
          "since-start",
          entry(card),
          List(left.copy(state = now)),
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
    served() { (ts, _) =>
      ts.page().map(html => assert(html.contains("101"), clue = html))
    }
  }

  test(
    "a tick moves the reading by a signal; the whole is read again, unmoved"
  ) {
    served() { (ts, asked) =>
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

  test("idle at 0, no history is read; a start reads it and fills the bar") {
    served(
      card = "((c.progress(left)) { sinceStart = true }).label(\"Vask\")",
      now = "0"
    ) { (ts, asked) =>
      for {
        idle <- ts.page()
        idleAsked <- asked
        client <- ts.connect()
        _ <- client.drain
        _ <- ts.change(left.entityId, "46")
        sent <- client.drain
        runningAsked <- asked
      } yield {
        assert(idle.contains("Vask"), clue = idle)
        // The bar's cell is gone from the first paint, margins and all.
        assert(
          """<div class="[^"]*fh-cell[^"]*fh-bar-idle""".r
            .findFirstIn(idle)
            .isDefined,
          clue = idle
        )
        assertEquals(idleAsked, 0)
        val data = sent.flatMap(_.data).mkString
        assert(data.contains("101"), clue = data)
        assert(runningAsked > 0)
      }
    }
  }
}
