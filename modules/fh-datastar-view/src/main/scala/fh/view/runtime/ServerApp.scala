package fh.view.runtime

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.kernel.Ref
import cats.effect.std.Env
import cats.syntax.all.*
import scala.concurrent.duration.*
import com.comcast.ip4s.{Host, Port, host, port}
import fh.api.FHApi
import fh.view.model.Dashboard
import io.circe.parser
import org.http4s.ember.server.EmberServerBuilder

/** Runtime phase entry point.
  *
  * Loads the prebuilt `dashboard.json`, seeds live state from Home Assistant,
  * and serves the dashboard with live Datastar updates. Run via
  * `fh-datastar-view/runMain fh.view.runtime.ServerApp` with `SERVER`/`SECRET`
  * set.
  */
object ServerApp extends IOApp {

  // Relative to the module directory (the forked `run` working dir).
  private val defaultDashboardJson = "dashboard.json"

  def run(args: List[String]): IO[ExitCode] = {
    for {
      dashboardPath <- Env[IO]
        .get("DASHBOARD_JSON")
        .map(_.getOrElse(defaultDashboardJson))
      dashboard <- loadDashboard(dashboardPath)
      _ <- dashboard.validate match {
        case Nil => IO.unit
        case errs =>
          IO.raiseError(
            new RuntimeException(
              s"Invalid dashboard (${errs.size} error(s)):\n" + errs.mkString(
                "\n"
              )
            )
          )
      }
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
  }

  private def loadDashboard(path: String): IO[Dashboard] =
    IO(os.read(os.Path(path, os.pwd)))
      .flatMap(parser.parse(_).liftTo[IO])
      .flatMap(_.as[Dashboard].liftTo[IO])
      .adaptError { case e =>
        new RuntimeException(
          s"Failed to load dashboard from $path (run the build phase first): ${e.getMessage}",
          e
        )
      }
}
