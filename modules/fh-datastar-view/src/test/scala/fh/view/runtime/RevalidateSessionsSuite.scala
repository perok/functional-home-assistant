package fh.view.runtime

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.effect.Ref
import fh.view.FHError
import fh.view.auth.{AuthRoutes, AuthSessions, HaOAuth, SessionStore}
import fh.view.testkit.TestAuth
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri, UrlForm}

import scala.concurrent.duration.*

/** What makes HA authoritative rather than the thing that once issued a login
  * (issue #89, ADR 0023): revoking fh in HA ends its sessions here. Background
  * work with no visible failure, where "stopped on the first tick" and "fine"
  * look identical.
  *
  * Through the real `HaOAuth` over a stub backend, so the `/auth/token`
  * contract (a 4xx means the grant is gone) is exercised. The stub applies HA's
  * rule that a `client_id` differing from the grant's is `invalid_request`: the
  * field that once broke production. Tests drive one sweep, not the schedule,
  * so nothing waits on a clock.
  */
class RevalidateSessionsSuite extends munit.CatsEffectSuite {

  private val user = TestAuth.admin

  private val MintedClient = "http://fh.test"

  /** Authorize and token bases coincide here; production splits them
    * ([[HaOAuth]]), tested below.
    */
  private def haStub(
      reply: IO[Response[IO]],
      expectedClientId: String = MintedClient
  ): HaOAuth =
    new HaOAuth(
      uri"http://ha.test",
      uri"http://ha.test",
      Client.fromHttpApp(HttpApp[IO] { req =>
        if !req.uri.path.renderString.endsWith("/auth/token") then NotFound()
        else
          req.as[UrlForm].flatMap { form =>
            if form.getFirst("client_id") == Some(expectedClientId) then reply
            else
              IO.pure(
                Response[IO](Status.BadRequest)
                  .withEntity("""{"error":"invalid_request"}""")
              )
          }
      })
    )

  private val renewed = Ok(
    """{"access_token":"fresh","refresh_token":"r2","expires_in":1800}"""
  )
  private val revoked =
    IO.pure(
      Response[IO](Status.BadRequest).withEntity(
        """{"error":"invalid_grant"}"""
      )
    )

  /** `after = -1.second` makes a session minted this instant due; `0` races its
    * `verifiedAt` in the same tick.
    */
  private def sweep(
      oauth: HaOAuth,
      identify: String => IO[HaUser] = _ => IO.pure(user),
      after: FiniteDuration = (-1).seconds,
      clientId: String = MintedClient
  ): IO[(AuthSessions, String)] =
    for {
      sessions <- AuthSessions.create(SessionStore.ephemeral)
      id <-
        sessions.create(user, "r1", Uri.unsafeFromString(clientId))
      _ <- ServerApp.revalidateOnce(
        sessions,
        oauth,
        identify,
        after = after
      )
    } yield (sessions, id)

  test("HA saying the grant is gone signs the session out") {
    sweep(haStub(revoked)).flatMap { case (sessions, id) =>
      sessions.get(id).map(assertEquals(_, None))
    }
  }

  /** HA answering `invalid_request` is not a fresh token, so the session ends:
    * signing out on our own bug beats keeping one nobody vouches for.
    */
  test("a refresh under a client_id HA does not know signs the session out") {
    sweep(
      haStub(revoked, expectedClientId = "http://someone-else.test")
    ).flatMap { case (sessions, id) =>
      sessions.get(id).map(assertEquals(_, None))
    }
  }

  /** An unreachable HA says nothing about any account; otherwise every hiccup
    * signs the household out.
    */
  test("an unreachable HA leaves the session alone") {
    val dead = new HaOAuth(
      uri"http://ha.test",
      uri"http://ha.test",
      Client.fromHttpApp(
        HttpApp[IO](_ => IO.raiseError(new java.net.ConnectException("nope")))
      )
    )
    sweep(dead).flatMap { case (sessions, id) =>
      sessions.get(id).map(s => assertEquals(s.map(_.user), Some(user)))
    }
  }

  /** A 5xx is HA failing to answer, not the grant gone; otherwise every HA
    * update signs the household out.
    */
  test("HA answering 5xx leaves the session alone") {
    val restarting =
      IO.pure(Response[IO](Status.ServiceUnavailable).withEntity("starting"))
    sweep(haStub(restarting)).flatMap { case (sessions, id) =>
      sessions.get(id).map(s => assertEquals(s.map(_.user), Some(user)))
    }
  }

  test("a renewed session keeps its place and takes the fresh token") {
    sweep(haStub(renewed)).flatMap { case (sessions, id) =>
      sessions.get(id).map { s =>
        assertEquals(s.map(_.user), Some(user))
        assertEquals(s.map(_.refresh), Some("r2"))
      }
    }
  }

  /** A role change is what a periodic check is for. */
  test("a demoted admin is demoted here too") {
    val demoted = user.copy(is_admin = false, is_owner = false)
    sweep(haStub(renewed), identify = _ => IO.pure(demoted)).flatMap {
      case (sessions, id) =>
        sessions
          .get(id)
          .map(s => assertEquals(s.map(_.user.is_admin), Some(false)))
    }
  }

  /** Otherwise every sweep is a round trip per person. */
  test("a fresh session is left untouched") {
    sweep(haStub(revoked), after = 1.hour).flatMap { case (sessions, id) =>
      sessions.get(id).map(s => assertEquals(s.map(_.refresh), Some("r1")))
    }
  }

  /** Asserted on recorded wire values, so it holds if `baseUriOf` changes or an
    * ingress base joins.
    */
  test("a session refreshes under the same client_id its login used") {
    for {
      seen <- Ref.of[IO, List[(String, Option[String])]](Nil)
      ha = Client.fromHttpApp(HttpApp[IO] { req =>
        req.as[UrlForm].flatMap { form =>
          val entry = (
            form.getFirst("grant_type").getOrElse(""),
            form.getFirst("client_id")
          )
          seen.update(_ :+ entry) *> Ok(
            """{"access_token":"at","refresh_token":"r2","expires_in":1800}"""
          )
        }
      })
      oauth = new HaOAuth(uri"http://ha.test", uri"http://ha.test", ha)
      sessions <- AuthSessions.create(SessionStore.ephemeral)
      routes <- AuthRoutes.create(
        oauth,
        sessions,
        _ => IO.pure(user),
        _ => Uri.unsafeFromString(MintedClient)
      )
      login <- routes.routes.orNotFound
        .run(Request(Method.GET, uri"/auth/login"))
      st <- IO.fromOption(
        login.headers.get[Location].map(_.uri.query.params("state"))
      )(new IllegalStateException("login carried no state"))
      callback <- routes.routes.orNotFound.run(
        login.cookies.foldLeft(
          Request[IO](
            Method.GET,
            uri"/auth/callback"
              .withQueryParam("code", "one-time")
              .withQueryParam("state", st)
          )
        )((r, c) => r.addCookie(c.name, c.content))
      )
      id = callback.cookies.collectFirst {
        case c if c.name == AuthSessions.CookieName => c.content
      }
      sid <- IO.fromOption(id)(
        new IllegalStateException("login set no session cookie")
      )
      _ <- ServerApp.revalidateOnce(
        sessions,
        oauth,
        _ => IO.pure(user),
        after = (-1).seconds
      )
      grants <- seen.get
      kept <- sessions.get(sid)
    } yield {
      val exchanged = grants.collectFirst { case ("authorization_code", cid) =>
        cid
      }
      val refreshed = grants.collectFirst { case ("refresh_token", cid) =>
        cid
      }
      assertEquals(exchanged, Some(Some(MintedClient)))
      assertEquals(refreshed, exchanged)
      assertEquals(kept.map(_.clientId), Some(MintedClient))
    }
  }

  /** Every production login died with a bare 500: the browser followed the
    * authorize link to HA's mDNS name, and the server dialled that name for the
    * exchange, which it cannot resolve.
    */
  test("the authorize link is built for the browser; tokens are dialled") {
    for {
      seen <- Ref.of[IO, List[Uri]](Nil)
      ha = Client.fromHttpApp(HttpApp[IO] { req =>
        seen.update(_ :+ req.uri) *> Ok(
          """{"access_token":"at","refresh_token":"r2","expires_in":1800}"""
        )
      })
      oauth = new HaOAuth(uri"http://login.test", uri"http://tokens.test", ha)
      link = oauth.authorizeUri(
        Uri.unsafeFromString(MintedClient),
        Uri.unsafeFromString(s"$MintedClient/auth/callback"),
        "s"
      )
      _ <- oauth.exchange("one-time", Uri.unsafeFromString(MintedClient))
      dialled <- seen.get
    } yield {
      assertEquals(link.host.map(_.renderString), Some("login.test"))
      assertEquals(
        dialled.map(_.host.map(_.renderString)).distinct,
        List(Some("tokens.test"))
      )
    }
  }

  /** The production 500: an unreachable token endpoint escaped past
    * `FHError.handle` as a raw exception.
    */
  test("an unreachable token endpoint raises unavailable, not a bare error") {
    val dead = new HaOAuth(
      uri"http://ha.test",
      uri"http://ha.test",
      Client.fromHttpApp(
        HttpApp[IO](_ => IO.raiseError(new java.net.ConnectException("nope")))
      )
    )
    interceptIO[FHError](
      dead.exchange("code", Uri.unsafeFromString(MintedClient))
    )
      .flatMap(e => IO(assertEquals(e.status, 503)))
  }

  /** `verifiedAt` is persisted, so a session that survived downtime is stale on
    * boot, and `awakeEvery` sleeps before its first element. `every = 1.hour`
    * is the assertion: nothing can pass by waiting.
    */
  test("the first sweep does not wait for the interval") {
    for {
      sessions <- AuthSessions.create(SessionStore.ephemeral)
      id <-
        sessions.create(user, "r1", Uri.unsafeFromString(MintedClient))
      _ <- ServerApp
        .revalidateSessions(
          sessions,
          haStub(revoked),
          _ => IO.pure(user),
          every = 1.hour,
          after = 0.seconds
        )
        .head
        .compile
        .drain
        .timeout(5.seconds)
      gone <- sessions.get(id)
    } yield assertEquals(gone, None)
  }
}
