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
          users("u1")
            .flatMap(first => users("u1").map(second => (first, second)))
        )
        .map { case (first, second) =>
          assertEquals(first, None)
          assertEquals(second.map(_.name), Some("Peri"))
        }
    }
  }

  /** The revocation stream `AuthGate.handleStream` hands a live SSE route:
    * "does the rule still hold". Held onto rather than consumed inside the
    * handler, which returns a throwaway response.
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

  /** A stream that neither speaks nor ENDS. `Server.untilRevoked` halts on
    * either side, so an empty stream cuts the connection exactly like a `false`
    * does — hence the timeout is the pass condition and a returned `Nil` is a
    * failure.
    */
  private def assertNeverRevoked(revocation: Stream[IO, Boolean]): IO[Unit] =
    revocation.take(1).compile.toList.timeout(200.millis).attempt.map {
      outcome =>
        assert(
          outcome.isLeft,
          s"the stream was revoked or ended instead of staying silent: $outcome"
        )
    }

  /** What a live stream's admission is re-asked of: whatever ADMITTED it.
    *
    * Only a cookie session can be withdrawn here, because the store being
    * watched is the one logging out empties. An ingress request is in no
    * session — HA re-authenticates it on every request — so watching the store
    * for one asks a map that will never hold it: the watch answers false on its
    * FIRST element, the stream says goodbye with `_reload`, and the page comes
    * back to be told the same thing. Behind the add-on's ingress that was an
    * endless reload loop on a dashboard the user could see perfectly well.
    */
  test("an ingress stream is never revoked — it is in no session to lose") {
    gate(Some(Ingress.SupervisorIp)).flatMap { g =>
      revocationFor(g, req(Ingress.SupervisorIp, Some("u1")))
        .flatMap(assertNeverRevoked)
    }
  }

  /** The same hole, on the other carrier that carries its own credential. */
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

  /** And the half that must NOT be lost to the fix: a cookie session IS
    * revocable, so ending it still cuts the stream that rode on it.
    */
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
