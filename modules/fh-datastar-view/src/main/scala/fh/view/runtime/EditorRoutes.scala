package fh.view.runtime

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.server.staticcontent.*

/** The dashboard **editor** surface: a CodeMirror 6 page that edits the Pkl
  * dashboard sources on disk, with live preview and Pkl language support
  * (highlighting locally, completion/hover/diagnostics from the real pkl-lsp).
  *
  * The front-end (HTML/CSS/JS) lives as real files under `resources/editor/`
  * (`index.html`, `app.js`, `app.css`, `overlay.js`, `overlay.css`, plus the
  * esbuild-bundled `vendor.js`) — NOT embedded in Scala strings — and is served
  * as **static classpath resources** via http4s `StaticFile`. The editor's own
  * assets are static; only the dashboard `.pkl` files are edited on the
  * filesystem (via `/edit/file`).
  *
  *   - `GET  /edit` the editor page (index.html with base href + config
  *     injected).
  *   - `GET  /edit/{app,vendor,overlay}.js|{app,overlay}.css` the static
  *     assets.
  *   - `GET  /edit/files` the editable source list (top-level `*.pkl` entries +
  *     the `lib` sources), each with its absolute on-disk path (LSP document
  *     URI).
  *   - `GET  /edit/file/<rel>` read a source; `PUT` write it. A write lands on
  *     disk and the existing `ServerApp.watchSources` reload repaints every
  *     open preview — no coupling here.
  *   - `GET  /lsp/pkl` the language-server WebSocket ([[LspBridge]]).
  *
  * Editing is **deliberately ungated** for now, safe only because the server
  * binds loopback by default (see the plan's "Deferred: feature gate +
  * security" section). The write path is still clamped: only `<name>.pkl` and
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

  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "edit" =>
        serveAsset(req, "index.html")

      case req @ GET -> Root / "edit" / asset if staticAssets(asset) =>
        serveAsset(req, asset)

      case GET -> Root / "edit" / "files" =>
        Ok(listFiles).map(
          _.withContentType(`Content-Type`(MediaType.application.json))
        )

      case req @ GET -> "edit" /: "file" /: rest =>
        resolveEditable(rest) match {
          case None => NotFound()
          case _ =>
            fileService[IO](FileService.Config(dashboardsDir.toString, "edit/file")).apply(req).getOrElseF(NotFound())
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

  /** The static editor assets (served verbatim); everything else under `/edit`
    * is an API route. `index.html` is NOT here — it needs placeholder
    * injection.
    */
  private val staticAssets =
    Set("app.js", "vendor.js", "app.css", "overlay.js", "overlay.css")


  /** Serve one static editor asset through http4s [[StaticFile]] straight from
    * the classpath (`/editor/…`) — content type from the extension, caching
    * validators, conditional/range support. The editor's own assets are static;
    * only the dashboard `.pkl` files are edited on the filesystem.
    */
  private def serveAsset(req: Request[IO], name: String): IO[Response[IO]] =
    StaticFile
      .fromResource(s"/editor/$name", Some(req))
      .semiflatMap {
        /** Serve `editor/index.html` (a classpath resource) with the per-request base
         * href + config JSON injected (the two `__…__` placeholders).
         */
        case resp if name.endsWith(".html") =>
          val base = Server.ingressPrefixOf(req).fold("/")(p => s"$p/")
          val config = Json
            .obj(
              "defaultSlug" -> Json.fromString(defaultSlug),
              "basePath" -> Json.fromString(base)
            )
            .noSpaces

          resp.bodyText.compile.string
            .map(_.replace("__BASE__", base).replace("__CONFIG__", config))
            .map(s =>
              resp
                .withEntity(s)
                .withContentType(`Content-Type`(MediaType.text.html))
            )
        case resp => IO.pure(resp)
      }
      .getOrElseF(
        NotFound("editor index.html not found on the classpath (/editor)")
      )

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
