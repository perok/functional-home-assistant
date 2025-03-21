package fh.codegen

import api.homeassistant.ws.domain.Entity
import fh.codegen.utils.StaticCode
import ha.runtime.definitions.*

case class AbsolutePosition(directory: String, namespace: List[String])
case class ThingReference[T](
    thing: T,
    name: String,
    pckage: List[String],
    toCode: () => String
) {
  def toPath(using abspos: AbsolutePosition) = {
    val nameEscaped = name.replace(" ", "-").replace("/", "_")
    java.nio.file.Paths
      .get(
        abspos.directory,
        abspos.namespace.appendedAll(pckage).appended(s"$nameEscaped.scala")*
      )
  }

  def toCodeFileContent(using abspos: AbsolutePosition) = {
    val pcgName = (abspos.namespace.appendedAll(pckage)).mkString("", ".", "")
    s"""package $pcgName
       |
       |${toCode()}
       |""".stripMargin
  }

  def almostFullyQualifiedName(using abspos: AbsolutePosition): String =
    s"_root_${abspos.namespace.appendedAll(pckage).mkString(".", ".", ".")}${Helpers.objectNameSafe(name)}"
}

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
