package fh.view.runtime

import cats.effect.IO
import cats.effect.kernel.Ref
import org.http4s.{HttpApp, Status}
import org.http4s.client.Client
import org.http4s.dsl.io.*

/** [[AssetCache]] — startup fetch + disk cache + CSS sub-resource rewriting +
  * the `/assets` serving contract. All network is a stubbed [[Client]]
  * ([[Client.fromHttpApp]]); all disk is a temp dir.
  */
class AssetCacheSuite extends munit.CatsEffectSuite {

  private val cssUrl = "https://cdn.example/lib@1.0/dist/style.min.css"
  private val jsUrl = "https://cdn.example/lib@1.0/dist/app.min.js"

  /** A stylesheet exercising every ref shape: bare-relative (cache+rewrite),
    * quoted-relative (cache+rewrite), absolute and data: (both left alone).
    */
  private val css =
    """@font-face{src:url(font.woff2) format("woff2"),url(https://cdn.example/lib@1.0/dist/font.woff2) format("woff2")}
      |.a{background:url("img.png")}
      |.b{background:url(data:image/gif;base64,R0lGOD)}
      |""".stripMargin

  /** Stub CDN, counting fetches per path. */
  private def stubClient(hits: Ref[IO, Map[String, Int]]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { req =>
      val path = req.uri.path.renderString
      hits.update(m => m.updatedWith(path)(c => Some(c.getOrElse(0) + 1))) *> {
        path.split('/').last match {
          case "style.min.css" => Ok(css)
          case "app.min.js"    => Ok("console.log(1)")
          case "font.woff2"    => Ok("WOFF2BYTES")
          case "img.png"       => Ok("PNGBYTES")
          case _               => NotFound()
        }
      }
    })

  private def build(
      dir: os.Path,
      urls: List[String],
      client: Client[IO]
  ): IO[AssetCache] =
    AssetCache.build(dir, urls, client)

  test("caches urls, rewrites the page urls, passes unknown urls through") {
    val dir = os.temp.dir()
    for {
      hits <- Ref[IO].of(Map.empty[String, Int])
      cache <- build(dir, List(cssUrl, jsUrl), stubClient(hits))
    } yield {
      assertEquals(
        cache.rewrite(cssUrl),
        s"assets/${AssetCache.hashName(cssUrl)}"
      )
      assertEquals(
        cache.rewrite(jsUrl),
        s"assets/${AssetCache.hashName(jsUrl)}"
      )
      assertEquals(cache.rewrite("https://other/x.css"), "https://other/x.css")
    }
  }

  test(
    "css: relative refs (bare + quoted) are fetched, cached, and rewritten; absolute + data: left alone"
  ) {
    val dir = os.temp.dir()
    for {
      hits <- Ref[IO].of(Map.empty[String, Int])
      _ <- build(dir, List(cssUrl), stubClient(hits))
    } yield {
      val cached = os.read(dir / AssetCache.hashName(cssUrl))
      val fontName =
        AssetCache.hashName("https://cdn.example/lib@1.0/dist/font.woff2")
      val imgName =
        AssetCache.hashName("https://cdn.example/lib@1.0/dist/img.png")
      assert(cached.contains(s"url($fontName)"), clue = cached)
      assert(cached.contains(s"url($imgName)"), clue = cached)
      // The absolute CDN fallback inside the src-list and the data: uri survive.
      assert(
        cached.contains("url(https://cdn.example/lib@1.0/dist/font.woff2)"),
        clue = cached
      )
      assert(
        cached.contains("url(data:image/gif;base64,R0lGOD)"),
        clue = cached
      )
      // The sub-resources landed on disk.
      assertEquals(os.read(dir / fontName), "WOFF2BYTES")
      assertEquals(os.read(dir / imgName), "PNGBYTES")
    }
  }

  test("second build is a pure cache hit — no network at all") {
    val dir = os.temp.dir()
    for {
      hits1 <- Ref[IO].of(Map.empty[String, Int])
      _ <- build(dir, List(cssUrl, jsUrl), stubClient(hits1))
      hits2 <- Ref[IO].of(Map.empty[String, Int])
      cache2 <- build(dir, List(cssUrl, jsUrl), stubClient(hits2))
      recorded <- hits2.get
    } yield {
      assertEquals(recorded, Map.empty[String, Int])
      // And it still rewrites (mapping is rebuilt from the urls, not the fetch).
      assertEquals(
        cache2.rewrite(cssUrl),
        s"assets/${AssetCache.hashName(cssUrl)}"
      )
    }
  }

  test("a url that fails to fetch keeps its original url (CDN fallback)") {
    val dir = os.temp.dir()
    val failing = Client.fromHttpApp(HttpApp[IO](_ => NotFound()))
    build(dir, List(cssUrl), failing).map { cache =>
      assertEquals(cache.rewrite(cssUrl), cssUrl)
    }
  }

  test("a failed css sub-resource keeps its ref; the css itself still caches") {
    val dir = os.temp.dir()
    val partial = Client.fromHttpApp(HttpApp[IO] { req =>
      req.uri.path.renderString.split('/').last match {
        case "style.min.css" => Ok(css)
        case _               => NotFound()
      }
    })
    build(dir, List(cssUrl), partial).map { cache =>
      assertEquals(
        cache.rewrite(cssUrl),
        s"assets/${AssetCache.hashName(cssUrl)}"
      )
      val cached = os.read(dir / AssetCache.hashName(cssUrl))
      assert(cached.contains("url(font.woff2)"), clue = cached)
    }
  }

  test("serve: cached names get content-type + immutable; misses 404") {
    val dir = os.temp.dir()
    for {
      hits <- Ref[IO].of(Map.empty[String, Int])
      cache <- build(dir, List(cssUrl, jsUrl), stubClient(hits))
      cssResp <- cache.serve(AssetCache.hashName(cssUrl))
      jsResp <- cache.serve(AssetCache.hashName(jsUrl))
      miss <- cache.serve("nope.css")
      // Names that could escape the dir are rejected outright.
      escape <- cache.serve("..")
      hidden <- cache.serve(".hidden")
    } yield {
      assertEquals(cssResp.status, Status.Ok)
      assertEquals(
        cssResp.headers.get[org.http4s.headers.`Content-Type`].map(_.mediaType),
        Some(org.http4s.MediaType.text.css)
      )
      assert(
        cssResp.headers.headers
          .exists(h =>
            h.name.toString.equalsIgnoreCase("Cache-Control") &&
              h.value.contains("immutable")
          )
      )
      assertEquals(jsResp.status, Status.Ok)
      assertEquals(miss.status, Status.NotFound)
      assertEquals(escape.status, Status.NotFound)
      assertEquals(hidden.status, Status.NotFound)
    }
  }

  test("AssetCache.empty passes everything through and serves nothing") {
    AssetCache.empty.serve("whatever.css").map { resp =>
      assertEquals(AssetCache.empty.rewrite(cssUrl), cssUrl)
      assertEquals(resp.status, Status.NotFound)
    }
  }
}
