package fh.view.runtime

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.kernel.Ref
import cats.effect.std.Env
import scala.concurrent.duration.*
import com.comcast.ip4s.{Host, Port, host, port}
import fh.api.FHApi
import fh.view.build.DashboardBuild
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
        // Evaluate the jsonnet dashboard in memory against the live instance.
        dashboard <- DashboardBuild
          .build(api, dashboardsDir, "dashboard.jsonnet")
          .toResource
        store <- StateStore.create(api)
        renderer = new Renderer(dashboard, Templates.from(dashboard))
        lastRendered <- Ref[IO].of(Map.empty[String, String]).toResource
        server = new Server(api, store, renderer, lastRendered)
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

  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    Env[IO]
      .get(name)
      .map(_.getOrElse(default))
      .map(s => os.Path(s, os.pwd))
}
