package fh.view.build

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.Dashboard
import io.circe.Json
import fh.view.telemetry.Logging
import org.typelevel.log4cats.{Logger, LoggerFactory}

/** Pkl sources to a validated [[Dashboard]], for both [[BuildApp]] and the
  * in-memory runtime.
  */
object DashboardBuild {

  /** Seed the live dump as the `@fh-home` package (ADR 0010); there is no loose
    * dump file.
    */
  def prepareDumps(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      bundledLib: Option[LibPackage.Artifacts] = None,
      loggerFactory: LoggerFactory[IO] = Logging.console
  ): IO[Unit] =
    RegistryDump.fetch(api).timed.flatMap { (fetched, dump) =>
      val log = loggerFactory.getLoggerFromName(LoggerName)
      log.info(s"registry fetched in ${fetched.toMillis} ms") *>
        // Never fatal: one odd integration must not stop the dump.
        PklDump
          .warnings(dump)
          .traverse_(w => log.warn(s"dump warning: $w")) *>
        IO.blocking(
          DumpPackage
            .seedFromText(dashboardsDir, PklDump.render(dump), bundledLib)
        ).timed
          .flatMap { (seeded, lines) =>
            lines.traverse_(log.info(_)) *>
              log.info(s"dump rendered and seeded in ${seeded.toMillis} ms")
          }
    }

  /** `bundledLib` is needed only for the first dump on a fresh workspace. */
  def evaluate(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      entry: String,
      bundledLib: Option[LibPackage.Artifacts] = None
  ): IO[SourceEval.Result] =
    prepareDumps(api, dashboardsDir, bundledLib) *> evalSource(
      dashboardsDir,
      entry
    )

  private def evalSource(
      dashboardsDir: os.Path,
      entry: String
  ): IO[SourceEval.Result] =
    IO.blocking(SourceEval.eval(dashboardsDir, entry))
      .flatMap(
        _.leftMap(err => new RuntimeException(s"dashboard eval failed:\n$err"))
          .liftTo[IO]
      )

  /** `sources` only locates bad transforms in their source. */
  def decode(
      json: Json,
      sources: Set[os.Path] = Set.empty,
      // Applied before validation, so a `Validated` is not proven against a
      // slug it no longer has.
      slug: Option[String] = None,
      minifier: Minifier = Minifier.none
  ): IO[Dashboard.Validated] =
    for {
      minified <- minifier.dashboard(json)
      decoded <- minified
        .as[Dashboard]
        .leftMap(err =>
          FHError.badCondition(s"dashboard is not a valid Dashboard: $err")
        )
        .liftTo[IO]
      dashboard = slug.fold(decoded)(s => decoded.copy(slug = s))
      validated <- dashboard.validated(
        SourceEval.literalLocator(sources)
      ) match {
        case Right(v)   => IO.pure(v)
        case Left(errs) =>
          FHError
            .badCondition(
              s"dashboard failed validation (${errs.size} error(s)):\n" +
                errs.mkString("\n")
            )
            .raiseError[IO, Dashboard.Validated]
      }
    } yield validated

  /** An evaluation error raises (no slug to blame); one dashboard's error is a
    * `Left` that costs only that slug.
    */
  def evalSite(
      dashboardsDir: os.Path,
      log: Logger[IO] = Logging.console.getLoggerFromName(LoggerName),
      minifier: Minifier = Minifier.none
  ): IO[(Site.Decoded, Set[os.Path])] =
    evalSource(dashboardsDir, Site.EntryFile).timed.flatMap { (took, r) =>
      val how = if (r.fromCache) "read from the eval cache" else "evaluated"
      log.info(
        s"${Site.EntryFile} $how in ${took.toMillis} ms " +
          s"(${r.imports.size} workspace files)"
      ) *> Site.decode(r.value, r.imports, log, minifier).map(_ -> r.imports)
    }

  /** Issue #406: on a Pi a boot or a reload takes seconds, and these lines say
    * which phase took them.
    */
  private val LoggerName = "fh.view.build.DashboardBuild"
}
