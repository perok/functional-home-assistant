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
  object CommandPhase {
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
        extends CommandPhase
        with CommandResponse.AsResult[Json] derives ConfiguredEncoder


    // https://github.com/home-assistant/core/blob/164d38ac0df5b590ef18dd0bc9481da1e674da85/homeassistant/components/device_automation/__init__.py#L422
    case class `device_automation/trigger/list`(device_id: DeviceId)
        extends CommandPhase
        with CommandResponse.AsResult[List[DeviceTrigger]]
        derives ConfiguredEncoder

    // https://developers.home-assistant.io/docs/api/websocket/#subscribe-to-events
    case class subscribe_events(event_type: Option[String])
        extends CommandPhase
        with CommandResponse.AsStream.AsEvent derives ConfiguredEncoder

    // todo https://developers.home-assistant.io/docs/api/websocket#unsubscribing-from-events
    case class unsubscribe_events(subscription: Int)
        extends CommandPhase
        with CommandResponse.AsResult[Unit] derives ConfiguredEncoder

    // https://developers.home-assistant.io/docs/api/websocket/#subscribe-to-trigger
    // https://www.home-assistant.io/docs/automation/trigger/
    // https://github.com/home-assistant/core/blob/a98bb96325cf50d4ca77b68573b53c253ff673e1/homeassistant/components/websocket_api/commands.py#L717-L728
    // TODO
    case class subscribe_trigger(trigger: List[TriggerData])
        extends CommandPhase
        with CommandResponse.AsStream.AsTrigger derives ConfiguredEncoder
  }

  /*
    device trigger
    how to fetch the information necessary to create this code?

// Automation trigger defintion

// https://www.home-assistant.io/docs/automation/trigger/#device-triggers
// Hmm, how to ge the domain of a device?

// https://github.com/home-assistant/core/blob/abc256fb3e1163859e77be5d478912b0205ea21b/homeassistant/components/hue/v1/device_trigger.py#L37
// Type remomte_button_short_Press is at least a concept of device
// NOOO hue..

// TODO how to call this! https://github.com/home-assistant/core/blob/5ea54130644fb904f40655066ed754cceaeaa499/homeassistant/components/zha/device_trigger.py#L92-L113
// configs?
// THIS? https://www.home-assistant.io/docs/automation/templating/#device
// No this? https://github.com/home-assistant/core/blob/c601170b1d901c4a577f49aa61c6d2e91a1c26ab/homeassistant/components/config/config_entries.py#L81-L84
// "/api/config/config_entries/entry/{entry_id}"
// https://github.com/home-assistant/core/blob/c601170b1d901c4a577f49aa61c6d2e91a1c26ab/homeassistant/components/device_automation/__init__.py#L420

alias: Light on
description: ""
mode: single
triggers:
  - device_id: 518ff41eac9e275872e15aa3c692b7fb
    domain: zha
    type: remote_button_short_press
    subtype: turn_on
    trigger: device
conditions: []
actions:
  - action: light.turn_on
    metadata: {}
    data: {}
    target:
      entity_id: light.nabu_casa_skyconnect_v1_0_lys_bibliotek

   */
  /*
An event

event_type: zha_event
data:
  device_ieee: 00:17:88:01:08:0c:05:da
  unique_id: 00:17:88:01:08:0c:05:da:2:0xfc00
  device_id: 518ff41eac9e275872e15aa3c692b7fb
  endpoint_id: 2
  cluster_id: 64512
  command: on_short_release
  args:
    button: "on"
    press_type: short_release
    command_id: 0
    duration: 1
    args:
      - 1
      - 3145728
      - 2
      - 33
      - 1
  params: {}
origin: LOCAL
time_fired: "2024-12-11T19:45:54.132915+00:00"
context:
  id: 01JEVM3AEMWTS5T4314ZEM45FY
  parent_id: null
  user_id: null

   */

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
