package fh.view.runtime

import cats.effect.IO
import fh.view.model.{CardDef, Dashboard, LayoutNode, SlotSource, Surface}
import io.circe.Json
import org.http4s.*
import org.http4s.implicits.*

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
            LayoutNode.Component("btn", Map("label" -> SlotSource(literal = Some("A")))),
            LayoutNode.Component("btn", Map("label" -> SlotSource(literal = Some("B"))))
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
    assert(r.uiStateAnomalies(uiState).isEmpty, clue = r.uiStateAnomalies(uiState))
  }

  test("cookie round-trip: a malformed fhui_ value falls back to index 0 + warns") {
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

    val rm = Datastar.removeElement("#s_x").renderString
    assert(rm.contains("data: selector #s_x"), clue = rm)
    assert(rm.contains("data: mode remove"), clue = rm)
    assert(!rm.contains("\nmode remove"), clue = rm)
  }
}
