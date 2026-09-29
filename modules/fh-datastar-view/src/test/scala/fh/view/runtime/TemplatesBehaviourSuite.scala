package fh.view.runtime

/** The engine behaviours the runtime depends on, against expected bytes: the
  * escape set (newlines included), missing keys rendering empty,
  * `emptyStringIsFalse` truthiness, and the region-loop shapes every container
  * uses.
  */
class TemplatesBehaviourSuite extends munit.FunSuite:

  /** Through the production factory ([[Templates.factory]]), writer-native as
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

  /** What a region loop iterates. */
  private def children(names: String*): java.util.List[AnyRef] =
    val list = new java.util.ArrayList[AnyRef]()
    names.foreach(n => list.add(java.util.Collections.singletonMap("html", n)))
    list

  test("escaping: the five HTML specials inside {{x}}") {
    assertEquals(
      render("""<p title="{{v}}">{{v}}</p>""", "v" -> """<b>&"'</b>"""),
      """<p title="&lt;b&gt;&amp;&quot;&#39;&lt;/b&gt;">&lt;b&gt;&amp;&quot;&#39;&lt;/b&gt;</p>"""
    )
  }

  test("a value's line breaks survive a patch") {
    // The more-info `<pre>` shape: its attribute lines are one value, and a
    // patch collapses the literal newlines of its fragment.
    val value = "a: 1\nb: 2\r\nc: 3"
    val sent = Datastar
      .patchElements(render("<pre>{{v}}</pre>", "v" -> value))
      .data
      .get
    assertEquals(sent, "elements <pre>a: 1&#10;b: 2&#13;&#10;c: 3</pre>")
    val shown = sent
      .stripPrefix("elements <pre>")
      .stripSuffix("</pre>")
      .replace("&#10;", "\n")
      .replace("&#13;", "\r")
    assertEquals(shown, value)
  }

  test("raw holes: {{{x}}} never escapes") {
    assertEquals(
      render("""<div>{{{v}}}</div>""", "v" -> """<i class="x">&amp;</i>"""),
      """<div><i class="x">&amp;</i></div>"""
    )
  }

  test("missing key: renders empty in a hole") {
    assertEquals(render("""<span>{{absent}}</span>"""), "<span></span>")
  }

  test("missing key: a section over it is skipped") {
    assertEquals(
      render("""<span>A{{#absent}}X{{/absent}}B</span>"""),
      "<span>AB</span>"
    )
  }

  test("empty string section: skipped (emptyStringIsFalse semantics)") {
    assertEquals(
      render("""<span>A{{#c}}X{{/c}}B</span>""", "c" -> ""),
      "<span>AB</span>"
    )
  }

  test("non-empty string section: renders once with value as context") {
    assertEquals(
      render("""<span>{{#c}}[{{c}}]{{/c}}</span>""", "c" -> "on"),
      "<span>[on]</span>"
    )
  }

  test("a hole naming the section's own variable") {
    // The layout containers' shape.
    assertEquals(
      render("""<div class="fh-row{{#c}} {{c}}{{/c}}">""", "c" -> "fh-cols-2"),
      """<div class="fh-row fh-cols-2">"""
    )
  }

  test("inverted section: present and absent") {
    assertEquals(render("""A{{^x}}Y{{/x}}B""", "x" -> ""), "AYB")
    assertEquals(render("""A{{^x}}Y{{/x}}B""", "x" -> "set"), "AB")
  }

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
