package fh.codegen

import api.homeassistant.ws.domain.*
import cats.syntax.all.*
import ha.runtime.definitions.*
import cats.data.NonEmptyList
import fh.codegen.utils.*

// TODO split into more files
// TODO warn about duplicates before compilation issues? That is a case of usually
// things with a status of not working. Or could we detect that and hide em?
class CodeGenDevices(
    allManifests: Map[ManifestDomain, Manifest],
    allConfigEntries: Map[EntryId, ConfigEntry],
    devices: Map[DeviceId, Device],
    deviceTriggers: Map[DeviceId, NonEmptyList[DeviceTrigger]],
    entities: Map[EntityId, Entity]
) {

  { // All config entries has
    val missingManifests = allConfigEntries.values.toList.mapFilter(ce =>
      Option.when(!allManifests.contains(ce.domain))(ce)
    )

    if missingManifests.nonEmpty then
      throw UnsupportedOperationException(
        missingManifests
          .map(ce => s"${ce.title} with ${ce.domain} missing")
          .mkString(", ")
      )
  }
  // val platformThenDevices = devices.values.map(d => (d.))
  // area_id, id, name_by_user, name
  // io.circe.parser.decode[io.circe.Json]("").toOption.get

  extension (trigger: DeviceTrigger)
    def name = trigger.`type` + trigger.subtype.map(st => s"_$st").orEmpty

  val phaseDevices: List[(Option[EntryId], List[(Device, String)])] =
    devices.values.toList.map { device =>
      val areaId = device.area_id
      val id = device.id
      val name = device.name_by_user.getOrElse(device.name)

      val allTriggers = deviceTriggers.get(id).map(_.toList).toList.flatten
      val domainGroupedTriggers = allTriggers.groupBy(_.domain)

      def triggerToCode(
          trigger: DeviceTrigger,
          nameCollision: Boolean
      ): String = {
        val name = trigger.name

        val controlledName =
          if nameCollision then
            s"${name}_${entities(trigger.entity_id.get).bestName}"
          else name

        StaticCode[DeviceTrigger].toStatic(
          trigger,
          overrideLabel = Some(controlledName),
          `extends` = List("IsDeviceTrigger")
        )
      }

      val triggers = domainGroupedTriggers
        .map((domain, triggers) =>
          val triggersCode = triggers
            .map(trigger =>
              triggerToCode(trigger, triggers.count(_.name == trigger.name) > 1)
            )
            .mkString("\n")

          s"""object ${Helpers.objectNameSafe(domain)} {
         |  $triggersCode
         |}""".stripMargin
        )
        .mkString("\n")

      device.primary_config_entry -> List(
        (
          device,
          StaticCode[Device].toStatic(
            device,
            overrideLabel = Some(name),
            `extends` = List("IsDevice"),
            additionalContent = s"""
           |object triggers {
           |  $triggers
           |}""".stripMargin
          )
        )
      )
    }

  val phaseConfigs: Map[Option[ManifestDomain], List[String]] =
    phaseDevices
      .map {
        case (Some(entryId), stuff) =>

          val entry = allConfigEntries(entryId)
          Option.when(entry.title.nonEmpty)(entryId) -> stuff
        case other => other
      }
      .groupMapReduce(_._1)(_._2)(_ ++ _)
      .map {
        case (None, devices) =>
          val code = s"""object unknown {
         |  ${devices.map(_._2).mkString("\n")}
         |}
         |""".stripMargin

          Option.empty[ManifestDomain] -> List(code)
        case (Some(entryId), devices) =>

          val entry = allConfigEntries(entryId)

          if (devices.size == 1 && devices.head._1.name == entry.title) {
            entry.domain.some -> List(devices.head._2)
          } else {
            val code =
              s"""
                  |object ${Helpers.objectNameSafe(entry.title)} {
                  |  ${devices.map(_._2).mkString("\n")}
                  |}
                  |""".stripMargin

            entry.domain.some -> List(code)
          }
      }

  val phaseManifests = phaseConfigs.map {
    case (None, code) => code.mkString("\n")
    case (Some(manifestDomain), configCode) =>
      val manifest = allManifests(manifestDomain)

      s"""object ${Helpers.objectNameSafe(manifest.name)} {
         |  ${configCode.mkString("\n")}
         |}
         |""".stripMargin
  }

  val code = s"""
    |package ha.generated
    |
    |import ha.runtime.definitions.*
    |object integrations {
    |  ${phaseManifests.mkString("\n")}
    |}
    |""".stripMargin

}
