package fh.view.runtime

import cats.effect.IO
import fh.view.model.ChromeColors
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{Header, MediaType, Response}
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8

/** The manifest, service worker and icons. Fixed names — the SW must be fetched
  * at its registered URL — so nothing is `immutable`: `no-cache` is how a
  * change reaches clients. The manifest is committed (vite's manifest lists
  * only entry chunks); the SW is built by vite outside `web/`.
  *
  * The manifest's colours are filled per request from the theme at `/`
  * ([[fh.view.model.ChromeColors]]). `background_color` has no meta equivalent.
  * `color_scheme_dark` (w3c/manifest#1207) carries the dark pair, but Chrome
  * ignores it (crbug.com/383165202), so `ChromeColors.base` is pinned dark.
  *
  * '''Traps that cost an investigation each.''' Chrome caches the manifest at
  * install and refreshes lazily, so a change reaches phones days later with no
  * deploy to blame. And do not drop `theme_color` for the metas: an installed
  * PWA's status bar takes the manifest's value and ignores the meta (crbug
  * 40759522, 40686953, 40634649) — without it the bar is white, permanently.
  *
  * Read once at class-init; a missing file fails hard. `serve` matches one
  * segment against these four names, so there is no traversal.
  */
object PwaAssets {

  private val files: Map[String, (String, MediaType)] = Map(
    "manifest.webmanifest" -> (
      "/pwa/manifest.webmanifest",
      MediaType
        .parse(
          "application/manifest+json"
        )
        .getOrElse(MediaType.application.json)
    ),
    "sw.js" -> ("/sw.js", MediaType.application.javascript),
    "icon-192.png" -> ("/pwa/icon-192.png", MediaType.image.png),
    "icon-512.png" -> ("/pwa/icon-512.png", MediaType.image.png)
  )

  private val contents: Map[String, (Array[Byte], MediaType)] =
    files.map { case (name, (path, mt)) =>
      val bytes = Option(getClass.getResourceAsStream(path))
        .map { in =>
          try in.readAllBytes()
          finally in.close()
        }
        .getOrElse(
          sys.error(
            s"missing PWA resource $path — the manifest/icons are committed under src/main/resources/pwa/, the service worker comes from `sbt fh-datastar-view/frontendBundle` (needs node + npm)"
          )
        )
      name -> (bytes, mt)
    }

  // By entry name; startup fails if the build stops producing it.
  val swUrl: String = FrontendAssets.url("sw")

  val manifestUrl: String = "manifest.webmanifest"

  private val ManifestName = "manifest.webmanifest"

  private val manifestJson: Json = {
    val (bytes, _) = contents(ManifestName)
    parse(new String(bytes, UTF_8)).fold(
      err => sys.error(s"/pwa/$ManifestName is not valid JSON: ${err.message}"),
      identity
    )
  }

  /** `None` serves the committed colours, so installing never depends on a
    * dashboard that builds.
    */
  def manifest(chrome: Option[ChromeColors]): IO[Response[IO]] =
    respond(
      chrome
        .fold(manifestJson) { c =>
          manifestJson.deepMerge(
            Json.obj(
              "theme_color" -> Json.fromString(c.base),
              "background_color" -> Json.fromString(c.base),
              "color_scheme_dark" -> Json.obj(
                "theme_color" -> Json.fromString(c.dark),
                "background_color" -> Json.fromString(c.dark)
              )
            )
          )
        }
        .spaces2
        .getBytes(UTF_8),
      contents(ManifestName)._2
    )

  def serve(name: String): IO[Response[IO]] =
    contents.get(name) match {
      case None              => NotFound()
      case Some((bytes, mt)) => respond(bytes, mt)
    }

  private def respond(bytes: Array[Byte], mt: MediaType): IO[Response[IO]] =
    Ok(bytes).map(
      _.withContentType(`Content-Type`(mt))
        .putHeaders(Header.Raw(CIString("Cache-Control"), "no-cache"))
    )
}
