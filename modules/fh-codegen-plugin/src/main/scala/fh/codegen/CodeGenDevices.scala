package fh.codegen

import api.homeassistant.ws.domain.*
import cats.data.NonEmptyList
import cats.syntax.all.*
import fh.codegen.utils.*
import ha.runtime.definitions.*

// TODO warn about duplicates before compilation issues? That is a case of usually
// things with a status of not working. Or could we detect that and hide em?
// TODO could we instead have a few select things we output and then have a json dump that we can easily extract
// the data from? or sqllite or something. Worth investigating if the file size is an issue
class CodeGenDevices(
    devices: Map[DeviceId, Device],
    deviceTriggers: Map[DeviceId, NonEmptyList[DeviceTrigger]],
    entities: Map[EntityId, ThingReference[Entity]]
)(using AbsolutePosition) {

  // How to get categories of the sort?
  // controls
  // sensors
  // diagnostics

  // val platformThenDevices = devices.values.map(d => (d.))
  // area_id, id, name_by_user, name
  // io.circe.parser.decode[io.circe.Json]("").toOption.get

  extension (trigger: DeviceTrigger)
    def name = trigger.`type` + trigger.subtype.map(st => s"_$st").orEmpty

  val deviceToEntities = entities.values.groupBy(_.thing.device_id)

  val deviceReferences: Map[DeviceId, ThingReference[Device]] =
    devices.view.mapValues { device =>
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
            s"${name}_${entities(trigger.entity_id.get).thing.bestName}"
          else name

        StaticCode[DeviceTrigger].toStatic(
          trigger,
          overrideLabel = Some(controlledName),
          `extends` = List("IsDeviceTrigger")
        )
      }

      val entitiesInDevice = deviceToEntities.get(Some(device.id))

      val entitiesList = entitiesInDevice
        .map(e =>
          consume(
            e,
            0,
            entity =>
              s"val ${Helpers.objectNameSafe(entity.name)}: ha.runtime.definitions.IsEntity = ${entity.almostFullyQualifiedName}"
          )
        )
        .orEmpty

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

      ThingReference(
        device,
        name,
        List("devices"),
        () =>
          StaticCode[Device].toStatic(
            device,
            overrideLabel = Some(name),
            `extends` = List("IsDevice"),
            imports = List("ha.runtime.definitions.*"),
            additionalContent = s"""
                                 |
                                 |$entitiesList
                                 |
                                 |object triggers {
                                 |  $triggers
                                 |}""".stripMargin
          )
      )
    }.toMap
}

class CodeGenConfigEntries(
    allConfigEntries: Map[EntryId, ConfigEntry],
    deviceReferences: Map[DeviceId, ThingReference[Device]]
)(using AbsolutePosition) {

  val configEntriesReferences: Map[EntryId, ThingReference[ConfigEntry]] =
    allConfigEntries.view.mapValues { configEntry =>
      val name =
        if configEntry.title.isEmpty then configEntry.entry_id.toString
        else configEntry.title

      val devicesInConfig: Iterable[ThingReference[Device]] =
        deviceReferences.values.filter(
          _.thing.primary_config_entry == Some(configEntry.entry_id)
        )

      val configsList = consume(
        devicesInConfig,
        0,
        entity =>
          // TODO should be object.type as type? usefull when we add more stuff
          s"val ${Helpers.objectNameSafe(entity.name)}: ha.runtime.definitions.IsDevice = ${entity.almostFullyQualifiedName}"
      )

      ThingReference(
        configEntry,
        name,
        List("config_entries", ManifestDomain.toString(configEntry.domain)),
        () =>
          StaticCode[ConfigEntry].toStatic(
            configEntry,
            overrideLabel = Some(name),
            `extends` = List("IsConfigEntry"),
            imports = List("ha.runtime.definitions.*"),
            additionalContent = s"""
                 |$configsList
                 |""".stripMargin
          )
      )

    }.toMap
}

class CodeGenManifests(
    allManifests: Map[ManifestDomain, Manifest],
    configEntries: Map[EntryId, ThingReference[ConfigEntry]]
)(using AbsolutePosition) {

  { // All config entries has
    val missingManifests = configEntries.values.toList.mapFilter(ce =>
      Option.when(!allManifests.contains(ce.thing.domain))(ce.thing)
    )

    if missingManifests.nonEmpty then
      throw UnsupportedOperationException(
        missingManifests
          .map(ce => s"${ce.title} with ${ce.domain} missing")
          .mkString(", ")
      )
  }

  val manifestReferences: Map[ManifestDomain, ThingReference[Manifest]] =
    allManifests.view.mapValues { manifest =>
      val name = manifest.name

      val configsInManifest: Iterable[ThingReference[ConfigEntry]] =
        configEntries.values.filter(_.thing.domain == manifest.domain)

      val configsList = consume(
        configsInManifest,
        0,
        entity =>
          s"val ${Helpers.objectNameSafe(entity.name)}: ha.runtime.definitions.IsConfigEntry = ${entity.almostFullyQualifiedName}"
      )

      ThingReference(
        manifest,
        name,
        List("manifest"), // , manifest.domain),
        () =>
          StaticCode[Manifest].toStatic(
            manifest,
            overrideLabel = Some(name),
            `extends` = List("IsManifest"),
            imports = List("ha.runtime.definitions.*"),
            additionalContent = s"""
               |$configsList
               |""".stripMargin
          )
      )

    }.toMap
}
