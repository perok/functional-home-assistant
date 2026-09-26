package fh.view.runtime

import cats.effect.IO
import fh.view.auth.{AuthGate, Requirement}
import fh.view.build.{PklBuild, Site}
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.server.staticcontent.*

/** The editor: a CodeMirror page over the workspace's Pkl sources, with pkl-lsp
  * over `/lsp/pkl` ([[LspBridge]]). Its assets are classpath resources; only
  * the `.pkl` files are edited on disk. A write lands on disk and the source
  * watcher's reload repaints every preview. `dump.pkl` is generated and never
  * editable.
  */
final class EditorRoutes(
    dashboardsDir: os.Path,
    gate: AuthGate,
    pklLspJar: Option[os.Path],
    // Per request: both change while the entrypoint is edited.
    defaultSlug: IO[String],
    liveSlugs: IO[List[String]]
) {

  /** Admin over the whole group, so a route added later inherits it (ADR 0023).
    * The write path is clamped separately ([[resolveEditable]]).
    */
  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    AuthGate.require(gate, Requirement.Admin) {
      case req @ GET -> Root / "edit" =>
        (serveAsset(req, "index.html"))

      case req @ GET -> Root / "edit" / asset if staticAssets(asset) =>
        (serveAsset(req, asset))

      case GET -> Root / "edit" / "files" =>
        (
          listFiles.flatMap(
            Ok(_).map(
              _.withContentType(`Content-Type`(MediaType.application.json))
            )
          )
        )

      // From the runtime: a slug is a key in the entrypoint (ADR 0021).
      case GET -> Root / "edit" / "dashboards" =>
        (
          liveSlugs
            .map(slugs => Json.arr(slugs.map(Json.fromString)*).noSpaces)
            .flatMap(
              Ok(_).map(
                _.withContentType(`Content-Type`(MediaType.application.json))
              )
            )
        )

      case req @ GET -> "edit" /: "file" /: rest =>
        (resolveEditable(rest) match {
          case None => NotFound()
          case _    =>
            fileService[IO](
              FileService.Config(dashboardsDir.toString, "edit/file")
            ).apply(req).getOrElseF(NotFound())
        })

      case req @ PUT -> "edit" /: "file" /: rest =>
        (resolveEditable(rest) match {
          case None =>
            Forbidden("""{"error":"not an editable dashboard source"}""")
          case Some(p) =>
            req.bodyText.compile.string.flatMap { body =>
              // Identical bytes are skipped: touching the file re-evaluates the
              // site and repaints every connected browser.
              IO.blocking {
                val same = os.exists(p) && os.read(p) == body
                if (!same) os.write.over(p, body)
                !same
              }.flatMap(saved(p, _))
            }
        })

      case GET -> Root / "lsp" / "pkl" =>
        (pklLspJar match {
          case Some(jar) => LspBridge.wsResponse(wsb, jar)
          case None      =>
            ServiceUnavailable("""{"error":"pkl-lsp jar not available"}""")
        })
    }

  // The JavaScript is content-hashed and served from `/web`.
  private val staticAssets = Set("app.css", "overlay.css")

  private def serveAsset(req: Request[IO], name: String): IO[Response[IO]] =
    StaticFile
      .fromResource(s"/editor/$name", Some(req))
      .semiflatMap {
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
              // Content-hashed, so the markup cannot spell it.
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

  /** `used`: whether the entrypoint reads this file ([[PklBuild.fileImports]],
    * from disk now, not the running site). A note, not a gate: a gate would
    * refuse the first half of every two-file change. A failed analysis answers
    * a superset, so `false` is never confidently wrong.
    */
  private def saved(path: os.Path, changed: Boolean): IO[Response[IO]] =
    IO.blocking(PklBuild.fileImports(dashboardsDir, Site.EntryFile))
      .flatMap { reads =>
        val used =
          path == dashboardsDir / Site.EntryFile || reads.contains(path)
        Ok(
          Json
            .obj(
              "written" -> Json
                .fromString(path.relativeTo(dashboardsDir).toString),
              "used" -> Json.fromBoolean(used),
              // So `fh write` does not report work it did not do.
              "changed" -> Json.fromBoolean(changed)
            )
            .noSpaces
        ).map(_.withContentType(`Content-Type`(MediaType.application.json)))
      }

  /** `{ name, path, kind }`: `path` is the LSP document URI. */
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

  // Blocking; only from [[listFiles]].
  private def scanSources: (List[os.Path], List[os.Path], Option[os.Path]) = {
    def pklFilesIn(dir: os.Path): List[os.Path] =
      if (os.exists(dir))
        os.list(dir)
          .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
          .toList
      else Nil

    // An edit takes effect: `PklBuild.staleLockfile` re-resolves on mtime.
    val manifest = dashboardsDir / EditorRoutes.Manifest
    (
      pklFilesIn(dashboardsDir),
      pklFilesIn(dashboardsDir / "lib").filter(_.last != "dump.pkl"),
      Option.when(os.exists(manifest))(manifest)
    )
  }

  /** `<name>.pkl`, `lib/<name>.pkl` or `PklProject` only, each segment an
    * [[AssetCache.SafeName]] (no `..`, dot-files or slashes): no traversal.
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

  // Named because it has no `.pkl` extension.
  val Manifest: String = "PklProject"
}
