package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fh.view.FHError
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.{HttpRoutes, Query, Request, Response, Uri}

import java.time.Instant

/** A login this server started and is waiting for HA to send back.
  *
  * `next` is where the browser was going before it was interrupted. The
  * `deadline` bounds how long a half-finished login stays claimable.
  */
private final case class Pending(next: String, deadline: Instant)

/** The OAuth endpoints (issue #89).
  *
  * The whole browser-facing half of authentication: start a login, finish one,
  * end one. Everything else in the system only ever reads the cookie these set.
  *
  * None of them goes through the gate, and none can: a login page that needs a
  * login cannot load. That is why there is no [[AuthGate]] here at all.
  */
final class AuthRoutes(
    oauth: HaOAuth,
    sessions: AuthSessions,
    identify: String => IO[HaUser],
    pending: Ref[IO, Map[String, Pending]],
    baseUriOf: Request[IO] => Uri
) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // Start a login. `next` is where to land afterwards; anything that is not a
    // local path is dropped (see AuthGate.safeNext) so this cannot be used as
    // an open redirect.
    case req @ GET -> Root / "auth" / "login" =>
      val next = AuthGate.safeNext(req.uri.query.params.get("next"))
      for {
        state <- IO(java.util.UUID.randomUUID().toString)
        now <- IO.realTimeInstant
        _ <- pending.update(
          // Expired entries are dropped on the way past rather than by a
          // sweeper fiber: this map is only ever touched by a login, so a
          // login is the only moment it can have grown.
          _.filter(_._2.deadline.isAfter(now)) +
            (state -> Pending(next, now.plusSeconds(PendingTtlSeconds)))
        )
        base = baseUriOf(req)
        resp <- SeeOther(
          Location(
            oauth.authorizeUri(
              clientId = base,
              redirect = redirectUri(base),
              state = state
            )
          )
        )
      } yield resp

    // HA sends the browser back here with a one-time code.
    case req @ GET -> Root / "auth" / "callback" =>
      val params = req.uri.query.params
      (params.get("code"), params.get("state")) match {
        case (Some(code), Some(state)) => complete(req, code, state)
        case _                         =>
          // Also the shape of a user-cancelled login, which arrives as
          // `?error=access_denied` with no code.
          FHError
            .badCondition(
              params
                .get("error")
                .fold("The login response carried no code.")(e =>
                  s"Home Assistant refused the login: $e"
                )
            )
            .raiseError[IO, Response[IO]]
      }

    // End it here AND at HA, so the entry disappears from the user's own
    // Profile -> Security list rather than lingering as a device they cannot
    // account for.
    case req @ POST -> Root / "auth" / "logout" =>
      AuthSessions.cookieOf(req).flatTraverse(sessions.get).flatMap { current =>
        val revoked = current.traverse_(s => oauth.revoke(s.refresh))
        // Every session for this user, not just this cookie: signing out on the
        // phone is meant to end the tablet too.
        val dropped = current.traverse_(s => sessions.removeUser(s.user.id))
        revoked *> dropped *> SeeOther(Location(Uri(path = Uri.Path.Root)))
          .map(_.addCookie(AuthSessions.clearCookie(isSecure(req))))
      }
  }

  private def complete(
      req: Request[IO],
      code: String,
      state: String
  ): IO[Response[IO]] =
    for {
      now <- IO.realTimeInstant
      // Claim the state: taken out of the map as it is read, so a code cannot
      // be replayed against the same pending login twice.
      claimed <- pending.modify { m =>
        (m - state, m.get(state).filter(_.deadline.isAfter(now)))
      }
      next <- claimed
        .map(_.next)
        .liftTo[IO](
          FHError.badCondition(
            "This login has expired or was already used. Try again."
          )
        )
      base = baseUriOf(req)
      tokens <- oauth.exchange(code, base)
      refresh <- tokens.refreshToken.liftTo[IO](
        FHError.internal(
          "Home Assistant returned no refresh token for the login."
        )
      )
      // The ONE thing the user's own token is used for: asking HA who it
      // belongs to. Nothing afterwards acts on HA as this user.
      user <- identify(tokens.accessToken)
      id <- sessions.create(user, refresh, base)
      resp <- SeeOther(Location(Uri.unsafeFromString(next)))
    } yield resp.addCookie(AuthSessions.cookie(id, isSecure(req)))

  private def redirectUri(base: Uri): Uri =
    base.withPath(base.path / "auth" / "callback").copy(query = Query.empty)

  /** Whether to mark the cookie `Secure`. Set it on a plain-http LAN instance
    * and the browser silently drops the cookie, so login would appear to
    * succeed and never stick.
    */
  private def isSecure(req: Request[IO]): Boolean =
    baseUriOf(req).scheme.exists(_.value == "https")

  private val PendingTtlSeconds = 600L
}

object AuthRoutes {
  def create(
      oauth: HaOAuth,
      sessions: AuthSessions,
      identify: String => IO[HaUser],
      baseUriOf: Request[IO] => Uri
  ): IO[AuthRoutes] =
    Ref[IO]
      .of(Map.empty[String, Pending])
      .map(new AuthRoutes(oauth, sessions, identify, _, baseUriOf))
}
