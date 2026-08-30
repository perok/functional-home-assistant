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
  Region,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.headers.{`Cache-Control`, `If-None-Match`, ETag}
import org.http4s.implicits.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** What the per-slug recorder does with one frame, and what it costs.
  *
  * The contract is that N viewers of one dashboard cost one render of each
  * changed node — a number, not a shape, which is why these tests count renders
  * rather than asserting on bytes.
  */
class SharedPassSuite extends ServerHarness {

  // ---------------------------------------------------------------------------
  // Shared per-slug patch fan-out
  // ---------------------------------------------------------------------------

  /** Counts every live-patch render, so the test can assert a fragment was
    * produced ONCE for N viewers.
    */

  // Two live leaves: one entity can change during the connect handshake and
  // never again (so nothing later heals it), while the other provides an
  // ordering barrier that proves the connection is live before we look.
  private def twoLeafDash = Dashboard(
    cards = Map(
      "col" -> CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        List("sensor.a", "sensor.b").map(e =>
          LayoutNode.Component(
            "card",
            slots = Map("state" -> SlotSource(Some(e)))
          )
        )*
      )
    )
  )

  test("a session records what its own connection was actually sent") {
    // The per-session record is written but not yet read — the shared log still
    // decides — so nothing else in the suite would notice it drifting from what
    // the client holds. This is the check that it does not: the digest it keeps
    // must be the digest of the bytes that went out, and the position must be
    // the version they were rendered at.
    val io = for {
      store <- StateStore.inMemory(
        Map(
          "sensor.a" -> EntityState("sensor.a", "a0", Map.empty),
          "sensor.b" -> EntityState("sensor.b", "b0", Map.empty)
        )
      )
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(twoLeafDash))
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
          server.routes.orNotFound
            .run(Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch"))
            .flatMap { resp =>
              resp.body.compile.drain.background.surround {
                for {
                  // BOTH subscriptions, and they are different ones: the
                  // publisher's to the STORE, and this connection's to the
                  // topic. Waiting only for the latter passes when the suite
                  // runs alone and loses the update to a not-yet-subscribed
                  // publisher when it runs under load.
                  _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
                  _ <- server.connectedSessions
                    .filter(_ >= 1)
                    .head
                    .compile
                    .drain
                  _ <- store.update(EntityState("sensor.a", "a1", Map.empty))
                  session <- (IO.sleep(5.millis) *>
                    sessions.forSlug("dashboard").map(_.headOption))
                    .iterateUntil(_.isDefined)
                    .map(_.get)
                  // The position is advanced AFTER the batch's items are
                  // recorded, so waiting on it means both are settled — waiting
                  // on `holds` instead would race the position's write.
                  at <- (IO.sleep(5.millis) *> session.position.get)
                    .iterateUntil(_ > 0)
                  held <- session.holds.get
                  now <- stateAndRenderer(store, ref)
                } yield (held, at, now)
              }
            }
        }
    } yield out
    io.timeout(30.seconds).map { case (held, at, (version, renderer, states)) =>
      // The repaint that opened this connection claimed everything it painted,
      // and the tick then re-claimed the ONE node that moved — at its new
      // bytes, which is the point: a record of what THIS connection was sent,
      // not of what the dashboard looks like.
      assertEquals(
        held.get("c_0"),
        renderer.renderNodeById("c_0", states).map(Held.of),
        clue = held
      )
      // The untouched sibling still carries what the repaint gave it, so the
      // claim above is not simply "everything, re-derived".
      assertEquals(
        held.get("c_1"),
        renderer.renderNodeById("c_1", states).map(Held.of),
        clue = held
      )
      assertEquals(at, version)
    }
  }

  private def stateAndRenderer(
      store: StateStore,
      ref: SignallingRef[IO, Server.RendererState]
  ): IO[(Long, Renderer, Map[String, EntityState])] =
    (store.current, ref.get)
      .mapN((s, r) => (s.version, r.rendererOf.get, s.entities))

  test(
    "a change published during the connect handshake still reaches the connection"
  ) {
    // The handshake window: `routes.run` computes the opening patches (reading
    // the snapshot and the log) and returns, but the response body has not been
    // pulled yet — so anything published before the stream's subscription is
    // registered reaches this connection never. It heals on the NEXT reconnect
    // (the cursor stays put and `since` is inclusive), but until then the client
    // shows a pre-connect value with nothing to indicate it.
    val missed = "gap_value_xq"
    val barrier = "barrier_value_xq"
    val renders = new AtomicInteger(0)
    val io = for {
      store <- StateStore.inMemory(
        Map(
          "sensor.a" -> EntityState("sensor.a", "a0", Map.empty),
          "sensor.b" -> EntityState("sensor.b", "b0", Map.empty)
        )
      )
      ref <- SignallingRef[IO]
        .of(
          Server.RendererState.Ready(
            new CountingRenderer(twoLeafDash, renders): Renderer
          )
        )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      text <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          for {
            // The shared publisher is attached before anything changes.
            _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
            resp <- server.routes.orNotFound
              .run(Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch"))
            _ <- store.update(EntityState("sensor.a", missed, Map.empty))
            // Waiting for the RECORD proves the frame was written before the
            // body was pulled — the gap this test is about — rather than the
            // test racing ahead of a slow recorder. The publisher renders
            // nothing now, so the render count cannot say this any more.
            live <- server.liveSlug("dashboard")
            _ <- (IO.sleep(5.millis) *> live.doorbell.get).iterateUntil(_ >= 1)
            seen <- Ref[IO].of("")
            // Pulling the body is what registers the subscription, and only now.
            reader <- resp.body
              .through(fs2.text.utf8.decode)
              .evalMap(chunk => seen.updateAndGet(_ + chunk))
              .exists(_.contains(barrier))
              .compile
              .drain
              .start
            _ <- server.connectedSessions.filter(_ >= 1).head.compile.drain
            _ <- store.update(EntityState("sensor.b", barrier, Map.empty))
            // The barrier arrived, so everything ordered before it has too.
            _ <- reader.joinWithNever
            text <- seen.get
          } yield text
        }
    } yield text
    io.timeout(30.seconds)
      .map(text => assert(text.contains(missed), clue = text))
  }

  test("a connection that stops reading cannot stall the store") {
    // `Topic.publish1` sends to every subscriber's channel in turn and blocks
    // on a full one, so a bounded per-connection subscription would let ONE
    // stalled browser freeze the HA feed — for every dashboard and every
    // viewer, not just itself.
    val io = for {
      store <- StateStore.inMemory(
        Map("sensor.a" -> EntityState("sensor.a", "a0", Map.empty))
      )
      // A dashboard with a PER-SESSION node (a tabs host bakes the client's
      // selected panel), so the per-connection pass really emits — the case
      // that can block, unlike a page whose every node is shared.
      ref <- SignallingRef[IO].of(Server.RendererState.Ready(tabsRenderer))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      _ <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          server.routes.orNotFound
            .run(Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch"))
            .flatMap { resp =>
              // Read normally until the test says stop — the connection has to
              // be fully subscribed first, and it only subscribes once the body
              // is being pulled.
              IO.deferred[Unit].flatMap { stop =>
                val reads = resp.body
                  .evalTap(_ =>
                    stop.tryGet
                      .flatMap(s => IO.sleep(1.minute).whenA(s.isDefined))
                  )
                  .compile
                  .drain
                reads.background.surround {
                  for {
                    // The shared publisher — the only consumer of `changes`.
                    _ <- store.changeSubscribers
                      .filter(_ >= 1)
                      .head
                      .compile
                      .drain
                    _ <- stop.complete(())
                    // The reader only stalls on the next event it takes, so
                    // give it one (the keepalive is far too slow to wait for).
                    _ <- store.update(
                      EntityState("sensor.a", "engage-the-stall", Map.empty)
                    )
                    _ <- IO.sleep(1.second)
                    // Far more than a bounded subscription would hold.
                    _ <- (1 to 300).toList.traverse_(i =>
                      store.update(EntityState("sensor.a", s"v$i", Map.empty))
                    )
                  } yield ()
                }
              }
            }
        }
    } yield ()
    io.timeout(15.seconds)
  }

  /** Two connections, one change, and the render count that goes with it.
    *
    * '''One render, not one per viewer.''' Each session pulls independently and
    * renders what IT is owed, so the sharing is no longer structural — it is
    * the per-slug [[RenderCache]], which both pulls go through: whoever gets
    * there first renders and the other waits on the same slot. Two viewers of
    * one dashboard have the same [[RenderInputs]] for a node unless their
    * selections differ, which is what makes the hit the normal case rather than
    * a lucky one.
    *
    * So this number is a cost contract: if it ever reads 2 again, the cache is
    * being missed (a key that varies per viewer where it should not, or a pull
    * that renders outside it), and the fan-out is back.
    */

  test(
    "two connections both receive a changed fragment, rendered once between them"
  ) {
    val marker = "shared_once_value_xq"
    val count = new AtomicInteger(0)
    val io = for {
      store <- StateStore.inMemory(
        Map("sensor.a" -> EntityState("sensor.a", "initial", Map.empty))
      )
      renderer = new CountingRenderer(liveLeafDash, count)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(renderer: Renderer)
      )
      sessions <- Sessions.create
      // Stub HA: the SSE/patch path never calls it (an unexpected registry call
      // still raises); the store is driven in-memory, so the empty seed is inert.
      fake <- FakeHomeAssistant.create(Nil)
      // `Server.resource` runs the shared publishers for the scope's lifetime —
      // so the render count below is entirely the shared pass's doing.
      _ <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          val connect = server.routes.orNotFound
            .run(Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch"))
          // Opened when THIS connection's opening block has finished (its
          // cursor), done when it has seen the marker. Both are needed: a
          // session is adopted before its opening block runs, so a change
          // emitted on a session count alone can land in the opening REPAINT —
          // which renders the body wholesale and counts nothing here.
          val awaitMarker = (resp: Response[IO], opened: Deferred[IO, Unit]) =>
            resp.body
              .through(fs2.text.utf8.decode)
              .scan("")(_ + _)
              .evalTap(text =>
                IO.whenA(text.contains(Server.StoreVersionSignal))(
                  opened.complete(()).void
                )
              )
              .exists(_.contains(marker))
              .compile
              .drain
          for {
            resp1 <- connect
            resp2 <- connect
            opened1 <- Deferred[IO, Unit]
            opened2 <- Deferred[IO, Unit]
            seen1 <- awaitMarker(resp1, opened1).start
            seen2 <- awaitMarker(resp2, opened2).start
            // Deterministic readiness: both connections past their opening
            // block, and the ONE recorder subscribed to the store's changes.
            _ <- opened1.get
            _ <- opened2.get
            _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
            _ <- store.update(EntityState("sensor.a", marker, Map.empty))
            // (a) both SSE streams receive the changed fragment...
            _ <- seen1.joinWithNever
            _ <- seen2.joinWithNever
          } yield ()
        }
    } yield count.get()
    // ...and (b) it was rendered ONCE between them.
    io.timeout(30.seconds).assertEquals(1)
  }

}
