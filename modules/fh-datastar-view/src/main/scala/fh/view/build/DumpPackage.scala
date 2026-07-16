package fh.view.build

import io.circe.Json

/** The generated `home/dump.pkl` as a Pkl package artifact whose VERSION is
  * derived from its content — `fh-home@1.0.0-g<hash>` (ADR 0010, "resolved by
  * content-derived versions"). The "live artifact" becomes a sequence of
  * immutable snapshots: an unchanged home re-derives the same version (both the
  * zip and the metadata are deterministic), a structural change mints a NEW
  * cache entry, so checksums stay honest and nothing is ever served stale under
  * an old name (the fixed-version staleness trap the ADR documents).
  *
  * The version hashes the FULL artifact identity — the dump bytes AND the lib
  * dependency (uri + checksum) the metadata declares — so the metadata is
  * immutable per version too: a lib pin bump under an unchanged dump mints a
  * new version rather than changing bytes under an old one, which would strand
  * every laptop cache that already holds it.
  *
  * Consumed by a laptop as a normal remote package dependency: the metadata's
  * `dependencies` carry the `@fh-dashboard` pin (shape spike-verified on
  * 0.31.1), so the dump's `import "@fh-dashboard/hass.pkl"` resolves onto the
  * same cached artifact the entry's alias uses — module identity holds with no
  * project-context tricks. Served by the existing `/system/pkl/packages/`
  * route, which is name-agnostic over the cache.
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
  )

  def build(
      dumpText: String,
      libVersion: String,
      libMetadataSha256: String
  ): Artifacts = {
    val zip =
      LibPackage.deterministicZip(List("dump.pkl" -> dumpText.getBytes("UTF-8")))
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

  /** What the workspace's current dump packages to, when it can package at
    * all: needs the effective `@fh-dashboard` pin, that pin's metadata in the
    * workspace cache (the dump's metadata declares it as a checksummed
    * dependency), and a written dump. `None` on a path-form workspace (repo
    * checkout — no pin, and a laptop pulls from an add-on, not from a dev
    * checkout) or before `prepareDumps` has run.
    */
  private def current(dashboardsDir: os.Path): Option[Artifacts] = {
    val dumpFile = DashboardBuild.dumpPath(dashboardsDir)
    for {
      pin <- LibPackage.effectivePin(dashboardsDir)
      cache = PklBuild.workspaceCacheDir(dashboardsDir)
      libMeta = LibPackage.cacheEntryDir(cache, pin) /
        s"${LibPackage.Name}@$pin.json"
      if os.exists(libMeta) && os.exists(dumpFile)
    } yield build(
      os.read(dumpFile),
      pin,
      LibPackage.sha256(os.read.bytes(libMeta))
    )
  }

  /** Seed the current dump's package artifacts into the workspace cache —
    * called right after every dump write ([[DashboardBuild.prepareDumps]]).
    * Idempotent by construction: the version names the bytes, so an existing
    * entry is never rewritten, and old versions stay (a laptop pinned to an
    * older snapshot keeps resolving). Returns a human-readable action log.
    */
  def seedFromWorkspace(dashboardsDir: os.Path): List[String] =
    current(dashboardsDir) match {
      case Some(artifacts) =>
        val cache = PklBuild.workspaceCacheDir(dashboardsDir)
        val dir = cacheEntryDir(cache, artifacts.version)
        val zipPath = dir / s"$Name@${artifacts.version}.zip"
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
      case None => Nil
    }

  /** The discovery document behind `GET /system/pkl/packages`: the current
    * version + metadata sha256 of both packages a laptop pins — exactly what
    * `fh pull` writes into the laptop manifest (`uri` + `checksums` together).
    * The shas are of the METADATA files, the artifact a manifest-declared
    * checksum pins (spike-verified; the zip is covered transitively).
    */
  def index(dashboardsDir: os.Path): Option[String] =
    for {
      artifacts <- current(dashboardsDir)
      pin <- LibPackage.effectivePin(dashboardsDir)
      cache = PklBuild.workspaceCacheDir(dashboardsDir)
      libMeta = LibPackage.cacheEntryDir(cache, pin) /
        s"${LibPackage.Name}@$pin.json"
    } yield Json
      .obj(
        LibPackage.Name -> Json.obj(
          "version" -> Json.fromString(pin),
          "sha256" -> Json.fromString(
            LibPackage.sha256(os.read.bytes(libMeta))
          )
        ),
        Name -> Json.obj(
          "version" -> Json.fromString(artifacts.version),
          "sha256" -> Json.fromString(
            LibPackage.sha256(artifacts.metadataJson.getBytes("UTF-8"))
          )
        )
      )
      .spaces2
}
