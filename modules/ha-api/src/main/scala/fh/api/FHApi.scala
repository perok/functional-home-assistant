package fh.api

import api.homeassistant.ws.{HAWSApi, HAWSApiLowLevel}
import api.homeassistant.rest.restApi
import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Env
import cats.syntax.all.*
import org.http4s.Uri
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}
import perok.ha.HomeAssistantApiService

import java.net.http.HttpClient

object FHApi {
  def fromEnv: Resource[
    IO,
    (HomeAssistantApiService[IO], HAWSApi[IO])
  ] = for {
    server <- Env[IO]
      .get("SERVER")
      .flatMap(_.liftTo[IO](new Exception("Missing SERVER")))
      .flatMap(s => IO(Uri.unsafeFromString(s)))
      .toResource
    secretToken <- Env[IO]
      .get("SECRET")
      .flatMap(_.liftTo[IO](new Exception("Missing SERVER")))
      .toResource
    result <- from(server, secretToken)
  } yield result

  // TODO websocket api https://developers.home-assistant.io/docs/api/websocket
  def from(api: Uri, secretToken: String): Resource[
    IO,
    (HomeAssistantApiService[IO], HAWSApi[IO])
  ] = for {
    wsUri <- utils.haUriHttpToWS[IO](api).toResource

    // TODO should be params that are independent of underlying implementation
    httpClient <- IO(HttpClient.newHttpClient()).toResource
    client = JdkHttpClient[IO](httpClient)
    // import org.http4s.ember.client.EmberClientBuilder
    // client <- EmberClientBuilder.default[IO].build
    wsClient = JdkWSClient[IO](httpClient)

    api <- restApi(
      client,
      api,
      secretToken
    )
    wsApi <- HAWSApiLowLevel(
      wsClient,
      wsUri,
      secretToken
    ).map(HAWSApi.fromLowLevel)
  } yield (api, wsApi)
}
