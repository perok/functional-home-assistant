package fh.view.auth

import cats.effect.IO
import org.http4s.{Request, ResponseCookie, SameSite, Uri}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What a login carries from `/auth/login` to its callback: a cookie named
  * after the OAuth `state`, holding `next` and its issue time under an HMAC.
  * Requiring it binds the callback to the browser that started the login, and
  * the server holds nothing in between (ADR 0023).
  */
final class LoginTickets private (key: SecretKeySpec, random: SecureRandom) {
  import LoginTickets.*

  /** The `state` to send HA, and the cookie that must come back with it. */
  def issue(
      next: String,
      now: Instant,
      path: Uri.Path,
      secure: Boolean
  ): IO[(String, ResponseCookie)] =
    IO {
      val nonce = new Array[Byte](16)
      random.nextBytes(nonce)
      encode(nonce)
    }.map { state =>
      val body = s"${now.getEpochSecond}.${encode(next.getBytes(UTF_8))}"
      val ticket = s"$body.${sign(state, body)}"
      state -> cookie(state, ticket, path, secure, TtlSeconds)
    }

  /** `next`, when `req` carries an unexpired, untampered ticket for `state`. */
  def redeem(req: Request[IO], state: String, now: Instant): Option[String] =
    req.cookies
      .collectFirst { case c if c.name == nameOf(state) => c.content }
      .flatMap(_.split('.') match {
        case Array(issued, next, mac)
            if MessageDigest.isEqual(
              sign(state, s"$issued.$next").getBytes(UTF_8),
              mac.getBytes(UTF_8)
            ) =>
          issued.toLongOption
            .filter(s => now.getEpochSecond - s < TtlSeconds)
            .map(_ => new String(Base64.getUrlDecoder.decode(next), UTF_8))
        case _ => None
      })

  /** Must match the issued cookie's name and path, or the browser keeps it. */
  def clear(state: String, path: Uri.Path, secure: Boolean): ResponseCookie =
    cookie(state, "", path, secure, 0L)

  // The state is signed too, so a ticket cannot be renamed onto another login.
  private def sign(state: String, body: String): String = {
    val mac = Mac.getInstance(Algorithm)
    mac.init(key)
    encode(mac.doFinal(s"$state.$body".getBytes(UTF_8)))
  }
}

object LoginTickets {

  val TtlSeconds: Long = 600L

  private val Algorithm = "HmacSHA256"

  /** One per login, so two tabs logging in at once do not overwrite each other.
    */
  def nameOf(state: String): String = s"fh_login_$state"

  private def encode(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** The session cookie's attributes, for its reasons ([[AuthSessions.cookie]]):
    * `Lax` because the callback is a cross-site GET, `secure` only over https.
    */
  private def cookie(
      state: String,
      content: String,
      path: Uri.Path,
      secure: Boolean,
      maxAge: Long
  ): ResponseCookie =
    ResponseCookie(
      name = nameOf(state),
      content = content,
      httpOnly = true,
      secure = secure,
      sameSite = Some(SameSite.Lax),
      path = Some(path.renderString),
      maxAge = Some(maxAge)
    )

  /** The key lives only in memory, so a login in flight across a restart starts
    * again.
    */
  def create: IO[LoginTickets] =
    IO {
      val random = new SecureRandom()
      val key = new Array[Byte](32)
      random.nextBytes(key)
      new LoginTickets(new SecretKeySpec(key, Algorithm), random)
    }
}
