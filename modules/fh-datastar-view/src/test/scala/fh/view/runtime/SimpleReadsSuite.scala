package fh.view.runtime

import fh.view.testkit.Conds

import fh.view.fetch.FetchSnapshot
import fh.view.model.*
import io.circe.Json
import io.circe.parser.decode

/** The fast tier beyond the card's own entity: a condition fills a slot as
  * directly as it decides a `c.iff`, and any read may name another entity.
  */
class SimpleReadsSuite extends munit.FunSuite {

  private val card =
    CardDef("""<b {{{v.bind}}}>{{v}}</b>""")

  private def slot(expr: ValueExpr, bool: Boolean = false) = TemplateSlot(
    expr,
    signal = Some(if (bool) SignalBind.Attr("hidden") else SignalBind.Text),
    `type` = if (bool) SlotType.Bool else SlotType.Text
  )

  private def condition(p: ValueExpr) = p

  private def reading(
      of: Option[String],
      part: Part = Part.State,
      reads: Reads = Reads.Live
  )(steps: Step*) =
    ValueExpr(
      Value.Entity(of, part, reads, bypassUnavailable = false),
      steps.toList
    )

  private def dashboard(v: TemplateSlot, subject: Boolean = true) = Dashboard(
    cards = Map("c" -> card),
    card = LayoutNode.Component(
      card = "c",
      templateSlots = Map("v" -> v) ++
        Option.when(subject)(
          "entity_id" -> TemplateSlot(ValueExpr.literal("sensor.a"))
        )
    )
  )

  private val doorOpen =
    Conds.cmp(
      "state",
      Op.Eq,
      Json.fromString("on"),
      Some("binary_sensor.door")
    )

  private def states(door: String, battery: Json = Json.fromInt(80)) = Map(
    "sensor.a" -> EntityState("sensor.a", "1", Map.empty),
    "binary_sensor.door" -> EntityState("binary_sensor.door", door, Map.empty),
    "sensor.b" -> EntityState("sensor.b", "x", Map("battery" -> battery))
  )

  private def value(d: Dashboard, st: Map[String, EntityState]) =
    Renderer
      .create(d)
      .signalsFor(LayoutNode.rootId("", d.card), st, FetchSnapshot.empty)
      .values
      .toList

  /** One step over `part` of `e`, as the evaluator runs it. */
  private def run(part: Part, step: Step, e: EntityState): Any =
    Conds.run(ValueExpr(Value.Entity(None, part), List(step)))(
      e,
      Map(e.entityId -> e),
      Evaluator.noFetch
    )

  private def whole(n: Long): Value.Operand = Value.Literal(Lit.Whole(n))

  test("a condition is a slot's source, woken by the entity it names") {
    val d = dashboard(slot(condition(doorOpen), bool = true))
    assertEquals(d.validate(), Nil)
    assert(Renderer.create(d).componentsFor("binary_sensor.door").nonEmpty)
    assertEquals(value(d, states("on")), List[SlotValue](true))
    assertEquals(value(d, states("off")), List[SlotValue](false))
  }

  test("a list CEL built is a list to every step that takes one") {
    // A Java list read as no value in each, though `validate` typed it a list.
    def text(cel: String, step: Step) = dashboard(
      slot(reading(Some("binary_sensor.door"), Part.Whole)(Step.Cel(cel), step))
    )
    val count = text("['a', 'b']", Step.Count)
    assertEquals(count.validate(), Nil)
    assertEquals(value(count, states("on")), List[SlotValue]("2"))
    val leader = text("['sensor.b', 'sensor.a']", Step.First())
    assertEquals(leader.validate(), Nil)
    assertEquals(value(leader, states("on")), List[SlotValue]("sensor.b"))
  }

  test("an and/or over a list CEL built decides as over a list source") {
    // Only a list source's run is fused; a CEL's list is whole when it lands.
    def over(step: Step) = dashboard(
      slot(
        reading(Some("binary_sensor.door"), Part.Whole)(
          Step.Cel("[value.state == 'on', value.state == 'open']"),
          step
        ),
        bool = true
      )
    )
    for (step, door, holds) <- List(
        (Step.AnyOf, "on", true),
        (Step.AnyOf, "off", false),
        (Step.AllOf, "on", false),
        (Step.AllOf, "off", false)
      )
    do
      assertEquals(over(step).validate(), Nil)
      assertEquals(
        value(over(step), states(door)),
        List[SlotValue](holds),
        clue = (step, door)
      )
  }

  test("a condition naming no entity reads the card's subject") {
    val d = dashboard(
      slot(condition(Conds.cmp("state", Op.Gt, Json.fromInt(0))), true)
    )
    assertEquals(d.validate(), Nil)
    assertEquals(value(d, states("on")), List[SlotValue](true))
  }

  test("a value read may name another entity, which wakes the slot") {
    val d = dashboard(slot(reading(Some("sensor.b"), Part.Attr("battery"))()))
    assertEquals(d.validate(), Nil)
    assert(Renderer.create(d).componentsFor("sensor.b").nonEmpty)
    assertEquals(value(d, states("on")), List[SlotValue]("80"))
    // One the store does not hold has no value.
    assertEquals(
      run(
        Part.Attr("brightness"),
        Step.Percent(whole(0), whole(255)),
        EntityState.none
      ),
      null
    )
  }

  test("an attribute with its unit, total over a number and an absence") {
    def read(attrs: (String, Json)*) =
      val e = EntityState("sensor.t", "on", attrs.toMap)
      // As the slot shows it: an absent value is null, shown as nothing.
      Steps.text(
        run(
          Part.Attr("temperature"),
          Step.WithUnit(Value.Entity(None, Part.Attr("unit_of_measurement"))),
          e
        )
      )
    val unit = "unit_of_measurement" -> Json.fromString("°C")
    assertEquals(
      read("temperature" -> Json.fromDoubleOrNull(21.5), unit),
      "21.5 °C"
    )
    assertEquals(read("temperature" -> Json.fromString("21"), unit), "21 °C")
    assertEquals(read("temperature" -> Json.fromInt(21)), "21")
    assertEquals(read(unit), "")
  }

  test("a match reads any part, and its otherwise may be a read") {
    val labels = Step.Match(
      Map("rainbow" -> Lit.Text("Rainbow")),
      otherwise = Value.Entity(None, Part.Attr("effect"))
    )
    def read(effect: String) =
      val e =
        EntityState("light.l", "on", Map("effect" -> Json.fromString(effect)))
      run(Part.Attr("effect"), labels, e)
    assertEquals(read("rainbow"), "Rainbow")
    assertEquals(read("candle"), "candle")
    val byEffect = reading(None, Part.Attr("effect"))(labels)
    assertNotEquals(byEffect.key, reading(None)(labels).key)
    assertNotEquals(
      byEffect.key,
      reading(None, Part.Attr("effect"))(
        labels.copy(otherwise = Value.Literal(Lit.Text("candle")))
      ).key
    )
    assertEquals(
      reading(None)(
        labels.copy(otherwise = Value.Entity(Some("sensor.b")))
      ).partEntities,
      List("sensor.b")
    )
  }

  test(
    "a named entity, or a condition, names a signal apart from the subject's"
  ) {
    def named(e: ValueExpr) =
      val b = BoundSlot.bindAt(
        Map.empty,
        Placement(None, SlotType.Text),
        Some("sensor.a")
      )(e)
      (b.entityId, b.valueKey)
    val names = List(
      reading(None)(),
      reading(Some("sensor.b"))(),
      condition(doorOpen),
      condition(Conds.cmp("state", Op.Eq, Json.fromString("on")))
    ).map(named)
    assertEquals(names.distinct.size, names.size, clue = names)
  }

  test(
    "the build refuses a once slot with an operand on another entity, and a free condition with no entity"
  ) {
    val once = dashboard(
      slot(
        reading(None, reads = Reads.Once)(
          Step.WithUnit(Value.Entity(Some("sensor.b"), Part.Attr("unit")))
        )
      )
    ).validate().mkString("\n")
    assert(
      once.contains("reads another entity but is read 'once'"),
      clue = once
    )
    val free = dashboard(
      slot(
        condition(Conds.cmp("state", Op.Eq, Json.fromString("on"))),
        true
      ),
      subject = false
    ).validate().mkString("\n")
    assert(free.contains("compares an entity it does not name"), clue = free)
    val unnamed = dashboard(slot(reading(None)()), subject = false)
      .validate()
      .mkString("\n")
    assert(
      unnamed.contains(
        "slot 'v' reads the card's entity, and the card has none"
      ),
      clue = unnamed
    )
  }

  test("only a text slot's own read of a state or a whole entity bypasses") {
    def bypassing(part: Part): Value.Operand =
      Value.Entity(Some("sensor.a"), part, bypassUnavailable = true)
    def refused(v: TemplateSlot) =
      dashboard(v).validate().exists(_.contains(ValueExpr.MisplacedBypass))
    assert(!refused(slot(ValueExpr(bypassing(Part.State)))))
    assert(!refused(slot(ValueExpr(bypassing(Part.Whole)))))
    assert(refused(slot(ValueExpr(bypassing(Part.Attr("x"))))))
    assert(refused(slot(ValueExpr(bypassing(Part.State)), bool = true)))
    assert(
      refused(
        slot(reading(Some("sensor.a"))(Step.Suffix(bypassing(Part.State))))
      )
    )
    assert(
      ValueExpr(
        bypassing(Part.State),
        List(Step.Compare(Op.Eq, Value.Literal(Lit.Text("on"))))
      ).conditionError.contains(ValueExpr.MisplacedBypass)
    )
  }

  test("the wire carries a condition and a named read as a source") {
    assertEquals(
      decode[ValueExpr](
        """{"source": {"kind": "Entity", "of": "binary_sensor.door"}, "steps": [{"kind": "Compare", "op": "eq", "to": "on"}]}"""
      ),
      Right(doorOpen)
    )
    assertEquals(
      decode[ValueExpr](
        """{"source": {"kind": "Entity", "of": "sensor.b", "part": {"kind": "Attr", "name": "battery"}}}"""
      ).map(_.source),
      Right(Value.Entity(Some("sensor.b"), Part.Attr("battery")))
    )
    def matchWith(otherwise: String) = decode[ValueExpr](
      s"""{"source": {"kind": "Entity"}, "steps": [{"kind": "Match", "cases": {}, "otherwise": $otherwise}]}"""
    ).map(_.steps)
    assertEquals(
      matchWith("""{"kind": "Entity"}"""),
      Right(List(Step.Match(Map.empty, Value.Entity())))
    )
    assert(matchWith("""{"kind": "Entity", "off": "x"}""").isLeft)
    assert(
      decode[ValueExpr](
        """{"source": {"kind": "Entity"}, "steps": [{"kind": "Match", "cases": {}}]}"""
      ).isLeft
    )
  }

  test(
    "an operand is a literal or an entity read, in every step that has one"
  ) {
    // The evaluator reads no other shape, so the wire cannot carry one.
    val steps = List(
      """{"kind": "Compare", "op": "eq", "to": %s}""",
      """{"kind": "Prefix", "text": %s}""",
      """{"kind": "Suffix", "text": %s}""",
      """{"kind": "WithUnit", "unit": %s}""",
      """{"kind": "Percent", "min": %s, "max": 1}""",
      """{"kind": "Fill", "min": 0, "max": %s}""",
      """{"kind": "Match", "cases": {}, "otherwise": %s}"""
    )
    def step(shape: String, operand: String) =
      decode[ValueExpr](
        s"""{"source": {"kind": "Entity"}, "steps": [${shape.format(
            operand
          )}]}"""
      )
    for shape <- steps do
      assert(step(shape, "\"x\"").isRight, clue = shape)
      assert(step(shape, """{"kind": "Entity", "of": "a.b"}""").isRight, shape)
      for other <- List(
          """{"kind": "List", "itemType": "bool", "items": []}""",
          """{"kind": "Present", "candidates": []}""",
          """{"kind": "Cel", "src": "1"}""",
          """{"kind": "Fetch", "fetch": {"provider": "history", "entity": "a.b", "window": {"kind": "Raw", "span": 3600}}}"""
        )
      do
        assert(
          step(shape, other).left.exists(
            _.getMessage.contains("a literal or an entity read")
          ),
          clue = (shape, other)
        )
  }

  test(
    "`reads` and an order term's `dir` decode only as the wire spells them"
  ) {
    def reads(r: String) =
      decode[ValueExpr](s"""{"source": {"kind": "Entity", "reads": "$r"}}""")
        .map(_.source)
    for r <- Reads.values do
      assertEquals(reads(r.wire), Right(Value.Entity(reads = r)))
    assert(reads("Live").isLeft)
    def dir(d: String) =
      decode[SortTerm](
        s"""{"by": {"source": {"kind": "Entity"}}, "dir": "$d"}"""
      )
        .map(_.dir)
    for d <- SortTerm.Dir.values do assertEquals(dir(d.wire), Right(d))
    assert(dir("Desc").isLeft)
  }
}
