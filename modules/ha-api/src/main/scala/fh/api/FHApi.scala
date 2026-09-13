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

  /** Resolve `SERVER`/`SECRET`/`SERVER_WS` from the process environment,
    * falling back to a `.env` file (the same file `build.sbt` reads). The env
    * var wins when set and non-empty; otherwise `.env` is consulted. This makes
    * the app self-sufficient: forked `runMain` under sbt does not reliably
    * inherit `run / envVars`, so relying on the process env alone is brittle —
    * the `.env` fallback is deterministic.
    */
  def fromEnv: Resource[IO, HomeAssistantApi[IO]] =
    fromEnvWithClose.map(_._1)

  /** Like [[fromEnv]], but also exposes the connection's `awaitClosed` (an
    * `IO[Unit]` that completes when the underlying WebSocket has died). A
    * caller that wants to reconnect races its work against it; callers that
    * just need the API (codegen, one-shot builds) use [[fromEnv]] and ignore
    * it.
    */
  def fromEnvWithClose: Resource[IO, (HomeAssistantApi[IO], IO[Unit])] =
    resolveEnv.toResource.flatMap(connectWithClose)

  /** The connection config resolved from `SERVER`/`SECRET`/`SERVER_WS` (env,
    * then `.env`). `serverWs` is the optional WS endpoint override — the HA
    * supervisor proxy exposes the websocket at
    * `ws://supervisor/core/websocket`, not the `/api/websocket` path derived
    * from `SERVER`.
    */
  final case class Env(server: Uri, secretToken: String, serverWs: Option[Uri])

  /** Resolve + REQUIRE the connection config, failing FAST if `SERVER` or
    * `SECRET` is missing. This is the misconfiguration boundary: a caller that
    * hands the connection to a reconnecting supervisor ([[connectWithClose]] →
    * [[fh.view.runtime.HaFeed]]) resolves ONCE here at boot, so a missing
    * credential crashes immediately instead of being swallowed by the retry
    * loop and mistaken for an unreachable-HA outage. (The socket connect that
    * [[connectWithClose]] performs on each attempt IS the retryable part.)
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

  /** The reconnectable connection for an already-resolved [[Env]] — the socket
    * + auth only, which is what a supervisor re-`.use`s on each reconnect. Kept
    * separate from [[resolveEnv]] so credential errors surface at boot, not on
    * a background reconnect attempt.
    */
  def connectWithClose(
      env: Env
  ): Resource[IO, (HomeAssistantApi[IO], IO[Unit])] =
    fromWithClose(env.server, env.secretToken, env.serverWs)

  /** Like [[connectWithClose]] but yields the raw low-level WS connection
    * (`HAWSApiLowLevel`) rather than the high-level [[HomeAssistantApi]]. The
    * reconnecting supervisor ([[fh.view.runtime.HaFeed]]) fronts THIS with a
    * durable facade and rebuilds the high-level API over it, so the whole API
    * survives reconnects behind one seam.
    */
  def lowLevelConnectWithClose(
      env: Env
  ): Resource[IO, (HAWSApiLowLevel[IO], IO[Unit])] =
    lowLevelWithClose(env.server, env.secretToken, env.serverWs)

  // TODO websocket api https://developers.home-assistant.io/docs/api/websocket
  def from(
      api: Uri,
      secretToken: String,
      wsUriOverride: Option[Uri] = None
  ): Resource[IO, HomeAssistantApi[IO]] =
    fromWithClose(api, secretToken, wsUriOverride).map(_._1)

  /** Like [[from]], but also returns the connection's `awaitClosed` signal (see
    * [[fromEnvWithClose]]).
    */
  def fromWithClose(
      api: Uri,
      secretToken: String,
      wsUriOverride: Option[Uri] = None
  ): Resource[IO, (HomeAssistantApi[IO], IO[Unit])] =
    lowLevelWithClose(api, secretToken, wsUriOverride).map { case (ws, close) =>
      (HomeAssistantApi.fromWs(ws), close)
    }

  /** The single WebSocket connection + its `awaitClosed`. WS-only: one
    * connection backs the whole API (states, services, templates,
    * subscriptions, `call_service`) — no REST client. [[fromWithClose]] wraps
    * this in the high-level API; [[lowLevelConnectWithClose]] hands it raw to
    * the reconnecting supervisor.
    */
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

      // Frame tracing is a logger level now, not a constructor flag: set
      // `api.homeassistant.ws.HAWSApiLowLevel` to DEBUG in logback.xml.
      wsApi <- HAWSApiLowLevel(wsClient, wsUri, secretToken)
    } yield (wsApi, wsApi.awaitClosed)
}
