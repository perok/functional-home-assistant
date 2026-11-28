package fh.view.build

import fh.view.testkit.{HouseFixture, PklWorkspace}
import io.circe.Json

/** The add-on evaluates in a child JVM, tests and dev runs in process: this is
  * what keeps the two the same evaluation.
  */
class PklEvalChildSuite extends munit.FunSuite {

  private val dump = PklDump.render(
    Json.obj(
      "areas" -> Json.obj(),
      "floors" -> Json.obj(),
      "entities" -> Json.fromFields(HouseFixture.all.map(_.toDumpEntry))
    )
  )

  /** The bundled starter, which renders every dump list it is given. */
  private def starter(): os.Path = {
    val ws = os.temp.dir() / "fh-dashboards"
    val _ = PklWorkspace.bootstrap(ws, dump)
    ws
  }

  private def child(ws: os.Path, entry: String) =
    PklEvalChild.run(ws, entry, PklBuild.workspaceCacheDir(ws))

  test("the child renders the same site JSON as an in-process evaluation") {
    val ws = starter()
    val inProcess = SourceEval
      .eval(ws, Site.EntryFile)
      .fold(e => fail(e), _.value)
    val forked = child(ws, Site.EntryFile)
      .flatMap(io.circe.parser.parse(_).left.map(_.message))
      .fold(e => fail(e), identity)
    assert(inProcess.asObject.exists(_.contains("dashboards")), "no site")
    assertEquals(forked, inProcess)
  }

  test("a Pkl error crosses the process boundary as the author's message") {
    val ws = starter()
    os.write.over(ws / "broken.pkl", "x = nope\n")
    val result = child(ws, "broken.pkl")
    assert(result.isLeft, s"expected an error, got $result")
    assert(result.left.exists(_.contains("nope")), s"$result")
  }
}
