package api.homeassistant.ws

import cats.effect.IO
import io.circe.Json
import client.CommandPhase.*

trait HAWSApi[F[_]] {
  def configDeviceRegistryList: IO[Json]
  def deviceAutomationTriggerList(deviceId: String): IO[Json]
}

object HAWSApi {
  def fromLowLevel(in: HAWSApiLowLevel[IO]): HAWSApi[IO] = new HAWSApi[IO]:
    def configDeviceRegistryList: IO[Json] =
      in.sendCommand(`config/device_registry/list`())[Json]

    def deviceAutomationTriggerList(deviceId: String): IO[Json] =
      in.sendCommand(`device_automation/trigger/list`(deviceId))[Json]
}
