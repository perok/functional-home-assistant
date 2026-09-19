package fh.view.auth

import api.homeassistant.ws.domain.{HaAccount, HaUser}
import cats.effect.IO
import com.comcast.ip4s.{Ipv4Address, ipv4}
import org.http4s.Request
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** Home Assistant has already authenticated the user, when it is HA doing the
  * asking (issue #89).
  *
  * Behind the add-on's ingress, a request reaches this server only after HA has
  * logged the user in and the Supervisor has proxied it — so there is nobody
  * left to log in, and the OAuth flow is only for someone reaching the direct
  * port. What the Supervisor forwards, verified against its own source rather
  * than assumed (`supervisor/api/ingress.py`, `supervisor/const.py`):
  *
  * {{{
  * X-Remote-User-Id            the HA user id
  * X-Remote-User-Name          their username
  * X-Remote-User-Display-Name  their display name
  * }}}
  *
  * Those three are STRIPPED from the incoming request before being re-added —
  * the Supervisor's own comment says "to prevent client spoofing" — so a client
  * cannot inject them through ingress.
  *
  * It can inject them at the DIRECT port, though, which is why the header is
  * worth nothing on its own. The trust comes from where the connection came
  * from: ingress arrives from the Supervisor at a fixed address, and the add-on
  * documentation makes that a requirement rather than an observation — "Only
  * connections from 172.30.32.2 must be allowed. You should deny access to all
  * other IP addresses within your app server." A source address cannot be
  * forged on an established TCP connection, so this is the boundary.
  *
  * The port cannot be the boundary here: `home-addon/config.yaml` gives ingress
  * and the optional direct port the SAME 8080, so they are indistinguishable by
  * the socket they landed on.
  *
  * The headers carry no ROLE, which is the one thing they are missing — HA
  * sends id and names and nothing about admin. So the id is resolved against
  * the account list this server already fetches for the dump, and a user it
  * cannot find is not an identity: a header naming somebody unknown must not
  * become an anonymous-but-present user, since `Access.Authenticated` would
  * admit them.
  */
object Ingress {

  /** The Supervisor's fixed address on the `hassio` network. */
  val SupervisorIp: Ipv4Address = ipv4"172.30.32.2"

  val UserIdHeader: CIString = CIString("X-Remote-User-Id")

  /** Whether this request came through the Supervisor's ingress proxy — the
    * only thing that makes the user headers worth reading.
    */
  def isIngress(req: Request[IO], trusted: Ipv4Address): Boolean =
    req.remote.map(_.host).contains(trusted)

  /** The HA user id ingress says is behind this request, if it is one. */
  def userIdOf(req: Request[IO], trusted: Ipv4Address): Option[String] =
    Option
      .when(isIngress(req, trusted))(req.headers.get(UserIdHeader))
      .flatten
      .map(_.head.value)
      .filter(_.nonEmpty)
}

/** Resolves an ingress user id to the user, role included.
  *
  * A function rather than a class so the wiring stays in `ServerApp` and a test
  * can hand over a fixed household — and `None` is a real answer here, meaning
  * "HA named somebody this instance does not know", which must not be treated
  * as a logged-in nobody.
  */
type IngressUsers = String => IO[Option[HaUser]]

object IngressUsers {

  /** How long a fetched household is reused. Long enough that ingress costs no
    * round trip per request, short enough that adding a user or granting admin
    * takes effect without a restart.
    */
  val Ttl: FiniteDuration = 5.minutes

  /** Resolve ids against `fetch`, re-asking at most once per [[Ttl]].
    *
    * `IO[IngressUsers]`, not `IngressUsers`, because the `Ref` is built ONCE:
    * `ServerApp` runs this at boot and the function it returns closes over that
    * one cache for the life of the process. Building it inside the returned
    * function instead would give every request its own empty cache.
    *
    * A failed fetch is not cached and not fatal: it answers "unknown" for that
    * request, so an unreachable HA makes ingress users anonymous rather than
    * making every one of them an admin. Erring toward less access is the whole
    * point of not caching the failure.
    */
  def cached(
      fetch: IO[List[HaAccount]],
      ttl: FiniteDuration = Ttl
  ): IO[IngressUsers] =
    IO.ref(Option.empty[(FiniteDuration, Map[String, HaUser])])
      .map { cache => (id: String) =>
        IO.monotonic.flatMap { now =>
          cache.get
            .flatMap {
              case Some((at, users)) if now - at < ttl => IO.pure(users)
              case _                                   =>
                fetch.attempt.flatMap {
                  case Left(_)         => IO.pure(Map.empty[String, HaUser])
                  case Right(accounts) =>
                    val users = accounts
                      .filter(_.isPerson)
                      .map { a =>
                        a.id -> HaUser(a.id, a.name, a.isAdmin, a.is_owner)
                      }
                      .toMap
                    cache.set(Some(now -> users)).as(users)
                }
            }
            .map(_.get(id))
        }
      }
}
