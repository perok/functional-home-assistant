package fh.api

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.HAWSApiLowLevel
import api.homeassistant.rest.restApi
import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Env
import cats.syntax.all.*
import org.http4s.Uri
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}

import java.net.http.HttpClient

object FHApi {
  def fromEnv: Resource[IO, HomeAssistantApi[IO]] = for {
    server <- Env[IO]
      .get("SERVER")
      .flatMap(_.liftTo[IO](new Exception("Missing SERVER")))
      .flatMap(s => IO(Uri.unsafeFromString(s)))
      .toResource
    secretToken <- Env[IO]
      .get("SECRET")
      .flatMap(_.liftTo[IO](new Exception("Missing SECRET")))
      .toResource
    // Optional WS endpoint override. The HA supervisor proxy exposes the
    // websocket at ws://supervisor/core/websocket, not the /api/websocket
    // path derived from SERVER.
    serverWs <- Env[IO]
      .get("SERVER_WS")
      .flatMap(_.traverse(s => IO(Uri.unsafeFromString(s))))
      .toResource
    result <- from(server, secretToken, serverWs)
  } yield result

  // TODO websocket api https://developers.home-assistant.io/docs/api/websocket
  def from(
      api: Uri,
      secretToken: String,
      wsUriOverride: Option[Uri] = None
  ): Resource[IO, HomeAssistantApi[IO]] =
    for {
      wsUri <- wsUriOverride
        .fold(utils.haUriHttpToWS[IO](api))(IO.pure)
        .toResource

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
      )
    } yield HomeAssistantApi.fromLowLevel(wsApi, api)
}
