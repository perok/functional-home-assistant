import cats.effect.{IO, Resource}
import cats.syntax.all.*
import perok.ha.{
  HomeAssistantApiService,
  PostServiceApiOutput,
  ServicesApiOutput
}
import ha.generated.*
import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.client.TriggerData
import cats.effect.std.QueueSource
import io.circe.Json
import scala.concurrent.duration.*

object hello {
  // TODO https://developers.home-assistant.io/docs/core/entity/light/
  val lys = entities.light.`lysgruppe Bibliotek`

  entities.automation.off
  services.light.`Turn on`()
  // lys.turn_on()

  // services.input_button.Press
  def testTrigger(api: HomeAssistantApi[IO]): IO[Unit] = {
    val switchOveretasje = devices.hue_dimmer_switch_gang_overetasje
    api
      .trigger(
        switchOveretasje.triggers.remote_button_short_press_turn_on
      )
      .use(_.take)
      .void
      .timeout(2.seconds)
  }

  def postServiceApiTest(
      api: HomeAssistantApiService[IO]
  ): IO[PostServiceApiOutput] = {
    val lys2 = entities.light.plantelys
    val service = services.light.Toggle()
    // val service= services.light.Toggle()

    // api.postServiceApi(service.domain, service.serviceId, entity_id = lys2.id.some)
    api.postServiceApi(
      service.domain,
      service.serviceId,
      area_id = "a2a7fc17306e45e7a3fda077203b0598".some
    ) // living room

  }

  /*

  // services: Wanted code high level

  entities.light.plantelyd.Toggle() >>
    entities.light.plantelyd.`Turn on`(level = 80)

  floors.overetasje.lights.Toggle()
  floors.overetasje.bibliotek.lights.Toggle()


  // automations
  TODO
  switch.overetasje_hue_button.turn_on.then()
   */
}
