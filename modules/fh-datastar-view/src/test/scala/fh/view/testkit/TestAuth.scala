package fh.view.testkit

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import fh.view.auth.{AuthMiddleware, AuthSessions, SessionStore}
import fh.view.runtime.Server

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
    val gate: AuthMiddleware,
    val defaultSession: String
)

object TestAuth {

  /** The user every harness request is, unless a test says otherwise. An admin,
    * so the suites that exercise admin-only routes need no special setup.
    */
  val admin: HaUser =
    HaUser(id = "test-admin", name = "Test Admin", is_admin = true, is_owner = true)

  val guest: HaUser =
    HaUser(id = "test-guest", name = "Test Guest", is_admin = false, is_owner = false)

  def create(server: Server): IO[TestAuth] =
    for {
      sessions <- AuthSessions.create(SessionStore.ephemeral)
      id <- sessions.create(admin, "test-refresh-token")
      gate = new AuthMiddleware(
        sessions,
        // No HA to resolve a bearer token against. Raising (rather than
        // returning None) keeps the harness honest: `bearerUser` is supposed to
        // treat an unresolvable token as "not an identity", and this proves it
        // does rather than assuming it.
        _ => IO.raiseError(new Exception("no HA in the harness")),
        server.accessFor,
        server.slugForConn,
        Server.ConnSignal
      )
    } yield new TestAuth(sessions, gate, id)
}
