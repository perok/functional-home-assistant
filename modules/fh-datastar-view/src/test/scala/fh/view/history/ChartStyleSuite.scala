package fh.view.history

import fh.view.model.{SlotSource, Transform}
import io.circe.parser.decode

/** A chart stage's params are decoded with the wire, so a bad one fails the
  * build with its own reason.
  */
class ChartStyleSuite extends munit.FunSuite {

  private def transformOf(json: String): Either[String, Any] =
    decode[SlotSource](s"""{"transform": $json}""")
      .map(_.transform)
      .left
      .map(_.getMessage)

  private def refusal(json: String): String =
    transformOf(json).swap.getOrElse(fail("expected a decoding failure"))

  test("a chart stage decodes into its style, absent params defaulted") {
    assertEquals(
      transformOf(
        """{"stage": "chart", "params": {"width": 320, "unit": "°C"}}"""
      ),
      Right(Transform.Stage.Chart(ChartStyle(width = 320, unit = Some("°C"))))
    )
    assertEquals(
      transformOf("""{"stage": "chart"}"""),
      Right(Transform.Stage.Chart(ChartStyle()))
    )
  }

  test("passthrough decodes") {
    assertEquals(
      transformOf("""{"stage": "passthrough"}"""),
      Right(Transform.Stage.Passthrough)
    )
  }

  test("a bad size is refused with the stage's own reason") {
    // Not the Simple arm's "no discriminator 'kind'", which is what trying
    // each arm in turn reported.
    val wrongType = refusal(
      """{"stage": "chart", "params": {"width": "wide"}}"""
    )
    assert(wrongType.contains("width"), clue = wrongType)
    assert(!wrongType.contains("kind"), clue = wrongType)
    val zero = refusal("""{"stage": "chart", "params": {"height": 0}}""")
    assert(zero.contains("chart size must be positive"), clue = zero)
  }

  test("an unknown stage is refused, not read as a simple transform") {
    val e = refusal("""{"stage": "sparkline"}""")
    assert(e.contains("sparkline"), clue = e)
    assert(!e.contains("kind"), clue = e)
  }

  test("a simple transform and a CEL string still decode") {
    assertEquals(transformOf(""""state""""), Right("state"))
    assertEquals(
      transformOf("""{"kind": "value", "op": "state"}"""),
      Right(Transform.Simple.State)
    )
  }
}
