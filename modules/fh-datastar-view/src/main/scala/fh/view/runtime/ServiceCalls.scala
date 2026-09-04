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

/** How an action reaches Home Assistant — the one thing a dashboard does that
  * WRITES, and the only use this server has for an HA credential beyond
  * reading.
  *
  * A seam because IDENTITY is the question (issue #198). HA attributes a
  * `call_service` to whoever owns the CONNECTION, decided once in its auth
  * handshake — so the one shared socket the whole app reads through makes every
  * tap, by every person, the add-on's own, and the logbook says Supervisor
  * pressed it. Who pressed is a property of the REQUEST, and the two can only
  * meet if the call gets a connection of its own.
  *
  * The `req` is here for exactly that: it is what carries the person.
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

  /** The instance's own identity, over the connection everything else already
    * uses — what this always did.
    *
    * Not a legacy path: a deployment with no login configured has nobody to be,
    * and behind ingress HA has authenticated the user without ever giving us a
    * token for them. Both land here, and both are the honest answer rather than
    * a failure.
    */
  def asInstance(api: HomeAssistantApi[IO]): ServiceCalls =
    (_, domain, service, entityId, serviceData) =>
      api.callService(domain, service, entityId, serviceData)

  /** One socket per action, authenticated as the person who pressed.
    *
    * Per ACTION, which is the expensive shape: a connect plus HA's
    * `auth_required`/`auth_ok` handshake for every button press. It is here
    * first because it needs no lifecycle at all — the two cheaper shapes (a
    * pooled socket per logged-in person, or the REST API with the token on the
    * request) both answer the same question, and the pool's reaping and
    * staleness rules are worth writing only once this is known to work.
    *
    * A request with no auth session falls through to `fallback`. See
    * [[asInstance]] for why that is a real case rather than a hole.
    *
    * `connectAs` rather than a URL: WHERE a per-user credential is accepted is
    * a third address, neither the browser's nor the feed's (under the add-on
    * the feed talks to the supervisor proxy, which takes only the add-on's own
    * token), and `ServerApp` already has to work that out for `identify`. One
    * expression, used by both, rather than that reasoning written twice.
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

    /** The session's stored token while it is comfortably good, a fresh one
      * otherwise.
      *
      * '''The margin is the mechanism, not a nicety.''' A handshake that loses
      * the race is answered `auth_invalid`, and the transport reports that as
      * an ordinary connect failure — one exception, indistinguishable from a
      * dead network — so there is no classifier to write on the other side.
      * Refusing early is what keeps the two apart.
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
            // The grant is gone at HA, so this person is not logged in any
            // more, and that is bigger than one refused tap. Dropping the
            // session is what cuts their open streams (`AuthSessions.watch`),
            // so the button and the page stop together rather than the
            // dashboard staying live around a control that can never work.
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

  /** Seconds of remaining life an access token needs to be used as-is. */
  private val Margin: Long = 60.seconds.toSeconds
}
