package fh.view.build

import io.circe.Json

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** `.fh/pins.json`, which the static `.fh/base.pkl` reads to bind both aliases,
  * so a pin move is a file rewrite, never a Pkl edit (ADR 0010).
  *
  * Real or absent, never partial: `base.pkl` cannot load one missing the home
  * fields, so the file is born with the first dump, all three keys at once.
  * Both writers read-modify-write their own keys.
  *
  * Synchronous disk access: callers hold the `IO.blocking` region.
  */
object Pins {

  def path(dashboardsDir: os.Path): os.Path =
    dashboardsDir / ".fh" / "pins.json"

  private val BackupPrefix = "pins.json.backup."

  private val BackupStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

  val MaxBackups = 50

  /** Oldest first: the stamped names sort lexically. */
  def backups(dashboardsDir: os.Path): Seq[os.Path] = {
    val dir = dashboardsDir / ".fh"
    if (!os.exists(dir)) Nil
    else
      os.list(dir)
        .filter(p => os.isFile(p) && p.last.startsWith(BackupPrefix))
        .sortBy(_.last)
        .toSeq
  }

  // The zero-padded suffix keeps same-millisecond names in creation order.
  private def freshBackup(dashboardsDir: os.Path): os.Path = {
    val dir = dashboardsDir / ".fh"
    val base = BackupPrefix + LocalDateTime.now().format(BackupStamp)
    LazyList
      .from(0)
      .map(i => dir / (if (i == 0) base else f"$base-$i%03d"))
      .find(p => !os.exists(p))
      .get
  }

  // No writer produces this any more; read as "no dump yet" until the add-on
  // release has rolled, then remove.
  private val LegacyPlaceholderHome = "0.0.0-unresolved"

  case class Data(
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
    PackageRef.parse(uri).filter(_.name == name).map(_.version)

  def dashboardVersion(dashboardsDir: os.Path): Option[String] =
    read(dashboardsDir).flatMap(d => versionOf(d.dashboardUri, LibPackage.Name))

  def homeVersion(dashboardsDir: os.Path): Option[String] =
    read(dashboardsDir)
      .flatMap(d => versionOf(d.homeUri, DumpPackage.Name))
      .filter(_ != LegacyPlaceholderHome)

  def homeSha256(dashboardsDir: os.Path): Option[String] =
    read(dashboardsDir).map(_.homeSha256).filter(_.nonEmpty)

  private def json(d: Data): String =
    Json
      .obj(
        "dashboardUri" -> Json.fromString(d.dashboardUri),
        "homeUri" -> Json.fromString(d.homeUri),
        "homeSha256" -> Json.fromString(d.homeSha256)
      )
      .spaces2

  /** Backs up the previous file only on a real change. */
  private def writeData(dashboardsDir: os.Path, d: Data): Boolean =
    if (read(dashboardsDir).contains(d)) false
    else {
      val file = path(dashboardsDir)
      os.makeDir.all(file / os.up)
      if (os.exists(file)) {
        os.copy.over(file, freshBackup(dashboardsDir))
        backups(dashboardsDir).dropRight(MaxBackups).foreach(os.remove)
      }
      os.write.over(file, json(d))
      true
    }

  /** A no-op before the first dump, to keep the file real-or-absent. */
  def seedBootstrap(dashboardsDir: os.Path, dashboardUri: String): Unit =
    read(dashboardsDir).foreach { d =>
      val _ = writeData(dashboardsDir, d.copy(dashboardUri = dashboardUri))
    }

  /** `dashboardUri` is used only when creating the file; an existing lib pin
    * wins.
    */
  def writeHome(
      dashboardsDir: os.Path,
      dashboardUri: String,
      homeUri: String,
      homeSha256: String
  ): List[String] = {
    val dash = read(dashboardsDir).map(_.dashboardUri).getOrElse(dashboardUri)
    if (writeData(dashboardsDir, Data(dash, homeUri, homeSha256)))
      List(s"pinned @fh-home -> $homeUri")
    else Nil
  }
}
