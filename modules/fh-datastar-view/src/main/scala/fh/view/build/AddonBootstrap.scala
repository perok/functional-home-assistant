package fh.view.build

import io.circe.Json

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
  * pin bump. Nothing the user authored is ever moved or overwritten; the only
  * overwrite-with-backup is the machine-owned `.fh/pins.json` ([[Pins]]).
  *
  * `@fh-home` is a package too: the dump is a content-versioned cache package
  * (`fh-home@1.0.0-g<hash>`, [[DumpPackage]]), NOT a loose `home/dump.pkl`.
  * There is no `home/` folder in the workspace. Both pins are DATA in
  * `.fh/pins.json` ([[Pins]]); the static `.fh/base.pkl` just `read`s them.
  *
  * The committed scaffold is BYTE-IDENTICAL on every machine — the same
  * `.fh/base.pkl`, `PklProject`, and `.gitignore` the instance serves to a
  * laptop's `fh init` (see [[BaseManifest]] / the `/system/pkl/{base.pkl,
  * PklProject,gitignore}` routes). The two per-machine values — the pkl cache
  * dir and the instance URL the rewrite targets — live in a gitignored
  * `.fh/machine.json`, read by `base.pkl` the same way it reads `pins.json`.
  * That is what lets a user keep their dashboards in git and use the exact same
  * files on both sides: only `.fh/machine.json` differs (this instance fills it
  * with the persistent cache path + a loopback URL — inert, a cache hit — a
  * laptop with its own cache + the real instance URL).
  *
  * The files split machine-owned from user-owned along an `amends` chain
  * (spike-verified on 0.31.1: a PklProject can amend a local base module, the
  * child inherits its `dependencies` and `evaluatorSettings` and its own
  * mapping entries override the base's):
  *
  *   - `.fh/base.pkl` — machine-owned, STATIC and machine-agnostic: reads
  *     `machine.json` for `moduleCacheDir` + the `http.rewrites` target and
  *     `pins.json` for both alias pins, all via `pkl:json`. Rewritten only when
  *     this template changes across add-on versions.
  *   - `.fh/machine.json` — machine-owned `{ cacheDir, instanceUrl }`, the
  *     per-machine values, NEVER committed (the seeded `.gitignore` excludes
  *     it). Refreshed each start with this instance's cache + loopback URL.
  *   - `.fh/pins.json` — machine-owned `{ dashboardUri, homeUri, homeSha256 }`,
  *     the file rewritten as pins move: `dashboardUri` set to the bundled lib
  *     version every start, the home fields by [[DumpPackage.seedFromText]] per
  *     dump (a placeholder until the first dump).
  *   - `PklProject` — the user's, written ONCE when absent and never touched.
  *     It amends the base; with no `dependencies` block of its own the
  *     workspace tracks the bundled lib version, and the user may add a block
  *     to pin a version or declare third-party packages.
  *   - `.gitignore` — seeded once: excludes the per-machine + generated files
  *     (`.fh/machine.json`, caches, the lockfile, pins backups).
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
    *   the persistent package cache (`/data/pkl-cache`) — written into
    *   `.fh/machine.json` as `cacheDir`, which the static `base.pkl` reads for
    *   its `moduleCacheDir`, so pkl-lsp and the server resolve from the same
    *   place
    * @param loopbackUrl
    *   this instance's own URL (`http://127.0.0.1:<port>`) — written into
    *   `.fh/machine.json` as `instanceUrl`, the `http.rewrites` target. Inert
    *   on the instance (packages are cache hits), it makes the workspace
    *   copy-usable; a laptop's `fh init` overwrites it with the real URL.
    */
  def run(
      dashboardsDir: os.Path,
      bundledLib: os.Path,
      seedDir: os.Path,
      cacheDir: os.Path,
      loopbackUrl: String
  ): List[String] = {
    val bundledVersion = LibPackage.version(bundledLib)
    val log = List.newBuilder[String]

    log ++= LibPackage.seedCache(bundledLib, cacheDir)
    os.makeDir.all(dashboardsDir)

    // The static, machine-agnostic scaffold — byte-identical to what a laptop's
    // `fh init` fetches from this instance. The per-machine values it reads live
    // in `.fh/machine.json` (below).
    log ++= writeMachineFile(
      dashboardsDir / ".fh" / "base.pkl",
      BaseManifest
    )
    log ++= writeMachineFile(
      dashboardsDir / ".fh" / "machine.json",
      machineJson(cacheDir, loopbackUrl)
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
      os.write(dashboardsDir / "PklProject", ConsumerManifest)

    // A default `.gitignore` so a user who keeps this workspace in git commits
    // the byte-identical scaffold but not the per-machine / generated files.
    // Seeded once; the user's from then on.
    if (!os.exists(dashboardsDir / ".gitignore"))
      os.write(dashboardsDir / ".gitignore", GitignoreTemplate)

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

  /** The default package cache location — the cross-platform user DATA dir
    * under the SAME appdirs coordinates the `fh` script uses, so a local
    * instance, `BuildApp`, and a laptop `fh` all land in one place. The add-on
    * overrides it to its persistent `/data/pkl-cache` via `FH_PKL_CACHE_DIR`.
    * This is the value written into `.fh/machine.json`; it is NOT a `PklBuild`
    * fallback — a workspace whose `base.pkl` declares no `moduleCacheDir` is a
    * hard error.
    */
  def defaultCacheDir: String =
    s"${net.harawata.appdirs.AppDirsFactory.getInstance
        .getUserDataDir("fh", "0.0.1", "perok")}/pkl-cache"

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

  private val MachineOwnedMarker =
    "Machine-managed — regenerated at every add-on start"

  /** The static, machine-agnostic base manifest — BYTE-IDENTICAL on the
    * instance and on any laptop (`fh init` fetches this verbatim over
    * `/system/pkl/base.pkl`). It reads the two per-machine values (cache dir,
    * instance URL) from the gitignored `.fh/machine.json` and the two version
    * pins from `.fh/pins.json`, both via `pkl:json` — so this file itself never
    * carries a path or URL and never differs across machines.
    */
  val BaseManifest: String =
    s"""/// $MachineOwnedMarker; do not edit.
       |///
       |/// Your customizations belong in the `PklProject` that amends this file.
       |/// This one carries the add-on's wiring: where resolved packages live and
       |/// the two names your dashboards import —
       |///
       |///   @fh-dashboard  the authoring library that ships with the add-on — cards,
       |///                  the Home Assistant schema, themes. Its version tracks the
       |///                  add-on's bundled one unless you pin it in your PklProject.
       |///   @fh-home       YOUR home: a content-versioned package of the typed
       |///                  entity dump, rebuilt from your live Home Assistant
       |///                  registry.
       |///
       |/// This file is machine-AGNOSTIC and byte-identical everywhere. The two
       |/// per-machine values (the package cache dir and the instance URL the
       |/// rewrite targets) live in the gitignored sibling `machine.json`; the two
       |/// version pins live in `pins.json`. Both are read below via `pkl:json`.
       |amends "pkl:Project"
       |import "pkl:json"
       |
       |local class Machine { cacheDir: String; instanceUrl: String }
       |local class Pins { dashboardUri: String; homeUri: String; homeSha256: String }
       |
       |local machine: Machine = (new json.Parser {})
       |  .parse(read("machine.json"))
       |  .toTyped(Machine)
       |local pins: Pins = (new json.Parser {})
       |  .parse(read("pins.json"))
       |  .toTyped(Pins)
       |
       |evaluatorSettings {
       |  moduleCacheDir = machine.cacheDir
       |  http {
       |    rewrites {
       |      ["https://fh.invalid/"] = machine.instanceUrl + "/system/pkl/packages/"
       |    }
       |  }
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

  /** The per-machine `{ cacheDir, instanceUrl }` that `base.pkl` reads.
    * Gitignored, regenerated each start — never committed, never served.
    */
  private def machineJson(cacheDir: os.Path, instanceUrl: String): String =
    Json
      .obj(
        "cacheDir" -> Json.fromString(cacheDir.toString),
        "instanceUrl" -> Json.fromString(instanceUrl)
      )
      .spaces2 + "\n"

  /** Seeded once into a workspace a user may keep in git: commit the identical
    * scaffold + entries, ignore the per-machine + generated files.
    */
  val GitignoreTemplate: String =
    """# fh dashboards workspace (ADR 0010). The committed scaffold — .fh/base.pkl,
      |# PklProject, your *.pkl entries, and .fh/pins.json — is byte-identical on
      |# every machine. These are per-machine or generated; keep them out of git.
      |.fh/machine.json
      |.fh/cache/
      |.fh/pins.json.backup.*
      |PklProject.deps.json
      |""".stripMargin

  val ConsumerManifest: String =
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
