package fh.view.build

import io.circe.Json

/** The generated dump as a Pkl package artifact whose VERSION is derived from
  * its content — `fh-home@1.0.0-g<hash>` (ADR 0010, "resolved by
  * content-derived versions"). The "live artifact" becomes a sequence of
  * immutable snapshots: an unchanged home re-derives the same version (both the
  * zip and the metadata are deterministic), a structural change mints a NEW
  * cache entry, so checksums stay honest and nothing is ever served stale under
  * an old name.
  *
  * The version hashes the FULL artifact identity — the dump bytes AND the lib
  * dependency (uri + checksum) the metadata declares — so the metadata is
  * immutable per version too: a lib pin bump under an unchanged dump mints a
  * new version rather than changing bytes under an old one, which would strand
  * every laptop cache that already holds it.
  *
  * This is the ONLY form the dump takes anywhere (ADR 0010): server, `BuildApp`
  * and tests all consume it as a cache package, resolved offline via
  * `moduleCacheDir` exactly like `@fh-dashboard`. There is no loose
  * `home/dump.pkl` on disk. The workspace binds `@fh-home` through a STATIC
  * `.fh/base.pkl` that reads the `homeUri`/`homeSha256` pin from
  * `.fh/pins.json` ([[Pins]]) — the seam rewritten per dump change (see
  * [[AddonBootstrap]]). Consumed by a laptop as a normal remote dependency: the
  * metadata's `dependencies` carry the `@fh-dashboard` pin so the dump's
  * `import "@fh-dashboard/hass.pkl"` resolves onto the same cached artifact the
  * entry's alias uses — module identity holds.
  */
object DumpPackage {

  val Name = "fh-home"

  def packageUri(version: String): String =
    s"package://${LibPackage.Host}/$Name@$version"

  def build(
      dumpText: String,
      libVersion: String,
      libMetadataSha256: String
  ): LibPackage.Artifacts = {
    val zip =
      LibPackage.deterministicZip(
        List("dump.pkl" -> dumpText.getBytes("UTF-8"))
      )
    val zipSha = LibPackage.sha256(zip)
    val version = "1.0.0-g" + LibPackage
      .sha256(s"$zipSha $libVersion $libMetadataSha256".getBytes("UTF-8"))
      .take(12)
    val metadata = LibPackage.metadataJson(
      Name,
      version,
      zipSha,
      dependencies = Json.obj(
        LibPackage.Name -> Json.obj(
          "uri" -> Json.fromString(LibPackage.packageUri(libVersion)),
          "checksums" -> Json.obj(
            "sha256" -> Json.fromString(libMetadataSha256)
          )
        )
      )
    )
    LibPackage.Artifacts(Name, version, zip, zipSha, metadata)
  }

  def cacheEntryDir(cacheDir: os.Path, version: String): os.Path =
    LibPackage.entryDir(cacheDir, Name, version)

  /** Build the current dump's package from the rendered dump TEXT (never a
    * loose file), seed it into the workspace cache, and move the `@fh-home` pin
    * in `.fh/pins.json` ([[Pins]]) to point at it. Called right after every
    * dump render ([[DashboardBuild.prepareDumps]], [[DumpRefresh]]).
    *
    * Needs the effective `@fh-dashboard` pin and that pin's metadata in the
    * workspace cache (the dump's metadata declares it as a checksummed
    * dependency, so the dump's `import "@fh-dashboard/hass.pkl"` lands on the
    * same artifact the entry's alias uses — module identity, Spike 0). The pin
    * is derived from the LOADED project ([[libPin]]) whenever it can be — a
    * running server and a bootstrapped test workspace can. But the VERY FIRST
    * dump on a fresh workspace runs BEFORE `pins.json` exists, so the project
    * can't load (its base.pkl reads that file) and [[libPin]] is `None`; the
    * caller then supplies `fallbackLib`, the bundled `@fh-dashboard` artifacts
    * this boot already built ([[AddonBootstrap.run]]), and this first write
    * mints `pins.json` with all three keys real. Once pins exist, later seeds
    * (refresh) derive the pin from the project and the fallback is unused (a
    * user override in `PklProject` is honored via mirroring, Spike 0).
    *
    * Idempotent: the version names the bytes, so an existing cache entry is
    * never rewritten and old versions stay (a laptop pinned to an older
    * snapshot keeps resolving); the pin file is only rewritten when the
    * snapshot actually changes. `Nil` when the workspace can neither derive nor
    * be given a pin. Returns a human-readable action log.
    */
  def seedFromText(
      dashboardsDir: os.Path,
      dumpText: String,
      fallbackLib: Option[LibPackage.Artifacts] = None
  ): List[String] =
    libPin(dashboardsDir)
      .orElse(fallbackLib.map(a => (a.version, a.metadataSha256))) match {
      case None                 => Nil
      case Some((pin, metaSha)) =>
        val artifacts = build(dumpText, pin, metaSha)
        // The FIRST dump seeds into the real cache before pins.json exists, so
        // the project can't be loaded to read `moduleCacheDir` — take it
        // straight from `.fh/machine.json` ([[AddonBootstrap.machineCacheDir]]),
        // the same value base.pkl forwards. (Post-first-boot the project path
        // resolves the identical dir; the machine.json read just also works
        // before pins land.)
        val cache = AddonBootstrap
          .machineCacheDir(dashboardsDir)
          .getOrElse(PklBuild.workspaceCacheDir(dashboardsDir))
        LibPackage.seedEntry(
          cache,
          artifacts,
          s"seeded dump package: ${packageUri(artifacts.version)}"
        ) ++ Pins.writeHome(
          dashboardsDir,
          LibPackage.packageUri(pin),
          packageUri(artifacts.version),
          artifacts.metadataSha256
        )
    }

  /** The workspace's effective `@fh-dashboard` pin plus the sha256 of that
    * pin's cached metadata (what the dump's `dependencies` declare), or `None`
    * when the workspace cannot package (no pin, or its metadata is not in the
    * cache yet).
    */
  private def libPin(dashboardsDir: os.Path): Option[(String, String)] =
    for {
      pin <- LibPackage.effectivePin(dashboardsDir)
      cache = PklBuild.workspaceCacheDir(dashboardsDir)
      libMeta = LibPackage.cacheEntryDir(cache, pin) /
        s"${LibPackage.Name}@$pin.json"
      if os.exists(libMeta)
    } yield (pin, LibPackage.sha256(os.read.bytes(libMeta)))

  /** Build the artifacts for `dumpText` in this workspace, or `None` when the
    * workspace cannot package ([[libPin]]).
    */
  private def artifactsFor(
      dashboardsDir: os.Path,
      dumpText: String
  ): Option[LibPackage.Artifacts] =
    libPin(dashboardsDir).map { case (pin, metaSha) =>
      build(dumpText, pin, metaSha)
    }

  /** The content-version of `dumpText` in this workspace (what [[seedFromText]]
    * would mint), or `None` when the workspace cannot package. Lets
    * [[DumpRefresh]] short-circuit an unchanged home by comparing versions
    * rather than files.
    */
  def versionFor(dashboardsDir: os.Path, dumpText: String): Option[String] =
    artifactsFor(dashboardsDir, dumpText).map(_.version)

  /** The discovery document behind `GET /system/pkl/packages`: the current
    * version + metadata sha256 of both packages a laptop pins — exactly what
    * `fh pull` writes into the laptop manifest (`uri` + `checksums` together).
    * The fh-home half is read straight from `.fh/pins.json` (the served
    * snapshot), the lib half from the effective pin + its cached metadata
    * ([[libPin]]). `None` until a real dump has been pinned (before the first
    * dump there is no `pins.json` at all).
    */
  def index(dashboardsDir: os.Path): Option[String] =
    for {
      homeVersion <- Pins.homeVersion(dashboardsDir)
      homeSha <- Pins.homeSha256(dashboardsDir)
      (pin, libMetaSha) <- libPin(dashboardsDir)
    } yield Json
      .obj(
        LibPackage.Name -> Json.obj(
          "version" -> Json.fromString(pin),
          "sha256" -> Json.fromString(libMetaSha)
        ),
        Name -> Json.obj(
          "version" -> Json.fromString(homeVersion),
          "sha256" -> Json.fromString(homeSha)
        )
      )
      .spaces2
}
