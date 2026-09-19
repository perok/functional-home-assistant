package fh.view.build

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Env
import cats.syntax.all.*
import fh.api.FHApi
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Build phase entry point.
  *
  * Bootstraps a **package-form** workspace exactly as the server does
  * ([[AddonBootstrap]]) — there is a single resolution mode (ADR 0010): the lib
  * and the dump are both cache packages, resolved offline via `moduleCacheDir`.
  * Then it connects to Home Assistant, evaluates the dashboard Pkl entry into a
  * `dashboard.json` artifact (validating it decodes into the runtime model
  * along the way), and writes it.
  *
  * There is nothing to choose: a workspace has ONE entrypoint
  * ([[Site.EntryFile]]) naming every dashboard it serves (ADR 0021), so
  * `sbt dashboardBuild` with `SERVER`/`SECRET` set builds the whole site into
  * one artifact.
  *
  * The artifact is for inspection/CI; the runtime
  * ([[fh.view.runtime.ServerApp]]) evaluates the same Pkl in memory and does
  * not need it. The workspace is named by `DASHBOARDS_DIR` and never defaulted;
  * the pkl package cache still defaults to the shared one `sbt dashboardServe`
  * uses, so pointing both at one directory bootstraps it once.
  */
object BuildApp extends IOApp {

  private val log = Slf4jLogger.getLogger[IO]

  // Paths are relative to the forked `run` working dir, which is the REPO ROOT
  // (`Compile / run / baseDirectory`), not the module directory.
  private val defaultDashboardJson = "dashboard.json"

  def run(args: List[String]): IO[ExitCode] =
    for {
      dashboardsDir <- workspaceFromEnv
      outputPath <- pathFromEnv("DASHBOARD_JSON", defaultDashboardJson)

      // Bring the workspace to a package-form state (lib package in the cache,
      // static base.pkl, seeded entries) before anything evaluates — but NO
      // `pins.json` on a fresh workspace: `evaluate` runs `prepareDumps`, which
      // seeds the live dump package and writes the real pins in one step. The
      // bundled lib artifacts are threaded down so that first dump can pin its
      // `@fh-dashboard` dependency before any pins exist. The lib AND the
      // starter entry are both the running jar's own classpath resources
      // ([[BundledLib]], [[AddonBootstrap.starterSite]]) — no seed path.
      cacheDir <- pathFromEnv(
        "FH_PKL_CACHE_DIR",
        AddonBootstrap.defaultCacheDir
      )
      bundled <- IO.blocking(BundledLib.artifacts())
      _ <- IO
        .blocking(
          AddonBootstrap.run(dashboardsDir, bundled, cacheDir)
        )
        .flatMap(_.traverse_(log.info(_)))

      result <- FHApi.fromEnv.use(
        DashboardBuild.evaluate(_, dashboardsDir, Site.EntryFile, Some(bundled))
      )
      siteJson = result.value

      // Validate every dashboard it names decodes into the runtime model
      // before writing it — including the per-slug failures, which are
      // reported rather than carried here: the artifact is for inspection and
      // CI, so a site that only half-builds should fail the build.
      decoded <- Site.decode(siteJson, result.imports)
      _ <- decoded.dashboards.collect { case (slug, Left(err)) =>
        s"'$slug': $err"
      } match {
        case Nil    => IO.unit
        case errors =>
          IO.raiseError(
            new IllegalStateException(
              s"${errors.size} dashboard(s) failed to build:\n" +
                errors.mkString("\n")
            )
          )
      }

      _ <- IO.blocking(os.write.over(outputPath, siteJson.spaces2))
      _ <- log.info(
        s"Wrote site artifact (${decoded.slugs.mkString(", ")}) to $outputPath"
      )
    } yield ExitCode.Success

  /** The workspace to build, which is REQUIRED and never guessed — same rule as
    * [[fh.view.runtime.ServerApp]]. `BuildApp` takes no argument (ADR 0021), so
    * `DASHBOARDS_DIR` is the only channel; put it in the repo-root `.env`
    * alongside `SERVER`/`SECRET`, which is where this run already gets its
    * environment from.
    */
  private def workspaceFromEnv: IO[os.Path] =
    Env[IO].get("DASHBOARDS_DIR").flatMap {
      case Some(dir) if dir.nonEmpty => IO.pure(os.Path(dir, os.pwd))
      case _                         =>
        IO.raiseError(
          Exception(
            "no workspace: set DASHBOARDS_DIR (in the repo-root .env) to the directory to build"
          )
        )
    }

  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    Env[IO]
      .get(name)
      .map(_.getOrElse(default))
      .map(s => os.Path(s, os.pwd))
}
