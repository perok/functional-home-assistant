package fh.codegen

import api.homeassistant.ws.domain.Entity
import fh.codegen.utils.StaticCode
import ha.runtime.definitions.*

class CodeGenEntities(
    entities: Map[EntityId, Entity]
) {

  val refererenceOverview = entities.view.mapValues { entity =>
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
