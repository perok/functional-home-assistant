package api.homeassistant.ws

import api.homeassistant.ws.client.CommandPhase.*
import api.homeassistant.ws.client.TriggerData
import api.homeassistant.ws.domain.{Device, Entity, Trigger}
import api.homeassistant.ws.server.Event
import cats.effect.std.QueueSource
import cats.effect.{IO, Resource}
import io.circe.Json

trait HAWSApi[F[_]] {

  /** https://developers.home-assistant.io/docs/device_registry_index/
    * @return
    */
  def configDeviceRegistryList: IO[List[Device]]

  def configEntityRegistryList
      : IO[List[Entity]] // Exposes entity_id and device_id
  def configEntityRegistryGet(entityId: String): IO[Json]

  def deviceAutomationTriggerList(deviceId: String): IO[List[Trigger]]
  def trigger(data: TriggerData): Resource[IO, QueueSource[IO, Json]]
  def event(event: Option[String]): Resource[IO, QueueSource[IO, Event]]
}

object HAWSApi {
  def fromLowLevel(in: HAWSApiLowLevel[IO]): HAWSApi[IO] = new HAWSApi[IO] {
    def configDeviceRegistryList: IO[List[Device]] =
      in.sendCommand(`config/device_registry/list`())

    def configEntityRegistryList: IO[List[Entity]] =
      in.sendCommand(`config/entity_registry/list`())

    def configEntityRegistryGet(entityId: String): IO[Json] =
      in.sendCommand(`config/entity_registry/get`(entityId))

    def deviceAutomationTriggerList(deviceId: String): IO[List[Trigger]] =
      in.sendCommand(`device_automation/trigger/list`(deviceId))

    def event(event: Option[String]): Resource[IO, QueueSource[IO, Event]] =
      in.subscribeStream(subscribe_events(Some("state_changed")))

    def trigger(data: TriggerData): Resource[IO, QueueSource[IO, Json]] =
      in.subscribeStream(subscribe_trigger(List(data)))
  }
}
