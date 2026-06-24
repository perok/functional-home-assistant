package fh.view.runtime

import io.circe.Json

class ServerSuite extends munit.FunSuite {

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
