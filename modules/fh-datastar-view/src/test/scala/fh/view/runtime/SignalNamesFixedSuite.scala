package fh.view.runtime

import fh.view.model.{
  CardDef,
  Dashboard,
  NodeId,
  Region,
  SignalBind,
  SlotSource
}
import fh.view.testkit.DashboardBuilders.{col, component, lit, st}
import io.circe.Json

/** For a node with a constant subject, its signal names are fixed; only values
  * move. True by construction in `Renderer.buildPlan`, asserted anyway because
  * `Datastar.SignalSeed` bakes the names into a skeleton, and drift would nest
  * the wrong paths in a well-formed attribute, silently killing every binding.
  * Dynamic subjects are excluded: [[DynamicSubjectSuite]].
  */
class SignalNamesFixedSuite extends munit.FunSuite {

  private val cards = Map(
    "col" -> CardDef(
      "<div>{{#children}}{{{html}}}{{/children}}</div>",
      regions = Map("children" -> Region())
    ),
    "card" -> CardDef(
      """<span {{{value__bind}}}>{{value}}</span>""",
      slots = List("value")
    ),
    "two" -> CardDef(
      """<span {{{a__bind}}} {{{b__bind}}}>{{a}}{{b}}</span>""",
      slots = List("a", "b")
    )
  )

  private def sig(entity: String, transform: String = "state") =
    SlotSource(Some(entity), transform, signal = Some(SignalBind.Text))

  private val dash = Dashboard(
    cards = cards,
    card = col(
      component("card", "value" -> sig("sensor.a")),
      component(
        "two",
        "a" -> sig("sensor.a", "attr.brightness"),
        "b" -> sig("sensor.b")
      ),
      component("card", "value" -> lit("constant"))
    )
  )

  private val renderer = Renderer.create(dash)

  /** States chosen to move everything a name could conceivably be derived from
    * — the state itself, an attribute, presence, and the entity vanishing.
    */
  private val worlds: List[Map[String, EntityState]] = List(
    Map(
      "sensor.a" -> st("sensor.a", "warm", "brightness" -> Json.fromInt(10)),
      "sensor.b" -> st("sensor.b", "on")
    ),
    Map(
      "sensor.a" -> st("sensor.a", "cold", "brightness" -> Json.fromInt(200)),
      "sensor.b" -> st("sensor.b", "off")
    ),
    Map(
      "sensor.a" -> st("sensor.a", "", "brightness" -> Json.Null),
      "sensor.b" -> st("sensor.b", "on")
    ),
    // The entity gone entirely — the paint still has to name the same signals.
    Map("sensor.b" -> st("sensor.b", "on")),
    Map.empty
  )

  test("a node's signal NAMES do not depend on entity state") {
    // Every node in the tree, asked for its own signals under each world.
    val ids = List("c", "c_0", "c_1", "c_2").map(NodeId.derived)
    val perWorld = worlds.map { states =>
      ids.map(id => id -> renderer.signalsFor(id, states).keySet).toMap
    }
    val first = perWorld.head
    perWorld.tail.zipWithIndex.foreach { case (names, i) =>
      assertEquals(
        names,
        first,
        clue = s"world ${i + 1} names a different signal set than world 0"
      )
    }
    assert(first.values.exists(_.nonEmpty), "the fixture declares no signals")
  }

  test("the rendered seed is what signalsAttr would have produced") {
    import RendererTestOps.*
    // The document takes the precomputed path, so its bytes must equal the
    // general build in every world; wrong names would still be well-formed.
    val ids = List("c", "c_0", "c_1", "c_2").map(NodeId.derived)
    worlds.foreach { states =>
      val html = renderer.renderBody(states)
      ids.foreach { id =>
        val expected = Datastar.signalsAttr(renderer.signalsFor(id, states))
        if (expected.nonEmpty) {
          assert(
            html.contains(expected),
            clue = s"$id: document seed differs from signalsAttr\n" +
              s"expected: $expected\nin: $html"
          )
        }
      }
    }
  }
}
