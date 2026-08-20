package fh.view.testkit

import fh.view.testkit.TestAuth

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.auth.{AuthGate, AuthSessions, SessionStore}
import fh.view.model.Access

/** The auth fixture every harness request rides on (issue #89).
  *
  * The gate is ON in the harness, and the default request carries a real minted
  * session rather than a bypass flag. That is the whole point: a gate switched
  * off in every test is a gate nothing tests, and the failure mode — auth that
  * passes its suite and refuses every real browser, or worse, admits one — is
  * exactly what a bypass hides.
  *
  * Tests that are ABOUT auth override per call: `page(as = None)` for the
  * un-authenticated redirect, [[sessionFor]] for somebody who is not an admin.
  */
final class TestAuth(
    val sessions: AuthSessions,
    val gate: AuthGate,
    val defaultSession: String
) {

  /** A session for somebody other than the harness admin — the guest, or a user
    * an `Access.Users` rule names. Pass the result as `as` on `page` / `post`.
    */
  def sessionFor(user: HaUser): IO[String] =
    sessions.create(user, s"test-refresh-${user.id}")

  /** End the default session, the way a logout or an HA-side revocation does.
    * The gate's `interruptWhen` watches the same map, so an SSE stream opened
    * with it stops.
    */
  def revokeDefault: IO[Unit] = sessions.remove(defaultSession)
}

object TestAuth {

  /** The user every harness request is, unless a test says otherwise. An admin,
    * so the suites that exercise admin-only routes need no special setup.
    */
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

  /** A gate that admits everyone, for the suites that are not about auth.
    *
    * NOT a bypass: the routes still declare their requirements and still stamp
    * their responses, so a route that forgot one still fails these suites. What
    * is relaxed is only WHO — every dashboard reads as `Public`, so a harness
    * request needs no cookie. The suites that are about auth use [[create]],
    * which mints real sessions and reads the dashboard's real rule.
    */
  val openGate: AuthGate =
    new AuthGate(
      AuthSessions.create(SessionStore.ephemeral).unsafeRunSync(),
      _ => IO.raiseError(new Exception("no HA in the harness")),
      _ => IO.pure(Access.Public)
    ) {
      // Everyone is the harness admin, so the admin routes answer without a
      // cookie these suites have no way to attach.
      override def of(req: org.http4s.Request[IO]): IO[Option[HaUser]] =
        IO.pure(Some(admin))
    }

  /** Built from the site's OWN rule lookup, like production: the gate has to
    * exist before the server that routes with it. Takes `accessFor` rather than
    * the site because `Server.LiveSite` is `private[runtime]` and this fixture
    * is not.
    */
  def create(accessFor: Option[String] => IO[Access]): IO[TestAuth] =
    for {
      sessions <- AuthSessions.create(SessionStore.ephemeral)
      id <- sessions.create(admin, "test-refresh-token")
      gate = new AuthGate(
        sessions,
        // No HA to resolve a bearer token against. Raising (rather than
        // returning None) keeps the harness honest: `bearerUser` is supposed to
        // treat an unresolvable token as "not an identity", and this proves it
        // does rather than assuming it.
        _ => IO.raiseError(new Exception("no HA in the harness")),
        accessFor
      )
    } yield new TestAuth(sessions, gate, id)
}
