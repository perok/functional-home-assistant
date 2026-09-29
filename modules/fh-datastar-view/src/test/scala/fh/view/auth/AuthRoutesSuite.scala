package fh.view.auth

import cats.effect.{IO, Ref}
import fh.view.FHError
import fh.view.testkit.TestAuth
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.http4s.{
  HttpApp,
  Method,
  Request,
  Response,
  ResponseCookie,
  SameSite,
  Uri,
  UrlForm
}

import java.time.Instant

/** The login's two requests, through the real routes over a stub HA that counts
  * code exchanges: a callback is honoured only in the browser that started its
  * login (ADR 0023).
  */
class AuthRoutesSuite extends munit.CatsEffectSuite {

  private final case class Fixture(
      routes: HttpApp[IO],
      exchanges: Ref[IO, Int],
      revoked: Ref[IO, List[String]],
      sessions: AuthSessions
  )

  /** Each exchange mints refresh token `r<n>`, so a revocation names its
    * session.
    */
  private def fixture(base: String = "http://fh.test"): IO[Fixture] =
    for {
      exchanges <- Ref.of[IO, Int](0)
      revoked <- Ref.of[IO, List[String]](Nil)
      ha = Client.fromHttpApp(HttpApp[IO] { req =>
        req.as[UrlForm].flatMap { form =>
          if req.uri.path.renderString.endsWith("/auth/revoke") then
            revoked.update(_ :+ form.getFirst("token").getOrElse("")) *> Ok()
          else
            exchanges.updateAndGet(_ + 1).flatMap { n =>
              Ok(
                s"""{"access_token":"at","refresh_token":"r$n","expires_in":1800}"""
              )
            }
        }
      })
      sessions <- AuthSessions.create(SessionStore.ephemeral)
      routes <- AuthRoutes.create(
        new HaOAuth(uri"http://ha.test", uri"http://ha.test", ha),
        sessions,
        _ => IO.pure(TestAuth.admin),
        _ => Uri.unsafeFromString(base)
      )
    } yield Fixture(routes.routes.orNotFound, exchanges, revoked, sessions)

  /** A whole login from one browser, carrying the session it already holds. */
  private def signIn(f: Fixture, holding: Option[String] = None): IO[String] =
    for {
      (state, ticket) <- login(f)
      resp <- callback(
        f,
        state,
        (ticket.name -> ticket.content) +:
          holding.map(AuthSessions.CookieName -> _).toSeq*
      )
    } yield resp.cookies
      .collectFirst {
        case c if c.name == AuthSessions.CookieName => c.content
      }
      .getOrElse(fail("login set no session cookie"))

  private def refreshOf(f: Fixture, id: String): IO[Option[String]] =
    f.sessions.get(id).map(_.map(_.refresh))

  /** The `state` HA would round-trip, and the ticket the browser now holds. */
  private def login(
      f: Fixture,
      next: String = "/d/kitchen"
  ): IO[(String, ResponseCookie)] =
    f.routes
      .run(Request(Method.GET, uri"/auth/login".withQueryParam("next", next)))
      .map { resp =>
        val state = resp.headers
          .get[Location]
          .flatMap(_.uri.query.params.get("state"))
          .getOrElse(fail("login carried no state"))
        val ticket = resp.cookies
          .find(_.name == LoginTickets.nameOf(state))
          .getOrElse(fail("login set no ticket"))
        (state, ticket)
      }

  private def callback(
      f: Fixture,
      state: String,
      cookies: (String, String)*
  ): IO[Response[IO]] =
    f.routes.run(
      cookies.foldLeft(
        Request[IO](
          Method.GET,
          uri"/auth/callback"
            .withQueryParam("code", "one-time")
            .withQueryParam("state", state)
        )
      ) { case (r, (name, content)) => r.addCookie(name, content) }
    )

  private def assertRefused(resp: IO[Response[IO]], f: Fixture): IO[Unit] =
    for {
      outcome <- resp.attempt
      exchanged <- f.exchanges.get
    } yield {
      outcome match {
        case Left(FHError(400, _)) => ()
        case other => fail(s"expected a 400 refusal, got $other")
      }
      assertEquals(exchanged, 0, clue = "refused only after spending the code")
    }

  test(
    "a login completes in the browser that started it, and clears its ticket"
  ) {
    for {
      f <- fixture()
      (state, ticket) <- login(f)
      resp <- callback(f, state, ticket.name -> ticket.content)
    } yield {
      assertEquals(
        resp.headers.get[Location].map(_.uri.renderString),
        Some("/d/kitchen")
      )
      assert(resp.cookies.exists(_.name == AuthSessions.CookieName))
      val cleared = resp.cookies.find(_.name == ticket.name)
      assertEquals(cleared.flatMap(_.maxAge), Some(0L))
      assertEquals(cleared.flatMap(_.path), ticket.path)
    }
  }

  /** The login CSRF: someone who completed a login as themselves hands over the
    * callback link.
    */
  test("a callback without its login's cookie is refused") {
    for {
      f <- fixture()
      (state, _) <- login(f)
      _ <- assertRefused(callback(f, state), f)
    } yield ()
  }

  test("another login's ticket, renamed onto this state, is refused") {
    for {
      f <- fixture()
      (state, _) <- login(f)
      (_, other) <- login(f)
      _ <- assertRefused(
        callback(f, state, LoginTickets.nameOf(state) -> other.content),
        f
      )
    } yield ()
  }

  test("a ticket whose next was rewritten is refused") {
    for {
      f <- fixture()
      (state, ticket) <- login(f)
      forged = ticket.content.split('.') match {
        case Array(issued, _, mac) =>
          val evil = java.util.Base64.getUrlEncoder.withoutPadding
            .encodeToString("//evil.example".getBytes)
          s"$issued.$evil.$mac"
        case _ => fail(s"unexpected ticket shape: ${ticket.content}")
      }
      _ <- assertRefused(callback(f, state, ticket.name -> forged), f)
    } yield ()
  }

  test("two logins at once both complete") {
    for {
      f <- fixture()
      (a, ta) <- login(f, "/d/a")
      (b, tb) <- login(f, "/d/b")
      both = Seq(ta.name -> ta.content, tb.name -> tb.content)
      ra <- callback(f, a, both*)
      rb <- callback(f, b, both*)
    } yield {
      assertEquals(
        ra.headers.get[Location].map(_.uri.renderString),
        Some("/d/a")
      )
      assertEquals(
        rb.headers.get[Location].map(_.uri.renderString),
        Some("/d/b")
      )
    }
  }

  test("a ticket lasts ten minutes from its login") {
    val issued = Instant.parse("2026-01-01T00:00:00Z")
    for {
      tickets <- LoginTickets.create
      minted <- tickets.issue("/d/x", issued, Uri.Path.Root, secure = false)
      (state, ticket) = minted
      req = Request[IO]().addCookie(ticket.name, ticket.content)
      at = (s: Long) => tickets.redeem(req, state, issued.plusSeconds(s))
    } yield {
      assertEquals(at(LoginTickets.TtlSeconds - 1), Some("/d/x"))
      assertEquals(at(LoginTickets.TtlSeconds), None)
    }
  }

  test("the longest next still makes a cookie a browser keeps") {
    for {
      f <- fixture("https://fh.test")
      (state, ticket) <- login(f, "/d/" + "a" * 2045)
      resp <- callback(f, state, ticket.name -> ticket.content)
    } yield {
      assert(
        ticket.renderString.length < 4096,
        clue = ticket.renderString.length
      )
      assert(
        resp.headers.get[Location].exists(_.uri.renderString.length == 2048)
      )
    }
  }

  /** A private window or another device is its own login. */
  test("logging out ends only this browser's session") {
    for {
      f <- fixture()
      phone <- signIn(f)
      tablet <- signIn(f)
      phoneToken <- refreshOf(f, phone)
      _ <- f.routes.run(
        Request[IO](Method.POST, uri"/auth/logout")
          .addCookie(AuthSessions.CookieName, phone)
      )
      phoneAfter <- refreshOf(f, phone)
      tabletAfter <- refreshOf(f, tablet)
      revoked <- f.revoked.get
    } yield {
      assertEquals(phoneAfter, None)
      assert(tabletAfter.isDefined, clue = "another device was logged out")
      assertEquals(revoked, phoneToken.toList)
    }
  }

  /** Otherwise the old one lives on unreachable, refreshed by every sweep. */
  test("logging in again from the same browser replaces its session") {
    for {
      f <- fixture()
      first <- signIn(f)
      other <- signIn(f)
      firstToken <- refreshOf(f, first)
      second <- signIn(f, holding = Some(first))
      firstAfter <- refreshOf(f, first)
      secondAfter <- refreshOf(f, second)
      otherAfter <- refreshOf(f, other)
      revoked <- f.revoked.get
    } yield {
      assertEquals(firstAfter, None)
      assert(secondAfter.isDefined)
      assert(otherAfter.isDefined, clue = "another browser's session went")
      assertEquals(revoked, firstToken.toList)
    }
  }

  /** `Secure` over plain http would lose the cookie, and the login with it. */
  test("the ticket is Lax and HttpOnly, and Secure only over https") {
    for {
      plain <- fixture("http://fh.test").flatMap(login(_))
      tls <- fixture("https://fh.test").flatMap(login(_))
    } yield {
      val (_, http) = plain
      val (_, https) = tls
      assertEquals(http.sameSite, Some(SameSite.Lax))
      assert(http.httpOnly)
      assert(!http.secure)
      assert(https.secure)
      assertEquals(http.path, Some("/auth/callback"))
    }
  }
}
