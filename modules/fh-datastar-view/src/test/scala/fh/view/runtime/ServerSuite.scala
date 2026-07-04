package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.protocol.client.TriggerData
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import fh.view.model.{CardDef, Dashboard, LayoutNode, SlotSource, Surface}
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
            defaultOpen = true
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
}
