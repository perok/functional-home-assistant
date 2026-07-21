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
import perok.ha.{GetStatesData, Service, ServiceDomain}
import smithy4s.{Document, Schema}

// TODO add caching of rest + json response. triggers and actions usually don't change
//
// The trait is effect-polymorphic in `F`: methods return `F[...]` /
// `Resource[F, *]`, not a hardcoded `IO`. The only production instance is built
// at `F = IO` ([[HomeAssistantApi.fromWs]]) — consumers still work against
// `HomeAssistantApi[IO]` — but honoring `F` keeps the type honest (a test double
// or alternative interpreter can pick another effect) and confines the effect to
// this "machinery" boundary.
trait HomeAssistantApi[F[_]] {

  /** https://developers.home-assistant.io/docs/device_registry_index/
    * @return
    */
  def configDeviceRegistryList: F[Map[DeviceId, Device]]

  def configEntityRegistryList
      : F[Map[EntityId, Entity]] // Exposes entity_id and device_id

  def configEntityRegistryGet(entityId: EntityId): F[Json]

  // Not interesting
  def manifestList(): F[List[Manifest]]

  def configEntriesGet(
      type_filter: List[String] = List.empty,
      domain: Option[String] = None
  ): F[List[ConfigEntry]]

  def deviceAutomationTriggerList(deviceId: DeviceId): F[List[DeviceTrigger]]

  def deviceAutomationActionList(deviceId: DeviceId): F[List[Json]]

  def deviceAutomationActionCapabilities(action: Json): F[Json]

  def getConfigWS: F[Json]

  def getServicesWS: F[Json]

  def event(event: Option[String]): Resource[F, QueueSource[F, Event]]

  /** Subscribe to an arbitrary HA event type, yielding the raw event JSON.
    * Event payload shapes are event-type-specific (`entity_registry_updated`
    * carries `{action, entity_id}`, not a state), so no decoding is imposed
    * here — [[event]] is the typed `state_changed` special case.
    */
  def rawEvents(eventType: String): Resource[F, QueueSource[F, Json]]

  def trigger(data: TriggerData*): Resource[F, QueueSource[F, Json]]

  /** Call a Home Assistant service/action on an entity via the WebSocket API.
    * `serviceData` carries extra parameters (e.g. `{ "brightness": 128 }`).
    */
  def callService(
      domain: String,
      service: String,
      entityId: String,
      serviceData: Json
  ): F[Json]

  def getStates: F[List[GetStatesData]]

  def getServices: F[List[ServiceDomain]]

  // Assumes | to_json as the end
  def templateFunc[Body: Decoder](template: String): F[Body]
}

object HomeAssistantApi {

  /** Decode a WS JSON payload into a smithy4s type `A` via its schema, bridging
    * circe -> smithy `Document` ([[api.DocumentJson.fromJson]]). This is how
    * the WS-only API returns the same typed shapes the REST leg used to,
    * without a second HTTP client on a second connection.
    */
  private def decodeVia[A](json: Json, schema: Schema[A]): IO[A] =
    Document.Decoder
      .fromSchema(schema)
      .decode(DocumentJson.fromJson(json))
      .leftMap(e =>
        new Exception(s"WS response decode failed: ${e.getMessage}")
      )
      .liftTo[IO]

  /** Build the unified API over a single Home Assistant WebSocket connection.
    * Everything — states, services, templates, subscriptions, `call_service` —
    * rides this one transport (HA's WS API is a superset of what this app used
    * REST for), so the whole API has exactly one connection to supervise and
    * one place for a reconnecting facade to sit.
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
        // The raw stream decoded into the state_changed shape (the only event
        // type this method has ever subscribed to).
        in.subscribeStream(subscribe_events(Some("state_changed")))
          .map(_.map(json => json.as[Event].fold(throw _, identity)))

      def rawEvents(eventType: String): Resource[IO, QueueSource[IO, Json]] =
        in.subscribeStream(subscribe_events(Some(eventType)))

      def trigger(data: TriggerData*): Resource[IO, QueueSource[IO, Json]] =
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

      // WS `get_states` returns the same state representation REST `/api/states`
      // did, so it decodes with the same schema.
      def getStates: IO[List[GetStatesData]] =
        in.sendCommand(`get_states`())
          .flatMap(decodeVia(_, Schema.list(GetStatesData.schema)))

      def getConfigWS: IO[Json] =
        in.sendCommand(`get_config`())

      def getServicesWS: IO[Json] =
        in.sendCommand(`get_services`())

      // WS `get_services` is an OBJECT keyed by domain (`{domain: {service:
      // ...}}`), where REST returned an ARRAY of `{domain, services}`; decode
      // the object shape and re-key it into the same `List[ServiceDomain]`.
      def getServices: IO[List[ServiceDomain]] =
        in.sendCommand(`get_services`())
          .flatMap(
            decodeVia(
              _,
              Schema
                .map(Schema.string, Schema.map(Schema.string, Service.schema))
            )
          )
          .map(_.toList.map { case (domain, services) =>
            ServiceDomain(domain, services)
          })

      // `render_template` is a subscription: subscribe, take the single initial
      // render, release (unsubscribe). The first-event race that would have
      // dropped that lone render is fixed in `subscribeStream`. NOTE: a
      // `| tojson` template renders to a JSON-encoded STRING (HA does not parse
      // the filter output back), so `Body=Json` decodes to a `Json` string, not
      // the structured value — a caller that wants the object parses it
      // (`DataDump.parseIfString`).
      def templateFunc[Body: Decoder](template: String): IO[Body] =
        in.subscribeStream(render_template(template))
          .use(_.take)
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
