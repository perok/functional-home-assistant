package fh.view.runtime

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
    signal = Some(SignalBind.Text),
    `type` = if (bool) SlotType.Bool else SlotType.Text
  )

  private def condition(p: Predicate) = ValueExpr(Some(Value.Condition(p)))

  private def reading(
      of: Option[String],
      part: Part = Part.State,
      reads: String = Reads.Live
  )(steps: Step*) =
    ValueExpr(
      Some(Value.Entity(of, part, reads, bypassUnavailable = false)),
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
    Predicate.Cmp(
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

  /** An operand read off `e`, as the renderer reads one off its entity. */
  private def over(e: EntityState): Value => Any = {
    case Value.Entity(_, part, _, _) => Transform.read(part, e)
    case Value.Literal(l)            => l.raw
    case _                           => ""
  }

  private def whole(n: Long) = Value.Literal(Lit.Whole(n))

  test("a condition is a slot's source, woken by the entity it names") {
    val d = dashboard(slot(condition(doorOpen), bool = true))
    assertEquals(d.validate(), Nil)
    assert(Renderer.create(d).componentsFor("binary_sensor.door").nonEmpty)
    assertEquals(value(d, states("on")), List[SlotValue](true))
    assertEquals(value(d, states("off")), List[SlotValue](false))
  }

  test("a condition naming no entity reads the card's subject") {
    val d = dashboard(
      slot(condition(Predicate.Cmp("state", Op.Gt, Json.fromInt(0))), true)
    )
    assertEquals(d.validate(), Nil)
    assertEquals(value(d, states("on")), List[SlotValue](true))
  }

  test("a value read may name another entity, which wakes the slot") {
    val d = dashboard(slot(reading(Some("sensor.b"), Part.Attr("battery"))()))
    assertEquals(d.validate(), Nil)
    assert(Renderer.create(d).componentsFor("sensor.b").nonEmpty)
    assertEquals(value(d, states("on")), List[SlotValue]("80"))
    // One the store does not hold is the step's absent form.
    assertEquals(
      Transform.format(
        Step.Percent(whole(0), whole(255)),
        Transform.read(Part.Attr("brightness"), EntityState.none),
        over(EntityState.none)
      ),
      "0 %"
    )
  }

  test("an attribute with its unit, total over a number and an absence") {
    def read(attrs: (String, Json)*) =
      val e = EntityState("sensor.t", "on", attrs.toMap)
      Transform.format(
        Step.WithUnit(Value.Entity(None, Part.Attr("unit_of_measurement"))),
        Transform.read(Part.Attr("temperature"), e),
        over(e)
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
      Transform.format(labels, Transform.read(Part.Attr("effect"), e), over(e))
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
      ).operandEntities,
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
      condition(doorOpen.copy(entity = None))
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
        condition(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
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

  test("the wire carries a condition and a named read as a source") {
    assertEquals(
      decode[ValueExpr](
        """{"source": {"kind": "Condition", "predicate": {"kind": "Cmp", "property": "state", "op": "eq", "value": "on", "entity": "binary_sensor.door"}}}"""
      ).map(_.source),
      Right(Some(Value.Condition(doorOpen)))
    )
    assertEquals(
      decode[ValueExpr](
        """{"source": {"kind": "Entity", "of": "sensor.b", "part": {"kind": "Attr", "name": "battery"}}}"""
      ).map(_.source),
      Right(Some(Value.Entity(Some("sensor.b"), Part.Attr("battery"))))
    )
    def matchWith(otherwise: String) = decode[ValueExpr](
      s"""{"steps": [{"kind": "Match", "cases": {}, "otherwise": $otherwise}]}"""
    ).map(_.steps)
    assertEquals(
      matchWith("""{"kind": "Entity"}"""),
      Right(List(Step.Match(Map.empty, Value.Entity())))
    )
    assert(matchWith("""{"kind": "Entity", "off": "x"}""").isLeft)
    assert(
      decode[ValueExpr](
        """{"steps": [{"kind": "Match", "cases": {}}]}"""
      ).isLeft
    )
  }
}
