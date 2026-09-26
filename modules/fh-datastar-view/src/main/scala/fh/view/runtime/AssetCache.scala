package fh.view.runtime

import fh.view.telemetry.Logging
import cats.effect.IO
import cats.syntax.all.*
import org.http4s.{EntityDecoder, Header, MediaType, Response, Uri}
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.matching.Regex

/** The themes' external assets cached locally, so a LAN dashboard works with HA
  * offline. Fetched once at startup under a URL-hashed name; a stylesheet's
  * relative `url(...)` sub-resources too, rewritten to their cached names.
  * Absolute refs are left alone, so a CDN fallback in an `@font-face` list
  * still works.
  *
  * A failed fetch keeps the CDN URL and retries next restart. URLs a reload
  * introduces pass through until restart.
  */
final class AssetCache private (
    dir: os.Path,
    mapping: Map[String, String]
) {

  def rewrite(url: String): String = mapping.getOrElse(url, url)

  /** The cache dir is the allowlist; names are URL-hashed, so `immutable`. */
  def serve(name: String): IO[Response[IO]] =
    if (!AssetCache.SafeName.matches(name)) NotFound()
    else
      IO.blocking {
        val p = dir / name
        Option.when(os.exists(p) && os.isFile(p))(os.read.bytes(p))
      }.flatMap {
        case None        => NotFound()
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

  val empty: AssetCache =
    new AssetCache(os.root / "fh-assets-cache-unused", Map.empty)

  /** Never raises: a failed URL keeps its original. */
  def build(
      dir: os.Path,
      urls: List[String],
      client: Client[IO],
      loggerFactory: LoggerFactory[IO] = Logging.console
  ): IO[AssetCache] = {
    val log = loggerFactory.getLoggerFromName("fh.view.runtime.AssetCache")
    IO.blocking(os.makeDir.all(dir)) *>
      urls.distinct
        .traverse { url =>
          cacheOne(dir, url, client, log).attempt.flatMap {
            // Relative, so it works behind the ingress prefix.
            case Right(name) => IO.pure(Some(url -> s"assets/$name"))
            case Left(err)   =>
              // Warn: every page open now waits on the CDN for the script that
              // runs `data-init`, and nothing else says why (issue #75).
              log
                .warn(
                  s"asset cache: keeping original URL for $url (${err.getMessage})"
                )
                .as(None)
          }
        }
        .map(entries => new AssetCache(dir, entries.flatten.toMap))
  }

  /** `a1b2c3d4e5f6-beer.min.css`: a hash of the URL, not the bytes. */
  def hashName(url: String): String = {
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(url.getBytes(StandardCharsets.UTF_8))
    val hash = digest.take(6).map(b => f"$b%02x").mkString
    val last = url.takeWhile(c => c != '?' && c != '#').split('/').last
    val safe = last.replaceAll("[^A-Za-z0-9._-]", "_")
    s"$hash-$safe"
  }

  // A leading alphanumeric rejects dot-files and `..`.
  private[runtime] val SafeName: Regex = "^[A-Za-z0-9][A-Za-z0-9._-]*$".r

  // Group 2 is the ref.
  private val CssUrlRef: Regex = """url\(\s*(['"]?)([^)'"]+)\1\s*\)""".r

  private def isRelativeRef(ref: String): Boolean =
    !ref.startsWith("/") && !ref.contains(":")

  private def cacheOne(
      dir: os.Path,
      url: String,
      client: Client[IO],
      log: SelfAwareStructuredLogger[IO]
  ): IO[String] = {
    val name = hashName(url)
    IO.blocking(os.exists(dir / name)).flatMap {
      case true                           => IO.pure(name)
      case false if name.endsWith(".css") =>
        cacheCss(dir, url, name, client, log)
      case false =>
        fetch(client, url).flatMap(write(dir / name, _)).as(name)
    }
  }

  /** Sub-resources are written first, so an interrupted run never leaves a
    * stylesheet naming missing files.
    */
  private def cacheCss(
      dir: os.Path,
      url: String,
      name: String,
      client: Client[IO],
      log: SelfAwareStructuredLogger[IO]
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
            case Right(_)  => IO.pure(Some(ref -> subName))
            case Left(err) =>
              log
                .warn(
                  s"asset cache: keeping ref $ref in $url (${err.getMessage})"
                )
                .as(None)
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
      .flatMap(
        client.expect[Array[Byte]](_)(using EntityDecoder.byteArrayDecoder)
      )

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
