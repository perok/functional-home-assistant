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
  * Its `theme_color`/`background_color` are FILLED PER REQUEST from the theme
  * of the dashboard served at `/` ([[fh.view.model.ChromeColors]]); the
  * committed values are only what an instance with no dashboard at all falls
  * back to. A manifest takes no comments, hence the note here — and the two
  * members are NOT the same kind of value, which is the thing to get right:
  *
  *   - `theme_color` is a DEFAULT. Per spec, a page's own
  *     `<meta name="theme-color">` overrides it everywhere the manifest
  *     applies, and every dashboard page carries a scheme-qualified pair
  *     ([[Renderer.themeColorTags]]). So this value is what the surfaces with
  *     no document of ours get: the splash, the task switcher, and any page we
  *     serve without a meta — the failed-dashboard error page, which has no
  *     theme to emit one from.
  *   - `background_color` has NO meta equivalent, and cannot have one: it
  *     paints the window before the stylesheets load, i.e. before there is a
  *     document to carry a meta. The manifest is its ONLY channel, which is the
  *     stronger half of the reason this is derived rather than frozen.
  *
  * A manifest is still one per ORIGIN, so WHICH dashboard's theme it takes is a
  * choice (the one at `/`). Which SCHEME is not, any more: `color_scheme_dark`
  * (w3c/manifest#1207, merged 2026-04-09) is an ordered map of overrides for
  * the themeable members — exactly these two — applied when the OS is in dark
  * mode, and both members are emitted into it.
  *
  * The bare members are therefore the value for light mode AND for every
  * browser that does not implement the override. That is currently Chrome
  * (crbug.com/383165202; WebKit shipped it in May 2026), i.e. almost everyone
  * here — so [[fh.view.model.ChromeColors.base]] is pinned to the DARK colour
  * and the override is a no-op until it flips. That pin is the whole reason
  * this reads as redundant JSON; see that method for what changes when, and for
  * what WebKit pays for it in the meantime.
  *
  * THE TRAP, because it cost a whole investigation: Chrome caches the manifest
  * at install time and refreshes it lazily, so a change here reaches installed
  * phones days later with no deploy to correlate it against — a colour that
  * changes on its own, with `git log` on this file looking innocent because the
  * commit that did it is weeks back. Deriving the value does not remove that
  * lag; it moves what feeds it to something the user can see and control.
  *
  * DO NOT "just drop `theme_color` and let the metas do it" — the advice you
  * will find when you search this (SO 79744082 and its author's dev.to post,
  * both Aug 2025). The metas are right and we emit them, but Chrome diverges
  * from the spec exactly here: an installed PWA's status bar takes the
  * MANIFEST's `theme_color` and ignores the document's meta (crbug 40759522,
  * 40686953, 40634649 — titles readable, bodies need a sign-in). Remove it and
  * a standalone app has no colour source at all, which is the white bar again
  * and permanently. It also explains the observation that started this: a white
  * bar weeks after the tokens moved, with no deploy to blame.
  *
  * The consequence to keep in mind: for an INSTALLED app the manifest is the
  * only channel that reaches the status bar, so `color_scheme_dark` is the only
  * route there will ever be to per-scheme chrome there. The metas serve the
  * surfaces it does not reach — an ordinary browser tab, `minimal-ui`.
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

  private val ManifestName = "manifest.webmanifest"

  /** The committed manifest, parsed once — the shape [[manifest]] recolours. */
  private val manifestJson: Json = {
    val (bytes, _) = contents(ManifestName)
    parse(new String(bytes, UTF_8)).fold(
      err => sys.error(s"/pwa/$ManifestName is not valid JSON: ${err.message}"),
      identity
    )
  }

  /** The manifest, painted `chrome` — the colours of whatever `/` serves.
    *
    * `None` (an instance whose entrypoint never evaluated, or a theme with no
    * background token) serves the committed values unchanged, so an installable
    * app is never held hostage to a dashboard that will not build.
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

  /** Serve a PWA file by name, or 404. Same origin, revalidated (`no-cache`) —
    * see the object doc for why nothing here is `immutable`. The manifest goes
    * through [[manifest]] instead, which has a colour to fill.
    */
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
