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

  def run(args: List[String]): IO[ExitCode] =
    for {
      dashboardsDir <- pathFromEnv("DASHBOARDS_DIR", defaultDashboardsDir)
      outputPath <- pathFromEnv("DASHBOARD_JSON", defaultDashboardJson)
      // The `.pkl` entry file (relative to the dashboards dir).
      entry <- Env[IO]
        .get("DASHBOARD_ENTRY")
        .map(_.getOrElse(defaultDashboardEntry))

      // Bring the workspace to a package-form state (lib package in the cache,
      // static base.pkl, seeded entries) before anything evaluates — but NO
      // `pins.json` on a fresh workspace: `evaluate` runs `prepareDumps`, which
      // seeds the live dump package and writes the real pins in one step. The
      // bundled lib artifacts are threaded down so that first dump can pin its
      // `@fh-dashboard` dependency before any pins exist.
      bundledLib <- pathFromEnv("FH_BUNDLED_LIB", s"$bundledResourcesDir/lib")
      seedDir <- pathFromEnv("FH_SEED_DIR", bundledResourcesDir)
      cacheDir <- pathFromEnv(
        "FH_PKL_CACHE_DIR",
        AddonBootstrap.defaultCacheDir
      )
      bundled <- IO
        .blocking(
          // The build phase runs no server; the rewrite URL is inert (resolution
          // is cache-only), so a loopback default is fine in `machine.json`.
          AddonBootstrap.run(
            dashboardsDir,
            bundledLib,
            seedDir,
            cacheDir,
            loopbackUrl = "http://127.0.0.1:8080"
          )
        )
        .flatMap { case (artifacts, log) =>
          log.traverse_(IO.println).as(artifacts)
        }

      result <- FHApi.fromEnv.use(
        DashboardBuild.evaluate(_, dashboardsDir, entry, Some(bundled))
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
