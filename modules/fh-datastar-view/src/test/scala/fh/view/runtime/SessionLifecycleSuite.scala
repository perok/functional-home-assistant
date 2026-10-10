package fh.view.runtime

import fh.view.query.QuerySnapshot
import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor
import cats.effect.kernel.{Deferred, Ref}
import cats.syntax.all.*
import fh.view.telemetry.Logging
import fh.view.testkit.FixtureEntity
import fh.view.testkit.TestIds.given
import io.circe.Json
import org.http4s.*

import scala.concurrent.duration.*

/** A session's life, from document to reap. Every `Tenure` transition names the
  * tenure it expects to replace, so these tests are mostly about what a race
  * can produce.
  */
class SessionLifecycleSuite extends ServerHarness {

  private val warm = Map("sensor.a" -> es("sensor.a", "warm"))

  private def patchWith(ts: TestServer, query: String): Uri =
    Uri.unsafeFromString(s"/sse/dashboard/${ts.slug}/patch?$query")

  /** Sleeps: a bare retry loop starves the fibers it waits on when threads are
    * few.
    */
  private def awaitTenure(ts: TestServer, conn: String, t: Tenure): IO[Unit] =
    (IO.sleep(5.millis) *> ts.sessions
      .get(conn)
      .flatMap(_.traverse(_.tenure.get)))
      .iterateUntil(_.contains(t))
      .void

  private def awaitGone(ts: TestServer, conn: String): IO[Unit] =
    (IO.sleep(10.millis) *> ts.sessions.get(conn))
      .iterateWhile(_.isDefined)
      .void

  /** Cancelled on the first byte, not the tenure: `adoptOrMint` sets `Held(1)`
    * in the handler, so cancelling on the tenure skips the bracket that
    * registers the stream, the release never hands the session to its linger,
    * and the wait never ends.
    */
  private def openThenDrop(ts: TestServer, stream: Uri): IO[Unit] =
    for {
      resp <- ts.get(stream)
      opened <- Deferred[IO, Unit]
      reading <- resp.body
        .evalTap(_ => opened.complete(()).void)
        .compile
        .drain
        .start
      _ <- opened.get.timeout(5.seconds)
      _ <- reading.cancel
    } yield ()

  private val mixed = Map(
    "sensor.shared" -> es("sensor.shared", "cold"),
    "sensor.a" -> es("sensor.a", "warm")
  )

  test("a first load resumes from the document instead of repainting it") {
    live(mixedTabsDash, mixed) { ts =>
      for {
        doc <- ts.load()
        opening <- ts.get(doc.stream).flatMap(sseFrom(_)(isCursor))
      } yield {
        assert(doc.html.contains(">cold<"), clue = doc.html)
        assert(doc.html.contains(">warm<"), clue = doc.html)
        // The whole opening block, so anything re-sent shows up as an extra
        // event. One event, the cursor: the document seeds `conn` and puts it
        // on this URL, so the stream sends it only when it minted one.
        assertEquals(opening.map(_.name), List(Signals), clue = opening)
        assert(isCursor(opening.head), clue = opening.head)
        assert(
          !opening.head.signals.exists(_.contains(Server.ConnSignal)),
          clue = opening.head
        )
      }
    }
  }

  /** The page render is the first thing that puts fragments in this client's
    * DOM, and the only place that knows what they were, so the document creates
    * the session.
    */
  test("the document establishes the session its stream then adopts") {
    live(mixedTabsDash, mixed) { ts =>
      for {
        doc <- ts.load()
        established <- ts.sessions.get(doc.conn)
        held <- established.traverse(_.state.map(_.holds))
        _ <- ts.get(doc.stream).flatMap(sseFrom(_)(isCursor))
        // A first epoch on the document's object proves the stream took that
        // session. Lingering by now: the stream read its opening block and
        // ended.
        epoch <- established.traverse(_.tenure.get)
        snapshot <- ts.store.current
      } yield {
        val body = Renderer
          .create(mixedTabsDash)
          .renderNodeById(
            "c_0",
            snapshot.entities,
            fragments = QuerySnapshot.empty
          )
        assertEquals(
          held.flatMap(_.get("c_0")),
          body.map(Held.of),
          clue = held
        )
        assertEquals(
          epoch,
          Some(Tenure.Lingering(1): Tenure),
          clue = "the stream adopted it"
        )
      }
    }
  }

  /** Two live streams on one session would record each other's bytes into one
    * `holds`, and each would suppress a change the client never got, so the
    * second displaces the first. The `join` guards where `sseStream` applies
    * its displacement `interruptWhen`: inside `Server.untilRevoked`'s merge the
    * body never ends and this hangs.
    */
  test("a second stream for one session displaces the first") {
    live(liveLeafDash, warm) { ts =>
      for {
        doc <- ts.load()
        first <- ts.get(doc.stream)
        // Only displacement ends this stream. A byte says it is running: the
        // tenures are already set by `adoptOrMint` in the handler, before the
        // body's bracket registers anything.
        opened <- Deferred[IO, Unit]
        drained <- first.body
          .evalTap(_ => opened.complete(()).void)
          .compile
          .drain
          .start
        _ <- opened.get.timeout(5.seconds)
        second <- ts.get(doc.stream)
        current <- second.body.compile.drain.start
        // The join is the assertion: without displacement this times out.
        _ <- drained.join
        // The displaced stream's release must not deregister the session.
        survived <- ts.sessions.get(doc.conn)
        _ <- current.cancel
      } yield assert(survived.isDefined)
    }
  }

  test(
    "a document loaded while HA is down SAYS so, without waiting to connect"
  ) {
    // A literal `haDown: false` seed rendered a page loaded while HA was down
    // as healthy until the stream corrected it.
    def pageWith(up: Boolean): IO[String] =
      live(liveLeafDash, warm)(ts => IO.unlessA(up)(ts.haDown) *> ts.page())

    (pageWith(false), pageWith(true)).flatMapN { (down, up) =>
      IO {
        assert(
          down.contains(s"${Server.HaDownSignal}: true"),
          clue = down.linesIterator.find(_.contains("data-signals"))
        )
        assert(
          up.contains(s"${Server.HaDownSignal}: false"),
          clue = up.linesIterator.find(_.contains("data-signals"))
        )
      }
    }
  }

  test("a connect does not repeat the health the document already rendered") {
    // The document records the banner on the session, so re-emitting it said
    // nothing. The opening-block tests miss it: `haDown` rides the merged
    // streams and arrives after the cursor they stop on.
    def eventsOn(up: Boolean): IO[List[ServerSentEvent]] =
      live(liveLeafDash, warm) { ts =>
        for {
          _ <- IO.unlessA(up)(ts.haDown)
          doc <- ts.load()
          resp <- ts.get(doc.stream)
          seen <- Ref[IO].of(Vector.empty[ServerSentEvent])
          pump <- resp.body
            .through(ServerSentEvent.decoder[IO])
            .evalMap(e => seen.update(_ :+ e))
            .compile
            .drain
            .start
          // `healthy.discrete` fires on subscribe, so anything it would send
          // has gone by now.
          _ <- fs2.Stream
            .repeatEval(seen.get <* IO.sleep(5.millis))
            .find(_.exists(isCursor))
            .compile
            .drain
          _ <- IO.sleep(250.millis)
          got <- seen.get
          _ <- pump.cancel
        } yield got.toList
      }

    (eventsOn(true), eventsOn(false)).flatMapN { (up, down) =>
      IO {
        assert(
          !up.exists(_.data.exists(_.contains(Server.HaDownSignal))),
          clue = up
        )
        // Skipping is about agreeing with the document, not about the value.
        assert(
          !down.exists(_.data.exists(_.contains(Server.HaDownSignal))),
          clue = down
        )
      }
    }
  }

  test(
    "the document seeds `conn`; only a stream that MINTED one announces it"
  ) {
    // The document minted `conn` and put it on the URL; only a bookmarked SSE
    // endpoint names no session.
    live(liveLeafDash, warm) { ts =>
      for {
        doc <- ts.load()
        bare <- ts.sse().flatMap(sseFrom(_)(isCursor))
      } yield {
        // As a signal, so an action POST can echo it without waiting for the
        // stream.
        assert(
          doc.html.contains(s"${Server.ConnSignal}: '${doc.conn}'"),
          clue = doc.conn
        )
        assert(
          bare.exists(_.signals.exists(_.contains(Server.ConnSignal))),
          clue = bare
        )
      }
    }
  }

  test("a reload's `prev` retires the session it superseded") {
    // A reload mints a fresh `conn`. The replaced session would linger with an
    // old `position`, and the floor is the lowest, so a few reloads keep the
    // changelog un-prunable. The client names its predecessor from
    // sessionStorage.
    live(liveLeafDash, warm) { ts =>
      for {
        // Tenure.Fresh, what an abandoned load leaves.
        first <- ts.load().map(_.conn)
        before <- ts.sessions.get(first)
        resp <- ts.get(patchWith(ts, s"${Server.PrevConnParam}=$first"))
        current <- resp.body.compile.drain.start
        // Read immediately: retirement runs while the handler builds `resp`.
        // Polling would let `AdoptionWindow` reap it anyway, passing with
        // retirement deleted.
        after <- ts.sessions.get(first)
        _ <- current.cancel
      } yield assertEquals((before.isDefined, after.isDefined), (true, false))
    }
  }

  test("`prev` never retires a session a stream is still HOLDING") {
    // sessionStorage is copied into a duplicated tab (and Chrome's
    // target=_blank), so the named predecessor can be alive. Only a non-Held
    // session is retired.
    live(liveLeafDash, warm) { ts =>
      for {
        held <- ts.load()
        heldStream <- ts.get(held.stream)
        alive <- heldStream.body.compile.drain.start
        _ <- ts.sessions.liveStreams.filter(_ >= 1).head.compile.drain
        other <- ts.load()
        resp <- ts.get(
          other.stream.withQueryParam(Server.PrevConnParam, held.conn)
        )
        second <- resp.body.compile.drain.start
        _ <- IO.sleep(100.millis)
        survived <- ts.sessions.get(held.conn)
        _ <- alive.cancel *> second.cancel
      } yield assert(survived.isDefined)
    }
  }

  /** A dropped stream is normal: a sleeping phone, a wifi handover. The same
    * session must come back, since a new one under the same `conn` would have
    * an empty `holds`.
    */
  test(
    "a dropped stream leaves its session lingering, and a reconnect takes it back"
  ) {
    live(liveLeafDash, warm) { ts =>
      for {
        doc <- ts.load()
        _ <- openThenDrop(ts, doc.stream)
        _ <- awaitTenure(ts, doc.conn, Tenure.Lingering(1))
        before <- ts.sessions.get(doc.conn)
        heldBefore <- before.traverse(_.state.map(_.holds))
        // The same URL, as Datastar's retry does.
        second <- ts.get(doc.stream)
        current <- second.body.compile.drain.start
        _ <- awaitTenure(ts, doc.conn, Tenure.Held(2))
        after <- ts.sessions.get(doc.conn)
        _ <- current.cancel
      } yield {
        assert(
          before.isDefined && after.exists(a => before.exists(_ eq a)),
          clue = "the reconnect adopted the very session the drop left behind"
        )
        // The record of this client's DOM, which a fresh session could not
        // have.
        assert(heldBefore.exists(_.nonEmpty), clue = heldBefore)
      }
    }
  }

  /** A client that never comes back must not cost a map read on every batch for
    * the life of the process.
    */
  test("a session nobody comes back for is reaped") {
    live(
      liveLeafDash,
      warm,
      Server.SessionWindows.default.copy(linger = 50.millis)
    ) { ts =>
      ts.load()
        .flatMap(doc => openThenDrop(ts, doc.stream) *> awaitGone(ts, doc.conn))
    }
  }

  test("a document nobody connects to does not leak a session") {
    live(
      liveLeafDash,
      warm,
      Server.SessionWindows.default.copy(adoption = 50.millis)
    ) { ts =>
      for {
        doc <- ts.load()
        // Present when the document is served, for a stream opening a beat
        // later.
        before <- ts.sessions.get(doc.conn)
        // Gone once the window passes: every live session is read on every
        // batch.
        _ <- awaitGone(ts, doc.conn)
      } yield assert(before.isDefined)
    }
  }

  test("end to end: a leaf tick, then one that renders identically") {
    liveOne(
      liveLeafDash,
      Map("sensor.a" -> es("sensor.a", "cold"))
    ) { (world, client) =>
      for {
        _ <- client.drain
        // An outer morph targets the id inside its own HTML and names no
        // selector.
        hot <- world.change("sensor.a", "hot") *> client.drain
        _ = assertEquals(
          domEvents(hot),
          List(
            (
              "outer",
              None,
              Some("""<div class="fh-cell" id="c_0"><span>hot</span></div>""")
            )
          ),
          clue = hot
        )
        _ = assert(hot.exists(isCursor), clue = hot)
        // A frame that renders identically sends nothing, not even a cursor.
        again <- world.frame(
          FixtureEntity("sensor.a", "hot", Map("unrelated" -> Json.fromInt(7)))
        ) *> client.drain
        _ = assertEquals(domEvents(again), Nil, clue = again)
      } yield ()
    }
  }

  private def answer(n: Int)(s: SessionState): IO[Session.Step[Int]] =
    IO.pure(Session.Step(s, Nil, n))

  private def isEnded(r: Either[Throwable, ?]): Boolean =
    r.left.exists(Session.isEnded)

  test("a reaped session's owner stops, and refuses what it is asked next") {
    // A supervisor holds a fiber until it completes, and an owner waiting on its
    // inbox never does: unless a reap ends it, every session ever served stays
    // in memory until shutdown.
    Supervisor[IO].use { supervisor =>
      for {
        session <- Session.create(
          "d",
          supervisor,
          Logging.console.getLoggerFromName("test")
        )
        served <- session.run(answer(1))
        _ <- session.relinquish(Tenure.Fresh)
        refused <- session
          .run(answer(2))
          .attempt
          .iterateUntil(isEnded)
          .timeout(5.seconds)
      } yield {
        assertEquals(served, 1)
        assert(isEnded(refused), clue = refused)
      }
    }
  }

  test(
    "a step running when its session is reaped is refused, not left hanging"
  ) {
    (Supervisor[IO], Resource.eval(Deferred[IO, Unit])).tupled
      .use { (supervisor, entered) =>
        for {
          session <- Session.create(
            "d",
            supervisor,
            Logging.console.getLoggerFromName("test")
          )
          caller <- session
            .run(_ => entered.complete(()) *> IO.never[Session.Step[Unit]])
            .attempt
            .start
          _ <- entered.get
          _ <- session.relinquish(Tenure.Fresh)
          result <- caller.joinWithNever.timeout(5.seconds)
        } yield assert(isEnded(result), clue = result)
      }
  }

  test("after shutdown a step is refused, not left hanging") {
    ownerlessSession("d")
      .flatMap(_.run(answer(1)).attempt)
      .timeout(5.seconds)
      .map(r => assert(isEnded(r), clue = r))
  }

  test("a reap racing the owner's take never strands a caller") {
    // A cancel landing between the take and its handler lost the command. Rare
    // for any one reap, so many of them.
    Supervisor[IO].use { supervisor =>
      List
        .range(0, 5000)
        .traverse_ { _ =>
          for {
            session <- Session.create(
              "d",
              supervisor,
              Logging.console.getLoggerFromName("test")
            )
            caller <- session.run(answer(1)).attempt.start
            _ <- session.relinquish(Tenure.Fresh)
            _ <- caller.joinWithNever
          } yield ()
        }
        .timeout(30.seconds)
    }
  }

}
