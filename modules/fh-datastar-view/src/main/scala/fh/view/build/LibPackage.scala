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
  * The zip is **deterministic** (sorted entries, fixed timestamps), and the
  * version is **content-derived** like the dump's ([[DumpPackage]]):
  * `<base>-g<hash>`, where `<base>` comes from `lib/PklProject`'s `version`
  * line and the hash is of the zip bytes. An unchanged lib re-derives the same
  * version (cache hit everywhere); a changed lib mints a NEW immutable cache
  * entry, so no cache — the instance's, a laptop's, or pkl's default
  * `~/.pkl/cache` — can ever hold stale bytes under a current name. Old
  * versions stay resolvable from the cache. When the lib stabilizes, the plan
  * is to decouple this: drop the hash suffix and bump the base version normally
  * per release.
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

  /** The workspace's effective lib pin — the version the ENTRY resolves
    * `@fh-dashboard` to, which the dump package must declare to keep module
    * identity. Read off the LOADED pkl project's declared dependencies
    * (spike-verified on 0.31.1: `getDependencies.getRemoteDependencies`), so
    * pkl itself applies the amends chain — a user override in `PklProject`
    * shadows the base default, otherwise the machine pin `base.pkl` read from
    * `.fh/pins.json` comes through. (An earlier text-regex scan of the manifest
    * matched the pin EXAMPLE in the seeded doc header — exactly the class of
    * bug delegating to the real parser removes.) `None` when the workspace has
    * no loadable `PklProject` with an `fh-dashboard` dependency (not a
    * bootstrapped package-form workspace).
    */
  def effectivePin(dashboardsDir: os.Path): Option[String] =
    scala.util
      .Try(
        org.pkl.core.project.Project
          .loadFromPath((dashboardsDir / "PklProject").toNIO)
      )
      .toOption
      .flatMap(p => Option(p.getDependencies.getRemoteDependencies.get(Name)))
      .map(_.getPackageUri.toString)
      .flatMap(uri =>
        s"$Name@(.+)$$".r.unanchored.findFirstMatchIn(uri).map(_.group(1))
      )

  /** The lib's BASE version, from the `version = "…"` line of its manifest —
    * the ONE place a human-declared version lives (decoupled from the add-on
    * version by design: the authoring layer is where churn lives). The
    * effective package version appends the content hash ([[build]]).
    */
  private def baseVersion(libDir: os.Path): String = {
    val manifest = libDir / "PklProject"
    """version\s*=\s*"([^"]+)"""".r
      .findFirstMatchIn(os.read(manifest))
      .map(_.group(1))
      .getOrElse(sys.error(s"no version = \"…\" line in $manifest"))
  }

  /** The lib's effective, content-derived package version
    * (`<base>-g<zip-hash>`). Deterministic — same lib bytes, same version.
    */
  def version(libDir: os.Path): String = build(libDir).version

  /** The built artifact pair for one package version — shared shape with the
    * dump package ([[DumpPackage.build]] returns the same type).
    */
  final case class Artifacts(
      name: String,
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

  // The manifest is not a module (its content rides in the metadata JSON);
  // lockfiles and caches are generated noise.
  private val Excluded = Set("PklProject", "PklProject.deps.json")

  // Inside the zip epoch (1980); `setTimeLocal` sidesteps the DOS-time
  // timezone dependence a millis-based `setTime` would reintroduce.
  private val FixedTime = LocalDateTime.of(1980, 1, 1, 0, 0)

  def build(libDir: os.Path): Artifacts = {
    val zip = zipBytes(libDir)
    val sha = sha256(zip)
    val version = s"${baseVersion(libDir)}-g${sha.take(12)}"
    Artifacts(Name, version, zip, sha, metadata(version, sha))
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

  def metadata(version: String, zipSha256: String): String =
    metadataJson(Name, version, zipSha256, dependencies = Json.obj())

  /** The package-metadata JSON pkl fetches for `package://…@version` — the
    * shape spike-verified on 0.31.1 (resolver reads `packageZipChecksums` into
    * the lockfile pin). One template for BOTH served packages, so the wire
    * format cannot drift between the lib and the dump.
    */
  private[build] def metadataJson(
      name: String,
      version: String,
      zipSha256: String,
      dependencies: Json
  ): String =
    Json
      .obj(
        "name" -> Json.fromString(name),
        "packageUri" -> Json.fromString(s"package://$Host/$name@$version"),
        "version" -> Json.fromString(version),
        "packageZipUrl" -> Json.fromString(
          s"https://$Host/$name@$version.zip"
        ),
        "packageZipChecksums" -> Json.obj(
          "sha256" -> Json.fromString(zipSha256)
        ),
        "dependencies" -> dependencies
      )
      .spaces2

  /** Where a resolved package lives in pkl's cache (`package-2` is pkl's
    * cache-format tag, observed on 0.31.1).
    */
  def cacheEntryDir(cacheDir: os.Path, version: String): os.Path =
    entryDir(cacheDir, Name, version)

  private[build] def entryDir(
      cacheDir: os.Path,
      name: String,
      version: String
  ): os.Path =
    cacheDir / "package-2" / Host / s"$name@$version"

  /** Write built artifacts into pkl's package-cache layout. Idempotent: the
    * content-derived version names the bytes, so an existing cache entry is
    * never rewritten and old versions stay (a workspace pinned to an older
    * version keeps resolving). Returns a human-readable action log.
    */
  private[build] def seedEntry(
      cacheDir: os.Path,
      artifacts: Artifacts,
      logLine: => String
  ): List[String] = {
    val dir = entryDir(cacheDir, artifacts.name, artifacts.version)
    val zipPath = dir / s"${artifacts.name}@${artifacts.version}.zip"
    if (os.exists(zipPath)) Nil
    else {
      os.makeDir.all(dir)
      os.write.over(zipPath, artifacts.zip)
      os.write.over(
        dir / s"${artifacts.name}@${artifacts.version}.json",
        artifacts.metadataJson
      )
      List(logLine)
    }
  }

  /** [[seedEntry]] for the lib, from prebuilt artifacts (so a caller that
    * already needed [[build]]'s version doesn't zip the lib twice).
    */
  def seedCache(artifacts: Artifacts, cacheDir: os.Path): List[String] =
    seedEntry(
      cacheDir,
      artifacts,
      s"seeded package cache: ${packageUri(artifacts.version)} (sha256 ${artifacts.sha256.take(12)}…)"
    )

  def seedCache(libDir: os.Path, cacheDir: os.Path): List[String] =
    seedCache(build(libDir), cacheDir)
}
