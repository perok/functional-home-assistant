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

  test("a set fill is picked by its kind, a member a node or a choice of one") {
    val decoded = decode[Fill](
      """{"kind": "Set", "candidates": ["light.a", "light.b"],
        | "members": {
        |   "light.a": {"kind": "Component", "card": "tile"},
        |   "light.b": {"kind": "Chosen", "by": {"source": true},
        |     "cases": [{"is": true, "value": {"kind": "Component", "card": "tile"}}]}
        | },
        | "limit": 3, "of": "entity"}""".stripMargin
    )
    val tile = LayoutNode.Component("tile")
    assertEquals(
      decoded,
      Right(
        Fill.Set(
          candidates = List("light.a", "light.b"),
          members = Map(
            "light.a" -> Choice.Fixed(tile),
            "light.b" -> Choice.Chosen(
              ValueExpr
                .literal("true")
                .copy(source = Value.Literal(Lit.Bool(true))),
              List(Case(Lit.Bool(true), tile))
            )
          ),
          limit = Some(3)
        )
      )
    )
    assert(decode[Fill]("""{"kind": "Set", "candidate": []}""").isLeft)
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
