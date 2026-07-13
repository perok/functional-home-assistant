package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.protocol.client.TriggerData
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  DynamicCase,
  LayoutNode,
  Op,
  Predicate,
  SlotSource,
  Surface
}
import fs2.concurrent.{SignallingRef, Topic}
import ha.runtime.definitions.{DeviceId, EntityId}
import io.circe.Json
import org.http4s.*
import org.http4s.implicits.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class ServerSuite extends munit.FunSuite {

  // A minimal tabs dashboard: a `tabs` component (id "c") with two panels baked
  // into it (c_t0 default, c_t1) — the cookie index selects among them.
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

  private def get(cookies: (String, String)*): Request[IO] =
    cookies.foldLeft(Request[IO](Method.GET, uri"/")) { case (r, (n, v)) =>
      r.addCookie(n, v)
    }

  test("uiStateOf keeps fhui_ cookies, drops the prefix, ignores the rest") {
    assertEquals(
      Server.uiStateOf(get("fhui_c" -> "1", "other" -> "x")),
      Map("c" -> "1")
    )
    // raw value, no parsing here
    assertEquals(Server.uiStateOf(get("fhui_c" -> "abc")), Map("c" -> "abc"))
    // no fhui_ cookies -> empty
    assertEquals(Server.uiStateOf(get("other" -> "x")), Map.empty)
    assertEquals(Server.uiStateOf(get()), Map.empty)
  }

  test("cookie round-trip: fhui_<tabsId>=1 opens the index-1 surface") {
    val r = tabsRenderer
    val uiState = Server.uiStateOf(get("fhui_c" -> "1"))
    // The server seeds the open set (and bakes) from this selection.
    assertEquals(r.selectedSurfaces(uiState), Set("c_t1"))
    assert(r.renderBody(Map.empty, uiState).contains("tab_c: 1"))
    assert(
      r.uiStateAnomalies(uiState).isEmpty,
      clue = r.uiStateAnomalies(uiState)
    )
  }

  test(
    "cookie round-trip: a malformed fhui_ value falls back to index 0 + warns"
  ) {
    val r = tabsRenderer
    val uiState = Server.uiStateOf(get("fhui_c" -> "abc"))
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
  private def pageHtml(dash: Dashboard): String =
    (for {
      store <- StateStore.inMemory(Map.empty)
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      server = new Server(
        StubApi,
        store,
        Map(dash.slug -> ref),
        dash.slug,
        sessions,
        Map.empty
      )
      resp <- server.routes.orNotFound
        .run(Request[IO](Method.GET, Uri.unsafeFromString(s"/d/${dash.slug}")))
      body <- resp.body.through(fs2.text.utf8.decode).compile.string
    } yield body).timeout(30.seconds).unsafeRunSync()

  test("page <title> uses the dashboard's authored title when present") {
    assert(
      pageHtml(titleDash("home", Some("My Home")))
        .contains("<title>My Home</title>")
    )
  }

  test("page <title> falls back to the slug when no title is authored") {
    assert(
      pageHtml(titleDash("energy", None)).contains("<title>energy</title>")
    )
  }

  test("page <title> escapes an authored title") {
    assert(
      pageHtml(titleDash("x", Some("A & <B>")))
        .contains("<title>A &amp; &lt;B&gt;</title>")
    )
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
      server = new Server(
        StubApi,
        store,
        Map("home" -> ref),
        "home",
        sessions,
        Map.empty,
        AssetCache.empty,
        system
      )
      routes = server.routes.orNotFound
      get = (p: String) =>
        routes.run(Request[IO](Method.GET, Uri.unsafeFromString(p)))
      dump <- get("/system/pkl/dump.pkl")
      dumpBody <- dump.body.through(fs2.text.utf8.decode).compile.string
      hass <- get("/system/pkl/hass.pkl")
      miss <- get("/system/pkl/nope.pkl")
    } yield (dump.status, dumpBody, hass.status, miss.status))
      .timeout(30.seconds)
      .unsafeRunSync()

    assertEquals(dumpStatus, Status.Ok)
    assertEquals(dumpBody, "kitchen = 1")
    assertEquals(hassStatus, Status.Ok)
    assertEquals(missStatus, Status.NotFound)
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

  /** The Server never touches the HA api on the SSE/patch path; every method
    * fails loudly so an unexpected call surfaces as a test failure.
    */
  private object StubApi extends HomeAssistantApi[IO] {
    private def na: IO[Nothing] =
      IO.raiseError(new NotImplementedError("test stub"))
    private def naR: Resource[IO, Nothing] = Resource.eval(na)
    def configDeviceRegistryList = na
    def configEntityRegistryList = na
    def configEntityRegistryGet(entityId: EntityId) = na
    def manifestList() = na
    def configEntriesGet(type_filter: List[String], domain: Option[String]) =
      na
    def deviceAutomationTriggerList(deviceId: DeviceId) = na
    def deviceAutomationActionList(deviceId: DeviceId) = na
    def deviceAutomationActionCapabilities(action: Json) = na
    def getConfigWS = na
    def getServicesWS = na
    def event(event: Option[String]) = naR
    def trigger(data: TriggerData*) = naR
    def callService(
        domain: String,
        service: String,
        entityId: String,
        serviceData: Json
    ) = na
    def getStates = na
    def getServices = na
    def templateFunc[Body: io.circe.Decoder](template: String) = na
  }

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

  test(
    "shared per-slug pass: two connections both receive a changed fragment rendered ONCE"
  ) {
    val marker = "shared_once_value_xq"
    val count = new AtomicInteger(0)
    val io = for {
      store <- StateStore.inMemory(
        Map("sensor.a" -> EntityState("sensor.a", "initial", Map.empty))
      )
      topic <- Topic[IO, ServerSentEvent]
      renderer = new CountingRenderer(liveLeafDash, count)
      ref <- SignallingRef[IO].of(renderer: Renderer)
      sessions <- Sessions.create
      server = new Server(
        StubApi,
        store,
        Map("dashboard" -> ref),
        "dashboard",
        sessions,
        Map("dashboard" -> topic)
      )
      // The lifecycle owner (ServerApp via Server.resource) runs the shared
      // publishers; here the test does.
      publisher <- server.sharedPatchPublishers.compile.drain.start
      connect = server.routes.orNotFound
        .run(Request[IO](Method.GET, uri"/sse/dashboard/dashboard/patch"))
      resp1 <- connect
      resp2 <- connect
      awaitMarker = (resp: Response[IO]) =>
        resp.body
          .through(fs2.text.utf8.decode)
          .scan("")(_ + _)
          .exists(_.contains(marker))
          .compile
          .drain
      seen1 <- awaitMarker(resp1).start
      seen2 <- awaitMarker(resp2).start
      // Deterministic readiness (topics deliver only to already-subscribed
      // consumers): both connections on the slug's shared topic, and the
      // publisher + both per-session change loops on the store's change topic.
      _ <- topic.subscribers.filter(_ >= 2).head.compile.drain
      _ <- store.changeSubscribers.filter(_ >= 3).head.compile.drain
      _ <- store.update(EntityState("sensor.a", marker, Map.empty))
      // (a) both SSE streams receive the changed fragment...
      _ <- seen1.joinWithNever
      _ <- seen2.joinWithNever
      _ <- publisher.cancel
    } yield count.get()
    // ...and (b) it was rendered once, by the shared pass (the per-session
    // loops render only bake owners / open surfaces — none here).
    assertEquals(io.timeout(30.seconds).unsafeRunSync(), 1)
  }

  // ---------------------------------------------------------------------------
  // Per-entity dynamic-group patches (Tier 1 in-place + Tier 2 add/remove)
  // ---------------------------------------------------------------------------

  private def on(id: String): EntityState = EntityState(id, "on", Map.empty)
  private def off(id: String): EntityState = EntityState(id, "off", Map.empty)

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
  private def runShared(
      dash: Dashboard,
      after: Map[String, EntityState],
      change: StateChange,
      seedCache: Map[String, String] = Map.empty
  ): (List[String], Map[String, String]) =
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      server = new Server(
        StubApi,
        store,
        Map("dashboard" -> ref),
        "dashboard",
        sessions,
        Map.empty
      )
      renderer <- ref.get
      cache <- Ref[IO].of(seedCache)
      patches <- server.sharedPatches(renderer, cache, change)
      finalCache <- cache.get
    } yield (patches.map(_.renderString), finalCache))
      .timeout(30.seconds)
      .unsafeRunSync()

  test("dynamic in-place tick patches ONE child, not the whole group") {
    val after = Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))
    // light.b ticks (a fresh EntityState, same "on" state) -> InPlace member.
    val change = StateChange("light.b", Some(on("light.b")), on("light.b"))
    val (patches, _) = runShared(dynDash, after, change)
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
    val (patches, cache) =
      runShared(dynDash, after, change, seedCache = Map("c" -> "<stale>"))
    assertEquals(patches.size, 1, clue = patches)
    val p = patches.head
    assert(p.contains("mode before"), clue = p)
    assert(p.contains("selector #c_light_c"), clue = p) // first member after b
    assert(
      p.contains("""elements <div class="fh-cell" id="c_light_b">"""),
      clue = p
    )
    // the new child is cached; the group-level entry is invalidated.
    assert(cache.contains("c_light_b"), clue = cache)
    assert(!cache.contains("c"), clue = cache)
  }

  test("dynamic add of the last-sorting entity APPENDS into the group") {
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> on("light.b"),
      "light.c" -> on("light.c"),
      "light.z" -> on("light.z")
    )
    val change = StateChange("light.z", Some(off("light.z")), on("light.z"))
    val (patches, _) =
      runShared(dynDash, after, change, seedCache = Map("c" -> "<stale>"))
    assertEquals(patches.size, 1, clue = patches)
    val p = patches.head
    assert(p.contains("mode append"), clue = p)
    assert(p.contains("selector #c"), clue = p)
    assert(
      p.contains("""elements <div class="fh-cell" id="c_light_z">"""),
      clue = p
    )
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
    val (patches, cache) = runShared(
      dynDash,
      after,
      change,
      seedCache = Map("c" -> "<stale>", "c_light_b" -> "<old>")
    )
    assertEquals(patches.size, 1, clue = patches)
    val p = patches.head
    assert(p.contains("mode remove"), clue = p)
    assert(p.contains("selector #c_light_b"), clue = p)
    // remove carries no HTML payload (the event name still says "…elements").
    assert(!p.contains("data: elements"), clue = p)
    assert(!cache.contains("c_light_b"), clue = cache)
  }

  test("heuristic: removing 1 of 2 members repaints the whole group + prunes") {
    // shown 2, churn 1 -> 1 < 0.5*2 is false -> whole-group repaint fallback.
    val after = Map("light.a" -> on("light.a"), "light.b" -> off("light.b"))
    val change = StateChange("light.b", Some(on("light.b")), off("light.b"))
    val (patches, cache) = runShared(
      dynDash,
      after,
      change,
      seedCache =
        Map("c" -> "<stale>", "c_light_a" -> "<a>", "c_light_b" -> "<b>")
    )
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
    val (patches, cache) = runShared(dynDash, after, change)
    assertEquals(patches.size, 1, clue = patches)
    assert(
      patches.head.contains(
        """elements <div class="fh-cell fh-group" id="c">"""
      ),
      clue = patches
    )
    assert(cache.contains("c"), clue = cache)
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
    val (patches, _) = (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(Renderer.create(surfaceDynDash))
      sessions <- Sessions.create
      server = new Server(
        StubApi,
        store,
        Map("dashboard" -> ref),
        "dashboard",
        sessions,
        Map.empty
      )
      session <- Session.create("dashboard")
      _ <- session.open.set(Set("det"))
      patches <- server.changedPatches(session, change, Map.empty)
      cache <- session.lastRendered.get
    } yield (patches.map(_.renderString), cache))
      .timeout(30.seconds)
      .unsafeRunSync()
    assertEquals(patches.size, 1, clue = patches)
    // one child morph, surface-namespaced id — not the whole surface group.
    assert(
      patches.head.contains(
        """elements <div class="fh-cell" id="s_det__c_light_b">"""
      ),
      clue = patches
    )
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

  private def es(id: String, state: String): EntityState =
    EntityState(id, state, Map.empty)

  /** Drives the SHARED per-slug pass over an EVOLVING store: each [[step]]
    * applies one entity update (deriving the StateChange exactly like the WS
    * ingest does) and returns the SSE patches emitted for it, diffing against
    * the cache ACCUMULATED across steps — what multi-step contracts (flip then
    * re-reveal) need, unlike the single-shot [[runShared]].
    */
  private class SharedHarness(
      dash: Dashboard,
      initial: Map[String, EntityState]
  ) {
    private val (store, server, renderer, cache) = (for {
      store <- StateStore.inMemory(initial)
      ref <- SignallingRef[IO].of(Renderer.create(dash))
      sessions <- Sessions.create
      server = new Server(
        StubApi,
        store,
        Map("dashboard" -> ref),
        "dashboard",
        sessions,
        Map.empty
      )
      renderer <- ref.get
      cache <- Ref[IO].of(Map.empty[String, String])
    } yield (store, server, renderer, cache))
      .timeout(30.seconds)
      .unsafeRunSync()

    def step(next: EntityState): List[String] =
      (for {
        prev <- store.snapshot.map(_.get(next.entityId))
        _ <- store.update(next)
        patches <- server.sharedPatches(
          renderer,
          cache,
          StateChange(next.entityId, prev, next)
        )
      } yield patches.map(_.renderString))
        .timeout(30.seconds)
        .unsafeRunSync()

    def cacheNow: Map[String, String] =
      cache.get.timeout(30.seconds).unsafeRunSync()
  }

  test("state surfaces: churn in the INACTIVE branch emits ZERO patches") {
    val h = new SharedHarness(
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
    assertEquals(h.step(es("sensor.b", "B1")), Nil)
    // An entity no branch binds and no condition reads: nothing at all (the
    // O(1) shortcut path — no member condition match flipped for it).
    assertEquals(h.step(es("sensor.z", "Z1")), Nil)
    // The ACTIVE branch's entity, by contrast, patches its surface-scoped node.
    val live = h.step(es("sensor.a", "A1"))
    assertEquals(live.size, 1, clue = live)
    assert(live.head.contains("""id="s_then__c""""), clue = live)
    assert(live.head.contains("A1"), clue = live)
  }

  test(
    "state flip: ONE host morph with the new branch at CURRENT state; members pruned"
  ) {
    val h = new SharedHarness(
      ifDash(),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    )
    // Establish the active branch in the shared cache...
    assertEquals(h.step(es("sensor.a", "A1")).size, 1)
    // ...and churn the hidden branch (never rendered, never patched).
    assertEquals(h.step(es("sensor.b", "B1")), Nil)
    // The flip: exactly ONE patch — the host morph — whose HTML is the else
    // branch rendered against CURRENT state (B1, which no client ever saw).
    val flip = h.step(es("alarm.h", "disarmed"))
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
    assert(!h.cacheNow.keys.exists(_.startsWith("s_then__")), clue = h.cacheNow)
    assert(!h.cacheNow.keys.exists(_.startsWith("s_else__")), clue = h.cacheNow)
    assert(h.cacheNow.contains("c_0"), clue = h.cacheNow)
  }

  test(
    "flip prune: a re-revealed child diffs cleanly (no stale-cache suppression)"
  ) {
    val h = new SharedHarness(
      ifDash(),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "boot"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    )
    // 1. Cache the then-branch child at "on".
    assertEquals(h.step(es("sensor.a", "on")).size, 1)
    // 2. Flip away (prunes s_then__*), 3. churn the hidden branch to "off"
    // (silent — the stale-entry trap this test springs), 4. flip back (host
    // morph shows "off" from current state).
    assertEquals(h.step(es("alarm.h", "disarmed")).size, 1)
    assertEquals(h.step(es("sensor.a", "off")), Nil)
    val back = h.step(es("alarm.h", "armed"))
    assertEquals(back.size, 1, clue = back)
    assert(back.head.contains("off"), clue = back)
    // 5. The re-revealed child returns to "on" — HTML byte-identical to the
    // step-1 cache entry. Without the flip prune this would be suppressed as
    // "unchanged" while the DOM (showing "off") has moved on.
    val reveal = h.step(es("sensor.a", "on"))
    assertEquals(reveal.size, 1, clue = reveal)
    assert(reveal.head.contains("""id="s_then__c""""), clue = reveal)
    assert(reveal.head.contains("on"), clue = reveal)
  }

  test("a dynamic group inside an INACTIVE branch stays silent") {
    val dyn = LayoutNode.Dynamic(
      query = Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
      cases = List(
        DynamicCase(always, "dot", slots = Map("state" -> SlotSource()))
      )
    )
    val h = new SharedHarness(
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
    val tick = h.step(es("light.x", "on2"))
    // "on2" fails the query -> a membership change (remove) for the group.
    assert(tick.nonEmpty, clue = tick)
    assert(tick.forall(_.contains("s_then__c")), clue = tick)
    // Flip to else: one host morph...
    assertEquals(h.step(es("alarm.h", "disarmed")).size, 1)
    // ...and now the group is in a hidden branch: query-affecting churn that
    // would previously re-render it emits NOTHING.
    assertEquals(h.step(es("light.y", "off")), Nil)
    assertEquals(h.step(es("light.y", "on")), Nil)
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
    val h = new SharedHarness(
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
    val innerFlip = h.step(es("mode.h", "day"))
    assertEquals(innerFlip.size, 1, clue = innerFlip)
    assert(
      innerFlip.head.contains("""<div id="s_then__c_0">"""),
      clue = innerFlip
    )
    assert(innerFlip.head.contains("""id="s_in_else__c""""), clue = innerFlip)
    // Flip the OUTER group away (one host morph of c_0)...
    assertEquals(h.step(es("alarm.h", "disarmed")).size, 1)
    // ...then the inner group's condition flips inside the hidden branch:
    // unreachable DOM, zero patches (the active-set recursion never descends
    // into an unselected member).
    assertEquals(h.step(es("mode.h", "night")), Nil)
    // Liveness inside the hidden branch's active member is silent too.
    assertEquals(h.step(es("sensor.y", "Y1")), Nil)
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
    val (sessionPatches, sharedPatches) = (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(Renderer.create(d))
      sessions <- Sessions.create
      server = new Server(
        StubApi,
        store,
        Map("dashboard" -> ref),
        "dashboard",
        sessions,
        Map.empty
      )
      renderer <- ref.get
      session <- Session.create("dashboard")
      _ <- session.open.set(Set("det"))
      perSession <- server.changedPatches(session, change, Map.empty)
      cache <- Ref[IO].of(Map.empty[String, String])
      shared <- server.sharedPatches(renderer, cache, change)
    } yield (perSession.map(_.renderString), shared.map(_.renderString)))
      .timeout(30.seconds)
      .unsafeRunSync()
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
    // The shared pass emits nothing for it — popup containment is per-session.
    assertEquals(sharedPatches, Nil)
  }
}
