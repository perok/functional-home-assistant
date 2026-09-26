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

private final case class Pending(next: String, deadline: Instant)

/** The OAuth endpoints (issue #89); ungated, since a login page cannot need a
  * login.
  */
final class AuthRoutes(
    oauth: HaOAuth,
    sessions: AuthSessions,
    identify: String => IO[HaUser],
    pending: Ref[IO, Map[String, Pending]],
    baseUriOf: Request[IO] => Uri
) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "auth" / "login" =>
      val next = AuthGate.safeNext(req.uri.query.params.get("next"))
      for {
        state <- IO(java.util.UUID.randomUUID().toString)
        now <- IO.realTimeInstant
        _ <- pending.update(
          // Pruned here: only a login can have grown the map.
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

    case req @ GET -> Root / "auth" / "callback" =>
      val params = req.uri.query.params
      (params.get("code"), params.get("state")) match {
        case (Some(code), Some(state)) => complete(req, code, state)
        case _                         =>
          // A cancelled login arrives as `?error=access_denied`.
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

    // Revoked at HA too, so it leaves the user's Profile -> Security list.
    case req @ POST -> Root / "auth" / "logout" =>
      AuthSessions.cookieOf(req).flatTraverse(sessions.get).flatMap { current =>
        val revoked = current.traverse_(s => oauth.revoke(s.refresh))
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
      // Removed as it is read, so a code cannot be replayed against it.
      claimed <- pending.modify { m =>
        (m - state, m.get(state).filter(_.deadline.isAfter(now)))
      }
      next <- claimed
        .map(_.next)
        .liftTo[IO](
          FHError.badCondition(
            "This login has expired or was already used. Open the site root (/) to start a new login."
          )
        )
      base = baseUriOf(req)
      tokens <- oauth.exchange(code, base)
      refresh <- tokens.refreshToken.liftTo[IO](
        FHError.internal(
          "Home Assistant returned no refresh token for the login."
        )
      )
      user <- identify(tokens.accessToken)
      // After the exchange, so the stored expiry cannot outlast the real one.
      mintedAt <- IO.realTimeInstant
      id <- sessions.create(
        user,
        refresh,
        base,
        Some(
          HaAccess(tokens.accessToken, mintedAt.plusSeconds(tokens.expiresIn))
        )
      )
      resp <- SeeOther(Location(Uri.unsafeFromString(next)))
    } yield resp.addCookie(AuthSessions.cookie(id, isSecure(req)))

  private def redirectUri(base: Uri): Uri =
    base.withPath(base.path / "auth" / "callback").copy(query = Query.empty)

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
