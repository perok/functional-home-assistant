package fh.view.runtime

import fh.view.query.QuerySnapshot
import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.syntax.all.*
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** A session's life, from document to reap. Every `Tenure` transition names the
  * tenure it expects to replace, so these tests are mostly about what a race
  * can produce.
  */
class SessionLifecycleSuite extends ServerHarness {

  // Opens documents; see [[ServerHarness.simulateTime]].
  override protected def simulateTime: Boolean = false

  test("a first load resumes from the document instead of repainting it") {
    val dash = mixedTabsDash
    val initial = Map(
      "sensor.shared" -> es("sensor.shared", "cold"),
      "sensor.a" -> es("sensor.a", "warm")
    )
    (for {
      store <- StateStore.inMemory(initial)
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
            // Unescaped, as a browser parses the attribute.
            sseUrl = page
              .split("""data-init="@get\('""")(1)
              .split("'")(0)
              .replace("&amp;", "&")
            opening <- routes
              .run(
                Request[IO](Method.GET, Uri.unsafeFromString("/" + sseUrl))
              )
              .flatMap(sseFrom(_)(isCursor))
          } yield (page, opening)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (page, opening) =>
        assert(page.contains(">cold<"), clue = page)
        assert(page.contains(">warm<"), clue = page)
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

  /** The page render is the first thing that puts fragments in this client's
    * DOM, and the only place that knows what they were, so the document creates
    * the session.
    */

  test("the document establishes the session its stream then adopts") {
    val dash = mixedTabsDash
    val initial = Map(
      "sensor.shared" -> es("sensor.shared", "cold"),
      "sensor.a" -> es("sensor.a", "warm")
    )
    (for {
      store <- StateStore.inMemory(initial)
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
            conn = Uri
              .unsafeFromString("/" + sseUrl)
              .query
              .params(Server.ConnSignal)
            established <- sessions.get(conn)
            held <- established.traverse(_.holds.get)
            _ <- routes
              .run(Request[IO](Method.GET, Uri.unsafeFromString("/" + sseUrl)))
              .flatMap(sseFrom(_)(isCursor))
            // A first epoch on the document's object proves the stream took
            // that session. Lingering by now: the stream read its opening block
            // and ended.
            epoch <- established.traverse(_.tenure.get)
            renderer <- ref.get.map(_.rendererOf.get)
            snapshot <- store.current
          } yield (held, epoch, renderer, snapshot)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (held, epoch, renderer, snapshot) =>
        val body = renderer.renderNodeById(
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

  /** Two live streams on one session would record each other's bytes into one
    * `holds`, and each would suppress a change the client never got, so the
    * second displaces the first. Server topology, so the real runtime. The
    * `join` guards where `sseStream` applies its displacement `interruptWhen`:
    * inside `Server.untilRevoked`'s merge the body never ends and this hangs.
    */
  testReal("a second stream for one session displaces the first") {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(liveLeafDash))
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
            url = Uri.unsafeFromString(
              "/" + page
                .split("""data-init="@get\('""")(1)
                .split("'")(0)
                .replace("&amp;", "&")
            )
            conn = url.query.params(Server.ConnSignal)
            first <- routes.run(Request[IO](Method.GET, url))
            // Only displacement ends this stream. A byte says it is running:
            // the tenures are already set by `adoptOrMint` in the handler,
            // before the body's bracket registers anything.
            opened <- Deferred[IO, Unit]
            drained <- first.body
              .evalTap(_ => opened.complete(()).void)
              .compile
              .drain
              .start
            _ <- opened.get.timeout(5.seconds)
            second <- routes.run(Request[IO](Method.GET, url))
            live <- second.body.compile.drain.start
            // The join is the assertion: without displacement this times out.
            _ <- drained.join
            // The displaced stream's release must not deregister the session.
            survived <- sessions.get(conn)
            _ <- live.cancel
          } yield survived.isDefined
        }
    } yield out).timeout(30.seconds).map(assert(_))
  }

  test(
    "a document loaded while HA is down SAYS so, without waiting to connect"
  ) {
    // A literal `haDown: false` seed rendered a page loaded while HA was down
    // as healthy until the stream corrected it.
    def pageWith(healthy: Boolean): IO[String] =
      for {
        store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
        ref <- SignallingRef[IO].of(
          Server.RendererState.Ready(Renderer.create(liveLeafDash))
        )
        sessions <- Sessions.create
        fake <- FakeHomeAssistant.create(Nil)
        html <- Server
          .resource(
            ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
            store,
            Map("dashboard" -> ref),
            "dashboard",
            sessions,
            TestAuth.openGate,
            healthy = fs2.concurrent.Signal.constant(healthy)
          )
          .use(
            _.routes.orNotFound
              .run(Request[IO](Method.GET, uri"/d/dashboard"))
              .flatMap(_.bodyText.compile.string)
          )
      } yield html

    (pageWith(false), pageWith(true))
      .flatMapN { (down, up) =>
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
      .timeout(30.seconds)
  }

  test("a first connect resumes from AFTER its document's version") {
    // A document has all of version V, so it needs `> V`. `Server.resumeFrom`
    // told first connect from reconnect by the presence of signals, but
    // Datastar sets `datastar={}` on every GET, so every page load took the
    // reconnect branch; `hasSignals` tests for a non-empty store.
    //
    // Only renders show it (the document's `holds` suppress the wire), and only
    // on a cold cache: a pulling session would let `RenderCache` serve the
    // nodes. Hence a document that never connects holds the gate open.
    val count = new AtomicInteger(0)
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "A0")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(
          new CountingRenderer(liveLeafDash, count): Renderer
        )
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
          def openDocument: IO[Uri] =
            routes
              .run(Request[IO](Method.GET, uri"/d/dashboard"))
              .flatMap(_.bodyText.compile.string)
              .map(page =>
                Uri.unsafeFromString(
                  "/" + page
                    .split(SseUrlMarker)(1)
                    .split("'")(0)
                    .replace("&amp;", "&")
                )
              )
          for {
            // Holds the recording gate open without pulling, so nothing warms
            // the cache.
            _ <- openDocument
            _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
            _ <- store.update(es("sensor.a", "A1"))
            // The recorder's fiber is unobservable here. Reverting `hasSignals`
            // fails this test, so the wait is long enough.
            _ <- IO.sleep(300.millis)
            _ <- IO(count.set(0))
            url <- openDocument
            // What a browser adds and the `data-init` URL does not: an empty
            // store, since `data-init` fires before descendants' `data-signals`
            // merge. Without it both branches read the query params, which is
            // why the bug survived.
            resp <- routes.run(
              Request[IO](
                Method.GET,
                url.withQueryParam("datastar", "{}")
              )
            )
            block <- sseFrom(resp)(isCursor)
          } yield (count.get(), block)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (renders, block) =>
        assertEquals(renders, 0, clue = block)
      }
  }

  test("a connect does not repeat the health the document already rendered") {
    // The document records the banner on the session, so re-emitting it said
    // nothing. The opening-block tests miss it: `haDown` rides the merged
    // streams and arrives after the cursor they stop on.
    def eventsOn(healthy: Boolean): IO[List[ServerSentEvent]] =
      for {
        store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
        ref <- SignallingRef[IO].of(
          Server.RendererState.Ready(Renderer.create(liveLeafDash))
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
            TestAuth.openGate,
            healthy = fs2.concurrent.Signal.constant(healthy)
          )
          .use { server =>
            val routes = server.routes.orNotFound
            for {
              page <- routes
                .run(Request[IO](Method.GET, uri"/d/dashboard"))
                .flatMap(_.bodyText.compile.string)
              url = Uri.unsafeFromString(
                "/" + page
                  .split(SseUrlMarker)(1)
                  .split("'")(0)
                  .replace("&amp;", "&")
              )
              resp <- routes.run(Request[IO](Method.GET, url))
              seen <- Ref[IO].of(Vector.empty[ServerSentEvent])
              pump <- resp.body
                .through(ServerSentEvent.decoder[IO])
                .evalMap(e => seen.update(_ :+ e))
                .compile
                .drain
                .start
              // `healthy.discrete` fires on subscribe, so anything it would
              // send has gone by now.
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
      } yield out

    (eventsOn(true), eventsOn(false))
      .flatMapN { (up, down) =>
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
      .timeout(30.seconds)
  }

  test(
    "the document seeds `conn`; only a stream that MINTED one announces it"
  ) {
    // The document minted `conn` and put it on the URL; only a bookmarked SSE
    // endpoint names no session.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(liveLeafDash))
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
            conn = Uri
              .unsafeFromString(
                "/" + page
                  .split("""data-init="@get\('""")(1)
                  .split("'")(0)
                  .replace("&amp;", "&")
              )
              .query
              .params(Server.ConnSignal)
            bare <- routes
              .run(
                Request[IO](
                  Method.GET,
                  uri"/sse/dashboard/dashboard/patch"
                )
              )
              .flatMap(sseFrom(_)(isCursor))
          } yield (page, conn, bare)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (page, conn, bare) =>
        // As a signal, so an action POST can echo it without waiting for the
        // stream.
        assert(page.contains(s"${Server.ConnSignal}: '$conn'"), clue = conn)
        assert(
          bare.exists(_.signals.exists(_.contains(Server.ConnSignal))),
          clue = bare
        )
      }
  }

  test("a reload's `prev` retires the session it superseded") {
    // A reload mints a fresh `conn`. The replaced session would linger with an
    // old `position`, and the floor is the lowest, so a few reloads keep the
    // changelog un-prunable. The client names its predecessor from
    // sessionStorage.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(liveLeafDash))
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
            // Tenure.Fresh, what an abandoned load leaves.
            first <- connOfPage(routes)
            before <- sessions.get(first)
            resp <- routes.run(
              Request[IO](
                Method.GET,
                Uri.unsafeFromString(
                  s"/sse/dashboard/dashboard/patch?${Server.PrevConnParam}=$first"
                )
              )
            )
            live <- resp.body.compile.drain.start
            // Read immediately: retirement runs while the handler builds
            // `resp`. Polling would let `AdoptionWindow` reap it anyway,
            // passing with retirement deleted.
            after <- sessions.get(first)
            _ <- live.cancel
          } yield (before.isDefined, after.isDefined)
        }
    } yield out).timeout(30.seconds).assertEquals((true, false))
  }

  test("`prev` never retires a session a stream is still HOLDING") {
    // sessionStorage is copied into a duplicated tab (and Chrome's
    // target=_blank), so the named predecessor can be alive. Only a non-Held
    // session is retired.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(liveLeafDash))
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
            held <- connOfPage(routes)
            heldStream <- routes.run(
              Request[IO](
                Method.GET,
                Uri.unsafeFromString(
                  s"/sse/dashboard/dashboard/patch?${Server.ConnSignal}=$held"
                )
              )
            )
            alive <- heldStream.body.compile.drain.start
            _ <- sessions.liveStreams.filter(_ >= 1).head.compile.drain
            other <- connOfPage(routes)
            resp <- routes.run(
              Request[IO](
                Method.GET,
                Uri.unsafeFromString(
                  s"/sse/dashboard/dashboard/patch?${Server.ConnSignal}=$other" +
                    s"&${Server.PrevConnParam}=$held"
                )
              )
            )
            second <- resp.body.compile.drain.start
            _ <- IO.sleep(100.millis)
            survived <- sessions.get(held)
            _ <- alive.cancel *> second.cancel
          } yield survived.isDefined
        }
    } yield out).timeout(30.seconds).assert
  }

  test("a frame this client is owed nothing for puts NOTHING on its wire") {
    // No events at all: the cursor rides the keepalive rather than every pull,
    // which is why `LiveWorld.change` gates on the server.
    liveWorld(
      twoTabsDash,
      Map(
        "sensor.shared" -> es("sensor.shared", "s0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { world =>
      for {
        onT0 <- world.connect()
        onT1 <- world.connect("?ui.c_1=1")
        _ <- onT0.drain
        _ <- onT1.drain
        _ <- world.change(es("sensor.a", "A1"))
        a0 <- onT0.drain
        a1 <- onT1.drain
      } yield {
        assert(a0.nonEmpty, clue = a0)
        assertEquals(a1, Nil, clue = ("tab 1 gets no bytes at all", a1))
      }
    }
  }

  /** A dropped stream is normal: a sleeping phone, a wifi handover. The same
    * session must come back, since a new one under the same `conn` would have
    * an empty `holds`.
    */

  test(
    "a dropped stream leaves its session lingering, and a reconnect takes it back"
  ) {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(liveLeafDash))
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
          // Sleeps: a bare retry loop starves the fibers it waits on when
          // threads are few.
          def awaitTenure(conn: String, t: Tenure): IO[Unit] =
            (IO.sleep(5.millis) *> sessions
              .get(conn)
              .flatMap(_.traverse(_.tenure.get)))
              .iterateUntil(_.contains(t))
              .void
          for {
            page <- routes
              .run(Request[IO](Method.GET, uri"/d/dashboard"))
              .flatMap(_.bodyText.compile.string)
            url = Uri.unsafeFromString(
              "/" + page
                .split("""data-init="@get\('""")(1)
                .split("'")(0)
                .replace("&amp;", "&")
            )
            conn = url.query.params(Server.ConnSignal)
            first <- routes.run(Request[IO](Method.GET, url))
            // The first byte, not the tenure: `adoptOrMint` sets `Held(1)` in
            // the handler, so cancelling on the tenure skips the bracket that
            // registers the stream, the release never hands the session to its
            // linger, and the wait never ends.
            opened <- Deferred[IO, Unit]
            reading <- first.body
              .evalTap(_ => opened.complete(()).void)
              .compile
              .drain
              .start
            _ <- opened.get
            _ <- reading.cancel
            _ <- awaitTenure(conn, Tenure.Lingering(1))
            before <- sessions.get(conn)
            heldBefore <- before.traverse(_.holds.get)
            // The same URL, as Datastar's retry does.
            second <- routes.run(Request[IO](Method.GET, url))
            live <- second.body.compile.drain.start
            _ <- awaitTenure(conn, Tenure.Held(2))
            after <- sessions.get(conn)
            _ <- live.cancel
          } yield (before, after, heldBefore)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (before, after, heldBefore) =>
        assert(
          before.isDefined && after.exists(a => before.exists(_ eq a)),
          clue = "the reconnect adopted the very session the drop left behind"
        )
        // The record of this client's DOM, which a fresh session could not
        // have.
        assert(heldBefore.exists(_.nonEmpty), clue = heldBefore)
      }
  }

  /** A client that never comes back must not cost a map read on every batch for
    * the life of the process.
    */

  test("a session nobody comes back for is reaped") {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(liveLeafDash))
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
          TestAuth.openGate,
          lingerWindow = 50.millis
        )
        .use { server =>
          val routes = server.routes.orNotFound
          for {
            page <- routes
              .run(Request[IO](Method.GET, uri"/d/dashboard"))
              .flatMap(_.bodyText.compile.string)
            url = Uri.unsafeFromString(
              "/" + page
                .split("""data-init="@get\('""")(1)
                .split("'")(0)
                .replace("&amp;", "&")
            )
            conn = url.query.params(Server.ConnSignal)
            first <- routes.run(Request[IO](Method.GET, url))
            // The first byte, not the tenure; see the test above.
            opened <- Deferred[IO, Unit]
            reading <- first.body
              .evalTap(_ => opened.complete(()).void)
              .compile
              .drain
              .start
            _ <- opened.get.timeout(5.seconds)
            _ <- reading.cancel
            _ <- (IO.sleep(10.millis) *> sessions.get(conn))
              .iterateWhile(_.isDefined)
          } yield ()
        }
    } yield out).timeout(30.seconds).void
  }

  test("a document nobody connects to does not leak a session") {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(liveLeafDash))
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
          TestAuth.openGate,
          adoptionWindow = 50.millis
        )
        .use { server =>
          for {
            page <- server.routes.orNotFound
              .run(Request[IO](Method.GET, uri"/d/dashboard"))
              .flatMap(_.bodyText.compile.string)
            conn = Uri
              .unsafeFromString(
                "/" + page
                  .split("""data-init="@get\('""")(1)
                  .split("'")(0)
                  .replace("&amp;", "&")
              )
              .query
              .params(Server.ConnSignal)
            // Present when the document is served, for a stream opening a beat
            // later.
            before <- sessions.get(conn)
            // Gone once the window passes: every live session is read on every
            // batch.
            _ <- (IO.sleep(10.millis) *> sessions.get(conn))
              .iterateWhile(_.isDefined)
          } yield before.isDefined
        }
    } yield out).timeout(30.seconds).map(assert(_))
  }

  test("end to end: a leaf tick, then the same value again") {
    liveClient(
      liveLeafDash,
      Map("sensor.a" -> es("sensor.a", "cold"))
    ) { (world, client) =>
      for {
        _ <- client.drain
        // An outer morph targets the id inside its own HTML and names no
        // selector.
        hot <- world.change(es("sensor.a", "hot")) *> client.drain
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
        // A change that renders identically sends nothing, not even a cursor.
        again <-
          world.change(es("sensor.a", "hot")) *> client.drain
        _ = assertEquals(domEvents(again), Nil, clue = again)
      } yield ()
    }
  }

}
