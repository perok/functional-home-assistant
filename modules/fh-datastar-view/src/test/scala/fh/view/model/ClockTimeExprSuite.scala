package fh.view.model

import fh.view.runtime.EntityState
import fh.view.testkit.PklWorkspace
import org.pkl.core.{EvaluatorBuilder, ModuleSource}

/** The library's clock-time expression, in a fixed zone: the process zone is
  * UTC here and in CI, where no conversion would pass unnoticed.
  */
class ClockTimeExprSuite extends munit.FunSuite {

  private val inOslo: Transform.Compiled = {
    val slot = (PklWorkspace.resourcesLib / "core" / "slot.pkl").toNIO.toUri
    val ev = EvaluatorBuilder.preconfigured().build()
    val src =
      try
        ev.evaluateExpressionString(
          ModuleSource.text(s"""import "$slot" as s"""),
          "s.CLOCK_TIME_EXPR"
        )
      finally ev.close()
    Transform
      .parse(src.replace("time_zone", "'Europe/Oslo'"))
      .fold(e => fail(e), identity)
  }

  private def clock(state: String): String =
    Transform.run(inOslo, EntityState("scene.kveld", state, Map.empty), "d")

  test("an instant reads as the local clock time, summer and winter") {
    assertEquals(clock("2026-10-12T16:03:11.552123+00:00"), "18:03")
    assertEquals(clock("2026-01-12T07:05:00+00:00"), "08:05")
  }

  test("hours and minutes are two digits") {
    assertEquals(clock("2026-01-12T23:01:00+00:00"), "00:01")
  }

  test("a state that is not an instant passes through") {
    assertEquals(clock("unknown"), "unknown")
  }
}
