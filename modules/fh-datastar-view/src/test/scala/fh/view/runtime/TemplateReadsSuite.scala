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

  test("a dotted name reads its head; the section's own value reads nothing") {
    val r = reads("{{a.b}}{{#xs}}{{.}}{{/xs}}")
    assertEquals(r.names, Set("a", "xs"))
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
      LayoutNode.Component("card", slots = Map("state" -> SlotSource()))
    )
    assertEquals(d.validate(), Nil)
  }
