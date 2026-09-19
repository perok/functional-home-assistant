package api.homeassistant.ws.protocol

import cats.effect.IO
import api.DocumentJson
import api.homeassistant.ws.domain.*
import api.homeassistant.ws.protocol.client.CommandPhase.unsubscribe_events
import server.{WSCommandPhaseServer, WSHAError}
import api.homeassistant.ws.utils.defaults.given
import ha.runtime.definitions.{DeviceId, EntityId, IsDeviceTrigger}
import io.circe.*
import io.circe.derivation.ConfiguredEncoder
import io.circe.syntax.*
import cats.syntax.all.*
import perok.ha.{GetStatesData, ServiceDomain, ServicesData}

object client {
  // https://github.com/zachowj/node-red-contrib-home-assistant-websocket/blob/main/src/homeAssistant/Websocket.ts#L659

  /** What a command expects back. A pure marker; decoding lives on the subtypes
    * so the transport stays codec-agnostic (routes by id, calls the command's
    * own decoder).
    */
  sealed trait CommandResponse[R] {
    def decodeMessage(payload: server.WSCommandPhaseServerPayload): IO[R]
  }

  object CommandResponse {

    trait WithFinalization[R] {
      def finalizationMessage(
          id: Int
      ): CommandPhase & CommandResponse.WithSingleResponse[R]
    }

    /** The first response is the result */
    trait WithSingleResponse[R] extends CommandResponse[R]

    // An HA subscription: the `result` ack (AsResult[Unit]) plus a per-event
    // stream decoder; `unsubscribe_events` cancels it.
    trait AsStream[R] extends AsResult[Unit] with WithFinalization[Unit] {
      def decodeStreamMessage(
          payload: server.WSCommandPhaseServerPayload
      ): IO[R]

      def finalizationMessage(
          id: Int
      ): CommandPhase & CommandResponse.WithSingleResponse[Unit] =
        unsubscribe_events(id)
    }

    object AsStream {

      /** The message's `event` object, decoded into `R`. */
      trait AsEventOf[R](using Decoder[R]) extends AsStream[R] {
        def decodeStreamMessage(
            payload: server.WSCommandPhaseServerPayload
        ): IO[R] =
          payload.parsedPayload.liftTo[IO].flatMap {
            case WSCommandPhaseServer.event(event) => event.as[R].liftTo[IO]
            case other                             =>
              IO.raiseError(
                new Exception(s"expected a event message, got: $other")
              )
          }
      }

      /** The raw `event` object of the message, undecoded. The typed
        * [[WSCommandPhaseServer]] enum decodes `event` into the
        * `state_changed`-shaped [[Event]], but HA event payloads are
        * event-type-specific (e.g. `*_registry_updated` carries
        * `{action, …_id}`) — the raw form is the one that works for all of
        * them; callers decode what they subscribed to.
        */
      trait AsEvent extends AsEventOf[Json]

      trait AsTrigger extends AsStream[Json] {
        def decodeStreamMessage(
            payload: server.WSCommandPhaseServerPayload
        ): IO[Json] =
          payload.parsedPayload.liftTo[IO].flatMap {
            case WSCommandPhaseServer.trigger(event) => IO.pure(event)
            case other                               =>
              IO.raiseError(
                new Exception(s"expected a trigger message, got: $other")
              )
          }
      }
    }

    /** A one-shot `result`, decoded via a circe [[Decoder]]; raises HA's
      * [[WSHAError]] on a failure frame. `get_states`/`get_services` plug a
      * smithy-schema decoder in here, so `R` is the final typed value.
      */
    trait AsResult[R](using val resultDecoder: Decoder[R])
        extends CommandResponse.WithSingleResponse[R] {

      def decodeMessage(payload: server.WSCommandPhaseServerPayload): IO[R] =
        payload.parsedPayload.liftTo[IO].flatMap {
          case WSCommandPhaseServer.result(
                true,
                result,
                _
              ) =>
            result
              // A bare success ack carries no `result`; decode `null`, which a
              // `Unit` result (a subscribe ack) expects.
              .getOrElse(Json.Null)
              .as[R](using resultDecoder)
              .liftTo[IO]
          case WSCommandPhaseServer.result(
                false,
                _,
                error
              ) =>
            IO.raiseError[R](
              error
                .flatMap(json => json.as[WSHAError].toOption)
                .getOrElse(
                  new Exception(
                    s"Result parsing failed. Error:\n$error"
                  )
                )
            )
          case other =>
            IO.raiseError(
              new Exception(s"expected a result message, got: $other")
            )
        }

    }

    trait AsPong extends CommandResponse.WithSingleResponse[Unit] {
      def decodeMessage(payload: server.WSCommandPhaseServerPayload): IO[Unit] =
        payload.parsedPayload.liftTo[IO].flatMap {
          case WSCommandPhaseServer.pong() => IO.unit
          case other                       =>
            IO.raiseError(
              new Exception(s"expected a pong message, got: $other")
            )
        }
    }
  }

  sealed trait CommandPhase derives ConfiguredEncoder

  // https://github.com/home-assistant-ecosystem/home-assistant-cli
  // All websocket calls https://github.com/search?q=repo%3Ahome-assistant%2Fcore+%40websocket_api.websocket_command%28&type=code&p=1
  object CommandPhase {

    /** Target of a `call_service` command. Entity-scoped; extend with
      * area/device ids if needed.
      */
    case class CallServiceTarget(entity_id: String) derives ConfiguredEncoder

    // Idle-keepalive heartbeat; HA answers with a `pong` frame (see AsPong).
    // https://developers.home-assistant.io/docs/api/websocket/#pings-and-pongs
    case class ping() extends CommandPhase with CommandResponse.AsPong
        derives ConfiguredEncoder

    // call_service https://developers.home-assistant.io/docs/api/websocket#calling-a-service-action
    // service_data carries arbitrary parameters (e.g. brightness); target is the
    // entity to act on. Kept null-free so HA does not receive stray null fields.
    case class `call_service`(
        domain: String,
        service: String,
        service_data: Json,
        target: CallServiceTarget
    ) extends CommandPhase
        with CommandResponse.AsResult[Json] derives ConfiguredEncoder

    // get_config https://github.com/home-assistant/core/blob/a98bb96325cf50d4ca77b68573b53c253ff673e1/homeassistant/components/websocket_api/commands.py#L515
    case class `get_config`()
        extends CommandPhase
        with CommandResponse.AsResult[Json] derives ConfiguredEncoder

    /** The feature-enablement phase, sent once right after auth.
      *
      * `coalesce_messages` lets HA pack everything pending in one event-loop
      * tick into a SINGLE frame — so a burst of entity changes arrives together
      * instead of one frame each. Note it changes the framing UNCONDITIONALLY:
      * once enabled, every frame is a JSON ARRAY of payloads, even a lone one
      * (verified on 2026.7.2).
      * https://developers.home-assistant.io/docs/api/websocket/#feature-enablement-phase
      */
    case class supported_features(
        features: Map[String, Int] = Map("coalesce_messages" -> 1)
    ) extends CommandPhase
        with CommandResponse.AsResult[Unit] derives ConfiguredEncoder

    // get_services https://developers.home-assistant.io/docs/api/websocket#fetching-service-actions
    // HA answers `{domain: {service: ...}}`; the schema-derived decoder yields
    // the wrapper, so the domain map is unwrapped here.
    case class `get_services`()
        extends CommandPhase
        with CommandResponse.AsResult[List[ServiceDomain]](using
          DocumentJson.circeDecoderFor(using ServicesData.schema).map(_.value)
        ) derives ConfiguredEncoder

    // get_states https://developers.home-assistant.io/docs/api/websocket#fetching-states
    // The WS equivalent of REST `/api/states`: the same state representation, so
    // the result decodes with the same shape the REST leg used.
    case class `get_states`()
        extends CommandPhase
        with CommandResponse.AsResult[List[GetStatesData]](using
          DocumentJson.circeDecoderFor(using
            smithy4s.Schema.list(GetStatesData.schema)
          )
        ) derives ConfiguredEncoder

    // render_template https://developers.home-assistant.io/docs/api/websocket#render-a-template
    // A SUBSCRIPTION, not a one-shot result: HA acks with `result`, then pushes
    // `event` messages `{result, listeners}` — and re-pushes whenever a
    // referenced entity changes. A one-shot caller subscribes, takes the first
    // event's `result`, and releases (the generic `unsubscribe_events` cancels
    // it). `report_errors` makes a template error arrive as an `error` event
    // rather than silently sticking.
    case class render_template(template: String, report_errors: Boolean = true)
        extends CommandPhase
        with CommandResponse.AsStream.AsEvent derives ConfiguredEncoder

    //
    // Configs
    //

    case class `manifest/list`( // integrations: Option[String]
    ) extends CommandPhase
        with CommandResponse.AsResult[List[Manifest]] derives ConfiguredEncoder

    // TODO config_entries/* https://github.com/home-assistant/core/blob/7c9d30eb067f6d7ae9b0315f7d77ed5e01e5a1d7/homeassistant/components/config/config_entries.py#L480
    case class `config_entries/get`(
        // type_filter: Option[List[String]],
        // domain: Option[String]
    ) extends CommandPhase
        with CommandResponse.AsResult[List[ConfigEntry]]
        derives ConfiguredEncoder

    // https://github.com/home-assistant/core/blob/dev/homeassistant/components/config/device_registry.py
    // https://github.com/home-assistant/core/blob/efcfd97d1b4a3485ae754c821a65a581491cf677/homeassistant/helpers/device_registry.py#L83-L105
    case class `config/device_registry/list`()
        extends CommandPhase
        with CommandResponse.AsResult[List[Device]] derives ConfiguredEncoder

    case class `config/entity_registry/list`()
        extends CommandPhase
        with CommandResponse.AsResult[List[Entity]] derives ConfiguredEncoder

    // https://github.com/home-assistant/core/blob/164d38ac0df5b590ef18dd0bc9481da1e674da85/homeassistant/components/config/entity_registry.py#L93
    case class `config/entity_registry/get`(entity_id: EntityId)
        extends CommandPhase
        with CommandResponse.AsResult[Json] derives ConfiguredEncoder

    // TODO config/entity_registry/get_entries entity_ids https://github.com/home-assistant/core/blob/164d38ac0df5b590ef18dd0bc9481da1e674da85/homeassistant/components/config/entity_registry.py#L122

    // https://github.com/home-assistant/core/blob/dev/homeassistant/components/config/area_registry.py
    case class `config/area_registry/list`()
        extends CommandPhase
        with CommandResponse.AsResult[List[Area]] derives ConfiguredEncoder

    // https://github.com/home-assistant/core/blob/164d38ac0df5b590ef18dd0bc9481da1e674da85/homeassistant/components/config/floor_registry.py#L26C32-L26C58
    case class `config/floor_registry/list`()
        extends CommandPhase
        with CommandResponse.AsResult[List[Floor]] derives ConfiguredEncoder

    /** Every account that can log in — HA's own user list.
      *
      * Admin-only, which is fine for the one caller: the dump generator runs on
      * the machine token. Verified against HA 2026.8.2, where it answered six
      * accounts, three of them `system_generated`.
      */
    case class `config/auth/list`()
        extends CommandPhase
        with CommandResponse.AsResult[List[HaAccount]] derives ConfiguredEncoder

    /** Who the access token that authenticated THIS connection belongs to.
      *
      * Unlike every other command here, the answer depends on which token
      * opened the socket rather than on the home's state — which is exactly
      * what makes it useful: a short-lived connection opened with a *user's*
      * OAuth token identifies that user (issue #89). Asked on the shared feed
      * it reports the machine identity, which is only ever a diagnostic.
      *
      * Verified against HA 2026.8.2: `{"id":1,"type":"auth/current_user"}` →
      * `{"id":…,"name":…,"is_owner":true,"is_admin":true,"credentials":[…]}`
      */
    case class `auth/current_user`()
        extends CommandPhase
        with CommandResponse.AsResult[HaUser] derives ConfiguredEncoder

    //
    // Devices
    //

    // https://github.com/home-assistant/core/blob/3b69a2bbd190844258b8761342f075f5e15284ab/homeassistant/components/device_automation/__init__.py#L380
    // https://www.home-assistant.io/docs/automation/action/
    // https://developers.home-assistant.io/docs/device_automation_action/
    // Is it the same as services? https://data.home-assistant.io/docs/services
    case class `device_automation/action/list`(device_id: DeviceId)
        extends CommandPhase
        with CommandResponse.AsResult[List[Json]] derives ConfiguredEncoder

    // TODO device_automation/action/capabilities https://github.com/home-assistant/core/blob/634e1dd9eb7855a4adcdaaff99769c83473a5e8b/homeassistant/components/device_automation/__init__.py#L443
    case class `device_automation/action/capabilities`(
        action: Json
    ) // is actionid a thing?
        extends CommandPhase
        with CommandResponse.AsResult[Json] derives ConfiguredEncoder

    // TODO device_automation/condition/list

    // TODO device_automation/condition/capabilities

    // TODO device_automation/trigger/capabilities

    // https://github.com/home-assistant/core/blob/164d38ac0df5b590ef18dd0bc9481da1e674da85/homeassistant/components/device_automation/__init__.py#L422
    case class `device_automation/trigger/list`(device_id: DeviceId)
        extends CommandPhase
        with CommandResponse.AsResult[List[DeviceTrigger]]
        derives ConfiguredEncoder

    //
    // Subscriptions
    //

    // https://developers.home-assistant.io/docs/api/websocket/#subscribe-to-events
    // https://data.home-assistant.io/docs/events
    // Raw: event payload shapes are event-type-specific, so the stream yields
    // the undecoded `event` object; `HomeAssistantApi.event` decodes the
    // `state_changed` shape on top of it.
    case class subscribe_events(event_type: Option[String])
        extends CommandPhase
        with CommandResponse.AsStream.AsEvent derives ConfiguredEncoder

    /** HA's compressed state feed — the subscribed entity set in full on
      * subscribe, then deltas. Replaces `get_states` + `subscribe_events
      * state_changed` for anything tracking live state: one subscription cannot
      * have a gap between the snapshot and the change feed. See
      * [[EntitiesEvent]].
      *
      * `entity_ids` narrows it, which HA supports but does not document —
      * `websocket_api/commands.py` gates both the opening snapshot and every
      * later event on `not entity_ids or state.entity_id in entity_ids`.
      *
      * '''`Some(Nil)` would mean EVERY entity, not none.''' HA reads the list
      * as `set(msg.get("entity_ids", [])) or None`, so an empty one falls back
      * to unfiltered. A caller holding an empty set must not subscribe at all
      * rather than send one.
      */
    case class subscribe_entities(entity_ids: Option[List[String]] = None)
        extends CommandPhase
        with CommandResponse.AsStream.AsEventOf[EntitiesEvent]

    object subscribe_entities {

      /** The derived encoder writes an absent `entity_ids` as `null`, which is
        * NOT the same as omitting it: HA validates the field with
        * `cv.entity_ids`, which rejects null, so the whole subscription would
        * fail rather than fall back to the whole house. Dropped explicitly.
        */
      given Encoder.AsObject[subscribe_entities] =
        ConfiguredEncoder
          .derived[subscribe_entities]
          .mapJsonObject(_.filter { case (_, v) => !v.isNull })
    }

    // todo https://developers.home-assistant.io/docs/api/websocket#unsubscribing-from-events
    case class unsubscribe_events(subscription: Int)
        extends CommandPhase
        with CommandResponse.AsResult[Unit] derives ConfiguredEncoder

    // https://developers.home-assistant.io/docs/api/websocket/#subscribe-to-trigger
    // https://www.home-assistant.io/docs/automation/trigger/
    // https://github.com/home-assistant/core/blob/a98bb96325cf50d4ca77b68573b53c253ff673e1/homeassistant/components/websocket_api/commands.py#L717-L728
    // TODO variables?
    // TODO
    case class subscribe_trigger(trigger: List[TriggerData])
        extends CommandPhase
        with CommandResponse.AsStream.AsTrigger derives ConfiguredEncoder
  }

  given Encoder["sunset" | "sunrise"] =
    Encoder.instance(Json.fromString)

  /*    trait Platform(s: String) {
      val platform: String = s
    }*/
  sealed trait TriggerData

  object TriggerData {
    // TODO https://www.home-assistant.io/docs/automation/trigger/#event-trigger
    // TODO https://www.home-assistant.io/docs/automation/trigger/#numeric-state-trigger

    // You cannot use from and not_from at the same time. The same applies to to and not_to.
    // https://www.home-assistant.io/docs/automation/trigger/#state-trigger
    case class State(
        entity_id: String = "",
        attribute: Option[String],
        from: List[String],
        notFrom: List[String],
        to: String,
        notTo: String
    ) extends TriggerData

    // https://www.home-assistant.io/docs/automation/trigger/#sun-trigger
    case class sun(event: "sunset" | "sunrise", offset: Option[String] = None)
        extends TriggerData

    // https://www.home-assistant.io/docs/automation/trigger/#device-triggers
    case class device(deviceTrigger: DeviceTrigger) extends TriggerData
    object device {
      given Encoder[device] = Encoder.instance { d =>
        d.deviceTrigger.asJson
      }
    }

    // TODO https://www.home-assistant.io/docs/automation/trigger/#time-trigger
    // TODO https://www.home-assistant.io/docs/automation/trigger/#sensors-of-datetime-device-class
    // TODO https://www.home-assistant.io/docs/automation/trigger/#time-pattern-trigger
    // TODO https://www.home-assistant.io/docs/automation/trigger/#zone-trigger
    // TODO https://www.home-assistant.io/docs/automation/trigger/#calendar-trigger
    // TODO https://www.home-assistant.io/docs/automation/trigger/#sentence-trigger
    given Encoder[TriggerData] = ConfiguredEncoder
      .derive[TriggerData](
        discriminator = Some("platform")
      )
      .mapJson(_.dropNullValues) // null is considered configured in HA
  }

  given Conversion[IsDeviceTrigger, TriggerData] = in =>
    TriggerData.device(summon[Conversion[IsDeviceTrigger, DeviceTrigger]](in))
}
