package fh.view.build

import cats.effect.IO

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
    * whole entrypoint. The current workspace is evaluated only when the staged
    * one has a failure to excuse: on a Pi each evaluation is seconds (#406).
    */
  private def newlyBroken(
      newDump: String,
      dashboardsDir: os.Path
  ): IO[List[(String, String)]] = {
    val current = DashboardBuild.evalSite(dashboardsDir).attempt
    IO.blocking(stageWorkspace(newDump, dashboardsDir))
      .bracket { staged =>
        DashboardBuild.evalSite(staged).attempt.flatMap {
          case Left(err) =>
            current.map {
              case Right(_) => List(Site.EntryFile -> Site.messageOf(err))
              case Left(_)  => Nil
            }
          case Right((staged, _)) =>
            val broken = staged.dashboards.collect { case (slug, Left(err)) =>
              slug -> err
            }
            if (broken.isEmpty) IO.pure(Nil)
            else
              current.map { now =>
                val building = now.toOption
                  .map(_._1.dashboards.collect { case (slug, Right(_)) =>
                    slug
                  })
                  .getOrElse(Nil)
                  .toSet
                broken.filter((slug, _) => building(slug))
              }
        }
      }(staged => IO.blocking(os.remove.all(staged / os.up)))
  }

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
