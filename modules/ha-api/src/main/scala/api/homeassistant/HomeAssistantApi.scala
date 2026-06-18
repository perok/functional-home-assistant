package api.homeassistant

import api.DocumentJson
import api.homeassistant.ws.HAWSApiLowLevel
import cats.syntax.all.*
import api.homeassistant.ws.protocol.client.CommandPhase.*
import api.homeassistant.ws.protocol.client.TriggerData
import api.homeassistant.ws.domain.*
import ha.runtime.definitions.*
import api.homeassistant.ws.protocol.server.Event
import cats.effect.std.QueueSource
import cats.effect.{IO, Resource}
import io.circe.{Decoder, Json}
import perok.ha.{GetStatesData, HomeAssistantApiService, ServiceDomain}

// TODO add caching of rest + json response. triggers and actions usually don't change
trait HomeAssistantApi[F[_]] {

  /** https://developers.home-assistant.io/docs/device_registry_index/
    * @return
    */
  def configDeviceRegistryList: IO[Map[DeviceId, Device]]

  def configEntityRegistryList
      : IO[Map[EntityId, Entity]] // Exposes entity_id and device_id

  def configEntityRegistryGet(entityId: EntityId): IO[Json]

  // Not interesting
  def manifestList(): IO[List[Manifest]]

  def configEntriesGet(
      type_filter: List[String] = List.empty,
      domain: Option[String] = None
  ): IO[List[ConfigEntry]]

  def deviceAutomationTriggerList(deviceId: DeviceId): IO[List[DeviceTrigger]]

  def deviceAutomationActionList(deviceId: DeviceId): IO[List[Json]]

  def deviceAutomationActionCapabilities(action: Json): IO[Json]

  def getConfigWS: IO[Json]

  def getServicesWS: IO[Json]

  def event(event: Option[String]): Resource[IO, QueueSource[IO, Event]]
  def trigger(data: TriggerData*): Resource[IO, QueueSource[IO, Json]]

  def getStates: IO[List[GetStatesData]]

  def getServices: IO[List[ServiceDomain]]

  // Assumes | to_json as the end
  def templateFunc[Body: Decoder](template: String): IO[Body]
}

object HomeAssistantApi {
  def fromLowLevel(
      in: HAWSApiLowLevel[IO],
      restApi: HomeAssistantApiService[IO]
  ): HomeAssistantApi[IO] =
    new HomeAssistantApi[IO] {
      def configDeviceRegistryList: IO[Map[DeviceId, Device]] =
        in.sendCommand(`config/device_registry/list`())
          .nested
          .filter(_.disabled_by.isEmpty)
          .map(device => (device.id, device))
          .value
          .map(_.toMap)

      def configEntityRegistryList: IO[Map[EntityId, Entity]] =
        in.sendCommand(`config/entity_registry/list`())
          .nested
          .filter(e => e.disabled_by.isEmpty || e.hidden_by.isEmpty)
          .map(device => (device.id, device))
          .value
          .map(_.toMap)

      def configEntityRegistryGet(entityId: EntityId): IO[Json] =
        in.sendCommand(`config/entity_registry/get`(entityId))

      def manifestList(): IO[List[Manifest]] =
        in.sendCommand(`manifest/list`())

      def configEntriesGet(
          type_filter: List[String] = List.empty,
          domain: Option[String] = None
      ): IO[List[ConfigEntry]] =
        in.sendCommand(
          `config_entries/get`(
            //   Option.when(type_filter.nonEmpty)(type_filter),
            //  domain
          )
        ).nested
          // Will crash on codegen if things are not there
          .filter(ce => List("loaded", "setup_error").contains(ce.state))
          .value

      def deviceAutomationTriggerList(
          deviceId: DeviceId
      ): IO[List[DeviceTrigger]] =
        in.sendCommand(`device_automation/trigger/list`(deviceId))

      def deviceAutomationActionList(deviceId: DeviceId): IO[List[Json]] =
        in.sendCommand(`device_automation/action/list`(deviceId))

      def deviceAutomationActionCapabilities(action: Json): IO[Json] =
        in.sendCommand(`device_automation/action/capabilities`(action))

      def event(event: Option[String]): Resource[IO, QueueSource[IO, Event]] =
        in.subscribeStream(subscribe_events(Some("state_changed")))

      def trigger(data: TriggerData*): Resource[IO, QueueSource[IO, Json]] =
        in.subscribeStream(subscribe_trigger(data.toList))

      def getStates: IO[List[GetStatesData]] =
        restApi.getStates().map(_.output)

      def getConfigWS: IO[Json] =
        in.sendCommand(`get_config`())

      def getServicesWS: IO[Json] =
        in.sendCommand(`get_services`())

      def getServices: IO[List[ServiceDomain]] =
        restApi.getServicesApi().map(_.output)

      def templateFunc[Body: Decoder](template: String): IO[Body] =
        restApi
          .template(template)
          .flatMap(_.output.decode(using DocumentJson.decoder).liftTo[IO])
          .flatMap(_.as[Body].liftTo[IO])
    }

  extension (service: HomeAssistantApi[IO])

    def areas: IO[List[String]] =
      service
        .templateFunc[List[String]]("{{ areas() | to_json() }}")

    def floors: IO[List[String]] =
      service
        .templateFunc[List[String]]("{{ floors() | to_json() }}")

    def floorArea(floor: String): IO[String] =
      service
        .templateFunc[String](s"{{ floor_areas('$floor') | to_json }}")

    // devices with entities https://community.home-assistant.io/t/devices-via-rest-api/455634/3
    def devicec(): IO[io.circe.Json] =
      service.templateFunc[io.circe.Json]("""
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
}
