package fh.view.runtime

import fh.view.testkit.PklWorkspace
import org.pkl.core.{EvaluatorBuilder, ModuleSource}

/** The offline banners and the dead-stream rollbacks read the shell's signals
  * through `core/shell.pkl`; the server spells them as its own, and a drift
  * freezes a banner silently.
  */
class OfflineBannersSuite extends munit.FunSuite {

  private def library(expr: String): String = {
    val shell = (PklWorkspace.resourcesLib / "core" / "shell.pkl").toNIO.toUri
    val ev = EvaluatorBuilder.preconfigured().build()
    try
      ev.evaluateExpressionString(
        ModuleSource.text(s"""import "$shell" as t"""),
        expr
      )
    finally ev.close()
  }

  test("the library names the signals the server declares") {
    assertEquals(library("t.sseSignal"), Server.SseSignal)
    assertEquals(library("t.haDownSignal"), Server.HaDownSignal)
  }
}
