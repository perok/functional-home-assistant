package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all.*
import fh.view.build.PklDump
import fh.view.testkit.{FakeHomeAssistant, HouseFixture, PklWorkspace}
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** Objective 4 of the failed-dashboard plan — the `Failed` semantics on the hot
  * paths: the error page (HTML, self-contained), the non-HTML consumers seeing
  * a failed slug as absent (`nodeDebug` 404s, actions are unchanged), and a
  * live connection being told to RELOAD when its slug breaks — and when it
  * recovers.
  */
class FailedDashboardSuite extends ServerHarness {

  private val boom = "boom: sensor.a exploded"

  private val failed = Server.RendererState.Failed(boom)

  /** A live [[Server]] holding ONE slug whose state the test controls — `use`
    * can flip the ref between Ready and Failed and watch the wire.
    */
  private def withLiveServer(
      state: Server.RendererState
  )(
      use: (
          Server,
          StateStore,
          SignallingRef[IO, Server.RendererState],
          FakeHomeAssistant
      ) => IO[Unit]
  ): IO[Unit] =
    (for {
      store <- StateStore
        .inMemory(Map("sensor.a" -> es("sensor.a", "a0")))
        .toResource
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

  test(
    "a live connection is told to reload when its slug breaks, and recovers"
  ) {
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
              ref.set(
                Server.RendererState.Ready(Renderer.create(liveLeafDash))
              ) *>
              awaitReload(seen)
          }
          reloads <- seen.get
        } yield {
          val reloadEvents = reloads.filter(reloadEvent)
          assert(reloadEvents.sizeIs >= 2, clue = reloadEvents)
        }
    }
  }

  test(
    "reloadEntries repairs a broken dashboard and breaks a live one, without restart"
  ) {
    // Objective 1 — the widened ServerApp.reloadEntries seam drives the REAL
    // eval path: a dashboard that failed at boot recovers when its source is
    // fixed, and a live one breaks back to its error page when the source
    // breaks. No restart, no re-boot.
    stageRepairWorld.use { case (ws, fake) =>
      for {
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        refs = Map("dash" -> ref)
        entries = List("dash" -> "dash.pkl")
        // Fix the source on disk — the ref must become Ready and serve the
        // dashboard.
        _ <- IO.blocking(os.write.over(ws / "dash.pkl", kitchenEntry))
        _ <- ServerApp.reloadEntries(ws, entries, refs, imports)
        ready <- ref.get
        fixedPage <- serve(ws, fake, refs)
        // Break it again — the ref must become Failed and serve the error page.
        _ <- IO.blocking(
          os.write.over(ws / "dash.pkl", "this is not valid pkl")
        )
        _ <- ServerApp.reloadEntries(ws, entries, refs, imports)
        broken <- ref.get
        brokenPage <- serve(ws, fake, refs)
      } yield {
        assert(ready.isInstanceOf[Server.RendererState.Ready], clue = ready)
        assert(fixedPage._1 == Status.Ok, clue = fixedPage)
        assert(fixedPage._2.contains("light.kitchen"), clue = fixedPage._2)
        assert(broken.isInstanceOf[Server.RendererState.Failed], clue = broken)
        assertEquals(brokenPage._1, Status.Ok)
        assert(brokenPage._2.contains("failed to build"), clue = brokenPage._2)
        // The old dashboard is gone from the wire: no stale title.
        assert(!brokenPage._2.contains("light.kitchen"), clue = brokenPage._2)
      }
    }
  }

  test("the error page carries an SSE auto-reload, not a meta-refresh") {
    withLiveServer(failed) { (server, _, _, _) =>
      for {
        resp <- server.routes.orNotFound.run(
          Request[IO](Method.GET, uri"/d/dashboard")
        )
        body <- resp.body.through(fs2.text.utf8.decode).compile.string
      } yield {
        // The page opens this slug's SSE stream (marked [[Server.ErrorPageParam]])
        // and reloads on the `_reload` signal. The slug itself is joined at
        // runtime, so the URL is asserted as its two literal halves.
        assert(body.contains("sse/dashboard/"), clue = body)
        assert(
          body.contains(s"/patch?${Server.ErrorPageParam}=1"),
          clue = body
        )
        assert(body.contains("datastar-patch-signals"), clue = body)
        assert(body.contains(s""""${Server.ReloadSignal}":true"""), clue = body)
        // A real reload mechanism, not a polling meta-refresh.
        assert(!body.contains("http-equiv"), clue = body)
      }
    }
  }

  test(
    "an error-page SSE connection is not reloaded on open under a failed slug, " +
      "but reloads when the slug recovers"
  ) {
    withLiveServer(failed) { (server, _, ref, _) =>
      for {
        seen <- Ref[IO].of(Vector.empty[ServerSentEvent])
        resp <- server.routes.orNotFound.run(
          Request[IO](
            Method.GET,
            uri"/sse/dashboard/dashboard/patch?error-page=1"
          )
        )
        _ <- Supervisor[IO].use { supervisor =>
          supervisor.supervise(
            resp.body
              .through(ServerSentEvent.decoder[IO])
              .evalMap(e => seen.update(_ :+ e))
              .compile
              .drain
          ) *>
            // The opening under Failed must send nothing: a reload here would
            // loop, because the page just loaded. The recovery reload comes
            // from the Failed -> Ready transition instead.
            IO.sleep(500.millis) *>
            assertNothing(seen) *>
            ref.set(
              Server.RendererState.Ready(Renderer.create(liveLeafDash))
            ) *>
            awaitReload(seen)
        }
        reloads <- seen.get
      } yield {
        val reloadEvents = reloads.filter(reloadEvent)
        assert(reloadEvents.sizeIs >= 1, clue = reloadEvents)
      }
    }
  }

  test(
    "a bookmarked SSE URL on a failed slug is still answered with a reload"
  ) {
    withLiveServer(failed) { (server, _, _, _) =>
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
            awaitReload(seen)
        }
        reloads <- seen.get
      } yield {
        val reloadEvents = reloads.filter(reloadEvent)
        assert(reloadEvents.sizeIs >= 1, clue = reloadEvents)
      }
    }
  }

  test(
    "the source watcher pipeline repairs a broken dashboard and breaks it again, " +
      "driven without a live OS watcher"
  ) {
    // The [[ServerApp.watchSourcesWith]] seam: the same `events -> reloadEntries`
    // wiring the OS watcher drives, here fed a controlled event stream — a real
    // file edit and the WatchService event it would raise.
    stageRepairWorld.use { case (ws, _) =>
      for {
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        refs = Map("dash" -> ref)
        entries = List("dash" -> "dash.pkl")
        events <- Queue.unbounded[IO, fs2.io.file.Watcher.Event]
        watched <- Ref[IO].of(Vector.empty[fs2.io.file.Path])
        _ <- Supervisor[IO].use { supervisor =>
          supervisor.supervise(
            ServerApp
              .watchSourcesWith(
                fs2.Stream.fromQueueUnterminated(events),
                p => watched.update(_ :+ p).as(IO.unit),
                ws,
                entries,
                refs,
                imports
              )
              .compile
              .drain
          ) *>
            // Fix the source, deliver the edit event: the ref must become Ready.
            IO.blocking(os.write.over(ws / "dash.pkl", kitchenEntry)) *>
            events.offer(modified(ws / "dash.pkl")) *>
            awaitState(ref)(_.isInstanceOf[Server.RendererState.Ready]) *>
            // The fixed entry's files joined the watch graph.
            awaitWatched(watched) *>
            // Break it again: the same pipeline flips the ref back to Failed.
            IO.blocking(
              os.write.over(ws / "dash.pkl", "this is not valid pkl")
            ) *>
            events.offer(modified(ws / "dash.pkl")) *>
            awaitState(ref)(_.isInstanceOf[Server.RendererState.Failed])
        }
        finalState <- ref.get
      } yield {
        assert(
          finalState.isInstanceOf[Server.RendererState.Failed],
          clue = finalState
        )
      }
    }
  }

  /** A real package-form workspace (lib + the fixture house seeded as the
    * `@fh-home` dump) whose `dash.pkl` starts BROKEN — the same staging the
    * production boot produces for a bad user edit, minus the boot itself.
    */
  private def stageRepairWorld: Resource[IO, (os.Path, FakeHomeAssistant)] =
    for {
      tmp <- IO.blocking(os.temp.dir(prefix = "fh-repair")).toResource
      _ <- IO.blocking {
        val _ =
          PklWorkspace.bootstrap(
            tmp,
            PklDump.render(HouseFixture.transformedDump)
          )
        os.list(tmp)
          .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
          .foreach(os.remove)
        os.write.over(tmp / "dash.pkl", "this is not valid pkl")
      }.toResource
      fake <- FakeHomeAssistant.create(Nil).toResource
    } yield (tmp, fake)

  /** Serve `GET /d/dash` through a real Server holding `refs` — what a client
    * sees after the ref moved.
    */
  private def serve(
      ws: os.Path,
      fake: FakeHomeAssistant,
      refs: Map[String, SignallingRef[IO, Server.RendererState]]
  ): IO[(Status, String)] =
    (for {
      store <- StateStore
        .inMemory(Map("light.kitchen" -> es("light.kitchen", "on")))
        .toResource
      sessions <- Sessions.create.toResource
      server <- Server.resource(
        HomeAssistantApi.fromWs(fake),
        store,
        refs,
        "dash",
        sessions
      )
    } yield server).use { server =>
      server.routes.orNotFound
        .run(Request[IO](Method.GET, uri"/d/dash"))
        .flatMap(resp =>
          resp.body
            .through(fs2.text.utf8.decode)
            .compile
            .string
            .map(resp.status -> _)
        )
    }

  /** A valid entry pinned to the fixture `light_kitchen` — builds only while
    * the fixture house is the seeded dump, exactly like DumpRefreshSuite's.
    */
  private val kitchenEntry =
    """amends "@fh-dashboard/entry.pkl"
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-home/dump.pkl" as dump
      |title = "Kitchen"
      |card = (c.grid) {
      |  children {
      |    c.title(dump.entities.light_kitchen.entity_id)
      |  }
      |}
      |""".stripMargin

  private def awaitReload(
      seen: Ref[IO, Vector[ServerSentEvent]]
  ): IO[Unit] =
    fs2.Stream
      .repeatEval(seen.get <* IO.sleep(10.millis))
      .find(_.exists(reloadEvent))
      .compile
      .drain
      .timeout(15.seconds)

  /** The negative half of an SSE-opening test: nothing reload-triggering may
    * arrive in a window that would cover any immediate-reload bug.
    */
  private def assertNothing(
      seen: Ref[IO, Vector[ServerSentEvent]]
  ): IO[Unit] =
    seen.get.map(events => assert(!events.exists(reloadEvent), clue = events))

  private def awaitState(
      ref: SignallingRef[IO, Server.RendererState]
  )(pred: Server.RendererState => Boolean): IO[Unit] =
    fs2.Stream
      .repeatEval(ref.get <* IO.sleep(10.millis))
      .find(pred)
      .compile
      .drain
      .timeout(15.seconds)

  private def awaitWatched(
      watched: Ref[IO, Vector[fs2.io.file.Path]]
  ): IO[Unit] =
    fs2.Stream
      .repeatEval(watched.get <* IO.sleep(10.millis))
      .find(_.exists(_.toString.endsWith("dash.pkl")))
      .compile
      .drain
      .timeout(15.seconds)

  private def modified(p: os.Path): fs2.io.file.Watcher.Event =
    fs2.io.file.Watcher.Event.Modified(
      fs2.io.file.Path.fromNioPath(p.toNIO),
      1
    )

  private def reloadEvent(e: ServerSentEvent): Boolean =
    e.signals.exists(_.contains(s""""${Server.ReloadSignal}":true"""))

  private def htmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
