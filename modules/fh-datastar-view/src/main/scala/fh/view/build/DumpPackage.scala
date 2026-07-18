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

  final case class Artifacts(
      version: String,
      zip: Array[Byte],
      sha256: String,
      metadataJson: String
  ) {

    /** The sha of the METADATA JSON — what a manifest `checksums { sha256 }`
      * pins (it transitively pins the zip via `packageZipChecksums`).
      */
    def metadataSha256: String =
      LibPackage.sha256(metadataJson.getBytes("UTF-8"))
  }

  def build(
      dumpText: String,
      libVersion: String,
      libMetadataSha256: String
  ): Artifacts = {
    val zip =
      LibPackage.deterministicZip(
        List("dump.pkl" -> dumpText.getBytes("UTF-8"))
      )
    val zipSha = LibPackage.sha256(zip)
    val version = "1.0.0-g" + LibPackage
      .sha256(s"$zipSha $libVersion $libMetadataSha256".getBytes("UTF-8"))
      .take(12)
    val metadata = Json
      .obj(
        "name" -> Json.fromString(Name),
        "packageUri" -> Json.fromString(packageUri(version)),
        "version" -> Json.fromString(version),
        "packageZipUrl" -> Json.fromString(
          s"https://${LibPackage.Host}/$Name@$version.zip"
        ),
        "packageZipChecksums" -> Json.obj("sha256" -> Json.fromString(zipSha)),
        "dependencies" -> Json.obj(
          "fh-dashboard" -> Json.obj(
            "uri" -> Json.fromString(LibPackage.packageUri(libVersion)),
            "checksums" -> Json.obj(
              "sha256" -> Json.fromString(libMetadataSha256)
            )
          )
        )
      )
      .spaces2
    Artifacts(version, zip, zipSha, metadata)
  }

  def cacheEntryDir(cacheDir: os.Path, version: String): os.Path =
    cacheDir / "package-2" / LibPackage.Host / s"$Name@$version"

  /** Build the current dump's package from the rendered dump TEXT (never a
    * loose file), seed it into the workspace cache, and move the `@fh-home` pin
    * in `.fh/pins.json` ([[Pins]]) to point at it. Called right after every
    * dump render ([[DashboardBuild.prepareDumps]], [[DumpRefresh]]).
    *
    * Needs the effective `@fh-dashboard` pin and that pin's metadata in the
    * workspace cache (the dump's metadata declares it as a checksummed
    * dependency). A live server (add-on OR local `sbt dashboardServe`) and a
    * bootstrapped test/BuildApp workspace always have both. Idempotent: the
    * version names the bytes, so an existing cache entry is never rewritten and
    * old versions stay (a laptop pinned to an older snapshot keeps resolving);
    * the pin file is only rewritten when the snapshot actually changes. Returns
    * a human-readable action log.
    */
  def seedFromText(dashboardsDir: os.Path, dumpText: String): List[String] =
    artifactsFor(dashboardsDir, dumpText) match {
      case None            => Nil
      case Some(artifacts) =>
        val cache = PklBuild.workspaceCacheDir(dashboardsDir)
        val dir = cacheEntryDir(cache, artifacts.version)
        val zipPath = dir / s"$Name@${artifacts.version}.zip"
        val seedLog =
          if (os.exists(zipPath)) Nil
          else {
            os.makeDir.all(dir)
            os.write.over(zipPath, artifacts.zip)
            os.write.over(
              dir / s"$Name@${artifacts.version}.json",
              artifacts.metadataJson
            )
            List(s"seeded dump package: ${packageUri(artifacts.version)}")
          }
        seedLog ++ Pins.writeHome(
          dashboardsDir,
          packageUri(artifacts.version),
          artifacts.metadataSha256
        )
    }

  /** Build the artifacts for `dumpText` in this workspace, or `None` when the
    * workspace cannot package (no `@fh-dashboard` pin, or its metadata is not
    * in the cache yet).
    */
  private def artifactsFor(
      dashboardsDir: os.Path,
      dumpText: String
  ): Option[Artifacts] =
    for {
      pin <- LibPackage.effectivePin(dashboardsDir)
      cache = PklBuild.workspaceCacheDir(dashboardsDir)
      libMeta = LibPackage.cacheEntryDir(cache, pin) /
        s"${LibPackage.Name}@$pin.json"
      if os.exists(libMeta)
    } yield build(dumpText, pin, LibPackage.sha256(os.read.bytes(libMeta)))

  /** The content-version of `dumpText` in this workspace (what [[seedFromText]]
    * would mint), or `None` when the workspace cannot package. Lets
    * [[DumpRefresh]] short-circuit an unchanged home by comparing versions
    * rather than files.
    */
  def versionFor(dashboardsDir: os.Path, dumpText: String): Option[String] =
    artifactsFor(dashboardsDir, dumpText).map(_.version)

  /** The `@fh-home` version currently pinned in `.fh/pins.json`, if any (and
    * not the bootstrap placeholder).
    */
  def pinnedVersion(dashboardsDir: os.Path): Option[String] =
    Pins.homeVersion(dashboardsDir)

  /** The discovery document behind `GET /system/pkl/packages`: the current
    * version + metadata sha256 of both packages a laptop pins — exactly what
    * `fh pull` writes into the laptop manifest (`uri` + `checksums` together).
    * The fh-home half is read straight from `.fh/pins.json` (the served
    * snapshot), the lib half from the effective pin + its cached metadata.
    * `None` until a real dump has been pinned (bootstrap placeholder aside).
    */
  def index(dashboardsDir: os.Path): Option[String] =
    for {
      homeVersion <- Pins.homeVersion(dashboardsDir)
      homeSha <- Pins.homeSha256(dashboardsDir)
      pin <- LibPackage.effectivePin(dashboardsDir)
      cache = PklBuild.workspaceCacheDir(dashboardsDir)
      libMeta = LibPackage.cacheEntryDir(cache, pin) /
        s"${LibPackage.Name}@$pin.json"
      if os.exists(libMeta)
    } yield Json
      .obj(
        LibPackage.Name -> Json.obj(
          "version" -> Json.fromString(pin),
          "sha256" -> Json.fromString(
            LibPackage.sha256(os.read.bytes(libMeta))
          )
        ),
        Name -> Json.obj(
          "version" -> Json.fromString(homeVersion),
          "sha256" -> Json.fromString(homeSha)
        )
      )
      .spaces2
}
