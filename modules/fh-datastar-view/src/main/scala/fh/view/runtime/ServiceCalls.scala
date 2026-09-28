package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{IO, Resource}
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
import org.http4s.{Request, Uri}

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
      entityId: String,
      serviceData: Json
  ): IO[Json]
}

object ServiceCalls {

  /** Not a legacy path: with no login there is nobody else to be, and ingress
    * authenticates a user without giving us their token.
    */
  def asInstance(api: HomeAssistantApi[IO]): ServiceCalls =
    (_, domain, service, entityId, serviceData) =>
      api.callService(domain, service, entityId, serviceData)

  /** One socket per action, as the person who pressed: a handshake per tap,
    * chosen first because it needs no lifecycle; a pool or REST can follow. No
    * auth session falls back ([[asInstance]]). `connectAs` is shared with
    * `identify` so the address ranking is written once.
    */
  def asUser(
      fallback: HomeAssistantApi[IO],
      connectAs: String => Resource[IO, HomeAssistantApi[IO]],
      sessions: AuthSessions,
      oauth: HaOAuth
  ): ServiceCalls = new ServiceCalls {

    def call(
        req: Request[IO],
        domain: String,
        service: String,
        entityId: String,
        serviceData: Json
    ): IO[Json] =
      AuthSessions
        .cookieOf(req)
        .flatTraverse(id => sessions.get(id).map(_.tupleLeft(id)))
        .flatMap {
          case None =>
            fallback.callService(domain, service, entityId, serviceData)
          case Some((id, session)) =>
            tokenFor(id, session).flatMap { token =>
              connectAs(token)
                .use(_.callService(domain, service, entityId, serviceData))
            }
        }

    /** The margin is the mechanism: an expired token's `auth_invalid` looks
      * exactly like a dead network, so refresh early instead of classifying.
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
