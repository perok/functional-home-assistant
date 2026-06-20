package fh.view.build

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import io.circe.{Json, JsonObject}

/** Build-phase entity/area/floor dump.
  *
  * Scala port of `../ha-frontend/script.sh`: it asks Home Assistant to render a
  * Jinja template (via `/api/template`) producing
  * `{ floors, areas, entities }`, then transforms each list into an object
  * keyed by a dotless, sanitized id (for ergonomic jsonnet autocomplete).
  *
  * The result is written next to the dashboard jsonnet as `dump.libsonnet` and
  * imported by `dashboard.jsonnet`, so authors reference real entities by name.
  */
object DataDump {

  /** Jinja template rendered server-side by Home Assistant. Mirrors
    * `script.sh`; pulls a few domain-specific static attributes for lights.
    */
  val template: String =
    """
      |{% set ns = namespace(floors=[], result=[], areas=[]) %}
      |
      |{% for f in floors() %}
      |  {% set ns.floors = ns.floors + [{
      |    "floor_id": f,
      |    "floor_name": floor_name(f)
      |  }] %}
      |{% endfor %}
      |
      |{% for _area_id in areas() %}
      |  {% set ns.areas = ns.areas + [{
      |    "area_id": _area_id,
      |    "floor_id": floor_id(area_name(_area_id)),
      |    "area_name": area_name(_area_id)
      |  }] %}
      |{% endfor %}
      |
      |{% for state in states %}
      |  {% set entity_ns = namespace(data={}) %}
      |  {% if state.domain == "light" %}
      |    {% set entity_ns.data = dict(
      |      entity_ns.data,
      |      color_mode=state_attr(state.entity_id, 'color_mode'),
      |      effect_list=state_attr(state.entity_id, 'effect_list'),
      |    )%}
      |  {% endif %}
      |
      |  {% set ns.result = ns.result + [{
      |    "entity_id": state.entity_id,
      |    "friendly_name": state_attr(state.entity_id, 'friendly_name'),
      |    "id_hidden": is_hidden_entity(state.entity_id),
      |    "area_id": area_id(state.entity_id),
      |    "floor_id": floor_id(state.entity_id),
      |    "domain": state.domain,
      |    "attributes": entity_ns.data,
      |  }]
      |  %}
      |{% endfor %}
      |
      |{{ {"areas": ns.areas, "floors": ns.floors, "entities": ns.result } | tojson }}
      |""".stripMargin

  /** Fetch the raw dump and apply the id-keying transform. */
  def fetch(api: HomeAssistantApi[IO]): IO[Json] =
    api.templateFunc[Json](template).map(transform)

  /** Turn the `areas`/`floors`/`entities` lists into objects keyed by a
    * sanitized, dotless id (a valid jsonnet field name), so authors reference
    * entities by name.
    */
  def transform(raw: Json): Json = {
    def keyBy(arr: Json, idField: String): Json =
      arr.asArray match {
        case None => arr
        case Some(items) =>
          val entries = items.flatMap { item =>
            item.hcursor.get[String](idField).toOption.map { id =>
              sanitize(id) -> item
            }
          }
          Json.fromJsonObject(JsonObject.fromIterable(entries))
      }

    raw.asObject match {
      case None => raw
      case Some(obj) =>
        Json.fromJsonObject(
          obj
            .add("areas", keyBy(obj("areas").getOrElse(Json.arr()), "area_id"))
            .add(
              "floors",
              keyBy(obj("floors").getOrElse(Json.arr()), "floor_id")
            )
            .add(
              "entities",
              keyBy(obj("entities").getOrElse(Json.arr()), "entity_id")
            )
        )
    }
  }

  /** Same sanitization the jq script applies: dots become underscores so the id
    * is a valid jsonnet field name.
    */
  private def sanitize(id: String): String = id.replace(".", "_")
}
