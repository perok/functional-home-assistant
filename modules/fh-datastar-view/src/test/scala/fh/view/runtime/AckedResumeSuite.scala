package fh.view.runtime

import cats.effect.IO
import fh.view.testkit.FixtureEntity
import io.circe.Json
import org.http4s.*

import scala.concurrent.duration.*

/** '''The client's cursor is the only proof bytes were applied.''' `holds`
  * records what was sent: a stream broken mid-batch, or a frozen tab whose
  * socket kept filling, leaves a session claiming digests the DOM never got,
  * and every resume computes "nothing owed". That shipped: a backgrounded tab
  * never caught up.
  *
  * The cursor rides last in its batch ([[Server.pull]]), so an echo of V means
  * everything before it applied. It is measured against `Session.told`, the
  * newest version announced, not `position`: a pull owing nothing advances the
  * position silently, so gating on it would repaint nearly every tab switch
  * (the third test).
  */
class AckedResumeSuite extends ServerHarness {

  private class Tab(ts: TestServer, val document: TestServer.Document) {

    /** The version the page rendered into itself. */
    val documentCursor: Long =
      Server
        .cursorOf(Request[IO](Method.GET, document.stream))
        .getOrElse(fail(s"no cursor on ${document.stream}"))
        .version

    /** Optionally quoting a different cursor than the client holds, the subject
      * here. Reading the opening block closes the stream: the tab has gone away
      * again.
      */
    def connect(cursor: Long): IO[List[ServerSentEvent]] =
      ts.get(withCursor(cursor))
        .flatMap(sseFrom(_)(isCursor))
        .timeout(30.seconds)

    /** Returns once the opening block has landed, so what the fiber collects
      * after is live traffic.
      */
    def held(cursor: Long): IO[IO[Unit]] =
      for {
        seen <- IO.ref(Vector.empty[ServerSentEvent])
        resp <- ts.get(withCursor(cursor))
        fiber <- resp.body
          .through(ServerSentEvent.decoder[IO])
          .evalMap(e => seen.update(_ :+ e))
          .compile
          .drain
          .start
        _ <- fs2.Stream
          .repeatEval(seen.get <* IO.sleep(10.millis))
          .find(_.exists(isCursor))
          .compile
          .drain
          .timeout(30.seconds)
      } yield fiber.cancel

    private def withCursor(version: Long): Uri =
      document.stream.withQueryParam(
        Server.cursorParam(Server.StoreVersionSignal),
        version
      )
  }

  /** The document establishes the session, including its `told`: the page
    * renders a cursor into itself.
    */
  private def withTab(f: (Tab, TestServer) => IO[Unit]): IO[Unit] =
    live(liveLeafDash, Map("sensor.a" -> es("sensor.a", "cold"))) { ts =>
      ts.load().flatMap(doc => f(new Tab(ts, doc), ts))
    }

  private def cursorOf(events: List[ServerSentEvent]): Long =
    events
      .filter(isCursor)
      .flatMap(_.signals)
      .lastOption
      .flatMap(s =>
        s.split(s""""${Server.StoreVersionSignal}":""")
          .drop(1)
          .headOption
          .map(_.takeWhile(_.isDigit))
      )
      .map(_.toLong)
      .getOrElse(fail("no cursor in the opening block"))

  private def repainted(events: List[ServerSentEvent]): Boolean =
    events.exists(_.selector.contains("#dashboard"))

  /** The ordinary tab switch, which must not get more expensive: a closed
    * stream sends nothing, so `told` stays put and the missed change comes back
    * as patches.
    */
  test("away, then back: the missed change resumes, no repaint") {
    withTab { (tab, ts) =>
      for {
        opening <- tab.connect(tab.documentCursor)
        held = cursorOf(opening)
        // The session lingers, so the slug is still watched and the frame
        // described.
        _ <- ts.record(FixtureEntity("sensor.a", "hot"))
        back <- tab.connect(held)
      } yield {
        assert(
          back.flatMap(_.elements).exists(_.contains(">hot<")),
          clue = back
        )
        assert(!repainted(back), clue = back)
      }
    }
  }

  /** The server announced a version this client never acknowledges, so `holds`
    * describe a DOM that does not exist; the repaint is the only correct
    * answer.
    */
  test("a cursor behind what we announced repaints, holds notwithstanding") {
    withTab { (tab, ts) =>
      for {
        opening <- tab.connect(tab.documentCursor)
        _ <- ts.record(FixtureEntity("sensor.a", "hot"))
        // Answered: from here the server believes this DOM holds "hot".
        served <- tab.connect(cursorOf(opening))
        announced = cursorOf(served)
        // It never applied what we claimed.
        back <- tab.connect(tab.documentCursor)
      } yield {
        assert(served.flatMap(_.elements).exists(_.contains(">hot<")), served)
        assert(announced > tab.documentCursor, clue = (announced, served))
        assert(repainted(back), clue = back)
        assert(back.flatMap(_.elements).exists(_.contains(">hot<")), back)
      }
    }
  }

  /** '''`told` is not `position`.''' A frame touching nothing this dashboard
    * renders advances the position and announces nothing, so the echo trails
    * the position by design. Gating on `position` would repaint on every tab
    * switch of every real dashboard.
    */
  test("a silent frame moves the position, not the yardstick: still resumes") {
    withTab { (tab, ts) =>
      for {
        release <- tab.held(tab.documentCursor)
        session <- ts.sessions
          .get(tab.document.conn)
          .map(_.getOrElse(fail(s"no session for ${tab.document.conn}")))
        toldBefore <- session.told.get
        // An attribute the card does not read: pulled, and owed nothing.
        _ <- ts.record(
          FixtureEntity("sensor.a", "cold", Map("noise" -> Json.fromInt(1)))
        )
        // Its pull still ran and claimed the version.
        _ <- fs2.Stream
          .repeatEval(session.position.get <* IO.sleep(10.millis))
          .find(_ > tab.documentCursor)
          .compile
          .drain
          .timeout(30.seconds)
        position <- session.position.get
        told <- session.told.get
        _ <- release
        back <- tab.connect(tab.documentCursor)
      } yield {
        // Without this the assertion below passes for the wrong reason.
        assertEquals(told, toldBefore, clue = (told, position))
        assert(position > told, clue = (position, told))
        assert(!repainted(back), clue = back)
      }
    }
  }
}
