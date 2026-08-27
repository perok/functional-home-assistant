package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  Region,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import io.circe.Json
import org.http4s.*
import org.http4s.headers.{
  `Cache-Control`,
  `Content-Type`,
  `If-None-Match`,
  ETag
}
import org.http4s.implicits.*
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** The routes a browser hits directly: the document, its view-state carriers,
  * the `/system/pkl` endpoints, and the small pure helpers those rest on.
  *
  * Nothing here drives the live stream — that starts with [[SharedPassSuite]].
  */
class ServerRoutesSuite extends ServerHarness {

  /** [[Patches.resume]] run against a FRESH cache — these contracts are about
    * which patches come out, and a per-call cache keeps one from depending on
    * what another test rendered. Mirrors `resume`'s own parameter list so a
    * call site reads the same either way.
    */

  /** One frame RECORDED for the slug, then PULLED by one viewer — the whole
    * path a live change now takes, in the shape a test can assert on.
    *
    * `holds` is what that viewer's DOM already had, and it is what suppresses a
    * redundant patch now that no shared structure answers that for everybody.
    * `from` is its cursor: `0` means "tell me everything this log knows".
    */

  /** A request carrying ui state in the Datastar signal payload, as an SSE
    * reconnect does (and, in the body, as every action POST does).
    */

  private def signalled(signals: String): Request[IO] =
    Request[IO](
      Method.GET,
      uri"/".withQueryParam("datastar", signals)
    )

  test("uiStateOf reads ui. params and ui_ signals, ignoring the rest") {
    assertEquals(
      Server.uiStateOf(get("ui.c" -> "1", "other" -> "x")),
      Map("c" -> "1")
    )
    // raw value, no parsing here
    assertEquals(Server.uiStateOf(get("ui.c" -> "abc")), Map("c" -> "abc"))
    assertEquals(Server.uiStateOf(get("other" -> "x")), Map.empty)
    assertEquals(Server.uiStateOf(get()), Map.empty)
    // Signals carry the same fact, as a number rather than a string.
    assertEquals(
      Server.uiStateOf(signalled("""{"ui_c":1,"conn":"x"}""")),
      Map("c" -> "1")
    )
    // Both present: the signal is the live value, the URL only trails it.
    assertEquals(
      Server
        .uiStateOf(
          Request[IO](
            Method.GET,
            uri"/"
              .withQueryParam("ui.c", "0")
              .withQueryParam("datastar", """{"ui_c":1}""")
          )
        ),
      Map("c" -> "1")
    )
  }

  test("ui-state round-trip: ui.<tabsId>=1 opens the index-1 surface") {
    val r = tabsRenderer
    val uiState = Server.uiStateOf(get("ui.c" -> "1"))
    // The server seeds the open set (and bakes) from this selection.
    assertEquals(r.surfaces.selectedSurfaces(uiState), Set("c_t1"))
    assert(r.renderBody(Map.empty, uiState).contains("tab_c: 1"))
    assert(
      r.surfaces.uiStateAnomalies(uiState).isEmpty,
      clue = r.surfaces.uiStateAnomalies(uiState)
    )
  }

  test("ui-state round-trip: a malformed value falls back to index 0 + warns") {
    val r = tabsRenderer
    val uiState = Server.uiStateOf(get("ui.c" -> "abc"))
    assertEquals(r.surfaces.selectedSurfaces(uiState), Set("c_t0"))
    assert(r.renderBody(Map.empty, uiState).contains("tab_c: 0"))
    assertEquals(r.surfaces.uiStateAnomalies(uiState).size, 1)
  }

  test("parseValue picks the most specific JSON type") {
    assertEquals(Server.parseValue("128"), Json.fromInt(128))
    assertEquals(Server.parseValue("21.5"), Json.fromDoubleOrNull(21.5))
    assertEquals(Server.parseValue("heat"), Json.fromString("heat"))
  }

  test("escapeHtml neutralizes HTML metacharacters") {
    assertEquals(
      Server.escapeHtml("""A & B <x> "q" 'z'"""),
      "A &amp; B &lt;x&gt; &quot;q&quot; &#39;z&#39;"
    )
  }

  // A minimal static dashboard (no live entities) for exercising the page shell.
  private def titleDash(slug: String, title: Option[String]): Dashboard =
    Dashboard(
      cards = Map(
        "col" -> CardDef(
          "<div>{{#children}}{{{html}}}{{/children}}</div>",
          regions = Map("children" -> Region())
        )
      ),
      card = LayoutNode.Component("col"),
      slug = slug,
      title = title
    )

  /** GET the page shell for `dash` (served at its own slug) and return the
    * HTML.
    */

  private def pageHtml(dash: Dashboard, query: String = ""): IO[String] =
    (for {
      store <- StateStore.inMemory(Map.empty)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(dash))
      )
      sessions <- Sessions.create
      // Stub HA: the SSE/patch path never calls it (an unexpected registry call
      // still raises); the store is driven in-memory, so the empty seed is inert.
      fake <- FakeHomeAssistant.create(Nil)
      body <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map(dash.slug -> ref),
          dash.slug,
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          server.routes.orNotFound
            .run(
              Request[IO](
                Method.GET,
                Uri.unsafeFromString(s"/d/${dash.slug}$query")
              )
            )
            .flatMap(_.body.through(fs2.text.utf8.decode).compile.string)
        }
    } yield body).timeout(30.seconds)

  /** Run one arbitrary request against a real server, for the routes that are
    * about the response rather than the page.
    */
  private def response(uri: String): IO[Response[IO]] =
    (for {
      store <- StateStore.inMemory(Map.empty)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(titleDash("home", None)))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      resp <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("home" -> ref),
          "home",
          sessions,
          TestAuth.openGate
        )
        .use(
          _.routes.orNotFound
            .run(Request[IO](Method.GET, Uri.unsafeFromString(uri)))
        )
    } yield resp).timeout(30.seconds)

  test("the frontend bundles are served immutable, and only by built name") {
    // The filename carries a content hash, so a rebuild is a NEW url and a
    // client can never hold a stale one — which is what makes `immutable`
    // honest here rather than a gamble.
    val app = FrontendAssets.url("app")
    for {
      hit <- response("/" + app)
      cached = hit.headers.get(CIString("Cache-Control")).map(_.head.value)
      miss <- response("/web/app.js")
    } yield {
      assertEquals(hit.status, Status.Ok)
      assertEquals(cached, Some("public, max-age=31536000, immutable"))
      // Not an allowlist applied to a path — a name the manifest does not list
      // is not a route at all, so there is no traversal to sanitise.
      assertEquals(miss.status, Status.NotFound)
    }
  }

  test("the PWA files are served, revalidated, and only the four of them") {
    // The manifest and the service worker are the UPDATE mechanism — fixed
    // filenames the browser re-fetches on every load/register — so they must be
    // revalidated, never `immutable`. Unlike the hashed bundles, a same-named
    // redeploy would otherwise strand clients on the first version forever.
    for {
      manifest <- response("/manifest.webmanifest")
      sw <- response("/sw.js")
      icon <- response("/icon-512.png")
      cache = (r: Response[IO]) =>
        r.headers.get(CIString("Cache-Control")).map(_.head.value)
      miss <- response("/sw-ish.js")
      other <- response("/pwa/manifest.webmanifest")
    } yield {
      assertEquals(manifest.status, Status.Ok)
      assertEquals(
        manifest.headers.get[`Content-Type`].map(_.value),
        Some("application/manifest+json")
      )
      assertEquals(cache(manifest), Some("no-cache"))
      assertEquals(sw.status, Status.Ok)
      assertEquals(
        sw.headers.get[`Content-Type`].map(_.value),
        Some("application/javascript")
      )
      assertEquals(cache(sw), Some("no-cache"))
      assertEquals(icon.status, Status.Ok)
      assertEquals(
        icon.headers.get[`Content-Type`].map(_.value),
        Some("image/png")
      )
      assertEquals(cache(icon), Some("no-cache"))
      assertEquals(miss.status, Status.NotFound)
      // The four live at the app root (relative to `<base href>`), not under
      // `web/` (immutable) or `pwa/`.
      assertEquals(other.status, Status.NotFound)
    }
  }

  test("the page head links the manifest and registers the service worker") {
    pageHtml(titleDash("home", None)).map { html =>
      // The manifest link rides the <base href> like every other app URL.
      assert(
        html.contains(
          s"""<link rel="manifest" href="${PwaAssets.manifestUrl}">"""
        ),
        clue = html
      )
      // Registration is the shell helper called with the manifest-resolved URL —
      // nothing in the page spells `sw.js` out (except the shell's own route).
      assert(html.contains(s"fhRegisterSw('${PwaAssets.swUrl}')"), clue = html)
      // ...and the shell really defines it (the `if(window.fhScroll)`-style
      // guard has no SW analogue: an unregistered SW is silent by design, so
      // the presence of the call is all the page can assert).
      assert(
        FrontendAssets.content("shell").contains("window.fhRegisterSw="),
        clue = "shell must define fhRegisterSw"
      )
    }
  }

  test("a page on its way out stops painting connection banners") {
    // Navigating away aborts the SSE stream, so the OUTGOING document spends
    // its last moments reporting an outage. The class the shell sets on
    // `pagehide` is what the base CSS hides the banners by; the Pkl suite
    // (`components.test.pkl`) pins the other half of the pair.
    IO {
      val shell = FrontendAssets.content("shell")
      // Substrings only, no quote character: the bundle is minified, and this
      // build's minifier rewrites every string literal to a backtick one.
      assert(shell.contains("fh-leaving"), clue = shell)
      // Two `pagehide` listeners: `fhScroll`'s offset save, and this one.
      assertEquals(
        shell.sliding("pagehide".length).count(_ == "pagehide"),
        2,
        clue = shell
      )
    }
  }

  test("the phone's chrome follows the theme's background, per scheme") {
    val themed = titleDash("home", None).copy(theme =
      Theme(
        tokens = Map("primary-background-color" -> "#fafafa"),
        tokensDark = Map("primary-background-color" -> "#111111")
      )
    )
    pageHtml(themed).map { html =>
      assert(
        html.contains(
          """<meta name="theme-color" media="(prefers-color-scheme: light)" content="#fafafa">"""
        ),
        clue = html
      )
      assert(
        html.contains(
          """<meta name="theme-color" media="(prefers-color-scheme: dark)" content="#111111">"""
        ),
        clue = html
      )
      // Both are scheme-qualified: an unqualified theme-color would win over
      // whichever of the two matched, and pin the chrome to one scheme.
      assertEquals(
        html.sliding("theme-color".length).count(_ == "theme-color"),
        2,
        clue = html
      )
    }
  }

  test("a theme that names no background emits no theme-color at all") {
    // Better nothing than a wrong colour: with no meta the browser keeps its
    // own chrome, which at least matches the rest of the device.
    pageHtml(titleDash("home", None)).map { html =>
      assert(!html.contains("theme-color"), clue = html)
    }
  }

  test("the connection-lost banner LATCHES once the retries are exhausted") {
    // Every fetch type other than retrying/error/retries-failed classifies as
    // "fine", so without a latch any event after the failure cleared the banner
    // — and because the handler is debounced, a `finished` in the same 600ms
    // window could swallow the failure before it ever painted. Either way the
    // page went back to looking connected while it was not.
    pageHtml(titleDash("home", None)).map { html =>
      val handler = html.linesIterator
        .find(_.contains(s"data-on:${Server.StreamEvent}"))
        .getOrElse(fail(s"no stream handler in the shell: $html"))
      // 2 is absorbing: the assignment can only ever read 2 back out.
      assert(handler.contains("$_sse >= 2 ? 2 :"), clue = handler)
      // ...and the classification it guards is unchanged.
      assert(handler.contains("'retries-failed' ? 2"), clue = handler)
      assert(handler.contains("'retrying'"), clue = handler)
      // The two banners still read the same signal, so the latch reaches them.
      assert(html.contains("""data-show="$_sse < 2""""), clue = html)
      assert(html.contains("""data-show="$_sse >= 2""""), clue = html)
      // Recovery from a mere blip is NOT latched — 1 must still fall back to 0,
      // or an ordinary refetch would pin "Reconnecting…" forever.
      assert(!handler.contains("$_sse >= 1"), clue = handler)
    }
  }

  test("only the STREAM's own fetch moves the connection banner") {
    // `datastar-fetch` fires for every fetch on the page, and the stream is one
    // of many. Bound to it directly, an action decided the stream's state in
    // both directions: a rejected click raised "Reconnecting…" on a live
    // connection, and a stream frame landing while a tap's POST failed put the
    // banner away again. The split is made in `shell.ts`, per event, because a
    // debounced handler only ever sees the last event of its window — so the
    // shell must bind the FILTERED event, and never the raw one.
    pageHtml(titleDash("home", None)).map { html =>
      val handler = html.linesIterator
        .find(_.contains(s"data-on:${Server.StreamEvent}"))
        .getOrElse(fail(s"no stream handler in the shell: $html"))
      assert(handler.contains("debounce"), clue = handler)
      assert(!html.contains("data-on:datastar-fetch__debounce"), clue = html)
      // The shell's own JS is what narrows it, and it is inlined into this very
      // page — so both halves of the protocol are checked here rather than one
      // of them alone. Matched loosely (a name, a property) because the bundle
      // is minified: anything shaped like source would be asserting on esbuild.
      val emitters =
        html.sliding(Server.StreamEvent.length).count(_ == Server.StreamEvent)
      assert(emitters >= 2, clue = s"only $emitters mention(s) of the event")
      assert(
        html.contains("document.body"),
        clue = "shell.ts no longer filters the stream event to the <body> fetch"
      )
    }
  }

  test("the page shell seeds the popup selection from the URL, or empty") {
    // A refresh with ?ui.popups=<id> must re-open the dialog: the seeded signal
    // reaches the SSE connect, which renders it back into its host. An unknown
    // id is dropped rather than seeded. The popup host is the one selection
    // with no card template to declare its signal — it lives in theme.chrome —
    // so the shell declares it, but it is `ui_<hostId>` like every other.
    val dash = titleDash("home", None).copy(
      surfaces = Map("det" -> Surface(LayoutNode.Component("col")))
    )
    for {
      seeded <- pageHtml(dash, "?ui.popups=det")
      unknown <- pageHtml(dash, "?ui.popups=nope")
      none <- pageHtml(dash)
    } yield {
      assert(seeded.contains(s"""$PopupSig: \'det\'"""), seeded)
      assert(unknown.contains(s"""$PopupSig: \'\'"""), unknown)
      assert(none.contains(s"""$PopupSig: \'\'"""), none)
      // And the URL mirror helper is defined before Datastar can call it.
      assert(none.contains("window.fhUrl="), none)
    }
  }

  test("the page restores its scroll offset, last of all") {
    // Crossing dashboards is a document load (ADR 0002) and the page holds a
    // streaming fetch, so the browser will neither bfcache it nor restore the
    // offset itself — the shell has to. It must be the LAST thing in the body:
    // the restore reads the document's height, so anything emitted after it
    // could still move the floor.
    for {
      html <- pageHtml(titleDash("home", None))
    } yield {
      assert(html.contains("window.fhScroll="), html)
      // The storage key and `manual` are asserted as bare substrings because
      // the shell is a MINIFIED bundle now (src/js/shell.ts): quote style and
      // parameter names are the bundler's to choose, so anything shaped like
      // source would pin the wrong thing. `manual` matters — with the browser's
      // own `auto` restore still armed it can re-apply its offset, 0 on this
      // path, after ours has landed.
      assert(html.contains("fh.scroll."), html)
      assert(html.contains("scrollRestoration"), html)
      assert(html.contains("manual"), html)
      // Guarded, and the guard is a SEPARATE script tag from the inlined
      // shell: a parse error in one does not stop the next, so this is reached
      // exactly when the shell is broken and turns a silent loss into a named
      // console error.
      assert(html.contains("if(window.fhScroll)"), html)
      assert(
        html.contains("console.error('fh: the page shell did not run"),
        html
      )
      val call = s"<script>${Server.scrollCall("home")}</script>"
      assert(html.contains(call), html)
      val after = html.drop(html.indexOf(call) + call.length)
      assertEquals(
        after.linesIterator.map(_.trim).filter(_.nonEmpty).toList,
        List("</body>", "</html>"),
        clue = html
      )
    }
  }

  test("the data-init SSE URL carries what the page is showing") {
    // The first connect carries NO signals (data-init fires before Datastar has
    // merged the descendants' data-signals), so without this the server would
    // repaint the DEFAULT tab over the correct first paint — and the URL mirror
    // would then follow the repaint down to ui.c=0.
    val dash = titleDash("home", None).copy(
      surfaces = Map("det" -> Surface(LayoutNode.Component("col")))
    )
    for {
      restored <- pageHtml(dash, "?ui.c=1&ui.popups=det")
      plain <- pageHtml(dash)
    } yield {
      assert(restored.contains("sse/dashboard/home/patch?ui.c=1"), restored)
      assert(restored.contains("ui.popups=det"), restored)
      // ...and the CURSOR: the version this document was rendered at, so the
      // first connect resumes from it instead of taking the no-cursor branch
      // and inner-patching a body the document already contains.
      List(
        Server.HeadHashSignal,
        Server.StyleHashSignal,
        Server.LogIdSignal,
        Server.StoreVersionSignal
      ).foreach(f =>
        assert(
          restored.contains(s"${Server.cursorParam(f)}="),
          clue = (f, restored)
        )
      )
      // With nothing else to restore the cursor still rides — every document
      // knows what it is showing.
      assert(
        plain.contains(s"patch?${Server.cursorParam(Server.HeadHashSignal)}="),
        plain
      )
      // `always` is load-bearing, not decoration: the default retry mode
      // reconnects a DROPPED stream but treats one the server ended as
      // finished — which is how the server closes a stalled connection.
      assert(plain.contains("retry:'always'"), plain)
      // ...and the cursor is asked for BACK. It is `_`-prefixed so Datastar's
      // default filter drops it from every request; this include is what puts
      // it on the one request that reads it.
      //
      // The VALUE, not just the word: `SseRetry` interpolates `SseInclude`, and
      // an object's `val` reading one declared after it gets `null` silently.
      // That shipped `include:'null'` — a regex matching no signal name — so
      // every reconnect arrived with no cursor, no `conn` and no tab selection.
      // `CursorSuite` could not see it: it reads `Server.SseInclude` directly,
      // which is correct by then. Only the served bytes carry the null.
      assert(
        plain.contains(s"filterSignals:{include:'${Server.SseInclude}'"),
        clue = (Server.SseInclude, plain)
      )
    }
  }

  test("the popup selection: signal wins when present, URL only seeds") {
    // A first connect has the param and no signal.
    assertEquals(
      Server.uiStateOf(get("ui.popups" -> "det")).get("popups"),
      Some("det")
    )
    // A reconnect after the user closed it carries `ui_popups: ""` alongside
    // the page's now-stale param: the signal is authoritative, so the dialog
    // stays closed rather than resurrecting on every retry. This is the same
    // precedence every tab selection gets — one rule, not a popup rule.
    assertEquals(
      Server
        .uiStateOf(
          Request[IO](
            Method.GET,
            uri"/"
              .withQueryParam("ui.popups", "det")
              .withQueryParam("datastar", """{"ui_popups":""}""")
          )
        )
        .get("popups"),
      Some("")
    )
    assertEquals(Server.uiStateOf(get()).get("popups"), None)
  }

  test("openPopup adopts only a surface this dashboard can actually host") {
    val r = Renderer.create(
      titleDash("home", None).copy(
        surfaces = Map(
          "det" -> Surface(LayoutNode.Component("col")),
          // Baked into a node, so it hosts in a panel — never the popup mount.
          "panel" -> Surface(
            LayoutNode.Component("col"),
            bakeInto = Some("c"),
            bakeAs = Some("panel")
          )
        )
      )
    )
    assertEquals(r.surfaces.openPopup(Map("popups" -> "det")), Some("det"))
    // Closed, unknown (renamed/removed/another dashboard's), and a baked panel
    // id are all refused — adopting one would put the session in a state its
    // renderer cannot serve.
    assertEquals(r.surfaces.openPopup(Map("popups" -> "")), None)
    assertEquals(r.surfaces.openPopup(Map("popups" -> "nope")), None)
    assertEquals(r.surfaces.openPopup(Map("popups" -> "panel")), None)
    assertEquals(r.surfaces.openPopup(Map.empty), None)
    // ...and an adopted one joins the open set through the ordinary path.
    assert(r.surfaces.selectedSurfaces(Map("popups" -> "det")).contains("det"))
  }

  test("page <title> uses the dashboard's authored title when present") {
    pageHtml(titleDash("home", Some("My Home"))).map { html =>
      assert(
        html.contains(s"""<title id="${Server.TitleId}">My Home</title>""")
      )
    }
  }

  test("page <title> falls back to the slug when no title is authored") {
    pageHtml(titleDash("energy", None)).map { html =>
      assert(html.contains(s"""<title id="${Server.TitleId}">energy</title>"""))
    }
  }

  test("page <title> escapes an authored title") {
    pageHtml(titleDash("x", Some("A & <B>"))).map { html =>
      assert(
        html.contains(
          s"""<title id="${Server.TitleId}">A &amp; &lt;B&gt;</title>"""
        )
      )
    }
  }

  test("/system/pkl serves a provided module and 404s an unknown one") {
    val system = fh.view.build.SystemPkl(
      hass = Some("// schema"),
      dump = Some("kitchen = 1")
    )
    val (dumpStatus, dumpBody, hassStatus, missStatus) = (for {
      store <- StateStore.inMemory(Map.empty)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(titleDash("home", None)))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("home" -> ref),
          "home",
          sessions,
          TestAuth.openGate,
          assets = AssetCache.empty,
          systemPkl = system
        )
        .use { server =>
          val routes = server.routes.orNotFound
          val get = (p: String) =>
            routes.run(Request[IO](Method.GET, Uri.unsafeFromString(p)))
          for {
            dump <- get("/system/pkl/dump.pkl")
            dumpBody <- dump.body.through(fs2.text.utf8.decode).compile.string
            hass <- get("/system/pkl/hass.pkl")
            miss <- get("/system/pkl/nope.pkl")
          } yield (dump.status, dumpBody, hass.status, miss.status)
        }
    } yield out)
      .timeout(30.seconds)
      .unsafeRunSync()

    assertEquals(dumpStatus, Status.Ok)
    assertEquals(dumpBody, "kitchen = 1")
    assertEquals(hassStatus, Status.Ok)
    assertEquals(missStatus, Status.NotFound)
  }

  test(
    "/system/pkl serves the byte-identical workspace scaffold to `fh init`"
  ) {
    // The static, machine-agnostic files a laptop fetches verbatim — served off
    // the shared AddonBootstrap constants, independent of any home data (so the
    // default empty SystemPkl is fine).
    val (base, consumer, gitignore) = (for {
      store <- StateStore.inMemory(Map.empty)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(titleDash("home", None)))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("home" -> ref),
          "home",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          val routes = server.routes.orNotFound
          val get = (p: String) =>
            routes
              .run(Request[IO](Method.GET, Uri.unsafeFromString(p)))
              .flatMap(r =>
                r.body
                  .through(fs2.text.utf8.decode)
                  .compile
                  .string
                  .map((r.status, _))
              )
          for {
            b <- get("/system/pkl/base.pkl")
            c <- get("/system/pkl/PklProject")
            g <- get("/system/pkl/gitignore")
          } yield (b, c, g)
        }
    } yield out).timeout(30.seconds).unsafeRunSync()

    assertEquals(base._1, Status.Ok)
    assertEquals(base._2, fh.view.build.AddonBootstrap.BaseManifest)
    assertEquals(consumer._2, fh.view.build.AddonBootstrap.ConsumerManifest)
    assertEquals(gitignore._2, fh.view.build.AddonBootstrap.GitignoreTemplate)
  }

  test(
    "/system/pkl revalidates: no-cache always, 304 only on a stale-free tag"
  ) {
    // `dump.pkl` is live per-home data under a fixed URL, so the contract is
    // "never reuse without asking": `no-cache` on every response (200 and 304
    // alike — a 304 refreshes the directive), and a 304 only when the client's
    // tag matches the CURRENT bytes. The re-serve under a changed dump is the
    // point: a stale tag must NOT win, or an author gets completions for
    // devices they no longer own.
    val system = fh.view.build.SystemPkl(
      hass = Some("// schema"),
      dump = Some("kitchen = 1")
    )
    val noCache = `Cache-Control`(CacheDirective.`no-cache`())

    val (ok, okTag, matched, stale) = (for {
      store <- StateStore.inMemory(Map.empty)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(titleDash("home", None)))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("home" -> ref),
          "home",
          sessions,
          TestAuth.openGate,
          assets = AssetCache.empty,
          systemPkl = system
        )
        .use { server =>
          val routes = server.routes.orNotFound
          val uri = Uri.unsafeFromString("/system/pkl/dump.pkl")
          for {
            ok <- routes.run(Request[IO](Method.GET, uri))
            okTag = ok.headers.get[ETag].map(_.tag)
            // The client comes back with the tag it was given: unchanged -> 304.
            matched <- routes.run(
              Request[IO](Method.GET, uri)
                .putHeaders(`If-None-Match`(okTag.map(NonEmptyList.one)))
            )
            // A tag the served bytes never had must be re-served in full, not
            // 304'd.
            stale <- routes.run(
              Request[IO](Method.GET, uri)
                .putHeaders(
                  `If-None-Match`(
                    Some(NonEmptyList.one(EntityTag("stale-etag")))
                  )
                )
            )
          } yield (ok, okTag, matched, stale)
        }
    } yield out).timeout(30.seconds).unsafeRunSync()

    assertEquals(ok.status, Status.Ok)
    assert(okTag.isDefined, clue = ok.headers)
    assertEquals(ok.headers.get[`Cache-Control`], Some(noCache))

    assertEquals(matched.status, Status.NotModified)
    // The 304 must carry the directives too — a bare 304 would let a cache
    // fall back to its own heuristics on the next hit.
    assertEquals(matched.headers.get[`Cache-Control`], Some(noCache))
    assertEquals(matched.headers.get[ETag].map(_.tag), okTag)

    assertEquals(stale.status, Status.Ok)
  }

  test("page serves both connection indicators (SSE transport + HA feed)") {
    pageHtml(titleDash("home", None)).map { html =>
      // Concept 1: the server-pushed HA-down signal drives the HA banner.
      assert(html.contains(Server.HaDownSignal), html)
      // Concept 2: transport-down is derived client-side from Datastar's
      // connection lifecycle — no polling. It binds the shell's own
      // [[Server.StreamEvent]] rather than `datastar-fetch` itself, because
      // that fires for every fetch on the page and this banner is about one of
      // them (see "only the STREAM's own fetch…" below). Both are dispatched on
      // `document` without bubbling, so `__document` is load-bearing — a
      // `__window` modifier would silently never fire.
      assert(html.contains(s"data-on:${Server.StreamEvent}__document"), html)
      assert(html.contains("retries-failed"), html)
      assert(!html.contains("data-on-interval"), html)
      // Every `data-show` element must ALSO ship inline-hidden, or it paints
      // before Datastar loads and flashes on each page load.
      html
        .split("<")
        .filter(_.contains("data-show="))
        .foreach(tag => assert(tag.contains("""style="display:none""""), tag))
      // Two DISTINCT messages, one per failure kind.
      assert(html.contains("Reconnecting to the dashboard"), html)
      assert(html.contains("Home Assistant unavailable"), html)
      // Styled by theme-owned classes, not inline styles.
      assert(html.contains("fh-offline-sse"), html)
      assert(html.contains("fh-offline-ha"), html)
    }
  }

  test("a deferred stylesheet does not block the first paint") {
    val themed = titleDash("home", None).copy(theme =
      Theme(
        stylesheets = List("https://example.test/frame.css"),
        deferredStylesheets = List("https://example.test/icons.css")
      )
    )
    pageHtml(themed).map { html =>
      // The critical one still blocks; the deferred one is preloaded and
      // swapped to a stylesheet on load.
      assert(
        html.contains(
          """<link rel="stylesheet" href="https://example.test/frame.css">"""
        ),
        clue = html
      )
      assert(
        html.contains(
          """<link rel="preload" as="style" href="https://example.test/icons.css" onload="this.onload=null;this.rel='stylesheet'">"""
        ),
        clue = html
      )
      // ...and without JS the preload never becomes a stylesheet, so the
      // fallback is not optional.
      assert(
        html.contains(
          """<noscript><link rel="stylesheet" href="https://example.test/icons.css"></noscript>"""
        ),
        clue = html
      )
      // The deferred one must NOT also be linked normally — that would restore
      // exactly the blocking fetch this avoids. (The `<noscript>` copy is
      // inert: nothing inside it is fetched when scripting is on.)
      val blocking = html.linesIterator
        .filter(l =>
          l.contains("rel=\"stylesheet\"") && !l.contains("noscript")
        )
        .toList
      assertEquals(blocking.length, 1, clue = blocking.toString)
    }
  }

  test("a theme's inline scripts are inlined in the head, verbatim") {
    // The gesture half of a CSS interaction (the slider's press-and-hold gate)
    // is AUTHORED — a theme property, not a constant in this server. Emitted
    // raw like `styles`/`chrome`: a theme is authored source, and escaping it
    // would break the JS it is made of.
    val js = "document.addEventListener('pointerdown',e=>{if(e.x<1)return});"
    val dash = titleDash("home", None)
      .copy(theme = Theme(inlineScripts = List(js)))
    pageHtml(dash).map { html =>
      assert(html.contains(s"<script>$js</script>"), html)
    }
  }

  test("patchElements collapses multi-line fragments to a single data line") {
    val sse = Datastar.patchElements("<div>\n  <span>x</span>\n</div>")
    assertEquals(sse.eventType, Some("datastar-patch-elements"))
    // Single data line so http4s does not drop unprefixed continuation lines.
    assertEquals(sse.data, Some("elements <div> <span>x</span> </div>"))
    assert(!sse.data.get.contains("\n"), clue = sse.data)
  }

  test("patchElements is unchanged for single-line fragments") {
    assertEquals(
      Datastar.patchElements("""<div id="c">x</div>""").data,
      Some("""elements <div id="c">x</div>""")
    )
  }

  test(
    "multi-line patches prefix EVERY data line (http4s renders 'data:' once)"
  ) {
    // http4s 0.23 writes `data: ` once then the string verbatim, so each Datastar
    // protocol line must carry its own prefix or the client drops it (which left
    // a navigate/popup body empty until a refresh).
    val open = Datastar
      .patch(
        """<dialog id="x">hi</dialog>""",
        PatchMode.Append,
        Some("#popups")
      )
      .renderString
    assert(open.contains("data: selector #popups"), clue = open)
    assert(open.contains("data: mode append"), clue = open)
    assert(
      open.contains("""data: elements <dialog id="x">hi</dialog>"""),
      clue = open
    )
    // no unprefixed continuation line
    assert(!open.contains("\nmode append"), clue = open)
    assert(!open.contains("\nelements "), clue = open)

    val inner =
      Datastar
        .patch("<i>e</i>", PatchMode.Inner, Some("#dashboard"))
        .renderString
    assert(inner.contains("data: selector #dashboard"), clue = inner)
    assert(inner.contains("data: mode inner"), clue = inner)
  }

}
