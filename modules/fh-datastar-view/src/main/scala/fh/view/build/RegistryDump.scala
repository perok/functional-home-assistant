package fh.view.build

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.domain.{
  Area,
  Device,
  EntitiesEvent,
  Entity as RegistryEntity,
  Floor
}
import cats.effect.IO
import cats.effect.std.Env
import cats.syntax.all.*
import ha.runtime.definitions.{DeviceId, ReadableEntityId}
import io.circe.{Json, JsonObject}

/** Build-phase dump built from the WebSocket REGISTRIES, the side-by-side
  * replacement for [[DataDump]]'s Jinja template.
  *
  * Why a second implementation rather than growing the template:
  * `/api/template` truncates its output at 262144 characters and the existing
  * template already renders ~228k of that, so there is no room left for device
  * ids, group members or a wider attribute set. The registries have no such
  * cap, and they carry two things a template provably cannot reach —
  * `entity_category` (absent from state attributes) and, on this HA version,
  * any whole-device listing (`devices()` is undefined; only the per-entity
  * `device_id()` exists).
  *
  * Emits the SAME JSON shape [[DataDump.fetch]] does — `{floors, areas,
  * entities}` keyed by [[DataDump.transform]] — so [[PklDump.render]] consumes
  * either one unchanged. The extra fields are additive; the template path
  * simply never sets them.
  */
object RegistryDump {

  /** Attributes copied into the dump by default.
    *
    * The cut is not size, and not staleness alone — it is **the dump's content
    * version**. The dump is a content-addressed package
    * (`fh-home@1.0.0-g<hash>`) and [[DumpRefresh]] re-seeds it and re-evaluates
    * every dashboard whenever that hash moves. An attribute that changes while
    * the server runs therefore does not merely go stale: it re-hashes the
    * package on every change, turning a dimmed light into a full rebuild.
    * Nothing volatile can live here however useful it looks; volatile values
    * are read runtime-side as JSONata over the SSE stream.
    *
    * Every name below was checked against a live instance by watching
    * `subscribe_entities` delta frames for 180s — none of them appeared in a
    * single change. `entity_picture` DID (4 changes across 4 entities) and is
    * deliberately excluded: camera and media_player picture URLs carry a
    * rotating `access_token`. Re-run that check before adding to this set.
    */
  val CapabilityAttributes: Set[String] = Set(
    // any domain
    "device_class",
    "unit_of_measurement",
    "state_class",
    "icon",
    "supported_features",
    // light
    "supported_color_modes",
    "effect_list",
    "min_color_temp_kelvin",
    "max_color_temp_kelvin",
    // number / input_number
    "min",
    "max",
    "step",
    "mode",
    // select / input_select
    "options",
    // climate
    "hvac_modes",
    "min_temp",
    "max_temp",
    "target_temp_step",
    "fan_modes",
    "preset_modes",
    "swing_modes",
    // media_player
    "source_list",
    "sound_mode_list",
    // vacuum / lawn_mower
    "fan_speed_list"
  )

  /** The two attributes that name an entity's MEMBERS, in precedence order.
    *
    * `entity_id` is the HA Light Group helper's member list (a `light.*` that
    * fans out to other lights); `group_entities` is the Zigbee/ZHA group
    * equivalent. They nest — a light group can hold a zigbee group, which holds
    * bulbs — and both are plain `entity_id` lists, so one `members` edge covers
    * them.
    */
  private val MemberAttributes: List[String] =
    List("entity_id", "group_entities")

  /** Extra attribute names to carry, comma-separated, from
    * `FH_DUMP_ATTRIBUTES`.
    *
    * Additive to [[CapabilityAttributes]]: the default set is the safe floor,
    * and a home with an integration exposing something static and useful that
    * ships here can widen it without a rebuild. The same caching rule applies —
    * naming a volatile attribute re-hashes the dump on every change of it — so
    * this is a sharp tool, which is why it is opt-in rather than a filter that
    * ships wide.
    */
  private def extraAttributes: IO[Set[String]] =
    Env[IO]
      .get("FH_DUMP_ATTRIBUTES")
      .map(
        _.fold(Set.empty[String])(
          _.split(",").map(_.trim).filter(_.nonEmpty).toSet
        )
      )

  def fetch(api: HomeAssistantApi[IO]): IO[Json] =
    extraAttributes.flatMap(extra =>
      fetchWith(api, CapabilityAttributes ++ extra)
    )

  private def fetchWith(
      api: HomeAssistantApi[IO],
      carried: Set[String]
  ): IO[Json] =
    (
      snapshot(api),
      api.configEntityRegistryList.map(_.values.toList),
      api.configDeviceRegistryList.map(_.values.toList),
      api.configAreaRegistryList,
      api.configFloorRegistryList
    ).mapN(build(_, _, _, _, _, carried)).map(DataDump.transform)

  /** `subscribe_entities`' FIRST frame is a full snapshot of every entity with
    * every attribute (see [[EntitiesEvent]]) — one command, no size cap, and no
    * Jinja. Take that frame and release the subscription; the live feed is the
    * runtime's job, not the dump's.
    */
  private def snapshot(
      api: HomeAssistantApi[IO]
  ): IO[Map[String, EntitiesEvent.Full]] =
    api.entities.use(_.head.compile.lastOrError).map(_.added)

  /** The pure core: join the state snapshot against the registries.
    *
    * The STATE snapshot is the spine, not the entity registry. The registry
    * lists every entity that ever existed — 2296 against 1069 with state on the
    * dev instance, the difference being disabled ones — while an entity with no
    * state is not something a dashboard can render. A handful of entities go
    * the other way (`sun.sun` and friends have state but no registry row), so
    * this is a LEFT join from states, with registry fields defaulted when
    * absent.
    */
  def build(
      states: Map[String, EntitiesEvent.Full],
      registry: List[RegistryEntity],
      devices: List[Device],
      areas: List[Area],
      floors: List[Floor],
      carried: Set[String] = CapabilityAttributes
  ): Json = {
    val byEntityId: Map[String, RegistryEntity] =
      registry.map(e => ReadableEntityId.toString(e.entity_id) -> e).toMap
    val deviceById: Map[String, Device] =
      devices.map(d => DeviceId.toString(d.id) -> d).toMap
    val floorOfArea: Map[String, String] =
      areas.flatMap(a => a.floor_id.map(a.area_id -> _)).toMap

    val entityJson = states.toList.sortBy(_._1).map { case (entityId, full) =>
      val reg = byEntityId.get(entityId)
      val device = reg.flatMap(_.device_id).map(DeviceId.toString)
      // An entity inherits its DEVICE's area unless it overrides it — the same
      // fallback the Jinja `area_id()` function applies.
      val areaId =
        reg
          .flatMap(_.area_id)
          .orElse(device.flatMap(deviceById.get).flatMap(_.area_id))

      Json.fromFields(
        List(
          "entity_id" -> Json.fromString(entityId),
          "domain" -> Json.fromString(entityId.takeWhile(_ != '.')),
          // The COMPOSED display name, which only the state carries: the
          // registry stores `name`/`original_name` and leaves assembling them
          // with the device name to HA.
          "friendly_name" -> full.attributes
            .get("friendly_name")
            .getOrElse(Json.Null),
          "id_hidden" -> Json.fromBoolean(reg.exists(_.hidden_by.isDefined)),
          "entity_category" -> reg
            .flatMap(_.entity_category)
            .fold(Json.Null)(Json.fromString),
          "device_id" -> device.fold(Json.Null)(Json.fromString),
          "area_id" -> areaId.fold(Json.Null)(Json.fromString),
          "floor_id" -> areaId
            .flatMap(floorOfArea.get)
            .fold(Json.Null)(Json.fromString),
          "members" -> Json.fromValues(members(full)),
          "attributes" -> Json.fromFields(
            full.attributes.filter((k, _) => carried.contains(k))
          )
        )
      )
    }

    // Keyed HERE rather than in `DataDump.transform`, which keys only the three
    // fields the template path produces and passes anything else through
    // untouched. Device NAMES are not unique the way area names are (two bulbs
    // of the same model land on the same slug), so the key is deduplicated.
    val deviceJson = dedupeKeyed(
      devices.sortBy(d => DeviceId.toString(d.id)).map { d =>
        val name = d.name_by_user.getOrElse(d.name)
        DataDump.slug(name) -> Json.fromFields(
          List(
            "device_id" -> Json.fromString(DeviceId.toString(d.id)),
            "device_name" -> Json.fromString(name),
            "area_id" -> d.area_id.fold(Json.Null)(Json.fromString),
            "manufacturer" -> d.manufacturer.fold(Json.Null)(Json.fromString),
            "model" -> d.model.fold(Json.Null)(Json.fromString)
          )
        )
      }
    )

    Json.obj(
      "floors" -> Json.fromValues(floors.sortBy(_.floor_id).map { f =>
        Json.fromFields(
          List(
            "floor_id" -> Json.fromString(f.floor_id),
            "floor_name" -> Json.fromString(f.name),
            "level" -> f.level.fold(Json.Null)(l => Json.fromInt(l))
          )
        )
      }),
      "areas" -> Json.fromValues(areas.sortBy(_.area_id).map { a =>
        Json.fromFields(
          List(
            "area_id" -> Json.fromString(a.area_id),
            "area_name" -> Json.fromString(a.name),
            "floor_id" -> a.floor_id.fold(Json.Null)(Json.fromString)
          )
        )
      }),
      "devices" -> deviceJson,
      "entities" -> Json.fromValues(entityJson)
    )
  }

  /** Key an already-slugged list, suffixing `_2`, `_3`, ... on a repeat so no
    * entry is silently dropped by the map. Input order decides who keeps the
    * bare slug, so callers sort first for a stable dump.
    */
  private def dedupeKeyed(entries: List[(String, Json)]): Json = {
    val (out, _) =
      entries.foldLeft((List.empty[(String, Json)], Map.empty[String, Int])) {
        case ((acc, seen), (slug, value)) =>
          val n = seen.getOrElse(slug, 0)
          val key = if (n == 0) slug else s"${slug}_${n + 1}"
          ((key -> value) :: acc, seen.updated(slug, n + 1))
      }
    Json.fromJsonObject(JsonObject.fromIterable(out.reverse))
  }

  private def members(full: EntitiesEvent.Full): List[Json] =
    MemberAttributes
      .flatMap(full.attributes.get)
      .flatMap(_.asArray)
      .flatten
      .filter(_.isString)
}
