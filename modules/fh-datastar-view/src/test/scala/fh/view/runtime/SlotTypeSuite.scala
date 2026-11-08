package fh.view.runtime

import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}
import fh.view.history.{ChartStyle, Windows}
import fh.view.model.{
  CardDef,
  Choice,
  Dashboard,
  FetchTemplate,
  LayoutNode,
  Lit,
  Op,
  SlotRead,
  SlotType,
  Step,
  TemplateSlot,
  Value,
  ValueExpr,
  ValueType
}

/** A slot's type names its hole, and what reaches it must fit the type. */
class SlotTypeSuite extends munit.FunSuite {

  private val fetch = FetchTemplate("sensor.t", Choice.Fixed(Windows.day))

  private def errors(hole: String, slot: TemplateSlot) =
    Dashboard(
      cards = Map("c" -> CardDef(s"""<div id="{{id}}">$hole</div>""")),
      card = LayoutNode.Component(
        card = "c",
        templateSlots = Map("x" -> slot)
      )
    ).validate()

  private def literalFor(t: SlotType) =
    ValueExpr(Value.Literal(if t == SlotType.Bool then Lit.Bool(true)
    else Lit.Text("a")))

  private val holes = Map(
    "escaped" -> "{{x}}",
    "raw" -> "{{{x}}}",
    "section" -> "{{#x}}on{{/x}}"
  )

  test("each slot type is placed only in the holes it names") {
    val allowed = Map(
      SlotType.Text -> Set("escaped", "section"),
      SlotType.TextRaw -> Set("raw", "section"),
      SlotType.Bool -> Set("section")
    )
    for
      t <- SlotType.values.toList
      (hole, tpl) <- holes
    do
      val errs = errors(tpl, TemplateSlot(literalFor(t), `type` = t))
      assertEquals(errs.isEmpty, allowed(t)(hole), clue = (t, hole, errs))
  }

  test("text, numbers, bools and dyn become text at the slot, nothing else") {
    val shown = Set(
      ValueType.Text,
      ValueType.Int,
      ValueType.Real,
      ValueType.Bool,
      ValueType.Dyn
    )
    val all = List(
      ValueType.Text,
      ValueType.Bool,
      ValueType.Int,
      ValueType.Real,
      ValueType.Dyn,
      ValueType.Entity,
      ValueType.TextRaw,
      ValueType.Json,
      ValueType.List(ValueType.Text),
      ValueType.Series
    )
    for out <- all do
      assertEquals(ValueType.fits(out, SlotType.Text), shown(out), clue = out)
      assertEquals(
        ValueType.fits(out, SlotType.TextRaw),
        shown(out) || out == ValueType.TextRaw,
        clue = out
      )
      assertEquals(
        ValueType.fits(out, SlotType.Bool),
        out == ValueType.Bool || out == ValueType.Dyn,
        clue = out
      )
  }

  test("a bool is shown as text, but a step that takes text refuses it") {
    val compared = Step.Compare(Op.Gt, Value.Literal(Lit.Whole(0)))
    def slot(steps: List[Step]) =
      TemplateSlot(ValueExpr(Value.Literal(Lit.Whole(3)), steps))
    assertEquals(errors("{{x}}", slot(List(compared))), Nil)
    val errs = errors(
      "{{x}}",
      slot(List(compared, Step.Prefix(Value.Literal(Lit.Text("is ")))))
    )
    assert(errs.exists(_.contains("after a bool")), clue = errs)
  }

  test("a fetch's series fits no slot, nor a valueWhile") {
    for
      t <- SlotType.values.toList
      tpl = if t == SlotType.TextRaw then "{{{x}}}" else holes("section")
    do
      val errs = errors(
        tpl,
        TemplateSlot(ValueExpr(Value.Fetch(fetch), Nil), `type` = t)
      )
      assert(errs.exists(_.contains("list of list of real")), clue = (t, errs))
    val gated = TemplateSlot(
      literalFor(SlotType.Text),
      valueWhile = Some(ValueExpr(Value.Fetch(fetch), Nil))
    )
    val errs = errors("{{x}}", gated)
    assert(errs.exists(_.contains("does not produce a bool")), clue = errs)
  }

  test("json is text only through jsonText, and a chart only raw") {
    def fetchSlot(steps: List[Step], t: SlotType = SlotType.Text) =
      TemplateSlot(ValueExpr(Value.Fetch(fetch), steps), `type` = t)
    val json = errors("{{x}}", fetchSlot(List(Step.Json)))
    assert(json.exists(_.contains("jsonText")), clue = json)
    assertEquals(
      errors("{{x}}", fetchSlot(List(Step.Json, Step.JsonText))),
      Nil
    )
    val chart = List(Step.Chart(ChartStyle()))
    val asText = errors("{{x}}", fetchSlot(chart))
    assert(asText.exists(_.contains("textRaw")), clue = asText)
    assertEquals(errors("{{{x}}}", fetchSlot(chart, SlotType.TextRaw)), Nil)
  }

  test("jsonText places the provider's answer, escaped") {
    val d = Dashboard(
      cards = Map("c" -> CardDef("""<div id="{{id}}">{{x}}</div>""")),
      card = LayoutNode.Component(
        card = "c",
        templateSlots = Map(
          "x" -> TemplateSlot(
            ValueExpr(Value.Fetch(fetch), List(Step.Json, Step.JsonText))
          )
        )
      )
    )
    val read =
      SlotRead(FetchRequest.History("sensor.t", Windows.day), Step.Json)
    val html = Renderer
      .create(d)
      .renderBodyTraced(
        Shared.of(Map.empty),
        Selections.none,
        FetchSnapshot.of(Map(read -> Staged(1L, """{"points":[]}""")))
      )
      .html
    assert(html.contains("&quot;points&quot;"), clue = html)
    assert(!html.contains("""{"points""""), clue = html)
  }
}
