package fh.view.auth

import api.homeassistant.ws.domain.{HaAccount, HaUser}
import cats.effect.IO
import cats.syntax.all.*
import com.comcast.ip4s.{ipv4, port, Ipv4Address, SocketAddress}
import fh.view.model.{Access, Permission}
import org.http4s.{Header, Method, Request, Uri}
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** Trusting Home Assistant's word about who is asking (ADR 0023).
  *
  * Behind the add-on's ingress HA has already logged the user in, so the
  * Supervisor's `X-Remote-User-Id` is the answer and there is nobody left to
  * log in. The header is worth NOTHING on its own, though — anyone can send it
  * to the direct port, which shares the same 8080 — so what this suite is
  * really about is the boundary: the header counts only from the Supervisor's
  * own address.
  */
class IngressSuite extends munit.CatsEffectSuite {

  private val peri =
    HaAccount("u1", "Peri", List(HaAccount.AdminGroup), false, true, true)
  private val guest = HaAccount("u2", "Heidi", Nil, false, true, false)

  private def req(from: Ipv4Address, userId: Option[String]) =
    userId
      .foldLeft(Request[IO](Method.GET, Uri.unsafeFromString("/d/kitchen")))(
        (r, id) => r.putHeaders(Header.Raw(Ingress.UserIdHeader, id))
      )
      .withAttribute(
        Request.Keys.ConnectionInfo,
        Request.Connection(
          local = SocketAddress(ipv4"172.30.32.1", port"8080"),
          remote = SocketAddress(from, port"55555"),
          secure = false
        )
      )

  private def gate(trusted: Option[Ipv4Address]) =
    (
      AuthSessions.create(SessionStore.ephemeral),
      IngressUsers.cached(IO.pure(List(peri, guest)))
    ).flatMapN { (sessions, users) =>
      IO.pure(
        new AuthGate(
          sessions,
          _ => IO.raiseError(new Exception("no HA here")),
          _ => IO.pure(Permission(Access.Authenticated, _ => true)),
          users,
          trusted
        )
      )
    }

  test("HA's word is taken when HA is the one asking") {
    gate(Some(Ingress.SupervisorIp)).flatMap { g =>
      g.of(req(Ingress.SupervisorIp, Some("u1"))).map { user =>
        assertEquals(user.map(_.name), Some("Peri"))
        // The headers carry no role — it comes from HA's account list, which is
        // the only reason an ingress admin can reach the editor at all.
        assertEquals(user.map(_.is_admin), Some(true))
      }
    }
  }

  /** The whole point. Ingress and the optional direct port share 8080
    * (`home-addon/config.yaml`), so the socket cannot tell them apart — only
    * the source address can, and a source address cannot be forged on an
    * established TCP connection.
    */
  test("the same header from anywhere else is worth nothing") {
    gate(Some(Ingress.SupervisorIp)).flatMap { g =>
      g.of(req(ipv4"192.168.1.50", Some("u1")))
        .map(assertEquals(_, None))
    }
  }

  test("a header naming somebody this instance does not know is not a user") {
    // Must not become a logged-in nobody: `Access.Authenticated` admits any
    // user, so an unresolvable id has to answer None rather than Some(_).
    gate(Some(Ingress.SupervisorIp)).flatMap { g =>
      g.of(req(Ingress.SupervisorIp, Some("who?"))).map(assertEquals(_, None))
    }
  }

  test("ingress trust is off unless it is configured on") {
    gate(None).flatMap { g =>
      g.of(req(Ingress.SupervisorIp, Some("u1"))).map(assertEquals(_, None))
    }
  }

  test("an unreachable HA makes ingress users anonymous, not admins") {
    (
      AuthSessions.create(SessionStore.ephemeral),
      IngressUsers.cached(IO.raiseError(new Exception("HA is down")))
    ).flatMapN { (sessions, users) =>
      new AuthGate(
        sessions,
        _ => IO.raiseError(new Exception("no HA here")),
        _ => IO.pure(Permission(Access.Authenticated, _ => true)),
        users,
        Some(Ingress.SupervisorIp)
      ).of(req(Ingress.SupervisorIp, Some("u1"))).map(assertEquals(_, None))
    }
  }

  test("the household is fetched once, not once per request") {
    IO.ref(0).flatMap { calls =>
      IngressUsers
        .cached(calls.update(_ + 1).as(List(peri)), 1.hour)
        .flatMap(users =>
          users("u1") *> users("u1") *> users("u2") *> calls.get
        )
        .map(assertEquals(_, 1))
    }
  }

  test("a failed fetch is not cached — the next request asks again") {
    IO.ref(0).flatMap { calls =>
      IngressUsers
        .cached(
          calls.updateAndGet(_ + 1).flatMap {
            case 1 => IO.raiseError(new Exception("down"))
            case _ => IO.pure(List[HaAccount](peri))
          },
          1.hour
        )
        .flatMap(users =>
          users("u1").flatMap(first =>
            users("u1").map(second => (first, second))
          )
        )
        .map { case (first, second) =>
          assertEquals(first, None)
          assertEquals(second.map(_.name), Some("Peri"))
        }
    }
  }
}
