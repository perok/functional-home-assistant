package fh.view.build

import org.pkl.core.{EvaluatorBuilder, ModuleSource, TestResults}

import scala.jdk.CollectionConverters.*

/** Runs the pure-Pkl `*.test.pkl` suite in process, so `sbt test` covers the
  * authoring library instead of a `pkl` CLI nobody in CI or a container
  * necessarily has.
  *
  * Nothing here re-implements `pkl test`: `Evaluator.evaluateTest` IS the
  * runner the CLI calls, and the only work is turning its [[TestResults]] into
  * a munit failure. The modules need no project, lockfile or package resolver —
  * they import the library by RELATIVE PATH rather than `@fh-dashboard`, on
  * purpose (a test module inside `lib/` would change the package hash and
  * re-evaluate every dashboard), so a bare `preconfigured()` evaluator resolves
  * them.
  */
class PklLibraryTestSuite extends munit.FunSuite {

  private val testPklDir =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "pkl"

  /** `*.test.pkl`, not `*.pkl` — the same glob the CLI invocation uses. The
    * directory also holds fixture modules (`site-kitchen.pkl`) that are
    * imports, not suites, and amend no `pkl:test`.
    */
  private val modules: List[os.Path] =
    os.list(testPklDir).filter(_.last.endsWith(".test.pkl")).toList.sorted

  /** Never overwrite `examples` baselines from an automated run — a suite that
    * rewrites what it checks against always passes. Accepting new example
    * output is an authoring act and stays with the CLI (`pkl test
    * --overwrite`). Moot today: the suite is facts-only.
    *
    * Not a flag, deliberately. A `-D` or env var on the `sbt` command line does
    * not reach the tests here — both measured, both silent no-ops, because the
    * long-lived sbt server captures its environment when it starts. A switch
    * that does nothing is worse than no switch, and the repo's answer when a
    * test genuinely must be told something is an sbt `Command` around
    * `sys.props` (`dashboardSnapshotsUpdate`), which this does not need.
    */
  private val Overwrite = false

  /** A glob that matches nothing is a suite that passes by testing nothing, and
    * renaming a file is all it takes. Assert the count rather than the names,
    * so adding a module needs no edit here and losing every module fails
    * loudly.
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

  /** `TestResults` has no renderer outside the CLI, so build the message here:
    * every failing assertion with its module, section and test name, because a
    * bare "3 failures" sends the reader back to a tool they do not have.
    */
  private def report(results: TestResults): String = {
    val header = s"${results.moduleName} (${results.displayUri})"

    val sections =
      List(Option(results.facts), Option(results.examples)).flatten.flatMap {
        section =>
          section.results.asScala.toList.filter(_.isFailure).map { test =>
            val failures = test.failures.asScala.toList
              .map(f => s"      ${f.kind}: ${f.message}")
            // An ERROR carries its text on the EXCEPTION; `message` is a short
            // kind ("cannotFindKey") and is often null. Printing the field alone
            // produced `error: cannotFindKey` with no file, line or expression —
            // useless exactly when something is badly wrong.
            val errors = test.errors.asScala.toList
              .map(e => s"      error: ${errorText(e)}")
            (s"    ${section.name} / ${test.name}" :: (failures ++ errors))
              .mkString("\n")
          }
      }

    // A module-level error (a type mismatch, a broken import) produces no
    // section results at all, so it has to be reported separately or the
    // message comes out empty for the loudest kind of failure there is.
    val moduleError =
      Option(results.error).toList.map(e =>
        s"    module error: ${errorText(e)}"
      )

    (s"pkl test failed: $header" :: (sections ++ moduleError)).mkString("\n")
  }

  /** Whatever the error actually says. `TestResults.Error.message` is a short
    * kind and may be null; the location and the offending expression are on the
    * exception, which is what a reader needs.
    */
  private def errorText(e: TestResults.Error): String =
    List(
      Option(e.message),
      Option(e.exception).flatMap(x => Option(x.getMessage))
    ).flatten.distinct.mkString(": ")
}
