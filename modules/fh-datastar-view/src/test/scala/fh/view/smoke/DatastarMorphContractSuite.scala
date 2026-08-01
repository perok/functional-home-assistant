package fh.view.smoke

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{host, port}
import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}
import fh.view.runtime.Datastar
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
  * (docs/adr/0012-one-pass-addressed-per-client.md). Not a general morph exploration — only the
  * contracts, so a failure here names exactly what broke.
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
  * Pinned bundle: whatever `assets-cache` holds. On upgrade, a failure here
  * means the split is unsafe — NOT that the test needs relaxing.
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
  private val bundle: IO[String] = IO.blocking {
    val dir = LazyList
      .iterate(os.pwd)(_ / os.up)
      .take(4)
      .flatMap(d =>
        List(
          d / "assets-cache",
          d / "modules" / "fh-datastar-view" / "assets-cache"
        )
      )
      .find(os.exists)
      .getOrElse(sys.error(s"no assets-cache found from ${os.pwd}"))
    os.read(
      os.list(dir)
        .find(_.last.endsWith("datastar.js"))
        .getOrElse(sys.error(s"no datastar.js in $dir"))
    )
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
}
