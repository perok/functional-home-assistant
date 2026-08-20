package fh.view.runtime

import cats.effect.IO
import org.http4s.{Header, MediaType, Response}
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

/** The PWA files that make the dashboard installable: the manifest, the service
  * worker, and the icons.
  *
  * The SW is a vite BUILD output like the rest of the frontend (`src/js/sw.ts`
  * → emitted at the output ROOT as `sw.js` — see vite.config.ts; it must not be
  * content-hashed, since the browser fetches SW updates at the registered URL,
  * and it must not live under `web/`, which is served `immutable`) and the
  * manifest is a COMMITTED resource: it cannot ride the vite manifest
  * (`build.manifest` only lists entry chunks) and its relative `start_url`/icon
  * paths must resolve against the app origin, so it is authored alongside the
  * icons it names in `src/main/resources/pwa/`.
  *
  * Their filenames are FIXED, which is exactly the property the SW's update
  * mechanism needs — but it also means NOTHING here may be served `immutable`:
  * the content is not addressed by its name, so a re-validating cache
  * (`no-cache`) is the only way a deployed change reaches clients. For the
  * manifest and the SW, `no-cache` is also the *mechanism*: the browser
  * re-fetches both on every load/register to learn about updates.
  *
  * Its `theme_color`/`background_color` are the DEFAULT theme's light
  * background (`tokens.pkl`'s `primary-background-color`), not its accent: they
  * paint an installed app's chrome and its splash, which sit directly above the
  * page. A cold launch is all they cover — once a document is up,
  * [[Renderer.themeColorTags]] overrides `theme_color` with the live theme's
  * own value, per scheme. A manifest takes no comments, hence the note here.
  *
  * Everything is read ONCE at class-init, and missing files are a HARD failure
  * like [[FrontendAssets]] — a pwa/ without its files is a broken build.
  *
  * The allowlist IS the route: `serve` takes a single path segment, matches it
  * against exactly these four names, and 404s everything else, so there is no
  * path traversal to get wrong.
  */
object PwaAssets {

  /** name -> (classpath resource, media type) */
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

  /** The service worker's URL, relative like every app URL (resolves against
    * the page's `<base href>`). Read from the frontend manifest by ENTRY NAME,
    * so nothing here spells `sw.js` out — and the server hard-fails at startup
    * if the build ever stops producing it.
    */
  val swUrl: String = FrontendAssets.url("sw")

  /** The manifest's URL, for the page head's `<link rel="manifest">`. A fixed
    * committed filename — unlike the SW it does not ride the vite manifest (see
    * the object doc).
    */
  val manifestUrl: String = "manifest.webmanifest"

  /** Serve a PWA file by name, or 404. Same origin, revalidated (`no-cache`) —
    * see the object doc for why nothing here is `immutable`.
    */
  def serve(name: String): IO[Response[IO]] =
    contents.get(name) match {
      case None              => NotFound()
      case Some((bytes, mt)) =>
        Ok(bytes).map(
          _.withContentType(`Content-Type`(mt))
            .putHeaders(Header.Raw(CIString("Cache-Control"), "no-cache"))
        )
    }
}
