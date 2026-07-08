package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.{EntityDecoder, Header, MediaType, Response, Uri}
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.matching.Regex

/** Local cache of the themes' external assets, so a LAN dashboard doesn't
  * depend on CDNs at runtime (offline HA is a feature, not an outage).
  *
  * [[AssetCache.build]] fetches every theme `stylesheets`/`scripts` URL ONCE at
  * startup and persists each under a URL-hashed name in `dir`; `Server` then
  * serves them from `GET /assets/:name` (via [[serve]]) and rewrites the page's
  * `<link>`/`<script>` URLs through [[rewrite]]. A stylesheet's RELATIVE
  * `url(...)` sub-resources (e.g. the Material Symbols woff2 files inside
  * beer.min.css) are fetched and cached too, and the cached CSS is rewritten to
  * their hashed names (still relative — they resolve against `/assets/`);
  * absolute/`data:` refs are left alone, so a CDN fallback source inside an
  * `@font-face` src-list keeps working.
  *
  * Failure = fallback, never an error: a URL that can't be fetched (offline
  * startup with a cold cache) just keeps its original CDN URL in the page, and
  * the next restart retries. Names are content-addressed by URL (hash +
  * original filename), so a version bump in the URL is a new cache entry; stale
  * entries are harmless (delete `dir` to reset). Theme URLs appearing via
  * live-reload after startup pass through to their CDN until the next restart.
  */
final class AssetCache private (
    dir: os.Path,
    // original URL -> local route ("assets/<name>", base-relative); misses
    // pass through.
    mapping: Map[String, String]
) {

  /** The URL the page should reference: the local `/assets/...` route when
    * cached, the original URL otherwise.
    */
  def rewrite(url: String): String = mapping.getOrElse(url, url)

  /** Serve a cached asset by name (the `GET /assets/:name` handler). The name
    * must be a plain hashed filename ([[AssetCache.SafeName]]) that exists in
    * the cache dir — anything else is a 404 (the dir listing IS the whitelist;
    * the route match already guarantees a single path segment). Hash-named
    * content never changes, so it's served immutable.
    */
  def serve(name: String): IO[Response[IO]] =
    if (!AssetCache.SafeName.matches(name)) NotFound()
    else
      IO.blocking {
        val p = dir / name
        Option.when(os.exists(p) && os.isFile(p))(os.read.bytes(p))
      }.flatMap {
        case None => NotFound()
        case Some(bytes) =>
          Ok(bytes).map(
            _.withContentType(`Content-Type`(AssetCache.mediaTypeOf(name)))
              .putHeaders(
                Header.Raw(
                  CIString("Cache-Control"),
                  "public, max-age=31536000, immutable"
                )
              )
          )
      }
}

object AssetCache {

  /** No cache: every rewrite passes through, every serve 404s. The `Server`
    * default, so tests and callers without a cache need no ceremony.
    */
  val empty: AssetCache =
    new AssetCache(os.root / "fh-assets-cache-unused", Map.empty)

  /** Fetch-and-persist every URL (cache hits skip the network entirely) and
    * return the cache. Per-URL failures log and fall back to the original URL —
    * this never raises.
    */
  def build(
      dir: os.Path,
      urls: List[String],
      client: Client[IO]
  ): IO[AssetCache] =
    IO.blocking(os.makeDir.all(dir)) *>
      urls.distinct
        .traverse { url =>
          cacheOne(dir, url, client).attempt.flatMap {
            // Relative (resolves via the page's <base href>) so the same
            // rendered HTML works directly and behind the ingress prefix.
            case Right(name) => IO.pure(Some(url -> s"assets/$name"))
            case Left(err) =>
              IO.println(
                s"asset cache: keeping original URL for $url (${err.getMessage})"
              ).as(None)
          }
        }
        .map(entries => new AssetCache(dir, entries.flatten.toMap))

  /** Cached filename for a URL: short content-address (of the URL, not the
    * bytes) + the URL's filename, so names are unique per URL version but still
    * readable (`a1b2c3d4e5f6-beer.min.css`).
    */
  def hashName(url: String): String = {
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(url.getBytes(StandardCharsets.UTF_8))
    val hash = digest.take(6).map(b => f"$b%02x").mkString
    val last = url.takeWhile(c => c != '?' && c != '#').split('/').last
    val safe = last.replaceAll("[^A-Za-z0-9._-]", "_")
    s"$hash-$safe"
  }

  /** A cached name: leading alphanumeric (rejects dot-files and `..`), then
    * filename characters only.
    */
  private[runtime] val SafeName: Regex = "^[A-Za-z0-9][A-Za-z0-9._-]*$".r

  /** `url(...)` refs in CSS, quoted or bare; group 2 is the ref. */
  private val CssUrlRef: Regex = """url\(\s*(['"]?)([^)'"]+)\1\s*\)""".r

  /** Relative = no scheme, not protocol-relative, not root-relative. */
  private def isRelativeRef(ref: String): Boolean =
    !ref.startsWith("/") && !ref.contains(":")

  private def cacheOne(
      dir: os.Path,
      url: String,
      client: Client[IO]
  ): IO[String] = {
    val name = hashName(url)
    IO.blocking(os.exists(dir / name)).flatMap {
      case true                           => IO.pure(name)
      case false if name.endsWith(".css") => cacheCss(dir, url, name, client)
      case false =>
        fetch(client, url).flatMap(write(dir / name, _)).as(name)
    }
  }

  /** Fetch a stylesheet, cache its relative sub-resources, rewrite their refs
    * to the cached names, persist. Sub-resources are written BEFORE the CSS so
    * an interrupted run never leaves a stylesheet referencing missing files; a
    * failed sub-fetch keeps that ref as-is (a same-src CDN fallback then still
    * applies) and only logs.
    */
  private def cacheCss(
      dir: os.Path,
      url: String,
      name: String,
      client: Client[IO]
  ): IO[String] =
    fetch(client, url).flatMap { bytes =>
      val css = new String(bytes, StandardCharsets.UTF_8)
      val refs = CssUrlRef
        .findAllMatchIn(css)
        .map(_.group(2))
        .filter(isRelativeRef)
        .distinct
        .toList
      refs
        .traverse { ref =>
          val abs = java.net.URI.create(url).resolve(ref).toString
          val subName = hashName(abs)
          val cached = IO.blocking(os.exists(dir / subName)).flatMap {
            case true  => IO.unit
            case false => fetch(client, abs).flatMap(write(dir / subName, _))
          }
          cached.attempt.flatMap {
            case Right(_) => IO.pure(Some(ref -> subName))
            case Left(err) =>
              IO.println(
                s"asset cache: keeping ref $ref in $url (${err.getMessage})"
              ).as(None)
          }
        }
        .map(_.flatten.toMap)
        .flatMap { renames =>
          val rewritten = CssUrlRef.replaceAllIn(
            css,
            m =>
              renames
                .get(m.group(2))
                .map(sub => s"url($sub)")
                .getOrElse(Regex.quoteReplacement(m.matched))
          )
          write(dir / name, rewritten.getBytes(StandardCharsets.UTF_8))
            .as(name)
        }
    }

  private def fetch(client: Client[IO], url: String): IO[Array[Byte]] =
    Uri
      .fromString(url)
      .liftTo[IO]
      .flatMap(client.expect[Array[Byte]](_)(EntityDecoder.byteArrayDecoder))

  private def write(path: os.Path, bytes: Array[Byte]): IO[Unit] =
    IO.blocking(os.write.over(path, bytes, createFolders = true))

  private[runtime] def mediaTypeOf(name: String): MediaType =
    name.split('.').last.toLowerCase match {
      case "css"          => MediaType.text.css
      case "js" | "mjs"   => MediaType.application.javascript
      case "woff2"        => MediaType.font.woff2
      case "woff"         => MediaType.font.woff
      case "ttf"          => MediaType.font.ttf
      case "svg"          => MediaType.image.`svg+xml`
      case "png"          => MediaType.image.png
      case "jpg" | "jpeg" => MediaType.image.jpeg
      case _              => MediaType.application.`octet-stream`
    }
}
