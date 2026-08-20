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
  * is really aimed at is the silent one: a path that should be gated reading
  * as `Open`, which no assertion about a gated path would ever notice.
  */
class AuthGateSuite extends munit.FunSuite {

  private def get(path: String) =
    AuthGate.requirementFor(Request[IO](Method.GET, Uri.unsafeFromString(path)))

  private def post(path: String) =
    AuthGate.requirementFor(Request[IO](Method.POST, Uri.unsafeFromString(path)))

  test("the pre-auth surface is open — a login page that needs a login cannot load") {
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
    assertEquals(get("/d/kitchen"), Requirement.Dashboard(Some("kitchen"), true))
  }

  test("an SSE stream is never redirected — a 303 there reads as a broken connection") {
    assertEquals(
      get("/sse/dashboard/kitchen/patch"),
      Requirement.Dashboard(Some("kitchen"), redirectOnFailure = false)
    )
    assertEquals(
      get("/sse/dashboard/kitchen/recover"),
      Requirement.Dashboard(Some("kitchen"), redirectOnFailure = false)
    )
  }

  test("action POSTs name no slug, so they follow their connection's dashboard") {
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
