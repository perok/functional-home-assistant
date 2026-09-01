package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** '''The client's cursor is the only proof that bytes were APPLIED.'''
  *
  * A resume trusts `holds`, and `holds` records what was SENT — which is not
  * the same claim. A stream that broke mid-batch, or a tab frozen while its
  * socket kept filling, leaves a session claiming digests that DOM never got,
  * and every later resume computes "nothing owed" against them. The tab is
  * stale until its user reloads, and nothing anywhere reports it. That shipped:
  * a backgrounded tab came back and never caught up.
  *
  * The cursor closes it, because it rides LAST in its batch ([[Server.pull]]):
  * a client echoing version V demonstrably applied everything in front of it.
  * `Session.told` is what it is measured against — the newest version we ever
  * announced — and a cursor behind that means bytes we claimed were lost.
  *
  * The cost of getting the yardstick wrong is what the third test pins. `told`
  * is NOT `position`: a pull that owes this client nothing advances the
  * position silently and announces nothing, so gating on `position` would
  * repaint nearly every reconnect — and a reconnect is what a browser does
  * every time its tab is switched away from and back.
  */
class AckedResumeSuite extends ServerHarness {

  // This suite opens DOCUMENTS, and the page route streams its body through a
  // blocking pipe that simulated time cannot host — see [[ServerHarness.simulateTime]].
  override protected def simulateTime: Boolean = false

  private def dash = liveLeafDash

  /** One browser: the document, then whatever its stream is told. */
  private class Tab(
      routes: HttpApp[IO],
      val sseUrl: String,
      val documentCursor: Long
  ) {

    /** Connect exactly as the page's `data-init` says to, optionally quoting a
      * DIFFERENT cursor than the one the client actually holds — which is the
      * whole subject here. Reads the opening block and stops, which closes the
      * stream: the tab has gone away again.
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

    /** Connect and STAY connected, as a tab in the foreground does, collecting
      * whatever arrives until the caller lets go. Returns once the opening
      * block has landed, so what the fiber collects after that is live traffic.
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

    /** The session this tab's document established. */
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

  /** A server with one dashboard, its document loaded, and the state store the
    * test drives. The document is what establishes the session — including the
    * `told` these tests are about, since the page renders a cursor into itself.
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
          HomeAssistantApi.fromWs(fake),
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

  /** The ordinary tab switch, and the one that must NOT get more expensive.
    *
    * While a stream is closed nothing is sent, so `told` cannot move — the
    * returning client's echo still matches it and the changes it missed come
    * back as patches. If this ever repaints, every tab switch on a live
    * dashboard costs a full body render.
    */
  test("away, then back: the missed change resumes, no repaint") {
    withTab { (tab, store, server, _) =>
      for {
        opening <- tab.connect(tab.documentCursor)
        held = cursorOf(opening)
        // The tab is gone. This lands while nobody is reading, and the slug's
        // own recorder writes it — the session lingers, so the slug is still
        // watched and the frame is still described.
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

  /** The bug itself, at the smallest scale that produces it: the server has
    * announced a version this client never acknowledges. Whatever swallowed the
    * bytes — a break mid-batch, a frozen tab — the session's `holds` now
    * describe a DOM that does not exist, so answering from them is silent,
    * permanent staleness. The repaint is the only correct answer left.
    */
  test("a cursor behind what we announced repaints, holds notwithstanding") {
    withTab { (tab, store, server, _) =>
      for {
        opening <- tab.connect(tab.documentCursor)
        _ <- change(server, store, es("sensor.a", "hot"))
        // A reconnect that IS answered: it takes the patch and, with it, the
        // announcement. From here the server believes this DOM holds "hot".
        served <- tab.connect(cursorOf(opening))
        announced = cursorOf(served)
        // ...and now the client comes back saying it is still at the document's
        // version. It never applied what we claimed it has.
        back <- tab.connect(tab.documentCursor)
      } yield {
        assert(served.flatMap(_.elements).exists(_.contains(">hot<")), served)
        assert(announced > tab.documentCursor, clue = (announced, served))
        assert(repainted(back), clue = back)
        // The repaint is a WHOLE body, not a fragment that happens to match.
        assert(back.flatMap(_.elements).exists(_.contains(">hot<")), back)
      }
    }
  }

  /** '''`told` is not `position`, and this is what that distinction costs if it
    * is got wrong.'''
    *
    * A frame touching an entity this dashboard renders nowhere advances the
    * session's position — every pull does, owed something or not — and
    * announces NOTHING, because a batch with no bytes carries no cursor. So the
    * client's echo legitimately trails the position, by design and routinely.
    *
    * Gate the resume on `position` and this tab is repainted for having done
    * nothing wrong — on a dashboard where any unrendered entity ticks, which is
    * every real one, that is a full body render on every tab switch.
    */
  test("a silent frame moves the position, not the yardstick: still resumes") {
    withTab { (tab, store, server, sessions) =>
      for {
        release <- tab.held(tab.documentCursor)
        // Nothing this dashboard binds, so this client is owed nothing...
        _ <- change(server, store, es("sensor.unwatched", "x"))
        session <- sessions
          .get(tab.conn)
          .map(_.getOrElse(fail(s"no session for ${tab.conn}")))
        // ...but its pull still ran and still claimed the version.
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
        // The precondition this test exists for — without it the assertion
        // below passes for the wrong reason.
        assertEquals(told, tab.documentCursor, clue = (told, position))
        assert(position > told, clue = (position, told))
        assert(!repainted(back), clue = back)
      }
    }
  }

  /** Drive one change through the REAL path — the store, the slug's recorder
    * fiber, its doorbell — and return once the changelog describes it. Not
    * `recordFrame` directly: what a reconnect can be answered with is decided
    * by what that fiber wrote, so a test that wrote the log itself would be
    * asserting against its own bookkeeping.
    */
  private def change(
      server: Server,
      store: StateStore,
      next: EntityState
  ): IO[Unit] =
    for {
      // The recorder subscribes to `changes` when the resource starts; a frame
      // published before it attached reaches nobody.
      _ <- store.changeSubscribers.find(_ >= 1).compile.drain
      before <- store.current.map(_.version)
      _ <- store.update(next)
      live <- server.liveSlug("dashboard")
      _ <- live.doorbell.discrete.find(_ > before).compile.drain
    } yield ()
}
