package fh.view.testkit

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

/** What the oracle's page adds to the bundle it runs: what counts as a miss,
  * the URL its mirrors leave, the request a reconnect makes, and a press's
  * round trip.
  */
class FakePageSuite extends munit.CatsEffectSuite {

  private val js = ResourceSuiteLocalFixture("js", FakePage.runtime)

  override def munitFixtures = List(js)

  private def page(body: String, init: String = ""): FakePage =
    val onLoad = if (init.isEmpty) "" else s""" data-init="$init""""
    js().load(
      s"""<!doctype html><html><head><base href="/"></head><body$onLoad>
         |<main id="dashboard">$body</main><div id="popups"></div></body></html>""".stripMargin,
      "http://fake.test/d/s"
    )

  private val stream =
    """@get('sse/dashboard/s/patch?conn=k1', {retry:'always',filterSignals:{include:'^(conn$|_cursor\.|_var_(?!.*__pending$))',exclude:'(?!)'}})"""

  test("a patch with no target is a miss; removing an absent id is not") {
    val p = page("")
    p("datastar-patch-elements", "mode remove\nselector #gone")
    assertEquals(p.misses, Nil)
    p("datastar-patch-elements", """elements <b id="gone">x</b>""")
    p(
      "datastar-patch-elements",
      "selector #gone\nmode inner\nelements <i>x</i>"
    )
    assertEquals(p.misses.size, 2, p.misses)
    p.close()
  }

  test("an element a patch brings has its bindings run") {
    val p = page("""<div data-signals="{v: 5}"></div><div id="h"></div>""")
    p(
      "datastar-patch-elements",
      "selector #h\nmode append\nelements <b id=\"x\" data-text=\"$v\">0</b>"
    )
    assert(p.view.contains("\"5\""), p.view)
    p.close()
  }

  test("a null signal is a miss") {
    val p = page("")
    p("datastar-patch-signals", """signals {"_e":{"s":null}}""")
    assertEquals(p.misses.size, 1, p.misses)
    p.close()
  }

  test("the query is what the mirrors write, a declared value left out") {
    val p = page(
      """<div id="m" data-signals="{_var_c__tab: '0', ui_popups: ''}"
        | data-fh-url:tab="['v.c.tab', $_var_c__tab, '0']"
        | data-fh-url__history="['ui.popups', $ui_popups]"></div>""".stripMargin
    )
    assertEquals(p.query, "")
    p(
      "datastar-patch-signals",
      """signals {"_var_c__tab":"1","ui_popups":"detail"}"""
    )
    assertEquals(
      p.query.drop(1).split('&').toSet,
      Set("v.c.tab=1", "ui.popups=detail")
    )
    p.close()
  }

  test("a reconnect carries the signals the stream's filter lets through") {
    val p = page(
      """<div id="m" data-signals="{conn: 'k1', _var_c__tab: '1', _var_c__tab__pending: '', _e: {s: 'on'}}"></div>""",
      stream
    )
    assert(p.streamRequest.startsWith("/sse/dashboard/s/patch?conn=k1"))
    p("datastar-patch-signals", """signals {"_cursor":{"storeVersion":3}}""")
    val sent = URLDecoder.decode(p.streamRequest.split("datastar=")(1), UTF_8)
    assertEquals(
      sent,
      """{"conn":"k1","_var_c__tab":"1","_cursor":{"storeVersion":3}}"""
    )
    assertEquals(p.conn, "k1")
    p.close()
  }

  test("a press posts what its handler sends, and the answer is applied") {
    val p = page(
      """<div data-signals="{conn: 'k1', _var_c__tab: '0'}"></div>""" +
        """<b id="t" data-text="$_var_c__tab">0</b>""" +
        """<button data-on:click="$_var_c__tab__pending = '1'; @post('sse/call/s/x?node=b', {payload: {conn: $conn}})"></button>"""
    )
    assertEquals(p.presses, List("sse/call/s/x?node=b"))
    p.press("sse/call/s/x?node=b")
    val List(r) = p.requests: @unchecked
    assertEquals(
      (r.method, r.path, r.body),
      ("POST", "/sse/call/s/x?node=b", """{"conn":"k1"}""")
    )
    p.answer(
      r.id,
      200,
      "event: datastar-patch-signals\ndata: signals {\"_var_c__tab\":\"1\"}\n\n"
    )
    assertEquals(p.requests, Nil)
    assert(p.view.contains("\"1\""), p.view)
    assertEquals(p.misses, Nil)
    p.close()
  }
}
