package fh.view.build

import cats.effect.IO
import cats.syntax.all.*

/** Picks up a home change (the dump is deliberately not watched) without a
  * restart: validate-then-swap (ADR 0010). An unchanged content-version is a
  * no-op; otherwise every entry is re-evaluated against the new dump in a
  * staged copy, and the pin moves only if nothing that builds today breaks. The
  * previous version stays in the cache as the trail.
  */
object DumpRefresh {

  sealed trait Result

  case object Unchanged extends Result

  case class Swapped(version: String, seedLog: List[String]) extends Result

  /** `slug -> eval error` for each newly broken entry. */
  case class Rejected(errors: List[(String, String)]) extends Result

  /** Callers must serialize: concurrent refreshes race on the staged copy and
    * the pin.
    */
  def refresh(
      newDump: String,
      dashboardsDir: os.Path
  ): IO[Result] =
    IO.blocking(
      (
        DumpPackage.versionFor(dashboardsDir, newDump),
        Pins.homeVersion(dashboardsDir)
      )
    ).flatMap {
      case (Some(nv), Some(pv)) if nv == pv => IO.pure(Unchanged)
      case (newVersion, _)                  =>
        newlyBroken(newDump, dashboardsDir).flatMap {
          case Nil    => IO.blocking(swap(newDump, dashboardsDir, newVersion))
          case errors => IO.pure(Rejected(errors))
        }
    }

  /** A failure against the current dump too is pre-existing and does not veto,
    * or a user mid-edit would block every registry change. Same rule for the
    * whole entrypoint.
    */
  private def newlyBroken(
      newDump: String,
      dashboardsDir: os.Path
  ): IO[List[(String, String)]] =
    IO.blocking(stageWorkspace(newDump, dashboardsDir))
      .bracket { staged =>
        (
          DashboardBuild.evalSite(staged).attempt,
          DashboardBuild.evalSite(dashboardsDir).attempt
        ).flatMapN {
          case (Left(err), Right(_)) =>
            IO.pure(List(Site.EntryFile -> Site.messageOf(err)))
          case (Left(_), Left(_))            => IO.pure(Nil)
          case (Right((staged, _)), current) =>
            val building = current.toOption
              .map(_._1.dashboards.collect { case (slug, Right(_)) => slug })
              .getOrElse(Nil)
              .toSet
            IO.pure(
              staged.dashboards.collect {
                case (slug, Left(err)) if building(slug) => slug -> err
              }
            )
        }
      }(staged => IO.blocking(os.remove.all(staged / os.up)))

  /** Lockfiles are dropped so the copy re-resolves. The cache is shared, not
    * copied; seeding into it is additive.
    */
  private def stageWorkspace(
      newDump: String,
      dashboardsDir: os.Path
  ): os.Path = {
    val staged = os.temp.dir(prefix = "fh-dump-refresh") / "ws"
    os.copy(dashboardsDir, staged)
    os.walk(staged, maxDepth = 2)
      .filter(_.last == "PklProject.deps.json")
      .foreach(os.remove)
    val _ = DumpPackage.seedFromText(staged, newDump)
    staged
  }

  /** `version` is `None` only when the workspace cannot package, and then
    * seeding is a no-op too.
    */
  private def swap(
      newDump: String,
      dashboardsDir: os.Path,
      version: Option[String]
  ): Result = {
    val seedLog = DumpPackage.seedFromText(dashboardsDir, newDump)
    Swapped(version.getOrElse("?"), seedLog)
  }
}
