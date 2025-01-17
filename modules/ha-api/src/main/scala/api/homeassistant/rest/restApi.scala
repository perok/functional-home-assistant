package api.homeassistant.rest

import api.{DocumentJson, Middleware}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Decoder
import org.http4s.Uri
import org.http4s.client.Client
import perok.ha.HomeAssistantApiService

object restApi {
  def apply(
      client: Client[IO],
      uri: Uri,
      secretToken: String
  ): Resource[IO, HomeAssistantApiService[IO]] =
    import smithy4s.http4s.SimpleRestJsonBuilder
    for {
      helloClient <- SimpleRestJsonBuilder(HomeAssistantApiService)
        .client(client)
        .uri(uri)
        .middleware(Middleware(secretToken))
        .resource
    } yield helloClient

  extension (service: HomeAssistantApiService[IO])
    def templateFunc[Body: Decoder](template: String): IO[Body] =
      service
        .template(template)
        .flatMap(_.output.decode(DocumentJson.decoder).liftTo[IO])
        .flatMap(_.as[Body].liftTo[IO])

    def areas: IO[List[String]] =
      service
        .templateFunc[List[String]]("{{ areas() | to_json() }}")

    def floors: IO[List[String]] =
      service
        .templateFunc[List[String]]("{{ floors() | to_json() }}")

    def floorArea(floor: String): IO[String] =
      service
        .templateFunc[String](s"{{ floor_areas('$floor') | to_json }}")
}
