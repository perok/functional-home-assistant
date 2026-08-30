package fh.view.runtime

import com.github.mustachejava.DefaultMustacheFactory

/** The engine's behaviour contract, pinned directly.
  *
  * This suite began life as the migration gate — jmustache (what we shipped)
  * rendered beside mustache.java over the same contexts, and every difference
  * was a migration cost to weigh. That gate did its job and jmustache is gone;
  * what remains is the smaller set of behaviours the runtime DEPENDS on, now
  * asserted against expected bytes rather than against another engine: the
  * escape set (with the newline pin, whose raw-engine half is the reason the
  * override exists), missing keys rendering empty, `emptyStringIsFalse`
  * section truthiness, and the region-loop shapes every container template
  * uses.
  */
class TemplatesBehaviourSuite extends munit.FunSuite:

  /** mustache.java through the PRODUCTION factory ([[Templates.factory]]), the
    * same objects the runtime executes — writer-native, as
    * [[Renderer.executeInto]] drives it.
    */
  private def render(tpl: String, vars: (String, AnyRef)*): String =
    val ctx = new java.util.HashMap[String, AnyRef]()
    vars.foreach((k, v) => ctx.put(k, v))
    val w = new java.io.StringWriter
    Templates.factory
      .compile(new java.io.StringReader(tpl), "t")
      .execute(w, ctx)
    w.toString

  /** The childrenHtml shape: a list of single-entry maps, what a region loop
    * iterates.
    */
  private def children(names: String*): java.util.List[AnyRef] =
    val list = new java.util.ArrayList[AnyRef]()
    names.foreach(n => list.add(java.util.Collections.singletonMap("html", n)))
    list

  // ------------------------------------------------------------- escaping

  test("escaping: the five HTML specials inside {{x}}") {
    assertEquals(
      render("""<p title="{{v}}">{{v}}</p>""", "v" -> """<b>&"'</b>"""),
      """<p title="&lt;b&gt;&amp;&quot;&#39;&lt;/b&gt;">&lt;b&gt;&amp;&quot;&#39;&lt;/b&gt;</p>"""
    )
  }

  test("newlines pass through — the override pins it") {
    // mustache.java's own `encode` HTML-escapes a newline (mustache.js and
    // jmustache leave it verbatim); HA values carry newlines (more-info
    // attribute blocks), so [[Templates.factory]] overrides `encode` to
    // jmustache's exact set. If the override is ever dropped, THIS test names
    // the byte that changed — and the raw-engine half says what it would
    // become.
    val raw = new DefaultMustacheFactory()
      .compile(new java.io.StringReader("{{v}}"), "t")
    val w = new java.io.StringWriter
    raw.execute(
      w,
      java.util.Collections.singletonMap[String, AnyRef]("v", "a\nb")
    )
    assertEquals(
      w.toString,
      "a&#10;b",
      clue = "raw engine behavior moved — reread the override"
    )
    assertEquals(
      render("{{v}}", "v" -> "a\nb"),
      "a\nb",
      clue = "our encode override regressed"
    )
  }

  test("raw holes: {{{x}}} never escapes") {
    assertEquals(
      render("""<div>{{{v}}}</div>""", "v" -> """<i class="x">&amp;</i>"""),
      """<div><i class="x">&amp;</i></div>"""
    )
  }

  // --------------------------------------------------------- missing keys

  test("missing key: renders empty in a hole") {
    assertEquals(render("""<span>{{absent}}</span>"""), "<span></span>")
  }

  test("missing key: a section over it is skipped") {
    assertEquals(
      render("""<span>A{{#absent}}X{{/absent}}B</span>"""),
      "<span>AB</span>"
    )
  }

  // --------------------------------------------------- section truthiness

  test("empty string section: skipped (emptyStringIsFalse semantics)") {
    assertEquals(render("""<span>A{{#c}}X{{/c}}B</span>""", "c" -> ""), "<span>AB</span>")
  }

  test("non-empty string section: renders once with value as context") {
    assertEquals(
      render("""<span>{{#c}}[{{c}}]{{/c}}</span>""", "c" -> "on"),
      "<span>[on]</span>"
    )
  }

  test("a hole naming the section's own variable") {
    // The layout containers' actual shape: {{#class}} {{class}}{{/class}}
    assertEquals(
      render("""<div class="fh-row{{#c}} {{c}}{{/c}}">""", "c" -> "fh-cols-2"),
      """<div class="fh-row fh-cols-2">"""
    )
  }

  test("inverted section: present and absent") {
    // "" is falsy, so the inverted section fires on it.
    assertEquals(render("""A{{^x}}Y{{/x}}B""", "x" -> ""), "AYB")
    assertEquals(render("""A{{^x}}Y{{/x}}B""", "x" -> "set"), "AB")
  }

  // ------------------------------------------------------ iteration shape

  test("region loop: iterates the singleton-map list") {
    assertEquals(
      render(
        """<div>{{#children}}{{{html}}}{{/children}}</div>""",
        "children" -> children("<i>a</i>", "b&amp;", "")
      ),
      "<div><i>a</i>b&amp;</div>"
    )
  }

  test("region loop: empty list renders nothing") {
    assertEquals(
      render(
        """<div>{{#children}}{{{html}}}{{/children}}</div>""",
        "children" -> children()
      ),
      "<div></div>"
    )
  }

  test("region loop: absent renders nothing") {
    assertEquals(
      render("""<div>{{#children}}{{{html}}}{{/children}}</div>"""),
      "<div></div>"
    )
  }

  // ------------------------------------------- the shapes the library uses

  test("the entity card's tap-conditional shape") {
    assertEquals(
      render(
        """<button{{{onclick}}}{{#busy}} class="busy"{{/busy}}>{{label}}</button>""",
        "onclick" -> """ data-on:click="@post('/x/1')"""",
        "label" -> "Toggle"
      ),
      """<button data-on:click="@post('/x/1')">Toggle</button>"""
    )
    assertEquals(
      render(
        """<button{{{onclick}}}{{#busy}} class="busy"{{/busy}}>{{label}}</button>""",
        "label" -> "Toggle"
      ),
      "<button>Toggle</button>"
    )
  }

  test("the tabs host shape: vars + region loops") {
    assertEquals(
      render(
        """<div class="tabs" data-signals__ifmissing="{ ui_{{id}}: {{bakeIndex}} }">{{#children}}{{{html}}}{{/children}}</div><div id="{{hostId}}">{{#panel}}{{{html}}}{{/panel}}</div>""",
        "id" -> "c_2",
        "bakeIndex" -> "1",
        "hostId" -> "c_2_panel",
        "children" -> children("<a>one</a>", "<a>two</a>"),
        "panel" -> children("<section>the panel</section>")
      ),
      """<div class="tabs" data-signals__ifmissing="{ ui_c_2: 1 }"><a>one</a><a>two</a></div><div id="c_2_panel"><section>the panel</section></div>"""
    )
  }

  test("values containing mustache-looking text are verbatim") {
    assertEquals(
      render("""<span>{{v}}</span>""", "v" -> "{{not_a_var}} {{{also_not}}}"),
      "<span>{{not_a_var}} {{{also_not}}}</span>"
    )
  }

  test("comment tags are dropped") {
    assertEquals(render("""A{{! hidden }}B"""), "AB")
  }
