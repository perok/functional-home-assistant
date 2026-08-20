package fh.view.auth

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.model.Access
import org.http4s.implicits.*
import org.http4s.{HttpRoutes, Method, Request, Response, Status, Uri}
import org.http4s.dsl.io.*

/** The gate's own machinery: the backstop that makes a per-route rule safe, and
  * the two open-redirect guards around `next`.
  *
  * What each route REQUIRES is declared at the route and checked end to end in
  * `AuthGateBehaviourSuite`. What is checked here is the thing no route can
  * check about itself — that forgetting to declare anything fails closed.
  */
class AuthGateSuite extends munit.CatsEffectSuite {

  private def gateFor(access: Access) =
    for {
      sessions <- AuthSessions.create(SessionStore.ephemeral)
      stamp <- AuthGate.stampKey
    } yield (
      new AuthGate(
        sessions,
        _ => IO.raiseError(new Exception("no HA here")),
        _ => IO.pure(Some(access)),
        stamp
      ),
      stamp
    )

  private def get(path: String) =
    Request[IO](Method.GET, Uri.unsafeFromString(path))

  /** The property the whole per-route design rests on. A route that declares no
    * requirement is a route somebody wrote without thinking about auth, and the
    * only safe reading of it is "not served" — the alternative is serving it to
    * anyone, silently, which is exactly the failure a central classifier was
    * there to prevent.
    */
  test("a route that declares no requirement is refused, not served") {
    gateFor(Access.Public).flatMap { case (_, stamp) =>
      val ungated = HttpRoutes.of[IO] { case GET -> Root / "oops" =>
        Ok("secret")
      }
      AuthGate
        .assertGated(stamp)(ungated)
        .run(get("/oops"))
        .map(r => assertEquals(r.status, Status.InternalServerError))
    }
  }

  test("a declared route is served normally") {
    gateFor(Access.Public).flatMap { case (gate, stamp) =>
      val gated = HttpRoutes.of[IO] { case req @ GET -> Root / "fine" =>
        gate.handleRequirement(req, Requirement.Open)(Ok("hello"))
      }
      AuthGate
        .assertGated(stamp)(gated)
        .run(get("/fine"))
        .map(r => assertEquals(r.status, Status.Ok))
    }
  }

  /** No route MATCHED is an ordinary 404, not a gap — otherwise every typo'd
    * URL would report as a server bug.
    */
  test("an unmatched path is a 404, not a missing-requirement bug") {
    gateFor(Access.Public).flatMap { case (_, stamp) =>
      AuthGate
        .assertGated(stamp)(HttpRoutes.empty[IO])
        .run(get("/nothing/here"))
        .map(r => assertEquals(r.status, Status.NotFound))
    }
  }

  /** A `Key` is compared by identity, so a second one never matches — which is
    * what would happen if the gate and the backstop were built from separate
    * calls. Worth pinning: the symptom is every route 500ing at once, and the
    * cause looks nothing like the effect.
    */
  test("the backstop only accepts the stamp its own gate leaves") {
    for {
      (gate, _) <- gateFor(Access.Public)
      other <- AuthGate.stampKey
      routes = HttpRoutes.of[IO] { case req @ GET -> Root / "fine" =>
        gate.handleRequirement(req, Requirement.Open)(Ok("hello"))
      }
      resp <- AuthGate.assertGated(other)(routes).run(get("/fine"))
    } yield assertEquals(resp.status, Status.InternalServerError)
  }

  test("a denial is stamped too — a 401 must not read as a missing rule") {
    for {
      (gate, stamp) <- gateFor(Access.Authenticated)
      routes = HttpRoutes.of[IO] { case req @ GET -> Root / "d" / slug =>
        gate.handleRequirement(req, Requirement.data(Some(slug)))(Ok("hi"))
      }
      resp <- AuthGate.assertGated(stamp)(routes).run(get("/d/kitchen"))
    } yield assertEquals(resp.status, Status.Unauthorized)
  }

  test(
    "the login redirect goes where the BROWSER can reach HA, not where we dial it"
  ) {
    val dialed = uri"http://192.168.1.174:8123"
    val internal = uri"http://ha.lan:8123"
    val explicit = uri"https://ha.example"

    // An explicit setting outranks everything, including HA's own opinion.
    assertEquals(
      HaOAuth.browserBase(Some(explicit), Some(internal), dialed),
      explicit
    )
    assertEquals(HaOAuth.browserBase(None, Some(internal), dialed), internal)
    // `internal_url` is optional in HA and was null on the instance this was
    // built against, so the dialled address — which we have a live socket to —
    // has to be a real rung, not a formality.
    assertEquals(HaOAuth.browserBase(None, None, dialed), dialed)
  }

  /** The case that is broken today rather than merely suboptimal:
    * `home-addon/run.sh` dials `http://supervisor/core`, so under the add-on
    * the redirect currently points a browser at a container-internal host.
    */
  test("the supervisor address is never handed to a browser") {
    val supervisor = uri"http://supervisor/core"
    assertEquals(
      HaOAuth.browserBase(None, None, supervisor),
      HaOAuth.MdnsFallback
    )
    // ...but it is only the LAST resort: anything that actually knows wins.
    assertEquals(
      HaOAuth.browserBase(None, Some(uri"http://ha.lan:8123"), supervisor),
      uri"http://ha.lan:8123"
    )
  }

  test("get_config's internal_url is read, and its absence is just absence") {
    def internalUrlOf(raw: String) =
      HaOAuth.internalUrlOf(
        io.circe.parser.parse(raw).getOrElse(fail(s"bad fixture: $raw"))
      )

    assertEquals(
      internalUrlOf(
        """{"internal_url": "http://ha.lan:8123", "external_url": "https://x"}"""
      ),
      Some(uri"http://ha.lan:8123")
    )
    // All three mean the same thing — HA does not know — and none is an error.
    // `null` is the one the live instance actually returned.
    assertEquals(internalUrlOf("""{"internal_url": null}"""), None)
    assertEquals(internalUrlOf("""{}"""), None)
    assertEquals(internalUrlOf("""{"internal_url": "not a url"}"""), None)
  }

  test("a login redirect carries the whole request back, query included") {
    val to = AuthGate.loginRedirect(uri"/d/kitchen?tab=lights")
    assertEquals(to.path.renderString, "/auth/login")
    assertEquals(to.query.params.get("next"), Some("/d/kitchen?tab=lights"))
  }

  test("next only ever comes back as a local path") {
    assertEquals(AuthGate.safeNext(Some("/d/kitchen")), "/d/kitchen")
    // The three shapes that would forward a visitor off this site, with the
    // login flow lending the link credibility.
    assertEquals(AuthGate.safeNext(Some("//evil.example")), "/")
    assertEquals(AuthGate.safeNext(Some("https://evil.example")), "/")
    assertEquals(AuthGate.safeNext(Some("javascript:alert(1)")), "/")
    assertEquals(AuthGate.safeNext(Some("d/kitchen")), "/")
    assertEquals(AuthGate.safeNext(None), "/")
  }
}
