package fh.view.runtime

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.effect.Ref
import fh.view.auth.{AuthRoutes, AuthSessions, HaOAuth, SessionStore}
import fh.view.testkit.TestAuth
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri, UrlForm}

import scala.concurrent.duration.*

/** The thing that makes Home Assistant AUTHORITATIVE rather than merely the
  * thing that once issued a login (issue #89, ADR 0023).
  *
  * Without this, revoking fh in HA → Profile → Security leaves its sessions
  * alive here until they age out on their own. It is also the worst shape to
  * leave untested: background work with no visible failure mode, where "it
  * silently stopped on the first tick" and "everything is fine" look identical
  * from outside.
  *
  * Driven through the REAL `HaOAuth` over a stub HTTP backend rather than a
  * hand-written double, so the `/auth/token` contract it depends on — a non-200
  * meaning the grant is gone — is exercised rather than assumed. The stub also
  * applies HA's own rule that a token request whose `client_id` differs from
  * the grant's is `invalid_request`: that field is exactly what once broke
  * production, and a stub that never read it could not notice.
  *
  * Almost everything here drives ONE sweep (`revalidateOnce`) rather than the
  * schedule, so no assertion waits on a clock. The schedule gets exactly one
  * test, for the only thing it decides: when the first sweep happens.
  */
class RevalidateSessionsSuite extends munit.CatsEffectSuite {

  private val user = TestAuth.admin

  /** What every session in this suite was minted as, and what the stub accepts.
    */
  private val MintedClient = "http://fh.test"

  /** HA's token endpoint, as far as this suite is concerned. */
  private def haStub(
      reply: IO[Response[IO]],
      expectedClientId: String = MintedClient
  ): HaOAuth =
    new HaOAuth(
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

  /** One session, one sweep. `after = -1.second` puts the cutoff a second AHEAD
    * of now, so a session minted this instant is already due — an `after = 0`
    * cutoff races the session's own `verifiedAt` and skips the check whenever
    * both land in the same tick.
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

  /** A refresh naming a `client_id` HA never stored is HA ANSWERING
    * `invalid_request` — and per the strict rule, an answer that is not a fresh
    * token ends the session. Signing out on our own bug beats keeping a session
    * nobody can vouch for.
    */
  test("a refresh under a client_id HA does not know signs the session out") {
    sweep(
      haStub(revoked, expectedClientId = "http://someone-else.test")
    ).flatMap { case (sessions, id) =>
      sessions.get(id).map(assertEquals(_, None))
    }
  }

  /** The failure that must NOT happen. An unreachable HA is not a statement
    * about anybody's account, and treating it as one would sign the whole
    * household out of a working dashboard on every network hiccup.
    */
  test("an unreachable HA leaves the session alone") {
    val dead = new HaOAuth(
      uri"http://ha.test",
      Client.fromHttpApp(
        HttpApp[IO](_ => IO.raiseError(new java.net.ConnectException("nope")))
      )
    )
    sweep(dead).flatMap { case (sessions, id) =>
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

  /** Re-reading the USER is the point of re-checking, not just the token: a
    * role change is exactly what a periodic check is here to notice, and an
    * admin demoted in HA has to stop being an admin here.
    */
  test("a demoted admin is demoted here too") {
    val demoted = user.copy(is_admin = false, is_owner = false)
    sweep(haStub(renewed), identify = _ => IO.pure(demoted)).flatMap {
      case (sessions, id) =>
        sessions
          .get(id)
          .map(s => assertEquals(s.map(_.user.is_admin), Some(false)))
    }
  }

  /** A session that has not gone stale yet is not re-checked — otherwise every
    * sweep would be a round trip per logged-in person.
    */
  test("a fresh session is left untouched") {
    sweep(haStub(revoked), after = 1.hour).flatMap { case (sessions, id) =>
      sessions.get(id).map(s => assertEquals(s.map(_.refresh), Some("r1")))
    }
  }

  /** THE property, end to end: whatever client_id a login exchanged its code
    * under, the same string comes back on the periodic refresh. Asserting on
    * recorded wire values rather than internals keeps it true if baseUriOf
    * changes or an ingress base joins.
    */
  test("a session refreshes under the same client_id its login used") {
    for {
      seen <- Ref.of[IO, List[(String, Option[String])]](Nil)
      // One fake HA for login AND sweep; it records every (grant, client_id).
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
      oauth = new HaOAuth(uri"http://ha.test", ha)
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
        Request(
          Method.GET,
          uri"/auth/callback"
            .withQueryParam("code", "one-time")
            .withQueryParam("state", st)
        )
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

  /** What covers a RESTART. `verifiedAt` is persisted and absolute, so a
    * session that survived downtime is already stale on boot — but `awakeEvery`
    * sleeps before its first element, so a schedule without a leading sweep
    * would serve a revoked session for a whole interval.
    *
    * `every = 1.hour` is the assertion: nothing here can pass by waiting.
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
