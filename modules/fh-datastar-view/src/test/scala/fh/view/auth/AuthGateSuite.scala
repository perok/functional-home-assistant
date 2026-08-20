package fh.view.auth

import cats.effect.IO
import org.http4s.implicits.*
import org.http4s.{Method, Request, Uri}

/** Which requirement each route falls under, and the two open-redirect guards
  * around `next` (issue #89).
  *
  * `requirementFor` is pure and total, which is the whole reason the gate is
  * shaped this way — so the classification can be checked directly instead of
  * inferred from status codes through a booted server. The failure this suite
  * is really aimed at is the silent one: a path that should be gated reading as
  * `Open`, which no assertion about a gated path would ever notice.
  */
class AuthGateSuite extends munit.FunSuite {

  private def get(path: String) =
    AuthGate.requirementFor(Request[IO](Method.GET, Uri.unsafeFromString(path)))

  private def post(path: String) =
    AuthGate.requirementFor(
      Request[IO](Method.POST, Uri.unsafeFromString(path))
    )

  test(
    "the pre-auth surface is open — a login page that needs a login cannot load"
  ) {
    List(
      "/auth/login",
      "/auth/callback?code=x&state=y",
      "/web/shell.js",
      "/assets/beer.min.css",
      "/manifest.webmanifest",
      "/sw.js",
      "/icon-192.png"
    ).foreach(p => assertEquals(get(p), Requirement.Open, p))
  }

  test("pkl resolution stays open in this PR (issue #166)") {
    assertEquals(get("/system/pkl/packages"), Requirement.Open)
    assertEquals(get("/system/pkl/base.pkl"), Requirement.Open)
  }

  test("the authoring surface is admin, and never redirected") {
    List(
      get("/edit"),
      get("/edit/file/site.pkl"),
      get("/lsp/pkl"),
      post("/system/push/kitchen"),
      post("/system/dump/refresh")
    ).foreach(r => assertEquals(r, Requirement.Admin))
  }

  test("dashboards carry their own rule; a browser GET may be redirected") {
    assertEquals(get("/"), Requirement.Dashboard(None, true))
    assertEquals(
      get("/d/kitchen"),
      Requirement.Dashboard(Some("kitchen"), true)
    )
  }

  test(
    "an SSE stream is never redirected — a 303 there reads as a broken connection"
  ) {
    assertEquals(
      get("/sse/dashboard/kitchen/patch"),
      Requirement.Dashboard(Some("kitchen"), redirectOnFailure = false)
    )
    assertEquals(
      get("/sse/dashboard/kitchen/recover"),
      Requirement.Dashboard(Some("kitchen"), redirectOnFailure = false)
    )
  }

  test(
    "action POSTs name no slug, so they follow their connection's dashboard"
  ) {
    assertEquals(
      post("/sse/action/light/toggle/light.kitchen"),
      Requirement.SessionDashboard
    )
    assertEquals(post("/sse/popup/close"), Requirement.SessionDashboard)
  }

  /** The property, not the line: a route added later and never classified here
    * must fail CLOSED. Sampling paths that merely resemble the open ones is
    * what would have caught a prefix match written as `startsWith`.
    */
  test("nothing outside the listed pre-auth surface is open") {
    List(
      "/d/kitchen",
      "/nope",
      "/webhook/secret",
      "/assetsx/thing",
      "/authorize",
      "/system/push/kitchen",
      "/icon.png",
      "/sw.js.map"
    ).foreach(p => assert(get(p) != Requirement.Open, s"$p read as Open"))
  }

  test("a login redirect carries the whole request back, query included") {
    val to = AuthGate.loginRedirect(uri"/d/kitchen?tab=lights")
    assertEquals(to.path.renderString, "/auth/login")
    assertEquals(to.query.params.get("next"), Some("/d/kitchen?tab=lights"))
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
