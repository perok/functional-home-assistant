package fh.view.build

import fh.view.model.{Dashboard, LayoutNode, TemplateDef}
import io.circe.parser
import io.circe.syntax.*

class BuildPhaseSuite extends munit.FunSuite {

  private def dynamics(node: LayoutNode): List[LayoutNode.Dynamic] =
    node match {
      case c: LayoutNode.Component => c.children.flatMap(dynamics)
      case d: LayoutNode.Dynamic   => List(d)
    }

  test("DataDump.transform keys lists by sanitized id and adds a '*' member") {
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

    // "*" lists the original ids
    val all = entities.get[List[String]]("*").toOption.get.toSet
    assertEquals(all, Set("sensor.temp", "light.kitchen"))
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
        "areas" -> io.circe.Json.obj("*" -> List.empty[String].asJson),
        "floors" -> io.circe.Json.obj("*" -> List.empty[String].asJson),
        "entities" -> io.circe.Json.obj(
          "sensor_temp" -> io.circe.Json.obj(
            "entity_id" -> "sensor.temp".asJson,
            "friendly_name" -> "Temperature".asJson,
            "domain" -> "sensor".asJson
          ),
          "*" -> List("sensor.temp").asJson
        )
      )
    os.write(tmp / "dump.libsonnet", fakeDump.spaces2)

    val result = JsonnetBuild.eval(tmp, "dashboard.jsonnet")
    assert(result.isRight, clue = result)

    val dashboard = result.flatMap(_.as[Dashboard].left.map(_.getMessage))
    assert(dashboard.isRight, clue = dashboard)

    val d = dashboard.toOption.get
    // Shared template library is referenced by name (not baked per entity).
    assert(d.templates.contains("stateCard"), clue = d.templates.keySet)
    assert(d.templates.contains("button"), clue = d.templates.keySet)
    assert(d.templates.contains("slider"), clue = d.templates.keySet)
    // Recursive layout: top-level container (column) with exactly one dynamic
    // group somewhere inside.
    assertEquals(
      d.layout.asInstanceOf[LayoutNode.Component].template,
      "fhcol"
    )
    assertEquals(dynamics(d.layout).size, 1)
    // The composed dashboard is internally consistent.
    assertEquals(d.validate, Nil)
  }

  test("validate reports a component missing a required template input") {
    val d = Dashboard(
      templates = Map(
        "card" -> TemplateDef("""<div id="{{id}}">{{label}}</div>""", List("id", "label"))
      ),
      // `id` is backend-injected; only "label" is missing here.
      layout = LayoutNode.Component(template = "card")
    )
    val errs = d.validate
    assert(errs.exists(_.contains("label")), clue = errs)
    assert(!errs.exists(_.contains("missing inputs: id")), clue = errs)
  }

  test("validate reports a reference to an unknown template") {
    val d = Dashboard(
      templates = Map.empty,
      layout = LayoutNode.Component("nope")
    )
    assert(d.validate.exists(_.contains("unknown template")), clue = d.validate)
  }
}
