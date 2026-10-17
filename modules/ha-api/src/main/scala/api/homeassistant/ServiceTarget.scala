package api.homeassistant

import cats.data.NonEmptyList
import io.circe.{Encoder, Json}

/** What a service call acts on: HA's `target`. An area or a floor is expanded
  * by HA, which leaves out entities hidden in HA or carrying an
  * `entity_category`.
  */
enum ServiceTarget derives CanEqual:
  case Entity(entityId: String)
  case Entities(entityIds: NonEmptyList[String])
  case Area(areaId: String)
  case Floor(floorId: String)

  def json: Json = this match
    case Entity(id)    => Json.obj("entity_id" -> Json.fromString(id))
    case Entities(ids) =>
      Json.obj("entity_id" -> Json.fromValues(ids.toList.map(Json.fromString)))
    case Area(id)  => Json.obj("area_id" -> Json.fromString(id))
    case Floor(id) => Json.obj("floor_id" -> Json.fromString(id))

object ServiceTarget:
  given Encoder[ServiceTarget] = Encoder.instance(_.json)
