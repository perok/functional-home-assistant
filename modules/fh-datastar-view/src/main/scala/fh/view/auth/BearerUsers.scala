package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.effect.std.Semaphore
import cats.syntax.all.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat
import scala.concurrent.duration.*

/** Resolving a bearer token is a WebSocket handshake to HA (`auth/current_user`
  * is WS-only), and without this it was one per request, SSE reconnects
  * included.
  */
object BearerUsers {

  /** Bounds what junk tokens can make us open against HA: they never repeat, so
    * no cache helps with them.
    */
  val MaxLookups: Int = 4

  /** A resolved token is trusted for `ttl`, so a revoked one lingers that long
    * — shorter than a bearer SSE stream, which is never revoked at all. A
    * failure is not cached, so the map holds only tokens HA vouched for; it is
    * keyed by digest so the tokens themselves are not held.
    */
  def cached(
      identify: String => IO[HaUser],
      ttl: FiniteDuration = IngressUsers.Ttl,
      maxLookups: Int = MaxLookups
  ): IO[String => IO[HaUser]] =
    (
      IO.ref(Map.empty[String, (FiniteDuration, HaUser)]),
      Semaphore[IO](maxLookups.toLong)
    ).mapN { (cache, lookups) => (token: String) =>
      val key = digest(token)
      IO.monotonic.flatMap { now =>
        cache.get.map(_.get(key)).flatMap {
          case Some((at, user)) if now - at < ttl => IO.pure(user)
          case _                                  =>
            lookups.permit.surround(identify(token)).flatTap { user =>
              IO.monotonic.flatMap(at =>
                cache.update(
                  _.filter { case (_, (t, _)) => at - t < ttl } +
                    (key -> (at -> user))
                )
              )
            }
        }
      }
    }

  private def digest(token: String): String =
    HexFormat
      .of()
      .formatHex(
        MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8))
      )
}
