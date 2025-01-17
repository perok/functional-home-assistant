import cats.effect.{IO, Resource}
import cats.syntax.all.*
import perok.ha.{
  HomeAssistantApiService,
  PostServiceApiOutput,
  ServicesApiOutput
}
import ha.generated.*

object hello {
  // TODO https://developers.home-assistant.io/docs/core/entity/light/
  val lys = entities.light.`lysgruppe Bibliotek`

  entities.automation.off
  services.light.`Turn on`()
  // lys.turn_on()

  // services.input_button.Press

  def testit(api: HomeAssistantApiService[IO]): IO[PostServiceApiOutput] = {
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
