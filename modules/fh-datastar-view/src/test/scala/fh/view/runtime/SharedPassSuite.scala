package fh.view.runtime

import fh.view.query.QuerySnapshot
import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.syntax.all.*
import fh.view.model.{CardDef, Dashboard, LayoutNode, Region, SlotSource}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** What the per-slug recorder does with one frame, and what it costs: N viewers
  * cost one render of each changed node, a number, so these count renders.
  */
class SharedPassSuite extends ServerHarness {

  // One entity changes during the connect handshake and never again, so nothing
  // later heals it; the other is a barrier proving the connection is live.
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
    // The digest kept must be of the bytes that went out, and the position the
    // version they were rendered at.
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
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
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
                  // Both subscriptions: the recorder's to the store and this
                  // connection's. Waiting only for the latter loses the update
                  // under load.
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
                  // The position advances after the items are recorded; waiting
                  // on `holds` would race it.
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
      // The repaint claimed everything it painted, and the tick re-claimed the
      // one node that moved at its new bytes: a record of what this connection
      // was sent.
      assertEquals(
        held.get("c_0"),
        renderer
          .renderNodeById("c_0", states, fragments = QuerySnapshot.empty)
          .map(Held.of),
        clue = held
      )
      // So the claim above is not "everything, re-derived".
      assertEquals(
        held.get("c_1"),
        renderer
          .renderNodeById("c_1", states, fragments = QuerySnapshot.empty)
          .map(Held.of),
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
    // `routes.run` computes the opening and returns before the body is pulled.
    // A change in that window must still arrive, or the client shows a
    // pre-connect value with nothing to say so.
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
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          for {
            _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
            resp <- server.routes.orNotFound
              .run(Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch"))
            _ <- store.update(EntityState("sensor.a", missed, Map.empty))
            // The record proves the frame was written before the body was
            // pulled, rather than the test racing a slow recorder.
            live <- server.liveSlug("dashboard")
            _ <- (IO.sleep(5.millis) *> live.doorbell.get).iterateUntil(_ >= 1)
            seen <- Ref[IO].of("")
            // Pulling the body registers the subscription.
            reader <- resp.body
              .through(fs2.text.utf8.decode)
              .evalMap(chunk => seen.updateAndGet(_ + chunk))
              .exists(_.contains(barrier))
              .compile
              .drain
              .start
            _ <- server.connectedSessions.filter(_ >= 1).head.compile.drain
            _ <- store.update(EntityState("sensor.b", barrier, Map.empty))
            _ <- reader.joinWithNever
            text <- seen.get
          } yield text
        }
    } yield text
    io.timeout(30.seconds)
      .map(text => assert(text.contains(missed), clue = text))
  }

  test("a connection that stops reading cannot stall the store") {
    // `Topic.publish1` blocks on a full subscriber, so a bounded per-connection
    // subscription would let one stalled browser freeze the HA feed for
    // everyone.
    val io = for {
      store <- StateStore.inMemory(
        Map("sensor.a" -> EntityState("sensor.a", "a0", Map.empty))
      )
      // A per-session node (the tabs host bakes the client's panel), so the
      // per-connection pass really emits.
      ref <- SignallingRef[IO].of(Server.RendererState.Ready(tabsRenderer))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      _ <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
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
              // The connection subscribes only once the body is being pulled.
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
                    _ <- store.changeSubscribers
                      .filter(_ >= 1)
                      .head
                      .compile
                      .drain
                    _ <- stop.complete(())
                    // The reader stalls only on the next event, and the
                    // keepalive is too slow.
                    _ <- store.update(
                      EntityState("sensor.a", "engage-the-stall", Map.empty)
                    )
                    _ <- IO.sleep(1.second)
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

  /** '''One render, not one per viewer.''' Each session pulls on its own; the
    * sharing is the per-slug [[RenderCache]], where whoever arrives first
    * renders and the other waits on the slot. A 2 here means the cache is being
    * missed, a key varying per viewer or a pull rendering outside it.
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
      // The patch path never calls HA; an unexpected registry call still
      // raises.
      fake <- FakeHomeAssistant.create(Nil)
      // The render count is entirely the shared pass's doing.
      _ <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          val connect = server.routes.orNotFound
            .run(Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch"))
          // Opened on this connection's cursor: a session is adopted before its
          // opening block, so a change emitted on a count alone can land in the
          // opening repaint, which counts nothing here.
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
            _ <- opened1.get
            _ <- opened2.get
            _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
            _ <- store.update(EntityState("sensor.a", marker, Map.empty))
            _ <- seen1.joinWithNever
            _ <- seen2.joinWithNever
          } yield ()
        }
    } yield count.get()
    io.timeout(30.seconds).assertEquals(1)
  }

}
