package fh.view.runtime

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import fh.view.auth.{AuthSessions, HaOAuth, SessionStore}
import fh.view.testkit.TestAuth
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{HttpApp, Response, Status}

import scala.concurrent.duration.*

/** The thing that makes Home Assistant AUTHORITATIVE rather than merely the
  * thing that once issued a login (issue #89, ADR 0023).
  *
  * Without this fiber, revoking fh in HA → Profile → Security leaves its
  * sessions alive here until they age out on their own. It is also the worst
  * shape to leave untested: background work with no visible failure mode, where
  * "it silently stopped on the first tick" and "everything is fine" look
  * identical from outside.
  *
  * Driven through the REAL `HaOAuth` over a stub HTTP backend rather than a
  * hand-written double, so the `/auth/token` contract it depends on — a `400`
  * meaning the grant is gone — is exercised rather than assumed.
  */
class RevalidateSessionsSuite extends munit.CatsEffectSuite {

  private val user = TestAuth.admin

  /** HA's token endpoint, as far as this suite is concerned. */
  private def haStub(reply: IO[Response[IO]]): HaOAuth =
    new HaOAuth(
      uri"http://ha.test",
      Client.fromHttpApp(HttpApp[IO] { req =>
        if (req.uri.path.renderString.endsWith("/auth/token")) reply
        else NotFound()
      })
    )

  private val renewed = Ok(
    """{"access_token":"fresh","refresh_token":"r2","expires_in":1800}"""
  )
  private val revoked =
    IO.pure(Response[IO](Status.BadRequest).withEntity("""{"error":"invalid_grant"}"""))

  private def run(
      oauth: HaOAuth,
      identify: String => IO[HaUser] = _ => IO.pure(user)
  ): IO[(AuthSessions, String)] =
    for {
      sessions <- AuthSessions.create(SessionStore.ephemeral)
      id <- sessions.create(user, "r1")
      // `after = 0` makes every session due now, so the test does not have to
      // wait out a 30-minute staleness window to observe one tick.
      _ <- ServerApp
        .revalidateSessions(
          sessions,
          oauth,
          identify,
          uri"http://fh.test",
          every = 10.millis,
          after = 0.seconds
        )
        .take(1)
        .compile
        .drain
        .timeout(5.seconds)
    } yield (sessions, id)

  test("HA saying the grant is gone signs the session out") {
    run(haStub(revoked)).flatMap { case (sessions, id) =>
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
    run(dead).flatMap { case (sessions, id) =>
      sessions.get(id).map(s => assertEquals(s.map(_.user), Some(user)))
    }
  }

  test("a renewed session keeps its place and takes the fresh token") {
    run(haStub(renewed)).flatMap { case (sessions, id) =>
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
    run(haStub(renewed), identify = _ => IO.pure(demoted)).flatMap {
      case (sessions, id) =>
        sessions.get(id).map(s => assertEquals(s.map(_.user.is_admin), Some(false)))
    }
  }

  /** A session that has not gone stale yet is not re-checked — otherwise every
    * tick would be a round trip per logged-in person.
    */
  test("a fresh session is left untouched") {
    for {
      sessions <- AuthSessions.create(SessionStore.ephemeral)
      id <- sessions.create(user, "r1")
      _ <- ServerApp
        .revalidateSessions(
          sessions,
          haStub(revoked),
          _ => IO.pure(user),
          uri"http://fh.test",
          every = 10.millis,
          after = 1.hour
        )
        .interruptAfter(300.millis)
        .compile
        .drain
      still <- sessions.get(id)
    } yield assertEquals(still.map(_.refresh), Some("r1"))
  }
}
