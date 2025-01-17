package fh.codegen

//import scala.meta.* // scalameta for code generation. does not support dotty
import fh.util.*
import cats.effect.*
import cats.syntax.all.*
import org.http4s.Uri
import perok.ha.*
import fh.api.FHApi

import scala.util.chaining.*

object Plugin extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    for {
      haServer <- Uri.fromString(args(1)).liftTo[IO]
      outputDirectory = args(0)
      haSecret = args(2)
      _ <- program(outputDirectory, haServer, haSecret).use_
    } yield ExitCode.Success

  // TODO Get all sensors

  // TODO code gen
  // TODO create ingtegration: https://developers.home-assistant.io/docs/creating_component_index https://netdaemon.xyz/
  //

  def program(
      outputDirectory: String,
      api: Uri,
      secretToken: String
  ): Resource[IO, Unit] =
    for {
      (service, wsService) <- FHApi.from(api, secretToken)
      // _ <- service.postServiceApi("", "", "hello").toResource
      // _ <- hello.testit(service).debug("operatin").toResource
      (state, services) <- (
        service.getStates().map(_.output),
        service.getServicesApi().map(_.output)
      ).parTupled.toResource

      // (state, services) = (
      //   serializer.read[List[GetStatesData]]("test_state.blob"),
      //   serializer.read[List[ServiceDomain]]("test_services.blob")
      // )
      // _ = {
      //   serializer.write("test_state.blob", state)
      //   serializer.write("test_services.blob", services)
      // }

      codeGenResult = new CodeGen(state, services)
      _ = fh.util.writeToFile(
        s"$outputDirectory/ha/generated/CodeGenerated.scala",
        codeGenResult.entitiesCode
      )
      _ = fh.util.writeToFile(
        s"$outputDirectory/ha/generated/CodeGeneratedServices.scala",
        codeGenResult.serviceCode
      )
    } yield ()

  // https://github.com/net-daemon/netdaemon/blob/02e636d7c3dcc60859cbf248cba50c8de87b3dcf/src/HassModel/NetDaemon.HassModel.CodeGenerator/Helpers/EntityIdHelper.cs#L5-L6

  // "roborock", "vacuum", "switch", "light", "unifi"

  //
  // }
  // } // >>      wsService.subscribeStateChanged.use(_.take.debug("take"))
  // service.floors
  //   .debug("output")
}

// TODO should this wrapping be on the API layer as the types exposed?
// Then codegen with scalameta can produce the actual code here that is just variations of that
class CodeGen(entities: List[GetStatesData], services: List[ServiceDomain]) {

  def paramNameSafe(in: String) =
    in match
      case "type" => "`type`" // protected name
      case other  => other

  def objectNameSafe(in: String) =
    in match
      case a @ "notify" => "notify1" // protected name
      case "type"       => "type1" // protected name
      // TODO only look at if safe letters instead
      case other if List(" ", "/", "-", "@", ".").exists(other.contains(_)) =>
        s"`$other`"
      case other => other

  val entitiesMap =
    entities.groupBy(_.entity_id.value.split("\\.").headOption.orEmpty)

  val entitiesDomains = entitiesMap.keys.toList

  // To only deal with things with entities now in the beginning
  val servicesWithEntities =
    services
      .filter(s => entitiesDomains.contains(s.domain))
      // Remove advanced_fields as it's a lot of noise atm
      .map(s =>
        s.copy(services =
          s.services.view
            .mapValues(ss =>
              ss.copy(fields = ss.fields.removed("advanced_fields"))
            )
            .toMap
        )
      )

  // services.find(_.domain == "light").pipe(pprint.pprintln(_, height = 2000))
  // pprint.pprintln(entitiesMap.get("binary_sensor"))
  // pprint.pprintln(servicesWithEntities.find(_.domain == "light"))
  // services
  //   .groupBy(_.domain)
  //   .mapValues(_.length)
  //   .tap(pprint.pprintln(_))

  val serviceCode = services
    .map { domain =>
      val servicesInDomain = domain.services.view
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

  extension (in: GetStatesData)
    def domain = in.entity_id.value.split("\\.").head
    def name = in.entity_id.value.split("\\.").tail.mkString

  val entitiesCode = {
    val domains = entitiesMap.toList
      .sortBy(_._1)
      .map { (domain, entities) =>
        val lowercaseAfterSpace = " ([a-z]){1}".r
        val startOfStringLowerCase = "^[A-Z]{1}".r

        val entitiesNamed = entities
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

        val entitiesMapped =
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

object Automation {

  class Thing:
    def pre(f: Thing => Boolean) = ???

  /*
for {
  // All off these things collect inn all sensor values.
  // Maybe not monads? But something that can accumulate all things that are
  // wanted. So that it's statically known
  thing <- state(HA.sensor.thing1).preCondition(_.something > 1)
} yield ()
   */
}
