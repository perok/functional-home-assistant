package fh.view.build

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Env
import cats.syntax.all.*
import fh.api.FHApi

/** Build phase entry point.
  *
  * Bootstraps a **package-form** workspace exactly as the server does
  * ([[AddonBootstrap]]) — there is a single resolution mode (ADR 0010): the lib
  * and the dump are both cache packages, resolved offline via `moduleCacheDir`.
  * Then it connects to Home Assistant, evaluates the dashboard Pkl entry into a
  * `dashboard.json` artifact (validating it decodes into the runtime model
  * along the way), and writes it. Run via `fh-datastar-view/run` (or the
  * `dashboardBuild` alias) with `SERVER`/`SECRET` set.
  *
  * The artifact is for inspection/CI; the runtime
  * ([[fh.view.runtime.ServerApp]]) evaluates the same Pkl in memory and does
  * not need it. Paths default to the same gitignored scratch workspace + shared
  * appdirs cache the local `sbt dashboardServe` uses, so the two share one
  * bootstrapped workspace.
  */
object BuildApp extends IOApp {

  // Paths are relative to the module directory (the forked `run` working dir).
  private val defaultDashboardsDir = "dashboard-local-dev"
  private val bundledResourcesDir = "src/main/resources/dashboards"
  private val defaultDashboardJson = "dashboard.json"
  private val defaultDashboardEntry = "dashboard.pkl"

  // The SAME appdirs coordinates the `fh` script + `ServerApp` use, so a local
  // build resolves the same cache a local serve does.
  private def defaultCacheDir: String =
    s"${net.harawata.appdirs.AppDirsFactory.getInstance
        .getUserDataDir("fh", "0.0.1", "perok")}/pkl-cache"

  def run(args: List[String]): IO[ExitCode] =
    for {
      dashboardsDir <- pathFromEnv("DASHBOARDS_DIR", defaultDashboardsDir)
      outputPath <- pathFromEnv("DASHBOARD_JSON", defaultDashboardJson)
      // The `.pkl` entry file (relative to the dashboards dir).
      entry <- Env[IO]
        .get("DASHBOARD_ENTRY")
        .map(_.getOrElse(defaultDashboardEntry))

      // Bring the workspace to a package-form state (lib package in the cache,
      // static base.pkl + `.fh/pins.json` with the lib pin + a placeholder dump
      // pin, seeded entries) before anything evaluates — then `evaluate` runs
      // `prepareDumps`, which seeds the live dump package and moves the home pin.
      bundledLib <- pathFromEnv("FH_BUNDLED_LIB", s"$bundledResourcesDir/lib")
      seedDir <- pathFromEnv("FH_SEED_DIR", bundledResourcesDir)
      cacheDir <- pathFromEnv("FH_PKL_CACHE_DIR", defaultCacheDir)
      _ <- IO
        .blocking(
          AddonBootstrap.run(dashboardsDir, bundledLib, seedDir, cacheDir)
        )
        .flatMap(_.traverse_(IO.println))

      result <- FHApi.fromEnv.use(
        DashboardBuild.evaluate(_, dashboardsDir, entry)
      )
      dashboardJson = result.value

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
