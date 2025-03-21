package fh.codegen

import fh.util.*
import cats.syntax.all.*
import perok.ha.*
import scala.util.chaining.*
import fh.codegen.utils.Helpers.*
// TODO should this wrapping be on the API layer as the types exposed?
// Then codegen with scalameta can produce the actual code here that is just variations of that

// TODO migrate to StaticCode
class CodeGenServices(
    services: List[ServiceDomain]
) {

  // services.find(_.domain == "light").pipe(pprint.pprintln(_, height = 2000))
  // pprint.pprintln(entitiesMap.get("binary_sensor"))
  // pprint.pprintln(servicesWithEntities.find(_.domain == "light"))
  // services
  //   .groupBy(_.domain)
  //   .mapValues(_.length)
  //   .tap(pprint.pprintln(_))

  val serviceCode: String = services
    .map { domain =>
      val servicesInDomain: List[(String, Option[String], Service)] =
        domain.services.view
          .map((serviceId, service) =>
            val domainThing =
              service.target.collect {
                case ServiceTargetEntity(
                      _,
                      Some(List(ServiceTargetEntityEntity(Some(List(domain))))),
                      _,
                      _,
                      _
                    ) =>
                  domain
              }

            (serviceId, domainThing, service)

            // service.fields.view.map((fieldId, field) =>
            //  println
            //  field.filter.map(_.supported_features)
            // )
          )
          .toList

      val services2 = servicesInDomain
        .groupBy(_._2)
        .view
        .filter(_._2.nonEmpty)
        .map { (targetDomain, stuff) =>

          val domainDifferent = targetDomain =!= domain.domain.some

          val services = stuff
            .map((serviceId, _, service) =>
              val name = objectNameSafe(service.name)

              val attributes = service.fields.map((fieldId, field) =>
                val isNotRequired = field.required.forall(!_)
                // TODO default value
                val tpe =
                  if isNotRequired then "Option[String] = None" else "String"
                // TODO target?

                // todo fieldId - how to encode so that we can
                // use nonewrapped version
                s"${paramNameSafe(fieldId)}: $tpe, // ${field.filter.map(_.supported_features)}"
              )

              // TODO should be objects and not case classes?
              // TODO add val static = true field? Could be useful for debugging n stuff
              s"""
                 | /*
                 |   ${pprint(service, escapeUnicode = true).plainText}
                 | */
                 |   /**
                 |   ${service.description}
                 |   */
                 |   case class $name(
                    ${attributes.mkString("\n")}
                 |   ) extends Service {
                 |     val domain: String = \"${domain.domain}\"
                 |     val serviceId: String = \"$serviceId\"
                 | }
                """.stripMargin
            )

          targetDomain match {
            case Some(targetDomain) =>
              s"""
                 |${
                  if domainDifferent then
                    s"object ${objectNameSafe(domain.domain)} {"
                  else ""
                }
                 | object ${objectNameSafe(targetDomain)} {
                 |   ${services.mkString("\n")}
                 |${if domainDifferent then "}" else ""}
                 |}
                 |
                 |""".stripMargin
            case None => ""
          }
        }
        .toList
        .filter(_.nonEmpty)

      (domain, services2)
    }
    .filter(_._2.nonEmpty)
    .map { (domain, servicesInDomain) =>
      servicesInDomain.mkString("\n")

    }
    .pipe { domains =>
      s"""
         |package ha.generated
         |
         |import ha.runtime.definitions.*
         |
         |object services {
         |${domains.mkString("\n")}
         |}
      """.stripMargin
    }
}
