package fh.view.runtime

import fh.view.fetch.FetchSnapshot
import fh.view.model.*
import io.circe.Json
import io.circe.parser.decode

/** The fast tier beyond the slot's own entity: a condition fills a slot as
  * directly as it decides a `c.iff`, and any read may name another entity.
  */
class SimpleReadsSuite extends munit.FunSuite {

  private val card =
    CardDef("""<b {{{v__bind}}}>{{v}}</b>""", slots = List("v"))

  private def slot(t: Transform.Simple, bool: Boolean = false) = SlotSource(
    transform = t,
    signal = Some(SignalBind.Text),
    `type` = if (bool) SlotType.Bool else SlotType.Text,
    bypassUnavailable = false
  )

  private def dashboard(v: SlotSource, subject: Boolean = true) = Dashboard(
    cards = Map("c" -> card),
    card = LayoutNode.Component(
      card = "c",
      slots = Map("v" -> v) ++
        Option.when(subject)(
          "entity_id" -> SlotSource(literal = Some("sensor.a"))
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

  test("a condition is a slot's transform, woken by the entity it names") {
    val d = dashboard(slot(doorOpen, bool = true))
    assertEquals(d.validate(), Nil)
    assert(Renderer.create(d).componentsFor("binary_sensor.door").nonEmpty)
    assertEquals(value(d, states("on")), List[SlotValue](true))
    assertEquals(value(d, states("off")), List[SlotValue](false))
  }

  test("a condition naming no entity reads the slot's") {
    val d = dashboard(
      slot(Predicate.Cmp("state", Op.Gt, Json.fromInt(0)), bool = true)
    )
    assertEquals(d.validate(), Nil)
    assertEquals(value(d, states("on")), List[SlotValue](true))
  }

  test("a value read may name another entity, which wakes the slot") {
    val d = dashboard(slot(Transform.Simple.Attr("battery", Some("sensor.b"))))
    assertEquals(d.validate(), Nil)
    assert(Renderer.create(d).componentsFor("sensor.b").nonEmpty)
    assertEquals(value(d, states("on")), List[SlotValue]("80"))
    // One the store does not hold is the shape's absent form.
    assertEquals(
      Transform.runSimple(
        Transform.Simple.Percent("brightness", 0, 255, Some("light.gone")),
        EntityState.none,
        Map.empty
      ),
      "0 %"
    )
  }

  test("an attribute with its unit, total over a number and an absence") {
    def read(attrs: (String, Json)*) = Transform.runSimple(
      Transform.Simple.AttrUnit("temperature"),
      EntityState("sensor.t", "on", attrs.toMap),
      Map.empty
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

  test("a match reads any property, and shows an unmatched read as itself") {
    val labels = Transform.Simple.Match(
      Map("rainbow" -> "Rainbow"),
      property = "attr:effect"
    )
    def read(effect: String) = Transform.runSimple(
      labels,
      EntityState("light.l", "on", Map("effect" -> Json.fromString(effect))),
      Map.empty
    )
    assertEquals(read("rainbow"), "Rainbow")
    assertEquals(read("candle"), "candle")
    assertNotEquals(
      Transform.Simple.key(labels),
      Transform.Simple.key(labels.copy(property = "state"))
    )
    assertNotEquals(
      Transform.Simple.key(labels),
      Transform.Simple.key(labels.copy(otherwise = Some("candle")))
    )
  }

  test("a named entity, or a condition, keys apart from the slot's own read") {
    val keys = List[Transform.Simple](
      Transform.Simple.State(),
      Transform.Simple.State(Some("sensor.b")),
      doorOpen,
      doorOpen.copy(entity = None)
    ).map(Transform.Simple.key)
    assertEquals(keys.distinct.size, keys.size, clue = keys)
  }

  test(
    "the build refuses a once slot naming another entity, and a free condition with no entity"
  ) {
    val once = dashboard(
      slot(Transform.Simple.State(Some("sensor.b"))).copy(reads = Reads.Once)
    ).validate().mkString("\n")
    assert(
      once.contains("reads another entity but is read 'once'"),
      clue = once
    )
    val free = dashboard(
      slot(Predicate.Cmp("state", Op.Eq, Json.fromString("on")), bool = true),
      subject = false
    ).validate().mkString("\n")
    assert(free.contains("compares an entity it does not name"), clue = free)
  }

  test("the wire carries a condition and a named read as a transform") {
    assertEquals(
      decode[SlotSource](
        """{"transform": {"kind": "Cmp", "property": "state", "op": "eq", "value": "on", "entity": "binary_sensor.door"}}"""
      ).map(_.transform),
      Right(doorOpen)
    )
    assertEquals(
      decode[SlotSource](
        """{"transform": {"kind": "Op", "op": "Attr", "attribute": "battery", "entity": "sensor.b"}}"""
      ).map(_.transform),
      Right(Transform.Simple.Attr("battery", Some("sensor.b")))
    )
  }
}
