package fh.api

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.HAWSApiLowLevel
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import cats.effect.std.{Env => CEnv}
import org.http4s.Uri
import org.http4s.jdkhttpclient.JdkWSClient

import java.net.http.HttpClient

object FHApi {

  /** Under sbt the repo-root `.env` reaches the process environment through
    * sbt-dotenv; nothing here reads the file.
    */
  def fromEnv: Resource[IO, HomeAssistantApi[IO]] =
    fromEnvWithClose.map(_._1)

  /** Also yields `awaitClosed`, for a caller that reconnects. */
  def fromEnvWithClose: Resource[IO, (HomeAssistantApi[IO], IO[Unit])] =
    resolveEnv.toResource.flatMap(connectWithClose)

  /** `serverWs` overrides the WS endpoint: the supervisor proxy serves it at
    * `ws://supervisor/core/websocket`, not the `/api/websocket` derived from
    * `SERVER`.
    */
  final case class Env(server: Uri, secretToken: String, serverWs: Option[Uri])

  /** Resolved once at boot, apart from [[connectWithClose]], so a missing
    * credential crashes instead of being retried as an unreachable HA.
    */
  def resolveEnv: IO[Env] =
    for {
      server <- CEnv[IO]
        .get("SERVER")
        .flatMap(_.liftTo[IO](new Exception("Missing SERVER")))
        .flatMap(s => IO(Uri.unsafeFromString(s)))
      secretToken <- CEnv[IO]
        .get("SECRET")
        .flatMap(_.liftTo[IO](new Exception("Missing SECRET")))
      serverWs <- CEnv[IO]
        .get("SERVER_WS")
        .flatMap(_.traverse(s => IO(Uri.unsafeFromString(s))))
    } yield Env(server, secretToken, serverWs)

  def connectWithClose(
      env: Env
  ): Resource[IO, (HomeAssistantApi[IO], IO[Unit])] =
    fromWithClose(env.server, env.secretToken, env.serverWs)

  /** `fh.view.runtime.HaFeed` fronts this with a durable facade, so the
    * high-level API survives reconnects.
    */
  def lowLevelConnectWithClose(
      env: Env
  ): Resource[IO, (HAWSApiLowLevel[IO], IO[Unit])] =
    lowLevelWithClose(env.server, env.secretToken, env.serverWs)

  def from(
      api: Uri,
      secretToken: String,
      wsUriOverride: Option[Uri] = None
  ): Resource[IO, HomeAssistantApi[IO]] =
    fromWithClose(api, secretToken, wsUriOverride).map(_._1)

  def fromWithClose(
      api: Uri,
      secretToken: String,
      wsUriOverride: Option[Uri] = None
  ): Resource[IO, (HomeAssistantApi[IO], IO[Unit])] =
    lowLevelWithClose(api, secretToken, wsUriOverride).map { case (ws, close) =>
      (HomeAssistantApi.fromWs(ws), close)
    }

  def lowLevelWithClose(
      api: Uri,
      secretToken: String,
      wsUriOverride: Option[Uri] = None
  ): Resource[IO, (HAWSApiLowLevel[IO], IO[Unit])] =
    for {
      wsUri <- wsUriOverride
        .fold(utils.haUriHttpToWS[IO](api))(IO.pure)
        .toResource

      // TODO should be params that are independent of underlying implementation
      httpClient <- IO(HttpClient.newHttpClient()).toResource
      wsClient = JdkWSClient[IO](httpClient)

      // Frame tracing: set `api.homeassistant.ws.HAWSApiLowLevel` to DEBUG in
      // logback.xml.
      wsApi <- HAWSApiLowLevel(wsClient, wsUri, secretToken)
    } yield (wsApi, wsApi.awaitClosed)
}
