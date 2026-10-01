package fh.view.build

/** `components/base/` is the half of the library that knows nothing about Home
  * Assistant, so an HA layer can be built on it. Nothing else enforces that:
  * one `import` of the domain schema and the split is skin-deep again.
  */
class BaseComponentsSuite extends munit.FunSuite {

  private val base: os.Path =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" /
      "dashboards" / "lib" / "components" / "base"

  test("no base component imports the Home Assistant schema") {
    val modules = os.list(base).filter(_.ext == "pkl").toList
    assert(modules.nonEmpty, s"no modules under $base")
    val offending = for {
      m <- modules
      line <- os.read.lines(m)
      if line.trim.startsWith("import") && line.contains("hass")
    } yield s"${m.last}: $line"
    assertEquals(offending, Nil)
  }
}
