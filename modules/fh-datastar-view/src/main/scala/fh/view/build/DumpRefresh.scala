package fh.view.build

import cats.effect.IO
import cats.syntax.all.*

/** Re-seed the `@fh-home` dump package from a freshly-rendered dump — but only
  * after proving the new dump doesn't break any dashboard (ADR 0010,
  * "refreshing the dump while running").
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
  * Validate-then-swap, in package terms (there is no loose `home/dump.pkl`):
  * the new dump's CONTENT-VERSION is compared to the pinned one — equal means
  * an unchanged home, nothing to do. Otherwise the whole workspace is copied to
  * a temp dir, the new dump seeded there as its package (into the shared cache,
  * with the staged pin moved to it), and every entry re-evaluated against it.
  * An entry that fails with the new dump blocks the swap only if it still
  * builds with the CURRENT dump — an entry already broken (a user mid-edit, a
  * startup skip) must not veto a registry change forever. On green the real pin
  * moves (`.fh/pins.json`), the caller re-evaluates the live renderers, and the
  * PREVIOUS package version stays in the cache — the immutable snapshot IS the
  * trail (still resolvable for any laptop pinned to it), so there is no dated
  * backup file. On rejection nothing moves and the server warns.
  */
object DumpRefresh {

  sealed trait Result

  /** The rendered dump's content-version equals the pinned one — nothing to do.
    */
  case object Unchanged extends Result

  /** Swapped in: `version` is the new `@fh-home` snapshot now pinned, `seedLog`
    * is [[DumpPackage.seedFromText]]'s action log for it.
    */
  case class Swapped(version: String, seedLog: List[String]) extends Result

  /** The new dump breaks dashboards that build today; the workspace is
    * untouched. `errors` is `slug -> eval error` for each newly-broken entry.
    */
  case class Rejected(errors: List[(String, String)]) extends Result

  /** Validate `newDump` (the rendered `dump.pkl` text) against every entry and
    * swap it in if green. `entries` is `(slug, entryFilename)` — the same list
    * `ServerApp.discoverEntries` feeds the renderers. Serialize calls (the
    * caller holds a mutex): two concurrent refreshes would race on the staged
    * validation and the pin move.
    */
  def refresh(
      newDump: String,
      dashboardsDir: os.Path,
      entries: List[(String, String)]
  ): IO[Result] =
    IO.blocking(
      (
        DumpPackage.versionFor(dashboardsDir, newDump),
        Pins.homeVersion(dashboardsDir)
      )
    ).flatMap {
      case (Some(nv), Some(pv)) if nv == pv => IO.pure(Unchanged)
      case (newVersion, _)                  =>
        newlyBroken(newDump, dashboardsDir, entries).flatMap {
          case Nil    => IO.blocking(swap(newDump, dashboardsDir, newVersion))
          case errors => IO.pure(Rejected(errors))
        }
    }

  /** The entries the new dump would break: evaluate everything in a temp copy
    * of the workspace carrying the new dump package, then re-check each failure
    * against the real workspace (current dump) — a failure in both is
    * pre-existing and doesn't block.
    */
  private def newlyBroken(
      newDump: String,
      dashboardsDir: os.Path,
      entries: List[(String, String)]
  ): IO[List[(String, String)]] =
    IO.blocking(stageWorkspace(newDump, dashboardsDir))
      .bracket { staged =>
        for {
          results <- entries.traverse { case (slug, entry) =>
            DashboardBuild
              .reevaluate(staged, entry)
              .attempt
              .map(r => (slug, entry, r))
          }
          failed = results.collect { case (slug, entry, Left(err)) =>
            (slug, entry, err.getMessage)
          }
          blocking <- failed.filterA { case (_, entry, _) =>
            DashboardBuild
              .reevaluate(dashboardsDir, entry)
              .attempt
              .map(_.isRight)
          }
        } yield blocking.map { case (slug, _, err) => (slug, err) }
      }(staged => IO.blocking(os.remove.all(staged / os.up)))

  /** A throwaway copy of the workspace resolving the NEW dump: everything is
    * copied (entries, `.fh/`, manifests — an import can reach any of it), the
    * lockfiles are dropped so dependencies re-resolve against the copy
    * (`AddonBootstrap` does the same at boot), and the new dump is seeded as
    * its `@fh-home` package with the staged pin moved to it. The package cache
    * itself is not copied — `moduleCacheDir` is an absolute path shared with
    * the real workspace, so seeding here makes the new (immutable, additive)
    * version available to both.
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

  /** Green: seed the new dump package and move the real pin to it. No dump file
    * is written and no dated backup is kept — the previous immutable package
    * version stays in the cache (the trail). `version` is the content-version
    * [[refresh]] already computed for `newDump` (`None` only when the workspace
    * cannot package, in which case seeding is a no-op too).
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
