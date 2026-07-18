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
  * pin bump. A pre-package-form install's seeded `lib/` copy is renamed to a
  * dated backup, never deleted.
  *
  * `@fh-home` is a package too: the dump is a content-versioned cache package
  * (`fh-home@1.0.0-g<hash>`, [[DumpPackage]]), NOT a loose `home/dump.pkl`.
  * There is no `home/` folder in the workspace. Both pins are DATA in
  * `.fh/pins.json` ([[Pins]]); the static `.fh/base.pkl` just `read`s them.
  *
  * The files split machine-owned from user-owned along an `amends` chain
  * (spike-verified on 0.31.1: a PklProject can amend a local base module, the
  * child inherits its `dependencies` and `evaluatorSettings` and its own
  * mapping entries override the base's):
  *
  *   - `.fh/base.pkl` — machine-owned, STATIC: the cache location and both
  *     alias bindings, each reading its pin from `pins.json` via `pkl:json`.
  *     Rewritten only when this template changes across add-on versions.
  *   - `.fh/pins.json` — machine-owned `{ dashboardUri, homeUri, homeSha256 }`,
  *     the ONE file rewritten as pins move: `dashboardUri` set to the bundled
  *     lib version every start, the home fields by [[DumpPackage.seedFromText]]
  *     per dump (a placeholder until the first dump).
  *   - `PklProject` — the user's, written ONCE when absent and never touched.
  *     It amends the base; with no `dependencies` block of its own the
  *     workspace tracks the bundled lib version, and the user may add a block
  *     to pin a version or declare third-party packages.
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
      baseManifest(cacheDir)
    )

    // The static base.pkl `read`s `.fh/pins.json`. Set the `@fh-dashboard` pin to
    // the bundled version (so the workspace tracks add-on upgrades), and seed the
    // `@fh-home` placeholder if this workspace has never packaged a dump — so the
    // `read` resolves for the `Project.loadFromPath` that precedes the first
    // `prepareDumps`, which then moves the home pin. Machine data; never backed
    // up. A prior real dump pin survives (read-modify-write preserves it).
    Pins.seedBootstrap(dashboardsDir, LibPackage.packageUri(bundledVersion))

    // The consumer manifest is the user's file: written ONCE, only when absent,
    // then never touched. It amends `.fh/base.pkl`, which supplies the
    // `@fh-dashboard` default — so with no `dependencies` block of its own a
    // fresh workspace tracks the bundled version until the user adds a pin here.
    if (!os.exists(dashboardsDir / "PklProject"))
      os.write(dashboardsDir / "PklProject", consumerManifest)

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

    // The lockfile is a generated artifact; `PklBuild.staleLockfile` would
    // catch consumer-manifest edits by mtime, but a refreshed base manifest
    // doesn't move the consumer's mtime. Delete — resolution is offline-cheap
    // against the warm cache.
    if (os.exists(dashboardsDir / "PklProject.deps.json"))
      os.remove(dashboardsDir / "PklProject.deps.json")

    log.result()
  }

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

  /** `name.backup.<ISO-date>` beside the original (`-HHmmss` appended on a
    * same-day collision) — the one backup-naming convention every migration and
    * tool shares ([[DumpRefresh]] uses it for the replaced dump).
    */
  private[build] def backupPath(original: os.Path): os.Path = {
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

  private def baseManifest(cacheDir: os.Path): String =
    s"""/// $MachineOwnedMarker; do not edit.
       |///
       |/// Your customizations belong in the `PklProject` that amends this file.
       |/// This one carries the add-on's wiring: where resolved packages live (the
       |/// add-on's persistent storage, shared by the server and the /edit editor's
       |/// pkl-lsp) and the two names your dashboards import —
       |///
       |///   @fh-dashboard  the authoring library that ships with the add-on — cards,
       |///                  the Home Assistant schema, themes. Its version tracks the
       |///                  add-on's bundled one unless you pin it in your PklProject.
       |///   @fh-home       YOUR home: a content-versioned package of the typed
       |///                  entity dump, rebuilt from your live Home Assistant
       |///                  registry.
       |///
       |/// Both pins are DATA in the sibling `pins.json`, rewritten by the add-on
       |/// (the lib version at each start, the dump snapshot whenever the home
       |/// changes); this file just reads them, so it never changes per-dump.
       |amends "pkl:Project"
       |import "pkl:json"
       |
       |local class Pins { dashboardUri: String; homeUri: String; homeSha256: String }
       |local pins: Pins = (new json.Parser {})
       |  .parse(read("pins.json"))
       |  .toTyped(Pins)
       |
       |evaluatorSettings {
       |  moduleCacheDir = "@CACHE@"
       |}
       |
       |dependencies {
       |  ["fh-dashboard"] { uri = pins.dashboardUri }
       |  ["fh-home"] {
       |    uri = pins.homeUri
       |    checksums { sha256 = pins.homeSha256 }
       |  }
       |}
       |""".stripMargin
      .replace("@CACHE@", cacheDir.toString)

  private def consumerManifest: String =
    """/// Your dashboards project. Written ONCE, when this workspace is first
      |/// seeded, and never touched again — it is yours. (The add-on's own wiring
      |/// lives in `.fh/base.pkl`, which this file amends; that one is regenerated
      |/// at every start.)
      |///
      |/// Entries in this directory import the library and this home's dump:
      |///
      |///   amends "@fh-dashboard/entry.pkl"
      |///   import "@fh-dashboard/components.pkl" as c
      |///   import "@fh-home/dump.pkl" as dump
      |///
      |/// By default your dashboards track the @fh-dashboard version the add-on
      |/// bundles. To PIN a specific version — so an add-on upgrade never moves
      |/// what you build against — or to add a third-party card package, add a
      |/// `dependencies` block that overrides the base default, e.g.:
      |///
      |///   dependencies {
      |///     ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@1.0.0" }
      |///     // ["their-cards"] { uri = "package://pkg.pkl-lang.org/.../1.0.0" }
      |///   }
      |amends ".fh/base.pkl"
      |""".stripMargin
}
