//import scala.meta.* // scalameta for code generation. does not support dotty
import api.homeassistant.ws.HAWSApi
import api.homeassistant.rest.restApi.*
import cats.effect.*

import scala.concurrent.duration.*
import perok.ha.*
import fh.api.FHApi
import io.circe.Json
import util.given

object AppHome extends IOApp.Simple {
  val run = (for {

    (api, wsApi) <- FHApi.fromEnv
    // _ <- service.postServiceApi("", "", "hello").toResource
    // https://community.home-assistant.io/t/devices-via-rest-api/455634/4
    _ <- program(api, wsApi)
  } yield ()).use_

  def program(api: HomeAssistantApiService[IO], wsApi: HAWSApi[IO]) =
    for {
      // _ <- hello.testit(api).debug("Operation").toResource
      _ <- api.floors.debug("floors").toResource
      // TODO how to get information on device triggers
      _ <-
        api // devices with entities https://community.home-assistant.io/t/devices-via-rest-api/455634/3
          .templateFunc[io.circe.Json]("""
          |    {% set devices = states | map(attribute='entity_id') | map('device_id') | unique | reject('eq', None) | list %}
          |
          |    {%- set ns = namespace(devices = []) %}
          |
          |    {%- for device in devices %}
          |      {%- set entities = device_entities(device) | list %}
          |      {%- if entities %}
          |        {%- set ns.devices = ns.devices +  [ { device: { "name": device_attr(device, "name"), "entities": [ entities ] } } ] %}
          |      {%- endif %}
          |    {%- endfor %}
          |
          |    {{ ns.devices | to_json() }}
          |""".stripMargin)
          // TODO Use this to add device categorization
          // Map[DeviceId, { name: DeviceName, entities: List[EntityId] }]
          .map(_.spaces2)
          // .debug("DEvices")
          .toResource

      // _ <- wsApi.receiveStream.debug().compile.drain.background

      _ <- wsApi.configDeviceRegistryList.debug("lol").toResource
      _ <- wsApi
        .deviceAutomationTriggerList(
          "031d4786b1d7b98aa271b0de2298bc38"
        )
        .debug("test")
        .toResource

      // _ <- {
      //  wsApi
      //    .send(WSCommandPhaseClient.subscribe_events(1, None))
      //    .toResource
      // }
      _ <- IO.sleep(10.seconds).toResource
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
