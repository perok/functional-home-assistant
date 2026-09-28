package fh.view.build

import io.circe.Json

/** Brings the workspace to an evaluable state on every start, without owning
  * the user's files (ADR 0010). The lib and the dump are cache packages, not
  * files in the workspace; their pins are data in `.fh/pins.json` ([[Pins]]).
  *
  * The committed scaffold is byte-identical on every machine (what `fh init`
  * fetches). The two per-reader values — cache dir and instance URL — come from
  * the reader's environment, else its own gitignored `.fh/machine.json`. '''The
  * instance writes neither''': it once wrote its own, and a dev container
  * sharing the workspace failed every dashboard with
  * `AccessDeniedException: /home/<someone>`.
  *
  * `PklProject` (the user's, written once) amends `.fh/base.pkl`
  * (machine-owned, rewritten only when the template changes).
  *
  * '''First boot''': `pins.json` does not exist until the first dump, so the
  * project is unloadable until `prepareDumps` runs; nothing loads it before.
  * The caller hands the same `bundledLib` down to that first seed.
  */
object AddonBootstrap {

  /** @param cacheDir
    *   not written into the workspace: `base.pkl` resolves the same value by
    *   the same rule
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

    log ++= writeMachineFile(
      dashboardsDir / ".fh" / "base.pkl",
      BaseManifest
    )

    log ++= LibPackage.seedCache(bundledLib, cacheDir)

    // A no-op until the first dump creates `pins.json`.
    Pins.seedBootstrap(dashboardsDir, LibPackage.packageUri(bundledVersion))

    if (!os.exists(dashboardsDir / "PklProject"))
      os.write(dashboardsDir / "PklProject", ConsumerManifest)

    if (!os.exists(dashboardsDir / ".gitignore"))
      os.write(dashboardsDir / ".gitignore", GitignoreTemplate)

    if (!os.exists(dashboardsDir / Site.EntryFile)) {
      os.write(dashboardsDir / Site.EntryFile, starterSite)
      log += s"seeded starter site: ${Site.EntryFile}"
      // Loose `*.pkl` are modules, not dashboards (ADR 0021); say so here,
      // where their names are known.
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

    // A refreshed base does not move the consumer's mtime, which is all
    // `PklBuild.staleLockfile` sees; re-resolving is cheap.
    val lockfile = dashboardsDir / "PklProject.deps.json"
    if (os.exists(lockfile)) { val _ = os.remove(lockfile) }

    log.result()
  }

  /** `base.pkl`'s three steps in the same order, without loading the project,
    * which is unloadable before the first dump.
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

  /** For a reader with no launcher (a laptop's `fh init`, tests); never the
    * instance.
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

  /** pkl's own `~/.pkl/cache`, shared with the CLI and pkl-lsp. The add-on
    * overrides it: in the container it is an image layer, wiped per update. Not
    * an appdirs path: that read `XDG_DATA_HOME`, and a leaked value pointed a
    * container at an inaccessible home.
    */
  def defaultCacheDir: String =
    // pkl internals, not its API; the build.sbt pin keeps this honest.
    org.pkl.core.util.IoUtils.getDefaultModuleCacheDir().toString

  /** Here, naming it, rather than as an I/O error per dashboard. */
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

  // Never backed up: nothing user-authored lives in it.
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

  /** Served verbatim to `fh init`. Spiked on 0.32.1:
    *   - `read?` is null for a missing env var or file, so an absent
    *     `moduleCacheDir` means the tool's own default.
    *   - `read?("env:…")` is a String, not a `Resource` (no `.text`).
    *   - `??` short-circuits and properties are lazy: with the env set, a
    *     garbage `machine.json` is never read.
    *   - `toTyped` tolerates extra properties, so an older `machine.json`
    *     parses.
    *   - `moduleCacheDir` is allowed only on a file-based project.
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
