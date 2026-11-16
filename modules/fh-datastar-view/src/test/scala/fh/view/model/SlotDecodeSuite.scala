package fh.view.model

import io.circe.parser.decode

/** A slot picks its case by `kind`, a value's fields flat beside it, and a
  * field neither case knows is refused rather than dropped.
  */
class SlotDecodeSuite extends munit.FunSuite {

  test("a value slot's fields sit beside its kind") {
    assertEquals(
      decode[Slot]("""{"kind": "Value", "source": "hi"}"""),
      Right(Slot.Value(TemplateSlot(ValueExpr.literal("hi"))))
    )
  }

  test("a nodes slot holds its fill") {
    assertEquals(
      decode[Slot](
        """{"kind": "Nodes", "fill": {"kind": "Nodes", "choice": []}}"""
      ),
      Right(Slot.Nodes(Fill.Nodes(Choice.Fixed(Nil))))
    )
  }

  test("a field the slot's kind does not have is refused, in either kind") {
    assert(decode[Slot]("""{"kind": "Value", "sourc": "hi"}""").isLeft)
    assert(
      decode[Slot](
        """{"kind": "Nodes", "fill": {"kind": "Nodes", "choice": []}, "source": "x"}"""
      ).isLeft
    )
    assert(decode[Slot]("""{"kind": "Other"}""").isLeft)
  }
}
