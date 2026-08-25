package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all.*
import fh.view.build.{PklDump, Site, SystemPkl}
import fh.view.model.Dashboard
import fh.view.testkit.{FakeHomeAssistant, HouseFixture, PklWorkspace}
import fh.view.testkit.TestAuth
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
        sessions,
        TestAuth.openGate
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
        assert(body.contains("edit/file/site.pkl"), clue = body)
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

  test("a failed slug names no entities, so no action from it reaches HA") {
    withLiveServer(failed) { (server, _, _, fake) =>
      for {
        resp <- server.routes.orNotFound.run(
          Request[IO](
            Method.POST,
            uri"/sse/action/dashboard/light/toggle/light.kitchen"
          )
        )
        calls <- fake.recordedCalls
      } yield {
        // An action is bounded by the entities its dashboard names (ADR 0023),
        // and a failed dashboard has no renderer and therefore names none. It
        // is refused rather than forwarded — which matters because a failed
        // dashboard is exactly the one whose page is a diagnostics dump.
        assertEquals(resp.status, Status.Forbidden)
        assertEquals(calls.map(_.service), Vector.empty, clue = calls)
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
    "reloadSite repairs a broken dashboard and breaks a live one, without restart"
  ) {
    // Objective 1 — the ServerApp.reloadSite seam drives the REAL eval path: a
    // dashboard that failed at boot recovers when its source is fixed, and a
    // live one breaks back to its error page when the source breaks. No
    // restart, no re-boot.
    stageRepairWorld.use { case (ws, fake) =>
      for {
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        // The registry records what each slug last evaluated to: "dash" is
        // seeded as broken, so the first reload is a real change.
        site <- Server.LiveSite.of(
          Map("dash" -> ref),
          Map("dash" -> (Left("seeded broken"): Either[String, Dashboard])),
          "dash"
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        refs = Map("dash" -> ref)
        // Fix the source on disk — the ref must become Ready and serve the
        // dashboard.
        _ <- IO.blocking(os.write.over(ws / Site.EntryFile, kitchenSite()))
        _ <- ServerApp.reloadSite(ws, site, imports)
        ready <- ref.get
        fixedPage <- serve(ws, fake, refs)
        // Break it again — the ref must become Failed and serve the error page.
        _ <- IO.blocking(
          os.write.over(ws / Site.EntryFile, "this is not valid pkl")
        )
        _ <- ServerApp.reloadSite(ws, site, imports)
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

  test(
    "the error page reloads via Datastar on the recover stream, not a meta-refresh"
  ) {
    withLiveServer(failed) { (server, _, _, _) =>
      for {
        resp <- server.routes.orNotFound.run(
          Request[IO](Method.GET, uri"/d/dashboard")
        )
        body <- resp.body.through(fs2.text.utf8.decode).compile.string
      } yield {
        // Recovery is Datastar's `@get` on the dedicated recover stream: the
        // module is the page's only script, and the reload is declared as a
        // `data-effect` on the `_reload` signal — no hand-rolled EventSource.
        assert(body.contains("datastar.js"), clue = body)
        assert(body.contains("sse/dashboard/dashboard/recover"), clue = body)
        assert(body.contains("data-effect"), clue = body)
        assert(
          body.contains(s"{${Server.ReloadSignal}: false}"),
          clue = body
        )
        assert(body.contains("window.location.reload()"), clue = body)
        // A real reload mechanism, not a polling meta-refresh, and no inline JS.
        assert(!body.contains("EventSource"), clue = body)
        assert(!body.contains("http-equiv"), clue = body)
      }
    }
  }

  test(
    "a recover stream is not reloaded on open under a failed slug, " +
      "but reloads when the slug recovers"
  ) {
    recoveryReload(Server.RendererState.Ready(Renderer.create(liveLeafDash)))
  }

  test(
    "a recover stream reloads when a still-failed slug's error message changes"
  ) {
    recoveryReload(
      Server.RendererState.Failed("boom: the edit is STILL broken")
    )
  }

  /** Both anti-loop tests share one shape: open the recover stream under a
    * `Failed` slug, prove the open completed via the connection marker, prove
    * nothing reload-triggering follows it, then flip the state and require
    * exactly ONE reload. The flips differ in what they mean — recovery (`Failed
    * -> Ready`) and a re-broken edit (`Failed -> Failed` with a new message the
    * page must show) — but the assertion is the same.
    */
  private def recoveryReload(flip: Server.RendererState): IO[Unit] =
    withLiveServer(failed) { (server, _, ref, _) =>
      for {
        seen <- Ref[IO].of(Vector.empty[ServerSentEvent])
        resp <- server.routes.orNotFound.run(
          Request[IO](Method.GET, uri"/sse/dashboard/dashboard/recover")
        )
        _ <- Supervisor[IO].use { supervisor =>
          supervisor.supervise(
            resp.body
              .through(ServerSentEvent.decoder[IO])
              .evalMap(e => seen.update(_ :+ e))
              .compile
              .drain
          ) *>
            // The connection marker is the stream's first element and only
            // exists once it subscribed under the CURRENT state — awaiting it
            // proves the open ran under Failed (a fixed sleep could pass
            // vacuously before the open did). What follows must be nothing
            // reload-triggering: a reload here would loop, since the page just
            // loaded. The single reload comes from the flip.
            awaitMarker(seen) *>
            assertNothing(seen) *>
            ref.set(flip) *>
            awaitReload(seen)
        }
        reloads <- seen.get
      } yield {
        val reloadEvents = reloads.filter(reloadEvent)
        // Exactly one reload, and it is the flip's — nothing preceded the flip
        // (the anti-loop half). For the still-`Failed` flip, the page SHOWS the
        // message, so a changed one must repaint it.
        assertEquals(reloadEvents.size, 1, clue = reloadEvents)
      }
    }

  test(
    "a recover stream opened under an already-recovered slug reloads immediately"
  ) {
    withLiveServer(Server.RendererState.Ready(Renderer.create(liveLeafDash))) {
      (server, _, _, _) =>
        for {
          seen <- Ref[IO].of(Vector.empty[ServerSentEvent])
          resp <- server.routes.orNotFound.run(
            Request[IO](Method.GET, uri"/sse/dashboard/dashboard/recover")
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
          // The fix landed between the page's render and this connect: the
          // transition's reload was sent to nobody, so the stream says it now.
          assert(reloadEvents.sizeIs >= 1, clue = reloadEvents)
        }
    }
  }

  test("a recover stream on an unknown slug is a 404") {
    withLiveServer(failed) { (server, _, _, _) =>
      server.routes.orNotFound
        .run(Request[IO](Method.GET, uri"/sse/dashboard/nope/recover"))
        .map(resp => assertEquals(resp.status, Status.NotFound))
    }
  }

  test("a live stream on an unknown slug is a 404, not an empty SSE") {
    // The gate lives on the stream's own single lookup, so this is the same
    // question the recover 404 asks — and the answer must be a 404, never a
    // 200 whose body ends as soon as nothing is registered (which an empty
    // `renderers` map read by the ROUTE would have produced under a stale
    // double lookup).
    withLiveServer(failed) { (server, _, _, _) =>
      server.routes.orNotFound
        .run(Request[IO](Method.GET, uri"/sse/dashboard/nope/patch"))
        .map(resp => assertEquals(resp.status, Status.NotFound))
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
    // The [[ServerApp.watchSourcesWith]] seam: the same `events -> reloadSite`
    // wiring the OS watcher drives, here fed a controlled event stream — a real
    // file edit and the WatchService event it would raise.
    stageRepairWorld.use { case (ws, _) =>
      for {
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        // The registry records what each slug last evaluated to: "dash" is
        // seeded as broken, so the first reload is a real change.
        site <- Server.LiveSite.of(
          Map("dash" -> ref),
          Map("dash" -> (Left("seeded broken"): Either[String, Dashboard])),
          "dash"
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        events <- Queue.unbounded[IO, fs2.io.file.Watcher.Event]
        watched <- Ref[IO].of(Vector.empty[fs2.io.file.Path])
        _ <- Supervisor[IO].use { supervisor =>
          supervisor.supervise(
            ServerApp
              .watchSourcesWith(
                fs2.Stream.fromQueueUnterminated(events),
                p => watched.update(_ :+ p).as(IO.unit),
                ServerApp.reloadSite(ws, site, imports),
                imports
              )
              .compile
              .drain
          ) *>
            // Fix the source, deliver the edit event: the ref must become Ready.
            IO.blocking(
              os.write.over(ws / Site.EntryFile, kitchenSite())
            ) *>
            events.offer(modified(ws / Site.EntryFile)) *>
            awaitState(ref)(_.isInstanceOf[Server.RendererState.Ready]) *>
            // The entrypoint's files joined the watch graph.
            awaitWatched(watched) *>
            // Break it again: the same pipeline flips the ref back to Failed.
            IO.blocking(
              os.write.over(ws / Site.EntryFile, "this is not valid pkl")
            ) *>
            events.offer(modified(ws / Site.EntryFile)) *>
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

  test(
    "membership follows the entrypoint: a key added serves, a key removed 404s " +
      "and stops recording"
  ) {
    // The #141 half of ADR 0021: adding or removing a dashboard is an ordinary
    // edit. The publisher assertion is the part that would silently rot — a
    // removed slug that keeps its recorder still diffs every state batch
    // forever — so it is checked through the store's subscriber count.
    stageRepairWorld.use { case (ws, fake) =>
      for {
        _ <- IO.blocking(os.write.over(ws / Site.EntryFile, kitchenSite()))
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        // The registry records what each slug last evaluated to: "dash" is
        // seeded as broken, so the first reload is a real change.
        site <- Server.LiveSite.of(
          Map("dash" -> ref),
          Map("dash" -> (Left("seeded broken"): Either[String, Dashboard])),
          "dash"
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        store <- StateStore.inMemory(
          Map("light.kitchen" -> es("light.kitchen", "on"))
        )
        sessions <- Sessions.create
        out <- Server
          .withSite(
            HomeAssistantApi.fromWs(fake),
            store,
            site,
            sessions,
            TestAuth.openGate,
            AssetCache.empty,
            fs2.concurrent.Signal.constant(true),
            SystemPkl.empty,
            None
          )
          .use { server =>
            val reload = ServerApp.reloadSite(ws, site, imports)
            for {
              // One dashboard, one recorder.
              _ <- reload
              _ <- awaitSubscribers(store, 1)
              // Add a key: it serves, and it records.
              _ <- IO.blocking(
                os.write.over(ws / Site.EntryFile, kitchenSite(secondKey))
              )
              _ <- reload
              added <- site.names
              addedPage <- page(server, "/d/second")
              _ <- awaitSubscribers(store, 2)
              // Remove it again: 404, and its recorder is gone.
              _ <- IO.blocking(
                os.write.over(ws / Site.EntryFile, kitchenSite())
              )
              _ <- reload
              removed <- site.names
              gonePage <- page(server, "/d/second")
              _ <- awaitSubscribers(store, 1)
            } yield {
              assertEquals(added, List("dash", "second"))
              assertEquals(addedPage._1, Status.Ok)
              assertEquals(removed, List("dash"))
              assertEquals(gonePage._1, Status.NotFound)
            }
          }
      } yield out
    }
  }

  test("a file dropped in becomes a dashboard, through the watcher") {
    // The glob convention's whole point — and the case watching PATHS cannot
    // see, since a new file is nobody's import yet. Two things are pinned:
    // the workspace DIRECTORY is in the watch set (so the OS watcher would
    // deliver this event at all), and a `Created` event for a `*.pkl` survives
    // the filter that keeps the regenerated lockfile from feeding the reload.
    stageRepairWorld.use { case (ws, _) =>
      for {
        _ <- IO.blocking(os.write.over(ws / Site.EntryFile, globSite))
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        site <- Server.LiveSite.of(
          Map("dash" -> ref),
          Map("dash" -> (Left("seeded broken"): Either[String, Dashboard])),
          "dash"
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        events <- Queue.unbounded[IO, fs2.io.file.Watcher.Event]
        watched <- Ref[IO].of(Vector.empty[fs2.io.file.Path])
        _ <- Supervisor[IO].use { supervisor =>
          supervisor.supervise(
            ServerApp
              .watchSourcesWith(
                fs2.Stream.fromQueueUnterminated(events),
                p => watched.update(_ :+ p).as(IO.unit),
                ServerApp.reloadSite(ws, site, imports),
                imports
              )
              .compile
              .drain
          ) *>
            // The entrypoint globs, so this file IS a dashboard the moment it
            // exists — nothing else is edited.
            IO.blocking(
              os.write.over(ws / "attic.dashboard.pkl", atticDashboard)
            ) *>
            events.offer(created(ws / "attic.dashboard.pkl")) *>
            awaitSlugs(site)(_.contains("attic"))
        }
        names <- site.names
        dirWatched <- watched.get
      } yield {
        assertEquals(names, List("attic", "dash"))
        assert(
          dirWatched.exists(_.toString == ws.toString),
          clue = s"the workspace dir is not watched: $dirWatched"
        )
      }
    }
  }

  test("the lockfile the reload itself rewrites does not trigger a reload") {
    // Watching the directory means the reload's OWN output is in scope:
    // `PklProject.deps.json` is rewritten by the evaluation a reload runs, so
    // reacting to it would feed itself. Asserted on the filter rather than by
    // waiting for a loop that would hang the suite.
    val lockfile = fs2.io.file.Path("/ws/PklProject.deps.json")
    val source = fs2.io.file.Path("/ws/kitchen.dashboard.pkl")
    val manifest = fs2.io.file.Path("/ws/PklProject")
    assert(!ServerApp.isSourceEvent(modified(os.Path(lockfile.toString))))
    assert(ServerApp.isSourceEvent(created(os.Path(source.toString))))
    assert(ServerApp.isSourceEvent(modified(os.Path(manifest.toString))))
    // Overflow names no path and MUST pass: it means events were lost, which
    // is exactly when a reload is owed.
    assert(ServerApp.isSourceEvent(fs2.io.file.Watcher.Event.Overflow(1)))
  }

  test("a reload that changes nothing does not touch the registry") {
    // The watcher fires on anything in the workspace — a touched file, an edit
    // to a sibling, a created one. Writing a `Ready` state anyway would rotate
    // the slug's fragment log and repaint every open browser, so an unchanged
    // dashboard must not be re-installed. Observed through the state's own
    // identity: same value, same object, nothing emitted.
    stageRepairWorld.use { case (ws, _) =>
      for {
        _ <- IO.blocking(os.write.over(ws / Site.EntryFile, kitchenSite()))
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        site <- Server.LiveSite.of(
          Map("dash" -> ref),
          Map("dash" -> (Left("seeded broken"): Either[String, Dashboard])),
          "dash"
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        reload = ServerApp.reloadSite(ws, site, imports)
        _ <- reload
        built <- ref.get
        // Same sources, no edit: the second reload evaluates to the same
        // dashboard and must leave the state object alone.
        _ <- reload
        again <- ref.get
        // Adding ANOTHER dashboard does not touch this one either: the
        // comparison is per slug, so one author's edit repaints one dashboard.
        _ <- IO.blocking(
          os.write.over(ws / Site.EntryFile, kitchenSite(secondKey))
        )
        _ <- reload
        afterAdd <- ref.get
        names <- site.names
        // An edit to THIS dashboard does land.
        _ <- IO.blocking(
          os.write.over(ws / Site.EntryFile, kitchenSite(title = "Renamed"))
        )
        _ <- reload
        afterEdit <- ref.get
      } yield {
        assert(built.isInstanceOf[Server.RendererState.Ready], clue = built)
        assert(again eq built, clue = "an unchanged reload replaced the state")
        assert(afterAdd eq built, clue = "adding a sibling repainted this one")
        assertEquals(names, List("dash", "second"))
        assert(!(afterEdit eq built), clue = "an edit did not re-install")
      }
    }
  }

  test("the default slug falls back when the site drops it") {
    stageRepairWorld.use { case (ws, _) =>
      for {
        _ <- IO.blocking(
          os.write.over(ws / Site.EntryFile, kitchenSite(secondKey, "second"))
        )
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        // The registry records what each slug last evaluated to: "dash" is
        // seeded as broken, so the first reload is a real change.
        site <- Server.LiveSite.of(
          Map("dash" -> ref),
          Map("dash" -> (Left("seeded broken"): Either[String, Dashboard])),
          "dash"
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        _ <- ServerApp.reloadSite(ws, site, imports)
        chosen <- site.defaultSlug
        // Drop the dashboard the site asked for: `/` must still answer.
        _ <- IO.blocking(os.write.over(ws / Site.EntryFile, kitchenSite()))
        _ <- ServerApp.reloadSite(ws, site, imports)
        fallback <- site.defaultSlug
      } yield {
        assertEquals(chosen, "second")
        assertEquals(fallback, "dash")
      }
    }
  }

  test("a reload never reclaims a PUSHED slug, and never drops one it kept") {
    // ADR 0010's rule, checked against the registry rather than against a
    // caller's memory of what the site used to own: a slug the developer pushed
    // is not in any entrypoint, so every reload sees it as unnamed — and must
    // still leave it serving.
    stageRepairWorld.use { case (ws, _) =>
      for {
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        site <- Server.LiveSite.of(
          Map("dash" -> ref),
          Map("dash" -> (Left("seeded broken"): Either[String, Dashboard])),
          "dash"
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        _ <- site.installPushed(
          "preview",
          Server.RendererState.Ready(Renderer.create(liveLeafDash))
        )
        // Two reloads of a site that names only "dash": the first also drops
        // "second", so the removal path definitely ran.
        _ <- IO.blocking(
          os.write.over(ws / Site.EntryFile, kitchenSite(secondKey, "second"))
        )
        _ <- ServerApp.reloadSite(ws, site, imports)
        withSecond <- site.names
        _ <- IO.blocking(os.write.over(ws / Site.EntryFile, kitchenSite()))
        _ <- ServerApp.reloadSite(ws, site, imports)
        after <- site.names
        preview <- site.liveFor("preview").flatMap(_.get.renderer.get)
      } yield {
        assertEquals(withSecond, List("dash", "preview", "second"))
        assertEquals(after, List("dash", "preview"))
        assert(
          preview.isInstanceOf[Server.RendererState.Ready],
          clue = preview
        )
      }
    }
  }

  test("planSite: what changes, what is left alone, what is reclaimed") {
    // The reload's whole decision, without a server: the registry's record of
    // where each slug came from is the only input besides the new site.
    val dash = liveLeafDash
    def validated(d: Dashboard) = Right(Dashboard.Validated(d, Map.empty))
    val renamed = dash.copy(title = Some("Renamed"))
    for {
      live <- Server.LiveSlug.of(Server.RendererState.Failed("x"))
      current = Map(
        "same" -> Server.Entry(live, Server.Origin.FromSite(Right(dash))),
        "edited" -> Server.Entry(live, Server.Origin.FromSite(Right(dash))),
        "broken" -> Server.Entry(live, Server.Origin.FromSite(Right(dash))),
        "fixed" -> Server.Entry(live, Server.Origin.FromSite(Left("was bad"))),
        "dropped" -> Server.Entry(live, Server.Origin.FromSite(Right(dash))),
        "pushed" -> Server.Entry(live, Server.Origin.Pushed)
      )
      plan = Server.planSite(
        current,
        List(
          "same" -> validated(dash),
          "edited" -> validated(renamed),
          "broken" -> Left("it broke"),
          "fixed" -> validated(dash),
          "added" -> validated(dash)
        )
      )
    } yield {
      // An unchanged dashboard is not re-installed: installing rotates the
      // fragment log and repaints every open browser.
      assertEquals(
        plan.installs.map(_._1),
        List("added", "broken", "edited", "fixed")
      )
      assertEquals(
        plan.installs.map(_._3),
        List(
          Server.Change.Added("added", None),
          Server.Change.Broke("broken", "it broke"),
          Server.Change.Rebuilt("edited"),
          Server.Change.Recovered("fixed")
        )
      )
      // Only a slug the ENTRYPOINT owned is reclaimed.
      assertEquals(plan.removals, Set("dropped"))
    }
  }

  /** A real package-form workspace (lib + the fixture house seeded as the
    * `@fh-home` dump) whose entrypoint starts BROKEN — the same staging the
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
        os.write.over(tmp / Site.EntryFile, "this is not valid pkl")
      }.toResource
      fake <- FakeHomeAssistant.create(Nil).toResource
    } yield (tmp, fake)

  private def awaitSubscribers(store: StateStore, n: Int): IO[Unit] =
    store.changeSubscribers
      .filter(_ == n)
      .head
      .compile
      .drain
      .timeout(15.seconds)

  private def page(server: Server, path: String): IO[(Status, String)] =
    server.routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
      .flatMap(resp =>
        resp.body
          .through(fs2.text.utf8.decode)
          .compile
          .string
          .map(resp.status -> _)
      )

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
        sessions,
        TestAuth.openGate
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

  /** A valid entrypoint whose `dash` dashboard is pinned to the fixture
    * `light_kitchen` — it builds only while the fixture house is the seeded
    * dump, exactly like DumpRefreshSuite's. `extra` adds further keys and
    * `default` the site's preferred slug.
    */
  private def kitchenSite(
      extra: String = "",
      default: String = "",
      title: String = "Kitchen"
  ) =
    s"""amends "@fh-dashboard/site.pkl"
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |${if (default.isEmpty) "" else s"""default = "$default""""}
       |dashboards {
       |  ["dash"] {
       |    title = "$title"
       |    card = (c.grid) {
       |      children {
       |        c.title(dump.entities.light_kitchen.entity_id)
       |      }
       |    }
       |  }
       |$extra
       |}
       |""".stripMargin

  /** The same site, naming its dashboards by the `*.dashboard.pkl` convention
    * instead of one key each — pkl's glob import, resolved on every evaluation,
    * which is what makes a file appearing a dashboard appearing.
    */
  private def globSite =
    s"""amends "@fh-dashboard/site.pkl"
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |dashboards {
       |  ["dash"] { card = c.title(dump.entities.light_kitchen.entity_id) }
       |  for (path, dash in import*("*.dashboard.pkl")) {
       |    [path.replaceAll(".dashboard.pkl", "")] = dash
       |  }
       |}
       |""".stripMargin

  private val atticDashboard =
    """amends "@fh-dashboard/entry.pkl"
      |import "@fh-dashboard/components.pkl" as c
      |title = "Attic"
      |card = c.title("attic")
      |""".stripMargin

  private def awaitSlugs(site: Server.LiveSite)(
      pred: List[String] => Boolean
  ): IO[Unit] =
    fs2.Stream
      .repeatEval(site.names <* IO.sleep(10.millis))
      .find(pred)
      .compile
      .drain
      .timeout(15.seconds)

  private def created(p: os.Path): fs2.io.file.Watcher.Event =
    fs2.io.file.Watcher.Event.Created(
      fs2.io.file.Path.fromNioPath(p.toNIO),
      1
    )

  private val secondKey =
    """  ["second"] {
      |    title = "Second"
      |    card = c.title("second")
      |  }""".stripMargin

  private def awaitReload(
      seen: Ref[IO, Vector[ServerSentEvent]]
  ): IO[Unit] =
    fs2.Stream
      .repeatEval(seen.get <* IO.sleep(10.millis))
      .find(_.exists(reloadEvent))
      .compile
      .drain
      .timeout(15.seconds)

  /** The recover stream's first element, sent once it has subscribed under the
    * connection's own state. It is [[Server.recoverOpenMarker]] — a marker
    * COMMENT the browser's EventSource drops before Datastar — so awaiting it
    * proves the open completed without asserting anything reload-triggering.
    */
  private def awaitMarker(
      seen: Ref[IO, Vector[ServerSentEvent]]
  ): IO[Unit] =
    fs2.Stream
      .repeatEval(seen.get <* IO.sleep(10.millis))
      .find(_.exists(isMarker))
      .compile
      .drain
      .timeout(15.seconds)

  private def isMarker(e: ServerSentEvent): Boolean =
    e.comment == Server.recoverOpenMarker.comment

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
      .find(_.exists(_.toString.endsWith(Site.EntryFile)))
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
