package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.{HttpRoutes, Query, Request, Response, Uri}

/** The OAuth endpoints (issue #89); ungated, since a login page cannot need a
  * login.
  */
final class AuthRoutes(
    oauth: HaOAuth,
    sessions: AuthSessions,
    identify: String => IO[HaUser],
    tickets: LoginTickets,
    baseUriOf: Request[IO] => Uri
) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "auth" / "login" =>
      val next = AuthGate.safeNext(req.uri.query.params.get("next"))
      val base = baseUriOf(req)
      for {
        now <- IO.realTimeInstant
        issued <- tickets.issue(
          next,
          now,
          redirectUri(base).path,
          isSecure(req)
        )
        (state, ticket) = issued
        resp <- SeeOther(
          Location(
            oauth.authorizeUri(
              clientId = base,
              redirect = redirectUri(base),
              state = state
            )
          )
        )
      } yield resp.addCookie(ticket)

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
  ): IO[Response[IO]] = {
    val base = baseUriOf(req)
    for {
      now <- IO.realTimeInstant
      next <- tickets
        .redeem(req, state, now)
        .liftTo[IO](
          FHError.badCondition(
            "This login has expired, was already used, or was started in another browser. Open the site root (/) to start a new login."
          )
        )
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
    } yield resp
      .addCookie(AuthSessions.cookie(id, isSecure(req)))
      .addCookie(tickets.clear(state, redirectUri(base).path, isSecure(req)))
  }

  private def redirectUri(base: Uri): Uri =
    base.withPath(base.path / "auth" / "callback").copy(query = Query.empty)

  private def isSecure(req: Request[IO]): Boolean =
    baseUriOf(req).scheme.exists(_.value == "https")
}

object AuthRoutes {
  def create(
      oauth: HaOAuth,
      sessions: AuthSessions,
      identify: String => IO[HaUser],
      baseUriOf: Request[IO] => Uri
  ): IO[AuthRoutes] =
    LoginTickets.create.map(
      new AuthRoutes(oauth, sessions, identify, _, baseUriOf)
    )
}
