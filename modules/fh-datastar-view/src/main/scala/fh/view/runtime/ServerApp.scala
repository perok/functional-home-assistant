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
        built <- DashboardBuild
          .build(api, dashboardsDir, "dashboard.jsonnet")
          .toResource
        (dashboard, imports0) = built
        store <- StateStore.create(api)
        rendererRef <- SignallingRef[IO]
          .of(new Renderer(dashboard, Templates.from(dashboard)))
          .toResource
        importsRef <- SignallingRef[IO].of(imports0).toResource
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

  /** Poll the files the dashboard was built from (the entry + its transitive
    * imports, reported by sjsonnet) and, on change, re-evaluate and hot-swap
    * the renderer. The watched set comes from `importsRef`, which each
    * re-evaluation refreshes — so adding/removing an `import` starts/stops
    * watching that file. A failed re-eval logs and keeps the previous renderer
    * serving.
    *
    * (`reevaluate` reuses the on-disk `dump.libsonnet` and never rewrites it,
    * so watching it can't self-trigger.)
    */
  private def watchSources(
      dashboardsDir: os.Path,
      rendererRef: SignallingRef[IO, Renderer],
      importsRef: SignallingRef[IO, Set[os.Path]],
      lastRendered: Ref[IO, Map[String, String]]
  ): Stream[IO, Unit] =
    Stream
      .awakeEvery[IO](1.second)
      .evalMap(_ => importsRef.get.flatMap(signatureOf))
      .changes
      .drop(1) // skip the initial signature
      .evalMap { _ =>
        DashboardBuild
          .reevaluate(dashboardsDir, "dashboard.jsonnet")
          .attempt
          .flatMap {
            case Right((dash, imports)) =>
              rendererRef.set(new Renderer(dash, Templates.from(dash))) *>
                importsRef.set(imports) *>
                lastRendered.set(Map.empty) *>
                IO.println("Dashboard reloaded")
            case Left(err) =>
              IO.println(
                s"Dashboard reload failed (keeping previous): ${err.getMessage}"
              )
          }
      }

  /** mtimes of the given files (sorted, existing only), used as a change key.
    */
  private def signatureOf(paths: Set[os.Path]): IO[List[(String, Long)]] =
    IO {
      paths.toList
        .filter(os.exists)
        .map(p => p.toString -> os.mtime(p))
        .sorted
    }

  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    Env[IO]
      .get(name)
      .map(_.getOrElse(default))
      .map(s => os.Path(s, os.pwd))
}
