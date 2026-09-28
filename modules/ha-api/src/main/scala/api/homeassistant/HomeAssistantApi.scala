package api.homeassistant

import api.homeassistant.ws.HAWSApiLowLevel
import cats.syntax.all.*
import api.homeassistant.ws.protocol.client.CommandPhase.*
import api.homeassistant.ws.protocol.client.TriggerData
import api.homeassistant.ws.domain.*
import ha.runtime.definitions.*
import api.homeassistant.ws.protocol.server.Event
import cats.effect.{IO, Resource}
import fs2.Stream
import io.circe.{Decoder, Json}
import perok.ha.{GetStatesData, ServiceDomain}

import java.time.Instant

// TODO add caching of rest + json response. triggers and actions usually don't change
trait HomeAssistantApi[F[_]] {

  /** https://developers.home-assistant.io/docs/device_registry_index/ */
  def configDeviceRegistryList: F[Map[DeviceId, Device]]

  def configEntityRegistryList: F[Map[EntityId, Entity]]

  def configEntityRegistryGet(entityId: EntityId): F[Json]

  /** https://developers.home-assistant.io/docs/area_registry_index/ */
  def configAreaRegistryList: F[List[Area]]

  def configFloorRegistryList: F[List[Floor]]

  /** Admin-only; the machine connection is an admin. */
  def configAuthList: F[List[HaAccount]]

  def manifestList(): F[List[Manifest]]

  def configEntriesGet(
      type_filter: List[String] = List.empty,
      domain: Option[String] = None
  ): F[List[ConfigEntry]]

  def deviceAutomationTriggerList(deviceId: DeviceId): F[List[DeviceTrigger]]

  def deviceAutomationActionList(deviceId: DeviceId): F[List[Json]]

  def deviceAutomationActionCapabilities(action: Json): F[Json]

  def getConfigWS: F[Json]

  /** The connection token's owner: the machine on the shared feed, the user on
    * a connection opened with their OAuth token (issue #89).
    */
  def currentUser: F[HaUser]

  /** The subscribed set in full, then deltas, over one subscription, so no
    * snapshot fetch races it. `None` is the whole house. HA reads an empty
    * `entity_ids` as no filter, so `Some(Set.empty)` subscribes to everything:
    * a caller that wants nothing must not call this.
    */
  def entities(only: Option[Set[String]]): Resource[F, Stream[F, EntitiesEvent]]

  def event(event: Option[String]): Resource[F, Stream[F, Event]]

  /** Undecoded: payload shapes are event-type-specific. [[event]] is the typed
    * `state_changed` case.
    */
  def rawEvents(eventType: String): Resource[F, Stream[F, Json]]

  def trigger(data: TriggerData*): Resource[F, Stream[F, Json]]

  def callService(
      domain: String,
      service: String,
      entityId: String,
      serviceData: Json
  ): F[Json]

  def getStates: F[List[GetStatesData]]

  def getServices: F[List[ServiceDomain]]

  /** An entity with no rows — including one that does not exist — is absent
    * from the map, not an error.
    */
  def historyDuringPeriod(
      start: Instant,
      end: Instant,
      entityIds: List[String]
  ): F[Map[String, List[HistoryPoint]]]

  /** Only entities with a `state_class` have statistics; others are absent. */
  def statisticsDuringPeriod(
      start: Instant,
      end: Option[Instant],
      statisticIds: List[String],
      period: StatisticsPeriod
  ): F[Map[String, List[StatisticPoint]]]

  // Assumes | to_json as the end
  def templateFunc[Body: Decoder](template: String): F[Body]
}

object HomeAssistantApi {

  /** WS only: it covers everything REST did, so there is one connection to
    * supervise and one place for a reconnecting facade.
    */
  def fromWs(
      in: HAWSApiLowLevel[IO]
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

      def configAreaRegistryList: IO[List[Area]] =
        in.sendCommand(`config/area_registry/list`())

      def configFloorRegistryList: IO[List[Floor]] =
        in.sendCommand(`config/floor_registry/list`())

      def configAuthList: IO[List[HaAccount]] =
        in.sendCommand(`config/auth/list`())

      def manifestList(): IO[List[Manifest]] =
        in.sendCommand(`manifest/list`())

      def configEntriesGet(
          type_filter: List[String] = List.empty,
          domain: Option[String] = None
      ): IO[List[ConfigEntry]] =
        in.sendCommand(
          `config_entries/get`(
            // TODO type_filter and domain are not sent
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

      def entities(
          only: Option[Set[String]]
      ): Resource[IO, Stream[IO, EntitiesEvent]] =
        in.subscribeStream(
          subscribe_entities(only.map(_.toList.sorted))
        )

      def event(event: Option[String]): Resource[IO, Stream[IO, Event]] =
        // TODO `event` is ignored: this is always `state_changed`.
        in.subscribeStream(subscribe_events(Some("state_changed")))
          .map(_.evalMapChunk(_.as[Event].liftTo[IO]))

      // TODO fix into Event..
      def rawEvents(eventType: String): Resource[IO, Stream[IO, Json]] =
        in.subscribeStream(subscribe_events(Some(eventType)))

      def trigger(data: TriggerData*): Resource[IO, Stream[IO, Json]] =
        in.subscribeStream(subscribe_trigger(data.toList))

      def callService(
          domain: String,
          service: String,
          entityId: String,
          serviceData: Json
      ): IO[Json] =
        in.sendCommand(
          `call_service`(
            domain,
            service,
            serviceData,
            CallServiceTarget(entityId)
          )
        )

      def getStates: IO[List[GetStatesData]] =
        in.sendCommand(`get_states`())

      def historyDuringPeriod(
          start: Instant,
          end: Instant,
          entityIds: List[String]
      ): IO[Map[String, List[HistoryPoint]]] =
        in.sendCommand(
          `history/history_during_period`(start, end, entityIds)
        )

      def statisticsDuringPeriod(
          start: Instant,
          end: Option[Instant],
          statisticIds: List[String],
          period: StatisticsPeriod
      ): IO[Map[String, List[StatisticPoint]]] =
        in.sendCommand(
          `recorder/statistics_during_period`(start, end, statisticIds, period)
        )

      def getConfigWS: IO[Json] =
        in.sendCommand(`get_config`())

      def currentUser: IO[HaUser] =
        in.sendCommand(`auth/current_user`())

      def getServices: IO[List[ServiceDomain]] =
        in.sendCommand(`get_services`())

      // A `| tojson` template renders to a JSON-encoded string, so `Body=Json`
      // decodes to a `Json` string, not the structured value.
      def templateFunc[Body: Decoder](template: String): IO[Body] =
        in.subscribeStream(render_template(template))
          .use(_.head.compile.lastOrError)
          .flatMap(_.hcursor.downField("result").as[Body].liftTo[IO])
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
    def devices: IO[io.circe.Json] =
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
