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
}
