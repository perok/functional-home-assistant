package fh.view.runtime

import cats.effect.IO
import org.http4s.{Method, Request, Uri}

/** Where a request says its client is: the read that decides resume vs repaint
  * on every reconnect.
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
    // A fresh document has no store yet; that is what the params are for.
    assertEquals(Server.cursorAnomaly(req), None)
  }

  test(
    "a signal store with a PARTIAL cursor is reported, not silently ignored"
  ) {
    // A present store missing one field read like a first connect, so the
    // resume fell back to params frozen at render and re-derived the page on
    // every reconnect: correct output, at a cost nothing revealed.
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
    // Falling back is the safe direction; the warning is what makes it visible.
    assertEquals(
      Server.cursorOf(req),
      Some(Server.Cursor("hq", "sq", "Lq", 3L))
    )
  }

  test("an EMPTY store is a first connect, not a missing cursor") {
    // Datastar sets `datastar` on every GET, so a first connect arrives as
    // `{}`: `data-init` fires before descendants' `data-signals` merge. Reading
    // "the param is here" as a reconnect made every page load an anomaly.
    val req =
      get(params + "&datastar=" + java.net.URLEncoder.encode("{}", "UTF-8"))
    assertEquals(Server.cursorAnomaly(req), None)
    assertEquals(Server.hasSignals(req), false)
    assertEquals(
      Server.cursorOf(req),
      Some(Server.Cursor("hq", "sq", "Lq", 3L))
    )
  }

  /** Wrong here shows on no wire: a document resumed at its own version
    * re-renders nodes its `holds` then suppress.
    */
  test("a document resumes after its version, a reconnect at it") {
    val c = Server.Cursor("h", "s", "L", 7L)
    val firstConnect =
      get(params + "&datastar=" + java.net.URLEncoder.encode("{}", "UTF-8"))
    assertEquals(Server.resumeFrom(get(params), c), 8L)
    assertEquals(Server.resumeFrom(firstConnect, c), 8L)
    assertEquals(Server.resumeFrom(signals(whole), c), 7L)
  }

  test("a store carrying other signals but no cursor is the same case") {
    // A mis-specified client `filterSignals`: `conn` and ui state arrive, the
    // cursor does not.
    val req = signals(s"""{"${Server.ConnSignal}":"c1","ui_c_0":"1"}""")
    assert(Server.cursorAnomaly(req).isDefined)
    assertEquals(Server.connOf(req), Some("c1"))
  }

  test("the SSE include actually names everything a reconnect must carry") {
    // The include and the default exclude are ANDed, so this regex is all a
    // reconnect tells the server, and a wrong one degrades resume silently.
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
    // Per-connection client state stays local.
    List("_val_c_3", Server.ReloadSignal, "_sse").foreach(n =>
      assert(include.findFirstIn(n).isEmpty, clue = (n, Server.SseInclude))
    )
  }

  test("a reconnect carries a committed node variable, not its pending ask") {
    // A declarer id can hold `__` itself (a surface's), so the shapes are the
    // server's own spelling rather than hand-written ones.
    val include = Server.SseInclude.r
    val declarer: fh.view.model.NodeId =
      fh.view.model.NodeId.derived("s_detail__c_0")
    val committed = Server.varSignal(declarer, "window")
    assert(include.findFirstIn(committed).isDefined, clue = committed)
    val pending = committed + "__pending"
    assert(include.findFirstIn(pending).isEmpty, clue = pending)
  }
}
