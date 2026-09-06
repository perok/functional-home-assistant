package fh.view.build

import io.circe.Json

/** Add-on boot: bring the user's dashboards workspace to a state the server can
  * evaluate, without ever owning the user's files (ADR 0010, "the add-on
  * workspace").
  *
  * The library is NOT copied into the workspace. The bundled lib — streamed
  * from the running jar's own resources ([[BundledLib]]) — is packaged by
  * [[LibPackage]] into the persistent package cache, and the workspace depends
  * on it as `package://fh.invalid/fh-dashboard@<version>` — so the user's dir
  * holds only user files, a runtime upgrade never touches the user's pin (the
  * old version keeps resolving from the cache), and a LIB upgrade is the user's
  * deliberate pin bump. Nothing the user authored is ever moved or overwritten;
  * the only overwrite-with-backup is the machine-owned `.fh/pins.json`
  * ([[Pins]]).
  *
  * `@fh-home` is a package too: the dump is a content-versioned cache package
  * (`fh-home@1.0.0-g<hash>`, [[DumpPackage]]), NOT a loose `home/dump.pkl`.
  * There is no `home/` folder in the workspace. Both pins are DATA in
  * `.fh/pins.json` ([[Pins]]); the static `.fh/base.pkl` just `read`s them.
  *
  * The committed scaffold is BYTE-IDENTICAL on every machine — the same
  * `.fh/base.pkl`, `PklProject`, and `.gitignore` the instance serves to a
  * laptop's `fh init` (see [[BaseManifest]] / the `/system/pkl/{base.pkl,
  * PklProject,gitignore}` routes). The two per-reader values — the pkl cache
  * dir and the instance URL the rewrite targets — come from the ENVIRONMENT of
  * whoever is reading (`FH_PKL_CACHE_DIR`, `FH_INSTANCE_URL`), falling back to
  * a gitignored `.fh/machine.json` for a reader with no launcher to set them.
  *
  * **The instance writes neither.** One dashboards directory is meant to be
  * shared — an HA device, the author's laptop, a dev container — and each of
  * them needs different answers, so a per-machine value written into the shared
  * directory is a value imposed on everyone else. That is not hypothetical: the
  * add-on used to write its own cache path and loopback URL at every start, and
  * a container reading them failed every dashboard with pkl's own
  * "AccessDeniedException: /home/<someone>".
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
  *   - `.fh/machine.json` — the READER's own `{ cacheDir?, instanceUrl? }`,
  *     both optional, NEVER committed (the seeded `.gitignore` excludes it) and
  *     never written here: a laptop's `fh init` writes its own, and the
  *     environment wins over it wherever there is a launcher to set one.
  *   - `.fh/pins.json` — machine-owned `{ dashboardUri, homeUri, homeSha256 }`,
  *     born real-or-not-at-all: it does NOT exist until the first dump
  *     ([[DumpPackage.seedFromText]] writes all three keys at once). From then
  *     on [[Pins.seedBootstrap]] refreshes `dashboardUri` to the bundled lib
  *     version each start and the home fields move per dump. There is NO
  *     placeholder pin — before the first dump there is no file.
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
  *
  * FIRST-BOOT ORDERING. This step deliberately does NOT create `pins.json`: on
  * a fresh workspace it stays absent until the first dump is packaged. So
  * between this bootstrap and the first `prepareDumps` there is a window where
  * `base.pkl` exists but `pins.json` doesn't, and the project is therefore not
  * loadable. Nothing loads it in that window — evaluation, `/edit`, and pkl-lsp
  * all start AFTER `prepareDumps` seeds the dump and writes the real pins. The
  * first dump seed can't derive its `@fh-dashboard` pin from the (unloadable)
  * project, so the caller passes down the same `bundledLib` artifacts it handed
  * here.
  */
object AddonBootstrap {

  /** @param dashboardsDir
    *   the user's workspace (`/homeassistant/fh-dashboards`)
    * @param bundledLib
    *   the bundled `@fh-dashboard` artifacts — [[BundledLib.artifacts]]
    *   streamed from the running jar in production, `LibPackage.build(dir)` in
    *   tests
    * @param cacheDir
    *   the persistent package cache to seed (`/data/pkl-cache` on the add-on).
    *   NOT written into the workspace: `base.pkl` reads `FH_PKL_CACHE_DIR`
    *   itself, and this is the same value resolved by the same rule, so what we
    *   seed and what evaluation resolves cannot diverge
    */
  def run(
      dashboardsDir: os.Path,
      bundledLib: LibPackage.Artifacts,
      cacheDir: os.Path
  ): List[String] = {
    val bundledVersion = bundledLib.version
    val log = List.newBuilder[String]

    requireUsableCacheDir(cacheDir)
    os.makeDir.all(dashboardsDir)

    // The static, machine-agnostic scaffold — byte-identical to what a laptop's
    // `fh init` fetches from this instance. This is the ONLY scaffold file the
    // instance writes: the values that used to sit beside it in `machine.json`
    // come from this process's environment now, so a workspace shared with a
    // laptop or a dev container is never handed another machine's paths.
    log ++= writeMachineFile(
      dashboardsDir / ".fh" / "base.pkl",
      BaseManifest
    )

    log ++= LibPackage.seedCache(bundledLib, cacheDir)

    // Refresh the `@fh-dashboard` pin to the bundled version (so the workspace
    // tracks add-on upgrades) — but ONLY if `pins.json` already exists. On a
    // fresh workspace it does not, and this is a no-op: pins.json is born with
    // the first dump (`prepareDumps` → writeHome), all three keys real at once.
    // There is no placeholder. A prior real dump pin survives (read-modify-write
    // preserves the home fields). Machine data; never backed up on a lib bump.
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

    // A starter site only when there is no entrypoint at all — it is the user's
    // file from the moment it exists (a workspace with other `*.pkl` modules
    // but no entrypoint still gets one; those modules are not dashboards until
    // it names them). Bundled straight into the jar's own resources, so there
    // is no seed directory to keep in sync or copy into the image.
    if (!os.exists(dashboardsDir / Site.EntryFile)) {
      os.write(dashboardsDir / Site.EntryFile, starterSite)
      log += s"seeded starter site: ${Site.EntryFile}"
      // Loose `*.pkl` files in a workspace that had no entrypoint are modules,
      // not dashboards (ADR 0021) — nothing is moved or rewritten, and serving
      // one is a key away. Say so HERE, where their names are known, rather
      // than leaving the user to infer it from an instance that looks empty.
      val loose = os
        .list(dashboardsDir)
        .filter(p =>
          os.isFile(p) && p.last.endsWith(".pkl") && p.last != Site.EntryFile
        )
        .map(_.last)
        .sorted
      if (loose.nonEmpty)
        log +=
          s"${loose.length} other *.pkl here are ordinary modules " +
            s"(${loose.mkString(", ")}) — serve one by naming it in " +
            s"${Site.EntryFile}: dashboards { [\"home\"] = import(\"${loose.head}\") }"
    }

    // The lockfile is a generated artifact; `PklBuild.staleLockfile` would
    // catch consumer-manifest edits by mtime, but a refreshed base manifest
    // doesn't move the consumer's mtime. Delete — resolution is offline-cheap
    // against the warm cache.
    val lockfile = dashboardsDir / "PklProject.deps.json"
    if (os.exists(lockfile)) { val _ = os.remove(lockfile) }

    log.result()
  }

  /** The package cache this workspace resolves through, WITHOUT loading the
    * project — `FH_PKL_CACHE_DIR`, else `.fh/machine.json`'s `cacheDir`, else
    * pkl's own default. The same three steps `base.pkl` performs, in the same
    * order, which is the point: the dir we seed into and the dir pkl resolves
    * from are one value derived twice, so they cannot drift.
    *
    * Needed projectless because the first dump seed runs before `pins.json`
    * exists — the project is unloadable then, yet the seed must land in the
    * real cache ([[DumpPackage.seedFromText]]).
    */
  def effectiveCacheDir(dashboardsDir: os.Path): os.Path =
    sys.env
      .get("FH_PKL_CACHE_DIR")
      .orElse(machineFileCacheDir(dashboardsDir))
      .map(os.Path(_))
      .getOrElse(os.Path(defaultCacheDir))

  private def machineFileCacheDir(dashboardsDir: os.Path): Option[String] = {
    val file = dashboardsDir / ".fh" / "machine.json"
    Option
      .when(os.exists(file))(os.read(file))
      .flatMap(io.circe.parser.parse(_).toOption)
      .flatMap(_.hcursor.get[String]("cacheDir").toOption)
  }

  /** The `.fh/machine.json` a reader with no launcher to set env vars writes
    * for itself: a laptop's `fh init`, and the test harness. The instance
    * writes it NEVER — that is what makes one workspace usable from several
    * machines at once. Both keys are optional; an absent one falls through to
    * the default in `base.pkl`.
    */
  def machineFileJson(
      cacheDir: Option[os.Path],
      instanceUrl: Option[String]
  ): String =
    Json
      .obj(
        (cacheDir.map(d => "cacheDir" -> Json.fromString(d.toString)).toList ++
          instanceUrl.map("instanceUrl" -> Json.fromString(_)).toList)*
      )
      .spaces2 + "\n"

  /** The default package cache location — **pkl's own** (`~/.pkl/cache`), asked
    * of `pkl-core` rather than derived here, so the server, a laptop `fh`, the
    * `pkl` CLI and pkl-lsp all land in one cache without anyone declaring it.
    * The add-on overrides it to its persistent `/data/pkl-cache` via
    * `FH_PKL_CACHE_DIR`, because in that container this default is
    * `/root/.pkl/cache` — an image layer, wiped by every add-on update.
    *
    * This is the value written into `.fh/machine.json`; it is NOT a `PklBuild`
    * fallback — a workspace whose `base.pkl` declares no `moduleCacheDir` is a
    * hard error.
    *
    * It replaced an appdirs data dir of our own (`~/.local/share/fh/…`). Two
    * reasons, one of them a bug: appdirs reads `XDG_DATA_HOME`, so a leaked
    * value pointed a container at a home directory it could not access, and a
    * path we invent is one pkl-lsp does not share.
    */
  def defaultCacheDir: String =
    // org.pkl.core.util is pkl's own internals, not its published API — the
    // pin in build.sbt is what keeps this honest, and a move breaks the build
    // rather than silently relocating everyone's cache.
    org.pkl.core.util.IoUtils.getDefaultModuleCacheDir().toString

  /** Fail on an unusable package cache dir HERE, naming it, rather than letting
    * pkl report it once per dashboard as an I/O error against a module URI.
    * Everything downstream — the lib seed, the dump package, every `import
    * "@fh-dashboard/…"` — resolves through this directory, so there is no
    * degraded mode worth starting in.
    */
  private def requireUsableCacheDir(cacheDir: os.Path): Unit =
    try os.makeDir.all(cacheDir)
    catch {
      case e: java.io.IOException =>
        sys.error(
          s"pkl package cache dir is not usable: $cacheDir (${e.getClass.getSimpleName}: ${e.getMessage}). " +
            "Set FH_PKL_CACHE_DIR to a writable path — the add-on uses its persistent /data/pkl-cache."
        )
    }

  private val StarterSiteResource = "dashboards/site_default.pkl"

  /** The starter SITE's text, read straight off the running jar's own classpath
    * resources — the same [[BundledLib]] sourcing style, but for a single file
    * rather than a whole directory. Public so tests can assert against the
    * exact seeded content without a seed directory of their own.
    */
  def starterSite: String = {
    val cl = Option(getClass.getClassLoader).getOrElse(
      ClassLoader.getSystemClassLoader
    )
    val is = Option(cl.getResourceAsStream(StarterSiteResource))
      .getOrElse(
        sys.error(
          s"starter site not on the classpath ($StarterSiteResource missing)"
        )
      )
    try new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally is.close()
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

  private val MachineOwnedMarker =
    "Machine-managed — regenerated at every add-on start"

  /** The static, machine-agnostic base manifest — BYTE-IDENTICAL on the
    * instance and on any laptop (`fh init` fetches this verbatim over
    * `/system/pkl/base.pkl`). It never carries a path or URL of its own: the
    * version pins come from `.fh/pins.json`, and the two per-reader values come
    * from THIS PROCESS's environment first, `.fh/machine.json` second.
    *
    * That order is what makes one workspace usable from several machines at
    * once — the case that broke it before: the add-on wrote its own cache dir
    * and loopback URL into `machine.json` at every start, so a directory shared
    * with a laptop or a dev container handed them a path that does not exist
    * there and an instance URL that is not reachable from there. The add-on now
    * writes nothing per-machine; `run.sh` gives it `FH_PKL_CACHE_DIR` and
    * `FH_INSTANCE_URL`, and `machine.json` belongs to whoever has no launcher
    * to set those — a laptop, written once by `fh init`.
    *
    * Spiked on the 0.32.1 pin, and each of these decides a case:
    *   - `read?` yields null for a missing env var OR a missing file, so an
    *     absent `moduleCacheDir` means "the reading tool's default" —
    *     `~/.pkl/cache`, which pkl-lsp and the `pkl` CLI already use.
    *   - `read?("env:…")` returns a **String**, not a `Resource` (`.text` on it
    *     is "Cannot find property `text` in object of type `String`").
    *   - `??` short-circuits and properties are lazy, so with the env set the
    *     file is never read — verified with a machine.json of pure garbage.
    *   - `toTyped` tolerates EXTRA properties, so a `machine.json` from an
    *     older add-on (carrying `cacheDir`) still parses; its stale cache path
    *     is simply ignored.
    *   - the stdlib allows `moduleCacheDir` only on a file-based project
    *     (`(moduleCacheDir != null).implies(isFileBasedProject)`), which a
    *     workspace is and a package never is.
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
       |/// This file is machine-AGNOSTIC and byte-identical everywhere. It carries
       |/// no path and no URL: the two per-reader values are read from THIS
       |/// process's environment first, the gitignored sibling `machine.json`
       |/// second, and the two version pins live in `pins.json`.
       |///
       |///   FH_PKL_CACHE_DIR  where resolved packages live. Unset, and with no
       |///                     `cacheDir` in machine.json, this is pkl's own
       |///                     `~/.pkl/cache` — the one your pkl-lsp and `pkl`
       |///                     CLI already use, so nothing has to be declared.
       |///   FH_INSTANCE_URL   the instance whose `/system/pkl/packages/` serves
       |///                     `@fh-dashboard` and `@fh-home`. With neither this
       |///                     nor an `instanceUrl`, there is no rewrite at all
       |///                     and packages resolve from the cache alone.
       |///
       |/// So the SAME directory works from the add-on, your laptop and a dev
       |/// container at once: each supplies its own two values, and none of them
       |/// writes the others' into a file the rest have to live with.
       |amends "pkl:Project"
       |import "pkl:json"
       |
       |local class Machine { cacheDir: String? = null; instanceUrl: String? = null }
       |local class Pins { dashboardUri: String; homeUri: String; homeSha256: String }
       |
       |// `read?` is null for an absent file, and `??` short-circuits, so with
       |// both env vars set this is never parsed. Either key may be absent.
       |local machineText = read?("machine.json")
       |local machine: Machine? =
       |  if (machineText == null) null
       |  else (new json.Parser {}).parse(machineText).toTyped(Machine)
       |local pins: Pins = (new json.Parser {})
       |  .parse(read("pins.json"))
       |  .toTyped(Pins)
       |
       |local instanceUrl: String? =
       |  read?("env:FH_INSTANCE_URL") ?? machine?.instanceUrl
       |
       |evaluatorSettings {
       |  // Null is not "no cache": it leaves pkl's own default standing.
       |  moduleCacheDir = read?("env:FH_PKL_CACHE_DIR") ?? machine?.cacheDir
       |  http {
       |    rewrites {
       |      when (instanceUrl != null) {
       |        ["https://fh.invalid/"] = instanceUrl!! + "/system/pkl/packages/"
       |      }
       |    }
       |  }
       |  // pkl's defaults plus THIS instance. From 0.32 the resource allowlist
       |  // is checked against the REWRITTEN url, and the rewrite above targets
       |  // your instance on the LAN, which has no certificate to serve — so
       |  // without an http entry every package fetch is refused. Setting the
       |  // field REPLACES the defaults, hence the full list.
       |  //
       |  // Entries are REGEXES matched against the url, so the last one grants
       |  // exactly your instance rather than http at large: anchored at the
       |  // start, with each dot rewritten to the class `[.]` so it matches a
       |  // literal dot and not any character. (`///` is a doc comment — a parse
       |  // error in an amend body — hence `//` here.)
       |  allowedResources {
       |    "prop:"
       |    "env:"
       |    "file:"
       |    "modulepath:"
       |    "package:"
       |    "projectpackage:"
       |    "https:"
       |    when (instanceUrl != null) {
       |      "^" + instanceUrl!!.replaceAll(".", "[.]") + "/"
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
      |
      |# Credentials (issue #89). Both hold Home Assistant tokens and are
      |# written 0600; that they live here at all is issue #165.
      |.fh/sessions.json
      |.fh/user_secret.json
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
      |///     ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@1.0.0-g0123456789ab" }
      |///     // ["their-cards"] { uri = "package://pkg.pkl-lang.org/.../1.0.0" }
      |///   }
      |amends ".fh/base.pkl"
      |""".stripMargin
}
