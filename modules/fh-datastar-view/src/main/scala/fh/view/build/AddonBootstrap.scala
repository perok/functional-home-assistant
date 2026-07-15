package fh.view.build

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Add-on boot: bring the user's dashboards workspace to a state the server
  * can evaluate, without ever owning the user's files (ADR 0010, "the add-on
  * workspace").
  *
  * The library is NOT copied into the workspace. The bundled `lib/` (baked
  * into the image) is packaged by [[LibPackage]] into the persistent package
  * cache, and the seeded manifests depend on it as
  * `package://fh.invalid/fh-dashboard@<bundled version>` — so the user's dir
  * holds only user files, a runtime upgrade never touches the user's pin (the
  * old version keeps resolving from the cache), and a LIB upgrade is the
  * user's deliberate pin bump. Pre-package-form installs are migrated: their
  * seeded `lib/` copy and manifests are renamed to dated backups, never
  * deleted.
  *
  * Idempotent; called on every start, before anything evaluates. Pure
  * side-effecting file work — the caller wraps it in `IO.blocking` and prints
  * the returned action log.
  */
object AddonBootstrap {

  /** @param dashboardsDir the user's workspace (`/homeassistant/fh-dashboards`)
    * @param bundledLib    the image's library (`/opt/fh/lib`), read-only
    * @param seedDir       starter entries copied on first boot only
    * @param cacheDir      the persistent package cache (`/data/pkl-cache`) —
    *                      written into the seeded manifests' `moduleCacheDir`,
    *                      so pkl-lsp and the server resolve from the same place
    */
  def run(
      dashboardsDir: os.Path,
      bundledLib: os.Path,
      seedDir: os.Path,
      cacheDir: os.Path
  ): List[String] = {
    val version = LibPackage.version(bundledLib)
    val log = List.newBuilder[String]

    log ++= LibPackage.seedCache(bundledLib, cacheDir)
    os.makeDir.all(dashboardsDir)
    os.makeDir.all(dashboardsDir / "home")

    // A pre-package-form workspace carries the old seeded lib copy — frozen at
    // install time by the old copy-if-empty seeding, which is the bug this
    // whole bootstrap replaces. The backup preserves any local edits.
    if (os.exists(dashboardsDir / "lib")) {
      val target = backupPath(dashboardsDir / "lib")
      os.move(dashboardsDir / "lib", target)
      log += s"migrated: lib/ no longer lives in the workspace (moved to ${target.last})"
    }

    log ++= writeManifest(
      dashboardsDir / "PklProject",
      consumerManifest(version, cacheDir),
      oldFormMarker = "import(\"./lib/PklProject\")"
    )
    log ++= writeManifest(
      dashboardsDir / "home" / "PklProject",
      homeManifest(version),
      oldFormMarker = "import(\"../lib/PklProject\")"
    )

    // Starter entries only when the user has none at all — entries are the
    // user's files from the moment they exist.
    val hasEntries = os
      .list(dashboardsDir)
      .exists(p => os.isFile(p) && p.last.endsWith(".pkl"))
    if (!hasEntries) {
      val seeded = os
        .list(seedDir)
        .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
        .map { p => os.copy.into(p, dashboardsDir); p.last }
      log += s"seeded starter dashboards: ${seeded.mkString(", ")}"
    }

    // The lockfiles are generated artifacts; `PklBuild.staleLockfile` would
    // catch manifest edits by mtime, but layout migrations (this one included)
    // can leave a lockfile whose recorded shape no longer matches. Delete —
    // resolution is offline-cheap against the warm cache.
    List(
      dashboardsDir / "PklProject.deps.json",
      dashboardsDir / "home" / "PklProject.deps.json"
    ).foreach(p => if (os.exists(p)) os.remove(p))

    log.result()
  }

  /** Write `content` to `path` unless the user owns it: absent → write; the
    * old seeded form (recognized by `oldFormMarker`) → dated backup + write;
    * anything else is the user's (their own dependency declarations, their pin
    * bumps) and is left untouched.
    */
  private def writeManifest(
      path: os.Path,
      content: String,
      oldFormMarker: String
  ): List[String] =
    if (!os.exists(path)) {
      os.write(path, content)
      Nil
    } else if (os.read(path).contains(oldFormMarker)) {
      val target = backupPath(path)
      os.move(path, target)
      os.write(path, content)
      List(
        s"migrated: ${path.last} rewritten to the package-form dependency " +
          s"(previous version kept as ${target.last})"
      )
    } else Nil

  private def backupPath(original: os.Path): os.Path = {
    val date = LocalDateTime.now.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val simple = original / os.up / s"${original.last}.backup.$date"
    if (!os.exists(simple)) simple
    else {
      val time = LocalDateTime.now.format(DateTimeFormatter.ofPattern("HHmmss"))
      original / os.up / s"${original.last}.backup.$date-$time"
    }
  }

  private def consumerManifest(version: String, cacheDir: os.Path): String =
    """/// Your dashboards project. Seeded on first start; from then on it is yours.
      |///
      |/// It binds the two names your dashboards import:
      |///
      |///   @fh-dashboard  the authoring library that ships with the add-on — cards,
      |///                  the Home Assistant schema, themes. A versioned package,
      |///                  pre-cached by the add-on; the pin below names the version
      |///                  your dashboards build against. Upgrading the add-on does
      |///                  NOT move the pin — bump it yourself when you want the new
      |///                  library (the old version keeps working from the cache).
      |///   @fh-home       YOUR home: the `home/dump.pkl` typed entity dump, rebuilt
      |///                  from your live Home Assistant registry on every start.
      |///
      |/// So a dashboard reads:
      |///
      |///   amends "@fh-dashboard/entry.pkl"
      |///   import "@fh-dashboard/components.pkl" as c
      |///   import "@fh-home/dump.pkl" as dump
      |///
      |/// Third-party card packages are declared here too, next to @fh-dashboard.
      |/// Both names must stay bound — an entry that imports either one fails to
      |/// build without them.
      |amends "pkl:Project"
      |
      |/// Where resolved packages live — the add-on's persistent storage, shared by
      |/// the server and the /edit editor's pkl-lsp. Leave as is.
      |evaluatorSettings {
      |  moduleCacheDir = "@CACHE@"
      |}
      |
      |dependencies {
      |  ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@@V@" }
      |  ["fh-home"] = import("./home/PklProject")
      |}
      |""".stripMargin
      .replace("@CACHE@", cacheDir.toString)
      .replace("@V@", version)

  private def homeManifest(version: String): String =
    """/// Your home's data package — what `@fh-home` points at.
      |///
      |/// The add-on regenerates `dump.pkl` beside this file on every start, typed
      |/// against your live Home Assistant registry, so `dump.entities.<name>` is a
      |/// real, checked reference to a real device. Import it as:
      |///
      |///   import "@fh-home/dump.pkl" as dump
      |///
      |/// Don't edit `dump.pkl` — it is overwritten at every start. The
      |/// @fh-dashboard pin here must match the one in the main PklProject (the
      |/// dump reaches the schema through it — same package, same types).
      |amends "pkl:Project"
      |
      |package {
      |  name = "fh-home"
      |  baseUri = "package://fh.invalid/fh-home"
      |  version = "1.0.0"
      |  packageZipUrl = "https://fh.invalid/fh-home@\(version).zip"
      |}
      |
      |dependencies {
      |  ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@@V@" }
      |}
      |""".stripMargin.replace("@V@", version)
}
