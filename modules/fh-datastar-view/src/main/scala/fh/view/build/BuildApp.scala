package fh.view.build

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Env
import fh.api.FHApi

/** Build phase entry point.
  *
  * Connects to Home Assistant, evaluates the dashboard Pkl entry into a
  * `dashboard.json` artifact (validating it decodes into the runtime model
  * along the way), and writes it. Run via `fh-datastar-view/run` (or the
  * `dashboardBuild` alias) with `SERVER`/`SECRET` set.
  *
  * The artifact is for inspection/CI; the runtime
  * ([[fh.view.runtime.ServerApp]]) evaluates the same Pkl in memory and does
  * not need it.
  */
object BuildApp extends IOApp {

  // Paths are relative to the module directory (the forked `run` working dir).
  private val defaultDashboardsDir = "src/main/resources/dashboards"
  private val defaultDashboardJson = "dashboard.json"
  private val defaultDashboardEntry = "dashboard.pkl"

  def run(args: List[String]): IO[ExitCode] =
    for {
      dashboardsDir <- pathFromEnv("DASHBOARDS_DIR", defaultDashboardsDir)
      outputPath <- pathFromEnv("DASHBOARD_JSON", defaultDashboardJson)
      // The `.pkl` entry file (relative to the dashboards dir).
      entry <- Env[IO]
        .get("DASHBOARD_ENTRY")
        .map(_.getOrElse(defaultDashboardEntry))

      // Entries import `hass.pkl`/`dump.pkl` over the `/system/pkl/` http URL
      // (ADR 0010); the provider lets `PklBuild` resolve them in-memory off
      // disk. `evaluate` runs `prepareDumps` first, so `dump.pkl` exists before
      // the by-name read.
      systemPkl = SystemPkl.fromDisk(dashboardsDir)
      result <- FHApi.fromEnv.use(
        DashboardBuild.evaluate(_, dashboardsDir, entry, Some(systemPkl))
      )
      dashboardJson = result.value
      _ <- IO.println(
        s"Wrote entity dump to ${dashboardsDir / "lib" / "dump.pkl"}"
      )

      // Validate it decodes into the runtime model before writing it.
      _ <- DashboardBuild.decode(dashboardJson)

      _ <- IO.blocking(os.write.over(outputPath, dashboardJson.spaces2))
      _ <- IO.println(s"Wrote dashboard artifact to $outputPath")
    } yield ExitCode.Success

  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    Env[IO]
      .get(name)
      .map(_.getOrElse(default))
      .map(s => os.Path(s, os.pwd))
}
