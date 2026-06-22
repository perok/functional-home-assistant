package fh.view.runtime

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.kernel.Ref
import cats.effect.std.Env
import cats.syntax.all.*
import scala.concurrent.duration.*
import com.comcast.ip4s.{Host, Port, host, port}
import fh.api.FHApi
import fh.view.build.DashboardBuild
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.http4s.ember.server.EmberServerBuilder
import fs2.io.file.{Watcher, Path}

/** Runtime phase entry point.
  *
  * Connects to Home Assistant, evaluates the dashboard jsonnet **in memory**
  * into the runtime model (no prebuilt `dashboard.json` needed), seeds live
  * state, and serves the dashboard with live Datastar updates. Run via
  * `fh-datastar-view/runMain fh.view.runtime.ServerApp` with `SERVER`/`SECRET`
  * set.
  */
object ServerApp extends IOApp {

  // Relative to the module directory (the forked `run` working dir).
  private val defaultDashboardsDir = "src/main/resources/dashboards"

  def run(args: List[String]): IO[ExitCode] =
    for {
      dashboardsDir <- pathFromEnv("DASHBOARDS_DIR", defaultDashboardsDir)
      bindHost <- Env[IO]
        .get("HOST")
        .map(_.flatMap(Host.fromString).getOrElse(host"0.0.0.0"))
      bindPort <- Env[IO]
        .get("PORT")
        .map(
          _.flatMap(_.toIntOption).flatMap(Port.fromInt).getOrElse(port"8080")
        )

      _ <- (for {
        api <- FHApi.fromEnv
        // Evaluate the jsonnet dashboard in memory against the live instance;
        // `imports` is the entry + every file it transitively imports.
        (dashboard, imports0) <- DashboardBuild
          .build(api, dashboardsDir, "dashboard.jsonnet")
          .toResource
        store <- StateStore.create(api)
        rendererRef <- SignallingRef[IO]
          .of(Renderer.create(dashboard))
          .toResource
        importsRef <- SignallingRef[IO].of(imports0.map(fs2Path)).toResource
        lastRendered <- Ref[IO].of(Map.empty[String, String]).toResource
        server = new Server(api, store, rendererRef, lastRendered)
        // Live reload: re-evaluate (reusing the on-disk dump) when any watched
        // source changes, swap the renderer, and let the SSE stream repaint.
        _ <- watchSources(
          dashboardsDir,
          rendererRef,
          importsRef,
          lastRendered
        ).compile.drain.background
        _ <- EmberServerBuilder
          .default[IO]
          .withHost(bindHost)
          .withPort(bindPort)
          .withHttpApp(server.routes.orNotFound)
          .withShutdownTimeout(0.seconds)
          .build
        _ <- IO
          .println(s"Dashboard serving on http://$bindHost:$bindPort")
          .toResource
      } yield ()).useForever
    } yield ExitCode.Success

  private val watchedEvents = List(
    Watcher.EventType.Created,
    Watcher.EventType.Modified,
    Watcher.EventType.Deleted
  )

  /** Watch the files the dashboard was built from (the entry + its transitive
    * imports, reported by sjsonnet) with an `fs2.io.file.Watcher`, registering
    * each file individually, and, on change, re-evaluate and hot-swap the
    * renderer. The watched set tracks `importsRef`, which each re-evaluation
    * refreshes: a concurrent reconcile registers newly-imported files and
    * cancels ones no longer imported, so adding/removing an `import`
    * starts/stops watching that file. Events are debounced to coalesce the
    * burst a single editor save produces. A failed re-eval logs and keeps the
    * previous renderer serving.
    *
    * (`reevaluate` reuses the on-disk `dump.libsonnet` and never rewrites it,
    * so watching it can't self-trigger.)
    */
  private def watchSources(
      dashboardsDir: os.Path,
      rendererRef: SignallingRef[IO, Renderer],
      importsRef: SignallingRef[IO, Set[Path]],
      lastRendered: Ref[IO, Map[String, String]]
  ): Stream[IO, Unit] =
    Stream.resource(Watcher.default[IO]).flatMap { watcher =>
      // Keep the watcher registered on exactly the current import set: register
      // files that appeared and run the cancel handle for files that vanished.
      val reconcile =
        Stream.eval(Ref[IO].of(Map.empty[Path, IO[Unit]])).flatMap { active =>
          importsRef.discrete.evalMap { imports =>
            active.get.flatMap { current =>
              val toAdd = imports -- current.keySet
              val toCancel = current.keySet -- imports
              for {
                added <- toAdd.toList
                  .traverse(p => watcher.watch(p, watchedEvents).tupleLeft(p))
                _ <- toCancel.toList
                  .traverse_(p => current.getOrElse(p, IO.unit))
                _ <- active.set((current ++ added) -- toCancel)
              } yield ()
            }
          }
        }

      val reload =
        watcher
          .events()
          .debounce(200.millis) // coalesce the event burst of a single save
          .evalMap { _ =>
            DashboardBuild
              .reevaluate(dashboardsDir, "dashboard.jsonnet")
              .attempt
              .flatMap {
                case Right((dash, imports)) =>
                  rendererRef.set(Renderer.create(dash)) *>
                    importsRef.set(imports.map(fs2Path)) *>
                    lastRendered.set(Map.empty) *>
                    IO.println("Dashboard reloaded")
                case Left(err) =>
                  IO.println(
                    s"Dashboard reload failed (keeping previous): ${err.getMessage}"
                  )
              }
          }

      reload.concurrently(reconcile)
    }

  private def fs2Path(p: os.Path): Path = Path.fromNioPath(p.toNIO)

  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    Env[IO]
      .get(name)
      .map(_.getOrElse(default))
      .map(s => os.Path(s, os.pwd))
}
