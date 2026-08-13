package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.syntax.all.*
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.headers.{`Cache-Control`, `If-None-Match`, ETag}
import org.http4s.implicits.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** A session's whole life: the document that establishes it, the stream that
  * adopts it, displacement, the linger after a disconnect, the reap, and the
  * handover a reload performs.
  *
  * Its state machine is `Tenure`, and every transition names the tenure it
  * expects to replace — so these tests are mostly about the transitions a RACE
  * can produce, not the happy path.
  */
class SessionLifecycleSuite extends ServerHarness {

  test("a first load resumes from the document instead of repainting it") {
    val dash = mixedTabsDash
    val initial = Map(
      "sensor.shared" -> es("sensor.shared", "cold"),
      "sensor.a" -> es("sensor.a", "warm")
    )
    (for {
      store <- StateStore.inMemory(initial)
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          val routes = server.routes.orNotFound
          for {
            page <- routes
              .run(Request[IO](Method.GET, uri"/d/dashboard"))
              .flatMap(_.bodyText.compile.string)
            // Take the URL the page itself advertises, unescaped as a browser
            // would parse the attribute.
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
        // The document really does carry the dashboard...
        assert(page.contains(">cold<"), clue = page)
        assert(page.contains(">warm<"), clue = page)
        // ...so the stream sends none of it again. Stated as the WHOLE opening
        // block, event by event, so anything the server started re-sending
        // shows up here as an extra event rather than slipping past a negative
        // match.
        //
        // ONE event: the cursor. The `conn` used to lead it, and no longer
        // does — the document seeds that signal and puts it on this URL, so
        // announcing it back was telling the client its own id. The stream
        // still sends it when it MINTED one (a bookmarked SSE endpoint), which
        // is not this case.
        assertEquals(opening.map(_.name), List(Signals), clue = opening)
        assert(isCursor(opening.head), clue = opening.head)
        assert(
          !opening.head.signals.exists(_.contains(Server.ConnSignal)),
          clue = opening.head
        )
      }
  }

  /** The document, not the stream, creates the session — because the page
    * render is the first thing that puts fragments in this client's DOM and the
    * only place that knows what they were. So the suppression the test above
    * asserts end to end has a per-client record behind it, not a shared one.
    */

  test("the document establishes the session its stream then adopts") {
    val dash = mixedTabsDash
    val initial = Map(
      "sensor.shared" -> es("sensor.shared", "cold"),
      "sensor.a" -> es("sensor.a", "warm")
    )
    (for {
      store <- StateStore.inMemory(initial)
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
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
            // The id the document minted, which the URL it advertises carries.
            conn = Uri
              .unsafeFromString("/" + sseUrl)
              .query
              .params(Server.ConnSignal)
            established <- sessions.get(conn)
            held <- established.traverse(_.holds.get)
            // ...and the stream takes THAT session rather than making its own.
            _ <- routes
              .run(Request[IO](Method.GET, Uri.unsafeFromString("/" + sseUrl)))
              .flatMap(sseFrom(_)(isCursor))
            // Read off the object the DOCUMENT made: a FIRST epoch there is
            // the proof the stream took that session rather than minting one.
            // Lingering by now, since this stream has read its opening block
            // and ended.
            epoch <- established.traverse(_.tenure.get)
            renderer <- ref.get
            snapshot <- store.current
          } yield (held, epoch, renderer, snapshot)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (held, epoch, renderer, snapshot) =>
        // Its own render, node by node — not a projection of anyone else's.
        val body = renderer.renderNodeById("c_0", snapshot.entities)
        assertEquals(
          held.flatMap(_.get("c_0")),
          body.map(Digest.of),
          clue = held
        )
        assertEquals(
          epoch,
          Some(Tenure.Lingering(1): Tenure),
          clue = "the stream adopted it"
        )
      }
  }

  /** Two live streams on one session would each record bytes the other sent
    * into one `holds` map, and each would then suppress a change the client
    * never received — the one way a per-client record can go wrong by itself.
    * So the second stream displaces the first.
    */

  test("a second stream for one session displaces the first") {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(Renderer.create(liveLeafDash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
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
            // Nothing ends this stream but displacement: it is the keepalive
            // path, with no client hanging up.
            drained <- first.body.compile.drain.start
            second <- routes.run(Request[IO](Method.GET, url))
            live <- second.body.compile.drain.start
            // The join is the assertion. Without displacement the first stream
            // runs forever and this times out.
            _ <- drained.join
            // ...and the second still owns the session: the displaced stream's
            // own release must not deregister it on the way out.
            survived <- sessions.get(conn)
            _ <- live.cancel
          } yield survived.isDefined
        }
    } yield out).timeout(30.seconds).map(assert(_))
  }

  test(
    "a document loaded while HA is down SAYS so, without waiting to connect"
  ) {
    // The banner used to seed `haDown: false` as a literal, so a page loaded
    // while HA was unreachable rendered as healthy and stayed that way until
    // the stream connected and corrected it — a wrong banner on the one screen
    // whose job is to report that.
    def pageWith(healthy: Boolean): IO[String] =
      for {
        store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
        ref <- SignallingRef[IO].of(Renderer.create(liveLeafDash))
        sessions <- Sessions.create
        fake <- FakeHomeAssistant.create(Nil)
        html <- Server
          .resource(
            HomeAssistantApi.fromWs(fake),
            store,
            Map("dashboard" -> ref),
            "dashboard",
            sessions,
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
    // A document was rendered from one snapshot, so it has ALL of version V —
    // asking for `>= V` hands back everything it already contains. It is
    // complete through V, so it needs `> V`. `Server.resumeFrom` says exactly
    // that, and told the two apart by whether the request carried signals...
    // which a first connect DOES: Datastar sets `datastar={}` on every GET. So
    // every page load took the reconnect branch, and the fix is `hasSignals`
    // testing for a NON-EMPTY store.
    //
    // Only RENDERS can see it — the document seeds its own `holds`, so the
    // redundant work is suppressed before it reaches the wire. And only on a
    // COLD cache: with another session already pulling, `RenderCache` serves
    // those nodes and `renderNodeById` is never called, which is why the gate
    // here is held open by a document that never connects.
    val count = new AtomicInteger(0)
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "A0")))
      ref <- SignallingRef[IO].of(
        new CountingRenderer(liveLeafDash, count): Renderer
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
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
            // A document that never connects. Its session holds the recording
            // gate open — a slug nobody is watching records nothing — WITHOUT
            // pulling, so nothing warms the render cache.
            _ <- openDocument
            _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
            _ <- store.update(es("sensor.a", "A1"))
            // The recorder writes on its own fiber and nothing here can observe
            // it (no stream to watch). Sabotage is what keeps this honest:
            // reverting `hasSignals` fails this test, which it could not do if
            // the wait were too short.
            _ <- IO.sleep(300.millis)
            // Now a document rendered AT that version, and complete through it.
            _ <- IO(count.set(0))
            url <- openDocument
            // `datastar={}` is what a BROWSER adds and the server-built
            // `data-init` URL does not: Datastar serialises its signal store
            // into every GET, and on a first connect that store is empty
            // because `data-init` fires before the descendants' `data-signals`
            // are merged. Without this the harness cannot see the difference at
            // all — both branches read the query params — which is exactly why
            // the bug survived.
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
    // The document renders the banner's value into the page and records it on
    // the session, so an ordinary load is already correct. Emitting it again on
    // connect said nothing — and this is NOT caught by the opening-block tests,
    // because `haDown` rides the merged streams and arrives after the cursor
    // they stop on.
    def eventsOn(healthy: Boolean): IO[List[ServerSentEvent]] =
      for {
        store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
        ref <- SignallingRef[IO].of(Renderer.create(liveLeafDash))
        sessions <- Sessions.create
        fake <- FakeHomeAssistant.create(Nil)
        out <- Server
          .resource(
            HomeAssistantApi.fromWs(fake),
            store,
            Map("dashboard" -> ref),
            "dashboard",
            sessions,
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
              // Past the opening block, then a settle: `healthy.discrete` fires
              // on SUBSCRIBE, so anything it was going to send has been sent
              // well before this.
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
          // ...and the same when HA is DOWN: the page rendered `true`, so
          // repeating it is just as redundant. Skipping is about agreement with
          // the document, not about the value being false.
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
    // Every ordinary load carries `conn` on the SSE URL because the document
    // minted it, so echoing it back said nothing. A bookmarked SSE endpoint
    // names no session, and there the client genuinely does not know.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
      ref <- SignallingRef[IO].of(Renderer.create(liveLeafDash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
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
            // No conn at all: the bookmarked case.
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
        // The page carries it as a signal, so an action POST can echo it
        // without waiting for the stream to say what it already knows.
        assert(page.contains(s"${Server.ConnSignal}: '$conn'"), clue = conn)
        // ...and a stream that had to mint one still says so.
        assert(
          bare.exists(_.signals.exists(_.contains(Server.ConnSignal))),
          clue = bare
        )
      }
  }

  test("a reload's `prev` retires the session it superseded") {
    // A reload mints a fresh `conn`, so without this the session it replaced
    // sits in the registry for the whole linger window holding an old
    // `position` — and the floor is the LOWEST position, so a few reloads keep
    // the changelog un-prunable. The client names its predecessor from
    // sessionStorage; the server drops it.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
      ref <- SignallingRef[IO].of(Renderer.create(liveLeafDash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          val routes = server.routes.orNotFound
          for {
            // A document nobody connected to: Tenure.Fresh, exactly what an
            // abandoned load leaves behind.
            first <- connOfPage(routes)
            before <- sessions.get(first)
            // The reload's stream, naming its predecessor.
            resp <- routes.run(
              Request[IO](
                Method.GET,
                Uri.unsafeFromString(
                  s"/sse/dashboard/dashboard/patch?${Server.PrevConnParam}=$first"
                )
              )
            )
            live <- resp.body.compile.drain.start
            // Read IMMEDIATELY, with no polling: the retirement happens while
            // the handler builds its response, so it has already run by the
            // time `resp` exists. Waiting instead would let `AdoptionWindow`
            // reap this session on its own and the test would pass with the
            // retirement deleted — which is exactly what a first draft did.
            after <- sessions.get(first)
            _ <- live.cancel
          } yield (before.isDefined, after.isDefined)
        }
    } yield out).timeout(30.seconds).assertEquals((true, false))
  }

  test("`prev` never retires a session a stream is still HOLDING") {
    // sessionStorage is copied into a duplicated tab (and, in Chrome, into one
    // opened via target=_blank), so the predecessor a document names can belong
    // to a tab that is very much alive. Retiring only a non-Held session makes
    // that a no-op instead of pulling the rug from under a live viewer.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
      ref <- SignallingRef[IO].of(Renderer.create(liveLeafDash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
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
            // The duplicated tab: its own document, naming the live one.
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
    // Not merely "no element patches" — no events at all. The cursor used to go
    // out on every pull, which made a quiet frame cost one signal per client;
    // it rides the keepalive now instead. This is the contract that removal
    // creates, and the reason `LiveWorld.change` gates on the server rather
    // than on a cursor arriving.
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
        // Inside tab 0's panel only.
        _ <- world.change(es("sensor.a", "A1"))
        a0 <- onT0.drain
        a1 <- onT1.drain
      } yield {
        assert(a0.nonEmpty, clue = a0)
        assertEquals(a1, Nil, clue = ("tab 1 gets no bytes at all", a1))
      }
    }
  }

  /** A dropped SSE stream is the NORMAL case, not a goodbye: a phone sleeping,
    * a lid closing, a wifi handover. The session outlives it, so the client
    * that comes back is told what moved rather than repainted — and it is the
    * SAME session, because a new one under the same `conn` would have an empty
    * `holds` and could only claim what it re-sent.
    */

  test(
    "a dropped stream leaves its session lingering, and a reconnect takes it back"
  ) {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(Renderer.create(liveLeafDash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          val routes = server.routes.orNotFound
          // Sleeps, because a bare retry loop starves the very fibers it is
          // waiting on when the runtime has few threads.
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
            reading <- first.body.compile.drain.start
            _ <- awaitTenure(conn, Tenure.Held(1))
            // The client hangs up.
            _ <- reading.cancel
            _ <- awaitTenure(conn, Tenure.Lingering(1))
            before <- sessions.get(conn)
            heldBefore <- before.traverse(_.holds.get)
            // ...and comes back to the same URL, as Datastar's own retry does.
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
        // What makes that worth doing: the record of this client's DOM, which
        // the document seeded and a fresh session could not have.
        assert(heldBefore.exists(_.nonEmpty), clue = heldBefore)
      }
  }

  /** The other end of the same window: a client that never comes back must not
    * cost a map read on every state batch for the life of the process.
    */

  test("a session nobody comes back for is reaped") {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(Renderer.create(liveLeafDash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
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
            reading <- first.body.compile.drain.start
            _ <- (IO.sleep(5.millis) *> sessions
              .get(conn)
              .flatMap(_.traverse(_.tenure.get)))
              .iterateUntil(_.contains(Tenure.Held(1)))
            _ <- reading.cancel
            // Registered while it lingers — that IS the point of the window —
            // and gone once it closes.
            _ <- (IO.sleep(10.millis) *> sessions.get(conn))
              .iterateWhile(_.isDefined)
          } yield ()
        }
    } yield out).timeout(30.seconds).void
  }

  test("a document nobody connects to does not leak a session") {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(Renderer.create(liveLeafDash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
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
            // Present the moment the document is served — a stream opening a
            // beat later must find it.
            before <- sessions.get(conn)
            // ...and gone once the window passes with nobody adopting it,
            // because every live session is read on every state batch.
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
        // An outer morph: it targets the id inside its own HTML and names no
        // selector — the leaf's whole rendering, cell and all — and the batch
        // carries the cursor it advanced to.
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
        // The diff's whole purpose: a change that renders identically puts
        // NOTHING on the wire — not even a cursor, since only a non-empty batch
        // carries one.
        again <-
          world.change(es("sensor.a", "hot")) *> client.drain
        _ = assertEquals(domEvents(again), Nil, clue = again)
      } yield ()
    }
  }

}
