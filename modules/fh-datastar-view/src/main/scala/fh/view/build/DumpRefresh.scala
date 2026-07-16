package fh.view.build

import cats.effect.IO
import cats.syntax.all.*

/** Replace the on-disk `home/dump.pkl` with a freshly-rendered one — but only
  * after proving the new dump doesn't break any dashboard (ADR 0010, "force-
  * rerun the dump").
  *
  * The dump is deliberately NOT watched by the live-reload watcher: it changes
  * when the HOME changes (a device added or renamed in the HA registry), not
  * when an author edits. This is the sanctioned path for picking such a change
  * up without a restart, driven by two callers in `ServerApp`:
  *
  *   - the registry-event watcher (`*_registry_updated` over the WS, debounced,
  *     toggleable via the add-on's `watch_registry` option), and
  *   - the on-demand `POST /system/dump/refresh` endpoint (the /edit editor's
  *     "refresh dump" button).
  *
  * Validate-then-swap: the whole workspace is copied to a temp dir, the new
  * dump written THERE, and every entry re-evaluated against it. An entry that
  * fails with the new dump blocks the swap only if it still builds with the
  * CURRENT dump — an entry that is already broken (a user mid-edit, a startup
  * skip) must not veto a registry change forever. Only on green does the real
  * workspace change: the old dump is kept as `dump.pkl.backup.<date>` (the
  * shared convention — user-visible files are renamed, never deleted), the new
  * one written, and its content-versioned package seeded. The caller then
  * re-evaluates the live renderers; on a rejection it warns and nothing on disk
  * has moved.
  */
object DumpRefresh {

  sealed trait Result

  /** The rendered dump is byte-identical to the on-disk one — nothing to do. */
  case object Unchanged extends Result

  /** Swapped in: the previous dump lives on at `backup` (`None` only when there
    * was no dump to back up), `seedLog` is [[DumpPackage.seedFromWorkspace]]'s
    * action log for the new snapshot.
    */
  final case class Swapped(backup: Option[os.Path], seedLog: List[String])
      extends Result

  /** The new dump breaks dashboards that build today; the workspace is
    * untouched. `errors` is `slug -> eval error` for each newly-broken entry.
    */
  final case class Rejected(errors: List[(String, String)]) extends Result

  /** Validate `newDump` (the rendered `dump.pkl` text) against every entry and
    * swap it in if green. `entries` is `(slug, entryFilename)` — the same list
    * `ServerApp.discoverEntries` feeds the renderers. Serialize calls (the
    * caller holds a mutex): two concurrent refreshes would race on the backup
    * and the temp validation.
    */
  def refresh(
      newDump: String,
      dashboardsDir: os.Path,
      entries: List[(String, String)]
  ): IO[Result] = {
    val dumpFile = DashboardBuild.dumpPath(dashboardsDir)
    IO.blocking(os.exists(dumpFile) && os.read(dumpFile) == newDump).flatMap {
      case true  => IO.pure(Unchanged)
      case false =>
        newlyBroken(newDump, dashboardsDir, entries).flatMap {
          case Nil    => IO.blocking(swap(newDump, dashboardsDir))
          case errors => IO.pure(Rejected(errors))
        }
    }
  }

  /** The entries the new dump would break: evaluate everything in a temp copy
    * of the workspace carrying the new dump, then re-check each failure against
    * the real workspace (current dump) — a failure in both is pre-existing and
    * doesn't block.
    */
  private def newlyBroken(
      newDump: String,
      dashboardsDir: os.Path,
      entries: List[(String, String)]
  ): IO[List[(String, String)]] =
    IO.blocking(stageWorkspace(newDump, dashboardsDir)).bracket { staged =>
      for {
        results <- entries.traverse { case (slug, entry) =>
          DashboardBuild
            .reevaluate(staged, entry, Some(SystemPkl.fromDisk(staged)))
            .attempt
            .map(r => (slug, entry, r))
        }
        failed = results.collect { case (slug, entry, Left(err)) =>
          (slug, entry, err.getMessage)
        }
        blocking <- failed.filterA { case (_, entry, _) =>
          DashboardBuild
            .reevaluate(
              dashboardsDir,
              entry,
              Some(SystemPkl.fromDisk(dashboardsDir))
            )
            .attempt
            .map(_.isRight)
        }
      } yield blocking.map { case (slug, _, err) => (slug, err) }
    }(staged => IO.blocking(os.remove.all(staged / os.up)))

  /** A throwaway copy of the workspace with the new dump in place: everything
    * is copied (entries, `lib/` on a repo checkout, `.fh/`, manifests — an
    * import can reach any of it), the lockfiles are dropped so dependencies
    * re-resolve against the copy (`AddonBootstrap` does the same at boot), and
    * the new dump overwrites `home/dump.pkl`. The package cache needs no
    * copying: the add-on's is an absolute path (`/data/pkl-cache`) and a repo
    * checkout binds `@fh-dashboard` to `./lib` locally.
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
    os.write.over(
      DashboardBuild.dumpPath(staged),
      newDump,
      createFolders = true
    )
    staged
  }

  /** Green: back up the current dump (dated, never deleted), write the new one,
    * and seed its content-versioned package (same post-write step as
    * [[DashboardBuild.prepareDumps]]).
    */
  private def swap(newDump: String, dashboardsDir: os.Path): Result = {
    val dumpFile = DashboardBuild.dumpPath(dashboardsDir)
    val backup = Option.when(os.exists(dumpFile)) {
      val target = AddonBootstrap.backupPath(dumpFile)
      os.move(dumpFile, target)
      target
    }
    os.write.over(dumpFile, newDump, createFolders = true)
    Swapped(backup, DumpPackage.seedFromWorkspace(dashboardsDir))
  }
}
