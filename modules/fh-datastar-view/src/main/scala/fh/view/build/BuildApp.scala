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
  * along the way), and writes it.
  *
  * **The entry is an argument, and there is no default**:
  * `sbt 'dashboardBuild overetasje.pkl'`, with `SERVER`/`SECRET` set. It used
  * to default to `dashboard.pkl`, a file no workspace actually has, so the
  * no-argument form failed with a Pkl "cannot find module" stack trace naming a
  * path the user never chose. A workspace has several entries and the build
  * produces one artifact, so which one is genuinely the caller's to say.
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
  private val defaultDashboardJson = "dashboard.json"

  def run(args: List[String]): IO[ExitCode] =
    for {
      dashboardsDir <- pathFromEnv("DASHBOARDS_DIR", defaultDashboardsDir)
      outputPath <- pathFromEnv("DASHBOARD_JSON", defaultDashboardJson)
      // The `.pkl` entry file, relative to the dashboards dir. Required — see
      // the class doc.
      entry <- requireEntry(args, dashboardsDir)

      // Bring the workspace to a package-form state (lib package in the cache,
      // static base.pkl, seeded entries) before anything evaluates — but NO
      // `pins.json` on a fresh workspace: `evaluate` runs `prepareDumps`, which
      // seeds the live dump package and writes the real pins in one step. The
      // bundled lib artifacts are threaded down so that first dump can pin its
      // `@fh-dashboard` dependency before any pins exist. The lib AND the
      // starter entry are both the running jar's own classpath resources
      // ([[BundledLib]], [[AddonBootstrap.defaultDashboard]]) — no seed path.
      cacheDir <- pathFromEnv(
        "FH_PKL_CACHE_DIR",
        AddonBootstrap.defaultCacheDir
      )
      bundled <- IO.blocking(BundledLib.artifacts())
      _ <- IO
        .blocking(
          // The build phase runs no server; the rewrite URL is inert (resolution
          // is cache-only), so a loopback default is fine in `machine.json`.
          AddonBootstrap.run(
            dashboardsDir,
            bundled,
            cacheDir,
            loopbackUrl = "http://127.0.0.1:8080"
          )
        )
        .flatMap(_.traverse_(IO.println))

      result <- FHApi.fromEnv.use(
        DashboardBuild.evaluate(_, dashboardsDir, entry, Some(bundled))
      )
      dashboardJson = result.value

      // Validate it decodes into the runtime model before writing it.
      _ <- DashboardBuild.decode(dashboardJson)

      _ <- IO.blocking(os.write.over(outputPath, dashboardJson.spaces2))
      _ <- IO.println(s"Wrote dashboard artifact to $outputPath")
    } yield ExitCode.Success

  /** The entry to build, or a failure that NAMES the entries this workspace
    * actually has — the question "which of these did you mean" is answerable
    * here and nowhere downstream, where it surfaces as a Pkl module-resolution
    * error.
    *
    * Listing runs before the workspace is bootstrapped, so a not-yet-seeded
    * directory simply lists nothing rather than erroring; the message still
    * says what to do.
    */
  private def requireEntry(
      args: List[String],
      dashboardsDir: os.Path
  ): IO[String] =
    args match {
      case entry :: _ => IO.pure(entry)
      case Nil        =>
        IO.blocking {
          if (os.exists(dashboardsDir))
            os.list(dashboardsDir)
              .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
              .map(_.last)
              .sorted
          else Nil
        }.flatMap { entries =>
          val available =
            if (entries.isEmpty) s"no *.pkl entries found in $dashboardsDir"
            else s"entries in $dashboardsDir: ${entries.mkString(", ")}"
          IO.raiseError(
            new IllegalArgumentException(
              s"dashboardBuild needs the entry to build, e.g. " +
                s"sbt 'dashboardBuild ${entries.headOption.getOrElse("my-dashboard.pkl")}' — $available"
            )
          )
        }
    }

  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    Env[IO]
      .get(name)
      .map(_.getOrElse(default))
      .map(s => os.Path(s, os.pwd))
}
