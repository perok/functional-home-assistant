package fh.view.build

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Env
import cats.syntax.all.*
import fh.api.FHApi
import fh.view.model.Dashboard

/** Build phase entry point.
  *
  * Connects to Home Assistant, writes the entity dump next to the dashboard
  * jsonnet, evaluates the jsonnet into a `dashboard.json` artifact, and
  * validates that it decodes into the [[Dashboard]] model. Run via
  * `fh-datastar-view/run` (or the `dashboardBuild` alias) with
  * `SERVER`/`SECRET` set.
  */
object BuildApp extends IOApp {

  // Paths are relative to the module directory (the forked `run` working dir).
  private val defaultDashboardsDir = "src/main/resources/dashboards"
  private val defaultDashboardJson = "dashboard.json"

  def run(args: List[String]): IO[ExitCode] =
    for {
      dashboardsDir <- pathFromEnv("DASHBOARDS_DIR", defaultDashboardsDir)
      outputPath <- pathFromEnv("DASHBOARD_JSON", defaultDashboardJson)

      dump <- FHApi.fromEnv.use(DataDump.fetch)
      _ <- IO(os.write.over(dashboardsDir / "dump.libsonnet", dump.spaces2))
      _ <- IO.println(
        s"Wrote entity dump to ${dashboardsDir / "dump.libsonnet"}"
      )

      dashboardJson <- JsonnetBuild
        .eval(dashboardsDir, "dashboard.jsonnet")
        .leftMap(err => new RuntimeException(s"jsonnet eval failed:\n$err"))
        .liftTo[IO]

      // Validate the artifact decodes into our runtime model before writing it.
      dashboard <- dashboardJson
        .as[Dashboard]
        .leftMap(err =>
          new RuntimeException(s"dashboard.json is not a valid Dashboard: $err")
        )
        .liftTo[IO]

      // Validate every template reference resolves and its inputs are supplied.
      _ <- dashboard.validate match {
        case Nil => IO.unit
        case errs =>
          new RuntimeException(
            s"dashboard.json failed validation (${errs.size} error(s)):\n" +
              errs.mkString("\n")
          ).raiseError[IO, Unit]
      }

      _ <- IO(os.write.over(outputPath, dashboardJson.spaces2))
      _ <- IO.println(s"Wrote dashboard artifact to $outputPath")
    } yield ExitCode.Success

  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    Env[IO]
      .get(name)
      .map(_.getOrElse(default))
      .map(s => os.Path(s, os.pwd))
}
