//import scala.meta.* // scalameta for code generation. does not support dotty
import api.homeassistant.ws.HAWSApiLowLevel
import api.homeassistant.ws.client.WSCommandPhaseClient
import api.homeassistant.rest.restApi.*
import cats.effect.*
import scala.concurrent.duration.*
import perok.ha.*
import fh.api.FHApi

object AppHome extends IOApp.Simple {
  val run = (for {

    (api, wsApi) <- FHApi.fromEnv
    // _ <- service.postServiceApi("", "", "hello").toResource
    // https://community.home-assistant.io/t/devices-via-rest-api/455634/4
    _ <- program(api, wsApi)
  } yield ()).use_

  def program(api: HomeAssistantApiService[IO], wsApi: HAWSApiLowLevel[IO]) =
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

      _ <- wsApi.receiveStream.debug().compile.drain.background
      _ <- {
        // WE HAVE IT!
        wsApi
          .send(
            WSCommandPhaseClient.`device_automation/trigger/list`(
              "031d4786b1d7b98aa271b0de2298bc38"
            )
          )
          // .send(WSCommandPhaseClient.`config/device_registry/list`())
          .toResource
      }
      // _ <- {
      //  wsApi
      //    .send(WSCommandPhaseClient.subscribe_events(1, None))
      //    .toResource
      // }
      _ <- IO.sleep(10.seconds).toResource
    } yield ()
}
