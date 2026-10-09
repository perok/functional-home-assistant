package fh.view.model

class ThemeClassesSuite extends munit.FunSuite {

  private def rules(rs: (String, String)*) =
    ThemeClasses(
      rs.toMap.view.mapValues(_.split(' ').toList.filter(_.nonEmpty)).toMap
    )

  test(
    "a class token is rewritten inside a Mustache section, whole tokens only"
  ) {
    val t = rules("fh-on" -> "fh-on is-on", "fh-busy" -> "x")
    assertEquals(
      t.rewriteTemplate(
        """<b class="card {{#on}}fh-on{{/on}} fh-busy-spin fh-busy">"""
      ),
      """<b class="card {{#on}}fh-on is-on{{/on}} fh-busy-spin x">"""
    )
  }

  test("a binding becomes one per class in the content, modifiers kept") {
    val t = rules("fh-busy-spin" -> "shape loading-indicator")
    assertEquals(
      t.rewriteTemplate(
        """<i data-class:fh-busy-spin__case.kebab="$a_slow">"""
      ),
      """<i data-class:shape__case.kebab="$a_slow" data-class:loading-indicator__case.kebab="$a_slow">"""
    )
  }

  test("an empty content drops the class and its binding") {
    val t = rules("fh-busy-spin" -> "")
    val out = t.rewriteTemplate(
      """<i class="fh-busy-spin" data-class:fh-busy-spin="$b">"""
    )
    assert(!out.contains("fh-busy-spin"), out)
    assert(!out.contains("data-class"), out)
  }

  test("what is not a class attribute or an fh- binding is left alone") {
    val t = rules("fh-on" -> "fh-on is-on")
    val untouched = List(
      """<a data-class="{active: $x}" data-on:click="el.classList.add('fh-on')">""",
      """<a data-class:on="$x" title="fh-on">"""
    )
    untouched.foreach(s => assertEquals(t.rewriteTemplate(s), s))
  }

  test("a class list expands token by token") {
    val t = rules("fh-cell" -> "fh-cell s12", "fh-cols-3" -> "s4")
    assertEquals(
      t.expandAll("fh-cell fh-cols-3 fh-hug"),
      "fh-cell s12 s4 fh-hug"
    )
  }

  test("the runtime's own classes must stay in their own content") {
    assertEquals(
      rules("fh-cell" -> "fh-cell s12", "fh-group" -> "g fh-group").errors,
      Nil
    )
    val errs = rules("fh-cell" -> "s12", "fh-group" -> "").errors
    assertEquals(errs.size, 2, errs)
    assert(errs.forall(_.contains("must stay in its own content")), errs)
  }

  test("a rule names an fh- class") {
    val errs = rules("chip" -> "x").errors
    assert(errs.exists(_.contains("'chip' is not an fh- class")), errs)
  }
}
