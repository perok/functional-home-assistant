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
         |<div id="root"><main>$body</main><div id="popups"></div></div></body></html>""".stripMargin,
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

  private val twoInHistory =
    """<div data-signals="{_var_c__a: '', _var_c__b: '', _var_c__w: ''}"
      | data-fh-url:a__history="['v.c.a', $_var_c__a, '']"
      | data-fh-url:b__history="['v.c.b', $_var_c__b, '']"
      | data-fh-url:w="['v.c.w', $_var_c__w, '']"></div>""".stripMargin

  private def signals(p: FakePage, json: String) =
    p("datastar-patch-signals", s"signals $json")

  test("each history variable's choice is an entry, and keeps the others'") {
    val p = page(twoInHistory)
    signals(p, """{"_var_c__a":"1"}""")
    signals(p, """{"_var_c__w":"7d"}""")
    signals(p, """{"_var_c__b":"2"}""")
    signals(p, """{"_var_c__a":"3"}""")
    assertEquals(
      p.entries,
      List(
        "" -> false,
        "?v.c.a=1&v.c.w=7d" -> true,
        "?v.c.a=1&v.c.w=7d&v.c.b=2" -> true,
        "?v.c.a=3&v.c.w=7d&v.c.b=2" -> true
      )
    )
    p.close()
  }

  test("a link naming two history variables is one entry above the dashboard") {
    val p = js().load(
      s"""<!doctype html><html><head><base href="/"></head><body>
         |<div id="root"><main><div data-signals="{_var_c__a: '1', _var_c__b: '2', _var_c__w: '7d'}"
         | data-fh-url:a__history="['v.c.a', $$_var_c__a, '']"
         | data-fh-url:b__history="['v.c.b', $$_var_c__b, '']"
         | data-fh-url:w="['v.c.w', $$_var_c__w, '']"></div></main></div></body></html>""".stripMargin,
      "http://fake.test/d/s?v.c.a=1&v.c.b=2&v.c.w=7d"
    )
    assertEquals(
      p.entries,
      List("?v.c.w=7d" -> false, "?v.c.a=1&v.c.b=2&v.c.w=7d" -> true)
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
