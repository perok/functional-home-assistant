package fh.view.runtime

import io.circe.parser.parse

import java.nio.charset.StandardCharsets.UTF_8

/** The bundled frontend (`src/js` -> vite -> managed resources), addressed by
  * ENTRY NAME rather than by filename.
  *
  * The filenames carry a content hash (`web/app-D4DwBZ53.js`), so nothing on
  * either side of the wire can spell one out: vite writes a manifest naming
  * what it built, this reads it, and Scala and the editor's `index.html` ask
  * for `"app"`. The hash is what makes [[serve]]-ing them `immutable` honest —
  * a rebuilt bundle is a different URL, so no client can hold a stale one.
  *
  * Read ONCE at class-init, and a HARD failure when anything is missing rather
  * than a fallback: an absent manifest means the frontend was never bundled,
  * which is a broken build, not a mode to support. Failing at startup beats
  * failing per-request with a page that looks fine and silently does nothing.
  */
object FrontendAssets {

  /** Where vite writes what it built (`build.manifest` in vite.config.ts),
    * relative to the classpath root it is copied to.
    */
  private val ManifestPath = "/web/manifest.json"

  /** Entry name (`shell`, `app`, `overlay`) -> its built, hashed path
    * (`web/app-D4DwBZ53.js`). Keyed by name and not by vite's own key, which is
    * the SOURCE path (`src/js/editor/app.js`) and would put the layout of the
    * source tree into Scala.
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

  /** The URL for an entry, RELATIVE so it resolves against the page's `<base
    * href>` — `/` served directly, the ingress prefix behind the HA supervisor
    * proxy, exactly like every other app URL.
    */
  def url(entry: String): String = entries.getOrElse(
    entry,
    sys.error(
      s"no frontend entry '$entry' in $ManifestPath (have: ${entries.keys.toList.sorted.mkString(", ")})"
    )
  )

  /** An entry's built JavaScript, for the one bundle that is INLINED rather
    * than linked (the page shell — see [[Server.UrlSyncScript]]).
    */
  def content(entry: String): String = resourceText("/" + url(entry))

  /** Is `file` something this build actually produced? The guard on the route
    * that serves them: it makes the filename un-forgeable rather than
    * sanitising a path, so traversal is not a thing that can be got wrong.
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
