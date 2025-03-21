package fh.codegen

import cats.syntax.all.*
import api.homeassistant.ws.domain.{ConfigEntry, Device}
import api.homeassistant.ws.domain.*
import ha.runtime.definitions.*
import fh.codegen.utils.{Helpers, StaticCode}

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
          s"val ${Helpers.objectNameSafe(entity.name)}: ${entity.toRootReferenceAsObjectType} = ${entity.almostFullyQualifiedName}"
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
