package fh.view.build

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Env
import cats.syntax.all.*
import fh.api.FHApi
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** `sbt dashboardBuild`: bootstraps the workspace as the server does, then
  * writes the whole site as `dashboard.json` for inspection and CI. The runtime
  * does not need it.
  */
object BuildApp extends IOApp {

  private val log = Slf4jLogger.getLogger[IO]

  // Relative to the forked run's cwd, the repo root.
  private val defaultDashboardJson = "dashboard.json"

  def run(args: List[String]): IO[ExitCode] =
    for {
      dashboardsDir <- workspaceFromEnv
      outputPath <- pathFromEnv("DASHBOARD_JSON", defaultDashboardJson)

      // No `pins.json` yet on a fresh workspace: the first dump writes it, and
      // needs the bundled lib to pin against.
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

      // Unlike the server, a half-built site fails the build.
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

  /** Required, never guessed; it takes no argument (ADR 0021). */
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
