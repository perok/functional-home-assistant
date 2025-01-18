package api.homeassistant.ws

import perok.ha.EntityId
import io.circe.*
import io.circe.given
import io.circe.syntax.*
import io.circe.derivation.ConfiguredEncoder
import defaults.given

object client {
  // https://github.com/zachowj/node-red-contrib-home-assistant-websocket/blob/main/src/homeAssistant/Websocket.ts#L659
  import WSCommandPhaseClient.*

  sealed trait CommandResponse[R] {
    val decoder: Decoder[R]
  }
  object CommandResponse {
    trait Void(val decoder: Decoder[Unit] = Decoder.decodeUnit)
        extends CommandResponse[Unit]
    trait As[R](using val decoder: Decoder[R]) extends CommandResponse[R]
  }

  sealed trait CommandPhase derives ConfiguredEncoder
  object CommandPhase {

    // https://github.com/home-assistant-ecosystem/home-assistant-cli
    // https://github.com/home-assistant/core/blob/dev/homeassistant/components/config/device_registry.py

    case class `config/device_registry/list`()
        extends CommandPhase
        with CommandResponse.As[Json] derives ConfiguredEncoder

    case class `device_automation/trigger/list`(device_id: String)
        extends CommandPhase
        with CommandResponse.As[Json] derives ConfiguredEncoder

    // https://developers.home-assistant.io/docs/api/websocket/#subscribe-to-events
    case class subscribe_events(event_type: Option[String])
        extends CommandPhase
        with CommandResponse.Void derives ConfiguredEncoder

    // todo https://developers.home-assistant.io/docs/api/websocket#unsubscribing-from-events
    case class unsubscribe_events(subscription: Int)
        extends CommandPhase
        with CommandResponse.Void derives ConfiguredEncoder
  }

  enum WSCommandPhaseClient {
    // https://developers.home-assistant.io/docs/api/websocket/#subscribe-to-trigger
    // https://www.home-assistant.io/docs/automation/trigger/
    // TODO
    case subscribe_trigger(id: Int, triggerData: List[TriggerData])

  }
  object WSCommandPhaseClient {
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

    given Encoder[WSCommandPhaseClient] = Encoder.instance {

      case subscribe_trigger(id, triggerData) =>
        val triggers = triggerData.map { trigger =>
          val platform = trigger match {
            case _: TriggerData.State => "state"
            case _: TriggerData.Sun   => "sun"
          }

          // TODO platform as discriminator
          trigger.asJson.deepMerge(Json.obj("platform" -> platform.asJson))
        }

        Json
          .obj(
            ("id", Json.fromInt(id)),
            ("type", Json.fromString("subscribe_trigger")),
            ("trigger", triggers.asJson)
          )
          .deepMerge(triggerData.asJson)
    }

    given Encoder["sunset" | "sunrise"] =
      Encoder.instance(Json.fromString)

    /*    trait Platform(s: String) {
      val platform: String = s
    }*/
    enum TriggerData derives ConfiguredEncoder {
      // TODO https://www.home-assistant.io/docs/automation/trigger/#event-trigger
      // TODO https://www.home-assistant.io/docs/automation/trigger/#numeric-state-trigger
      // You cannot use from and not_from at the same time. The same applies to to and not_to.
      // https://www.home-assistant.io/docs/automation/trigger/#state-trigger
      case State(
          entity_id: String = "",
          attribute: Option[String],
          from: List[String],
          notFrom: List[String],
          to: String,
          notTo: String
      ) extends TriggerData // with Platform("state")
      // https://www.home-assistant.io/docs/automation/trigger/#sun-trigger
      case Sun(event: "sunset" | "sunrise", offset: Option[String])
      // TODO https://www.home-assistant.io/docs/automation/trigger/#device-triggers
      // TODO https://www.home-assistant.io/docs/automation/trigger/#time-trigger
      // TODO https://www.home-assistant.io/docs/automation/trigger/#sensors-of-datetime-device-class
      // TODO https://www.home-assistant.io/docs/automation/trigger/#time-pattern-trigger
      // TODO https://www.home-assistant.io/docs/automation/trigger/#zone-trigger
      // TODO https://www.home-assistant.io/docs/automation/trigger/#calendar-trigger
      // TODO https://www.home-assistant.io/docs/automation/trigger/#sentence-trigger
    }

  }
}
