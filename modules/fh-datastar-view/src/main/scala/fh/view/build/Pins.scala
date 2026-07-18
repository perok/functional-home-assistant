package fh.view.build

import io.circe.Json

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The machine-owned `.fh/pins.json` — the ONE data file the static
  * `.fh/base.pkl` reads (via `pkl:json`) to bind BOTH aliases (ADR 0010).
  * Moving every pin here makes bumping the lib or pulling the dump a plain file
  * rewrite, never a Pkl-source edit — the same mechanism for both, and the seam
  * a future `fh sync`/`pull` writes through.
  *
  * Flat shape, so base.pkl needs no nested classes or optionals:
  * {{{{ "dashboardUri", "homeUri", "homeSha256" }}}}
  *
  * `dashboardUri` is written by [[AddonBootstrap]] at the bundled lib version
  * on every start; `homeUri`/`homeSha256` by [[DumpPackage.seedFromText]] per
  * dump (a placeholder until the first dump). Both writers read-modify-write,
  * so neither clobbers the other's key. Every real change first rolls the prior
  * file into a dated `.fh/pins.json.backup.<stamp>` ([[backups]]) — the only
  * overwrite-with-backup the bootstrap keeps — and prunes to the newest
  * [[MaxBackups]] so the trail can't grow without bound.
  */
object Pins {

  def path(dashboardsDir: os.Path): os.Path =
    dashboardsDir / ".fh" / "pins.json"

  private val BackupPrefix = "pins.json.backup."

  private val BackupStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

  /** How many dated backups of `pins.json` to retain. On each real pin change
    * ([[writeData]]) the previous file is copied to a dated
    * `.fh/pins.json.backup.<stamp>` and the oldest are pruned so at most this
    * many remain — machine data rewritten on every dump refresh must not grow
    * an unbounded trail.
    */
  val MaxBackups = 50

  /** The dated backups of `pins.json`, oldest first (they sort lexically by the
    * timestamped name).
    */
  def backups(dashboardsDir: os.Path): Seq[os.Path] = {
    val dir = dashboardsDir / ".fh"
    if (!os.exists(dir)) Nil
    else
      os.list(dir)
        .filter(p => os.isFile(p) && p.last.startsWith(BackupPrefix))
        .sortBy(_.last)
        .toSeq
  }

  /** A fresh, non-colliding dated backup path. A zero-padded `-NNN` suffix
    * disambiguates writes that land in the same millisecond AND keeps the names
    * lexically ordered by creation (so [[backups]]'s name-sort is
    * oldest-first).
    */
  private def freshBackup(dashboardsDir: os.Path): os.Path = {
    val dir = dashboardsDir / ".fh"
    val base = BackupPrefix + LocalDateTime.now().format(BackupStamp)
    LazyList
      .from(0)
      .map(i => dir / (if (i == 0) base else f"$base-$i%03d"))
      .find(p => !os.exists(p))
      .get
  }

  /** The `@fh-home` placeholder version written before the first dump, so
    * base.pkl's `read` resolves for the `Project.loadFromPath` that precedes
    * it.
    */
  val PlaceholderHomeVersion = "0.0.0-unresolved"

  final case class Data(
      dashboardUri: String,
      homeUri: String,
      homeSha256: String
  )

  def read(dashboardsDir: os.Path): Option[Data] =
    Option
      .when(os.exists(path(dashboardsDir)))(os.read(path(dashboardsDir)))
      .flatMap(parse)

  private def parse(text: String): Option[Data] =
    io.circe.parser.parse(text).toOption.flatMap { j =>
      val c = j.hcursor
      for {
        d <- c.get[String]("dashboardUri").toOption
        hu <- c.get[String]("homeUri").toOption
        hs <- c.get[String]("homeSha256").toOption
      } yield Data(d, hu, hs)
    }

  private def versionOf(uri: String, name: String): Option[String] =
    s"""$name@(.+)$$""".r.unanchored.findFirstMatchIn(uri).map(_.group(1))

  /** The pinned `@fh-dashboard` version (always real — bootstrap seeds it). */
  def dashboardVersion(dashboardsDir: os.Path): Option[String] =
    read(dashboardsDir).flatMap(d => versionOf(d.dashboardUri, LibPackage.Name))

  /** The pinned `@fh-home` version, or `None` while it is still the placeholder
    * (no dump packaged yet).
    */
  def homeVersion(dashboardsDir: os.Path): Option[String] =
    read(dashboardsDir)
      .flatMap(d => versionOf(d.homeUri, DumpPackage.Name))
      .filter(_ != PlaceholderHomeVersion)

  def homeSha256(dashboardsDir: os.Path): Option[String] =
    read(dashboardsDir).map(_.homeSha256).filter(_.nonEmpty)

  private def placeholderHome: (String, String) =
    (
      s"package://${LibPackage.Host}/${DumpPackage.Name}@$PlaceholderHomeVersion",
      ""
    )

  private def json(d: Data): String =
    Json
      .obj(
        "dashboardUri" -> Json.fromString(d.dashboardUri),
        "homeUri" -> Json.fromString(d.homeUri),
        "homeSha256" -> Json.fromString(d.homeSha256)
      )
      .spaces2

  private def writeData(dashboardsDir: os.Path, d: Data): Boolean =
    if (read(dashboardsDir).contains(d)) false
    else {
      val file = path(dashboardsDir)
      os.makeDir.all(file / os.up)
      // Keep the previous pin recoverable: copy the existing file to a dated
      // backup before overwriting (only on a real change — a no-op write
      // returned above, so we never churn the backups), then prune to the
      // newest MaxBackups.
      if (os.exists(file)) {
        os.copy.over(file, freshBackup(dashboardsDir))
        backups(dashboardsDir).dropRight(MaxBackups).foreach(os.remove)
      }
      os.write.over(file, json(d))
      true
    }

  /** Bootstrap: set `dashboardUri` to the bundled lib version (refreshed every
    * start), preserving any already-pinned dump — or seeding the placeholder
    * dump on a fresh workspace. Machine data, not logged; a real change (a lib
    * bump) rolls the prior file into a dated backup like any other write.
    */
  def seedBootstrap(dashboardsDir: os.Path, dashboardUri: String): Unit = {
    val (hu, hs) =
      read(dashboardsDir)
        .map(d => (d.homeUri, d.homeSha256))
        .getOrElse(placeholderHome)
    val _ = writeData(dashboardsDir, Data(dashboardUri, hu, hs))
  }

  /** Move the `@fh-home` pin to a new snapshot, preserving `dashboardUri`.
    * Returns a one-line log when it actually changed.
    */
  def writeHome(
      dashboardsDir: os.Path,
      homeUri: String,
      homeSha256: String
  ): List[String] = {
    val dashboardUri = read(dashboardsDir)
      .map(_.dashboardUri)
      .getOrElse(s"package://${LibPackage.Host}/${LibPackage.Name}@0.0.0")
    if (writeData(dashboardsDir, Data(dashboardUri, homeUri, homeSha256)))
      List(s"pinned @fh-home -> $homeUri")
    else Nil
  }
}
