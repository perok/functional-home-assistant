package fh.view.smoke

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{host, port}
import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}
import fh.view.runtime.{AssetCache, Datastar, Server}
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*

import scala.compiletime.uninitialized
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The TWO Datastar behaviours the self/mount split rests on
  * (docs/adr/0012-one-pass-addressed-per-client.md). Not a general morph
  * exploration — only the contracts, so a failure here names exactly what
  * broke.
  *
  *   1. '''Sibling isolation.''' A container card patches its OWN element
  *      (`<nodeId>-self`), which is a SIBLING of the mount holding its
  *      children. The whole design — "a host's change must not re-render its
  *      children" — is that a top-level patch touches only the element matching
  *      its own id. The control in the same test patches the PARENT instead and
  *      must wipe the mount, so the test cannot pass vacuously.
  *   2. '''`data-ignore-morph` is total.''' A client-owned mount (a React root,
  *      a chart) survives an ancestor morph, AND patches aimed inside it are
  *      dropped. The second half is a FEATURE here (the JS owns that DOM) where
  *      it was fatal for a server-filled panel — and the vendored docs get it
  *      wrong (`attributes.md:218` claims attribute updates still apply).
  *
  * Pinned bundle: whatever `Server.DatastarCdn` names, fetched into a cache. On
  * upgrade, a failure here means the split is unsafe — NOT that the test needs
  * relaxing.
  *
  * Deliberately standalone: a bare page and an SSE stream this test fully
  * controls, so it measures Datastar and nothing of ours.
  */
class DatastarMorphContractSuite extends munit.CatsEffectSuite {

  private var playwright: Playwright = uninitialized
  private var browser: Browser = uninitialized

  override def beforeAll(): Unit = {
    playwright = Playwright.create(
      new Playwright.CreateOptions().setEnv(
        (sys.env ++ Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1")).asJava
      )
    )
    browser = playwright
      .chromium()
      .launch(new BrowserType.LaunchOptions().setHeadless(true))
  }

  override def afterAll(): Unit = {
    if (browser != null) browser.close()
    if (playwright != null) playwright.close()
  }

  test("a patch at the self element leaves its sibling mount untouched") {
    val page =
      """<div id="h">
        |  <div id="h-self">OLD</div>
        |  <div id="h_mount"><p id="h_keep">KEEP</p></div>
        |</div>
        |<div id="c">
        |  <div id="c-self">OLD</div>
        |  <div id="c_mount"><p id="c_keep">KEEP</p></div>
        |</div>""".stripMargin

    val patches = List(
      // The split's patch: aimed at the card's OWN element.
      Datastar.patch("""<div id="h-self">NEW</div>"""),
      // Control: the pre-split patch — the whole card, mount rendered empty.
      Datastar.patch(
        """<div id="c"><div id="c-self">NEW</div><div id="c_mount"></div></div>"""
      )
    )

    served(page, patches).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")

        // (1) The patch applied.
        self <- text(p, "#h-self")
        _ <- IO(assertEquals(self, "NEW", "the self patch must apply"))

        // (2) THE contract: the sibling mount and its contents are untouched,
        // because the fragment does not mention them.
        kept <- text(p, "#h_keep")
        _ <- IO(
          assertEquals(
            kept,
            "KEEP",
            "a patch at the self element must not touch the sibling mount"
          )
        )

        // (3) Control — patching the PARENT with an empty mount wipes it. This
        // is what the split exists to avoid, and without it (2) could pass
        // vacuously if morphs stopped wiping.
        control <- text(p, "#c_keep")
        _ <- IO(
          assertEquals(
            control,
            "<gone>",
            "control: patching the parent wipes the mount, so targeting matters"
          )
        )
      } yield ()
    }
  }

  test("ONE patch event morphs several sibling elements, each by its own id") {
    // One HA frame changes several entities, and today that leaves the server
    // as one SSE event per affected node. Carrying them in a single
    // `datastar-patch-elements` is only possible if Datastar morphs each
    // top-level element in `elements` against its own id. The local reference
    // documents multi-LINE HTML for one element and says nothing about
    // siblings, so this is the empirical answer.
    val page =
      """<div id="one">OLD1</div>
        |<div id="two">OLD2</div>
        |<div id="three">OLD3</div>""".stripMargin

    val patches = List(
      Datastar.patchElements(
        """<div id="one">NEW1</div><div id="three">NEW3</div>"""
      )
    )

    served(page, patches).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")
        one <- text(p, "#one")
        three <- text(p, "#three")
        _ <- IO(assertEquals(one, "NEW1", "the first element must morph"))
        _ <- IO(
          assertEquals(three, "NEW3", "so must the second, by its own id")
        )
        // Not a wholesale body replace: an element the patch does not mention
        // keeps what it had, and keeps its POSITION between the two.
        two <- text(p, "#two")
        _ <- IO(
          assertEquals(two, "OLD2", "an unmentioned sibling must be untouched")
        )
      } yield ()
    }
  }

  test("data-ignore-morph protects a client-owned mount, in both directions") {
    val page =
      """<div id="w">
        |  <span id="w_label">OLD</span>
        |  <div id="w_body" data-ignore-morph><p id="w_keep">KEEP</p></div>
        |</div>""".stripMargin

    val patches = List(
      // An ancestor morph carrying an EMPTY body. The attribute must be on the
      // incoming fragment too — a patch is parsed HTML Datastar never
      // processed, so only a literal satisfies the both-sides check.
      Datastar.patch(
        """<div id="w"><span id="w_label">NEW</span>""" +
          """<div id="w_body" data-ignore-morph></div></div>"""
      ),
      // Aimed INSIDE the protected subtree.
      Datastar.patch("""<p id="w_keep">CHANGED</p>""")
    )

    served(page, patches).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")

        // (1) The ancestor morph still applies to everything else.
        label <- text(p, "#w_label")
        _ <- IO(
          assertEquals(label, "NEW", "the ancestor morph must still apply")
        )

        // (2) The protected subtree survives it.
        kept <- text(p, "#w_keep")
        _ <- IO(
          assertEquals(
            kept,
            "KEEP",
            "a client-owned mount must survive an ancestor morph"
          )
        )

        // (3) And the server cannot patch INTO it. Fatal for a server-filled
        // panel; required for a mount whose JS owns the DOM.
        _ <- IO(
          assertEquals(
            kept,
            "KEEP",
            "a patch aimed inside a protected mount must be dropped"
          )
        )
      } yield ()
    }
  }

  private def text(page: Page, selector: String): IO[String] =
    IO.blocking(
      Option(page.querySelector(selector)).fold("<gone>")(_.innerText)
    )

  private def eventually[A](io: IO[A], timeout: FiniteDuration = 10.seconds)(
      cond: A => Boolean
  ): IO[A] =
    Stream
      .repeatEval(io <* IO.sleep(20.millis))
      .filter(cond)
      .head
      .compile
      .lastOrError
      .timeout(timeout)

  private def shell(body: String) =
    s"""<!doctype html><html><head><script type="module" src="/datastar.js"></script></head>
       |<body data-init="@get('/sse')">
       |$body
       |<div id="done">no</div>
       |</body></html>""".stripMargin

  /** The bundle the app actually ships, from the on-disk asset cache — the
    * pinned version is the whole point, so this must never reach the CDN.
    */
  /** The Datastar bundle this suite runs against — the SAME build production
    * serves, because it comes from the same pinned constant
    * ([[fh.view.runtime.Server.DatastarCdn]]). Nothing here restates a version,
    * so the two cannot drift.
    *
    * It is read from an asset cache and downloaded once if absent. NOT from
    * `assets-cache` in the repo: that is gitignored, so it is empty in CI and
    * differs between developers — a contract test against "whatever this
    * machine downloaded at some point" is pinned to nothing.
    *
    * The directory is `FH_ASSETS_DIR` (production's own knob) or a user cache
    * dir, which CI caches between runs so the download happens approximately
    * never.
    */
  private val bundle: IO[String] = IO.blocking {
    val dir = sys.env
      .get("FH_ASSETS_DIR")
      .map(os.Path(_, os.pwd))
      .getOrElse(
        os.Path(
          net.harawata.appdirs.AppDirsFactory.getInstance
            .getUserCacheDir("fh", "0.0.1", "perok")
        ) / "assets"
      )
    val file = dir / AssetCache.hashName(Server.DatastarCdn)
    if (!os.exists(file)) {
      os.makeDir.all(dir)
      val res = java.net.http.HttpClient
        .newHttpClient()
        .send(
          java.net.http.HttpRequest
            .newBuilder(java.net.URI.create(Server.DatastarCdn))
            .build(),
          java.net.http.HttpResponse.BodyHandlers.ofString()
        )
      if (res.statusCode() != 200)
        sys.error(s"GET ${Server.DatastarCdn} -> ${res.statusCode()}")
      os.write.over(file, res.body())
    }
    os.read(file)
  }

  private def served(
      body: String,
      patches: List[ServerSentEvent]
  ): Resource[IO, (Page, Uri)] =
    for {
      js <- bundle.toResource
      // Patched last and outside every fixture, so its arrival means the whole
      // sequence was processed — no arbitrary sleep.
      all = patches :+ Datastar.patch("""<div id="done">yes</div>""")
      routes = HttpRoutes.of[IO] {
        case GET -> Root =>
          Ok(shell(body)).map(
            _.withContentType(`Content-Type`(MediaType.text.html))
          )
        case GET -> Root / "datastar.js" =>
          Ok(js).map(
            _.withContentType(`Content-Type`(MediaType.application.javascript))
          )
        case GET -> Root / "sse" =>
          Ok(
            Stream
              .emits(all)
              .covary[IO]
              .metered(50.millis)
              .append(Stream.never[IO])
          )
      }
      bound <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpApp(routes.orNotFound)
        .withShutdownTimeout(0.seconds)
        .build
      context <- Resource.make(IO.blocking(browser.newContext()))(c =>
        IO.blocking(c.close())
      )
      page <- Resource.make(IO.blocking(context.newPage()))(p =>
        IO.blocking(p.close())
      )
    } yield (page, bound.baseUri)

  test("__ifmissing only seeds a signal nothing has read yet") {
    // Why the tabs seed asserts instead of initialising. Datastar creates a
    // signal the moment an expression READS one, so an `__ifmissing` seed that
    // appears after any reader finds the key already present — as "" — and
    // correctly declines. A tabs bar reads `$ui_<id>` and renders BEFORE the
    // panel that seeds it, so the seed would never fire.
    val page =
      """<div id="reader" data-text="$late"></div>
        |<div data-signals__ifmissing="{ late: 1 }"></div>
        |<div id="reader2" data-text="$asserted"></div>
        |<div data-signals="{ asserted: 1 }"></div>
        |<div id="together" data-signals__ifmissing="{ own: 1 }" data-text="$own"></div>
        |<div data-signals__ifmissing="{ kid: 1 }"><span id="child" data-text="$kid"></span></div>""".stripMargin

    served(page, Nil).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")
        late <- text(p, "#reader")
        asserted <- text(p, "#reader2")
        own <- text(p, "#together")
        kid <- text(p, "#child")
        _ <- IO {
          // Read first, seeded after: never initialised.
          assertEquals(
            late,
            "",
            "__ifmissing must not seed an already-read signal"
          )
          // The same shape with a plain seed: asserted, so it lands.
          assertEquals(
            asserted,
            "1",
            "a plain seed asserts regardless of readers"
          )
          // On ONE element, signals apply before the reader — so it works.
          assertEquals(own, "1", "same-element seed beats its own reader")
          println(s"SPIKE|parent-seeds-child-reads = '$kid'")
        }
      } yield ()
    }
  }
}
