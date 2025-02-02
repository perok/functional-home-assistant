package fh.codegen

import fh.util.*
import cats.syntax.all.*
import perok.ha.*
import Helpers.*

class CodeGenEntities(
    entities: List[GetStatesData]
) {

  val entitiesMap: Map[String, List[GetStatesData]] =
    entities.groupBy(_.entity_id.value.split("\\.").headOption.orEmpty)

  val entitiesDomains: List[String] = entitiesMap.keys.toList

  // services.find(_.domain == "light").pipe(pprint.pprintln(_, height = 2000))
  // pprint.pprintln(entitiesMap.get("binary_sensor"))
  // pprint.pprintln(servicesWithEntities.find(_.domain == "light"))
  // services
  //   .groupBy(_.domain)
  //   .mapValues(_.length)
  //   .tap(pprint.pprintln(_))

  extension (in: GetStatesData)
    def domain = in.entity_id.value.split("\\.").head
    def name = in.entity_id.value.split("\\.").tail.mkString

  val entitiesCode = {
    val domains = entitiesMap.toList
      .sortBy(_._1)
      .map { (domain, entities) =>
        val lowercaseAfterSpace = " ([a-z]){1}".r
        val startOfStringLowerCase = "^[A-Z]{1}".r

        val entitiesNamed: Map[String, GetStatesData] = entities
          .map { entity =>
            val name = entity.attributes.friendly_name
              .map(name =>
                val round1 =
                  lowercaseAfterSpace
                    .replaceAllIn(name.trim, m => m.group(0).toUpperCase())
                // .replaceAll(" ", "")
                // .replaceAll("-", "_")
                // .replaceAll("/", "_")
                // .replaceAll("\\.", "")

                // TODO uncapitalize https://commons.apache.org/proper/commons-lang/javadocs/api-2.6/org/apache/commons/lang/StringUtils.html#uncapitalize(java.lang.String)
                startOfStringLowerCase
                  .replaceAllIn(round1, _.group(0).toLowerCase())
              )
              .getOrElse(entity.name)

            (name, entity)
          }
          .groupBy(_._1)
          .view
          .mapValues(_.map(_._2))
          .flatMap {
            case (name, List(entity)) =>
              Map(name -> entity)
            case (name, entities) =>
              entities
                .mapWithIndex((entity, index) =>
                  Map(s"${name}_$index" -> entity)
                )
                .flatten
          }
          .view
          .map((name, entity) => (objectNameSafe(name), entity))
          .toMap

        val entitiesMapped: List[String] =
          entitiesNamed.toList.sortBy(_._2.entity_id.value).map {
            (name, entity) =>
              val device_class = entity.attributes.device_class
                .map(s => s"\"$s\"")

              val integration = entity.attributes.integration
                .map(s => s"\"$s\"")

              val supported_features = entity.attributes.supported_features
                .map(fh.util.getBitValues)
                .getOrElse(List.empty)

              // TODO supported feature
              // TODO find and remove hidden entities
              // Filter away state = DString("unavailable") ? as an option?
              s"""
                 | /*
                 | ${pprint(entity).plainText}
                 | */
                 | val $name = Thing(id = \"${entity.entity_id}\", device_class = $device_class, integration = $integration, supportedFeatures = $supported_features)
          """.stripMargin
          }

        val entitiesCode = entitiesMapped.mkString("\t", "\n", "")

        // TODO also add grouping within "integration"
        s"""
           |object ${objectNameSafe(domain)} {
           |  ${entitiesCode}
           |}""".stripMargin
      }
      .mkString("\n")

    s"""
       |package ha.generated
       |
       |import ha.runtime.definitions.*
       |
       |object entities {
       |$domains
       |}
    """.stripMargin
  }

}
