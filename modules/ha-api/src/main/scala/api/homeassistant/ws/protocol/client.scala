package api.homeassistant.ws.protocol

import api.homeassistant.ws.domain.*
import server.{Event, WSCommandPhaseServer}
import api.homeassistant.ws.utils.defaults.given
import ha.runtime.definitions.{DeviceId, EntityId, IsDeviceTrigger}
import io.circe.*
import io.circe.derivation.{Configuration, ConfiguredEncoder}
import io.circe.syntax.*

object client {
  // https://github.com/zachowj/node-red-contrib-home-assistant-websocket/blob/main/src/homeAssistant/Websocket.ts#L659

  sealed trait CommandResponse[R] {
    val resultDecoder: Decoder[R]
  }
  object CommandResponse {

    // Everything that is a subscription in HA (meaning that unsubscribe_events works as a way to cancel the subscription)
    trait AsStream[R] extends CommandResponse[Unit] {
      val resultDecoder: Decoder[Unit] = Decoder.decodeUnit
      val f: PartialFunction[WSCommandPhaseServer, R]
    }
    object AsStream {
      trait AsEvent extends AsStream[Event] {
        // TODO why not working? extends AsStream[Event]({ case event(_, event) => event })
        val f: PartialFunction[WSCommandPhaseServer, Event] = {
          case WSCommandPhaseServer.event(_, event) =>
            event
        }
      }

      trait AsTrigger extends AsStream[Json] {
        val f: PartialFunction[WSCommandPhaseServer, Json] = {
          case WSCommandPhaseServer.trigger(_, event) =>
            event
        }
      }
    }

    trait AsResult[R](using val resultDecoder: Decoder[R])
        extends CommandResponse[R]
  }

  sealed trait CommandPhase derives ConfiguredEncoder

  // https://github.com/home-assistant-ecosystem/home-assistant-cli
  // All websocket calls https://github.com/search?q=repo%3Ahome-assistant%2Fcore+%40websocket_api.websocket_command%28&type=code&p=1
  object CommandPhase {

    /** Target of a `call_service` command. Entity-scoped; extend with
      * area/device ids if needed.
      */
    case class CallServiceTarget(entity_id: String) derives ConfiguredEncoder

    //
    // Stuff
    //

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

    // TODO get_states https://developers.home-assistant.io/docs/api/websocket#fetching-states

    // TODO get_config https://github.com/home-assistant/core/blob/a98bb96325cf50d4ca77b68573b53c253ff673e1/homeassistant/components/websocket_api/commands.py#L515
    // alt av devices og services og whatnot ligger under det
    case class `get_config`()
        extends CommandPhase
        with CommandResponse.AsResult[Json] derives ConfiguredEncoder

    // TODO supported_features https://github.com/home-assistant/core/blob/f5fd49d8cb710c95cde30fc5071c20af351760b4/homeassistant/components/websocket_api/commands.py#L906

    // TODO ping https://github.com/home-assistant/core/blob/a98bb96325cf50d4ca77b68573b53c253ff673e1/homeassistant/components/websocket_api/commands.py#L574 for heartbeats

    // get_services https://developers.home-assistant.io/docs/api/websocket#fetching-service-actions
    case class `get_services`()
        extends CommandPhase
        with CommandResponse.AsResult[Json] derives ConfiguredEncoder

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

    // TODO config/floor_registry/list https://github.com/home-assistant/core/blob/164d38ac0df5b590ef18dd0bc9481da1e674da85/homeassistant/components/config/floor_registry.py#L26C32-L26C58

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
    case class subscribe_events(event_type: Option[String])
        extends CommandPhase
        with CommandResponse.AsStream.AsEvent derives ConfiguredEncoder

    // TODO subscribe_entities https://community.home-assistant.io/t/terrible-performance-on-seemingly-most-android-tablets/760318/78?u=perok

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
