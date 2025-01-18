package api.homeassistant.ws

import api.homeassistant.ws.client.{CommandPhase, CommandResponse}
import cats.effect.IO
import io.circe.{Encoder, Decoder, Json}
import CommandPhase.*

trait HAWSApi[F[_]] {
  def configDeviceRegistryList: IO[Json]
  def deviceAutomationTriggerList(deviceId: String): IO[Json]
}

object HAWSApi {
  def fromLowLevel(in: HAWSApiLowLevel[IO]): HAWSApi[IO] = new HAWSApi[IO] {
    def configDeviceRegistryList: IO[Json] =
      in.sendCommandWithResponse(`config/device_registry/list`()).map(_._2)

    def deviceAutomationTriggerList(deviceId: String): IO[Json] =
      in.sendCommandWithResponse(`device_automation/trigger/list`(deviceId))
        .map(_._2)
  }
}
