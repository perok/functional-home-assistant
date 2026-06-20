package fh.codegen

import api.homeassistant.ws.domain.Entity
import fh.codegen.utils.StaticCode
import ha.runtime.definitions.*

class CodeGenEntities(
    entities: Map[EntityId, Entity]
) {

  private val listOfAll = entities.values.toList

  val refererenceOverview = entities.view.mapValues { entity =>
    val collision =
      listOfAll.count(_.bestName == entity.bestName) > 1
    // val name = if collision then s"${entity.entity_id}" else entity.bestName
    val name = ReadableEntityId.name(entity.entity_id)

    ThingReference(
      entity,
      name,
      List("entities", entity.domain),
      () =>
        StaticCode[Entity].toStatic(
          entity,
          overrideLabel = Some(name),
          imports = List("ha.runtime.definitions.*"),
          `extends` = List("IsEntity")
        )
    )
  }.toMap

}
