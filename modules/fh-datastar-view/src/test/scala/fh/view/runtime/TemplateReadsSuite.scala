package fh.view.runtime

/** What [[Templates.card]] reads off a template: the names a node must set, the
  * names it may, and where each region is placed.
  */
class TemplateReadsSuite extends munit.FunSuite:

  private def reads(tpl: String, regions: String*): Templates.Reads =
    Templates.card("t", tpl, regions.toSet).fold(e => fail(e), _.reads)

  test("a placeholder outside every section is required; one inside is not") {
    val r =
      reads("<p>{{state}} {{#unit}}{{unit}}{{/unit}}{{^icon}}x{{/icon}}</p>")
    assertEquals(r.required, Set("state"))
    assertEquals(r.names, Set("state", "unit", "icon"))
  }

  test("a view reads its slot; the section's own value reads nothing") {
    import Templates.View
    val r = reads(
      "{{{a.bind}}}{{#b.has}}{{b}}{{/b.has}}{{{c.jsValue}}}{{d.signal}}" +
        "{{e.nope}}{{#xs}}{{.}}{{/xs}}"
    )
    assertEquals(r.names, Set("a", "b", "c", "d", "xs"))
    assertEquals(
      r.views,
      Set(
        "a" -> View.Bind,
        "b" -> View.Has,
        "c" -> View.JsValue,
        "d" -> View.Signal
      )
    )
    assertEquals(r.required, Set("a", "c", "d"))
    assertEquals(r.unknownViews, Set("e.nope"))
    assertEquals(r.raw, Set.empty[String])
  }

  test("a binding inside a section on its own value is recorded") {
    val r = reads(
      "{{#x}}<i {{{x.bind}}}>{{/x}}{{^w}}<i {{{w.bind}}}>{{/w}}" +
        "{{#y.has}}<i {{{y.bind}}}>{{/y.has}}{{#z}}<i {{{y.bind}}}>{{/z}}"
    )
    assertEquals(r.boundInOwnSection, Set("x", "w"))
  }

  test("a region is counted where it is a raw hole outside every section") {
    val r = reads(
      "{{{children}}}{{{panel}}}{{{panel}}}<b>{{{text}}}</b>{{#x}}{{{x}}}{{/x}}",
      "children",
      "panel"
    )
    assertEquals(r.regionHoles, Map("children" -> 1, "panel" -> 2))
    assertEquals(r.misplacedRegions, Set.empty[String])
    assertEquals(r.names, Set("text", "x"))
  }

  test("a region placed any other way is misplaced, and is no slot name") {
    val r = reads(
      "{{#a}}{{{html}}}{{/a}}{{b}}{{#s}}{{{c}}}{{/s}}{{^d}}-{{/d}}",
      "a",
      "b",
      "c",
      "d"
    )
    assertEquals(r.misplacedRegions, Set("a", "b", "c", "d"))
    assertEquals(r.regionHoles, Map.empty[String, Int])
    assertEquals(r.names, Set("html", "s"))
  }

  test("raw names are the triple-braced ones, inside sections too") {
    val r = reads("{{a}}{{{b}}}{{#s}}{{{c}}}{{/s}}{{& d}}")
    assertEquals(r.raw, Set("b", "c", "d"))
  }

  test("a template that does not compile is a Left") {
    assert(Templates.card("t", "{{#open}}", Set.empty).isLeft)
  }

  test("validate: a name inside a section may go unset") {
    import fh.view.model.*
    val d = Dashboard(
      Map(
        "card" -> CardDef(
          """<div><span>{{state}}</span> {{#unit}}{{unit}}{{/unit}}</div>"""
        )
      ),
      LayoutNode.Component(
        "card",
        slots = Map("state" -> TemplateSlot(SlotSource()))
      )
    )
    assertEquals(d.validate(), Nil)
  }
