package fh.view.auth

import fh.view.model.Access
import org.http4s.{Method, Request, Uri}

/** What a request has to satisfy before it is served (issue #89).
  *
  * Two things vary independently and both matter, which is why this is a small
  * ADT rather than a `Boolean`: WHAT is required, and — when it is not met —
  * whether the caller is a browser that should be sent to the login page or a
  * machine that should be told no. A redirected SSE stream or action POST fails
  * in a way nobody can read, so the distinction is part of the classification
  * rather than a guess made at the denial site.
  */
enum Requirement derives CanEqual:

  /** Served to anyone. The PWA shell, the bundled assets and the auth routes
    * themselves: a login page that needs a login cannot load.
    */
  case Open

  /** Whatever this dashboard's own rule says. The slug is resolved against the
    * live registry, so `/` follows the site's default dashboard.
    */
  case Dashboard(slug: Option[String], redirectOnFailure: Boolean)

  /** The rule of the dashboard the POST's `conn` belongs to — the action
    * routes, which name no slug in their URL.
    */
  case SessionDashboard

  /** An HA admin, by cookie or by bearer token. The authoring surface: it
    * writes `.pkl` source to disk and drives the instance.
    */
  case Admin

/** Which requirement a request falls under.
  *
  * Deliberately pure and total, and deliberately NOT expressed as extra cases
  * inside the route table: a gate assembled from the same `HttpRoutes` it
  * guards protects only the routes someone remembered to annotate, and the
  * failure is silent. This decides for every path, including ones that do not
  * exist — an unknown path is treated as a dashboard request, so a typo'd URL
  * cannot become an unauthenticated peek at a 404 that names real slugs.
  */
object AuthGate {

  def requirementFor(req: Request[?]): Requirement = {
    val segments = req.uri.path.segments.map(_.decoded())
    val browserGet = req.method == Method.GET

    segments.toList match {
      // Anything the browser needs BEFORE it can possibly be authenticated.
      case "auth" :: _                             => Requirement.Open
      case "web" :: _                              => Requirement.Open
      case "assets" :: _                           => Requirement.Open
      case "manifest.webmanifest" :: _             => Requirement.Open
      case "sw.js" :: Nil                          => Requirement.Open
      case name :: Nil if name.startsWith("icon-") => Requirement.Open

      // Read-only pkl resolution, consumed by the laptop `fh` script and
      // pkl-lsp, neither of which carries a cookie. Left open here on purpose
      // and tracked in issue #166 — gating it needs the bearer carrier plus a
      // check that pkl-lsp can actually send a header.
      case "system" :: "pkl" :: _ => Requirement.Open

      // The authoring surface: writes source to disk, drives the instance, and
      // proxies a language server. Admin, and never a redirect — every one of
      // these is an API call or a websocket.
      case "system" :: _ => Requirement.Admin
      case "lsp" :: _    => Requirement.Admin
      case "edit" :: Nil => Requirement.Admin
      case "edit" :: _   => Requirement.Admin

      // The dashboards themselves.
      case Nil              => Requirement.Dashboard(None, browserGet)
      case "" :: Nil        => Requirement.Dashboard(None, browserGet)
      case "d" :: slug :: _ => Requirement.Dashboard(Some(slug), browserGet)

      // The live stream for one dashboard. Never redirected: an SSE stream that
      // 303s to a login page reports as a broken connection.
      case "sse" :: "dashboard" :: slug :: _ =>
        Requirement.Dashboard(Some(slug), redirectOnFailure = false)

      // Action POSTs. They name no slug, but they carry `conn`, and the
      // connection knows which dashboard it is.
      case "sse" :: _ => Requirement.SessionDashboard

      // Unknown paths are treated as dashboard requests rather than Open: the
      // safe direction for anything added later and not classified here.
      case _ => Requirement.Dashboard(None, browserGet)
    }
  }

  /** Where to send a browser that has to log in, preserving what it asked for
    * so the callback can put it back.
    */
  def loginRedirect(target: Uri): Uri =
    Uri(path = Uri.Path.Root / "auth" / "login")
      .withQueryParam("next", nextOf(target))

  /** The path (never a full URI) a login should return to. Only a local,
    * absolute path is echoed back: `next` reaches us from the query string, and
    * a redirect that honoured an absolute URL there would forward anyone who
    * clicks a crafted link straight off this site — with the login flow lending
    * it credibility.
    */
  def nextOf(target: Uri): String = {
    val p = target.path.renderString
    val q = target.query.renderString
    val path = if (p.isEmpty) "/" else p
    if (q.isEmpty) path else s"$path?$q"
  }

  /** [[nextOf]]'s counterpart on the way back: reject anything that is not a
    * single-slash-rooted local path.
    */
  def safeNext(raw: Option[String]): String =
    raw
      .filter(s => s.startsWith("/") && !s.startsWith("//") && !s.contains(":"))
      .getOrElse("/")

  /** Whether `access` lets this request through, given who (if anyone) it is.
    */
  def permits(access: Access, session: Option[AuthSession]): Boolean =
    access.permits(session.map(_.user))
}
