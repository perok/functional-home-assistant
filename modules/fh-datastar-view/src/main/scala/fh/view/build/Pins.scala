package fh.view.build

import io.circe.Json

/** The machine-owned `.fh/pins.json` — the ONE data file the static `.fh/base.pkl`
  * reads (via `pkl:json`) to bind BOTH aliases (ADR 0010). Moving every pin here
  * makes bumping the lib or pulling the dump a plain file rewrite, never a
  * Pkl-source edit — the same mechanism for both, and the seam a future
  * `fh sync`/`pull` writes through.
  *
  * Flat shape, so base.pkl needs no nested classes or optionals:
  * {{{ { "dashboardUri", "homeUri", "homeSha256" } }}}
  *
  * `dashboardUri` is written by [[AddonBootstrap]] at the bundled lib version on
  * every start; `homeUri`/`homeSha256` by [[DumpPackage.seedFromText]] per dump
  * (a placeholder until the first dump). Both writers read-modify-write, so
  * neither clobbers the other's key.
  */
object Pins {

  def path(dashboardsDir: os.Path): os.Path =
    dashboardsDir / ".fh" / "pins.json"

  /** The `@fh-home` placeholder version written before the first dump, so
    * base.pkl's `read` resolves for the `Project.loadFromPath` that precedes it.
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
      os.makeDir.all(path(dashboardsDir) / os.up)
      os.write.over(path(dashboardsDir), json(d))
      true
    }

  /** Bootstrap: set `dashboardUri` to the bundled lib version (refreshed every
    * start), preserving any already-pinned dump — or seeding the placeholder
    * dump on a fresh workspace. Machine data; not logged, never backed up.
    */
  def seedBootstrap(dashboardsDir: os.Path, dashboardUri: String): Unit = {
    val (hu, hs) =
      read(dashboardsDir).map(d => (d.homeUri, d.homeSha256)).getOrElse(placeholderHome)
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
