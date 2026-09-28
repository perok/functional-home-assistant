package fh.view.runtime

import io.circe.parser.parse

import java.nio.charset.StandardCharsets.UTF_8

/** The vite bundle, addressed by entry name: filenames carry a content hash,
  * which is what makes serving them `immutable` honest. Read once at
  * class-init; anything missing fails startup rather than serving a page that
  * silently does nothing.
  */
object FrontendAssets {

  private val ManifestPath = "/web/manifest.json"

  /** Keyed by entry name, not vite's key, which is the source path and would
    * put the source tree's layout into Scala.
    */
  private val entries: Map[String, String] = {
    val json = resourceText(ManifestPath)
    val parsed = parse(json).getOrElse(
      sys.error(s"$ManifestPath is not valid JSON")
    )
    val built = for {
      obj <- parsed.asObject.toList
      (_, entry) <- obj.toList
      cursor = entry.hcursor
      name <- cursor.get[String]("name").toOption
      file <- cursor.get[String]("file").toOption
    } yield name -> file
    if (built.isEmpty) sys.error(s"$ManifestPath names no entries")
    built.toMap
  }

  // Relative, so it resolves against `<base href>`.
  def url(entry: String): String = entries.getOrElse(
    entry,
    sys.error(
      s"no frontend entry '$entry' in $ManifestPath (have: ${entries.keys.toList.sorted.mkString(", ")})"
    )
  )

  def content(entry: String): String = resourceText("/" + url(entry))

  /** The route's guard: only names this build produced, so there is no path to
    * sanitise.
    */
  def serves(file: String): Boolean = entries.values.exists(_ == s"web/$file")

  private def resourceText(path: String): String =
    Option(getClass.getResourceAsStream(path))
      .map { in =>
        try new String(in.readAllBytes(), UTF_8)
        finally in.close()
      }
      .getOrElse(
        sys.error(
          s"missing bundled frontend resource $path — run `sbt fh-datastar-view/frontendBundle` (needs node + npm)"
        )
      )
}
