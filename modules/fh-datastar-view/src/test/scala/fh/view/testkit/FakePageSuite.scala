package fh.view.testkit

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

class FakePageSuite extends munit.FunSuite {

  private def page(body: String, init: String = ""): FakePage =
    FakePage.load(
      s"""<!doctype html><html><head></head><body data-init="$init">
         |<main id="dashboard">$body</main><div id="popups"></div></body></html>""".stripMargin
    )

  private def elements(p: FakePage, lines: String*): Unit =
    p("datastar-patch-elements", lines.mkString("\n"))

  private def signals(p: FakePage, json: String): Unit =
    p("datastar-patch-signals", s"signals $json")

  private def dashboard(p: FakePage): String =
    p.view.linesIterator.takeWhile(_ != "#popups").mkString("\n")

  test("each mode lands where the bundle puts it") {
    val p = page("""<div id="h"><i id="a">a</i></div><b id="x">x</b>""")
    elements(p, """elements <b id="x">y</b>""")
    elements(p, "selector #h", "mode append", """elements <i id="c">c</i>""")
    elements(p, "selector #a", "mode before", """elements <i id="z">z</i>""")
    elements(p, "mode remove", "selector #c")
    elements(
      p,
      "selector #popups",
      "mode inner",
      """elements <p id="p">p</p>"""
    )
    assertEquals(
      p.view,
      FakePage
        .load(
          """<html><body><main id="dashboard"><div id="h"><i id="z">z</i><i id="a">a</i></div>""" +
            """<b id="x">y</b></main><div id="popups"><p id="p">p</p></div></body></html>"""
        )
        .view
    )
    assertEquals(p.misses, Nil)
  }

  test("a patch with no target is a miss; removing an absent id is not") {
    val p = page("")
    elements(p, "mode remove", "selector #gone")
    assertEquals(p.misses, Nil)
    elements(p, """elements <b id="gone">x</b>""")
    elements(p, "selector #gone", "mode inner", "elements <i>x</i>")
    assertEquals(p.misses.size, 2)
  }

  test("a binding is painted from the signals, a choice read as committed") {
    val p = page(
      """<span id="t" data-signals="{_e: {s: 'on'}}" data-text="$_e.s">on</span>""" +
        """<b id="c" class="fh-chosen" data-class:fh-chosen="String(($_v__pending || $_v)) === 'a'"></b>""" +
        """<i id="d" data-attr:disabled="$_e.off">x</i>"""
    )
    signals(p, """{"_e":{"s":"off","off":true},"_v":"b"}""")
    val shown = dashboard(p)
    assert(shown.contains("\"off\""), shown)
    assert(!shown.contains("fh-chosen\""), shown)
    assert(shown.contains("""disabled="""""), shown)
  }

  test(
    "a morph bringing the same seed back keeps the signal; a new seed sets it"
  ) {
    val p = page(
      """<b id="n" data-signals="{_e: {s: 'on'}}" data-text="$_e.s">on</b>"""
    )
    signals(p, """{"_e":{"s":"off"}}""")
    elements(
      p,
      """elements <b id="n" data-signals="{_e: {s: 'on'}}" data-text="$_e.s">on</b>"""
    )
    assertEquals(p.signal("_e.s").flatMap(_.asString), Some("off"))
    elements(
      p,
      """elements <b id="n" data-signals="{_e: {s: 'idle'}}" data-text="$_e.s">idle</b>"""
    )
    assertEquals(p.signal("_e.s").flatMap(_.asString), Some("idle"))
  }

  test("an __ifmissing seed sets only what is absent") {
    val p =
      page("""<b id="n" data-signals__ifmissing="{_a: false, _b: 'x'}"></b>""")
    signals(p, """{"_a":true}""")
    elements(
      p,
      """elements <b id="n" data-signals__ifmissing="{_a: false, _c: 'y'}"></b>"""
    )
    assertEquals(p.signal("_a").flatMap(_.asBoolean), Some(true))
    assertEquals(p.signal("_c").flatMap(_.asString), Some("y"))
  }

  test("what only an expression it cannot read keeps live is left out") {
    val stale = page(
      """<b id="n" class="k" style="--w: 1" data-class:k="$a > 1" data-style:--w="Math.min($a)" data-text="$a + 1">2</b>"""
    )
    val fresh = page(
      """<b id="n" data-class:k="$a > 1" data-style:--w="Math.min($a)" data-text="$a + 1">9</b>"""
    )
    assertEquals(stale.view, fresh.view)
  }

  test("a seed copying a signal reads it") {
    val p = page(
      """<b id="a" data-signals="{_e: {v: 7}}"></b><b id="n" data-signals__ifmissing="{_slide: $_e.v}"></b>"""
    )
    assertEquals(
      p.signal("_slide").flatMap(_.asNumber).map(_.toDouble),
      Some(7.0)
    )
  }

  test(
    "a binding on a signal a handler writes is left out, a pending ask is not"
  ) {
    val handler = """data-on-signal-patch="$_k__h = $_e.h""""
    val stale = page(
      s"""<b id="k" $handler style="--h: 30deg" data-style:--h="$$_k__h"></b>""" +
        """<b id="c" class="on" data-on:click="$_v__pending = 'a'" data-class:on="String(($_v__pending || $_v)) === 'a'"></b>"""
    )
    val fresh = page(
      s"""<b id="k" $handler style="--h: 0deg" data-style:--h="$$_k__h"></b>""" +
        """<b id="c" class="on" data-on:click="$_v__pending = 'a'" data-class:on="String(($_v__pending || $_v)) === 'a'"></b>"""
    )
    signals(stale, """{"_v":"a"}""")
    signals(fresh, """{"_v":"b"}""")
    val both = FakePage.matches(stale, fresh)
    assert(both.toString.contains("Failure"), both)
    assert(
      dashboard(fresh).linesIterator.exists(l =>
        l.contains("""id="k"""") && !l.contains("--h:")
      )
    )
  }

  test("the query is what the mirrors write, a declared value left out") {
    val p = page(
      """<div id="m" data-signals="{_var_c__tab: '0', ui_popups: ''}"
        | data-fh-url:tab="['v.c.tab', $_var_c__tab, '0']"
        | data-fh-url__history="['ui.popups', $ui_popups]"></div>""".stripMargin
    )
    assertEquals(p.query, "")
    signals(p, """{"_var_c__tab":"1","ui_popups":"detail"}""")
    assertEquals(p.query, "?ui.popups=detail&v.c.tab=1")
  }

  test("a reconnect carries the signals the stream's filter lets through") {
    val init =
      """@get('sse/dashboard/s/patch?conn=k1', {retry:'always',filterSignals:{include:'^(conn$|_cursor\.|_var_(?!.*__pending$))',exclude:'(?!)'}})"""
    val p = page(
      """<div id="m" data-signals="{conn: 'k1', _var_c__tab: '1', _var_c__tab__pending: '', _e: {s: 'on'}}"></div>""",
      init
    )
    signals(p, """{"_cursor":{"storeVersion":3}}""")
    val sent = URLDecoder.decode(p.streamRequest.split("datastar=")(1), UTF_8)
    assertEquals(
      sent,
      """{"conn":"k1","_var_c__tab":"1","_cursor":{"storeVersion":3}}"""
    )
    assertEquals(p.conn, "k1")
  }
}
