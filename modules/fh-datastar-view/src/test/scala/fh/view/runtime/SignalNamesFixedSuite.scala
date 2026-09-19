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

/** The invariant the precomputed seed rests on: for a node whose SUBJECT is
  * constant, the set of signal NAMES it carries is fixed — only the values
  * move.
  *
  * `Renderer.buildPlan` derives each name from the node id, the slot, the
  * subject and the binding kind, none of which reads entity state, so this
  * should hold by construction. It is asserted anyway because
  * `Datastar.SignalSeed` bakes the names into a literal skeleton: if they ever
  * did drift, the seed would emit a well-formed attribute nesting the WRONG
  * paths, and every binding on the card would silently go dead.
  *
  * The dynamic-subject case is deliberately excluded — there the names
  * genuinely do move per paint, which is why `Renderer.resolveDirect` owns that
  * node and no seed is precomputed for it. [[DynamicSubjectSuite]] covers it.
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
    // End to end: the document takes the PRECOMPUTED path, so for every world
    // its bytes must equal building the attribute from the node's signals the
    // general way. A skeleton filled with the wrong names would still be
    // well-formed HTML, so nothing else would notice.
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
