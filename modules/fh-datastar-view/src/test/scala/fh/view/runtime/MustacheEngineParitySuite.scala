package fh.view.runtime

import com.github.mustachejava.DefaultMustacheFactory
import com.samskivert.mustache.Mustache

/** The engine-migration gate: jmustache (what we ship) against mustache.java
  * (spullara, the migration candidate), rendered over the SAME context data —
  * the exact shapes the runtime uses (flat var maps, the childrenHtml list of
  * singleton maps) — across hostile values and mustache's edge semantics.
  *
  * Both engines claim spec compliance, but today's output depends on
  * jmustache's CONFIG, not just the spec: `defaultValue("")` and
  * `emptyStringIsFalse` are non-standard flags, and escaping entity sets vary
  * by engine. So compliance claims are not trusted here — every difference
  * this suite turns up is a migration cost to weigh, and a clean run is what
  * makes the swap safe.
  */
class MustacheEngineParitySuite extends munit.FunSuite:

  private val spullara = new DefaultMustacheFactory()

  /** jmustache, the production configuration ([[Templates.compiler]]). */
  private def jmustache(tpl: String, vars: java.util.Map[String, AnyRef]) =
    Templates.compiler.compile(tpl).execute(vars).asInstanceOf[String]

  /** mustache.java, defaults, writer-native (as [[Renderer.executeInto]] would
    * drive it).
    */
  private def spullaraRender(tpl: String, vars: java.util.Map[String, AnyRef]) =
    val mustache = spullara.compile(new java.io.StringReader(tpl), "t")
    val w = new java.io.StringWriter
    mustache.execute(w, vars)
    w.toString

  /** The childrenHtml shape: a list of single-entry maps, what a region loop
    * iterates.
    */
  private def children(names: String*): java.util.List[AnyRef] =
    val list = new java.util.ArrayList[AnyRef]()
    names.foreach(n => list.add(java.util.Collections.singletonMap("html", n)))
    list

  private def parity(tpl: String)(vars: (String, AnyRef)*)(using munit.Location) =
    val ctx = new java.util.HashMap[String, AnyRef]()
    vars.foreach((k, v) => ctx.put(k, v))
    val a = jmustache(tpl, ctx)
    val b = spullaraRender(tpl, ctx)
    assertEquals(b, a, clue = s"engines differ on: $tpl")

  // ------------------------------------------------------------- escaping

  test("escaping: the five HTML specials inside {{x}}") {
    parity("""<p title="{{v}}">{{v}}</p>""")(
      "v" -> """<b>&"'</b>"""
    )
  }

  test("escaping: unicode and newlines pass through") {
    parity("""<span>{{v}}</span>""")("v" -> "héllo — l1\nl2")
  }

  test("raw holes: {{{x}}} never escapes") {
    parity("""<div>{{{v}}}</div>""")("v" -> """<i class="x">&amp;</i>""")
  }

  // --------------------------------------------------------- missing keys

  test("missing key: renders empty in a hole") {
    parity("""<span>{{absent}}</span>""")()
  }

  test("missing key: a section over it is skipped") {
    parity("""<span>A{{#absent}}X{{/absent}}B</span>""")()
  }

  // --------------------------------------------------- section truthiness

  test("empty string section: emptyStringIsFalse semantics") {
    parity("""<span>A{{#c}}X{{/c}}B</span>""")("c" -> "")
  }

  test("non-empty string section: renders once with value as context") {
    parity("""<span>{{#c}}[{{c}}]{{/c}}</span>""")("c" -> "on")
  }

  test("a hole naming the section's own variable") {
    // The layout containers' actual shape: {{#class}} {{class}}{{/class}}
    parity("""<div class="fh-row{{#c}} {{c}}{{/c}}">""")("c" -> "fh-cols-2")
  }

  test("nested sections over two vars") {
    parity("""A{{#a}}1{{#b}}2{{/b}}3{{/a}}B""")("a" -> "x", "b" -> "")
  }

  test("inverted section: present and absent") {
    parity("""A{{^x}}Y{{/x}}B""")("x" -> "")
    parity("""A{{^x}}Y{{/x}}B""")("x" -> "set")
  }

  // ------------------------------------------------------ iteration shape

  test("region loop: iterates the singleton-map list") {
    parity("""<div>{{#children}}{{{html}}}{{/children}}</div>""")(
      "children" -> children("<i>a</i>", "b&amp;", "")
    )
  }

  test("region loop: empty list renders nothing") {
    parity("""<div>{{#children}}{{{html}}}{{/children}}</div>""")(
      "children" -> children()
    )
  }

  test("region loop: absent renders nothing") {
    parity("""<div>{{#children}}{{{html}}}{{/children}}</div>""")()
  }

  // ------------------------------------------- the shapes the library uses

  test("the entity card's tap-conditional shape") {
    parity(
      """<button{{{onclick}}}{{#busy}} class="busy"{{/busy}}>{{label}}</button>"""
    )("onclick" -> """ data-on:click="@post('/x/1')"""", "label" -> "Toggle")
    parity(
      """<button{{{onclick}}}{{#busy}} class="busy"{{/busy}}>{{label}}</button>"""
    )("label" -> "Toggle")
  }

  test("the tabs host shape: vars + loop + raw var") {
    parity(
      """<div class="tabs" data-signals__ifmissing="{ ui_{{id}}: {{bakeIndex}} }">{{#children}}{{{html}}}{{/children}}</div><div id="{{hostId}}">{{{panel}}}</div>"""
    )(
      "id" -> "c_2",
      "bakeIndex" -> """{ "t1": 0 }""",
      "hostId" -> "c_2_panel",
      "children" -> children("<a>one</a>", "<a>two</a>"),
      "panel" -> "<section>the panel</section>"
    )
  }

  test("values containing mustache-looking text") {
    parity("""<span>{{v}}</span>""")("v" -> "{{not_a_var}} {{{also_not}}}")
  }

  test("comment tags are dropped") {
    parity("""A{{! hidden }}B""")()
  }

  // ------------------------------------------------------- the fixture set

  test("the fixture dashboard's card templates") {
    val cards = fh.view.testkit.FixtureDashboard.cards
    cards.foreach { case (name, cd) =>
      val ctx = new java.util.HashMap[String, AnyRef]()
      ctx.put("id", "n_1")
      ctx.put("state", "on & <bright>")
      ctx.put("name", "Kitchen")
      ctx.put("unit", "W")
      ctx.put("class", "wide")
      ctx.put("children", children("<b>x</b>"))
      val a = Templates.compiler.compile(cd.template).execute(ctx)
      val b = spullaraRender(cd.template, ctx)
      assertEquals(b, a, clue = s"fixture card '$name' differs")
    }
  }

  test("jmustache compiles what mustache.java parses — no syntax gaps") {
    // Every template the two engines must agree on must at least COMPILE on
    // both; this catches tag-syntax support gaps cheaply before byte checks.
    val tpls = List(
      "{{x}}", "{{{x}}}", "{{#x}}a{{/x}}", "{{^x}}a{{/x}}", "{{!c}}",
      "{{#a}}{{#b}}x{{/b}}{{/a}}", "plain text", ""
    )
    tpls.foreach { tpl =>
      val ctx = new java.util.HashMap[String, AnyRef]()
      ctx.put("x", "v")
      ctx.put("a", "on")
      ctx.put("b", "on")
      assertEquals(
        spullaraRender(tpl, ctx),
        Templates.compiler.compile(tpl).execute(ctx),
        clue = s"syntax gap on: $tpl"
      )
    }
  }
