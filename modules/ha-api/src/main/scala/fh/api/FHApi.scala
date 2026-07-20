package fh.api

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.HAWSApiLowLevel
import api.homeassistant.rest.restApi
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import org.http4s.Uri
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}

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
      server <- IO(lookup("SERVER"))
        .flatMap(_.liftTo[IO](new Exception("Missing SERVER")))
        .flatMap(s => IO(Uri.unsafeFromString(s)))
      secretToken <- IO(lookup("SECRET"))
        .flatMap(_.liftTo[IO](new Exception("Missing SECRET")))
      serverWs <- IO(lookup("SERVER_WS"))
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

  /** A config value: the process environment first (when set and non-empty),
    * then the discovered `.env`.
    */
  private def lookup(key: String): Option[String] =
    sys.env
      .get(key)
      .filter(_.nonEmpty)
      .orElse(dotEnv.get(key).filter(_.nonEmpty))

  /** Parsed `.env` (once), or empty if none is found. */
  private lazy val dotEnv: Map[String, String] =
    findDotEnv() match {
      case None    => Map.empty
      case Some(f) =>
        val src = scala.io.Source.fromFile(f)
        try
          src
            .getLines()
            .map(_.trim)
            .filter(l => l.nonEmpty && !l.startsWith("#"))
            .flatMap { l =>
              l.split("=", 2) match {
                case Array(k, v) =>
                  Some(k.trim -> v.trim.stripPrefix("\"").stripSuffix("\""))
                case _ => None
              }
            }
            .toMap
        finally src.close()
    }

  /** Walk up from the working directory (the app runs with cwd = its module
    * dir) to find the repo-root `.env`. Bounded so a missing file never walks
    * the whole filesystem.
    */
  private def findDotEnv(): Option[java.io.File] =
    Iterator
      .iterate(
        new java.io.File(System.getProperty("user.dir")).getAbsoluteFile
      )(
        _.getParentFile
      )
      .takeWhile(_ != null)
      .take(8)
      .map(new java.io.File(_, ".env"))
      .find(_.isFile)

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
    } yield (HomeAssistantApi.fromLowLevel(wsApi, api), wsApi.awaitClosed)
}
