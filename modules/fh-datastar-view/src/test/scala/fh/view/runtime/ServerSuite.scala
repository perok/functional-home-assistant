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
  Op,
  Predicate,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.DashboardBuilders.st
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
      "tabs" -> CardDef(
        """<div class="tabs">{{#children}}{{{html}}}{{/children}}<div id="{{id}}_panel" data-signals="{ tab_{{id}}: {{bakeIndex}} }">{{{panel}}}</div></div>"""
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

  test("the page shell seeds the popup signal from the URL, or empty") {
    // A refresh with ?popup=<id> must re-open the dialog: the seeded signal
    // reaches the SSE connect, which renders it back into its host. An unknown
    // id is dropped rather than seeded.
    val dash = titleDash("home", None).copy(
      surfaces = Map("det" -> Surface(LayoutNode.Component("col")))
    )
    for {
      seeded <- pageHtml(dash, "?popup=det")
      unknown <- pageHtml(dash, "?popup=nope")
      none <- pageHtml(dash)
    } yield {
      assert(seeded.contains(s"""${Server.PopupSignal}: \'det\'"""), seeded)
      assert(unknown.contains(s"""${Server.PopupSignal}: \'\'"""), unknown)
      assert(none.contains(s"""${Server.PopupSignal}: \'\'"""), none)
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
      restored <- pageHtml(dash, "?ui.c=1&popup=det")
      plain <- pageHtml(dash)
    } yield {
      assert(
        restored.contains(
          """data-init="@get('sse/dashboard/home/patch?ui.c=1&amp;popup=det', {retry:'always'})""""
        ),
        restored
      )
      // Nothing to restore ⇒ no query at all, not a bare `?`.
      assert(
        plain.contains(
          """data-init="@get('sse/dashboard/home/patch', {retry:'always'})""""
        ),
        plain
      )
      // `always` is load-bearing, not decoration: the default retry mode
      // reconnects a DROPPED stream but treats one the server ended as
      // finished — which is how the server closes a stalled connection.
      assert(plain.contains("{retry:'always'}"), plain)
    }
  }

  test("popupOf: the signal wins when present, the URL only seeds a connect") {
    // A first connect has the param and no signal.
    assertEquals(Server.popupOf(get(Server.PopupSignal -> "det")), Some("det"))
    // A reconnect after the user closed it carries `popup: ""` alongside the
    // page's now-stale param: the signal is authoritative, so the dialog stays
    // closed rather than resurrecting on every retry.
    assertEquals(
      Server.popupOf(
        Request[IO](
          Method.GET,
          uri"/"
            .withQueryParam(Server.PopupSignal, "det")
            .withQueryParam("datastar", s"""{"${Server.PopupSignal}":""}""")
        )
      ),
      None
    )
    assertEquals(Server.popupOf(get()), None)
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
        id: String,
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
        LayoutNode.Component("card", slots = Map("state" -> SlotSource(Some(e))))
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
            _ <- store.changeSubscribers.filter(_ >= 2).head.compile.drain
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
                    stop.tryGet.flatMap(s => IO.sleep(1.minute).whenA(s.isDefined))
                  )
                  .compile
                  .drain
                reads.background.surround {
                  for {
                    // The shared publisher plus this connection.
                    _ <- store.changeSubscribers
                      .filter(_ >= 2)
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
            // consumers): both connections on the shared topic, and the
            // publisher + both per-session change loops on the store's changes.
            _ <- server.sharedSubscribers.filter(_ >= 2).head.compile.drain
            _ <- store.changeSubscribers.filter(_ >= 3).head.compile.drain
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
  // These tests assert on the cached HTML, not on the fragment versions that
  // ride with it (docs/plan-sse-resume.md), so the log is projected back to the
  // plain node -> html map they were written against.
  private def seedLog(seed: Map[String, String]): FragmentLog =
    FragmentLog("test", seed.view.mapValues(Fragment(_, 0L)).toMap)

  private def htmlOf(log: FragmentLog): Map[String, String] =
    log.fragments.view.mapValues(_.html).toMap

  /** The ELEMENT patches of a shared batch. Every non-empty batch also carries
    * the resume cursor as a `patch-signals` event (docs/plan-sse-resume.md);
    * these contracts are about what the DOM receives, and one dedicated test
    * below covers the cursor itself.
    */
  private def elementPatches(batch: List[ServerSentEvent]): List[String] =
    batch.map(_.renderString).filterNot(_.contains("datastar-patch-signals"))

  private def runShared(
      dash: Dashboard,
      after: Map[String, EntityState],
      change: StateChange,
      seedCache: Map[String, String] = Map.empty
  ): IO[(List[String], Map[String, String])] =
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
            patches <- server.sharedPatches(renderer, cache, change)
            finalCache <- cache.get.map(htmlOf)
          } yield (elementPatches(patches), finalCache)
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

  test("dynamic add: per-entity insert BEFORE the DOM successor") {
    // a,c,d already on; b turns on -> Added, churn 1 of shown 3 -> per-entity.
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> on("light.b"),
      "light.c" -> on("light.c"),
      "light.d" -> on("light.d")
    )
    val change = StateChange("light.b", Some(off("light.b")), on("light.b"))
    // Group already established in the cache so the per-entity path engages.
    runShared(dynDash, after, change, seedCache = Map("c" -> "<stale>")).map {
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
        // the new child is cached; the group-level entry is invalidated.
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
    runShared(dynDash, after, change, seedCache = Map("c" -> "<stale>")).map {
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

  test("heuristic: removing 1 of 2 members repaints the whole group + prunes") {
    // shown 2, churn 1 -> 1 < 0.5*2 is false -> whole-group repaint fallback.
    val after = Map("light.a" -> on("light.a"), "light.b" -> off("light.b"))
    val change = StateChange("light.b", Some(on("light.b")), off("light.b"))
    runShared(
      dynDash,
      after,
      change,
      seedCache =
        Map("c" -> "<stale>", "c_light_a" -> "<a>", "c_light_b" -> "<b>")
    ).map { case (patches, cache) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      // one outer morph of the GROUP (not a remove/insert), only light.a remains.
      assert(
        p.contains("""elements <div class="fh-cell fh-group" id="c">"""),
        clue = p
      )
      assert(p.contains("""id="c_light_a""""), clue = p)
      assert(!p.contains("mode remove"), clue = p)
      // child cache entries are pruned; the group entry is refreshed.
      assert(!cache.contains("c_light_a"), clue = cache)
      assert(!cache.contains("c_light_b"), clue = cache)
      assert(cache.get("c").exists(_.contains("id=\"c\"")), clue = cache)
    }
  }

  test("membership change on a not-yet-cached group falls back to repaint") {
    // Same 1-of-4 remove that would be per-entity — but with an EMPTY cache the
    // group isn't established, so we repaint to establish a known base.
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> off("light.b"),
      "light.c" -> on("light.c"),
      "light.d" -> on("light.d")
    )
    val change = StateChange("light.b", Some(on("light.b")), off("light.b"))
    runShared(dynDash, after, change).map { case (patches, cache) =>
      assertEquals(patches.size, 1, clue = patches)
      assert(
        patches.head.contains(
          """elements <div class="fh-cell fh-group" id="c">"""
        ),
        clue = patches
      )
      assert(cache.contains("c"), clue = cache)
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
            ps <- server.changedPatches(session, change, Map.empty)
          } yield ps
        }
    } yield patches.map(_.renderString))
      .timeout(30.seconds)
      .map { patches =>
        assertEquals(patches.size, 1, clue = patches)
        // one child morph, surface-namespaced id — not the whole surface group.
        assert(
          patches.head.contains(
            """elements <div class="fh-cell" id="s_det__c_light_b">"""
          ),
          clue = patches
        )
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
    "ifhost" -> CardDef("""<div id="{{id}}">{{{branch}}}</div>"""),
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
          renderer,
          cache,
          StateChange(next.entityId, prev, next)
        )
      } yield patches).timeout(30.seconds)

    def step(next: EntityState): IO[List[String]] =
      sharedBatch(next).map(elementPatches)

    /** Everything a batch emits, cursor signal included. */
    def stepRaw(next: EntityState): IO[List[String]] =
      sharedBatch(next).map(_.map(_.renderString))

    def cacheNow: IO[Map[String, String]] =
      cache.get.map(htmlOf).timeout(30.seconds)

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
      ) ++ popup.map(p => s""""${Server.PopupSignal}":"$p"""")
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
        topic <- Topic[IO, (String, ServerSentEvent)]
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
    "state flip: ONE host morph with the new branch at CURRENT state; members pruned"
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
      // The flip: exactly ONE patch — the host morph — whose HTML is the else
      // branch rendered against CURRENT state (B1, which no client ever saw).
      flip <- h.step(es("alarm.h", "disarmed"))
      cache <- h.cacheNow
    } yield {
      assertEquals(flip.size, 1, clue = flip)
      val p = flip.head
      // The patch's ROOT element must be the id'd host itself: the default
      // `outer` morph (no selector) targets the top-level element's id, so a
      // wrapped/rootless fragment would be silently dropped by the client.
      val root = p.linesIterator
        .find(_.startsWith("data: elements "))
        .map(_.stripPrefix("data: elements "))
      assert(root.exists(_.startsWith("""<div id="c_0">""")), clue = p)
      assert(p.contains("""id="s_else__c""""), clue = p)
      assert(p.contains("B1"), clue = p)
      assert(!p.contains("A1"), clue = p)
      // The prune contract: both members' surface-scoped entries are gone; the
      // host's fresh HTML is the only record of the group.
      assert(!cache.keys.exists(_.startsWith("s_then__")), clue = cache)
      assert(!cache.keys.exists(_.startsWith("s_else__")), clue = cache)
      assert(cache.contains("c_0"), clue = cache)
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
      // (silent — the stale-entry trap this test springs), 4. flip back (host
      // morph shows "off" from current state).
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
      // Flip to else: one host morph...
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
      // Outer active: the inner flip morphs ONLY the inner host (recursion into
      // the active member's index found it), rendered with its else branch.
      innerFlip <- h.step(es("mode.h", "day"))
      _ = assertEquals(innerFlip.size, 1, clue = innerFlip)
      _ = assert(
        innerFlip.head.contains("""<div id="s_then__c_0">"""),
        clue = innerFlip
      )
      _ = assert(
        innerFlip.head.contains("""id="s_in_else__c""""),
        clue = innerFlip
      )
      // Flip the OUTER group away (one host morph of c_0)...
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      // ...then the inner group's condition flips inside the hidden branch:
      // unreachable DOM, zero patches (the active-set recursion never descends
      // into an unselected member).
      _ <- h.step(es("mode.h", "night")).assertEquals(Nil)
      // Liveness inside the hidden branch's active member is silent too.
      _ <- h.step(es("sensor.y", "Y1")).assertEquals(Nil)
    } yield ()
  }

  test("a state group inside a user-opened popup rides the PER-SESSION pass") {
    // The If roots inside popup "det" (owner s_det__c_0): visibility is the
    // session's open set, so its flips/liveness belong to changedPatches; the
    // shared pass never reaches it (its owner is not main-rooted).
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
            perSession <- server.changedPatches(session, change, Map.empty)
            cache <- Ref[IO].of(seedLog(Map.empty))
            shared <- server.sharedPatches(renderer, cache, change)
          } yield (perSession, shared)
        }
    } yield (out._1.map(_.renderString), out._2.map(_.renderString)))
      .timeout(30.seconds)
      .map { case (sessionPatches, sharedPatches) =>
        // The session with the popup open gets exactly the inner host flip morph.
        assertEquals(sessionPatches.size, 1, clue = sessionPatches)
        assert(
          sessionPatches.head.contains("""<div id="s_det__c_0">"""),
          clue = sessionPatches
        )
        assert(
          sessionPatches.head.contains("""id="s_d_else__c""""),
          clue = sessionPatches
        )
        // The shared pass emits nothing — popup containment is per-session.
        assertEquals(sharedPatches, Nil)
      }
  }

  // ---------------------------------------------------------------------------
  // Resume on reconnect (docs/plan-sse-resume.md, steps 3-4)
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

  test(
    "the cursor signal rides every non-empty shared batch, quoting its log"
  ) {
    for {
      h <- SharedHarness.create(
        liveLeafDash,
        Map("sensor.a" -> es("sensor.a", "cold"))
      )
      raw <- h.stepRaw(es("sensor.a", "hot"))
      logId <- h.logId
      // An entity no card binds: nothing rendered, so no cursor either — the
      // client's existing cursor still names its DOM.
      quiet <- h.stepRaw(es("sensor.unwatched", "x"))
    } yield {
      assertEquals(raw.size, 2, clue = raw)
      assert(raw.last.contains(s""""${Server.LogIdSignal}":"$logId""""), raw)
      assert(raw.last.contains(s""""${Server.StoreVersionSignal}":1"""), raw)
      assert(raw.last.contains(h.headHash), clue = raw)
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
    // The saving this plan exists for: one small patch, not a group morph.
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
      "tabs" -> CardDef("""<div class="tabs">{{{panel}}}</div>""")
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

  test("a resume paints the per-session subtrees fresh (they have no log)") {
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
      // Without the fresh paint this value would never reach the reconnected
      // DOM — the silent staleness the whole resume path risks.
      assert(opening.contains(">new<"), clue = opening)
      assert(opening.contains("""<div class="tabs">"""), clue = opening)
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
      assert(restored.contains(hostSelector), clue = restored)
      assert(restored.contains(">B1<"), clue = restored)
      assert(!restored.contains(hostReset), clue = restored)
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
        // One signal per host swap, always naming what was rendered there.
        assertEquals(
          emitted.filter(_.contains("datastar-patch-signals")),
          List("det", "other", "").map(id =>
            Server.popupSignal(Option(id).filter(_.nonEmpty)).renderString
          )
        )
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
}
