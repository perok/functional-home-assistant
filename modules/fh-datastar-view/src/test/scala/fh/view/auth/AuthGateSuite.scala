package fh.view.auth

import cats.effect.IO
import fh.view.model.{Access, Permission}
import fh.view.testkit.TestAuth
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{Method, Request, Response, Status, Uri}

/** The gate's own pieces: holding a whole route GROUP to one rule, and the two
  * open-redirect guards around `next`.
  *
  * What each route requires is declared at the route and checked end to end in
  * `AuthGateBehaviourSuite`; the login flow's URL choice lives in
  * `HaOAuthSuite`.
  */
class AuthGateSuite extends munit.CatsEffectSuite {

  private def gateFor(access: Access) =
    AuthSessions
      .create(SessionStore.ephemeral)
      .map(sessions =>
        new AuthGate(
          sessions,
          _ => IO.raiseError(new Exception("no HA here")),
          _ => IO.pure(Permission(access, _ => true))
        )
      )

  private def get(path: String) =
    Request[IO](Method.GET, Uri.unsafeFromString(path))

  private val group: PartialFunction[Request[IO], IO[Response[IO]]] = {
    case GET -> Root / "edit" / "files" => Ok("the file list")
  }

  test("a group's rule covers every route in it, without each saying so") {
    gateFor(Access.Public).flatMap { gate =>
      AuthGate
        .require(gate, Requirement.Admin)(group)
        .orNotFound
        .run(get("/edit/files"))
        // Anonymous, and the group is admin — the route never asked for this.
        .map(r => assertEquals(r.status, Status.Unauthorized))
    }
  }

  /** The reason `require` is built from the route table's own `PartialFunction`
    * rather than from a finished `HttpRoutes`: a refused request must not reach
    * the handler at all. `PUT /edit/file` writes to disk, so "run it, then
    * decide" would save the file and then say no.
    */
  test("a refused request never runs the handler") {
    IO.ref(0).flatMap { ran =>
      val counting: PartialFunction[Request[IO], IO[Response[IO]]] = {
        case GET -> Root / "edit" / "files" => ran.update(_ + 1) *> Ok("x")
      }
      gateFor(Access.Public).flatMap { gate =>
        AuthGate
          .require(gate, Requirement.Admin)(counting)
          .orNotFound
          .run(get("/edit/files")) *> ran.get.map(assertEquals(_, 0))
      }
    }
  }

  test("a path the group does not own falls through to the next routes") {
    gateFor(Access.Public).flatMap { gate =>
      AuthGate
        .require(gate, Requirement.Admin)(group)
        .run(get("/d/kitchen"))
        .value
        .map(assertEquals(_, None))
    }
  }

  test("a login redirect carries the whole request back, query included") {
    val to = AuthGate.loginRedirect(uri"/d/kitchen?tab=lights")
    assertEquals(to.path.renderString, "/auth/login")
    assertEquals(to.query.params.get("next"), Some("/d/kitchen?tab=lights"))
  }

  test("next only ever comes back as a local path") {
    assertEquals(AuthGate.safeNext(Some("/d/kitchen")), "/d/kitchen")
    // The shapes that would forward a visitor off this site, with the login
    // flow lending the link credibility.
    assertEquals(AuthGate.safeNext(Some("//evil.example")), "/")
    assertEquals(AuthGate.safeNext(Some("https://evil.example")), "/")
    assertEquals(AuthGate.safeNext(Some("javascript:alert(1)")), "/")
    assertEquals(AuthGate.safeNext(Some("d/kitchen")), "/")
    assertEquals(AuthGate.safeNext(None), "/")
  }

  test("the harness gate admits everyone, which is what makes it a fixture") {
    TestAuth.openGate
      .of(get("/"))
      .map(u => assertEquals(u.map(_.is_admin), Some(true)))
  }
}
