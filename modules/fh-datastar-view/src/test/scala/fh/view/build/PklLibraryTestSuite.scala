package fh.view.build

import org.pkl.core.{EvaluatorBuilder, ModuleSource, TestResults}

import scala.jdk.CollectionConverters.*

/** Runs the pure-Pkl `*.test.pkl` suite in process, so `sbt test` covers the
  * authoring library without a `pkl` CLI. `Evaluator.evaluateTest` is the
  * runner the CLI calls. The modules import the library by relative path, since
  * a test module inside `lib/` would move the package hash, so a bare
  * `preconfigured()` evaluator resolves them.
  */
class PklLibraryTestSuite extends munit.FunSuite {

  private val testPklDir =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "pkl"

  /** The CLI's glob: the directory also holds fixture modules that amend no
    * `pkl:test`.
    */
  private val modules: List[os.Path] =
    os.list(testPklDir).filter(_.last.endsWith(".test.pkl")).toList.sorted

  /** A suite that rewrites what it checks against always passes, so accepting
    * example output stays with the CLI (`pkl test --overwrite`). Not a flag: a
    * `-D` or env var on the sbt command line silently does not reach the
    * long-lived server.
    */
  private val Overwrite = false

  /** A glob matching nothing passes by testing nothing, and a rename is all it
    * takes. The count, so adding a module needs no edit here.
    */
  test("the pkl suite is discovered") {
    assert(
      modules.nonEmpty,
      s"no *.test.pkl modules under $testPklDir — the glob or the layout moved"
    )
  }

  modules.foreach { module =>
    test(s"pkl test ${module.last}") {
      val results = fh.view.build.PklBuild.serialized {
        val evaluator = EvaluatorBuilder.preconfigured().build()
        try evaluator.evaluateTest(ModuleSource.path(module.toNIO), Overwrite)
        finally evaluator.close()
      }
      if (results.failed()) fail(report(results))
    }
  }

  /** `TestResults` has no renderer outside the CLI; a bare "3 failures" sends
    * the reader back to a tool they do not have.
    */
  private def report(results: TestResults): String = {
    val header = s"${results.moduleName} (${results.displayUri})"

    val sections =
      List(Option(results.facts), Option(results.examples)).flatten.flatMap {
        section =>
          section.results.asScala.toList.filter(_.isFailure).map { test =>
            val failures = test.failures.asScala.toList
              .map(f => s"      ${f.kind}: ${f.message}")
            // An error's text is on the exception; `message` is a short kind
            // ("cannotFindKey"), often null, and alone said nothing.
            val errors = test.errors.asScala.toList
              .map(e => s"      error: ${errorText(e)}")
            (s"    ${section.name} / ${test.name}" :: (failures ++ errors))
              .mkString("\n")
          }
      }

    // A module-level error produces no section results, so without this the
    // loudest failure reports an empty message.
    val moduleError =
      Option(results.error).toList.map(e =>
        s"    module error: ${errorText(e)}"
      )

    (s"pkl test failed: $header" :: (sections ++ moduleError)).mkString("\n")
  }

  private def errorText(e: TestResults.Error): String =
    List(
      Option(e.message),
      Option(e.exception).flatMap(x => Option(x.getMessage))
    ).flatten.distinct.mkString(": ")
}
