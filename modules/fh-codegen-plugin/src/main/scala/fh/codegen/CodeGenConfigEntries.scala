package fh.codegen

import cats.*
import cats.syntax.all.*
import api.homeassistant.ws.domain.{ConfigEntry, Device}
import api.homeassistant.ws.domain.*
import ha.runtime.definitions.*
import fh.codegen.utils.{Helpers, StaticCode}

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
          _.thing.primary_config_entry === Some(configEntry.entry_id)
        )

      val configsList = consume(
        devicesInConfig,
        0,
        entity =>
          // TODO should be object.type as type? usefull when we add more stuff
          s"val ${Helpers.objectNameSafe(entity.name)}: ${entity.toRootReferenceAsObjectType} = ${entity.almostFullyQualifiedName}"
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
