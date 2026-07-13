package fh.view.testkit

import cats.effect.{IO, Resource}
import fh.view.runtime.{AssetCache, Server}
import org.http4s.{Request, Response, Status}
import org.http4s.client.Client

/** An offline [[AssetCache]] for the browser smoke suite. `theme-beer.pkl`
  * and [[Server.DatastarCdn]] point at `cdn.jsdelivr.net` in production; this
  * session's egress policy blocks that host, and a hermetic test shouldn't
  * depend on any CDN being reachable anyway. Instead of hand-rolling
  * [[AssetCache]]'s fetch/rewrite/CSS-sub-resource logic, this drives the
  * REAL [[AssetCache.build]] against a fake [[Client]] that resolves the
  * exact URLs `theme-beer.pkl` references to the files vendored under
  * `src/test/resources/vendor/` (see `VENDORED.md` there) — everything else
  * 404s, which `AssetCache` already tolerates per-URL (keeps the original
  * CDN URL, logs, moves on).
  */
object VendoredAssets {

  // Must track theme-beer.pkl's `beerVersion`/`beerCdn`.
  private val BeerCdn: String =
    "https://cdn.jsdelivr.net/npm/beercss@4.0.23/dist/cdn"

  private val urlToResource: Map[String, String] = Map(
    Server.DatastarCdn -> "vendor/datastar.js",
    s"$BeerCdn/beer.min.css" -> "vendor/beer.min.css",
    s"$BeerCdn/beer.min.js" -> "vendor/beer.min.js",
    s"$BeerCdn/material-symbols-outlined.woff2" -> "vendor/material-symbols-outlined.woff2"
  )

  /** The CDN URLs a fixture dashboard using `theme-beer.pkl` needs cached —
    * `AssetCache.build`'s `urls` argument. */
  val urls: List[String] = urlToResource.keySet.toList

  private def readResource(path: String): Array[Byte] = {
    val in = getClass.getClassLoader.getResourceAsStream(path)
    if (in == null) sys.error(s"vendored test asset not found on classpath: $path")
    try in.readAllBytes()
    finally in.close()
  }

  /** Serves the vendored bytes for a known URL, 404s any other request —
    * never touches the network.
    */
  private val client: Client[IO] = Client[IO] { (req: Request[IO]) =>
    val body = urlToResource.get(req.uri.renderString).map(readResource)
    Resource.pure(
      body match {
        case Some(bytes) => Response[IO](Status.Ok).withEntity(bytes)
        case None        => Response[IO](Status.NotFound)
      }
    )
  }

  /** Build the [[AssetCache]] used by [[fh.view.runtime.TestServer.served]]:
    * a fresh temp dir, populated from the vendored files via the real
    * [[AssetCache.build]] pipeline (so CSS sub-resource rewriting is exactly
    * production behaviour) against the offline [[client]].
    */
  def build: IO[AssetCache] =
    IO.blocking(os.temp.dir(prefix = "fh-smoke-assets")).flatMap { dir =>
      AssetCache.build(dir, urls, client)
    }
}
