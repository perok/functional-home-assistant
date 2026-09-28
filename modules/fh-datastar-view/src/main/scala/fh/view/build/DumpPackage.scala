package fh.view.build

import io.circe.Json

/** The dump as a content-versioned package, `fh-home@1.0.0-g<hash>`, and the
  * only form it takes (ADR 0010). The hash covers the zip and the declared lib
  * dependency, so a lib pin bump mints a new version rather than changing
  * metadata under an old one, which would strand every laptop cache holding it.
  * Declaring the lib is also what lets the dump's `hass.pkl` import land on the
  * same artifact as the entry's alias.
  */
object DumpPackage {

  val Name = "fh-home"

  case class LibPin(ref: PackageRef, metadataSha: String)

  def packageUri(version: String): String =
    PackageRef(Name, version).uri

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
    val version = PackageRef.contentVersion(
      "1.0.0",
      LibPackage.sha256(
        s"$zipSha $libVersion $libMetadataSha256".getBytes("UTF-8")
      )
    )
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

  /** Seeds the dump and moves the `@fh-home` pin in `.fh/pins.json`.
    *
    * The first dump on a fresh workspace runs before `pins.json` exists, so the
    * project cannot load (its `base.pkl` reads that file) and [[libPin]] is
    * `None`; `fallbackLib`, this boot's bundled artifacts, covers that one
    * write. `Nil` when neither is available.
    */
  def seedFromText(
      dashboardsDir: os.Path,
      dumpText: String,
      fallbackLib: Option[LibPackage.Artifacts] = None
  ): List[String] =
    libPin(dashboardsDir)
      .orElse(fallbackLib.map(a => LibPin(a.ref, a.metadataSha256))) match {
      case None                          => Nil
      case Some(LibPin(libRef, metaSha)) =>
        val artifacts = build(dumpText, libRef.version, metaSha)
        // Not the project's `moduleCacheDir`: on the first dump it cannot load.
        val cache = AddonBootstrap.effectiveCacheDir(dashboardsDir)
        LibPackage.seedEntry(
          cache,
          artifacts,
          s"seeded dump package: ${packageUri(artifacts.version)}"
        ) ++ Pins.writeHome(
          dashboardsDir,
          libRef.uri,
          packageUri(artifacts.version),
          artifacts.metadataSha256
        )
    }

  /** `None` without a pin, or before its metadata is cached. */
  private def libPin(dashboardsDir: os.Path): Option[LibPin] =
    for {
      pin <- LibPackage.effectivePin(dashboardsDir)
      ref = PackageRef(LibPackage.Name, pin)
      cache = PklBuild.workspaceCacheDir(dashboardsDir)
      libMeta = ref.entryDir(cache) / ref.jsonName
      if os.exists(libMeta)
    } yield LibPin(ref, LibPackage.sha256(os.read.bytes(libMeta)))

  private def artifactsFor(
      dashboardsDir: os.Path,
      dumpText: String
  ): Option[LibPackage.Artifacts] =
    libPin(dashboardsDir).map(lp =>
      build(dumpText, lp.ref.version, lp.metadataSha)
    )

  def versionFor(dashboardsDir: os.Path, dumpText: String): Option[String] =
    artifactsFor(dashboardsDir, dumpText).map(_.version)

  /** `GET /system/pkl/packages`: what `fh pull` pins. `None` before the first
    * dump. Blocking; the caller supplies the `IO.blocking`.
    */
  def index(dashboardsDir: os.Path): Option[String] =
    for {
      homeVersion <- Pins.homeVersion(dashboardsDir)
      homeSha <- Pins.homeSha256(dashboardsDir)
      lp <- libPin(dashboardsDir)
    } yield Json
      .obj(
        LibPackage.Name -> Json.obj(
          "version" -> Json.fromString(lp.ref.version),
          "sha256" -> Json.fromString(lp.metadataSha)
        ),
        Name -> Json.obj(
          "version" -> Json.fromString(homeVersion),
          "sha256" -> Json.fromString(homeSha)
        )
      )
      .spaces2
}
