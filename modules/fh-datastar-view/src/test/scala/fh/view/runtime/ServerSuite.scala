package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  DynamicCase,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.TestIds.given
import fs2.concurrent.{SignallingRef, Topic}
import io.circe.Json
import org.http4s.*
import org.http4s.headers.{`Cache-Control`, `If-None-Match`, ETag}
import org.http4s.implicits.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class ServerSuite extends munit.CatsEffectSuite {

  // A minimal tabs dashboard: a `tabs` component (id "c") with two panels baked
  // into it (c_t0 default, c_t1) — the ui-state index selects among them.
  private def tabsRenderer: Renderer = {
    val cards = Map(
      "btn" ->
        CardDef("<button>{{label}}</button>", slots = List("label")),
      "card" ->
        CardDef("<span>{{state}}</span>", slots = List("state")),
      // Split like the shipped `Tabs` — minimal markup, real shape.
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        self = Some(
          """<div id="{{selfId}}" class="tabs">""" +
            """{{#children}}{{{html}}}{{/children}}</div>"""
        ),
        mount = Some(
          """<div id="{{mountId}}" data-signals="{ tab_{{id}}: {{bakeIndex}} }">{{{panel}}}</div>"""
        )
      )
    )
    def panel(name: String): LayoutNode.Component =
      LayoutNode.Component(
        "card",
        slots = Map("state" -> SlotSource(Some(s"sensor.$name")))
      )
    Renderer.create(
      Dashboard(
        cards,
        LayoutNode.Component(
          "tabs",
          children = List(
            LayoutNode.Component(
              "btn",
              Map("label" -> SlotSource(literal = Some("A")))
            ),
            LayoutNode
              .Component("btn", Map("label" -> SlotSource(literal = Some("B"))))
          )
        ),
        surfaces = Map(
          "c_t0" -> Surface(
            panel("a"),
            bakeInto = Some("c"),
            bakeAs = Some("panel"),
            bakeIndex = Some(0),
            activation = Activation.User(defaultOpen = true)
          ),
          "c_t1" -> Surface(
            panel("b"),
            bakeInto = Some("c"),
            bakeAs = Some("panel"),
            bakeIndex = Some(1)
          )
        )
      )
    )
  }

  /** A page GET carrying ui state in the URL, as a refresh does. */
  private def get(params: (String, String)*): Request[IO] =
    Request[IO](Method.GET, uri"/".withQueryParams(params.toMap))

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
    assertEquals(r.selectedSurfaces(uiState), Set("c_t1"))
    assert(r.renderBody(Map.empty, uiState).contains("tab_c: 1"))
    assert(
      r.uiStateAnomalies(uiState).isEmpty,
      clue = r.uiStateAnomalies(uiState)
    )
  }

  test("ui-state round-trip: a malformed value falls back to index 0 + warns") {
    val r = tabsRenderer
    val uiState = Server.uiStateOf(get("ui.c" -> "abc"))
    assertEquals(r.selectedSurfaces(uiState), Set("c_t0"))
    assert(r.renderBody(Map.empty, uiState).contains("tab_c: 0"))
    assertEquals(r.uiStateAnomalies(uiState).size, 1)
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
        "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>")
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
      ref <- SignallingRef[IO].of(Renderer.create(dash))
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
          sessions
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

  test("the connection-lost banner LATCHES once the retries are exhausted") {
    // Every fetch type other than retrying/error/retries-failed classifies as
    // "fine", so without a latch any event after the failure cleared the banner
    // — and because the handler is debounced, a `finished` in the same 600ms
    // window could swallow the failure before it ever painted. Either way the
    // page went back to looking connected while it was not.
    pageHtml(titleDash("home", None)).map { html =>
      val handler = html.linesIterator
        .find(_.contains("data-on:datastar-fetch"))
        .getOrElse(fail(s"no fetch handler in the shell: $html"))
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
      ).foreach(p => assert(restored.contains(s"$p="), clue = (p, restored)))
      // With nothing else to restore the cursor still rides — every document
      // knows what it is showing.
      assert(plain.contains(s"patch?${Server.HeadHashSignal}="), plain)
      // `always` is load-bearing, not decoration: the default retry mode
      // reconnects a DROPPED stream but treats one the server ended as
      // finished — which is how the server closes a stalled connection.
      assert(plain.contains("{retry:'always'}"), plain)
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
    assertEquals(r.openPopup(Map("popups" -> "det")), Some("det"))
    // Closed, unknown (renamed/removed/another dashboard's), and a baked panel
    // id are all refused — adopting one would put the session in a state its
    // renderer cannot serve.
    assertEquals(r.openPopup(Map("popups" -> "")), None)
    assertEquals(r.openPopup(Map("popups" -> "nope")), None)
    assertEquals(r.openPopup(Map("popups" -> "panel")), None)
    assertEquals(r.openPopup(Map.empty), None)
    // ...and an adopted one joins the open set through the ordinary path.
    assert(r.selectedSurfaces(Map("popups" -> "det")).contains("det"))
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
      ref <- SignallingRef[IO].of(Renderer.create(titleDash("home", None)))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("home" -> ref),
          "home",
          sessions,
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
      ref <- SignallingRef[IO].of(Renderer.create(titleDash("home", None)))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("home" -> ref),
          "home",
          sessions
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
      ref <- SignallingRef[IO].of(Renderer.create(titleDash("home", None)))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("home" -> ref),
          "home",
          sessions,
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
      // Concept 2: transport-down is derived client-side, bound straight to
      // Datastar's connection lifecycle — no bridge script, no polling. The
      // event name is load-bearing (`datastar-sse` does not exist in this
      // build), and `data-on` only reaches it because the plugin special-cases
      // this name onto `document`; a `__window` modifier would silently never
      // fire.
      assert(html.contains("data-on:datastar-fetch"), html)
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

  // ---------------------------------------------------------------------------
  // Shared per-slug patch fan-out
  // ---------------------------------------------------------------------------

  /** Counts every live-patch render, so the test can assert a fragment was
    * produced ONCE for N viewers.
    */
  private class CountingRenderer(dash: Dashboard, count: AtomicInteger)
      extends Renderer(dash, Templates.from(dash), Transforms.from(dash)) {
    override def renderNodeById(
        id: NodeId,
        states: Map[String, EntityState],
        uiState: Map[String, String]
    ): Option[String] = {
      count.incrementAndGet()
      super.renderNodeById(id, states, uiState)
    }
  }

  // One live leaf bound to sensor.a inside a static container — no bake
  // groups, so its live patches belong entirely to the shared per-slug pass.
  private def liveLeafDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
    ),
    card = LayoutNode.Component(
      "col",
      children = List(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.a")))
        )
      )
    )
  )

  // Two live leaves: one entity can change during the connect handshake and
  // never again (so nothing later heals it), while the other provides an
  // ordering barrier that proves the connection is live before we look.
  private def twoLeafDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
    ),
    card = LayoutNode.Component(
      "col",
      children = List("sensor.a", "sensor.b").map(e =>
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some(e)))
        )
      )
    )
  )

  test(
    "a change published during the connect handshake still reaches the connection"
  ) {
    // The handshake window: `routes.run` computes the opening patches (reading
    // the snapshot and the log) and returns, but the response body has not been
    // pulled yet — so anything published before the stream's subscription is
    // registered reaches this connection never. It heals on the NEXT reconnect
    // (the cursor stays put and `since` is inclusive), but until then the client
    // shows a pre-connect value with nothing to indicate it.
    val missed = "gap_value_xq"
    val barrier = "barrier_value_xq"
    val renders = new AtomicInteger(0)
    val io = for {
      store <- StateStore.inMemory(
        Map(
          "sensor.a" -> EntityState("sensor.a", "a0", Map.empty),
          "sensor.b" -> EntityState("sensor.b", "b0", Map.empty)
        )
      )
      ref <- SignallingRef[IO]
        .of(new CountingRenderer(twoLeafDash, renders): Renderer)
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      text <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          for {
            // The shared publisher is attached before anything changes.
            _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
            resp <- server.routes.orNotFound
              .run(Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch"))
            _ <- store.update(EntityState("sensor.a", missed, Map.empty))
            // Waiting for the render (the counting renderer's only caller here
            // is a diff pass) proves the shared pass has already PUBLISHED this
            // change, rather than the test racing ahead of a slow publisher.
            _ <- (IO.sleep(5.millis) *> IO(renders.get()))
              .iterateUntil(_ >= 1)
            seen <- Ref[IO].of("")
            // Pulling the body is what registers the subscription, and only now.
            reader <- resp.body
              .through(fs2.text.utf8.decode)
              .evalMap(chunk => seen.updateAndGet(_ + chunk))
              .exists(_.contains(barrier))
              .compile
              .drain
              .start
            _ <- server.sharedSubscribers.filter(_ >= 1).head.compile.drain
            _ <- store.update(EntityState("sensor.b", barrier, Map.empty))
            // The barrier arrived, so everything ordered before it has too.
            _ <- reader.joinWithNever
            text <- seen.get
          } yield text
        }
    } yield text
    io.timeout(30.seconds)
      .map(text => assert(text.contains(missed), clue = text))
  }

  test("a connection that stops reading cannot stall the store") {
    // `Topic.publish1` sends to every subscriber's channel in turn and blocks
    // on a full one, so a bounded per-connection subscription would let ONE
    // stalled browser freeze the HA feed — for every dashboard and every
    // viewer, not just itself.
    val io = for {
      store <- StateStore.inMemory(
        Map("sensor.a" -> EntityState("sensor.a", "a0", Map.empty))
      )
      // A dashboard with a PER-SESSION node (a tabs host bakes the client's
      // selected panel), so the per-connection pass really emits — the case
      // that can block, unlike a page whose every node is shared.
      ref <- SignallingRef[IO].of(tabsRenderer)
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      _ <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          server.routes.orNotFound
            .run(Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch"))
            .flatMap { resp =>
              // Read normally until the test says stop — the connection has to
              // be fully subscribed first, and it only subscribes once the body
              // is being pulled.
              IO.deferred[Unit].flatMap { stop =>
                val reads = resp.body
                  .evalTap(_ =>
                    stop.tryGet
                      .flatMap(s => IO.sleep(1.minute).whenA(s.isDefined))
                  )
                  .compile
                  .drain
                reads.background.surround {
                  for {
                    // The shared publisher — the only consumer of `changes`.
                    _ <- store.changeSubscribers
                      .filter(_ >= 1)
                      .head
                      .compile
                      .drain
                    _ <- stop.complete(())
                    // The reader only stalls on the next event it takes, so
                    // give it one (the keepalive is far too slow to wait for).
                    _ <- store.update(
                      EntityState("sensor.a", "engage-the-stall", Map.empty)
                    )
                    _ <- IO.sleep(1.second)
                    // Far more than a bounded subscription would hold.
                    _ <- (1 to 300).toList.traverse_(i =>
                      store.update(EntityState("sensor.a", s"v$i", Map.empty))
                    )
                  } yield ()
                }
              }
            }
        }
    } yield ()
    io.timeout(15.seconds)
  }

  test(
    "shared per-slug pass: two connections both receive a changed fragment rendered ONCE"
  ) {
    val marker = "shared_once_value_xq"
    val count = new AtomicInteger(0)
    val io = for {
      store <- StateStore.inMemory(
        Map("sensor.a" -> EntityState("sensor.a", "initial", Map.empty))
      )
      renderer = new CountingRenderer(liveLeafDash, count)
      ref <- SignallingRef[IO].of(renderer: Renderer)
      sessions <- Sessions.create
      // Stub HA: the SSE/patch path never calls it (an unexpected registry call
      // still raises); the store is driven in-memory, so the empty seed is inert.
      fake <- FakeHomeAssistant.create(Nil)
      // `Server.resource` runs the shared publishers for the scope's lifetime —
      // so the render count below is entirely the shared pass's doing.
      _ <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          val connect = server.routes.orNotFound
            .run(Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch"))
          val awaitMarker = (resp: Response[IO]) =>
            resp.body
              .through(fs2.text.utf8.decode)
              .scan("")(_ + _)
              .exists(_.contains(marker))
              .compile
              .drain
          for {
            resp1 <- connect
            resp2 <- connect
            seen1 <- awaitMarker(resp1).start
            seen2 <- awaitMarker(resp2).start
            // Deterministic readiness (topics deliver only to already-subscribed
            // consumers): both connections on the shared topic, and the ONE
            // publisher on the store's changes. There is no per-session loop to
            // wait for any more — which is the point of the pass being shared.
            _ <- server.sharedSubscribers.filter(_ >= 2).head.compile.drain
            _ <- store.changeSubscribers.filter(_ >= 1).head.compile.drain
            _ <- store.update(EntityState("sensor.a", marker, Map.empty))
            // (a) both SSE streams receive the changed fragment...
            _ <- seen1.joinWithNever
            _ <- seen2.joinWithNever
          } yield ()
        }
    } yield count.get()
    // ...and (b) it was rendered once, by the shared pass (the per-session
    // loops render only bake owners / open surfaces — none here).
    io.timeout(30.seconds).assertEquals(1)
  }

  // ---------------------------------------------------------------------------
  // Per-entity dynamic-group patches (Tier 1 in-place + Tier 2 add/remove)
  // ---------------------------------------------------------------------------

  private def on(id: String): EntityState = st(id, "on")
  private def off(id: String): EntityState = st(id, "off")

  // A dynamic group of on-state entities as the layout root (group id "c"); each
  // member renders `<span>on</span>` in an `fh-cell` wrapper `c_<slug>`.
  private def dynDash = Dashboard(
    cards =
      Map("dot" -> CardDef("<span>{{state}}</span>", slots = List("state"))),
    card = LayoutNode.Dynamic(
      query = Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
      cases = List(
        DynamicCase(
          Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__")),
          "dot",
          slots = Map("state" -> SlotSource())
        )
      )
    )
  )

  /** Drive the shared per-slug diff for one change against `after` (the current
    * snapshot) with an optional pre-seeded cache; return the emitted SSE
    * patches (rendered to strings) and the resulting cache.
    */
  // Seeded through `set`, so the digest derivation is never duplicated here.
  private def seedLog(seed: Map[String, String]): FragmentLog =
    seed.foldLeft(FragmentLog("test")) { case (l, (id, html)) =>
      l.set(id, html, 0L)
    }

  // WHICH nodes the log knows about, and when each last changed — everything
  // these contracts assert on. The log holds a digest, not HTML, so there is no
  // node -> html projection to make (docs/plan-one-shared-log.md, statement (3));
  // what the patches CARRY is asserted on the patches themselves.
  private def logged(log: FragmentLog): Map[NodeId, Long] =
    log.fragments.view.mapValues(_.version).toMap

  /** The ELEMENT patches of a shared batch. Every non-empty batch also carries
    * the resume cursor as a `patch-signals` event
    * (docs/adr/0011-the-live-connection.md); these contracts are about what the
    * DOM receives, and one dedicated test below covers the cursor itself.
    */
  private def elementPatches(batch: List[ServerSentEvent]): List[String] =
    batch.map(_.renderString).filterNot(_.contains("datastar-patch-signals"))

  private def runShared(
      dash: Dashboard,
      after: Map[String, EntityState],
      change: StateChange,
      seedCache: Map[String, String] = Map.empty
  ): IO[(List[String], Map[NodeId, Long])] =
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      // Stub HA: the SSE/patch path never calls it (an unexpected registry call
      // still raises); the store is driven in-memory, so the empty seed is inert.
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          for {
            renderer <- ref.get
            cache <- Ref[IO].of(seedLog(seedCache))
            patches <- server.sharedPatches(
              "dashboard",
              renderer,
              cache,
              change
            )
            finalCache <- cache.get.map(logged)
          } yield (elementPatches(readyEvents(patches)), finalCache)
        }
    } yield out)
      .timeout(30.seconds)

  test("dynamic in-place tick patches ONE child, not the whole group") {
    val after = Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))
    // light.b ticks (a fresh EntityState, same "on" state) -> InPlace member.
    val change = StateChange("light.b", Some(on("light.b")), on("light.b"))
    runShared(dynDash, after, change).map { case (patches, _) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      // outer-morphs the child id (default mode, no mode line), not the group.
      assert(
        p.contains("""elements <div class="fh-cell" id="c_light_b">"""),
        clue = p
      )
      assert(!p.contains("id=\"c\""), clue = p)
      assert(!p.contains("mode "), clue = p)
    }
  }

  /** A dynamic group the log already knows: MEMBER entries, which is what
    * "established" means now that no container logs a fragment of its own.
    */
  private val establishedGroup = Map(
    "c_light_a" -> "<a>",
    "c_light_c" -> "<c>",
    "c_light_d" -> "<d>"
  )

  test("dynamic add: per-entity insert BEFORE the DOM successor") {
    // a,c,d already on; b turns on -> Added, churn 1 of shown 3 -> per-entity.
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> on("light.b"),
      "light.c" -> on("light.c"),
      "light.d" -> on("light.d")
    )
    val change = StateChange("light.b", Some(off("light.b")), on("light.b"))
    // A group is ESTABLISHED by having member entries — there is no group-level
    // fragment any more (it would be a fragment containing other nodes).
    runShared(dynDash, after, change, seedCache = establishedGroup).map {
      case (patches, cache) =>
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("mode before"), clue = p)
        assert(
          p.contains("selector #c_light_c"),
          clue = p
        ) // first member after b
        assert(
          p.contains("""elements <div class="fh-cell" id="c_light_b">"""),
          clue = p
        )
        // the new child is logged; no node logs a fragment containing another.
        assert(cache.contains("c_light_b"), clue = cache)
        assert(!cache.contains("c"), clue = cache)
    }
  }

  test("dynamic add of the last-sorting entity APPENDS into the group") {
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> on("light.b"),
      "light.c" -> on("light.c"),
      "light.z" -> on("light.z")
    )
    val change = StateChange("light.z", Some(off("light.z")), on("light.z"))
    runShared(dynDash, after, change, seedCache = establishedGroup).map {
      case (patches, _) =>
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("mode append"), clue = p)
        assert(p.contains("selector #c"), clue = p)
        assert(
          p.contains("""elements <div class="fh-cell" id="c_light_z">"""),
          clue = p
        )
    }
  }

  test("dynamic remove: per-entity remove patch (no elements), child pruned") {
    // 4 on; b turns off -> Removed, churn 1 of shown 4 -> per-entity remove.
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> off("light.b"),
      "light.c" -> on("light.c"),
      "light.d" -> on("light.d")
    )
    val change = StateChange("light.b", Some(on("light.b")), off("light.b"))
    runShared(
      dynDash,
      after,
      change,
      seedCache = Map("c" -> "<stale>", "c_light_b" -> "<old>")
    ).map { case (patches, cache) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      assert(p.contains("mode remove"), clue = p)
      assert(p.contains("selector #c_light_b"), clue = p)
      // remove carries no HTML payload (the event name still says "…elements").
      assert(!p.contains("data: elements"), clue = p)
      assert(!cache.contains("c_light_b"), clue = cache)
    }
  }

  test(
    "heuristic: removing 1 of 2 members FILLS the group's mount + refingerprints"
  ) {
    // shown 2, churn 1 -> 1 < 0.5*2 is false -> the wholesale fallback.
    val after = Map("light.a" -> on("light.a"), "light.b" -> off("light.b"))
    val change = StateChange("light.b", Some(on("light.b")), off("light.b"))
    runShared(
      dynDash,
      after,
      change,
      seedCache = Map("c_light_a" -> "<a>", "c_light_b" -> "<b>")
    ).map { case (patches, cache) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      // An `Inner` fill AT THE MOUNT — the group's own element, which IS its
      // mount — carrying only the surviving member. Not an outer morph of the
      // group, which would have been a patch containing other nodes.
      assert(p.contains("mode inner"), clue = p)
      assert(p.contains("selector #c"), clue = p)
      assert(p.contains("""id="c_light_a""""), clue = p)
      assert(!p.contains("""id="c_light_b""""), clue = p)
      // The departed member's entry is gone and the surviving one is
      // RE-FINGERPRINTED: the fill re-supplied the mount wholesale, so without
      // that the next live diff would compare against a baseline the client never
      // had. And no group-level entry is written — that would be a fragment
      // containing another node.
      assert(!cache.contains("c_light_b"), clue = cache)
      assert(cache.contains("c_light_a"), clue = cache)
      assert(!cache.contains("c"), clue = cache)
    }
  }

  test("membership change on a not-yet-logged group falls back to a fill") {
    // Same 1-of-4 remove that would be per-entity — but with an EMPTY log the
    // group isn't established, so we fill to establish a known base.
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> off("light.b"),
      "light.c" -> on("light.c"),
      "light.d" -> on("light.d")
    )
    val change = StateChange("light.b", Some(on("light.b")), off("light.b"))
    runShared(dynDash, after, change).map { case (patches, cache) =>
      assertEquals(patches.size, 1, clue = patches)
      assert(patches.head.contains("mode inner"), clue = patches)
      assert(patches.head.contains("selector #c"), clue = patches)
      // Established by its MEMBERS' entries, so the next churn takes the delta
      // path — and by no entry of its own.
      assert(cache.contains("c_light_a"), clue = cache)
      assert(!cache.contains("c"), clue = cache)
    }
  }

  // A dynamic group inside an open SURFACE (id "det"); its group id is
  // surface-namespaced `s_det__c`, children `s_det__c_<slug>`.
  private def surfaceDynDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "dot" -> CardDef("<span>{{state}}</span>", slots = List("state"))
    ),
    card = LayoutNode.Component("col"),
    surfaces = Map(
      "det" -> Surface(
        LayoutNode.Dynamic(
          query = Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
          cases = List(
            DynamicCase(
              Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__")),
              "dot",
              slots = Map("state" -> SlotSource())
            )
          )
        )
      )
    )
  )

  /** '''A client is never sent a surface it is not viewing.'''
    *
    * Written BEFORE the per-session pass collapses into the shared one, because
    * this is the property that collapse must PRESERVE, not one it adds — and
    * because under-sending is the one failure mode nothing observes. A patch
    * withheld from a client that needed it looks exactly like nothing at all:
    * no error, no log line, just a value that stops updating. So the guard goes
    * in first, against today's behaviour, and the filter is written to keep it
    * green (docs/plan-one-shared-log.md, T3).
    *
    * Both directions are asserted deliberately. Without the second half this
    * would pass just as well if the server sent NOBODY anything.
    */
  test(
    "a tab nobody is viewing is not pushed to them; the viewer still gets it"
  ) {
    val after = Map(
      "sensor.a" -> es("sensor.a", "A0"),
      "sensor.b" -> es("sensor.b", "B1")
    )
    // The change is inside tab 1's panel, which only B has open.
    val change =
      StateChange("sensor.b", Some(es("sensor.b", "B0")), es("sensor.b", "B1"))
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(tabsRenderer)
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          for {
            viewingT0 <- Session.create("dashboard")
            _ <- viewingT0.open.set(Set("c_t0"))
            _ <- sessions.register("a", viewingT0)
            viewingT1 <- Session.create("dashboard")
            _ <- viewingT1.open.set(Set("c_t1"))
            _ <- sessions.register("b", viewingT1)
            renderer <- ref.get
            cache <- Ref[IO].of(seedLog(Map.empty))
            // ONE render for the slug; who sees it is the tag's job.
            shared <- server.sharedPatches("dashboard", renderer, cache, change)
          } yield ready(shared)
        }
    } yield out)
      .timeout(30.seconds)
      .map { patches =>
        // Tab 1's panel is open for SOMEBODY, so it is rendered — once.
        assertEquals(patches.size, 1, clue = patches.map(_.event.renderString))
        val one = patches.head
        assert(
          one.event.elements.exists(_.contains("""id="s_c_t1__c"""")),
          clue = one.event.renderString
        )
        assert(one.event.elements.exists(_.contains("B1")), clue = one.event)
        // A is looking at tab 0 and must not receive it; B, who IS looking at
        // it, must — the second half is what stops this passing vacuously.
        assertEquals(one.surface, Some("c_t1"))
        assert(!one.visibleTo(Set("c_t0")))
        assert(one.visibleTo(Set("c_t1")))
      }
  }

  test("open surface's dynamic group gets the same per-entity treatment") {
    val after = Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))
    val change = StateChange("light.b", Some(on("light.b")), on("light.b"))
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(Renderer.create(surfaceDynDash))
      sessions <- Sessions.create
      // Stub HA: the SSE/patch path never calls it (an unexpected registry call
      // still raises); the store is driven in-memory, so the empty seed is inert.
      fake <- FakeHomeAssistant.create(Nil)
      patches <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          for {
            session <- Session.create("dashboard")
            _ <- session.open.set(Set("det"))
            _ <- sessions.register("conn", session)
            renderer <- ref.get
            cache <- Ref[IO].of(seedLog(Map.empty))
            ps <- server.sharedPatches("dashboard", renderer, cache, change)
          } yield ready(ps)
        }
    } yield patches)
      .timeout(30.seconds)
      .map { patches =>
        assertEquals(patches.size, 1, clue = patches.map(_.event.renderString))
        val one = patches.head
        // one child morph, surface-namespaced id — not the whole surface group.
        assertEquals(
          one.event.elements,
          Some(
            """<div class="fh-cell" id="s_det__c_light_b"><span>on</span></div>"""
          )
        )
        // Rendered on the shared pass, addressed to the popup that holds it.
        assertEquals(one.surface, Some("det"))
      }
  }

  // ---------------------------------------------------------------------------
  // State-activated surfaces on the SHARED pass: hidden-branch silence, flips
  // with cache prune, nested groups, popup containment (the feature contract)
  // ---------------------------------------------------------------------------

  private val always: Predicate =
    Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__"))

  // "Entity X is in state Y": the entity_id pin + the default Any quantifier.
  private def entityIs(id: String, state: String): Predicate =
    Predicate.And(
      List(
        Predicate.Cmp("entity_id", Op.Eq, Json.fromString(id)),
        Predicate.Cmp("state", Op.Eq, Json.fromString(state))
      )
    )

  private val armedCond = entityIs("alarm.h", "armed")

  private val ifCards = Map(
    "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
    // A pure mount, like lib/components.pkl's `If`.
    "ifhost" -> CardDef(
      template = "{{{self}}}{{{mount}}}",
      mount = Some("""<div id="{{mountId}}">{{{branch}}}</div>""")
    ),
    "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
    "dot" -> CardDef("<b>{{state}}</b>", slots = List("state"))
  )

  private def branchCard(entity: String): LayoutNode.Component =
    LayoutNode.Component(
      "card",
      slots = Map("state" -> SlotSource(Some(entity)))
    )

  private def stateMember(
      content: LayoutNode,
      host: String,
      index: Int,
      condition: Predicate
  ): Surface =
    Surface(
      content,
      bakeInto = Some(host),
      bakeAs = Some("branch"),
      bakeIndex = Some(index),
      activation = Activation.State(condition)
    )

  /** An If/else dashboard: `ifhost` at "c_0" (col -> ifhost); `then` shows
    * sensor.a while alarm.h == armed, the always-true `else` shows sensor.b.
    */
  private def ifDash(
      thenContent: LayoutNode = branchCard("sensor.a"),
      elseContent: LayoutNode = branchCard("sensor.b")
  ): Dashboard =
    Dashboard(
      cards = ifCards,
      card = LayoutNode
        .Component("col", children = List(LayoutNode.Component("ifhost"))),
      surfaces = Map(
        "then" -> stateMember(thenContent, "c_0", 0, armedCond),
        "else" -> stateMember(elseContent, "c_0", 1, always)
      )
    )

  private def es(id: String, state: String): EntityState = st(id, state)

  /** Drives the SHARED per-slug pass over an EVOLVING store: each [[step]]
    * applies one entity update (deriving the StateChange exactly like the WS
    * ingest does) and returns the SSE patches emitted for it, diffing against
    * the cache ACCUMULATED across steps — what multi-step contracts (flip then
    * re-reveal) need, unlike the single-shot [[runShared]].
    */
  private class SharedHarness(
      store: StateStore,
      val server: Server,
      renderer: Renderer,
      cache: Ref[IO, FragmentLog]
  ) {
    private def sharedBatch(next: EntityState): IO[List[ServerSentEvent]] =
      (for {
        prev <- store.snapshot.map(_.get(next.entityId))
        _ <- store.update(next)
        patches <- server.sharedPatches(
          "dashboard",
          renderer,
          cache,
          StateChange(next.entityId, prev, next)
        )
      } yield readyEvents(patches)).timeout(30.seconds)

    def step(next: EntityState): IO[List[String]] =
      sharedBatch(next).map(elementPatches)

    /** Everything a batch emits, cursor signal included. */
    def stepRaw(next: EntityState): IO[List[String]] =
      sharedBatch(next).map(_.map(_.renderString))

    def cacheNow: IO[Map[NodeId, Long]] =
      cache.get.map(logged).timeout(30.seconds)

    def logId: IO[String] = cache.get.map(_.id)

    def headHash: String = renderer.headHash

    def styleHash: String = renderer.styleHash

    /** Connect to the SSE route with `cursor` in the `datastar` signal param —
      * exactly how a reconnecting browser arrives — and read the OPENING block.
      * That ends at the cursor signal, which the connect path emits last, or at
      * the reload signal, which replaces the whole block.
      */
    def opening(
        cursor: Option[Server.Cursor],
        popup: Option[String] = None
    ): IO[String] =
      val signals = cursor.toList.flatMap(c =>
        List(
          s""""${Server.HeadHashSignal}":"${c.headHash}"""",
          s""""${Server.StyleHashSignal}":"${c.styleHash}"""",
          s""""${Server.LogIdSignal}":"${c.logId}"""",
          s""""${Server.StoreVersionSignal}":${c.version}"""
        )
      ) ++ popup.map(p => s""""$PopupSig":"$p"""")
      val uri =
        if (signals.isEmpty) uri"/sse/dashboard/dashboard/patch"
        else
          uri"/sse/dashboard/dashboard/patch"
            .withQueryParam("datastar", signals.mkString("{", ",", "}"))
      server.routes.orNotFound
        .run(Request[IO](Method.GET, uri))
        .flatMap(
          _.body
            .through(fs2.text.utf8.decode)
            .scan("")(_ + _)
            .takeThrough(seen =>
              !seen.contains(Server.StoreVersionSignal) &&
                !seen.contains(Server.ReloadSignal)
            )
            .compile
            .lastOrError
        )
        .timeout(30.seconds)
  }

  private object SharedHarness {

    /** One supervisor for the whole suite.
      *
      * [[Server.resource]] normally owns this (it scopes the shared publishers'
      * fibers), but these harness tests hold their `Server` in `IO` rather than
      * `Resource` — they drive `sharedPatches` directly and never start a
      * publisher, so nothing is ever supervised through it and it holds no
      * fibers to leak. Allocated without a release for the same reason: there
      * is nothing to cancel.
      */
    private lazy val suiteSupervisor: Supervisor[IO] =
      Supervisor[IO].allocated.unsafeRunSync()._1

    def create(
        dash: Dashboard,
        initial: Map[String, EntityState]
    ): IO[SharedHarness] =
      (for {
        store <- StateStore.inMemory(initial)
        ref <- SignallingRef[IO].of(Renderer.create(dash))
        sessions <- Sessions.create
        // Stub HA: the SSE/patch path never calls it (an unexpected registry
        // call still raises); the store is driven in-memory, so the empty seed
        // is inert.
        fake <- FakeHomeAssistant.create(Nil)
        slugLog <- Server.freshLog.flatMap(Ref[IO].of)
        registry <- Ref[IO].of(
          Map("dashboard" -> Server.LiveSlug(ref, slugLog))
        )
        topic <- Topic[IO, (String, Directed)]
        server = new Server(
          HomeAssistantApi.fromWs(fake),
          store,
          registry,
          "dashboard",
          sessions,
          topic,
          suiteSupervisor
        )
        renderer <- ref.get
        // The shared pass diffs against the SLUG's log — the same one a
        // reconnecting client resumes from — so a cursor issued by `step` is
        // valid at `opening`, as in production.
      } yield new SharedHarness(store, server, renderer, slugLog))
        .timeout(30.seconds)
  }

  test("state surfaces: churn in the INACTIVE branch emits ZERO patches") {
    for {
      h <- SharedHarness.create(
        ifDash(),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "A0"),
          "sensor.b" -> es("sensor.b", "B0"),
          "sensor.z" -> es("sensor.z", "Z0")
        )
      )
      // then (sensor.a) is active; the ELSE branch's entity churns silently —
      // its member surface is never in the active set, so its index is never
      // consulted (structural silence, not a filtered render).
      _ <- h.step(es("sensor.b", "B1")).assertEquals(Nil)
      // An entity no branch binds and no condition reads: nothing at all (the
      // O(1) shortcut path — no member condition match flipped for it).
      _ <- h.step(es("sensor.z", "Z1")).assertEquals(Nil)
      // The ACTIVE branch's entity, by contrast, patches its surface-scoped node.
      live <- h.step(es("sensor.a", "A1"))
    } yield {
      assertEquals(live.size, 1, clue = live)
      assert(live.head.contains("""id="s_then__c""""), clue = live)
      assert(live.head.contains("A1"), clue = live)
    }
  }

  test(
    "state flip: ONE overwrite of the host's mount, at CURRENT state"
  ) {
    for {
      h <- SharedHarness.create(
        ifDash(),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "A0"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      // Establish the active branch in the shared cache...
      _ <- h.step(es("sensor.a", "A1")).map(p => assertEquals(p.size, 1))
      // ...and churn the hidden branch (never rendered, never patched).
      _ <- h.step(es("sensor.b", "B1")).assertEquals(Nil)
      // The flip: ONE patch. The mount takes at most one member, so overwriting
      // it IS the delta — no siblings to preserve, no position to fix — and it
      // lands the same whatever the client currently holds there. Not a morph of
      // the host, whose HTML would have embedded the branch.
      flip <- h.step(es("alarm.h", "disarmed"))
      cache <- h.cacheNow
    } yield {
      assertEquals(flip.size, 1, clue = flip)
      val p = flip.head
      assert(p.contains("mode inner"), clue = p)
      // The MOUNT — `Surface.hostId`, the id the If's mount template carries.
      assert(p.contains("selector #c_0_branch"), clue = p)
      assert(p.contains("""id="s_else__c""""), clue = p)
      // Rendered against CURRENT state: B1, which no client ever saw.
      assert(p.contains("B1"), clue = p)
      assert(!p.contains("A1"), clue = p)
      assert(!p.contains("mode remove"), clue = p)
      // The prune keeps its original job (hidden-branch churn leaves entries
      // stale), and the new branch's ROOT is now logged as the mount's occupant —
      // structure, not content. No host-level fragment at all.
      assert(!cache.keys.exists(_.startsWith("s_then__")), clue = cache)
      assert(cache.contains("s_else__c"), clue = cache)
      assert(!cache.contains("c_0"), clue = cache)
    }
  }

  /** '''A flip that happens while a client is away must survive the
    * reconnect''' (docs/plan-one-shared-log.md, T8b) — the exact hole recording
    * the flip structurally was meant to close.
    *
    * Found in the running app before this test existed: `Patches.resume`
    * grouped placements by container and looked each member up by POSITION in
    * `dynamicMembers`, which is empty for a state group — so a `Placed`
    * carrying a `Surface` member matched nothing and was dropped. The client
    * got the `Gone`, its branch vanished, and nothing ever put one back.
    * Silent, and permanent until an unrelated change moved something.
    */
  test("a flip across a disconnect replays as the same single overwrite") {
    for {
      h <- SharedHarness.create(
        ifDash(),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "A0"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      // Establish the then-branch, then note where a client's cursor sits.
      _ <- h.step(es("sensor.a", "A1"))
      logId <- h.logId
      cursor = Some(Server.Cursor(h.headHash, h.styleHash, logId, 2L))
      // It flips while that client is away.
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      opening <- h.opening(cursor)
    } yield {
      // The new branch ARRIVES — without it the host is left empty, which is
      // exactly what the running app showed before this was fixed.
      assert(opening.contains("mode inner"), clue = opening)
      assert(opening.contains("selector #c_0_branch"), clue = opening)
      assert(opening.contains("""id="s_else__c""""), clue = opening)
      // Rendered from the CURRENT snapshot, and not via a body repaint.
      assert(opening.contains("B0"), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
      // The overwrite subsumes the removal: a client that already applied the
      // flip and one that missed it both land on the same DOM, so there is no
      // paired remove to reason about.
      assert(!opening.contains("mode remove"), clue = opening)
    }
  }

  test(
    "flip prune: a re-revealed child diffs cleanly (no stale-cache suppression)"
  ) {
    for {
      h <- SharedHarness.create(
        ifDash(),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "boot"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      // 1. Cache the then-branch child at "on".
      _ <- h.step(es("sensor.a", "on")).map(p => assertEquals(p.size, 1))
      // 2. Flip away (prunes s_then__*), 3. churn the hidden branch to "off"
      // (silent — the stale-entry trap this test springs), 4. flip back (the
      // arriving branch is rendered from current state, so it shows "off").
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      _ <- h.step(es("sensor.a", "off")).assertEquals(Nil)
      back <- h.step(es("alarm.h", "armed"))
      _ = assertEquals(back.size, 1, clue = back)
      _ = assert(back.head.contains("off"), clue = back)
      // 5. The re-revealed child returns to "on" — HTML byte-identical to the
      // step-1 cache entry. Without the flip prune this would be suppressed as
      // "unchanged" while the DOM (showing "off") has moved on.
      reveal <- h.step(es("sensor.a", "on"))
    } yield {
      assertEquals(reveal.size, 1, clue = reveal)
      assert(reveal.head.contains("""id="s_then__c""""), clue = reveal)
      assert(reveal.head.contains("on"), clue = reveal)
    }
  }

  test("a dynamic group inside an INACTIVE branch stays silent") {
    val dyn = LayoutNode.Dynamic(
      query = Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
      cases = List(
        DynamicCase(always, "dot", slots = Map("state" -> SlotSource()))
      )
    )
    for {
      h <- SharedHarness.create(
        ifDash(thenContent = dyn),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "light.x" -> es("light.x", "on"),
          "light.y" -> es("light.y", "on"),
          "light.z" -> es("light.z", "on"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      // Active branch: the group's members get the usual per-entity treatment,
      // scoped under the member surface's id namespace.
      tick <- h.step(es("light.x", "on2"))
      // "on2" fails the query -> a membership change (remove) for the group.
      _ = assert(tick.nonEmpty, clue = tick)
      _ = assert(tick.forall(_.contains("s_then__c")), clue = tick)
      // Flip to else: one overwrite of the mount...
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      // ...and now the group is in a hidden branch: query-affecting churn that
      // would previously re-render it emits NOTHING.
      _ <- h.step(es("light.y", "off")).assertEquals(Nil)
      _ <- h.step(es("light.y", "on")).assertEquals(Nil)
    } yield ()
  }

  test("nested state groups: inner flips patch only inside the ACTIVE branch") {
    // Outer If ("c_0"): then-branch content is col(ifhost) — the INNER host
    // lives at the member's content path s_then__c_0; its members nest one
    // level deeper. Inner condition: mode.h == night.
    val innerHost =
      LayoutNode.Component(
        "col",
        children = List(LayoutNode.Component("ifhost"))
      )
    val d = Dashboard(
      cards = ifCards,
      card = LayoutNode
        .Component("col", children = List(LayoutNode.Component("ifhost"))),
      surfaces = Map(
        "then" -> stateMember(innerHost, "c_0", 0, armedCond),
        "else" -> stateMember(branchCard("sensor.b"), "c_0", 1, always),
        "in_then" -> stateMember(
          branchCard("sensor.x"),
          "s_then__c_0",
          0,
          entityIs("mode.h", "night")
        ),
        "in_else" -> stateMember(
          branchCard("sensor.y"),
          "s_then__c_0",
          1,
          always
        )
      )
    )
    for {
      h <- SharedHarness.create(
        d,
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "mode.h" -> es("mode.h", "night"),
          "sensor.x" -> es("sensor.x", "X0"),
          "sensor.y" -> es("sensor.y", "Y0"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      // Outer active: the inner flip patches ONLY the inner host's mount
      // (recursion into the active member's index found it), with its else branch.
      innerFlip <- h.step(es("mode.h", "day"))
      _ = assertEquals(innerFlip.size, 1, clue = innerFlip)
      _ = assert(
        innerFlip.head.contains("selector #s_then__c_0_branch"),
        clue = innerFlip
      )
      _ = assert(
        innerFlip.head.contains("""id="s_in_else__c""""),
        clue = innerFlip
      )
      // Flip the OUTER group away...
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      // ...then the inner group's condition flips inside the hidden branch:
      // unreachable DOM, zero patches (the active-set recursion never descends
      // into an unselected member).
      _ <- h.step(es("mode.h", "night")).assertEquals(Nil)
      // Liveness inside the hidden branch's active member is silent too.
      _ <- h.step(es("sensor.y", "Y1")).assertEquals(Nil)
    } yield ()
  }

  test(
    "a state group inside an open popup is rendered SHARED, tagged with it"
  ) {
    // The If roots inside popup "det" (owner s_det__c_0). Its flip is a pure
    // function of entity state — identical for every client that can see it —
    // so it is rendered ONCE for the slug and addressed to "det", not
    // re-rendered per connection. The popup being in SOMEONE's open set is what
    // makes it worth rendering at all.
    val d = Dashboard(
      cards = ifCards,
      card = LayoutNode.Component("col"),
      surfaces = Map(
        "det" -> Surface(
          LayoutNode
            .Component("col", children = List(LayoutNode.Component("ifhost")))
        ),
        "d_then" -> stateMember(
          branchCard("sensor.a"),
          "s_det__c_0",
          0,
          armedCond
        ),
        "d_else" -> stateMember(branchCard("sensor.b"), "s_det__c_0", 1, always)
      )
    )
    val after = Map(
      "alarm.h" -> es("alarm.h", "disarmed"),
      "sensor.a" -> es("sensor.a", "A0"),
      "sensor.b" -> es("sensor.b", "B0")
    )
    val change =
      StateChange(
        "alarm.h",
        Some(es("alarm.h", "armed")),
        es("alarm.h", "disarmed")
      )
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(Renderer.create(d))
      sessions <- Sessions.create
      // Stub HA: the SSE/patch path never calls it (an unexpected registry call
      // still raises); the store is driven in-memory, so the empty seed is inert.
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          for {
            renderer <- ref.get
            session <- Session.create("dashboard")
            _ <- session.open.set(Set("det"))
            _ <- sessions.register("conn", session)
            cache <- Ref[IO].of(seedLog(Map.empty))
            shared <- server.sharedPatches("dashboard", renderer, cache, change)
          } yield shared
        }
    } yield out)
      .timeout(30.seconds)
      .map { shared =>
        // The shared pass carries the inner flip's delta — once, for the slug.
        val patches = ready(shared)
        assertEquals(patches.size, 1, clue = patches)
        val one = patches.head
        assertEquals(one.event.selector, Some("#s_det__c_0_branch"))
        assert(
          one.event.elements.exists(_.contains("""id="s_d_else__c"""")),
          clue = one.event.renderString
        )
        // Addressed to the popup: a client with it open sees it, one without
        // does not. That tag is the whole per-connection filter.
        assertEquals(one.surface, Some("det"))
        assert(one.visibleTo(Set("det")))
        assert(!one.visibleTo(Set.empty))
      }
  }

  // ---------------------------------------------------------------------------
  // Resume on reconnect (ADR 0011)
  //
  // The failure mode is SILENT — the server believes the browser is current and
  // suppresses the patch, so a wrong resume shows stale values indefinitely. Each
  // test below therefore asserts BOTH what the client gets and that the full-body
  // repaint (`selector #dashboard`) was or was not used.
  // ---------------------------------------------------------------------------

  private val BodyRepaint = "selector #dashboard"

  test("cursorOf reads the resume cursor off the datastar signal param") {
    def req(q: String): Request[IO] =
      Request[IO](
        Method.GET,
        uri"/sse/dashboard/d/patch".withQueryParam("datastar", q)
      )
    assertEquals(
      Server.cursorOf(
        req(
          """{"headHash":"h1","styleHash":"s1","logId":"L1","storeVersion":7}"""
        )
      ),
      Some(Server.Cursor("h1", "s1", "L1", 7L))
    )
    // A first load carries only the signals the page declared — no cursor.
    assertEquals(Server.cursorOf(req("""{"conn":"c","haDown":false}""")), None)
    // Partial, garbled, and absent are all the same answer.
    assertEquals(Server.cursorOf(req("""{"logId":"L1"}""")), None)
    assertEquals(Server.cursorOf(req("not json")), None)
    assertEquals(
      Server.cursorOf(Request[IO](Method.GET, uri"/sse/dashboard/d/patch")),
      None
    )
  }

  test("a non-empty shared batch advances the VERSION, and nothing else") {
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      raw <- h.stepRaw(es("sensor.a", "hot"))
      // An entity no card binds: nothing rendered, so no cursor either — the
      // client's existing cursor still names its DOM.
      quiet <- h.stepRaw(es("sensor.unwatched", "x"))
    } yield {
      assertEquals(raw.size, 2, clue = raw)
      assert(raw.last.contains(s""""${Server.StoreVersionSignal}":1"""), raw)
      // The other three are constant for the life of a renderer, so a batch does
      // not repeat them — that is bytes on every patch of every connection, and
      // every signal a client holds is serialised back into every request it
      // makes. They are (re)established on connect and on a renderer swap.
      assert(!raw.last.contains(Server.LogIdSignal), clue = raw)
      assert(!raw.last.contains(Server.HeadHashSignal), clue = raw)
      assert(!raw.last.contains(Server.StyleHashSignal), clue = raw)
      assertEquals(quiet, Nil, clue = quiet)
    }
  }

  test("a valid cursor resumes with the changed fragment, no body repaint") {
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      _ <- h.step(es("sensor.a", "hot"))
      logId <- h.logId
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      )
    } yield {
      assert(opening.contains(">hot<"), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
    }
  }

  test("a member that LEFT across the disconnect resumes as a remove patch") {
    // The saving resume exists for: one small patch, not a group morph.
    val lights = List("light.a", "light.b", "light.c", "light.d")
    for {
      h <- SharedHarness.create(
        dynDash,
        lights.map(id => id -> on(id)).toMap + ("light.e" -> off("light.e"))
      )
      // Establish the group in the shared log first (the very first membership
      // change always repaints wholesale — there is no per-entity base yet).
      _ <- h.step(on("light.e"))
      // ...then b leaves: 1 of 5 shown, under the churn fraction, so per-entity.
      left <- h.step(off("light.b"))
      logId <- h.logId
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, h.styleHash, logId, 2L))
      )
    } yield {
      assertEquals(left.size, 1, clue = left)
      assert(opening.contains("selector #c_light_b"), clue = opening)
      assert(opening.contains("mode remove"), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
    }
  }

  test("every doubt about the cursor falls back to the full body repaint") {
    val cold = Map("sensor.a" -> es("sensor.a", "cold"))
    def opening(
        cursor: SharedHarness => IO[Option[Server.Cursor]]
    ): IO[String] =
      for {
        h <- SharedHarness.create(liveLeafDash, cold)
        _ <- h.step(es("sensor.a", "hot"))
        c <- cursor(h)
        out <- h.opening(c)
      } yield out
    for {
      // No cursor at all — a fresh page load, whose body is server-rendered.
      none <- opening(_ => IO.pure(None))
      // A log this server no longer has (a restart, or a renderer swap).
      staleLog <- opening(h =>
        IO.pure(
          Some(Server.Cursor(h.headHash, h.styleHash, "gone-with-the-log", 1L))
        )
      )
      // A version this store never reached.
      future <- opening(h =>
        h.logId.map(id => Some(Server.Cursor(h.headHash, h.styleHash, id, 99L)))
      )
    } yield {
      assert(none.contains(BodyRepaint), clue = none)
      assert(staleLog.contains(BodyRepaint), clue = staleLog)
      assert(future.contains(BodyRepaint), clue = future)
    }
  }

  test("a client whose <head> has changed is reloaded, not patched") {
    // The one thing a body patch cannot repair: the browser is holding the
    // previous theme's stylesheets. Nothing else is sent — the page is about to
    // render itself from scratch.
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      _ <- h.step(es("sensor.a", "hot"))
      logId <- h.logId
      opening <- h.opening(
        Some(Server.Cursor("0000deadbeef", h.styleHash, logId, 1L))
      )
    } yield {
      assert(opening.contains(s""""${Server.ReloadSignal}":true"""), opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
      assert(!opening.contains(">hot<"), clue = opening)
    }
  }

  /** A shared leaf (`sensor.shared`) beside a tabs host at "c_1", whose default
    * panel shows `sensor.a`. The panel's HTML bakes a client-selected member,
    * so it is rendered PER SESSION and never enters the slug's shared log —
    * which is the whole point of step 5.
    */
  private def mixedTabsDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      // A bar-less tabs host: pure mount, no `self` — nothing about it can
      // change without its content changing.
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        mount = Some("""<div id="{{mountId}}" class="tabs">{{{panel}}}</div>""")
      )
    ),
    card = LayoutNode.Component(
      "col",
      children = List(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.shared")))
        ),
        LayoutNode.Component("tabs")
      )
    ),
    surfaces = Map(
      "t0" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.a")))
        ),
        bakeInto = Some("c_1"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.User(defaultOpen = true)
      )
    )
  )

  test("a resume reconciles an OPEN surface's nodes, and only what differs") {
    for {
      h <- SharedHarness.create(
        mixedTabsDash,
        Map(
          "sensor.shared" -> es("sensor.shared", "cold"),
          "sensor.a" -> es("sensor.a", "old")
        )
      )
      // v1: shared, logged. v2: inside the tab panel, so the shared pass emits
      // nothing and nothing records it — exactly what the previous connection's
      // (now dead) per-session cache used to cover.
      _ <- h.step(es("sensor.shared", "hot"))
      panelTick <- h.step(es("sensor.a", "new"))
      logId <- h.logId
      // The client's cursor is at v1: it already has ">hot<".
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      )
    } yield {
      assertEquals(panelTick, Nil, clue = panelTick)
      // The panel node is reconciled on its OWN id — the untracked case, caught
      // by `fingerprint != stored` with no entry at all. Without it this value
      // would never reach the reconnected DOM: the silent staleness the whole
      // resume path risks.
      assert(opening.contains(">new<"), clue = opening)
      assert(opening.contains("""id="s_t0__c""""), clue = opening)
      // And the tabs HOST is not re-sent, because nothing about it changed. The
      // old mechanism painted every per-session root fresh on every resume; one
      // rule over one candidate set sends only what actually differs.
      assert(!opening.contains("""class="tabs""""), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
    }
  }

  test("a popup open across the disconnect is restored fresh, not closed") {
    // Backgrounding a phone tab must not dismiss the dialog you were reading.
    // The popup's content is per-session and its host sits outside #dashboard,
    // so neither the resume nor the repaint reaches it: it is re-rendered from
    // the client's own claim.
    val hostSelector = s"selector #${Dashboard.PopupHostId}"
    val hostReset = s"""<div id="${Dashboard.PopupHostId}"></div>"""
    val withPopup = liveLeafDash.copy(
      surfaces = Map(
        "det" -> Surface(
          LayoutNode.Component(
            "card",
            slots = Map("state" -> SlotSource(Some("sensor.b")))
          )
        )
      )
    )
    for {
      h <- SharedHarness.create(
        withPopup,
        Map(
          "sensor.a" -> es("sensor.a", "cold"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      _ <- h.step(es("sensor.a", "hot"))
      // Inside the popup, so the shared pass is silent about it and the previous
      // connection's (now dead) per-session cache was its only record.
      _ <- h.step(es("sensor.b", "B1")).assertEquals(Nil)
      logId <- h.logId
      cursor = Some(Server.Cursor(h.headHash, h.styleHash, logId, 1L))
      restored <- h.opening(cursor, popup = Some("det"))
      // A claim this dashboard cannot serve is the one case that clears the host.
      orphan <- h.opening(cursor, popup = Some("was-renamed"))
      // Claiming nothing leaves the host alone — nothing is open to keep.
      quiet <- h.opening(cursor)
    } yield {
      // No popup-shaped branch: its nodes are in `open`, so the ONE resume rule
      // reconciles them on their own ids and the dialog is never disturbed.
      assert(restored.contains(">B1<"), clue = restored)
      assert(!restored.contains(hostSelector), clue = restored)
      assert(!restored.contains(hostReset), clue = restored)
      // The one thing still worth a branch: a claim this dashboard no longer
      // serves. That dialog belongs to nothing and is in nobody's open set, so
      // without this it would sit on screen forever.
      assert(orphan.contains(hostReset), clue = orphan)
      assert(!quiet.contains(Dashboard.PopupHostId), clue = quiet)
    }
  }

  test("the popup signal follows the host: open, switch, close") {
    val dash = liveLeafDash.copy(
      surfaces = Map(
        "det" -> Surface(LayoutNode.Component("col")),
        "other" -> Surface(LayoutNode.Component("col"))
      )
    )
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "cold")))
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          val conn = "c1"
          val post = (p: String) =>
            server.routes.orNotFound.run(
              Request[IO](Method.POST, Uri.unsafeFromString(p))
                .withEntity(s"""{"${Server.ConnSignal}":"$conn"}""")
            )
          for {
            session <- Session.create("dashboard")
            _ <- sessions.register(conn, session)
            _ <- post("/sse/surface/open/det")
            _ <- post("/sse/surface/open/other")
            _ <- post("/sse/popup/close")
            emitted <- session.control.tryTakeN(None)
            open <- session.open.get
          } yield (emitted.map(_.renderString), open)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (emitted, open) =>
        // The server pushes NO signal for the swap. The tap that asked for it
        // already set `ui_popups` client-side, the way a tab button sets its
        // own — one mechanism for every selection.
        assertEquals(emitted.filter(_.contains("datastar-patch-signals")), Nil)
        // One host, one occupant: a popup replaces the previous one, and a close
        // leaves nothing behind.
        assertEquals(open, Set.empty[String])
      }
  }

  test("headHash tracks <head>, and only <head>") {
    val base = Renderer.create(liveLeafDash).headHash
    // Stable across restarts (a fresh Renderer over an equal dashboard), so an
    // add-on restart does not refresh every browser.
    assertEquals(Renderer.create(liveLeafDash).headHash, base)
    // A card edit changes the BODY, which the repaint re-sends in full — so it
    // must NOT force a reload.
    val editedCard = liveLeafDash.copy(
      cards = liveLeafDash.cards
        .updated("card", CardDef("<b>{{state}}</b>", slots = List("state")))
    )
    assertEquals(Renderer.create(editedCard).headHash, base)
    // A new stylesheet does: nothing can un-apply the old one.
    val editedTheme = liveLeafDash.copy(theme =
      Theme(stylesheets = List("https://example.test/other.css"))
    )
    assertNotEquals(Renderer.create(editedTheme).headHash, base)
  }

  test("styleHash tracks the patchable head, and headHash ignores it") {
    val base = Renderer.create(liveLeafDash)
    assertEquals(Renderer.create(liveLeafDash).styleHash, base.styleHash)
    // Inline CSS and the title patch, so they move styleHash and leave
    // headHash — the reload trigger — alone.
    val restyled =
      liveLeafDash.copy(theme = Theme(styles = ".card{color:red}"))
    val renamed = liveLeafDash.copy(title = Some("Renamed"))
    List(restyled, renamed).foreach { d =>
      assertNotEquals(Renderer.create(d).styleHash, base.styleHash)
      assertEquals(Renderer.create(d).headHash, base.headHash)
    }
  }

  test("a stale theme is patched into the head, not reloaded") {
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      _ <- h.step(es("sensor.a", "hot"))
      logId <- h.logId
      opening <- h.opening(
        Some(Server.Cursor(h.headHash, "0000deadbeef", logId, 1L))
      )
    } yield {
      assert(
        opening.contains(s"""<style id="${Renderer.ThemeStyleId}">"""),
        opening
      )
      assert(opening.contains(s"""<title id="${Server.TitleId}">"""), opening)
      assert(!opening.contains(s""""${Server.ReloadSignal}":true"""), opening)
      // And the resume still happens: the head is repaired alongside it.
      assert(!opening.contains(BodyRepaint), clue = opening)
      assert(opening.contains(">hot<"), clue = opening)
    }
  }

  // ---------------------------------------------------------------------------
  // End to end over the REAL stream
  // ---------------------------------------------------------------------------

  /** These tests read the stream as EVENTS, not as one string, and assert on
    * every one of them rather than on the absence of a substring.
    *
    * `assert(!raw.contains(…))` passes for every reason including the ones
    * nobody meant — a renamed selector, an event that never arrived at all, a
    * typo in the needle — so it pins almost nothing. Naming the exact sequence
    * pins everything, and reads like the wire dump you would see in the
    * browser.
    *
    * Decoding is http4s's own `ServerSentEvent.decoder`, so the tests parse the
    * wire with the same type the server writes it with.
    */
  extension (e: ServerSentEvent) {
    private def line(key: String): Option[String] =
      e.data.toList
        .flatMap(_.linesIterator)
        // A SERVER-BUILT event carries the `data: ` prefix on its continuation
        // lines (the multi-line data field is assembled as wire text); a
        // DECODED one does not. Tolerating both lets these accessors read an
        // event straight off `sharedPatches` as well as one off the stream.
        .map(l => if (l.startsWith("data: ")) l.drop("data: ".length) else l)
        .collectFirst {
          case l if l.startsWith(s"$key ") => l.drop(key.length + 1)
        }

    /** Datastar's default when the event names none. */
    def mode: String = line("mode").getOrElse("outer")
    def selector: Option[String] = line("selector")
    def elements: Option[String] = line("elements")
    def signals: Option[String] = line("signals")
    def name: String = e.eventType.getOrElse("")
  }

  /** The ready-made patches a shared batch produced, cursor signal dropped —
    * what a test asserting on BYTES wants. A `Reveal` is not bytes: it is the
    * instruction one connection finishes for itself, so it is not here.
    */
  private def ready(out: List[Directed]): List[Addressed] =
    out.collect { case a: Addressed if !isCursor(a.event) => a }

  private def readyEvents(out: List[Directed]): List[ServerSentEvent] =
    out.collect { case a: Addressed => a.event }

  /** The patches a shared batch left for each connection to RENDER itself,
    * resolved against one viewer's selections.
    */
  private def varying(
      out: List[Directed],
      uiState: Map[String, String] = Map.empty
  ): List[ServerSentEvent] =
    out.collect { case v: Varying => v.render(uiState) }

  /** The popup host's selection signal — `ui_` + the host id, exactly as the
    * shell composes it.
    */
  private val PopupSig = Server.UiSignalPrefix + Dashboard.PopupHostId

  private val Elements = "datastar-patch-elements"
  private val Signals = "datastar-patch-signals"

  /** Read a response body as the events it carries. */
  private def sseFrom(
      resp: Response[IO]
  )(done: ServerSentEvent => Boolean): IO[List[ServerSentEvent]] =
    resp.body
      .through(ServerSentEvent.decoder[IO])
      .takeThrough(e => !done(e))
      .compile
      .toList

  /** The cursor handshake the connect path emits last — the end of the opening
    * block, and the marker every one of these reads stops on.
    */
  private def isCursor(e: ServerSentEvent): Boolean =
    e.signals.exists(_.contains(Server.StoreVersionSignal))

  /** Just the DOM events, as `(mode, selector, elements)`.
    *
    * The element stream is the contract worth pinning exactly. Signals are not:
    * `haDown` rides its own merged stream, so its POSITION among the others is
    * a scheduling detail, and asserting on it would buy a flaky test rather
    * than a stronger one. So these tests state the element sequence in full and
    * check for the signals they care about by presence.
    */
  private def domEvents(
      events: List[ServerSentEvent]
  ): List[(String, Option[String], Option[String])] =
    events
      .filter(_.name == Elements)
      .map(e => (e.mode, e.selector, e.elements))

  /** ONE connected client, driven a step at a time.
    *
    * A test walks the interaction the way a browser experiences it — connect,
    * assert; change, assert; change again, assert — instead of collecting one
    * blob at the end and rummaging in it. Every assertion is then about a
    * specific moment, and an event arriving at the wrong TIME fails as loudly
    * as one that never arrives.
    */
  private class LiveClient(seen: Ref[IO, Vector[ServerSentEvent]]) {

    /** Everything received since the last read. */
    def drain: IO[List[ServerSentEvent]] =
      seen.getAndSet(Vector.empty).map(_.toList)

    /** Wait for this client's stream to go QUIET — not for it to move.
      *
      * "This produced nothing for me" is a real and important answer (a hidden
      * branch, a value that renders identically, a surface someone else is
      * viewing), so waiting for movement would hang on exactly the cases worth
      * asserting. Quiet needs a floor of observation to mean anything, hence
      * the minimum window; after that it is a stability check rather than a
      * fixed sleep, so a slow step still gets however long it needs.
      */
    def settled: IO[Unit] =
      fs2.Stream
        .repeatEval(seen.get.map(_.size) <* IO.sleep(25.millis))
        .zipWithPrevious
        .drop(6)
        .find { case (prev, now) => prev.contains(now) }
        .compile
        .drain
        .timeout(15.seconds)
  }

  /** A booted server plus however many connected clients a test wants.
    *
    * MANY clients matter: what one connection is sent is only half the contract
    * — the other half is what the OTHERS are not sent, and that cannot be
    * observed from a single stream. `change` applies one entity update and
    * waits for every client to fall quiet, so each `drain` afterwards is
    * exactly what that client received for that change.
    *
    * In-process: `routes.run` on the `HttpApp`, no port and no socket, so this
    * is deterministic and as fast as a unit test. What it adds over
    * [[SharedHarness]] is the parts that harness deliberately skips — the
    * publisher fibers, the topic, the per-connection merge — which is exactly
    * where every bug the running app found had been hiding.
    */
  private class LiveWorld(
      routes: org.http4s.HttpApp[IO],
      store: StateStore,
      clients: Ref[IO, List[LiveClient]]
  ) {

    /** Connect as a browser does. `query` carries what a document would hand
      * back — `?ui.<id>=<n>` for a selected tab (see [[Server.Restore]]).
      */
    def connect(query: String = ""): IO[LiveClient] =
      for {
        seen <- Ref[IO].of(Vector.empty[ServerSentEvent])
        resp <- routes.run(
          Request[IO](
            Method.GET,
            Uri.unsafeFromString(s"/sse/dashboard/dashboard/patch$query")
          )
        )
        _ <- resp.body
          .through(ServerSentEvent.decoder[IO])
          .evalMap(e => seen.update(_ :+ e))
          .compile
          .drain
          .start
        client = new LiveClient(seen)
        // The opening block ends at the cursor handshake; wait for it so the
        // first `drain` is exactly what CONNECTING produced.
        _ <- fs2.Stream
          .repeatEval(seen.get <* IO.sleep(10.millis))
          .find(_.exists(isCursor))
          .compile
          .drain
          .timeout(15.seconds)
        _ <- clients.update(_ :+ client)
      } yield client

    /** Apply one change and wait for EVERY client to fall quiet. */
    def change(next: EntityState): IO[Unit] =
      store.update(next) *> clients.get.flatMap(_.traverse_(_.settled))
  }

  private def liveWorld(
      dash: Dashboard,
      initial: Map[String, EntityState]
  )(use: LiveWorld => IO[Unit]): IO[Unit] =
    (for {
      store <- StateStore.inMemory(initial)
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      clients <- Ref[IO].of(List.empty[LiveClient])
      _ <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use(server =>
          use(new LiveWorld(server.routes.orNotFound, store, clients))
        )
    } yield ()).timeout(30.seconds)

  /** One client, for the tests that only need one. */
  private def liveClient(
      dash: Dashboard,
      initial: Map[String, EntityState]
  )(use: (LiveWorld, LiveClient) => IO[Unit]): IO[Unit] =
    liveWorld(dash, initial)(w => w.connect().flatMap(use(w, _)))

  /** A main-page card plus a TWO-tab host (`c_1`), so two clients can be
    * looking at different panels of the same dashboard at the same time — the
    * shape the per-connection contract is actually about.
    */
  private def twoTabsDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        mount = Some("""<div id="{{mountId}}" class="tabs">{{{panel}}}</div>""")
      )
    ),
    card = LayoutNode.Component(
      "col",
      children = List(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.shared")))
        ),
        LayoutNode.Component("tabs")
      )
    ),
    surfaces = Map(
      "t0" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.a")))
        ),
        bakeInto = Some("c_1"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.User(defaultOpen = true)
      ),
      "t1" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.b")))
        ),
        bakeInto = Some("c_1"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1)
      )
    )
  )

  /** '''What one client is sent is only half the contract.'''
    *
    * The other half is what the OTHERS are not sent, and no single stream can
    * show it. This is the property ADR 0002's collapse must PRESERVE — today it
    * falls out of the per-session pass rendering only `open` surfaces; after
    * the collapse it has to be a deliberate per-connection filter — so it is
    * pinned here first, at the level the change will be judged on.
    *
    * Under-sending is the failure mode with no symptom: a patch withheld from a
    * client that needed it produces no error, just a value that quietly stops
    * updating.
    */
  test("two clients on different tabs: each sees only its own") {
    liveWorld(
      twoTabsDash,
      Map(
        "sensor.shared" -> es("sensor.shared", "s0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { world =>
      for {
        onT0 <- world.connect()
        onT1 <- world.connect("?ui.c_1=1")
        _ <- onT0.drain
        _ <- onT1.drain

        // A change inside TAB 0's panel.
        _ <- world.change(es("sensor.a", "A1"))
        a0 <- onT0.drain
        a1 <- onT1.drain
        _ = assert(
          domEvents(a0).exists(_._3.exists(_.contains("A1"))),
          clue = ("viewer of tab 0 must get it", a0)
        )
        _ = assertEquals(
          domEvents(a1),
          Nil,
          clue = ("viewer of tab 1 must get nothing", a1)
        )

        // ...and one inside TAB 1's, the mirror image.
        _ <- world.change(es("sensor.b", "B1"))
        b0 <- onT0.drain
        b1 <- onT1.drain
        _ = assertEquals(
          domEvents(b0),
          Nil,
          clue = ("viewer of tab 0 must get nothing", b0)
        )
        _ = assert(
          domEvents(b1).exists(_._3.exists(_.contains("B1"))),
          clue = ("viewer of tab 1 must get it", b1)
        )

        // A MAIN-PAGE change reaches both — the filter must not swallow what is
        // not surface-scoped at all.
        _ <- world.change(es("sensor.shared", "s1"))
        s0 <- onT0.drain
        s1 <- onT1.drain
        _ = assert(
          domEvents(s0).exists(_._3.exists(_.contains("s1"))),
          clue = s0
        )
        _ = assert(
          domEvents(s1).exists(_._3.exists(_.contains("s1"))),
          clue = s1
        )
      } yield ()
    }
  }

  /** Tabs INSIDE a flipping branch — the shape that still forces
    * [[Renderer.sessionOnlyStateGroups]] onto the per-session pass.
    *
    * A flip is decided by entity state, so the branch renders once for the slug
    * — but the tabs host inside it holds a mount whose contents each client
    * chose for itself. Rendering that mount on the shared pass would hand every
    * client the DEFAULT tab, silently yanking a viewer off the tab they picked.
    * Pinned here before the collapse deletes the pass that hides it.
    */
  private def tabsInBranchDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "ifhost" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        mount = Some("""<div id="{{mountId}}">{{{branch}}}</div>""")
      ),
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        mount = Some("""<div id="{{mountId}}" class="tabs">{{{panel}}}</div>""")
      )
    ),
    card = LayoutNode.Component(
      "col",
      children = List(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.shared")))
        ),
        LayoutNode.Component("ifhost")
      )
    ),
    surfaces = Map(
      // The armed branch IS a tabs host (node `s_then__c`).
      "then" -> stateMember(LayoutNode.Component("tabs"), "c_1", 0, armedCond),
      "else" -> stateMember(branchCard("sensor.z"), "c_1", 1, always),
      "t0" -> Surface(
        branchCard("sensor.a"),
        bakeInto = Some("s_then__c"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.User(defaultOpen = true)
      ),
      "t1" -> Surface(
        branchCard("sensor.b"),
        bakeInto = Some("s_then__c"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1)
      )
    )
  )

  /** A BARE container — a mount and no `self` — has no rendering of its own:
    * rendering it by id renders its whole subtree, mounts included. The log is
    * per SLUG, so a digest recorded for one is one viewer's bytes presented as
    * everyone's, and a resume re-rendering it hands that viewer's variant to
    * whoever asks.
    *
    * Concretely, and this is the failure it caused: a client on tab 1
    * reconnects and is morphed onto tab 0 — over a change inside tab 0's panel,
    * which it could not see and did not ask for.
    */
  private def barePopupTabsDash = Dashboard(
    cards = Map(
      // A PURE MOUNT, exactly as the shipped `Column` is: no `self`, children
      // in the mount. That shape is the whole point — it has no markup of its
      // own to fingerprint.
      "col" -> CardDef(
        template = "{{{mount}}}",
        mount = Some("<div>{{#children}}{{{html}}}{{/children}}</div>")
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        self = Some("""<div id="{{selfId}}">bar</div>"""),
        mount = Some("""<div id="{{mountId}}">{{{panel}}}</div>""")
      )
    ),
    card = LayoutNode.Component("col"),
    surfaces = Map(
      // The popup's content root is a bare `col` wrapping the tabs host.
      "det" -> Surface(
        LayoutNode.Component(
          "col",
          children = List(LayoutNode.Component("tabs"))
        )
      ),
      "t0" -> Surface(
        branchCard("sensor.a"),
        bakeInto = Some("s_det__c_0"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.User(defaultOpen = true)
      ),
      "t1" -> Surface(
        branchCard("sensor.b"),
        bakeInto = Some("s_det__c_0"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1)
      )
    )
  )

  test("a fill records what it put there, so the next tick suppresses") {
    // The point of the trace. Opening a surface renders it and patches it into
    // the host; the log now learns each node's bytes from that same render. So
    // when an entity inside it ticks to the SAME value, the diff can tell
    // "unchanged" from "never told" and sends nothing.
    //
    // Before, a fill dropped those entries, and the first tick after any
    // surface open re-sent every node in it once — the cost W10b named and
    // could not pay, because fingerprinting meant walking the subtree twice.
    val dash = Dashboard(
      cards = Map(
        "col" -> CardDef(
          template = "{{{mount}}}",
          mount = Some("<div>{{#children}}{{{html}}}{{/children}}</div>")
        ),
        "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
      ),
      card = LayoutNode.Component("col"),
      surfaces = Map(
        "det" -> Surface(
          LayoutNode.Component("col", children = List(branchCard("sensor.a")))
        )
      )
    )
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "cold")))
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          val conn = "c1"
          for {
            session <- Session.create("dashboard")
            _ <- sessions.register(conn, session)
            renderer <- ref.get
            live <- server.liveSlug("dashboard")
            painted = renderer
              .renderNodeById(
                "s_det__c_0",
                Map("sensor.a" -> es("sensor.a", "cold"))
              )
              .get
            // Nothing is recorded for a surface nobody has opened.
            beforeFill <- live.log.get.map(_.holds("s_det__c_0", painted))
            // Open the popup the way a tap does.
            _ <- server.routes.orNotFound.run(
              Request[IO](Method.POST, uri"/sse/surface/open/det")
                .withEntity(s"""{"${Server.ConnSignal}":"$conn"}""")
            )
            // The fill told the log what it painted...
            afterFill <- live.log.get.map(_.holds("s_det__c_0", painted))
            // ...so a tick that renders identically produces nothing for it.
            //
            // A SYNTHETIC change against an unchanged store, deliberately: a
            // real `store.update` wakes the background publisher, which calls
            // this same pass, and whichever reaches the log first leaves the
            // other seeing "unchanged". The suppression under test is the log's,
            // not a race's.
            same <- server.sharedPatches(
              "dashboard",
              renderer,
              live.log,
              StateChange(
                "sensor.a",
                Some(es("sensor.a", "cold")),
                es("sensor.a", "cold")
              )
            )
          } yield (beforeFill, afterFill, ready(same))
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (beforeFill, afterFill, same) =>
        // Not vacuous: the entry did not exist until the fill wrote it.
        assert(!beforeFill, clue = "nothing recorded before the surface opened")
        assert(afterFill, clue = "the fill must record the node it painted")
        assertEquals(same, Nil, clue = same.map(_.event.renderString))
      }
  }

  test("a resume cannot move a viewer onto a tab it did not choose") {
    val r = Renderer.create(barePopupTabsDash)
    val before =
      Map(
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    // The page seeds the log for the open surfaces' nodes, exactly as
    // `pageResponse` does — by id, with no viewer.
    val ids =
      (r.surfaceNodeIds("det") ++ r.surfaceNodeIds("t1")).toList.sorted
    val seeded = ids.foldLeft(FragmentLog("w18")) { (l, id) =>
      r.renderLogged(id, before).fold(l)(h => l.seed(id, h, 1L))
    }
    // This viewer holds tab 1.
    val open = Set("det", "t1")
    val mine = Map("s_det__c_0" -> "1")

    // (1) A change inside TAB 0's panel. Invisible to this viewer, and its
    //     content must not reach it by ANY route.
    val tab0Moved = before.updated("sensor.a", es("sensor.a", "A1"))
    val owed = Patches.resume(r, seeded, tab0Moved, 2L, open, mine)
    assert(
      !owed.exists(_.renderString.contains("s_t0__c")),
      clue = owed.map(_.renderString)
    )
    assert(!owed.exists(_.renderString.contains("A1")), clue = owed)

    // (2) ...and the guard is not vacuous: a change in ITS OWN panel does
    //     arrive. Without this the test would pass by sending nothing, ever.
    val tab1Moved = before.updated("sensor.b", es("sensor.b", "B1"))
    val mineOwed = Patches.resume(r, seeded, tab1Moved, 2L, open, mine)
    assert(
      mineOwed.exists(_.renderString.contains("B1")),
      clue = mineOwed.map(_.renderString)
    )
  }

  /** An inactive branch costs nothing — the guarantee ADR 0007 states — and it
    * has to hold for a USER surface nested inside one too.
    *
    * `selectedSurfaces` reports a selection for every bake group whether or not
    * that group is on screen, so a tab panel inside a hidden `If` is in its
    * client's open set while nothing of it exists in any DOM. Rendering and
    * pushing it is harmless (the morph targets an id the DOM lacks) and is pure
    * waste, per tick of every entity it binds.
    */
  test("a tab panel inside a HIDDEN branch costs nothing") {
    liveWorld(
      tabsInBranchDash,
      Map(
        // Disarmed: the `then` branch, which holds the tabs, is NOT active.
        "alarm.h" -> es("alarm.h", "disarmed"),
        "sensor.shared" -> es("sensor.shared", "s0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0"),
        "sensor.z" -> es("sensor.z", "Z0")
      )
    ) { world =>
      for {
        c <- world.connect()
        _ <- c.drain
        // sensor.a is bound ONLY inside tab 0's panel, inside the hidden
        // branch. Nothing on screen shows it.
        _ <- world.change(es("sensor.a", "A1"))
        hidden <- c.drain
        _ = assertEquals(domEvents(hidden), Nil, clue = hidden)

        // ...and the guard is not vacuous: a change the client CAN see still
        // arrives, through the same pass.
        _ <- world.change(es("sensor.shared", "s1"))
        seen <- c.drain
        _ = assert(
          domEvents(seen).exists(_._3.exists(_.contains("s1"))),
          clue = seen
        )
      } yield ()
    }
  }

  test("a reconnect is not owed another client's tab") {
    // Two viewers, different tabs, both inside the ACTIVE branch. A change in
    // tab 0's panel is rendered (its viewer needs it) and logged — so the
    // cursor names it. The tab-1 viewer's resume must not carry it: the cursor
    // knows what changed, not who is looking.
    val r = Renderer.create(tabsInBranchDash)
    val states = Map(
      "alarm.h" -> es("alarm.h", "armed"),
      "sensor.shared" -> es("sensor.shared", "s0"),
      "sensor.a" -> es("sensor.a", "A1"),
      "sensor.b" -> es("sensor.b", "B0"),
      "sensor.z" -> es("sensor.z", "Z0")
    )
    val tab0Node: NodeId = "s_t0__c"
    val log = FragmentLog("w13")
      .set(tab0Node, r.renderNodeById(tab0Node, states).get, 5L)
    val owed = Patches.resume(r, log, states, 1L, Set("then", "t1"), Map.empty)
    assert(
      !owed.exists(_.renderString.contains("A1")),
      clue = owed.map(_.renderString)
    )
  }

  test("a flip re-reveals each client's OWN tab, not the default one") {
    liveWorld(
      tabsInBranchDash,
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.shared" -> es("sensor.shared", "s0"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0"),
        "sensor.z" -> es("sensor.z", "Z0")
      )
    ) { world =>
      for {
        onT0 <- world.connect()
        onT1 <- world.connect("?ui.s_then__c=1")
        open0 <- onT0.drain
        open1 <- onT1.drain
        // First paint already differs per client: the default tab vs the one
        // the second client asked for.
        _ = assert(
          open0.exists(_.renderString.contains("A0")),
          clue = ("tab 0's viewer opens on A", open0)
        )
        _ = assert(
          !open0.exists(_.renderString.contains("B0")),
          clue = open0
        )
        _ = assert(
          open1.exists(_.renderString.contains("B0")),
          clue = ("tab 1's viewer opens on B", open1)
        )
        _ = assert(
          !open1.exists(_.renderString.contains("A0")),
          clue = open1
        )

        // The branch goes away — for both, identically. Nothing tab-shaped is
        // left in the DOM.
        _ <- world.change(es("alarm.h", "disarmed"))
        off0 <- onT0.drain
        off1 <- onT1.drain
        _ = assert(
          domEvents(off0).exists(_._3.exists(_.contains("Z0"))),
          clue = off0
        )
        _ = assert(
          domEvents(off1).exists(_._3.exists(_.contains("Z0"))),
          clue = off1
        )

        // ...and comes back. THE assertion: each client is re-shown ITS tab.
        _ <- world.change(es("alarm.h", "armed"))
        on0 <- onT0.drain
        on1 <- onT1.drain
        _ = assert(
          on0.exists(_.renderString.contains("A0")),
          clue = ("tab 0's viewer must get A back", on0)
        )
        _ = assert(
          !on0.exists(_.renderString.contains("B0")),
          clue = ("...and never tab 1's content", on0)
        )
        _ = assert(
          on1.exists(_.renderString.contains("B0")),
          clue = ("tab 1's viewer must get B back, not the default", on1)
        )
        _ = assert(
          !on1.exists(_.renderString.contains("A0")),
          clue = ("...which is exactly the silent regression", on1)
        )

        // Both are still live inside their own tab afterwards.
        _ <- world.change(es("sensor.a", "A1"))
        a0 <- onT0.drain
        a1 <- onT1.drain
        _ = assert(
          domEvents(a0).exists(_._3.exists(_.contains("A1"))),
          clue = a0
        )
        _ = assertEquals(domEvents(a1), Nil, clue = a1)
        _ <- world.change(es("sensor.b", "B1"))
        b0 <- onT0.drain
        b1 <- onT1.drain
        _ = assertEquals(domEvents(b0), Nil, clue = b0)
        _ = assert(
          domEvents(b1).exists(_._3.exists(_.contains("B1"))),
          clue = b1
        )
      } yield ()
    }
  }

  /** A connection's LIFETIME: what a late arrival is owed, and that two clients
    * on one shared pass both stay live.
    */
  test("a client joining late is caught up, and both stay live after") {
    liveWorld(liveLeafDash, Map("sensor.a" -> es("sensor.a", "cold"))) {
      world =>
        for {
          first <- world.connect()
          _ <- first.drain
          _ <- world.change(es("sensor.a", "warm"))
          early <- first.drain
          _ = assert(
            domEvents(early).exists(_._3.exists(_.contains("warm"))),
            clue = early
          )
          // A SECOND client arrives after that change. It never saw the patch, so
          // its opening block must carry the current value — from the document
          // path, since it connects with no cursor.
          late <- world.connect()
          opening <- late.drain
          _ = assert(
            domEvents(opening).exists(_._3.exists(_.contains("warm"))),
            clue = opening
          )
          // Both are now live on the same shared pass.
          _ <- world.change(es("sensor.a", "hot"))
          e1 <- first.drain
          e2 <- late.drain
          _ = assert(
            domEvents(e1).exists(_._3.exists(_.contains("hot"))),
            clue = e1
          )
          _ = assert(
            domEvents(e2).exists(_._3.exists(_.contains("hot"))),
            clue = e2
          )
        } yield ()
    }
  }

  test("end to end: flipping there and back, one mount overwrite each time") {
    // The shape the running app showed wrong twice. Driving the diff core
    // directly could not see either: the first bug was in the resume path, the
    // second in how a replay was assembled, and both only appear once events
    // have actually travelled down a connection.
    liveClient(
      ifDash(),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { (world, client) =>
      // This fixture's branch content is a single card, so the branch root IS
      // the node — no Row wrapper (the shipped `If` wraps, the fixture does not).
      def branch(sid: String, inner: String) =
        Some(
          s"""<div class="fh-cell" id="s_${sid}__c"><span>$inner</span></div>"""
        )
      for {
        // 1. This client connects with NO cursor — it never loaded a document —
        //    so the honest answer is the whole body, once. (The document case is
        //    the separate first-load test, where the page hands its cursor back
        //    and the opening block carries no elements at all.)
        opening <- client.drain
        _ = assertEquals(
          domEvents(opening).map { case (m, s, _) => (m, s) },
          List(("inner", Some("#dashboard"))),
          clue = opening
        )
        _ = assert(opening.exists(isCursor), clue = opening)

        // 2. A tick inside the ACTIVE branch: one morph of that node alone.
        tick <- world.change(es("sensor.a", "A1")) *> client.drain
        _ = assertEquals(
          domEvents(tick),
          List(
            (
              "outer",
              None,
              Some(
                """<div class="fh-cell" id="s_then__c"><span>A1</span></div>"""
              )
            )
          ),
          clue = tick
        )

        // 3. A tick inside the HIDDEN branch: nothing at all, not even a cursor.
        //    Silence is structural — its ids never enter the selection.
        hidden <- world.change(es("sensor.b", "B1")) *> client.drain
        _ = assertEquals(hidden, Nil, clue = hidden)

        // 4. The flip: ONE overwrite of the host's mount, carrying the branch
        //    rendered at CURRENT state (B1, which this client never saw). The
        //    browser reported three events here — two removals and an append.
        flip <- world.change(es("alarm.h", "disarmed")) *> client.drain
        _ = assertEquals(
          domEvents(flip),
          List(("inner", Some("#c_0_branch"), branch("else", "B1"))),
          clue = flip
        )

        // 5. And back again — symmetric, and the then-branch returns at its
        //    CURRENT value rather than the one it had when it left.
        back <- world.change(es("alarm.h", "armed")) *> client.drain
        _ = assertEquals(
          domEvents(back),
          List(("inner", Some("#c_0_branch"), branch("then", "A1"))),
          clue = back
        )
      } yield ()
    }
  }

  /** '''A first page load must not send the body twice.'''
    *
    * Reported from the running app: loading `pkl-if` produced an
    * `Inner #dashboard` carrying the entire dashboard — every byte of which the
    * document already contained. The document knows what it is showing, so it
    * hands that back on connect (`Restore`) and the stream resumes from it
    * instead of taking the no-cursor branch.
    *
    * Follows the REAL wiring: the SSE url is read out of the rendered page's
    * `data-init`, so a mismatch between what the page advertises and what the
    * route accepts fails here rather than in a browser.
    */
  test("a first load resumes from the document instead of repainting it") {
    val dash = mixedTabsDash
    val initial = Map(
      "sensor.shared" -> es("sensor.shared", "cold"),
      "sensor.a" -> es("sensor.a", "warm")
    )
    (for {
      store <- StateStore.inMemory(initial)
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use { server =>
          val routes = server.routes.orNotFound
          for {
            page <- routes
              .run(Request[IO](Method.GET, uri"/d/dashboard"))
              .flatMap(_.bodyText.compile.string)
            // Take the URL the page itself advertises, unescaped as a browser
            // would parse the attribute.
            sseUrl = page
              .split("""data-init="@get\('""")(1)
              .split("'")(0)
              .replace("&amp;", "&")
            opening <- routes
              .run(
                Request[IO](Method.GET, Uri.unsafeFromString("/" + sseUrl))
              )
              .flatMap(sseFrom(_)(isCursor))
          } yield (page, opening)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (page, opening) =>
        // The document really does carry the dashboard...
        assert(page.contains(">cold<"), clue = page)
        assert(page.contains(">warm<"), clue = page)
        // ...so the stream sends none of it again. Stated as the WHOLE opening
        // block, event by event: the connection id, then the cursor. Anything
        // the server started re-sending shows up here as an extra event rather
        // than slipping past a negative match.
        assertEquals(
          opening.map(_.name),
          List(Signals, Signals),
          clue = opening
        )
        assert(
          opening.head.signals.exists(_.contains(Server.ConnSignal)),
          clue = opening.head
        )
        assert(isCursor(opening(1)), clue = opening(1))
      }
  }

  test("end to end: a leaf tick, then the same value again") {
    liveClient(
      liveLeafDash,
      Map("sensor.a" -> es("sensor.a", "cold"))
    ) { (world, client) =>
      for {
        _ <- client.drain
        // An outer morph: it targets the id inside its own HTML and names no
        // selector — the leaf's whole rendering, cell and all — and the batch
        // carries the cursor it advanced to.
        hot <- world.change(es("sensor.a", "hot")) *> client.drain
        _ = assertEquals(
          domEvents(hot),
          List(
            (
              "outer",
              None,
              Some("""<div class="fh-cell" id="c_0"><span>hot</span></div>""")
            )
          ),
          clue = hot
        )
        _ = assert(hot.exists(isCursor), clue = hot)
        // The diff's whole purpose: a change that renders identically puts
        // NOTHING on the wire — not even a cursor, since only a non-empty batch
        // carries one.
        again <- world.change(es("sensor.a", "hot")) *> client.drain
        _ = assertEquals(again, Nil, clue = again)
      } yield ()
    }
  }
}
