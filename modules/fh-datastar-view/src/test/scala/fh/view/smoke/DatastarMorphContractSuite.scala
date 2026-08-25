package fh.view.smoke

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{host, port}
import com.microsoft.playwright.{Browser, Page, Playwright}
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
  * (docs/adr/0012-each-session-renders-what-it-is-owed.md). Not a general morph
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
class DatastarMorphContractSuite extends munit.CatsEffectSuite with SlowSuite {

  private var playwright: Playwright = uninitialized
  private var browser: Browser = uninitialized

  override def beforeAll(): Unit = {
    playwright = Playwright.create(
      new Playwright.CreateOptions().setEnv(
        (sys.env ++ Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1")).asJava
      )
    )
    browser = SmokeSuite.connectOrLaunch(playwright, Nil)
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

  test("an action's datastar frames are applied on 2xx and DROPPED on 4xx") {
    // Settles where a failure may be reported (docs/adr/0025-a-value-in-flight.md).
    // Reading the pinned bundle suggests a 4xx body is still parsed — `onopen`
    // dispatches the error event on `status >= 400` and then neither throws nor
    // returns, so `onmessage` looks reachable. Running it says otherwise, which
    // is the whole reason this suite exists.
    //
    // The 2xx half is the control, and it is what makes the 4xx half mean
    // something: the SAME body, the same route, the same assertion — so a
    // failure here is about the STATUS and cannot be a malformed frame.
    //
    // Consequence: an error's own body is not a channel. A tap that fails has
    // to be recovered from by the client (clearing its pending signal off the
    // `datastar-fetch` error event), not by bytes the server sends back. It
    // also confirms ADR 0019's "the response body is unreachable here" for the
    // error path, by a second route.
    val page =
      """<button id="ok" data-on:click="@post('/allow')">ok</button>
        |<button id="no" data-on:click="@post('/refuse')">no</button>
        |<div id="allowed" data-text="$allowed"></div>
        |<div id="refused" data-text="$refused"></div>""".stripMargin

    def frame(name: String) =
      fs2.Stream
        .emit(Datastar.patchSignals(s"{$name: 'applied'}"))
        .covary[IO]

    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "allow" =>
        Ok(frame("allowed")).map(
          _.withContentType(`Content-Type`(MediaType.`text/event-stream`))
        )
      case POST -> Root / "refuse" =>
        BadRequest(frame("refused")).map(
          _.withContentType(`Content-Type`(MediaType.`text/event-stream`))
        )
    }

    servedWith(page, Nil, routes).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")
        _ <- IO.blocking(p.locator("#no").click())
        _ <- IO.blocking(p.locator("#ok").click())
        // The 2xx frame is the ORDERING GATE as well as the control: it was
        // sent second, so once it has landed the 4xx one has had its chance.
        _ <- eventually(text(p, "#allowed"))(_ == "applied")
        refused <- text(p, "#refused")
      } yield assertEquals(
        refused,
        "",
        "a 4xx body's datastar frames must NOT reach the store"
      )
    }
  }

  test(
    "a data-effect that clears the signal it reads settles, and survives a race"
  ) {
    // The clearing rule pending signals rest on
    // (docs/adr/0025-a-value-in-flight.md): the client writes `_g__pending` on tap,
    // the SERVER writes `ui_g`, and pending clears itself once the committed
    // value catches up. The effect READS and WRITES the same signal, which is
    // the shape that loops — so this measures that it settles, and counts the
    // runs rather than trusting that it does.
    //
    // The second half is why the clear is derived from the committed value
    // instead of sent in the frame: with two taps in flight, a commit for the
    // FIRST must leave the second's pending alone. A server-sent clear could
    // not tell them apart.
    val clear =
      "window.__runs = (window.__runs || 0) + 1; " +
        "$_g__pending !== '' && $ui_g == $_g__pending && ($_g__pending = '')"

    val page =
      s"""<div data-signals="{ui_g: '', _g__pending: ''}"></div>
         |<div data-effect="$clear"></div>
         |<div id="shown" data-text="$$_g__pending || $$ui_g"></div>
         |<div id="pending" data-text="$$_g__pending"></div>
         |<button id="tapA" data-on:click="$$_g__pending = 'a'">a</button>
         |<button id="tapB" data-on:click="$$_g__pending = 'b'">b</button>
         |<button id="commitA" data-on:click="@post('/commit/a')">ca</button>
         |<button id="commitB" data-on:click="@post('/commit/b')">cb</button>""".stripMargin

    val routes = HttpRoutes.of[IO] { case POST -> Root / "commit" / which =>
      Ok(
        fs2.Stream
          .emit(Datastar.patchSignals(s"{ui_g: '$which'}"))
          .covary[IO]
      ).map(_.withContentType(`Content-Type`(MediaType.`text/event-stream`)))
    }

    def click(p: Page, id: String) = IO.blocking(p.locator(id).click())

    servedWith(page, Nil, routes).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")

        // (1) The tap shows instantly, from pending alone.
        _ <- click(p, "#tapA")
        _ <- eventually(text(p, "#shown"))(_ == "a")

        // (2) A SECOND tap while the first is unresolved. Pending is now 'b'.
        _ <- click(p, "#tapB")
        _ <- eventually(text(p, "#shown"))(_ == "b")

        // (3) The FIRST tap's commit lands. Pending must survive it — this is
        // the race a server-sent clear gets wrong.
        _ <- click(p, "#commitA")
        _ <- eventually(text(p, "#pending"), 2.seconds)(_ == "b")
        shown <- text(p, "#shown")
        _ <- IO(
          assertEquals(
            shown,
            "b",
            "a commit for an OVERTAKEN tap must not clear the pending one"
          )
        )

        // (4) The second tap's commit lands: pending clears itself, and the
        // display is unchanged because the committed value now says the same.
        _ <- click(p, "#commitB")
        _ <- eventually(text(p, "#pending"))(_ == "")
        settled <- text(p, "#shown")
        _ <- IO(assertEquals(settled, "b", "the committed value takes over"))

        // (5) It SETTLED. A self-referential effect that looped would still be
        // running; the count is small and stops growing.
        runs <- IO.blocking(p.evaluate("window.__runs").asInstanceOf[Int])
        _ <- IO.sleep(300.millis)
        later <- IO.blocking(p.evaluate("window.__runs").asInstanceOf[Int])
        _ <- IO {
          assertEquals(
            later,
            runs,
            s"the effect must stop re-running (ran $runs)"
          )
          assert(runs < 20, s"the effect settled but ran $runs times")
        }
      } yield ()
    }
  }

  test(
    "null DELETES a signal and orphans its bindings; '' is a present attribute"
  ) {
    // Three facts about the pinned bundle, and a control. Together they decide
    // whether a pending signal may share a name with an ADR 0019 `busy` one
    // (ADR 0025), and none of them is what the docs suggest.
    //
    //   1. `data-attr` handles null CORRECTLY: an expression evaluating to null
    //      removes the attribute. `data-attr:aria-label="$foo"` is fine, and
    //      nothing here says otherwise.
    //   2. But `''` is PRESENT, not absent — HTML's boolean-attribute spelling
    //      (`disabled=""`). Datastar's own docs spell the predicate for exactly
    //      this (`data-attr="{disabled: $foo == ''}"`), and `data-style`
    //      DIFFERS: there `''` is falsy and restores the original inline style.
    //      Two plugins, two readings of the same value.
    //   3. ASSIGNING null does not set a signal to null — it DELETES it, and
    //      every binding already subscribed is orphaned. The store's proxy is
    //      explicit (`if (a == null) delete r[o]`); reading the name afterwards
    //      re-creates it as `''`, which is why this has to check the VALUE and
    //      drive the check from a SECOND signal. A server-sent `{"s": null}`
    //      does the same thing from the other side.
    //
    // The rest of the page keeps working — this is not a poisoned store, it is
    // one dead signal — which is precisely what makes it hard to spot.
    //
    // The CONTROL runs LAST and is not optional: a throwing expression DOES
    // break this page, which contaminated the first version of this test. It
    // is here so "nothing was reported" above is a measurement rather than a
    // claim about the harness.
    def page(nullBtn: String) =
      s"""<div data-signals="{ s: 'v', probe: 0 }"></div>
         |<input id="i" data-attr:disabled="$$s" />
         |<input id="j" data-attr:disabled="$$s === 'HIDE' ? null : $$s" />
         |<div id="r" data-text="$$probe + ':' + JSON.stringify($$s)"></div>
         |<button id="empty" data-on:click="$$s = ''">e</button>
         |<button id="val" data-on:click="$$s = 'v'">v</button>
         |<button id="hide" data-on:click="$$s = 'HIDE'">h</button>
         |<button id="probe" data-on:click="$$probe = $$probe + 1">p</button>
         |<button id="boom" data-on:click="$$s = JSON.parse('{')">b</button>
         |$nullBtn""".stripMargin

    val clientNull = """<button id="nul" data-on:click="$s = null">n</button>"""
    val serverNull =
      """<button id="nul" data-on:click="@post('/nullify')">n</button>"""

    val routes = HttpRoutes.of[IO] { case POST -> Root / "nullify" =>
      Ok(
        fs2.Stream.emit(Datastar.patchSignals("""{"s": null}""")).covary[IO]
      ).map(_.withContentType(`Content-Type`(MediaType.`text/event-stream`)))
    }

    def attr(p: Page, id: String) =
      IO.blocking(Option(p.locator(id).getAttribute("disabled")))
    def click(p: Page, id: String) = IO.blocking(p.locator(id).click())
    val settle = IO.sleep(300.millis)

    def run(body: String) =
      servedWith(body, Nil, routes).use { case (p, uri) =>
        for {
          errs <- IO(scala.collection.mutable.ListBuffer.empty[String])
          _ <- IO.blocking(
            p.onConsoleMessage(m =>
              if (m.`type`() == "error") { val _ = errs += m.text() }
            )
          )
          _ <- IO.blocking(p.onPageError(e => { val _ = errs += e }))
          _ <- IO.blocking(p.navigate(uri.renderString))
          _ <- eventually(text(p, "#done"))(_ == "yes")

          start <- attr(p, "#i")
          blank <- click(p, "#empty") *> settle *> attr(p, "#i")
          _ <- click(p, "#val") *> settle
          // (1) An expression EVALUATING to null, signal untouched.
          byExpr <- click(p, "#hide") *> settle *> attr(p, "#j")
          untouched <- attr(p, "#i")
          _ <- click(p, "#val") *> settle
          // (3) Assign/patch null, then read `s` through an effect driven by a
          // DIFFERENT signal — anything bound to `s` is orphaned by the delete.
          _ <- click(p, "#nul") *> settle
          readBack <- click(p, "#probe") *> settle *> text(p, "#r")
          orphaned <- click(p, "#empty") *> settle *> attr(p, "#i")
          quiet <- IO(errs.toList)
          _ <- click(p, "#boom") *> settle
          control <- IO(errs.toList)
        } yield (
          start,
          blank,
          byExpr,
          untouched,
          readBack,
          orphaned,
          quiet,
          control
        )
      }

    for {
      fromClient <- run(page(clientNull))
      fromServer <- run(page(serverNull))
    } yield List(("client", fromClient), ("server", fromServer)).foreach {
      case (
            who,
            (
              start,
              blank,
              byExpr,
              untouched,
              readBack,
              orphaned,
              quiet,
              control
            )
          ) =>
        assertEquals(start, Some("v"), s"$who: a plain string is the value")
        assertEquals(blank, Some(""), s"$who: '' PRESENTS the attribute")
        assertEquals(
          byExpr,
          None,
          s"$who: an expression yielding null removes it"
        )
        assertEquals(
          untouched,
          Some("HIDE"),
          s"$who: and never touched the signal"
        )
        // probe moved, so the page is ALIVE; `s` came back as "" rather than
        // the "v" it held, so the key itself was removed and re-created.
        assertEquals(readBack, """1:""""", s"$who: null DELETED the signal")
        assertEquals(
          orphaned,
          Some("v"),
          s"$who: and every binding on it is orphaned — this rewrite never lands"
        )
        assertEquals(quiet, Nil, s"$who: all of it with nothing reported")
        assert(
          control.nonEmpty,
          s"$who: CONTROL — a throwing expression IS reported, so that silence is real"
        )
    }
  }

  test("data-bind makes a co-located data-attr:value inert from the start") {
    // What a range input's position actually obeys, measured rather than
    // reasoned from the HTML spec — which is how the claim this replaces got
    // into ADR 0025.
    //
    // The spec half is real: `value` is a CONTENT attribute, so it sets the
    // default and the browser's dirty-value flag makes it inert once the value
    // has been set through the IDL. The part that reasoning missed is WHO sets
    // it — `data-bind` writes `.value` on its first pass, before any user
    // touches anything, so on an input carrying both bindings the attribute
    // never moves the thumb at all. Not "inert after a drag": inert at t=0.
    //
    // The slider carries both (`data-attr:value` for the committed signal,
    // `data-bind` for `_slide`), so this is that element, reduced.
    val page =
      """<div data-signals="{ a: '20', b: '80' }"></div>
        |<input id="both" type="range" min="0" max="100" data-attr:value="$a" data-bind="b" />
        |<input id="attr" type="range" min="0" max="100" data-attr:value="$a" />
        |<button id="bumpA" data-on:click="$a = '55'">a</button>""".stripMargin

    def prop(p: Page, id: String) =
      IO.blocking(p.locator(id).evaluate("el => el.value").toString)
    def attrOf(p: Page, id: String) =
      IO.blocking(Option(p.locator(id).getAttribute("value")))
    val settle = IO.sleep(300.millis)

    served(page, Nil).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")

        bothStart <- prop(p, "#both")
        attrStart <- prop(p, "#attr")
        // The attribute IS written on both — it simply loses on the bound one.
        bothAttr <- attrOf(p, "#both")
        // A later server write to the committed signal: still no movement.
        bothAfter <- IO.blocking(p.locator("#bumpA").click()) *> settle *> prop(
          p,
          "#both"
        )
        // On the UNBOUND input the same write does move it, so the assertions
        // above cannot pass because `data-attr:value` is broken in general.
        attrAfter <- prop(p, "#attr")
        // ...until the value has been set through the IDL, which is the dirty
        // flag doing what the spec says.
        _ <- IO.blocking(p.locator("#attr").fill("10"))
        attrDirty <- IO.blocking(p.locator("#bumpA").click()) *> settle *> prop(
          p,
          "#attr"
        )
      } yield {
        assertEquals(
          bothStart,
          "80",
          "data-bind wins on first paint — the attribute never positions a bound input"
        )
        assertEquals(
          bothAttr,
          Some("20"),
          "and data-attr DID write it; the attribute is present and simply ignored"
        )
        assertEquals(
          bothAfter,
          "80",
          "a later write to the attr-bound signal still moves nothing"
        )
        assertEquals(
          attrStart,
          "20",
          "CONTROL: unbound, the attribute does position the thumb"
        )
        assertEquals(
          attrAfter,
          "55",
          "CONTROL: and keeps positioning it while the input stays clean"
        )
        assertEquals(
          attrDirty,
          "10",
          "CONTROL: once the value is set through the IDL, the dirty flag makes it inert"
        )
      }
    }
  }

  private def served(
      body: String,
      patches: List[ServerSentEvent]
  ): Resource[IO, (Page, Uri)] =
    servedWith(body, patches, HttpRoutes.empty[IO])

  private def servedWith(
      body: String,
      patches: List[ServerSentEvent],
      extra: HttpRoutes[IO]
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
        .withHttpApp((extra <+> routes).orNotFound)
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
