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

import java.time.Instant

object client {
  // https://github.com/zachowj/node-red-contrib-home-assistant-websocket/blob/main/src/homeAssistant/Websocket.ts#L659

  /** Decoding lives on the command, so the transport only routes by id. */
  sealed trait CommandResponse[R] {
    def decodeMessage(payload: server.WSCommandPhaseServerPayload): IO[R]
  }

  object CommandResponse {

    trait WithFinalization[R] {
      def finalizationMessage(
          id: Int
      ): CommandPhase & CommandResponse.WithSingleResponse[R]
    }

    trait WithSingleResponse[R] extends CommandResponse[R]

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

      /** Undecoded: payloads are event-type-specific (`*_registry_updated` is
        * not `state_changed`-shaped), so callers decode what they subscribed
        * to.
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

    /** Raises HA's [[WSHAError]] on a failure frame. */
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
              // A bare ack has no `result`; a `Unit` decoder expects `null`.
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

    case class CallServiceTarget(entity_id: String) derives ConfiguredEncoder

    // https://developers.home-assistant.io/docs/api/websocket/#pings-and-pongs
    case class ping() extends CommandPhase with CommandResponse.AsPong
        derives ConfiguredEncoder

    // https://developers.home-assistant.io/docs/api/websocket#calling-a-service-action
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

    /** Sent once after auth. With `coalesce_messages` every frame is a JSON
      * array, even of one payload (verified on 2026.7.2).
      * https://developers.home-assistant.io/docs/api/websocket/#feature-enablement-phase
      */
    case class supported_features(
        features: Map[String, Int] = Map("coalesce_messages" -> 1)
    ) extends CommandPhase
        with CommandResponse.AsResult[Unit] derives ConfiguredEncoder

    // https://developers.home-assistant.io/docs/api/websocket#fetching-service-actions
    case class `get_services`()
        extends CommandPhase
        with CommandResponse.AsResult[List[ServiceDomain]](using
          DocumentJson.circeDecoderFor(using ServicesData.schema).map(_.value)
        ) derives ConfiguredEncoder

    // https://developers.home-assistant.io/docs/api/websocket#fetching-states
    // Same representation as REST `/api/states`, hence the shared schema.
    case class `get_states`()
        extends CommandPhase
        with CommandResponse.AsResult[List[GetStatesData]](using
          DocumentJson.circeDecoderFor(using
            smithy4s.Schema.list(GetStatesData.schema)
          )
        ) derives ConfiguredEncoder

    // https://developers.home-assistant.io/docs/api/websocket#render-a-template
    // A subscription that re-pushes on every referenced change; a one-shot
    // caller takes the first event. `report_errors` makes a template error an
    // event instead of silence.
    case class render_template(template: String, report_errors: Boolean = true)
        extends CommandPhase
        with CommandResponse.AsStream.AsEvent derives ConfiguredEncoder

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

    /** Admin-only; the one caller, the dump, runs on the machine token.
      * Includes `system_generated` accounts (three of six on HA 2026.8.2).
      */
    case class `config/auth/list`()
        extends CommandPhase
        with CommandResponse.AsResult[List[HaAccount]] derives ConfiguredEncoder

    /** Whoever's token opened this socket, so a short-lived connection with a
      * user's OAuth token identifies that user (issue #89); on the shared feed
      * it is the machine.
      */
    case class `auth/current_user`()
        extends CommandPhase
        with CommandResponse.AsResult[HaUser] derives ConfiguredEncoder

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

    /** Without the two flags every row repeats the attribute map (37 KB vs 8 KB
      * for 223 points, measured); [[HistoryPoint]] decodes only the compact
      * shape.
      *
      * An unknown entity and an end before the start both answer `{}`, so an
      * empty map cannot detect a bad request. Retention (`purge_keep_days`)
      * truncates the window silently.
      */
    case class `history/history_during_period`(
        start_time: Instant,
        end_time: Instant,
        entity_ids: List[String],
        minimal_response: Boolean = true,
        no_attributes: Boolean = true
    ) extends CommandPhase
        with CommandResponse.AsResult[Map[String, List[HistoryPoint]]]
        derives ConfiguredEncoder

    /** An entity without a `state_class` answers `{}`, not an error. A bucket
      * that has not closed yet does not exist: one hour at `Hour` is empty.
      */
    case class `recorder/statistics_during_period`(
        start_time: Instant,
        end_time: Option[Instant],
        statistic_ids: List[String],
        period: StatisticsPeriod
    ) extends CommandPhase
        with CommandResponse.AsResult[Map[String, List[StatisticPoint]]]

    object `recorder/statistics_during_period` {

      /** Omit `end_time` rather than send null: HA answers `invalid_format:
        * expected str at 'end_time'. Got None`.
        */
      given Encoder.AsObject[`recorder/statistics_during_period`] =
        ConfiguredEncoder
          .derived[`recorder/statistics_during_period`]
          .mapJsonObject(_.filter { case (_, v) => !v.isNull })
    }

    // https://developers.home-assistant.io/docs/api/websocket/#subscribe-to-events
    case class subscribe_events(event_type: Option[String])
        extends CommandPhase
        with CommandResponse.AsStream.AsEvent derives ConfiguredEncoder

    /** A full snapshot then deltas, with no gap between them as `get_states` +
      * `state_changed` would have. `entity_ids` narrows both, undocumented
      * (`websocket_api/commands.py`).
      *
      * '''`Some(Nil)` means every entity, not none''' (`set(...) or None`):
      * with an empty set, do not subscribe.
      */
    case class subscribe_entities(entity_ids: Option[List[String]] = None)
        extends CommandPhase
        with CommandResponse.AsStream.AsEventOf[EntitiesEvent]

    object subscribe_entities {

      /** A `null` `entity_ids` fails `cv.entity_ids` and the whole
        * subscription, so it is omitted.
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
