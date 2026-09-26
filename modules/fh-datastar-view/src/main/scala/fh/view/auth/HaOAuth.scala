package fh.view.auth

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import io.circe.{Json, parser}
import org.http4s.client.Client
import org.http4s.{Method, Request, Status, UrlForm, Uri}
import org.http4s.syntax.literals.*

/** `refreshToken` is absent on a refresh: HA reissues only the access token. */
final case class Tokens(
    accessToken: String,
    refreshToken: Option[String],
    expiresIn: Int
)

/** `Dead` is HA answering that the grant is gone, so the session is evicted. A
  * timeout or 5xx is not an answer and stays in the error channel: an
  * unreachable HA must not empty the session store.
  */
enum RefreshOutcome derives CanEqual:
  case Renewed(tokens: Tokens)
  case Dead

object HaOAuth {

  /** HA's documented first-run address; ranked last since it is a guess. */
  val MdnsFallback: Uri =
    uri"http://homeassistant.local:8123"

  /** Container-internal, so no browser can resolve it. */
  private val SupervisorHost = "supervisor"

  private def isSupervisor(uri: Uri): Boolean =
    uri.host.exists(_.value == SupervisorHost)

  /** HA core on the add-on network, per HA's add-on docs. */
  val AddonCoreFallback: Uri =
    uri"http://homeassistant:8123"

  /** Where this process reaches HA core directly, for `/auth/token`,
    * `/auth/revoke` and the websocket that identifies a new user token.
    *
    * Not `SERVER` under the add-on: the supervisor proxies only `/core/api/…`
    * and its websocket, so every add-on login failed with "Home Assistant
    * rejected the login code: 401: Unauthorized". Its websocket authenticates
    * an add-on, not a user, and failed with "Wrong msg: auth_invalid(Invalid
    * access)"; hence [[coreWs]].
    *
    * Ranked `FH_HA_TOKEN_URL`, then `SERVER` unless it is the supervisor, then
    * HA's `internal_url`, then [[AddonCoreFallback]].
    */
  def coreBase(
      explicit: Option[Uri],
      internal: Option[Uri],
      dialed: Uri
  ): Uri =
    explicit
      .orElse(Option.unless(isSupervisor(dialed))(dialed))
      .orElse(internal)
      .getOrElse(AddonCoreFallback)

  /** `SERVER_WS` describes the dialled address only, so it is dropped once
    * [[coreBase]] routes around that address.
    */
  def coreWs(core: Uri, dialed: Uri, dialedWs: Option[Uri]): Option[Uri] =
    dialedWs.filter(_ => core.renderString == dialed.renderString)

  /** Where the browser logs in: `FH_HA_PUBLIC_URL`, then HA's `internal_url`
    * (often unset), then `SERVER` unless it is the supervisor, then
    * [[MdnsFallback]].
    *
    * One answer for every visitor, which is wrong for a remote browser (its
    * target is `external_url`); deferred with the PWA's local-vs-internet work.
    */
  def browserBase(
      explicit: Option[Uri],
      internal: Option[Uri],
      dialed: Uri
  ): Uri =
    explicit
      .orElse(internal)
      .orElse(Option.unless(isSupervisor(dialed))(dialed))
      .getOrElse(MdnsFallback)

  def internalUrlOf(config: Json): Option[Uri] =
    config.hcursor
      .get[Option[String]]("internal_url")
      .toOption
      .flatten
      .flatMap(Uri.fromString(_).toOption)
}

/** HA as an OAuth2 + IndieAuth provider (issue #89). HA fetches `client_id`
  * only when `redirect_uri` has another host and port; ours never does, so a
  * LAN-only instance need not be reachable from outside (verified on HA
  * 2026.8.2).
  *
  * Two addresses, neither derivable from the other: `authorizeBase` for the
  * browser, `tokenBase` for this process. Exchanging at the browser's address
  * failed every production login with a bare 500 (the server could not resolve
  * HA's mDNS name).
  */
final class HaOAuth(authorizeBase: Uri, tokenBase: Uri, client: Client[IO]) {

  /** HA round-trips `state` untouched; it names the pending authorization. */
  def authorizeUri(clientId: Uri, redirect: Uri, state: String): Uri =
    (authorizeBase / "auth" / "authorize").withQueryParams(
      Map(
        "client_id" -> clientId.renderString,
        "redirect_uri" -> redirect.renderString,
        "state" -> state
      )
    )

  def exchange(code: String, clientId: Uri): IO[Tokens] =
    post(
      UrlForm(
        "grant_type" -> "authorization_code",
        "code" -> code,
        "client_id" -> clientId.renderString
      )
    ).flatMap {
      case Right(tokens)   => IO.pure(tokens)
      case Left((_, body)) =>
        FHError
          .badCondition(s"Home Assistant rejected the login code: $body")
          .raiseError[IO, Tokens]
    }

  /** The periodic proof that the user still exists and holds its role. */
  def refresh(refreshToken: String, clientId: Uri): IO[RefreshOutcome] =
    post(
      UrlForm(
        "grant_type" -> "refresh_token",
        "refresh_token" -> refreshToken,
        "client_id" -> clientId.renderString
      )
    ).flatMap {
      case Right(tokens) => IO.pure(RefreshOutcome.Renewed(tokens))
      // Only a 4xx is about the grant; a 5xx while HA restarts is not.
      case Left((status, _)) if status.responseClass == Status.ClientError =>
        IO.pure(RefreshOutcome.Dead)
      case Left((status, body)) =>
        FHError
          .unavailable(
            s"Home Assistant could not renew a login (${status.code}): $body"
          )
          .raiseError[IO, RefreshOutcome]
    }

  /** Drops the device from the user's Profile → Security list. HA answers 200
    * whatever happened, and a dead network must not fail a logout, so errors
    * are swallowed.
    */
  def revoke(token: String): IO[Unit] =
    client
      .status(
        Request[IO](Method.POST, tokenBase / "auth" / "revoke")
          .withEntity(UrlForm("token" -> token))
      )
      .attempt
      .void

  private def post(form: UrlForm): IO[Either[(Status, String), Tokens]] =
    client
      .run(
        Request[IO](Method.POST, tokenBase / "auth" / "token").withEntity(form)
      )
      .use { resp =>
        resp.bodyText.compile.string.map { body =>
          if (resp.status === Status.Ok)
            parseTokens(body).leftMap(resp.status -> _)
          else Left(resp.status -> body.take(200))
        }
      }
      // Never reached HA, so not a dead grant.
      .adaptError { e =>
        FHError
          .unavailable(
            s"could not reach Home Assistant at ${tokenBase.renderString}: ${e.getMessage}"
          )
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
                // HA sends 1800; the fallback keeps a malformed reply from
                // reading as already expired.
                expiresIn = js("expires_in")
                  .flatMap(_.asNumber)
                  .flatMap(_.toInt)
                  .getOrElse(1800)
              )
            )
        }
    }
}
