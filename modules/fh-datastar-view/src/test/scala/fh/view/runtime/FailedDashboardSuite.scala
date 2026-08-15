package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{IO, Ref}
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fh.view.testkit.FakeHomeAssistant
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** Objective 4 of the failed-dashboard plan — the `Failed` semantics on the
  * hot paths: the error page (HTML, self-contained), the non-HTML consumers
  * seeing a failed slug as absent (`nodeDebug` 404s, actions are unchanged),
  * and a live connection being told to RELOAD when its slug breaks — and when
  * it recovers.
  */
class FailedDashboardSuite extends ServerHarness {

  private val boom = "boom: sensor.a exploded"

  private val failed = Server.RendererState.Failed(boom)

  /** A live [[Server]] holding ONE slug whose state the test controls — `use`
    * can flip the ref between Ready and Failed and watch the wire.
    */
  private def withLiveServer(
      state: Server.RendererState
  )(use: (
      Server,
      StateStore,
      SignallingRef[IO, Server.RendererState],
      FakeHomeAssistant
  ) => IO[Unit]): IO[Unit] =
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "a0"))).toResource
      ref <- SignallingRef[IO].of(state).toResource
      sessions <- Sessions.create.toResource
      fake <- FakeHomeAssistant.create(Nil).toResource
      server <- Server.resource(
        HomeAssistantApi.fromWs(fake),
        store,
        Map("dashboard" -> ref),
        "dashboard",
        sessions
      )
    } yield (server, store, ref, fake)).use { case (server, store, ref, fake) =>
      use(server, store, ref, fake)
    }

  test("a failed slug serves a text/html error page naming slug and message") {
    withLiveServer(failed) { (server, _, _, _) =>
      for {
        resp <- server.routes.orNotFound.run(
          Request[IO](Method.GET, uri"/d/dashboard")
        )
        body <- resp.body.through(fs2.text.utf8.decode).compile.string
      } yield {
        assertEquals(resp.status, Status.Ok)
        assertEquals(
          resp.headers.get[`Content-Type`].map(_.mediaType),
          Some(MediaType.text.html)
        )
        assert(
          body.contains("Dashboard dashboard failed to build"),
          clue = body
        )
        assert(body.contains(htmlEscape(boom)), clue = body)
        // The fix path: the editor link to this slug's source.
        assert(body.contains("edit/file/dashboard.pkl"), clue = body)
      }
    }
  }

  test("nodeDebug sees a failed slug as absent, like an unknown one") {
    withLiveServer(failed) { (server, _, _, _) =>
      server.routes.orNotFound
        .run(Request[IO](Method.GET, uri"/edit/node/dashboard/c_0/debug"))
        .map(resp => assertEquals(resp.status, Status.NotFound))
    }
  }

  test("action POSTs on a failed slug behave exactly as on an unknown one") {
    withLiveServer(failed) { (server, _, _, fake) =>
      for {
        resp <- server.routes.orNotFound.run(
          Request[IO](Method.POST, uri"/sse/action/light/toggle/light.kitchen")
        )
        calls <- fake.recordedCalls
      } yield {
        // The action route is not slug-scoped today: it drives HA directly, and
        // a failed slug is handled exactly like an unknown one — the service is
        // still called and NoContent returned. No Failed-specific seam.
        assertEquals(resp.status, Status.NoContent)
        assertEquals(calls.map(_.service), Vector("toggle"), clue = calls)
      }
    }
  }

  test("a live connection is told to reload when its slug breaks, and recovers") {
    withLiveServer(Server.RendererState.Ready(Renderer.create(liveLeafDash))) {
      (server, _, ref, _) =>
        for {
          seen <- Ref[IO].of(Vector.empty[ServerSentEvent])
          resp <- server.routes.orNotFound.run(
            Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch")
          )
          _ <- Supervisor[IO].use { supervisor =>
            supervisor.supervise(
              resp.body
                .through(ServerSentEvent.decoder[IO])
                .evalMap(e => seen.update(_ :+ e))
                .compile
                .drain
            ) *>
              // The opening block has ended (cursor handshake delivered) before
              // anything is flipped — otherwise the break could land in it.
              fs2.Stream
                .repeatEval(seen.get <* IO.sleep(10.millis))
                .find(_.exists(isCursor))
                .compile
                .drain
                .timeout(15.seconds) *>
              // Break it: the connected session must be sent a reload, since the
              // error document has no #dashboard to patch and no head to patch.
              ref.set(failed) *> awaitReload(seen) *>
              // Recover it: the same reload, from the error page back to the
              // dashboard.
              ref.set(Server.RendererState.Ready(Renderer.create(liveLeafDash))) *>
              awaitReload(seen)
          }
          reloads <- seen.get
        } yield {
          val reloadEvents = reloads.filter(reloadEvent)
          assert(reloadEvents.sizeIs >= 2, clue = reloadEvents)
        }
    }
  }

  private def awaitReload(
      seen: Ref[IO, Vector[ServerSentEvent]]
  ): IO[Unit] =
    fs2.Stream
      .repeatEval(seen.get <* IO.sleep(10.millis))
      .find(_.exists(reloadEvent))
      .compile
      .drain
      .timeout(15.seconds)

  private def reloadEvent(e: ServerSentEvent): Boolean =
    e.signals.exists(_.contains(s""""${Server.ReloadSignal}":true"""))

  private def htmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
