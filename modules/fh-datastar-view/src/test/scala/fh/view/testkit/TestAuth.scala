package fh.view.testkit

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.auth.{AuthGate, AuthSessions, SessionStore}
import fh.view.model.{Access, Permission}
import org.http4s.implicits.*

/** The auth fixture every harness request rides on (issue #89). The gate is on
  * and the default request carries a real minted session: a gate off in every
  * test is a gate nothing tests, and a bypass hides auth that refuses every
  * real browser, or admits one. Auth tests override per call: `page(as =
  * None)`, or [[sessionFor]].
  */
final class TestAuth(
    val sessions: AuthSessions,
    val defaultSession: String
) {

  /** Pass the result as `as` on `page` / `post`. */
  def sessionFor(user: HaUser): IO[String] =
    sessions.create(user, s"test-refresh-${user.id}", TestAuth.TestClientId)

  /** As a logout or HA-side revocation does. The gate's `interruptWhen` watches
    * the same map, so an SSE stream opened with it stops.
    */
  def revokeDefault: IO[Unit] = sessions.remove(defaultSession)
}

object TestAuth {

  /** No HA exists here to disagree, but a session needs one: the sweep would
    * send it back.
    */
  val TestClientId: org.http4s.Uri = uri"http://fh.test"

  /** An admin, so admin-only routes need no setup. */
  val admin: HaUser =
    HaUser(
      id = "test-admin",
      name = "Test Admin",
      is_admin = true,
      is_owner = true
    )

  val guest: HaUser =
    HaUser(
      id = "test-guest",
      name = "Test Guest",
      is_admin = false,
      is_owner = false
    )

  /** Admits everyone, for suites not about auth. Not a bypass: routes still
    * declare their requirements, only every dashboard reads as `Public`.
    *
    * A `def`, so each server gets its own `AuthSessions`: every live stream
    * subscribes to it ([[fh.view.runtime.Server.untilRevoked]]), and one
    * `SignallingRef` shared across parallel suites is the wrong default.
    */
  def openGate: AuthGate =
    new AuthGate(
      AuthSessions.create(SessionStore.ephemeral).unsafeRunSync(),
      _ => IO.raiseError(new Exception("no HA in the harness")),
      _ => IO.pure(Permission(Access.Public, _ => true))
    ) {
      // These suites cannot attach a cookie.
      override def of(req: org.http4s.Request[IO]): IO[Option[HaUser]] =
        IO.pure(Some(admin))
    }

  /** The admin signed in on the server's own sessions, which its gate reads. */
  def admitted(sessions: AuthSessions): IO[TestAuth] =
    sessions
      .create(admin, "test-refresh-token", TestClientId)
      .map(new TestAuth(sessions, _))
}
