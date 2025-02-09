//import scala.meta.* // scalameta for code generation. does not support dotty

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.protocol.client.TriggerData
import api.homeassistant.ws.domain.*
import ha.runtime.definitions.{EntityId, DeviceId}
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

      _ <- hello.testTrigger(api)

      // _ <- wsApi
      //   .event(Some("state_changed"))
      //   .use(_.take.debug("First"))

      // _ <- wsApi.trigger(sun("sunset")).use(_.take.debug("trigger"))
    } yield ()
}
