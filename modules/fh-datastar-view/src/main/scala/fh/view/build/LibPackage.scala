package fh.view.build

import io.circe.Json

import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.{ZipEntry, ZipOutputStream}

/** `@fh-dashboard` as a Pkl package: a deterministic zip plus its metadata,
  * seeded into pkl's cache for the instance and served to laptops by
  * [[SystemPkl.packageArtifact]] (ADR 0010).
  *
  * The version is `<base>-g<zip-hash>`, so a changed lib mints a new immutable
  * cache entry and no cache can hold stale bytes under a current name. The
  * plan, once the lib stabilizes, is to drop the hash and bump the base per
  * release.
  */
object LibPackage {

  val Host = PackageRef.Host

  val Name = "fh-dashboard"

  def packageUri(version: String): String =
    PackageRef(Name, version).uri

  /** What the entry resolves `@fh-dashboard` to, read off the loaded project so
    * pkl applies the amends chain (a user override shadows the machine pin). A
    * text scan once matched the pin example in the seeded doc header.
    */
  def effectivePin(dashboardsDir: os.Path): Option[String] =
    // `loadFromPath` evaluates `pkl.Project`, where #226's race was caught.
    PklBuild.serialized(
      scala.util
        .Try(
          org.pkl.core.project.Project
            .loadFromPath((dashboardsDir / "PklProject").toNIO)
        )
        .toOption
        .flatMap(p => Option(p.getDependencies.remoteDependencies.get(Name)))
        .map(_.getPackageUri.toString)
        .flatMap(uri => PackageRef.parse(uri).map(_.version))
    )

  /** From text, so a dir lib and [[BundledLib]] read it identically. */
  private def baseVersionFrom(manifest: String): String =
    """version\s*=\s*"([^"]+)"""".r
      .findFirstMatchIn(manifest)
      .map(_.group(1))
      .getOrElse(
        sys.error("no version = \"…\" line in the bundled lib PklProject")
      )

  def version(libDir: os.Path): String = build(libDir).version

  case class Artifacts(
      name: String,
      version: String,
      zip: Array[Byte],
      sha256: String,
      metadataJson: String
  ) {

    def ref: PackageRef = PackageRef(name, version)

    /** What a manifest's `checksums { sha256 }` pins; covers the zip too. */
    def metadataSha256: String =
      LibPackage.sha256(metadataJson.getBytes("UTF-8"))
  }

  private val Excluded = Set("PklProject", "PklProject.deps.json")

  // `setTimeLocal`: a millis-based `setTime` makes DOS time timezone-dependent.
  private val FixedTime = LocalDateTime.of(1980, 1, 1, 0, 0)

  def build(libDir: os.Path): Artifacts = build(dirEntries(libDir))

  /** `entries` must include `PklProject`, for its base version. */
  def build(entries: Seq[(String, Array[Byte])]): Artifacts = {
    val manifest = entries
      .collectFirst {
        case (name, bytes) if name == "PklProject" =>
          new String(bytes, "UTF-8")
      }
      .getOrElse(sys.error("bundled lib entries have no PklProject"))
    val zip = deterministicZip(
      entries.filterNot { case (name, _) => Excluded.contains(name) }
    )
    val sha = sha256(zip)
    val version = PackageRef.contentVersion(baseVersionFrom(manifest), sha)
    Artifacts(Name, version, zip, sha, metadata(version, sha))
  }

  private def dirEntries(libDir: os.Path): Seq[(String, Array[Byte])] =
    os.walk(libDir)
      .filter(os.isFile)
      .map(f => f.relativeTo(libDir).toString -> os.read.bytes(f))

  def zipBytes(libDir: os.Path): Array[Byte] =
    deterministicZip(
      dirEntries(libDir).filterNot { case (name, _) => Excluded.contains(name) }
    )

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

  /** One template for both served packages, so their wire format cannot drift.
    */
  private[build] def metadataJson(
      name: String,
      version: String,
      zipSha256: String,
      dependencies: Json
  ): String =
    val ref = PackageRef(name, version)
    Json
      .obj(
        "name" -> Json.fromString(name),
        "packageUri" -> Json.fromString(ref.uri),
        "version" -> Json.fromString(version),
        "packageZipUrl" -> Json.fromString(
          s"https://$Host/${ref.zipName}"
        ),
        "packageZipChecksums" -> Json.obj(
          "sha256" -> Json.fromString(zipSha256)
        ),
        "dependencies" -> dependencies
      )
      .spaces2

  def cacheEntryDir(cacheDir: os.Path, version: String): os.Path =
    entryDir(cacheDir, Name, version)

  private[build] def entryDir(
      cacheDir: os.Path,
      name: String,
      version: String
  ): os.Path =
    PackageRef(name, version).entryDir(cacheDir)

  /** Never rewrites an existing entry, and old versions stay resolvable. */
  private[build] def seedEntry(
      cacheDir: os.Path,
      artifacts: Artifacts,
      logLine: => String
  ): List[String] = {
    val ref = artifacts.ref
    val dir = ref.entryDir(cacheDir)
    val zipPath = dir / ref.zipName
    if (os.exists(zipPath)) Nil
    else {
      os.makeDir.all(dir)
      os.write.over(zipPath, artifacts.zip)
      os.write.over(dir / ref.jsonName, artifacts.metadataJson)
      List(logLine)
    }
  }

  def seedCache(artifacts: Artifacts, cacheDir: os.Path): List[String] =
    seedEntry(
      cacheDir,
      artifacts,
      s"seeded package cache: ${packageUri(artifacts.version)} (sha256 ${artifacts.sha256.take(12)}…)"
    )

  def seedCache(libDir: os.Path, cacheDir: os.Path): List[String] =
    seedCache(build(libDir), cacheDir)
}
