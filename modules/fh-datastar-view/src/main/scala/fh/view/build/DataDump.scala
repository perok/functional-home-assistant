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
    * sanitized field (a valid jsonnet field name), so authors reference them by
    * name. Entities are keyed by `entity_id` (dots -> underscores);
    * areas/floors by their NAME (`area_name`/`floor_name`), slugified for
    * `dump.areas.<name>` access (e.g. `Kjøkken` -> `kjokken`, `Living Room` ->
    * `living_room`).
    *
    * Each floor additionally carries a nested, slug-keyed `areas` sub-object of
    * just the areas on that floor (matched by `floor_id`), so authors can drill
    * `dump.floors.<floor>.areas.<area>.area_id` with editor autocomplete. The
    * flat top-level `dump.areas` map is left intact — the nesting is additive.
    */
  def transform(raw: Json): Json = {
    def keyBy(arr: Json, keyField: String, key: String => String): Json =
      arr.asArray match {
        case None => arr
        case Some(items) =>
          val entries = items.flatMap { item =>
            item.hcursor.get[String](keyField).toOption.map { raw =>
              key(raw) -> item
            }
          }
          Json.fromJsonObject(JsonObject.fromIterable(entries))
      }

    raw.asObject match {
      case None => raw
      case Some(obj) =>
        val areasArr = obj("areas").getOrElse(Json.arr())
        val areaItems = areasArr.asArray.getOrElse(Vector.empty)

        // Add to each floor a slug-keyed `areas` sub-object of the areas whose
        // `floor_id` references it.
        def withAreas(floor: Json): Json = {
          val fid = floor.hcursor.get[String]("floor_id").toOption
          val mine = Json.fromValues(
            areaItems.filter(a =>
              a.hcursor.get[String]("floor_id").toOption == fid
            )
          )
          floor.deepMerge(Json.obj("areas" -> keyBy(mine, "area_name", slug)))
        }

        val floorsArr = obj("floors").getOrElse(Json.arr())
        val enrichedFloors = floorsArr.asArray match {
          case Some(items) => Json.fromValues(items.map(withAreas))
          case None        => floorsArr
        }

        Json.fromJsonObject(
          obj
            .add("areas", keyBy(areasArr, "area_name", slug))
            .add("floors", keyBy(enrichedFloors, "floor_name", slug))
            .add(
              "entities",
              keyBy(
                obj("entities").getOrElse(Json.arr()),
                "entity_id",
                entityKey
              )
            )
        )
    }
  }

  /** Entity key: just dots -> underscores (entity_ids are already
    * `[a-z0-9_]`-plus-one-dot, and `at(id)` in jsonnet mirrors this exactly).
    */
  private def entityKey(id: String): String = id.replace(".", "_")

  /** A friendly, valid jsonnet field name from a free-form name: lower-cased,
    * Nordic letters and diacritics folded to ASCII, runs of anything else
    * collapsed to a single underscore (`Kjøkken` -> `kjokken`).
    */
  private def slug(name: String): String =
    java.text.Normalizer
      .normalize(
        name.toLowerCase.replace("ø", "o").replace("æ", "ae").replace("å", "a"),
        java.text.Normalizer.Form.NFD
      )
      .replaceAll("\\p{M}+", "") // strip combining diacritics (é -> e)
      .replaceAll("[^a-z0-9]+", "_")
      .replaceAll("^_+|_+$", "")
}
