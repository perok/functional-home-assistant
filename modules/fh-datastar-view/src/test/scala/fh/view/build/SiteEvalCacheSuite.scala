package fh.view.build

import fh.view.testkit.PklWorkspace

/** [[SiteEvalCache]]: an unchanged workspace is not evaluated twice, and no
  * change to what the entry imports can be answered by a stale entry.
  */
class SiteEvalCacheSuite extends munit.FunSuite {

  private def workspace(): os.Path = {
    val tmp = os.temp.dir()
    val _ = PklWorkspace.bootstrap(tmp)
    os.write.over(tmp / "label.pkl", "text = \"first\"\n")
    os.write.over(
      tmp / Site.EntryFile,
      """amends "@fh-dashboard/site.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |import "label.pkl"
        |
        |dashboards {
        |  ["d"] { card = (c.grid) { children { c.title(label.text) } } }
        |}
        |""".stripMargin
    )
    tmp
  }

  private def eval(ws: os.Path): SourceEval.Result =
    SourceEval.eval(ws, Site.EntryFile).fold(e => fail(e), identity)

  test("an unchanged workspace is read back, not evaluated") {
    val ws = workspace()
    val first = eval(ws)
    val second = eval(ws)
    assert(!first.fromCache)
    assert(second.fromCache)
    assertEquals(second.value, first.value)
    assertEquals(second.imports, first.imports)
  }

  test("an edit to an imported module is evaluated") {
    val ws = workspace()
    val _ = eval(ws)
    os.write.over(ws / "label.pkl", "text = \"second\"\n")
    val edited = eval(ws)
    assert(!edited.fromCache)
    assert(edited.value.noSpaces.contains("second"), clue = edited.value)
  }

  test("a new file an unchanged entry does not import is not a change") {
    val ws = workspace()
    val _ = eval(ws)
    os.write.over(ws / "unrelated.pkl", "x = 1\n")
    assert(eval(ws).fromCache)
  }
}
