package fh.view.auth

import api.homeassistant.ws.domain.{HaAccount, HaUser}
import cats.effect.IO
import com.comcast.ip4s.{Ipv4Address, ipv4}
import org.http4s.Request
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** Behind ingress HA has already logged the user in (issue #89). The Supervisor
  * strips and re-adds `X-Remote-User-*` (`supervisor/api/ingress.py`), but a
  * client can still send them to the direct port, so the trust is the source
  * address: the add-on docs require allowing only 172.30.32.2. The port cannot
  * be the boundary, since `config.yaml` gives ingress and the direct port the
  * same 8080.
  *
  * The headers carry no role, so the id is resolved against the account list;
  * an unknown id is no identity, or `Access.Authenticated` would admit it.
  */
object Ingress {

  val SupervisorIp: Ipv4Address = ipv4"172.30.32.2"

  val UserIdHeader: CIString = CIString("X-Remote-User-Id")

  def isIngress(req: Request[IO], trusted: Ipv4Address): Boolean =
    req.remote.map(_.host).contains(trusted)

  def userIdOf(req: Request[IO], trusted: Ipv4Address): Option[String] =
    Option
      .when(isIngress(req, trusted))(req.headers.get(UserIdHeader))
      .flatten
      .map(_.head.value)
      .filter(_.nonEmpty)
}

/** `None` is "HA named somebody this instance does not know". */
type IngressUsers = String => IO[Option[HaUser]]

object IngressUsers {

  /** So adding a user or granting admin applies without a restart. */
  val Ttl: FiniteDuration = 5.minutes

  /** A failed fetch is not cached and answers "unknown": an unreachable HA
    * makes ingress users anonymous rather than admitted.
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
