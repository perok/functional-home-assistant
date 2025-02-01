//import scala.meta.* // scalameta for code generation. does not support dotty

import api.homeassistant.ws.HAWSApi
import api.homeassistant.rest.restApi.*
import api.homeassistant.ws.client.TriggerData
import cats.Show
import cats.data.NonEmptyList
import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import perok.ha.*
import fh.api.FHApi
import io.circe.Json
import util.given

object AppHome extends IOApp.Simple {
  val run = (for {

    (api, wsApi) <- FHApi.fromEnv
    // _ <- service.postServiceApi("", "", "hello").toResource
    // https://community.home-assistant.io/t/devices-via-rest-api/455634/4
    _ <- program(api, wsApi).toResource
  } yield ()).use_

  def program(api: HomeAssistantApiService[IO], wsApi: HAWSApi[IO]) =
    for {
      // _ <- hello.testit(api).debug("Operation").toResource
      _ <- api.floors.debug("floors")

      allEntities <- wsApi.configEntityRegistryList.debug("entities")

      allDevices <- wsApi.configDeviceRegistryList.nested
        .map(device => (device.id, device))
        .value
        .map(_.toMap)
        .debug("Devices")

      allTriggers <- allDevices.values.toSeq
        .parTraverseN(10) { device =>
          wsApi
            .deviceAutomationTriggerList(device.id)
            .map(triggers => (device.id, triggers))
        }
        .map(_.toMap.mapFilter(NonEmptyList.fromList))
        .debug("done")

      _ <- allEntities
        .find(e => e.device_id.nonEmpty && e.name.nonEmpty)
        .traverse { entity =>

          val d = allDevices.get(entity.device_id.get)

          pprint.pprintln(entity)
          pprint.pprintln(d)

          wsApi
            .deviceAutomationTriggerList(d.get.id)
            .debug("automatins")
        }
        .whenA(false)
      _ <- wsApi
        .trigger(TriggerData.device(Json.Null))
        .use(_.take.debug("trigger"))
        .whenA(false)
    } yield ()
}

object util {

  import _root_.pprint.PPrinter

  /** Helper pprint for creating print of case classes that are easy to copy
    * into tests
    */
  val pprint: PPrinter = _root_.pprint
    .copy(additionalHandlers = {
      case a: NonEmptyList[Any] =>
        _root_.pprint.Tree.Apply(
          "NonEmptyList",
          a.toIterable.iterator.map(v =>
            pprint.treeify(
              v,
              _root_.pprint.defaultEscapeUnicode,
              _root_.pprint.defaultShowFieldNames
            )
          )
        )
      case a: io.circe.Json =>
        _root_.pprint.Tree.Apply(
          "Json",
          Iterator(
            pprint.treeify(
              a.noSpaces,
              _root_.pprint.defaultEscapeUnicode,
              _root_.pprint.defaultShowFieldNames
            )
          )
        )

      case a: Some[Any] =>
        _root_.pprint.Tree.Apply(
          "Some",
          Iterator(
            pprint.treeify(
              a.value,
              _root_.pprint.defaultEscapeUnicode,
              _root_.pprint.defaultShowFieldNames
            )
          )
        )
      case a: org.http4s.Uri =>
        _root_.pprint.Tree.Literal(
          s"org.http4s.Uri.unsafeFromString(${a.renderString})"
        )
      case a: java.time.LocalDate =>
        _root_.pprint.Tree.Literal(
          s"java.time.LocalDate.of(${a.getYear}, ${a.getMonthValue}, ${a.getDayOfMonth})"
        )
      case a: java.time.Instant =>
        _root_.pprint.Tree.Literal(
          s"java.time.Instant.parse(\"${a.toString}\")"
        )
    })

  given [A]: Show[A] =
    Show.show(something => pprint.apply(something, height = 30).toString)

}
