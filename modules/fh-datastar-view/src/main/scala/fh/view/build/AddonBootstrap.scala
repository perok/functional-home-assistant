package fh.view.build

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Add-on boot: bring the user's dashboards workspace to a state the server can
  * evaluate, without ever owning the user's files (ADR 0010, "the add-on
  * workspace").
  *
  * The library is NOT copied into the workspace. The bundled `lib/` (baked into
  * the image) is packaged by [[LibPackage]] into the persistent package cache,
  * and the workspace depends on it as
  * `package://fh.invalid/fh-dashboard@<version>` — so the user's dir holds only
  * user files, a runtime upgrade never touches the user's pin (the old version
  * keeps resolving from the cache), and a LIB upgrade is the user's deliberate
  * pin bump. Pre-package-form installs are migrated: their seeded `lib/` copy
  * and manifests are renamed to dated backups, never deleted.
  *
  * The manifests split machine-owned from user-owned along an `amends` chain
  * (spike-verified on 0.31.1: a PklProject can amend a local base module, the
  * child inherits its `dependencies` and `evaluatorSettings` and its own
  * mapping entries override the base's):
  *
  *   - `.fh/base.pkl` — machine-owned, refreshed every start: the cache
  *     location, the `@fh-home` binding, and a *default* `@fh-dashboard` pin at
  *     the bundled version. Add-on internals can change here without touching
  *     anything the user wrote.
  *   - `PklProject` — the user's from first boot, never rewritten: it amends
  *     the base and holds the user's pin (seeded at the then-bundled version;
  *     the override wins, so add-on upgrades never move it — deleting the entry
  *     opts into tracking the bundled version) plus any third-party packages.
  *   - `home/PklProject` — machine-owned like the `dump.pkl` beside it,
  *     regenerated every start with its `@fh-dashboard` pin synced from the
  *     user's manifest (the dump and the entries must resolve the schema to ONE
  *     cached artifact or card factories are type errors).
  *
  * Idempotent; called on every start, before anything evaluates. Pure
  * side-effecting file work — the caller wraps it in `IO.blocking` and prints
  * the returned action log.
  */
object AddonBootstrap {

  /** @param dashboardsDir
    *   the user's workspace (`/homeassistant/fh-dashboards`)
    * @param bundledLib
    *   the image's library (`/opt/fh/lib`), read-only
    * @param seedDir
    *   starter entries copied on first boot only
    * @param cacheDir
    *   the persistent package cache (`/data/pkl-cache`) — written into the
    *   generated base's `moduleCacheDir`, so pkl-lsp and the server resolve
    *   from the same place
    */
  def run(
      dashboardsDir: os.Path,
      bundledLib: os.Path,
      seedDir: os.Path,
      cacheDir: os.Path
  ): List[String] = {
    val bundledVersion = LibPackage.version(bundledLib)
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

    log ++= writeMachineFile(
      dashboardsDir / ".fh" / "base.pkl",
      baseManifest(bundledVersion, cacheDir)
    )

    // The consumer manifest is the user's from the moment it exists. Any
    // machine-era form (it amends "pkl:Project" directly — both the original
    // path-form and the interim single-file package form did) is migrated to
    // the amends-the-base shape with a dated backup, so nothing a user added
    // to one is lost, just relocated — and a pin the old form carried is
    // carried over, not reset to the bundled version.
    val preMigrationPin = pinnedVersion(dashboardsDir / "PklProject")
    log ++= migrateOrSeedConsumer(
      dashboardsDir / "PklProject",
      consumerManifest(preMigrationPin.getOrElse(bundledVersion))
    )

    // The home manifest is machine-owned (like the dump.pkl beside it), its
    // pin synced from wherever the user's manifest points. Read it textually —
    // evaluating the project here would need the home manifest to exist first.
    val pin = pinnedVersion(dashboardsDir / "PklProject")
      .getOrElse(bundledVersion)
    log ++= writeHomeManifest(
      dashboardsDir / "home" / "PklProject",
      homeManifest(pin)
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
    // catch consumer-manifest edits by mtime, but a refreshed base or home
    // manifest doesn't move the consumer's mtime. Delete — resolution is
    // offline-cheap against the warm cache.
    List(
      dashboardsDir / "PklProject.deps.json",
      dashboardsDir / "home" / "PklProject.deps.json"
    ).foreach(p => if (os.exists(p)) os.remove(p))

    log.result()
  }

  /** The user's effective `@fh-dashboard` pin, read textually from their
    * manifest (absent when they deleted the entry to track the bundled
    * version).
    */
  private def pinnedVersion(consumerManifest: os.Path): Option[String] =
    Option
      .when(os.exists(consumerManifest))(os.read(consumerManifest))
      .flatMap(LibPackage.pinnedVersion)

  /** A machine-owned file: written when absent or stale, never backed up (its
    * header says so, and nothing user-authored ever lives in it).
    */
  private def writeMachineFile(path: os.Path, content: String): List[String] =
    if (os.exists(path) && os.read(path) == content) Nil
    else {
      val existed = os.exists(path)
      os.makeDir.all(path / os.up)
      os.write.over(path, content)
      if (existed) List(s"refreshed ${path.last}") else Nil
    }

  private def migrateOrSeedConsumer(
      path: os.Path,
      content: String
  ): List[String] =
    if (!os.exists(path)) {
      os.write(path, content)
      Nil
    } else if (os.read(path).contains("amends \"pkl:Project\"")) {
      val target = backupPath(path)
      os.move(path, target)
      os.write(path, content)
      List(
        s"migrated: ${path.last} rewritten to amend .fh/base.pkl " +
          s"(previous version kept as ${target.last} — re-add any dependencies " +
          "you had declared there)"
      )
    } else Nil

  /** The home manifest predates its machine-owned status (the interim package
    * form seeded it once and then treated it as the user's) — a legacy form
    * gets one dated backup on the way over; from then on it is overwritten
    * freely, like the dump.
    */
  private def writeHomeManifest(
      path: os.Path,
      content: String
  ): List[String] =
    if (os.exists(path) && !os.read(path).contains(MachineOwnedMarker)) {
      val target = backupPath(path)
      os.move(path, target)
      os.write(path, content)
      List(
        s"migrated: home/${path.last} is machine-managed now " +
          s"(previous version kept as ${target.last})"
      )
    } else writeMachineFile(path, content).map(l => s"$l (home/)")

  private def backupPath(original: os.Path): os.Path = {
    val date = LocalDateTime.now.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val simple = original / os.up / s"${original.last}.backup.$date"
    if (!os.exists(simple)) simple
    else {
      val time = LocalDateTime.now.format(DateTimeFormatter.ofPattern("HHmmss"))
      original / os.up / s"${original.last}.backup.$date-$time"
    }
  }

  private val MachineOwnedMarker =
    "Machine-managed — regenerated at every add-on start"

  private def baseManifest(version: String, cacheDir: os.Path): String =
    s"""/// $MachineOwnedMarker; do not edit.
       |///
       |/// Your customizations belong in the `PklProject` that amends this file.
       |/// This one carries the add-on's wiring: where resolved packages live (the
       |/// add-on's persistent storage, shared by the server and the /edit editor's
       |/// pkl-lsp) and the two names your dashboards import —
       |///
       |///   @fh-dashboard  the authoring library that ships with the add-on — cards,
       |///                  the Home Assistant schema, themes. The version here is the
       |///                  add-on's bundled one; the pin in your PklProject overrides
       |///                  it, so add-on upgrades never move what your dashboards
       |///                  build against.
       |///   @fh-home       YOUR home: the `home/dump.pkl` typed entity dump, rebuilt
       |///                  from your live Home Assistant registry on every start.
       |amends "pkl:Project"
       |
       |evaluatorSettings {
       |  moduleCacheDir = "@CACHE@"
       |}
       |
       |dependencies {
       |  ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@@V@" }
       |  ["fh-home"] = import("../home/PklProject")
       |}
       |""".stripMargin
      .replace("@CACHE@", cacheDir.toString)
      .replace("@V@", version)

  private def consumerManifest(version: String): String =
    """/// Your dashboards project. Seeded on first start; from then on it is yours —
      |/// the add-on never rewrites it. (The add-on's own wiring lives in
      |/// `.fh/base.pkl`, which this file amends; that one is regenerated at every
      |/// start.)
      |///
      |/// The pin below names the @fh-dashboard library version your dashboards
      |/// build against:
      |///
      |///   amends "@fh-dashboard/entry.pkl"
      |///   import "@fh-dashboard/components.pkl" as c
      |///   import "@fh-home/dump.pkl" as dump
      |///
      |/// Upgrading the add-on does NOT move the pin — bump it yourself when you
      |/// want the new library (the old version keeps working from the cache).
      |/// Deleting the entry makes your dashboards track whatever version the
      |/// add-on bundles.
      |///
      |/// Third-party card packages are declared here too, next to @fh-dashboard.
      |amends ".fh/base.pkl"
      |
      |dependencies {
      |  ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@@V@" }
      |}
      |""".stripMargin.replace("@V@", version)

  private def homeManifest(version: String): String =
    s"""/// $MachineOwnedMarker; do not edit.
       |///
       |/// Your home's data package — what `@fh-home` points at. The add-on
       |/// regenerates `dump.pkl` beside this file on every start, typed against
       |/// your live Home Assistant registry, so `dump.entities.<name>` is a real,
       |/// checked reference to a real device. Import it as:
       |///
       |///   import "@fh-home/dump.pkl" as dump
       |///
       |/// The @fh-dashboard pin here is synced from your main PklProject at every
       |/// start (the dump reaches the schema through it — same package, same
       |/// types).
       |amends "pkl:Project"
       |
       |package {
       |  name = "fh-home"
       |  baseUri = "package://fh.invalid/fh-home"
       |  version = "1.0.0"
       |  packageZipUrl = "https://fh.invalid/fh-home@\\(version).zip"
       |}
       |
       |dependencies {
       |  ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@@V@" }
       |}
       |""".stripMargin.replace("@V@", version)
}
