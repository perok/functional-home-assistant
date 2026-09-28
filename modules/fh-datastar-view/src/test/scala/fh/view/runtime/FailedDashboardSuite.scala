package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Queue, Supervisor}
import fh.view.build.{PklDump, Site, SystemPkl}
import fh.view.model.Dashboard
import fh.view.testkit.{FakeHomeAssistant, HouseFixture, PklWorkspace}
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** `Failed` on the hot paths: a self-contained error page, non-HTML consumers
  * seeing the slug as absent, and a live connection told to reload when its
  * slug breaks and when it recovers.
  */
class FailedDashboardSuite extends ServerHarness {

  // Opens documents; see [[ServerHarness.simulateTime]].
  override protected def simulateTime: Boolean = false

  private val boom = "boom: sensor.a exploded"

  private val failed = Server.RendererState.Failed(boom)

  /** `use` can flip the ref between Ready and Failed and watch the wire. */
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
        ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
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
        body <- resp.bodyText.compile.string
        calls <- fake.recordedCalls
      } yield {
        // A failed dashboard has no renderer and so names no entity (ADR 0023):
        // the action is refused, which matters because its page is a
        // diagnostics dump. The refusal is 200 with signals (ADR 0024), so the
        // message proves it.
        assertEquals(resp.status, Status.Ok)
        assert(body.contains("is not on this dashboard"), clue = body)
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
              // After the opening block, or the break could land in it.
              fs2.Stream
                .repeatEval(seen.get <* IO.sleep(10.millis))
                .find(_.exists(isCursor))
                .compile
                .drain
                .timeout(15.seconds) *>
              // The error document has no #dashboard or head to patch, so a
              // reload.
              ref.set(failed) *> awaitReloads(seen, 1) *>
              ref.set(
                Server.RendererState.Ready(Renderer.create(liveLeafDash))
              ) *>
              awaitReloads(seen, 2)
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
    // `ServerApp.reloadSite` drives the real eval path: a dashboard that failed
    // at boot recovers when fixed, and breaks back when broken, with no
    // restart.
    stageRepairWorld.use { case (ws, fake) =>
      for {
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        // Seeded as broken, so the first reload is a real change.
        site <- Server.LiveSite.of(
          Map("dash" -> ref),
          Map("dash" -> (Left("seeded broken"): Either[String, Dashboard])),
          "dash"
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        refs = Map("dash" -> ref)
        _ <- IO.blocking(os.write.over(ws / Site.EntryFile, kitchenSite()))
        _ <- ServerApp.reloadSite(ws, site, imports)
        ready <- ref.get
        fixedPage <- serve(fake, refs)
        _ <- IO.blocking(
          os.write.over(ws / Site.EntryFile, "this is not valid pkl")
        )
        _ <- ServerApp.reloadSite(ws, site, imports)
        broken <- ref.get
        brokenPage <- serve(fake, refs)
      } yield {
        assert(ready.isInstanceOf[Server.RendererState.Ready], clue = ready)
        assert(fixedPage._1 == Status.Ok, clue = fixedPage)
        assert(fixedPage._2.contains("light.kitchen"), clue = fixedPage._2)
        assert(broken.isInstanceOf[Server.RendererState.Failed], clue = broken)
        assertEquals(brokenPage._1, Status.Ok)
        assert(brokenPage._2.contains("failed to build"), clue = brokenPage._2)
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
        // Recovery is Datastar's `@get` on the recover stream, with the reload
        // a `data-effect` on `_reload`: no hand-rolled EventSource.
        assert(body.contains("datastar.js"), clue = body)
        assert(body.contains("sse/dashboard/dashboard/recover"), clue = body)
        assert(body.contains("data-effect"), clue = body)
        assert(
          body.contains(s"{${Server.ReloadSignal}: false}"),
          clue = body
        )
        assert(body.contains("window.location.reload()"), clue = body)
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

  /** Open the recover stream under `Failed`, prove the open completed and
    * nothing reload-triggering follows, then flip and require exactly one
    * reload. The flips are recovery, and a re-broken edit whose new message the
    * page must show.
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
            // The marker exists only once the stream subscribed under the
            // current state, so it proves the open ran under Failed. A reload
            // here would loop, since the page just loaded.
            awaitMarker(seen) *>
            assertNothing(seen) *>
            ref.set(flip) *>
            awaitReloads(seen, 1)
        }
        reloads <- seen.get
      } yield {
        val reloadEvents = reloads.filter(reloadEvent)
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
              awaitReloads(seen, 1)
          }
          reloads <- seen.get
        } yield {
          val reloadEvents = reloads.filter(reloadEvent)
          // The fix landed between render and connect, so the transition's
          // reload went to nobody and the stream says it now.
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
    // The gate is on the stream's own single lookup, so this is the recover
    // 404's question: a stale double lookup would have answered 200 with a body
    // ending at once.
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
            awaitReloads(seen, 1)
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
    // The `events -> reloadSite` wiring the OS watcher drives, fed a controlled
    // event stream.
    stageRepairWorld.use { case (ws, _) =>
      for {
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        // Seeded as broken, so the first reload is a real change.
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
            IO.blocking(
              os.write.over(ws / Site.EntryFile, kitchenSite())
            ) *>
            events.offer(modified(ws / Site.EntryFile)) *>
            awaitState(ref)(_.isInstanceOf[Server.RendererState.Ready]) *>
            awaitWatched(watched) *>
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
    // #141 (ADR 0021). The recorder is the part that would silently rot: a
    // removed slug keeping its recorder diffs every batch forever, so it is
    // checked through the store's subscriber count.
    stageRepairWorld.use { case (ws, fake) =>
      for {
        _ <- IO.blocking(os.write.over(ws / Site.EntryFile, kitchenSite()))
        ref <- SignallingRef[IO].of(
          Server.RendererState.Failed("seeded broken")
        )
        // Seeded as broken, so the first reload is a real change.
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
            ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
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
              _ <- reload
              _ <- awaitSubscribers(store, 1)
              _ <- IO.blocking(
                os.write.over(ws / Site.EntryFile, kitchenSite(secondKey))
              )
              _ <- reload
              added <- site.names
              addedPage <- page(server, "/d/second")
              _ <- awaitSubscribers(store, 2)
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
    // A new file is nobody's import yet, so watching paths cannot see it.
    // Pinned: the workspace directory is watched, and a `Created` `*.pkl` event
    // survives the lockfile filter.
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
            // The entrypoint globs, so the file is a dashboard the moment it
            // exists.
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
    // `PklProject.deps.json` is rewritten by the reload's own evaluation, so
    // reacting to it would feed itself. Asserted on the filter, not by a loop
    // that would hang the suite.
    val lockfile = fs2.io.file.Path("/ws/PklProject.deps.json")
    val source = fs2.io.file.Path("/ws/kitchen.dashboard.pkl")
    val manifest = fs2.io.file.Path("/ws/PklProject")
    assert(!ServerApp.isSourceEvent(modified(os.Path(lockfile.toString))))
    assert(ServerApp.isSourceEvent(created(os.Path(source.toString))))
    assert(ServerApp.isSourceEvent(modified(os.Path(manifest.toString))))
    // Overflow names no path and must pass: events were lost, so a reload is
    // owed.
    assert(ServerApp.isSourceEvent(fs2.io.file.Watcher.Event.Overflow(1)))
  }

  test("a reload that changes nothing does not touch the registry") {
    // The watcher fires on anything in the workspace, and installing `Ready`
    // rotates the fragment log and repaints every browser, so an unchanged
    // dashboard is not re-installed. Observed through the state's identity.
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
        _ <- reload
        again <- ref.get
        // Per slug, so one author's edit repaints one dashboard.
        _ <- IO.blocking(
          os.write.over(ws / Site.EntryFile, kitchenSite(secondKey))
        )
        _ <- reload
        afterAdd <- ref.get
        names <- site.names
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
        // Seeded as broken, so the first reload is a real change.
        site <- Server.LiveSite.of(
          Map("dash" -> ref),
          Map("dash" -> (Left("seeded broken"): Either[String, Dashboard])),
          "dash"
        )
        imports <- SignallingRef[IO].of(Set.empty[fs2.io.file.Path])
        _ <- ServerApp.reloadSite(ws, site, imports)
        chosen <- site.defaultSlug
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
    // ADR 0010, checked against the registry: a pushed slug is in no
    // entrypoint, so every reload sees it as unnamed and must leave it serving.
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
        // The first also drops "second", so the removal path ran.
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
    // The registry's record of each slug's origin is the only input besides the
    // new site.
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
      assertEquals(plan.removals, Set("dropped"))
    }
  }

  /** A package-form workspace whose entrypoint starts broken: production's
    * staging for a bad edit, minus the boot.
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

  private def serve(
      fake: FakeHomeAssistant,
      refs: Map[String, SignallingRef[IO, Server.RendererState]]
  ): IO[(Status, String)] =
    (for {
      store <- StateStore
        .inMemory(Map("light.kitchen" -> es("light.kitchen", "on")))
        .toResource
      sessions <- Sessions.create.toResource
      server <- Server.resource(
        ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
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

  /** Builds only while the fixture house is the dump. `extra` adds keys,
    * `default` the preferred slug.
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

  /** By the `*.dashboard.pkl` glob, resolved on every evaluation, so a file
    * appearing is a dashboard appearing.
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

  /** Counts, since `seen` accumulates: asking whether one exists is answered by
    * an earlier reload, green alone and red under load.
    */
  private def awaitReloads(
      seen: Ref[IO, Vector[ServerSentEvent]],
      atLeast: Int
  ): IO[Unit] =
    fs2.Stream
      .repeatEval(seen.get <* IO.sleep(10.millis))
      .find(_.count(reloadEvent) >= atLeast)
      .compile
      .drain
      .timeout(15.seconds)

  /** [[Server.recoverOpenMarker]], a comment the browser's EventSource drops
    * before Datastar, so awaiting it proves the open without anything
    * reload-triggering.
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
    e.comment.contains("recover-open")

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
