package fh.view.build

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.domain.{
  Area,
  Device,
  EntitiesEvent,
  Entity as RegistryEntity,
  Floor,
  HaAccount
}
import cats.effect.IO
import cats.effect.std.Env
import cats.syntax.all.*
import ha.runtime.definitions.{DeviceId, ReadableEntityId}
import io.circe.{Json, JsonObject}

/** What the authoring layer can see; [[PklDump]] decides how it is typed.
  *
  * Registries, not a Jinja template through `/api/template`: that truncates at
  * 262144 characters (the template already filled ~228k), and cannot reach
  * `entity_category` or a whole-device listing. See ADR 0013.
  */
object RegistryDump {

  /** Only static attributes: any change re-hashes the dump package and
    * re-evaluates every dashboard. Each was checked by watching
    * `subscribe_entities` deltas for 180s with none appearing; `entity_picture`
    * did (a rotating `access_token`) and is excluded. Re-run that check before
    * adding one.
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

  // A light group helper's members, then a ZHA group's.
  private val MemberAttributes: List[String] =
    List("entity_id", "group_entities")

  /** Additive; the same static-only rule applies. */
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
      api.configFloorRegistryList,
      api.configAuthList
    ).mapN(build(_, _, _, _, _, _, carried)).map(transform)

  /** The first frame of `subscribe_entities`. Unfiltered, unlike the runtime
    * feed: an author writes the next dashboard against entities nothing reads
    * yet.
    */
  private def snapshot(
      api: HomeAssistantApi[IO]
  ): IO[Map[String, EntitiesEvent.Full]] =
    api.entities(None).use(_.head.compile.lastOrError).map(_.added)

  /** A left join from states: the registry also lists disabled entities (2296
    * against 1069 with state on the dev instance), and a few like `sun.sun`
    * have state but no registry row.
    */
  def build(
      states: Map[String, EntitiesEvent.Full],
      registry: List[RegistryEntity],
      devices: List[Device],
      areas: List[Area],
      floors: List[Floor],
      accounts: List[HaAccount] = Nil,
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
      // The device's area unless overridden, as HA's `area_id()` does.
      val areaId =
        reg
          .flatMap(_.area_id)
          .orElse(device.flatMap(deviceById.get).flatMap(_.area_id))

      Json.fromFields(
        List(
          "entity_id" -> Json.fromString(entityId),
          "domain" -> Json.fromString(entityId.takeWhile(_ != '.')),
          // Only the state carries the composed name.
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

    // Keyed here, deduplicated: two bulbs of one model share a name.
    val deviceJson = dedupeKeyed(
      devices.sortBy(d => DeviceId.toString(d.id)).map { d =>
        val name = d.name_by_user.getOrElse(d.name)
        slug(name) -> Json.fromFields(
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
      "entities" -> Json.fromValues(entityJson),
      // Not Supervisor, Cast or the content user, two of which are admins.
      "users" -> Json.fromValues(
        accounts.filter(_.isPerson).sortBy(_.id).map { a =>
          Json.obj(
            "user_id" -> Json.fromString(a.id),
            "user_name" -> Json.fromString(a.name),
            "is_admin" -> Json.fromBoolean(a.isAdmin),
            "is_owner" -> Json.fromBoolean(a.is_owner)
          )
        }
      )
    )
  }

  /** `_2`, `_3`, ... on a repeat; input order decides who keeps the bare slug,
    * so sort first.
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

  /** Lists into objects keyed by identifier: entities by `entity_id`, the rest
    * by slugged name. Each floor also gets its own areas, keyed the same way.
    */
  def transform(raw: Json): Json = {
    def keyBy(arr: Json, keyField: String, key: String => String): Json =
      arr.asArray match {
        case None        => arr
        case Some(items) =>
          val entries = items.flatMap { item =>
            item.hcursor.get[String](keyField).toOption.map { raw =>
              key(raw) -> item
            }
          }
          Json.fromJsonObject(JsonObject.fromIterable(entries))
      }

    raw.asObject match {
      case None      => raw
      case Some(obj) =>
        val areasArr = obj("areas").getOrElse(Json.arr())
        val areaItems = areasArr.asArray.getOrElse(Vector.empty)

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
            .add(
              "users",
              keyBy(obj("users").getOrElse(Json.arr()), "user_name", slug)
            )
        )
    }
  }

  private[build] def entityKey(id: String): String = id.replace(".", "_")

  // `ø`/`æ` do not decompose under NFD, hence the explicit folds.
  private[build] def slug(name: String): String =
    java.text.Normalizer
      .normalize(
        name.toLowerCase.replace("ø", "o").replace("æ", "ae").replace("å", "a"),
        java.text.Normalizer.Form.NFD
      )
      .replaceAll("\\p{M}+", "")
      .replaceAll("[^a-z0-9]+", "_")
      .replaceAll("^_+|_+$", "")
}
