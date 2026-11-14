package fh.view.runtime

import io.circe.Json

/** The standard macros the environment declares, over an entity as a slot reads
  * one.
  */
class CelMacrosSuite extends munit.FunSuite {

  private val light = EntityState(
    "light.k",
    "on",
    Map(
      "brightness" -> Json.fromInt(128),
      "effect_list" -> Json.arr(Json.fromString("a"), Json.fromString("b"))
    )
  )

  private def eval(src: String): String =
    Cel.run(Cel.parse(src).fold(fail(_), identity), light, "home")

  test("has() tells a present attribute from a missing one") {
    assertEquals(eval("has(value.attr.brightness)"), "true")
    assertEquals(eval("has(value.attr.nope)"), "false")
  }

  test("map, filter and map with a filter") {
    assertEquals(
      eval("value.attr.effect_list.map(e, e + '!').join(',')"),
      "a!,b!"
    )
    assertEquals(
      eval("value.attr.effect_list.filter(e, e != 'a').join(',')"),
      "b"
    )
    assertEquals(eval("str([1, 2, 3].map(x, x > 1, x * 10))"), "[20,30]")
  }

  test("all and exists") {
    assertEquals(eval("[1, 2].all(x, x > 0)"), "true")
    assertEquals(eval("value.attr.effect_list.exists(e, e == 'b')"), "true")
  }

  test("a name read only inside a macro's body is read") {
    // What decides which named inputs a node binds.
    assert(Cel.identifiers("[1].map(x, x + limit)").contains("limit"))
    assert(Cel.identifiers("past.filter(p, p[1] > 0.0)").contains("past"))
  }
}
