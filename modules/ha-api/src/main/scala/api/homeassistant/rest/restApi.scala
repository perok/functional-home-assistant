package api.homeassistant.rest

import api.Middleware
import cats.effect.{IO, Resource}
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

}
