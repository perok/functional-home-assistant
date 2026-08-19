package fh.view.auth

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import io.circe.parser
import org.http4s.client.Client
import org.http4s.{Method, Request, Status, UrlForm, Uri}

/** What HA hands back from `/auth/token`. `refreshToken` is present on the
  * authorization-code grant and absent on a refresh (HA reissues only the
  * access token), which is why it is optional here rather than at the call
  * sites.
  */
final case class Tokens(
    accessToken: String,
    refreshToken: Option[String],
    expiresIn: Int
)

/** The outcome of trying to renew a session against HA.
  *
  * `Dead` and a raised error are deliberately different things: `Dead` is HA
  * ANSWERING that the grant is gone (the user revoked us in Profile → Security,
  * or was deleted), and the only correct response is to evict the session. A
  * timeout or a refused connection is not an answer, so it stays in `IO`'s
  * error channel where a caller can retry without logging anyone out — an
  * unreachable HA must not empty the session store.
  */
enum RefreshOutcome derives CanEqual:
  case Renewed(tokens: Tokens)
  case Dead

/** Home Assistant as an OAuth2 provider (issue #89).
  *
  * HA implements OAuth2 plus the IndieAuth extension, so this server is an
  * ordinary OAuth client and needs no add-on and no crypto library. The one
  * IndieAuth wrinkle that matters: `client_id` is a URL, and HA fetches it
  * looking for a `<link rel="redirect_uri">` ONLY when `redirect_uri` does not
  * share its host and port. Ours always does — both are this server's own base
  * URL — so a LAN-only instance never has to be reachable from outside.
  * Verified against HA 2026.8.2: `/auth/authorize` with a `192.168.x` client_id
  * answers 200 with the login page.
  *
  * `haBase` is the browser-facing HA URL, which is not always the one this
  * server dials (see `FH_HA_PUBLIC_URL` in `ServerApp`).
  */
final class HaOAuth(haBase: Uri, client: Client[IO]) {

  /** Where to send the browser to log in. `state` is round-tripped by HA
    * untouched; we use it to name the pending authorization.
    */
  def authorizeUri(clientId: Uri, redirect: Uri, state: String): Uri =
    (haBase / "auth" / "authorize").withQueryParams(
      Map(
        "client_id" -> clientId.renderString,
        "redirect_uri" -> redirect.renderString,
        "state" -> state
      )
    )

  /** Trade the callback's `code` for tokens. A bad or replayed code is the
    * caller's problem and terminal — there is no retry that would help — so it
    * raises rather than returning a value the login route would only re-inspect.
    */
  def exchange(code: String, clientId: Uri): IO[Tokens] =
    post(
      UrlForm(
        "grant_type" -> "authorization_code",
        "code" -> code,
        "client_id" -> clientId.renderString
      )
    ).flatMap {
      case Right(tokens) => IO.pure(tokens)
      case Left(body)    =>
        FHError
          .badCondition(s"Home Assistant rejected the login code: $body")
          .raiseError[IO, Tokens]
    }

  /** Renew against a stored refresh token — the periodic proof that this HA
    * user still exists and still holds its role. See [[RefreshOutcome]] for why
    * a rejection is a value and a network failure is not.
    */
  def refresh(refreshToken: String, clientId: Uri): IO[RefreshOutcome] =
    post(
      UrlForm(
        "grant_type" -> "refresh_token",
        "refresh_token" -> refreshToken,
        "client_id" -> clientId.renderString
      )
    ).map {
      case Right(tokens) => RefreshOutcome.Renewed(tokens)
      case Left(_)       => RefreshOutcome.Dead
    }

  /** Tell HA to forget a refresh token, so logging out here also drops the
    * device from the user's own Profile → Security list.
    *
    * HA answers 200 with an empty body whatever happened (verified), so there
    * is nothing to check and nothing a failure would tell us — but a dead
    * network must not fail a logout, since the session is being dropped locally
    * regardless. Hence the swallow.
    */
  def revoke(token: String): IO[Unit] =
    client
      .status(
        Request[IO](Method.POST, haBase / "auth" / "revoke")
          .withEntity(UrlForm("token" -> token))
      )
      .attempt
      .void

  private def post(form: UrlForm): IO[Either[String, Tokens]] =
    client.run(
      Request[IO](Method.POST, haBase / "auth" / "token").withEntity(form)
    ).use { resp =>
      resp.bodyText.compile.string.map { body =>
        if (resp.status === Status.Ok) parseTokens(body)
        else Left(body.take(200))
      }
    }

  private def parseTokens(body: String): Either[String, Tokens] =
    parser.parse(body).toOption.flatMap(_.asObject) match {
      case None     => Left("unparseable token response")
      case Some(js) =>
        js("access_token").flatMap(_.asString) match {
          case None        => Left("token response carried no access_token")
          case Some(token) =>
            Right(
              Tokens(
                accessToken = token,
                refreshToken = js("refresh_token").flatMap(_.asString),
                // HA sends 1800; the fallback only keeps a malformed response
                // from reading as "already expired".
                expiresIn =
                  js("expires_in").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(1800)
              )
            )
        }
    }
}
