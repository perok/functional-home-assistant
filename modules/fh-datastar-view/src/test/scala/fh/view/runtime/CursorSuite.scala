package fh.view.runtime

import cats.effect.IO
import org.http4s.{Method, Request, Uri}

/** What a request says about where its client is — the read that decides resume
  * vs repaint on every reconnect.
  *
  * Its own suite rather than another few hundred lines in `ServerSuite`, which
  * is 4 600 lines and has started needing more than 4 GB to compile.
  */
class CursorSuite extends munit.FunSuite {

  private def get(query: String): Request[IO] =
    Request[IO](
      Method.GET,
      Uri.unsafeFromString(s"/sse/dashboard/d/patch$query")
    )

  private def signals(json: String): Request[IO] =
    get("?datastar=" + java.net.URLEncoder.encode(json, "UTF-8"))

  private def store(fields: String): String =
    s"""{"${Server.CursorSignal}":{$fields}}"""

  private val whole = store(
    s""""${Server.HeadHashSignal}":"h","${Server.StyleHashSignal}":"s",""" +
      s""""${Server.LogIdSignal}":"L","${Server.StoreVersionSignal}":7"""
  )

  private val params =
    s"?${Server.cursorParam(Server.HeadHashSignal)}=hq" +
      s"&${Server.cursorParam(Server.StyleHashSignal)}=sq" +
      s"&${Server.cursorParam(Server.LogIdSignal)}=Lq" +
      s"&${Server.cursorParam(Server.StoreVersionSignal)}=3"

  test("a complete signal cursor is read, and beats the document's params") {
    val req =
      get(params + "&datastar=" + java.net.URLEncoder.encode(whole, "UTF-8"))
    assertEquals(
      Server.cursorOf(req),
      Some(Server.Cursor("h", "s", "L", 7L))
    )
    assertEquals(Server.cursorAnomaly(req), None)
  }

  test(
    "no signal store at all is a FIRST connect: the params are the carrier"
  ) {
    val req = get(params)
    assertEquals(
      Server.cursorOf(req),
      Some(Server.Cursor("hq", "sq", "Lq", 3L))
    )
    // Not an anomaly. A freshly-loaded document has no store yet — that is what
    // the params exist for.
    assertEquals(Server.cursorAnomaly(req), None)
  }

  test(
    "a signal store with a PARTIAL cursor is reported, not silently ignored"
  ) {
    // The failure the four-independent-`toOption` reads could not distinguish:
    // a store that is present and missing one field reads exactly like a first
    // connect, so the resume falls back to params frozen at page render and
    // re-derives the whole page on every reconnect — correct output, forever,
    // at a cost nothing reveals.
    val partial = store(
      s""""${Server.HeadHashSignal}":"h","${Server.StyleHashSignal}":"s",""" +
        s""""${Server.LogIdSignal}":"L""""
    )
    val req =
      get(params + "&datastar=" + java.net.URLEncoder.encode(partial, "UTF-8"))
    assert(
      Server.cursorAnomaly(req).isDefined,
      clue = Server.cursorAnomaly(req)
    )
    // It still SERVES: falling back is the safe direction, and the warning is
    // what makes it visible rather than the only symptom being a slow instance.
    assertEquals(
      Server.cursorOf(req),
      Some(Server.Cursor("hq", "sq", "Lq", 3L))
    )
  }

  test("a store carrying other signals but no cursor is the same case") {
    // What a mis-specified client-side `filterSignals` looks like from here:
    // the store arrives, `conn` and the ui state are in it, the cursor is not.
    val req = signals(s"""{"${Server.ConnSignal}":"c1","ui_c_0":"1"}""")
    assert(Server.cursorAnomaly(req).isDefined)
    assertEquals(Server.connOf(req), Some("c1"))
  }

  test("the SSE include actually names everything a reconnect must carry") {
    // The include and the default exclude are ANDed, so this regex is the WHOLE
    // of what a reconnect tells the server — and getting it wrong degrades the
    // resume without failing anything. Pinned here rather than trusted.
    val include = Server.SseInclude.r
    List(
      Server.cursorParam(Server.HeadHashSignal),
      Server.cursorParam(Server.StyleHashSignal),
      Server.cursorParam(Server.LogIdSignal),
      Server.cursorParam(Server.StoreVersionSignal),
      Server.ConnSignal,
      Server.UiSignalPrefix + "c_0"
    ).foreach(n =>
      assert(include.findFirstIn(n).isDefined, clue = (n, Server.SseInclude))
    )
    // ...and nothing else rides along: per-connection client state stays local.
    List("_val_c_3", Server.ReloadSignal, "_sse").foreach(n =>
      assert(include.findFirstIn(n).isEmpty, clue = (n, Server.SseInclude))
    )
  }
}
