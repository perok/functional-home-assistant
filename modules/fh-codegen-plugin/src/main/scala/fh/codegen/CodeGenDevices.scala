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
    devices: Map[DeviceId, Device],
    deviceTriggers: Map[DeviceId, NonEmptyList[DeviceTrigger]],
    entities: Map[EntityId, Entity]
) {
  // val platformThenDevices = devices.values.map(d => (d.))
  // area_id, id, name_by_user, name
  // io.circe.parser.decode[io.circe.Json]("").toOption.get

  extension (trigger: DeviceTrigger)
    def name = trigger.`type` + trigger.subtype.map(st => s"_$st").orEmpty

  val deviceCode = devices.values.map { device =>
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

    StaticCode[Device].toStatic(
      device,
      overrideLabel = Some(name),
      `extends` = List("IsDevice"),
      additionalContent = s"""object triggers {
           |$triggers
           |}""".stripMargin
    )
  }

  val code = s"""
    |import ha.runtime.definitions.*
    |object devices {
    |
    |${deviceCode.mkString("\n")}
    |}
    |""".stripMargin

}
