package fh.view.model

import io.circe.parser.decode

/** The decoder only decodes; folding a node's live cell entries and expression
  * values onto its slots is [[Dashboard.folded]]'s, which `validated` runs, so
  * a dashboard built by hand is folded as one decoded is.
  */
class FoldSuite extends munit.FunSuite {

  private val warm =
    SlotSource(
      entityId = Some("light.k"),
      transform = "state == 'on'",
      signal = Some(SignalBind.Class("warm"))
    )

  private def node(cell: Cell) =
    LayoutNode.Component(
      card = "c",
      slots = Map("label" -> SlotSource(transform = "string(n) + ' on'")),
      cell = Some(cell),
      values = Map("n" -> ExprValue.Whole(2))
    )

  private val byHand = Dashboard(
    cards = Map("c" -> CardDef("""<div id="{{id}}">{{label}}</div>""")),
    card = node(Cell(classWhen = Map("warm" -> warm)))
  )

  private def component(d: Dashboard) = d.card match
    case c: LayoutNode.Component => c
    case other                   => fail(s"not a component: $other")

  test("a dashboard built by hand is folded by validated") {
    val folded = component(
      byHand.validated().fold(e => fail(e.mkString("; ")), _.dashboard)
    )
    assertEquals(folded.slots.get(Dashboard.cellClassSlot("warm")), Some(warm))
    assertEquals(folded.cell.map(_.classWhen), Some(Map.empty))
    assertEquals(
      folded.slots.get("label").map(_.values),
      Some(Map("n" -> ExprValue.Whole(2)))
    )
  }

  test("decoding leaves the node as written, and folding is idempotent") {
    val wire =
      """{"cards": {"c": {"template": "<div id=\"{{id}}\">{{label}}</div>"}},
        | "card": {"kind": "component", "card": "c",
        |   "slots": {"label": {"transform": "string(n) + ' on'"}},
        |   "cell": {"classWhen": {"warm":
        |     {"entityId": "light.k", "transform": "state == 'on'",
        |      "signal": "class:warm"}}},
        |   "values": {"n": 2}}}""".stripMargin
    val decoded =
      decode[Dashboard](wire).fold(e => fail(e.getMessage), identity)
    assertEquals(decoded, byHand)
    assertEquals(decoded.folded.folded, decoded.folded)
  }
}
