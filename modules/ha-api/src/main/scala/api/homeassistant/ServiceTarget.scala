package api.homeassistant

import cats.data.NonEmptyList
import io.circe.{Encoder, Json}

/** The ids of one side of a target, a list per key as HA's `target` takes them.
  */
final case class TargetIds(
    entityIds: List[String] = Nil,
    areaIds: List[String] = Nil,
    floorIds: List[String] = Nil
) derives CanEqual:
  def isEmpty: Boolean =
    entityIds.isEmpty && areaIds.isEmpty && floorIds.isEmpty

  /** An empty key is left out: HA refuses `entity_id: []` as a target. */
  def fields: List[(String, Json)] =
    List(
      "entity_id" -> entityIds,
      "area_id" -> areaIds,
      "floor_id" -> floorIds
    ).collect:
      case (key, ids) if ids.nonEmpty =>
        key -> Json.fromValues(ids.map(Json.fromString))

/** What a service call acts on: HA's `target`, with its `exclude` (HA 2026.11).
  * An area or a floor is expanded by HA, which leaves out entities hidden in HA
  * or carrying an `entity_category`.
  *
  * `exclude` takes ids of the same shape and subtracts them; HA refuses it
  * without something to subtract from, so [[ServiceTarget.of]] needs `include`
  * non-empty.
  */
final case class ServiceTarget private (include: TargetIds, exclude: TargetIds)
    derives CanEqual:

  def json: Json =
    val excluded =
      if exclude.isEmpty then Nil
      else List("exclude" -> Json.obj(exclude.fields*))
    Json.obj((include.fields ++ excluded)*)

object ServiceTarget:
  given Encoder[ServiceTarget] = Encoder.instance(_.json)

  def of(
      include: TargetIds,
      exclude: TargetIds = TargetIds()
  ): Option[ServiceTarget] =
    Option.unless(include.isEmpty)(ServiceTarget(include, exclude))

  def Entities(entityIds: NonEmptyList[String]): ServiceTarget =
    ServiceTarget(TargetIds(entityIds = entityIds.toList), TargetIds())

  def Entity(entityId: String): ServiceTarget =
    Entities(NonEmptyList.one(entityId))

  def Area(areaId: String): ServiceTarget =
    ServiceTarget(TargetIds(areaIds = List(areaId)), TargetIds())

  def Floor(floorId: String): ServiceTarget =
    ServiceTarget(TargetIds(floorIds = List(floorId)), TargetIds())
