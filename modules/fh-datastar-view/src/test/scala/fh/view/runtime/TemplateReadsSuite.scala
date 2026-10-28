package fh.view.runtime

/** What [[Templates.card]] reads off a template: the names a node must set, the
  * names it may, and the shape of each region's section.
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

  test("a region's section is not descended, and its shape is reported") {
    val r = reads(
      "{{#children}}{{{html}}}{{/children}}{{#panel}}<i>{{{html}}}</i>{{/panel}}",
      "children",
      "panel"
    )
    assertEquals(r.names, Set.empty[String])
    assertEquals(r.regionBodies, Map("children" -> true, "panel" -> false))
    assertEquals(r.strayHtml, Set.empty[String])
  }

  test("html outside a declared region is stray, named by its section") {
    val r = reads("{{{html}}}{{#kids}}{{{html}}}{{/kids}}")
    assertEquals(r.strayHtml, Set("", "kids"))
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
