package fh.view.runtime

import api.DocumentJson
import api.homeassistant.{HomeAssistantApi, ServiceTarget}
import api.homeassistant.rest.restApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.auth.{
  AuthSession,
  AuthSessions,
  HaAccess,
  HaOAuth,
  RefreshOutcome
}
import io.circe.Json
import org.http4s.client.Client
import org.http4s.{Request, Uri}
import smithy4s.http.RawErrorResponse

import scala.concurrent.duration.*

/** How an action reaches HA. A seam because of identity (issue #198): HA
  * attributes a `call_service` to the connection's owner, so on the shared
  * socket every tap is the add-on's. `req` carries who pressed.
  */
trait ServiceCalls {
  def call(
      req: Request[IO],
      domain: String,
      service: String,
      target: ServiceTarget,
      serviceData: Json
  ): IO[Unit]
}

object ServiceCalls {

  /** One call as the person holding the token. */
  type CallAs =
    String => (
        domain: String,
        service: String,
        target: ServiceTarget,
        serviceData: Json
    ) => IO[Unit]

  /** Not a legacy path: with no login there is nobody else to be, and ingress
    * authenticates a user without giving us their token.
    */
  def asInstance(api: HomeAssistantApi[IO]): ServiceCalls =
    (_, domain, service, target, serviceData) =>
      api.callService(domain, service, target, serviceData).void

  /** Over REST, not a socket per tap: HA attributes the call to the token's
    * user either way, and HTTP needs no handshake and no lifecycle. `core` is
    * where user tokens are accepted ([[HaOAuth.coreBase]]).
    */
  def overRest(client: Client[IO], core: Uri): CallAs =
    token =>
      (domain, service, target, serviceData) =>
        restApi(client, core, token)
          .use(
            _.postServiceApi(
              domain,
              service,
              DocumentJson.toDocument(
                serviceData.deepMerge(target.json)
              )
            )
          )
          .void
          .adaptError { case e: RawErrorResponse => refusal(e) }

  /** A bare `vol.Invalid` or unknown service answers `400: Bad Request` with no
    * message; a service's `Unauthorized` is a 401, which means "not allowed"
    * only because [[asUser]] refreshes a token before it expires.
    */
  private[runtime] def refusal(e: RawErrorResponse): FHError = {
    val said = io.circe.parser
      .parse(e.body)
      .toOption
      .flatMap(_.hcursor.get[String]("message").toOption)
    FHError.badCondition(e.code match {
      case 401  => "Home Assistant does not allow you to do that."
      case code =>
        said.getOrElse(s"Home Assistant refused the action (HTTP $code).")
    })
  }

  /** As the person who pressed. No auth session falls back ([[asInstance]]).
    */
  def asUser(
      fallback: HomeAssistantApi[IO],
      callAs: CallAs,
      sessions: AuthSessions,
      oauth: HaOAuth
  ): ServiceCalls = new ServiceCalls {

    def call(
        req: Request[IO],
        domain: String,
        service: String,
        target: ServiceTarget,
        serviceData: Json
    ): IO[Unit] =
      AuthSessions
        .cookieOf(req)
        .flatTraverse(id => sessions.get(id).map(_.tupleLeft(id)))
        .flatMap {
          case None =>
            fallback.callService(domain, service, target, serviceData).void
          case Some((id, session)) =>
            tokenFor(id, session).flatMap(
              callAs(_)(domain, service, target, serviceData)
            )
        }

    /** The margin is the mechanism: an expired token would be refused like a
      * forbidden call ([[refusal]]), so refresh early instead of classifying.
      */
    private def tokenFor(id: String, session: AuthSession): IO[String] =
      IO.realTimeInstant.flatMap { now =>
        session.access.filter(
          _.expiresAt.isAfter(now.plusSeconds(Margin))
        ) match {
          case Some(a) => IO.pure(a.token)
          case None    => mint(id, session)
        }
      }

    private def mint(id: String, session: AuthSession): IO[String] =
      oauth
        .refresh(session.refresh, Uri.unsafeFromString(session.clientId))
        .flatMap {
          case RefreshOutcome.Dead =>
            // Dropping the session also cuts their open streams
            // (`AuthSessions.watch`), so the page stops with the button.
            sessions.remove(id) *>
              FHError
                .badCondition(
                  "Your Home Assistant login is no longer valid — sign in again."
                )
                .raiseError[IO, String]
          case RefreshOutcome.Renewed(tokens) =>
            IO.realTimeInstant.flatMap { at =>
              val access =
                HaAccess(tokens.accessToken, at.plusSeconds(tokens.expiresIn))
              sessions
                .tokenMinted(
                  id,
                  tokens.refreshToken.getOrElse(session.refresh),
                  access
                )
                .as(access.token)
            }
        }
  }

  private val Margin: Long = 60.seconds.toSeconds
}
