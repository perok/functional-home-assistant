package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

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

  // Opens documents; see [[ServerHarness.simulateTime]].
  override protected def simulateTime: Boolean = false

  private def dash = liveLeafDash

  private class Tab(
      routes: HttpApp[IO],
      val sseUrl: String,
      val documentCursor: Long
  ) {

    /** Optionally quoting a different cursor than the client holds, the subject
      * here. Reading the opening block closes the stream: the tab has gone away
      * again.
      */
    def connect(cursor: Long): IO[List[ServerSentEvent]] =
      routes
        .run(
          Request[IO](
            Method.GET,
            Uri.unsafeFromString("/" + withCursor(sseUrl, cursor))
          )
        )
        .flatMap(sseFrom(_)(isCursor))
        .timeout(30.seconds)

    /** Returns once the opening block has landed, so what the fiber collects
      * after is live traffic.
      */
    def held(cursor: Long): IO[IO[Unit]] =
      for {
        seen <- IO.ref(Vector.empty[ServerSentEvent])
        resp <- routes.run(
          Request[IO](
            Method.GET,
            Uri.unsafeFromString("/" + withCursor(sseUrl, cursor))
          )
        )
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

    def conn: String =
      sseUrl
        .split("&")
        .collectFirst {
          case p if p.startsWith(s"${Server.ConnSignal}=") =>
            p.drop(Server.ConnSignal.length + 1)
        }
        .getOrElse(fail(s"no conn on $sseUrl"))

    private def withCursor(url: String, version: Long): String =
      url.replaceAll(
        s"${Server.cursorParam(Server.StoreVersionSignal)}=\\d+",
        s"${Server.cursorParam(Server.StoreVersionSignal)}=$version"
      )
  }

  /** The document establishes the session, including its `told`: the page
    * renders a cursor into itself.
    */
  private def withTab[A](
      f: (Tab, StateStore, Server, Sessions) => IO[A]
  ): IO[A] =
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "cold")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(dash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          val routes = server.routes.orNotFound
          for {
            page <- routes
              .run(Request[IO](Method.GET, uri"/d/dashboard"))
              .flatMap(_.bodyText.compile.string)
            sseUrl = page
              .split("""data-init="@get\('""")(1)
              .split("'")(0)
              .replace("&amp;", "&")
            version <- store.current.map(_.version)
            a <- f(new Tab(routes, sseUrl, version), store, server, sessions)
          } yield a
        }
    } yield out).timeout(60.seconds)

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
    withTab { (tab, store, server, _) =>
      for {
        opening <- tab.connect(tab.documentCursor)
        held = cursorOf(opening)
        // The session lingers, so the slug is still watched and the frame
        // described.
        _ <- change(server, store, es("sensor.a", "hot"))
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
    withTab { (tab, store, server, _) =>
      for {
        opening <- tab.connect(tab.documentCursor)
        _ <- change(server, store, es("sensor.a", "hot"))
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
    withTab { (tab, store, server, sessions) =>
      for {
        release <- tab.held(tab.documentCursor)
        _ <- change(server, store, es("sensor.unwatched", "x"))
        session <- sessions
          .get(tab.conn)
          .map(_.getOrElse(fail(s"no session for ${tab.conn}")))
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
        assertEquals(told, tab.documentCursor, clue = (told, position))
        assert(position > told, clue = (position, told))
        assert(!repainted(back), clue = back)
      }
    }
  }

  /** Through the real store, recorder fiber and doorbell: a test writing the
    * log itself would assert against its own bookkeeping.
    */
  private def change(
      server: Server,
      store: StateStore,
      next: EntityState
  ): IO[Unit] =
    for {
      // A frame published before the recorder attached reaches nobody.
      _ <- store.changeSubscribers.find(_ >= 1).compile.drain
      before <- store.current.map(_.version)
      _ <- store.update(next)
      live <- server.liveSlug("dashboard")
      _ <- live.doorbell.discrete.find(_ > before).compile.drain
    } yield ()
}
