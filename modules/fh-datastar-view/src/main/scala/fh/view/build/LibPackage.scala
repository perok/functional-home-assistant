package fh.view.build

import io.circe.Json

import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.{ZipEntry, ZipOutputStream}

/** The `@fh-dashboard` library as a Pkl *package artifact*: a deterministic zip
  * of the `lib/` modules plus the package-metadata JSON that points at it (ADR
  * 0010, "the add-on workspace"). One build, two uses:
  *
  *   - [[seedCache]] writes both files straight into pkl's package-cache
  *     layout, so the add-on's own evaluation (and pkl-lsp behind `/edit`)
  *     resolves `package://fh.invalid/fh-dashboard@<v>` **offline** — a warm
  *     cache satisfies resolver and evaluator with a dummy http client
  *     (spike-verified on 0.31.1).
  *   - the same two files are what the `/system/pkl/packages/` endpoint serves
  *     to laptop workspaces (the metadata at `fh-dashboard@<v>`, the zip at
  *     `fh-dashboard@<v>.zip` — [[SystemPkl.packageArtifact]]).
  *
  * The zip is **deterministic** (sorted entries, fixed timestamps): an
  * unchanged lib yields byte-identical artifacts across image builds, so a
  * sha256 difference under an unchanged version IS the release-discipline
  * violation detector — packages are immutable per version, and serving changed
  * bytes under an old version breaks laptop checksum pins.
  */
object LibPackage {

  /** The placeholder package host. RFC 2606 reserves `.invalid`; `.local` would
    * be mDNS and real on an HA LAN. Never contacted by the add-on (the cache is
    * pre-seeded); a laptop maps it to its own instance with one
    * `evaluatorSettings.http.rewrites` line.
    */
  val Host = "fh.invalid"

  val Name = "fh-dashboard"

  def packageUri(version: String): String =
    s"package://$Host/$Name@$version"

  /** The `package://…/fh-dashboard@<v>` pin found in manifest text, if any. */
  def pinnedVersion(manifestText: String): Option[String] =
    s"""package://$Host/$Name@([^"]+)"""".r
      .findFirstMatchIn(manifestText)
      .map(_.group(1))

  /** The workspace's effective lib pin — the version the ENTRY resolves
    * `@fh-dashboard` to, which the dump package must declare to keep module
    * identity. The user's `PklProject` override wins (they may add a
    * `dependencies` block that shadows the base default); otherwise the
    * machine-owned pin in `.fh/pins.json` (which `.fh/base.pkl` reads) governs.
    * `None` on a workspace with neither (not a bootstrapped package-form one).
    */
  def effectivePin(dashboardsDir: os.Path): Option[String] =
    Option
      .when(os.exists(dashboardsDir / "PklProject"))(
        os.read(dashboardsDir / "PklProject")
      )
      .flatMap(pinnedVersion)
      .orElse(Pins.dashboardVersion(dashboardsDir))

  /** The lib's own version, from the `version = "…"` line of its manifest — the
    * ONE place the library version is declared (decoupled from the add-on
    * version by design: the authoring layer is where churn lives).
    */
  def version(libDir: os.Path): String = {
    val manifest = libDir / "PklProject"
    """version\s*=\s*"([^"]+)"""".r
      .findFirstMatchIn(os.read(manifest))
      .map(_.group(1))
      .getOrElse(sys.error(s"no version = \"…\" line in $manifest"))
  }

  /** The built artifact pair for one lib version. */
  final case class Artifacts(
      version: String,
      zip: Array[Byte],
      sha256: String,
      metadataJson: String
  )

  // The manifest is not a module (its content rides in the metadata JSON);
  // lockfiles and caches are generated noise.
  private val Excluded = Set("PklProject", "PklProject.deps.json")

  // Inside the zip epoch (1980); `setTimeLocal` sidesteps the DOS-time
  // timezone dependence a millis-based `setTime` would reintroduce.
  private val FixedTime = LocalDateTime.of(1980, 1, 1, 0, 0)

  def build(libDir: os.Path): Artifacts = {
    val zip = zipBytes(libDir)
    val sha = sha256(zip)
    Artifacts(version(libDir), zip, sha, metadata(version(libDir), sha))
  }

  /** Deterministic zip of the library modules, zip-root relative (an
    * `import "@fh-dashboard/components.pkl"` resolves `components.pkl` at the
    * package base).
    */
  def zipBytes(libDir: os.Path): Array[Byte] =
    deterministicZip(
      os.walk(libDir)
        .filter(os.isFile)
        .filterNot(p => Excluded.contains(p.last))
        .map(f => f.relativeTo(libDir).toString -> os.read.bytes(f))
    )

  /** Sorted entries, fixed timestamps — same input, same bytes. Shared with the
    * dump package ([[DumpPackage]]), whose *version* is derived from these
    * bytes.
    */
  private[build] def deterministicZip(
      entries: Seq[(String, Array[Byte])]
  ): Array[Byte] = {
    val bos = new java.io.ByteArrayOutputStream()
    val zos = new ZipOutputStream(bos)
    try
      entries.sortBy(_._1).foreach { case (name, bytes) =>
        val e = new ZipEntry(name)
        e.setTimeLocal(FixedTime)
        zos.putNextEntry(e)
        zos.write(bytes)
        zos.closeEntry()
      }
    finally zos.close()
    bos.toByteArray
  }

  def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map("%02x".format(_))
      .mkString

  /** The package-metadata JSON pkl fetches for `package://…@version` — the
    * shape spike-verified on 0.31.1 (resolver reads `packageZipChecksums` into
    * the lockfile pin).
    */
  def metadata(version: String, zipSha256: String): String =
    Json
      .obj(
        "name" -> Json.fromString(Name),
        "packageUri" -> Json.fromString(packageUri(version)),
        "version" -> Json.fromString(version),
        "packageZipUrl" -> Json.fromString(
          s"https://$Host/$Name@$version.zip"
        ),
        "packageZipChecksums" -> Json.obj(
          "sha256" -> Json.fromString(zipSha256)
        ),
        "dependencies" -> Json.obj()
      )
      .spaces2

  /** Where a resolved package lives in pkl's cache (`package-2` is pkl's
    * cache-format tag, observed on 0.31.1).
    */
  def cacheEntryDir(cacheDir: os.Path, version: String): os.Path =
    cacheDir / "package-2" / Host / s"$Name@$version"

  /** Write the artifacts into pkl's package-cache layout. Idempotent when the
    * cached zip is byte-identical; a sha difference under the same version is
    * reported loudly and OVERWRITTEN — the instance must run the lib it ships,
    * but any laptop that pinned the old checksum will now fail its verify,
    * which is the visible symptom of a lib change without a version bump.
    * Returns a human-readable action log.
    */
  def seedCache(libDir: os.Path, cacheDir: os.Path): List[String] = {
    val artifacts = build(libDir)
    val dir = cacheEntryDir(cacheDir, artifacts.version)
    val zipPath = dir / s"$Name@${artifacts.version}.zip"
    val jsonPath = dir / s"$Name@${artifacts.version}.json"
    val existingSha =
      Option.when(os.exists(zipPath))(sha256(os.read.bytes(zipPath)))
    existingSha match {
      case Some(sha) if sha == artifacts.sha256 => Nil
      case other                                =>
        os.makeDir.all(dir)
        os.write.over(zipPath, artifacts.zip)
        os.write.over(jsonPath, artifacts.metadataJson)
        val what = other match {
          case Some(oldSha) =>
            s"WARNING: lib bytes changed under unchanged version ${artifacts.version} " +
              s"(cached $oldSha -> ${artifacts.sha256}); overwriting the cache entry. " +
              "Packages are immutable per version — bump lib/PklProject's version " +
              "(laptops that pinned the old checksum will fail resolution until they re-pin)"
          case None =>
            s"seeded package cache: ${packageUri(artifacts.version)} (sha256 ${artifacts.sha256.take(12)}…)"
        }
        List(what)
    }
  }
}
