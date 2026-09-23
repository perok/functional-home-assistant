package fh.view.history

import fh.view.model.{SlotSource, Transform}
import io.circe.parser.decode

/** A chart stage's params are parsed where the wire is decoded, so a bad one
  * fails the build with its own reason.
  */
class ChartStyleSuite extends munit.FunSuite {

  private def transformOf(json: String): Either[String, Any] =
    decode[SlotSource](s"""{"transform": $json}""")
      .map(_.transform)
      .left
      .map(_.getMessage)

  test("a chart stage decodes into its style, absent params defaulted") {
    assertEquals(
      transformOf(
        """{"stage": "chart", "params": {"width": "320", "unit": "°C"}}"""
      ),
      Right(Transform.Stage.Chart(ChartStyle(width = 320, unit = Some("°C"))))
    )
    assertEquals(
      transformOf("""{"stage": "chart"}"""),
      Right(Transform.Stage.Chart(ChartStyle()))
    )
  }

  test("passthrough decodes with or without an empty params") {
    assertEquals(
      transformOf("""{"stage": "passthrough"}"""),
      Right(Transform.Stage.Passthrough)
    )
    assertEquals(
      transformOf("""{"stage": "passthrough", "params": {}}"""),
      Right(Transform.Stage.Passthrough)
    )
  }

  test("a non-numeric size is refused with the stage's own reason") {
    val e =
      transformOf("""{"stage": "chart", "params": {"width": "wide"}}""").swap
        .getOrElse(fail("expected a decoding failure"))
    assert(e.contains("non-numeric width 'wide'"), clue = e)
  }

  test("an unknown stage is refused, not read as a simple transform") {
    val e = transformOf("""{"stage": "sparkline"}""").swap
      .getOrElse(fail("expected a decoding failure"))
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
