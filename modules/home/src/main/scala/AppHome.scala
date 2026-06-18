//import scala.meta.* // scalameta for code generation. does not support dotty

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.protocol.client.TriggerData
import api.homeassistant.ws.domain.*
import cats.effect.*
import cats.syntax.all.*
import fh.api.FHApi
import fh.domain.utils.given

object AppHome extends IOApp.Simple {
  val run = (for {

    api <- FHApi.fromEnv
    // _ <- service.postServiceApi("", "", "hello").toResource
    // https://community.home-assistant.io/t/devices-via-rest-api/455634/4
    _ <- program(api).toResource
  } yield ()).use_

  // TODO? https://github.com/keirlawson/fs2-progress/blob/main/src/main/scala/fs2/progress/ProgressBar.scala
  def program(api: HomeAssistantApi[IO]) =

    for {
      _ <- IO.unit
      // _ <- api.getConfigWS.debug("get config")
      //things <- api
      //  .deviceAutomationActionList(
      //    ha.generated.manifest.`Zigbee Home Automation`.config_entries.zha.`Home Assistant SkyConnect`.devices.hue_led_list_kjøkken.id
      //  )
      //  .debug("deviceautomationList")
      //_ <- api
      //  .deviceAutomationActionCapabilities(
      //    things
      //      .find(
      //        _.hcursor.downField("type").as[String].toOption.get == "set_value"
      //      )
      //      .get
      //  )
      //  .debug("woot")
      // result <- api
      //  .deviceAutomationActionList(
      //    // devices.hue_dimmer_switch_gang_overetasje.id
      //    devices.hue_led_list_kjøkken.id
      //  )
      //  .debug("device action list")

      // _ <- api
      //  .deviceAutomationActionCapabilities(
      //    result
      //      .find(
      //        _.hcursor.downField("domain").as[String].toOption.get == "light"
      //      )
      //      .get
      //  )
      //  .debug("capabilities")
      // _ <- api.getServices.debug("services")

      // _ <- hello.testTrigger(api)

      // _ <- wsApi
      //   .event(Some("state_changed"))
      //   .use(_.take.debug("First"))

      // _ <- wsApi.trigger(sun("sunset")).use(_.take.debug("trigger"))
    } yield ()
}
