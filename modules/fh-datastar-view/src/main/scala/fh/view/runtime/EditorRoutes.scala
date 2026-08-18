package fh.view.runtime

import cats.effect.IO
import fh.view.build.Site
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
  * The front-end is never embedded in Scala strings; it is served as **static
  * classpath resources** via http4s `StaticFile`. Its markup and CSS
  * (`index.html`, `app.css`, `overlay.css`) are hand-written under
  * `resources/editor/`; its JavaScript is authored in `src/js/editor/` and
  * vite-bundled into MANAGED resources at the same classpath prefix, so both
  * halves answer to `/editor/…` and only the build knows the difference.
  * `app.js` carries CodeMirror and lsp-client inside it — there is no separate
  * vendor bundle — and its hashed URL is injected into `index.html` from the
  * build manifest ([[FrontendAssets]]). The editor's own assets are static;
  * only the dashboard `.pkl` files are edited on the filesystem (via
  * `/edit/file`).
  *
  *   - `GET  /edit` the editor page (index.html with base href + config
  *     injected).
  *   - `GET  /edit/{app,overlay}.css` the static stylesheets. The JavaScript is
  *     NOT here: it is content-hashed and served by `Server` from `/web`.
  *   - `GET  /edit/files` the editable source list (top-level `*.pkl` + the
  *     `lib` sources), each with its absolute on-disk path (LSP document URI)
  *     and its `kind`.
  *   - `GET  /edit/dashboards` the slugs currently served (the preview list).
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
    // Read per request from the live site, never captured: both change while
    // the editor is open — that is the point of editing the entrypoint.
    defaultSlug: IO[String],
    liveSlugs: IO[List[String]]
) {

  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "edit" =>
        serveAsset(req, "index.html")

      case req @ GET -> Root / "edit" / asset if staticAssets(asset) =>
        serveAsset(req, asset)

      case GET -> Root / "edit" / "files" =>
        listFiles.flatMap(
          Ok(_).map(
            _.withContentType(`Content-Type`(MediaType.application.json))
          )
        )

      // The dashboards the instance is SERVING. Not derivable from the file
      // list any more: a slug is a key in the entrypoint, so only the runtime
      // knows what the sources currently evaluate to (ADR 0021).
      case GET -> Root / "edit" / "dashboards" =>
        liveSlugs
          .map(slugs => Json.arr(slugs.map(Json.fromString)*).noSpaces)
          .flatMap(
            Ok(_).map(
              _.withContentType(`Content-Type`(MediaType.application.json))
            )
          )

      case req @ GET -> "edit" /: "file" /: rest =>
        resolveEditable(rest) match {
          case None => NotFound()
          case _    =>
            fileService[IO](
              FileService.Config(dashboardsDir.toString, "edit/file")
            ).apply(req).getOrElseF(NotFound())
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
          case None      =>
            ServiceUnavailable("""{"error":"pkl-lsp jar not available"}""")
        }
    }

  /** The static editor assets (served verbatim); everything else under `/edit`
    * is an API route. `index.html` is NOT here — it needs placeholder injection
    * — and neither is any JavaScript: the bundles are content-hashed and served
    * from `/web` ([[FrontendAssets]]).
    */
  private val staticAssets = Set("app.css", "overlay.css")

  /** Serve one static editor asset through http4s [[StaticFile]] straight from
    * the classpath (`/editor/…`) — content type from the extension, caching
    * validators, conditional/range support. The editor's own assets are static;
    * only the dashboard `.pkl` files are edited on the filesystem.
    */
  private def serveAsset(req: Request[IO], name: String): IO[Response[IO]] =
    StaticFile
      .fromResource(s"/editor/$name", Some(req))
      .semiflatMap {

        /** Serve `editor/index.html` (a classpath resource) with the
          * per-request base href + config JSON injected (the two `__…__`
          * placeholders).
          */
        case resp if name.endsWith(".html") =>
          val base = Server.ingressPrefixOf(req).fold("/")(p => s"$p/")

          (for {
            slug <- defaultSlug
            body <- resp.bodyText.compile.string
          } yield {
            val config = Json
              .obj(
                "defaultSlug" -> Json.fromString(slug),
                "basePath" -> Json.fromString(base)
              )
              .noSpaces
            body
              .replace("__BASE__", base)
              .replace("__CONFIG__", config)
              // The editor's own bundle, by entry name: its filename carries
              // a content hash, so the markup cannot spell it out. Relative,
              // like every app URL, so it resolves against <base href>.
              .replace("__APP_JS__", FrontendAssets.url("app"))
          }).map(s =>
            resp
              .withEntity(s)
              .withContentType(`Content-Type`(MediaType.text.html))
          )
        case resp => IO.pure(resp)
      }
      .getOrElseF(
        NotFound("editor index.html not found on the classpath (/editor)")
      )

  /** JSON list of editable sources: `{ name, path, kind }`. `name` is the
    * dashboards-relative path (the editor's identity + `GET/PUT` key), `path`
    * the absolute file (the LSP `file://` document URI).
    *
    * `kind` is `entry` for the one entrypoint, `module` for any other top-level
    * `*.pkl`, `lib` for a library module and `manifest` for `PklProject`. It
    * replaced a per-file `slug`, which is no longer a property a FILE has: a
    * dashboard is a key in the entrypoint (ADR 0021), so what is served comes
    * from `GET /edit/dashboards`.
    *
    * Returns `IO` because it reads the filesystem: the scan owns its own
    * `IO.blocking` so no caller has to know it blocks. Only the scan is inside
    * it — turning the result into JSON is pure and stays on the compute pool.
    */
  private def listFiles: IO[String] =
    IO.blocking(scanSources).map { case (top, lib, project) =>
      def entryJson(rel: String, p: os.Path, kind: String): Json =
        Json.obj(
          "name" -> Json.fromString(rel),
          "path" -> Json.fromString(p.toString),
          "kind" -> Json.fromString(kind)
        )

      def sorted(entries: List[Json]): List[Json] =
        entries.sortBy(_.hcursor.get[String]("name").toOption)

      val topJson =
        sorted(top.map { p =>
          entryJson(
            p.last,
            p,
            if (p.last == Site.EntryFile) "entry" else "module"
          )
        })
      val libJson = sorted(lib.map(p => entryJson(s"lib/${p.last}", p, "lib")))
      val projectJson =
        project.map(p => entryJson(EditorRoutes.Manifest, p, "manifest")).toList

      Json.arr((topJson ++ libJson ++ projectJson)*).noSpaces
    }

  /** The editable sources on disk: top-level entries, `lib/` modules, and the
    * workspace manifest if it exists. Blocking — called only from
    * [[listFiles]], inside its region.
    */
  private def scanSources: (List[os.Path], List[os.Path], Option[os.Path]) = {
    def pklFilesIn(dir: os.Path): List[os.Path] =
      if (os.exists(dir))
        os.list(dir)
          .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
          .toList
      else Nil

    // The workspace's own manifest — a real author file (it declares the package
    // dependencies), even though it has no `.pkl` extension. Editing it takes
    // effect: `PklBuild.staleLockfile` sees the mtime move and re-resolves
    // `PklProject.deps.json` in-process on the next build. The generated
    // lockfile itself, and the machine-specific `.fh/` files, stay hidden.
    val manifest = dashboardsDir / EditorRoutes.Manifest
    (
      pklFilesIn(dashboardsDir),
      pklFilesIn(dashboardsDir / "lib").filter(_.last != "dump.pkl"),
      Option.when(os.exists(manifest))(manifest)
    )
  }

  /** Resolve a request path (`<name>.pkl`, `lib/<name>.pkl`, or the workspace's
    * `PklProject`) to an on-disk source under the dashboards dir, or `None` if
    * it isn't a permitted editable file. Every segment must match
    * [[AssetCache.SafeName]] (rejecting `..`, dot-files and slashes), the leaf
    * must be `*.pkl` (or exactly `PklProject`) and not the generated
    * `dump.pkl`, and only depth 1 (entries) or `lib/` depth 2 is allowed.
    */
  private def resolveEditable(rest: Uri.Path): Option[os.Path] = {
    val segs = rest.segments.map(_.decoded()).toList
    val ok =
      segs.nonEmpty &&
        segs.forall(AssetCache.SafeName.matches) &&
        (segs.last.endsWith(".pkl") ||
          segs == List(EditorRoutes.Manifest)) &&
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

object EditorRoutes {

  /** The workspace's Pkl project manifest — editable (it declares the package
    * dependencies) despite having no `.pkl` extension, which is why it needs
    * naming rather than falling out of the `*.pkl` filters.
    */
  val Manifest: String = "PklProject"
}
