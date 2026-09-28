package fh.view.auth

import api.homeassistant.ws.domain.{HaAccount, HaUser}
import cats.effect.IO
import cats.syntax.all.*
import com.comcast.ip4s.{ipv4, port, Ipv4Address, SocketAddress}
import fh.view.model.{Access, Permission}
import fs2.Stream
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.{
  AuthScheme,
  Credentials,
  Header,
  Method,
  Request,
  Response,
  Uri
}

import scala.concurrent.duration.*

/** Trusting HA's word about who is asking (ADR 0023). Behind ingress the
  * Supervisor's `X-Remote-User-Id` is the answer, but anyone can send it to the
  * direct port on the same 8080, so this is about the boundary: the header
  * counts only from the Supervisor's own address.
  */
class IngressSuite extends munit.CatsEffectSuite {

  private val peri =
    HaAccount("u1", "Peri", List(HaAccount.AdminGroup), false, true, true)
  private val guest = HaAccount("u2", "Heidi", Nil, false, true, false)

  private def req(from: Ipv4Address, userId: Option[String]) =
    userId
      .foldLeft(Request[IO](Method.GET, uri"/d/kitchen"))((r, id) =>
        r.putHeaders(Header.Raw(Ingress.UserIdHeader, id))
      )
      .withAttribute(
        Request.Keys.ConnectionInfo,
        Request.Connection(
          local = SocketAddress(ipv4"172.30.32.1", port"8080"),
          remote = SocketAddress(from, port"55555"),
          secure = false
        )
      )

  private def gateWithSessions(
      trusted: Option[Ipv4Address],
      identify: String => IO[HaUser] = _ =>
        IO.raiseError(new Exception("no HA here"))
  ): IO[(AuthGate, AuthSessions)] =
    (
      AuthSessions.create(SessionStore.ephemeral),
      IngressUsers.cached(IO.pure(List(peri, guest)))
    ).mapN { (sessions, users) =>
      new AuthGate(
        sessions,
        identify,
        _ => IO.pure(Permission(Access.Authenticated, _ => true)),
        users,
        trusted
      ) -> sessions
    }

  private def gate(trusted: Option[Ipv4Address]) =
    gateWithSessions(trusted).map(_._1)

  test("HA's word is taken when HA is the one asking") {
    gate(Some(Ingress.SupervisorIp)).flatMap { g =>
      g.of(req(Ingress.SupervisorIp, Some("u1"))).map { user =>
        assertEquals(user.map(_.name), Some("Peri"))
        // The headers carry no role; it comes from HA's account list.
        assertEquals(user.map(_.is_admin), Some(true))
      }
    }
  }

  /** Ingress and the direct port share 8080 (`home-addon/config.yaml`), so only
    * the source address tells them apart, and it cannot be forged on an
    * established TCP connection.
    */
  test("the same header from anywhere else is worth nothing") {
    gate(Some(Ingress.SupervisorIp)).flatMap { g =>
      g.of(req(ipv4"192.168.1.50", Some("u1")))
        .map(assertEquals(_, None))
    }
  }

  test("a header naming somebody this instance does not know is not a user") {
    // `Access.Authenticated` admits any user, so an unresolvable id must be
    // None.
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
          users("u1")
            .flatMap(first => users("u1").map(second => (first, second)))
        )
        .map { case (first, second) =>
          assertEquals(first, None)
          assertEquals(second.map(_.name), Some("Peri"))
        }
    }
  }

  /** Held rather than consumed in the handler, which returns a throwaway
    * response.
    */
  private def revocationFor(
      g: AuthGate,
      request: Request[IO]
  ): IO[Stream[IO, Boolean]] =
    IO.deferred[Stream[IO, Boolean]].flatMap { slot =>
      g.handleStream(request, Some("kitchen"))(s =>
        slot.complete(s).as(Response[IO]())
      ) *> slot.get
    }

  /** `Server.untilRevoked` halts on either side, so an ending stream cuts like
    * a `false`: the timeout is the pass, a returned `Nil` a failure.
    */
  private def assertNeverRevoked(revocation: Stream[IO, Boolean]): IO[Unit] =
    revocation.take(1).compile.toList.timeout(200.millis).attempt.map {
      outcome =>
        assert(
          outcome.isLeft,
          s"the stream was revoked or ended instead of staying silent: $outcome"
        )
    }

  /** A stream's admission is re-asked of whatever admitted it. An ingress
    * request is in no session, so watching the store answered false on its
    * first element: `_reload`, and back to be told the same, an endless reload
    * loop behind ingress.
    */
  test("an ingress stream is never revoked — it is in no session to lose") {
    gate(Some(Ingress.SupervisorIp)).flatMap { g =>
      revocationFor(g, req(Ingress.SupervisorIp, Some("u1")))
        .flatMap(assertNeverRevoked)
    }
  }

  test("a bearer stream is never revoked either") {
    gateWithSessions(
      Some(Ingress.SupervisorIp),
      _ => IO.pure(HaUser("u1", "Peri", true, true))
    ).flatMap { case (g, _) =>
      revocationFor(
        g,
        req(ipv4"192.168.1.50", None).putHeaders(
          Authorization(Credentials.Token(AuthScheme.Bearer, "long-lived"))
        )
      ).flatMap(assertNeverRevoked)
    }
  }

  /** The half the fix must not lose: a cookie session is revocable. */
  test("a cookie stream still stops when its session ends") {
    gateWithSessions(Some(Ingress.SupervisorIp)).flatMap { case (g, sessions) =>
      for {
        id <- sessions.create(
          HaUser("u2", "Heidi", false, false),
          "refresh",
          uri"http://fh.test"
        )
        revocation <- revocationFor(
          g,
          req(ipv4"192.168.1.50", None)
            .addCookie(AuthSessions.CookieName, id)
        )
        watching <- revocation.find(!_).compile.lastOrError.start
        _ <- sessions.remove(id)
        revoked <- watching.joinWithNever.timeout(5.seconds)
      } yield assertEquals(revoked, false)
    }
  }
}
