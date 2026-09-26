package fh.view.query

import fh.view.model.Transform
import fh.view.testkit.PklWorkspace
import org.pkl.core.{EvaluatorBuilder, ModuleSource}

/** The label a failed chart shows is written by the runtime, and the one an
  * author places by the library (`core/text.pkl`). Two copies of one structure,
  * so this evaluates the library's and holds them equal — a label that drifted
  * would lose the base CSS it is styled by.
  */
class FailureLabelSuite extends munit.FunSuite {

  private def library(expr: String): String = {
    val text = (PklWorkspace.resourcesLib / "core" / "text.pkl").toNIO.toUri
    val ev = EvaluatorBuilder.preconfigured().build()
    try
      ev.evaluateExpressionString(
        ModuleSource.text(s"""import "$text" as t"""),
        expr
      )
    finally ev.close()
  }

  test("the runtime's label is the library's, in every tone") {
    for (tone <- List("normal", "dim", "error", "warning"))
      assertEquals(
        Staged.label(tone, "Chart unavailable"),
        library(s"""t.label("$tone", "Chart unavailable")""")
      )
  }

  test("a failed chart shows the error label; failed data shows nothing") {
    assertEquals(
      Staged.failed(Transform.Stage.Chart()).value,
      library("""t.label("error", "Chart unavailable")""")
    )
    assertEquals(Staged.failed(Transform.Stage.Passthrough).value, "")
  }
}
