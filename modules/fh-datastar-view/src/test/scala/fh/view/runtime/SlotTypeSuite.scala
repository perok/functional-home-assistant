package fh.view.runtime

import fh.view.testkit.Slots

import fh.view.testkit.TestCard
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
  SignalId,
  SlotRead,
  SlotType,
  SlotValue,
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
      cards = Map("c" -> TestCard(s"""<div id="{{id}}">$hole</div>""")),
      card = LayoutNode.Component(
        card = "c",
        slots = Slots.values(Map("x" -> slot))
      )
    ).validate()

  private def literalFor(t: SlotType) =
    ValueExpr(Value.Literal(t match
      case SlotType.Bool   => Lit.Bool(true)
      case SlotType.Number => Lit.Whole(1)
      case _               => Lit.Text("a")))

  private val holes = Map(
    "escaped" -> "{{x}}",
    "raw" -> "{{{x}}}",
    "section" -> "{{#x}}on{{/x}}"
  )

  test("a number is a number at both ends, a whole one without its .0") {
    val sig = SignalId.derived("_e.light.k.t1")
    assertEquals(Datastar.jsValue(128.0), "128")
    assertEquals(Datastar.jsValue(0.5), "0.5")
    assert(
      Datastar.signalsAttr(Map(sig -> 128.0)).contains("t1: 128}"),
      clue = Datastar.signalsAttr(Map(sig -> 128.0))
    )
    assertEquals(SlotValue.text(128.0), "128")
  }

  test("text pasted into a Datastar expression is refused, and nothing else") {
    val text = TemplateSlot(ValueExpr.literal("it's"))
    val inClick = """<b data-on:click="alert('{{x}}')"></b>"""
    assert(
      errors(inClick, text).exists(_.contains("inside a Datastar expression")),
      clue = errors(inClick, text)
    )
    assertEquals(
      errors("""<b data-on:click="alert({{{x.jsValue}}})"></b>""", text),
      Nil
    )
    assertEquals(errors("""<b title="{{x}}"></b>""", text), Nil)
    assertEquals(
      errors(
        inClick,
        TemplateSlot(literalFor(SlotType.Number), `type` = SlotType.Number)
      ),
      Nil
    )
  }

  test("each slot type is placed only in the holes it names") {
    val allowed = Map(
      SlotType.Text -> Set("escaped", "section"),
      SlotType.TextRaw -> Set("raw", "section"),
      SlotType.Bool -> Set("section"),
      SlotType.Number -> Set("escaped", "section")
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
      assertEquals(
        ValueType.fits(out, SlotType.Number),
        Set(ValueType.Int, ValueType.Real, ValueType.Dyn)(out),
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
      cards = Map("c" -> TestCard("""<div id="{{id}}">{{x}}</div>""")),
      card = LayoutNode.Component(
        card = "c",
        slots = Slots.values(
          Map(
            "x" -> TemplateSlot(
              ValueExpr(Value.Fetch(fetch), List(Step.Json, Step.JsonText))
            )
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
        Viewing(
          Selections.none,
          FetchSnapshot.of(Map(read -> Staged(1L, """{"points":[]}""")))
        )
      )
      .html
    assert(html.contains("&quot;points&quot;"), clue = html)
    assert(!html.contains("""{"points""""), clue = html)
  }
}
