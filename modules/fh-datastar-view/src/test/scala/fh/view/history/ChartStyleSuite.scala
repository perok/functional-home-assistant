package fh.view.history

import fh.view.model.Step
import io.circe.parser.decode
import io.github.iltotore.iron.autoRefine

/** A chart step's style is decoded with the wire, so a bad one fails the build
  * with its own reason.
  */
class ChartStyleSuite extends munit.FunSuite {

  private def stepOf(json: String): Either[String, Step] =
    decode[Step](json).left.map(_.getMessage)

  private def refusal(json: String): String =
    stepOf(json).swap.getOrElse(fail("expected a decoding failure"))

  test("a chart step decodes into its style, absent fields defaulted") {
    assertEquals(
      stepOf("""{"kind": "Chart", "style": {"width": 320, "unit": "°C"}}"""),
      Right(Step.Chart(ChartStyle(width = 320, unit = Some("°C"))))
    )
    assertEquals(stepOf("""{"kind": "Chart"}"""), Right(Step.Chart()))
  }

  test("a bad size is refused with the chart's own reason") {
    val wrongType = refusal(
      """{"kind": "Chart", "style": {"width": "wide"}}"""
    )
    assert(wrongType.contains("width"), clue = wrongType)
    val zero = refusal("""{"kind": "Chart", "style": {"height": 0}}""")
    assert(zero.contains("chart size must be positive"), clue = zero)
  }

  test("an unknown step is refused") {
    val e = refusal("""{"kind": "Sparkline"}""")
    assert(e.contains("Sparkline"), clue = e)
  }

  test("a CEL step and a format decode beside a chart") {
    assertEquals(
      stepOf("""{"kind": "Cel", "src": "state"}"""),
      Right(Step.Cel("state"))
    )
    assertEquals(
      stepOf("""{"kind": "Duration", "scale": 60}"""),
      Right(Step.Duration(60))
    )
  }
}
