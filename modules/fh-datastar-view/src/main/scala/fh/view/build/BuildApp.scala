package fh.view.build

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Env
import fh.api.FHApi

/** Build phase entry point.
  *
  * Connects to Home Assistant, evaluates the dashboard jsonnet into a
  * `dashboard.json` artifact (validating it decodes into the runtime model
  * along the way), and writes it. Run via `fh-datastar-view/run` (or the
  * `dashboardBuild` alias) with `SERVER`/`SECRET` set.
  *
  * The artifact is for inspection/CI; the runtime
  * ([[fh.view.runtime.ServerApp]]) evaluates the same jsonnet in memory and
  * does not need it.
  */
object BuildApp extends IOApp {

  // Paths are relative to the module directory (the forked `run` working dir).
  private val defaultDashboardsDir = "src/main/resources/dashboards"
  private val defaultDashboardJson = "dashboard.json"
  private val defaultDashboardEntry = "dashboard.jsonnet"

  def run(args: List[String]): IO[ExitCode] =
    for {
      dashboardsDir <- pathFromEnv("DASHBOARDS_DIR", defaultDashboardsDir)
      outputPath <- pathFromEnv("DASHBOARD_JSON", defaultDashboardJson)
      // The entry file (relative to the dashboards dir); a `.pkl` entry evaluates
      // through the same source-agnostic pipeline as the default `.jsonnet` one.
      entry <- Env[IO]
        .get("DASHBOARD_ENTRY")
        .map(_.getOrElse(defaultDashboardEntry))

      result <- FHApi.fromEnv.use(
        DashboardBuild.evaluate(_, dashboardsDir, entry)
      )
      dashboardJson = result.value
      _ <- IO.println(
        s"Wrote entity dump to ${dashboardsDir / "dump.libsonnet"}"
      )

      // Validate it decodes into the runtime model before writing it.
      _ <- DashboardBuild.decode(dashboardJson)

      _ <- IO(os.write.over(outputPath, dashboardJson.spaces2))
      _ <- IO.println(s"Wrote dashboard artifact to $outputPath")
    } yield ExitCode.Success

  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    Env[IO]
      .get(name)
      .map(_.getOrElse(default))
      .map(s => os.Path(s, os.pwd))
}
