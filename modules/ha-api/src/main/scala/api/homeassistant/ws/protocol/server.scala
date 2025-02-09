package api.homeassistant.ws.protocol

import api.homeassistant.ws.utils.defaults.given
import io.circe.{Decoder, Json}
import io.circe.derivation.{ConfiguredCodec, ConfiguredDecoder}

import scala.util.control.NoStackTrace

object server {

  // TODO?
  enum SubscribeTypes {
    case state_changed
  }

  case class ResultContext(
      id: String,
      parent_id: Option[Json],
      user_id: Option[String]
  ) derives ConfiguredCodec

  case class ResultData(context: ResultContext) derives ConfiguredCodec
  case class Event(
      data: Event.EventData,
      event_type: String,
      time_fired: String,
      origin: String,
      context: ResultContext
  ) derives ConfiguredCodec

  object Event {

    case class EventData(
        entity_id: String,
        new_state: EventDataState,
        old_state: EventDataState
    ) derives ConfiguredCodec

    case class EventDataStateAttributes(
        state_class: Option[String],
        unit_of_measurement: Option[String],
        device_class: Option[String],
        friendly_name: Option[String]
    ) derives ConfiguredCodec

    case class EventDataState(
        entity_id: String,
        state: Json,
        attributes: EventDataStateAttributes,
        last_changed: String,
        last_reported: String,
        last_updated: String,
        context: ResultContext
    ) derives ConfiguredCodec

  }

  case class WSHAError(code: String, message: String) extends NoStackTrace
      derives ConfiguredCodec {
    override def getMessage = s"code=$code\n$message"
  }

  case class WSCommandPhaseServerPayload(id: Int, payload: Json) {
    lazy val parsedPayload: Decoder.Result[WSCommandPhaseServer] =
      payload.as[WSCommandPhaseServer]
  }
  object WSCommandPhaseServerPayload {
    given Decoder[WSCommandPhaseServerPayload] = Decoder.instance { cursor =>
      cursor
        .get[Int]("id")
        .map(id => WSCommandPhaseServerPayload(id, cursor.top.get))
    }
  }
  enum WSCommandPhaseServer derives ConfiguredDecoder {
    case result(
        id: Int,
        success: Boolean,
        result: Option[Json],
        error: Option[Json]
    )
    case event(id: Int, event: Event)
    case trigger(id: Int, trigger: Json)
  }
}
