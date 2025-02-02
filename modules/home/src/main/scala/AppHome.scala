//import scala.meta.* // scalameta for code generation. does not support dotty

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.client.TriggerData
import api.homeassistant.ws.domain.{DeviceId, DeviceTrigger}
import cats.data.NonEmptyList
import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fh.api.FHApi
import io.circe.Json
import fh.domain.utils.given

object AppHome extends IOApp.Simple {
  val run = (for {

    api <- FHApi.fromEnv
    // _ <- service.postServiceApi("", "", "hello").toResource
    // https://community.home-assistant.io/t/devices-via-rest-api/455634/4
    _ <- program(api).toResource
  } yield ()).use_

  def program(api: HomeAssistantApi[IO]) =
    for {
      // _ <- hello.testit(api).debug("Operation").toResource
      _ <- api.floors.debug("floors")

      allEntities <- api.configEntityRegistryList.debug("entities")

      allDevices <- api.configDeviceRegistryList

      allTriggers <- allDevices.values.toSeq
        .parTraverseN(10) { device =>
          wsApi
            .deviceAutomationTriggerList(device.id)
            .map(triggers => (device.id, triggers))
        }
        .map(_.toMap.mapFilter(NonEmptyList.fromList))
        .debug("done")

      _ <- allEntities
        .find(e => e.device_id.nonEmpty && e.name.nonEmpty)
        .traverse { entity =>

          val d = allDevices.get(entity.device_id.get)

          pprint.pprintln(entity)
          pprint.pprintln(d)

          api
            .deviceAutomationTriggerList(d.get.id)
            .debug("automatins")
        }
        .whenA(false)

      // _ <- wsApi
      //   .event(Some("state_changed"))
      //   .use(_.take.debug("First"))

      // _ <- wsApi.trigger(sun("sunset")).use(_.take.debug("trigger"))
    } yield ()
}
