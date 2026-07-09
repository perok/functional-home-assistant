package fh.view.runtime

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2

/** The dashboard **editor** surface: a CodeMirror 6 page that edits the Pkl
  * dashboard sources on disk, with live preview and Pkl language support
  * (highlighting locally, completion/hover/diagnostics from the real pkl-lsp).
  *
  * The front-end (HTML/CSS/JS) lives as real files under `resources/editor/`
  * (`index.html`, `app.js`, `app.css`, `overlay.js`, `overlay.css`) and is
  * served straight from disk — NOT embedded in Scala string literals. So it is
  * lintable, reads like normal web code, and edits go live on a browser refresh
  * with no sbt rebuild.
  *
  *   - `GET  /edit` the editor page (index.html with base href + config injected).
  *   - `GET  /edit/app.js|app.css|overlay.js|overlay.css` the static assets.
  *   - `GET  /edit/files` the editable source list (top-level `*.pkl` entries +
  *     the `lib` sources), each with its absolute on-disk path (LSP document URI).
  *   - `GET  /edit/file/<rel>` read a source; `PUT` write it. A write lands on
  *     disk and the existing `ServerApp.watchSources` reload repaints every open
  *     preview — no coupling here.
  *   - `GET  /lsp/pkl` the language-server WebSocket ([[LspBridge]]).
  *
  * Editing is **deliberately ungated** for now, safe only because the server
  * binds loopback by default (see the plan's "Deferred: feature gate + security"
  * section). The write path is still clamped: only `<name>.pkl` and
  * `lib/<name>.pkl` under the dashboards dir, each segment matching
  * [[AssetCache.SafeName]] (which rejects `..` and slashes) — no traversal.
  *
  * `dump.pkl` is excluded everywhere: it's the generated, gitignored dump, not
  * an author source.
  */
final class EditorRoutes(
    dashboardsDir: os.Path,
    pklLspJar: Option[os.Path],
    defaultSlug: String
) {

  // The editor front-end assets sit next to the dashboards under resources/.
  private val editorDir = dashboardsDir / os.up / "editor"

  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "edit" => editorPage(req)

      case GET -> Root / "edit" / "app.js" =>
        serveAsset("app.js", MediaType.application.javascript)
      case GET -> Root / "edit" / "app.css" =>
        serveAsset("app.css", MediaType.text.css)
      case GET -> Root / "edit" / "overlay.js" =>
        serveAsset("overlay.js", MediaType.application.javascript)
      case GET -> Root / "edit" / "overlay.css" =>
        serveAsset("overlay.css", MediaType.text.css)

      case GET -> Root / "edit" / "files" =>
        Ok(listFiles).map(
          _.withContentType(`Content-Type`(MediaType.application.json))
        )

      case GET -> "edit" /: "file" /: rest =>
        resolveEditable(rest) match {
          case None => NotFound()
          case Some(p) =>
            IO.blocking(Option.when(os.exists(p) && os.isFile(p))(os.read(p)))
              .flatMap {
                case None => NotFound()
                case Some(text) =>
                  Ok(text).map(
                    _.withContentType(`Content-Type`(MediaType.text.plain))
                  )
              }
        }

      case req @ PUT -> "edit" /: "file" /: rest =>
        resolveEditable(rest) match {
          case None =>
            Forbidden("""{"error":"not an editable dashboard source"}""")
          case Some(p) =>
            req.bodyText.compile.string.flatMap { body =>
              IO.blocking(os.write.over(p, body)) *> NoContent()
            }
        }

      case GET -> Root / "lsp" / "pkl" =>
        pklLspJar match {
          case Some(jar) => LspBridge.wsResponse(wsb, jar)
          case None =>
            ServiceUnavailable("""{"error":"pkl-lsp jar not available"}""")
        }
    }

  /** Serve `editor/index.html` with the per-request base href + config JSON
    * injected (the two `__…__` placeholders). Read from disk so edits are live.
    */
  private def editorPage(req: Request[IO]): IO[Response[IO]] = {
    val base = Server.ingressPrefixOf(req).fold("/")(p => s"$p/")
    val config = Json
      .obj(
        "defaultSlug" -> Json.fromString(defaultSlug),
        "basePath" -> Json.fromString(base)
      )
      .noSpaces
    val indexPath = editorDir / "index.html"
    IO.blocking(
      Option.when(os.exists(indexPath) && os.isFile(indexPath))(os.read(indexPath))
    ).flatMap {
      case None => NotFound(s"editor assets not found at $editorDir")
      case Some(html) =>
        val page = html.replace("__BASE__", base).replace("__CONFIG__", config)
        Ok(page).map(_.withContentType(`Content-Type`(MediaType.text.html)))
    }
  }

  /** Serve one static editor asset from `resources/editor/` by exact name. */
  private def serveAsset(name: String, mt: MediaType): IO[Response[IO]] = {
    val p = editorDir / name
    IO.blocking(Option.when(os.exists(p) && os.isFile(p))(os.read.bytes(p)))
      .flatMap {
        case None => NotFound()
        case Some(bytes) =>
          Ok(bytes).map(_.withContentType(`Content-Type`(mt)))
      }
  }

  /** JSON list of editable sources: `{ name, path, slug? }`. `name` is the
    * dashboards-relative path (the editor's identity + `GET/PUT` key), `path`
    * the absolute file (the LSP `file://` document URI); entries carry their
    * `slug` (filename sans `.pkl`) so the page can preview them.
    */
  private def listFiles: String = {
    def entryJson(rel: String, p: os.Path, slug: Option[String]): Json =
      Json.obj(
        "name" -> Json.fromString(rel),
        "path" -> Json.fromString(p.toString),
        "slug" -> slug.fold(Json.Null)(Json.fromString)
      )

    val top = os
      .list(dashboardsDir)
      .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
      .map(p => entryJson(p.last, p, Some(p.last.stripSuffix(".pkl"))))
      .toList
      .sortBy(_.hcursor.get[String]("name").toOption)

    val libDir = dashboardsDir / "lib"
    val lib =
      if (os.exists(libDir))
        os.list(libDir)
          .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
          .filter(_.last != "dump.pkl")
          .map(p => entryJson(s"lib/${p.last}", p, None))
          .toList
          .sortBy(_.hcursor.get[String]("name").toOption)
      else Nil

    Json.arr((top ++ lib)*).noSpaces
  }

  /** Resolve a request path (`<name>.pkl` or `lib/<name>.pkl`) to an on-disk
    * source under the dashboards dir, or `None` if it isn't a permitted
    * editable file. Every segment must match [[AssetCache.SafeName]] (rejecting
    * `..`, dot-files and slashes), the leaf must be `*.pkl` and not the
    * generated `dump.pkl`, and only depth 1 (entries) or `lib/` depth 2 is
    * allowed.
    */
  private def resolveEditable(rest: Uri.Path): Option[os.Path] = {
    val segs = rest.segments.map(_.decoded()).toList
    val ok =
      segs.nonEmpty &&
        segs.forall(AssetCache.SafeName.matches) &&
        segs.last.endsWith(".pkl") &&
        segs.last != "dump.pkl"
    if (!ok) None
    else
      segs match {
        case name :: Nil          => Some(dashboardsDir / name)
        case "lib" :: name :: Nil => Some(dashboardsDir / "lib" / name)
        case _                    => None
      }
  }
}
