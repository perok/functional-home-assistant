package fh.view.runtime

import fh.view.runtime.RendererTestOps.*

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Region,
  Surface,
  Theme
}
import fh.view.testkit.TestIds.given
import io.circe.Json
import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** The routes a browser hits directly: the document, its view-state carriers,
  * the `/system/pkl` endpoints, and the helpers those rest on.
  */
class ServerRoutesSuite extends ServerHarness {

  /** As an SSE reconnect does, and every action POST in its body. */
  private def signalled(signals: String): Request[IO] =
    Request[IO](
      Method.GET,
      uri"/".withQueryParam("datastar", signals)
    )

  test(
    "popupOf reads the popup's ui. param and ui_ signal, ignoring the rest"
  ) {
    assertEquals(
      Server.popupOf(get("ui.popups" -> "det", "other" -> "x")),
      Some("det")
    )
    // A tab bar's selection is a node variable, so its old param is not read.
    assertEquals(Server.popupOf(get("ui.c" -> "1")), None)
    assertEquals(Server.popupOf(get("other" -> "x")), None)
    assertEquals(Server.popupOf(get()), None)
    assertEquals(
      Server.popupOf(
        signalled("""{"ui_popups":"det","ui_c":1,"conn":"x"}""")
      ),
      Some("det")
    )
    // The signal is the live value; the URL only trails it.
    assertEquals(
      Server
        .popupOf(
          Request[IO](
            Method.GET,
            uri"/"
              .withQueryParam("ui.popups", "a")
              .withQueryParam("datastar", """{"ui_popups":"b"}""")
          )
        ),
      Some("b")
    )
  }

  test("selection round-trip: a tab variable of 1 opens the index-1 surface") {
    val r = tabsRenderer
    val env = r.vars.env(Map(VarKey(NodeId.derived("c"), "tab") -> "1"))
    val selections = r.surfaces.selections(None, env)
    assertEquals(r.surfaces.selectedSurfaces(selections), Set("c_t1"))
    assert(r.renderBody(Map.empty, selections).contains("tab_c: 1"))
    assert(
      r.surfaces.selectionAnomalies(env).isEmpty,
      clue = r.surfaces.selectionAnomalies(env)
    )
  }

  test(
    "selection round-trip: a malformed value falls back to index 0 + warns"
  ) {
    // `refusals` keeps such a value off the session; this is the guard behind.
    val r = tabsRenderer
    val env = r.vars.env(Map(VarKey(NodeId.derived("c"), "tab") -> "abc"))
    val selections = r.surfaces.selections(None, env)
    assertEquals(r.surfaces.selectedSurfaces(selections), Set("c_t0"))
    assert(r.renderBody(Map.empty, selections).contains("tab_c: 0"))
    assertEquals(r.surfaces.selectionAnomalies(env).size, 1)
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

  private def pageHtml(dash: Dashboard, query: String = ""): IO[String] =
    TestServer.resource(dash, Nil).use(_.page(query)).timeout(30.seconds)

  /** Strict, since the body is read after the server is gone. */
  private def response(
      uri: String,
      dash: Dashboard = titleDash("home", None)
  ): IO[Response[IO]] =
    TestServer
      .resource(dash, Nil)
      .use(_.get(Uri.unsafeFromString(uri)).flatMap(_.toStrict(None)))
      .timeout(30.seconds)

  test("the frontend bundles are served immutable, and only by built name") {
    // A rebuild is a new hashed URL, which makes `immutable` honest.
    val app = FrontendAssets.url("app")
    for {
      hit <- response("/" + app)
      cached = hit.headers.get(CIString("Cache-Control")).map(_.head.value)
      miss <- response("/web/app.js")
    } yield {
      assertEquals(hit.status, Status.Ok)
      assertEquals(cached, Some("public, max-age=31536000, immutable"))
      // A name the manifest does not list is not a route, so there is no
      // traversal to sanitise.
      assertEquals(miss.status, Status.NotFound)
    }
  }

  test("the PWA files are served, revalidated, and only the four of them") {
    // Fixed filenames the browser re-fetches to update, so revalidated: as
    // `immutable` a same-named redeploy would strand clients forever.
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
      // They live at the app root, not under `web/` or `pwa/`.
      assertEquals(other.status, Status.NotFound)
    }
  }

  test("the manifest takes the background of the dashboard at /, per scheme") {
    // Both themeable members come from the dashboard at `/`, derived rather
    // than hardcoded so a retuned theme leaves no stale hex (ADR 0014). An
    // installed app caches the manifest, so a wrong value shows on a phone days
    // after the deploy.
    val themed = titleDash("home", None).copy(theme =
      Theme(
        tokens = Map("primary-background-color" -> "#fafafa"),
        tokensDark = Map("primary-background-color" -> "#223344")
      )
    )
    response("/manifest.webmanifest", themed).flatMap { r =>
      r.as[String].map { body =>
        val json = io.circe.parser.parse(body).toOption.get.hcursor
        val dark = json.downField("color_scheme_dark")

        // Pinned dark: a status bar is chrome, and the light value is a white
        // stripe above a dark page. When Chrome ships the override and
        // `ChromeColors.base` flips to light, these expectations change with
        // it.
        assertEquals(json.get[String]("theme_color").toOption, Some("#223344"))
        assertEquals(
          json.get[String]("background_color").toOption,
          Some("#223344")
        )

        // The override says the same, so the flip above is a one-word change.
        assertEquals(dark.get[String]("theme_color").toOption, Some("#223344"))
        assertEquals(
          dark.get[String]("background_color").toOption,
          Some("#223344")
        )

        assert(!body.contains("#fafafa"), clue = body)
      }
    }
  }

  test("a theme with ONE palette fills both the base and the override") {
    // A member left out falls back to the browser's white chrome on that
    // scheme.
    val themed = titleDash("home", None).copy(theme =
      Theme(tokens = Map("primary-background-color" -> "#fafafa"))
    )
    response("/manifest.webmanifest", themed).flatMap { r =>
      r.as[String].map { body =>
        val json = io.circe.parser.parse(body).toOption.get.hcursor
        assertEquals(json.get[String]("theme_color").toOption, Some("#fafafa"))
        assertEquals(
          json
            .downField("color_scheme_dark")
            .get[String]("theme_color")
            .toOption,
          Some("#fafafa")
        )
      }
    }
  }

  test("a theme with no background token leaves the manifest as committed") {
    // No background, or an entrypoint that never evaluated, still gets an
    // installable manifest rather than a 500.
    response("/manifest.webmanifest").flatMap { r =>
      r.as[String].map { body =>
        val json = io.circe.parser.parse(body).toOption.get.hcursor
        assertEquals(json.get[String]("theme_color").toOption, Some("#111111"))
        assert(!body.contains("color_scheme_dark"), clue = body)
      }
    }
  }

  test("the page head links the manifest and registers the service worker") {
    pageHtml(titleDash("home", None)).map { html =>
      assert(
        html.contains(
          s"""<link rel="manifest" href="${PwaAssets.manifestUrl}">"""
        ),
        clue = html
      )
      // Nothing in the page spells `sw.js` out.
      assert(html.contains(s"fhRegisterSw('${PwaAssets.swUrl}')"), clue = html)
      // An unregistered SW is silent by design, so the call's presence is all
      // the page can assert.
      assert(
        FrontendAssets.content("shell").contains("window.fhRegisterSw="),
        clue = "shell must define fhRegisterSw"
      )
    }
  }

  test("a page on its way out stops painting connection banners") {
    // Navigating away aborts the stream, so the outgoing document would report
    // an outage. The base CSS hides the banners by the class set on `pagehide`;
    // `components.test.pkl` pins that half.
    IO {
      val shell = FrontendAssets.content("shell")
      // No quote character: the minifier rewrites string literals to backticks.
      assert(shell.contains("fh-leaving"), clue = shell)
      // `fhScroll`'s offset save, and this one.
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
      // An unqualified theme-color would win over either and pin one scheme.
      assertEquals(
        html.sliding("theme-color".length).count(_ == "theme-color"),
        2,
        clue = html
      )
    }
  }

  test("a theme with ONE palette paints both schemes with it") {
    // A missing tag means the browser paints its own white chrome above a page
    // the theme coloured: the reported symptom.
    val lightOnly = titleDash("home", None).copy(theme =
      Theme(tokens = Map("primary-background-color" -> "#fafafa"))
    )
    pageHtml(lightOnly).map { html =>
      assert(
        html.contains(
          """<meta name="theme-color" media="(prefers-color-scheme: dark)" content="#fafafa">"""
        ),
        clue = html
      )
      assertEquals(
        html.sliding("theme-color".length).count(_ == "theme-color"),
        2,
        clue = html
      )
    }
  }

  test("a theme that names no background emits no theme-color at all") {
    // No meta keeps the browser's own chrome, which at least matches the
    // device.
    pageHtml(titleDash("home", None)).map { html =>
      assert(!html.contains("theme-color"), clue = html)
    }
  }

  test("the connection-lost banner LATCHES once the retries are exhausted") {
    // Every fetch type but retrying/error/retries-failed is "fine", so without
    // a latch any later event cleared the banner, and the 600ms debounce could
    // swallow the failure before it painted.
    pageHtml(titleDash("home", None)).map { html =>
      val handler = html.linesIterator
        .find(_.contains(s"data-on:${Server.StreamEvent}"))
        .getOrElse(fail(s"no stream handler in the shell: $html"))
      assert(handler.contains("$_sse >= 2 ? 2 :"), clue = handler)
      assert(handler.contains("'retries-failed' ? 2"), clue = handler)
      assert(handler.contains("'retrying'"), clue = handler)
      assert(html.contains("""data-show="$_sse < 2""""), clue = html)
      assert(html.contains("""data-show="$_sse >= 2""""), clue = html)
      // A blip is not latched: 1 must fall back to 0, or an ordinary refetch
      // pins "Reconnecting…".
      assert(!handler.contains("$_sse >= 1"), clue = handler)
    }
  }

  test("only the STREAM's own fetch moves the connection banner") {
    // `datastar-fetch` fires for every fetch: bound directly, a rejected click
    // raised "Reconnecting…" on a live stream and a stream frame hid it during
    // a failed POST. The shell splits per event, since a debounced handler sees
    // only its window's last event, so the page must bind the filtered event.
    pageHtml(titleDash("home", None)).map { html =>
      val handler = html.linesIterator
        .find(_.contains(s"data-on:${Server.StreamEvent}"))
        .getOrElse(fail(s"no stream handler in the shell: $html"))
      assert(handler.contains("debounce"), clue = handler)
      assert(!html.contains("data-on:datastar-fetch__debounce"), clue = html)
      // The shell is inlined into this page, so both halves are checked.
      // Matched loosely, since the bundle is minified.
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
    // The popup host is in theme.chrome, with no card template to declare its
    // signal, so the shell declares it, as `ui_<hostId>` like any selection. An
    // unknown id is dropped rather than seeded.
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
      // One Datastar on the page, the bundle that carries our attributes: a
      // second copy would be an instance that never sees them.
      val datastars = """<script type="module" src="([^"]+)">""".r
        .findAllMatchIn(none)
        .map(_.group(1))
        .filter(_.contains("datastar"))
        .toList
      assertEquals(datastars, List(Server.DatastarScript), none)
    }
  }

  test("the page restores its scroll offset, last of all") {
    // Crossing dashboards is a document load (ADR 0002) and the page holds a
    // streaming fetch, so neither bfcache nor the browser restores the offset.
    // Last in the body: the restore reads the document height.
    for {
      html <- pageHtml(titleDash("home", None))
    } yield {
      assert(html.contains("window.fhScroll="), html)
      // Bare substrings, since the shell is minified. `manual` matters: the
      // browser's `auto` restore would otherwise re-apply its own offset, 0
      // here, after ours.
      assert(html.contains("fh.scroll."), html)
      assert(html.contains("scrollRestoration"), html)
      assert(html.contains("manual"), html)
      // A separate script tag: a parse error in the shell does not stop it, so
      // a broken shell becomes a named console error.
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
    // The first connect carries no signals (data-init fires before Datastar
    // merges descendants' data-signals), so without this the server repaints
    // the popup closed and the URL mirror follows it. A tab is on the session.
    val dash = titleDash("home", None).copy(
      surfaces = Map("det" -> Surface(LayoutNode.Component("col")))
    )
    for {
      restored <- pageHtml(dash, "?ui.popups=det")
      plain <- pageHtml(dash)
    } yield {
      assert(
        restored.contains("sse/dashboard/home/patch?ui.popups=det"),
        restored
      )
      // The version this document was rendered at, so the first connect resumes
      // instead of inner-patching a body the document already has.
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
      assert(
        plain.contains(s"patch?${Server.cursorParam(Server.HeadHashSignal)}="),
        plain
      )
      // The default retry mode treats a stream the server ended as finished,
      // and ending it is how the server closes a stalled connection.
      assert(plain.contains("retry:'always'"), plain)
      // The cursor is `_`-prefixed so the default filter drops it; this include
      // puts it on the one request that reads it. The value is checked:
      // `SseRetry` interpolates `SseInclude`, and an object `val` reading one
      // declared later silently gets `null`. That shipped `include:'null'`, so
      // reconnects arrived with no cursor, `conn` or tab, and `CursorSuite`
      // could not see it.
      assert(
        plain.contains(s"filterSignals:{include:'${Server.SseInclude}'"),
        clue = (Server.SseInclude, plain)
      )
    }
  }

  test("the popup selection: signal wins when present, URL only seeds") {
    assertEquals(Server.popupOf(get("ui.popups" -> "det")), Some("det"))
    // `ui_popups: ""` beside the stale param keeps a closed dialog closed on
    // every retry: the signal is authoritative.
    assertEquals(
      Server
        .popupOf(
          Request[IO](
            Method.GET,
            uri"/"
              .withQueryParam("ui.popups", "det")
              .withQueryParam("datastar", """{"ui_popups":""}""")
          )
        ),
      None
    )
    assertEquals(Server.popupOf(get()), None)
  }

  test("openPopup adopts only a surface this dashboard can actually host") {
    val r = Renderer.create(
      titleDash("home", None).copy(
        surfaces = Map(
          "det" -> Surface(LayoutNode.Component("col")),
          "panel" -> Surface(
            LayoutNode.Component("col"),
            bakeInto = Some("c"),
            bakeAs = Some("panel")
          )
        )
      )
    )
    assertEquals(r.surfaces.openPopup(Some("det")), Some("det"))
    // Adopting any of these would put the session in a state its renderer
    // cannot serve.
    assertEquals(r.surfaces.openPopup(Some("")), None)
    assertEquals(r.surfaces.openPopup(Some("nope")), None)
    assertEquals(r.surfaces.openPopup(Some("panel")), None)
    assertEquals(r.surfaces.openPopup(None), None)
    assert(
      r.surfaces
        .selectedSurfaces(r.surfaces.selections(Some("det"), Map.empty))
        .contains("det")
    )
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

  test(
    "/system/pkl serves the byte-identical workspace scaffold to `fh init`"
  ) {
    // Machine-agnostic, so the harness's bare workspace is fine.
    TestServer
      .resource(titleDash("home", None), Nil)
      .use { ts =>
        def get(path: String) =
          ts.get(Uri.unsafeFromString(path))
            .flatMap(r => r.bodyText.compile.string.map((r.status, _)))
        (
          get("/system/pkl/base.pkl"),
          get("/system/pkl/PklProject"),
          get("/system/pkl/gitignore")
        ).tupled
      }
      .timeout(30.seconds)
      .map { (base, consumer, gitignore) =>
        assertEquals(base._1, Status.Ok)
        assertEquals(base._2, fh.view.build.AddonBootstrap.BaseManifest)
        assertEquals(consumer._2, fh.view.build.AddonBootstrap.ConsumerManifest)
        assertEquals(
          gitignore._2,
          fh.view.build.AddonBootstrap.GitignoreTemplate
        )
      }
  }

  test("page serves both connection indicators (SSE transport + HA feed)") {
    pageHtml(titleDash("home", None)).map { html =>
      assert(html.contains(Server.HaDownSignal), html)
      // Transport-down is derived client-side from the shell's own
      // [[Server.StreamEvent]], not `datastar-fetch`, which fires for every
      // fetch. Both are dispatched on `document` without bubbling, so
      // `__window` would never fire.
      assert(html.contains(s"data-on:${Server.StreamEvent}__document"), html)
      assert(html.contains("retries-failed"), html)
      assert(!html.contains("data-on-interval"), html)
      // Otherwise it paints before Datastar loads and flashes on every page
      // load.
      html
        .split("<")
        .filter(_.contains("data-show="))
        .foreach(tag => assert(tag.contains("""style="display:none""""), tag))
      assert(html.contains("Reconnecting to the dashboard"), html)
      assert(html.contains("Home Assistant unavailable"), html)
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
    // Linked as the local copy the asset cache fetched at boot.
    def cached(url: String) = s"assets/${AssetCache.hashName(url)}"
    val frame = cached("https://example.test/frame.css")
    val icons = cached("https://example.test/icons.css")
    pageHtml(themed).map { html =>
      assert(
        html.contains(s"""<link rel="stylesheet" href="$frame">"""),
        clue = html
      )
      assert(
        html.contains(
          s"""<link rel="preload" as="style" href="$icons" onload="this.onload=null;this.rel='stylesheet'">"""
        ),
        clue = html
      )
      // Without JS the preload never becomes a stylesheet.
      assert(
        html.contains(
          s"""<noscript><link rel="stylesheet" href="$icons"></noscript>"""
        ),
        clue = html
      )
      // A normal link would restore the blocking fetch. The `<noscript>` copy
      // is inert while scripting is on.
      val blocking = html.linesIterator
        .filter(l =>
          l.contains("rel=\"stylesheet\"") && !l.contains("noscript")
        )
        .toList
      assertEquals(blocking.length, 1, clue = blocking.toString)
    }
  }

  test("a theme's inline scripts are inlined in the head, verbatim") {
    // Authored in the theme, and emitted raw: escaping would break its JS.
    val js = "document.addEventListener('pointerdown',e=>{if(e.x<1)return});"
    val dash = titleDash("home", None)
      .copy(theme = Theme(inlineScripts = List(js)))
    pageHtml(dash).map { html =>
      assert(html.contains(s"<script>$js</script>"), html)
    }
  }

  test("card scripts are inlined once each, ahead of the theme's") {
    // A theme cannot drop a card's script, and cards sharing one run it once.
    val cardJs = "document.addEventListener('pointermove',()=>{});"
    val themeJs = "void 0;"
    val base = titleDash("home", None)
    val shared = CardDef("<i></i>", script = Some(cardJs))
    val dash = base.copy(
      cards = base.cards ++ Map("a" -> shared, "b" -> shared),
      theme = Theme(inlineScripts = List(themeJs))
    )
    pageHtml(dash).map { html =>
      val card = html.indexOf(s"<script>$cardJs</script>")
      assert(card >= 0, html)
      assertEquals(html.indexOf(s"<script>$cardJs</script>", card + 1), -1)
      assert(card < html.indexOf(s"<script>$themeJs</script>"), html)
    }
  }

  test("patchElements collapses multi-line fragments to a single data line") {
    val sse = Datastar.patchElements("<div>\n  <span>x</span>\n</div>")
    assertEquals(sse.eventType, Some("datastar-patch-elements"))
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
    // http4s 0.23 writes `data: ` once, then the string verbatim, so each
    // Datastar line needs its own prefix or the client drops it (a navigate or
    // popup body stayed empty until a refresh).
    val open = Datastar
      .patch(
        """<dialog id="x">hi</dialog>""",
        PatchMode.Append,
        Some("#popups")
      )
      .render
    assert(open.contains("data: selector #popups"), clue = open)
    assert(open.contains("data: mode append"), clue = open)
    assert(
      open.contains("""data: elements <dialog id="x">hi</dialog>"""),
      clue = open
    )
    assert(!open.contains("\nmode append"), clue = open)
    assert(!open.contains("\nelements "), clue = open)

    val inner =
      Datastar
        .patch("<i>e</i>", PatchMode.Inner, Some("#dashboard"))
        .render
    assert(inner.contains("data: selector #dashboard"), clue = inner)
    assert(inner.contains("data: mode inner"), clue = inner)
  }

}
