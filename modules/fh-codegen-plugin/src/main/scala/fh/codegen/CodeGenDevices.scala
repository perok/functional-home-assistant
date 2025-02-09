package fh.codegen

import api.homeassistant.ws.domain.*
import cats.syntax.all.*
import ha.runtime.definitions.*
import cats.data.NonEmptyList
import fh.codegen.utils.*

// TODO split into more files
class CodeGenDevices(
    devices: Map[DeviceId, Device],
    deviceTriggers: Map[DeviceId, NonEmptyList[DeviceTrigger]]
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

    val triggers = deviceTriggers
      .get(id)
      .nested
      .map { trigger =>
        val name = trigger.name

        val moreThanOne = allTriggers.count(_.name == name) > 1

        val controlledName =
          // TODO should be entity name
          if moreThanOne then s"${name}_${trigger.entity_id.get}"
          else name

        StaticCode[DeviceTrigger].toStatic(
          trigger,
          overrideLabel = Some(controlledName),
          `extends` = List("IsDeviceTrigger")
        )
      }
      .value
      .map { triggers =>
        triggers.mkString_("  ", "\n", "")
      }
      .map { strings =>
        s"""object triggers {
          |$strings
          |}""".stripMargin
      }
      .orEmpty

    StaticCode[Device].toStatic(
      device,
      overrideLabel = Some(name),
      `extends` = List("IsDevice"),
      additionalContent = triggers
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
