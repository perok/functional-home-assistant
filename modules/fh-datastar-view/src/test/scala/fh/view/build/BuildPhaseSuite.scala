package fh.view.build

import fh.view.model.{CardDef, Dashboard, LayoutNode}
import io.circe.parser
import io.circe.syntax.*

class BuildPhaseSuite extends munit.FunSuite {

  private def dynamics(node: LayoutNode): List[LayoutNode.Dynamic] =
    node match {
      case c: LayoutNode.Component => c.children.flatMap(dynamics)
      case d: LayoutNode.Dynamic   => List(d)
    }

  test("DataDump.transform keys lists by sanitized id (no '*' member)") {
    val raw = parser
      .parse("""
        {
          "areas": [],
          "floors": [],
          "entities": [
            { "entity_id": "sensor.temp", "friendly_name": "Temp", "domain": "sensor" },
            { "entity_id": "light.kitchen", "friendly_name": "Kitchen", "domain": "light" }
          ]
        }
      """)
      .toOption
      .get

    val entities = DataDump.transform(raw).hcursor.downField("entities")

    // dotless, sanitized keys
    assert(entities.downField("sensor_temp").succeeded)
    assert(entities.downField("light_kitchen").succeeded)
    assertEquals(
      entities.downField("sensor_temp").get[String]("friendly_name").toOption,
      Some("Temp")
    )

    // the clunky "*" all-ids member is gone; keys are exactly the entities
    assertEquals(
      entities.keys.map(_.toSet),
      Some(Set("sensor_temp", "light_kitchen"))
    )
  }

  test(
    "JsonnetBuild evaluates the example dashboard into a valid Dashboard model"
  ) {
    val resources =
      os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards"

    val tmp = os.temp.dir()
    os.copy.into(resources / "components.libsonnet", tmp)
    os.copy.into(resources / "dashboard.jsonnet", tmp)

    // Minimal fake dump standing in for the build-phase HA dump.
    val fakeDump = io.circe.Json
      .obj(
        "areas" -> io.circe.Json.obj(),
        "floors" -> io.circe.Json.obj(),
        "entities" -> io.circe.Json.obj(
          "sensor_temp" -> io.circe.Json.obj(
            "entity_id" -> "sensor.temp".asJson,
            "friendly_name" -> "Temperature".asJson,
            "domain" -> "sensor".asJson
          ),
          // The example dashboard statically references this entity by name.
          "sensor_ams_1a4e_p" -> io.circe.Json.obj(
            "entity_id" -> "sensor.ams_1a4e_p".asJson,
            "friendly_name" -> "Power".asJson,
            "domain" -> "sensor".asJson
          )
        )
      )
    os.write(tmp / "dump.libsonnet", fakeDump.spaces2)

    val result = JsonnetBuild.eval(tmp, "dashboard.jsonnet")
    assert(result.isRight, clue = result)

    // `decode`'s normalization lets `c.row(child)` (single child, no array) work.
    val dashboard = result.flatMap(
      DashboardBuild.normalizeChildren(_).as[Dashboard].left.map(_.getMessage)
    )
    assert(dashboard.isRight, clue = dashboard)

    val d = dashboard.toOption.get
    // Shared card library is referenced by name (not baked per entity).
    assert(d.cards.contains("stateCard"), clue = d.cards.keySet)
    assert(d.cards.contains("button"), clue = d.cards.keySet)
    assert(d.cards.contains("slider"), clue = d.cards.keySet)
    // Recursive layout: top-level container (column) with exactly one dynamic
    // group somewhere inside.
    assertEquals(
      d.card.asInstanceOf[LayoutNode.Component].card,
      "fhcol"
    )
    assertEquals(dynamics(d.card).size, 1)
    // The composed dashboard is internally consistent.
    assertEquals(d.validate, Nil)
  }

  test("normalizeChildren wraps a single (non-array) child into a list") {
    val single = parser
      .parse("""{ "kind":"component", "card":"fhrow",
                 "children": { "kind":"component", "card":"x" } }""")
      .toOption
      .get
    val fixed = DashboardBuild.normalizeChildren(single)
    val kids = fixed.hcursor.downField("children")
    assert(kids.values.nonEmpty, clue = fixed) // now an array
    assertEquals(kids.downN(0).get[String]("card").toOption, Some("x"))

    // an existing array is left as-is
    val arr = parser
      .parse("""{ "children": [ { "card":"a" }, { "card":"b" } ] }""")
      .toOption
      .get
    assertEquals(
      DashboardBuild.normalizeChildren(arr).hcursor
        .downField("children")
        .values
        .map(_.size),
      Some(2)
    )
  }

  test("validate reports a component missing a required card input") {
    val d = Dashboard(
      cards = Map(
        "card" -> CardDef("""<div id="{{id}}">{{label}}</div>""", List("id", "label"))
      ),
      // `id` is backend-injected; only "label" is missing here.
      card = LayoutNode.Component(card = "card")
    )
    val errs = d.validate
    assert(errs.exists(_.contains("label")), clue = errs)
    assert(!errs.exists(_.contains("missing inputs: id")), clue = errs)
  }

  test("validate reports a reference to an unknown card") {
    val d = Dashboard(
      cards = Map.empty,
      card = LayoutNode.Component("nope")
    )
    assert(d.validate.exists(_.contains("unknown card")), clue = d.validate)
  }
}
