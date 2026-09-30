package api.homeassistant

import io.circe.{Encoder, Json}

/** What a service call acts on: HA's `target`. An area or a floor is expanded
  * by HA, which leaves out entities hidden in HA or carrying an
  * `entity_category`.
  */
enum ServiceTarget(val key: String, val id: String) derives CanEqual:
  case Entity(entityId: String) extends ServiceTarget("entity_id", entityId)
  case Area(areaId: String) extends ServiceTarget("area_id", areaId)
  case Floor(floorId: String) extends ServiceTarget("floor_id", floorId)

  def json: Json = Json.obj(key -> Json.fromString(id))

object ServiceTarget:
  given Encoder[ServiceTarget] = Encoder.instance(_.json)
