package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.Access
import org.http4s.headers.{Authorization, Location}
import org.http4s.{
  AuthScheme,
  Credentials,
  HttpRoutes,
  Request,
  Response,
  Status,
  Uri
}

/** What a request has to satisfy before it is served (issue #89).
  *
  * Two things vary independently and both matter, which is why this is a small
  * ADT rather than a `Boolean`: WHAT is required, and — when it is not met —
  * whether the caller is a browser that should be sent to the login page or a
  * script that should be told no. A redirected SSE stream or action POST fails
  * in a way nobody can read, so the distinction is declared at the route rather
  * than guessed at the denial site.
  */
enum Requirement derives CanEqual:

  /** Served to anyone. The PWA shell, the bundled assets and the auth routes
    * themselves: a login page that needs a login cannot load.
    */
  case Open

  /** A browser NAVIGATION to a dashboard, held to that dashboard's own rule.
    * `None` is `/`, which resolves through the site's default dashboard, so it
    * is gated by whatever it actually serves.
    *
    * The only requirement that REDIRECTS, and that is the whole difference from
    * [[Data]]: there is a human here, and the page load is where a login can be
    * sent for. Everything the page then opens is already known to be permitted,
    * so a later refusal on one of those is a genuine error rather than a "you
    * should log in".
    */
  case Page(slug: Option[String])

  /** Anything a script asked for against a dashboard: its SSE stream, an action
    * POST, a JSON endpoint. Same rule as [[Page]], different answer when it is
    * not met — a 401, never a redirect.
    */
  case Data(slug: Option[String])

  /** An HA admin, by cookie or by bearer token. The authoring surface: it
    * writes `.pkl` source to disk and drives the instance.
    */
  case Admin

/** What the rest of the server needs from the gate: who this request is.
  *
  * A trait so `Server` depends on the question rather than on the OAuth
  * machinery that answers it — and so a test can hand over a fixed identity
  * with no HA to log in against.
  */
trait Identity {

  /** The authenticated user behind this request, if any. Resolves a session
    * cookie, or a bearer token belonging to a machine (`fh`).
    */
  def of(req: Request[IO]): IO[Option[HaUser]]

  /** The session id the request's cookie names, if it names a live one. The SSE
    * stream needs this to know which session's death should cut it.
    */
  def sessionOf(req: Request[IO]): IO[Option[String]]
}

/** The gate (issue #89).
  *
  * Each route declares its own requirement and wraps its handler in
  * [[handleRequirement]], so the rule is written where the route is rather than
  * in a table somewhere else that has to be kept in step with it. Two things
  * that arrangement gets right, and which a central classifier could not:
  *
  *   - A route that knows its own slug says so, and the ones that did not were
  *     changed until they do: an action POST now carries its dashboard in the
  *     URL, and `Server.withSession` declares its requirement once the session
  *     names one. Neither has to re-parse a request body to guess.
  *   - `Open` is an annotation beside the thing it exempts, so an exemption is
  *     visible in review at the point it is granted.
  *
  * A whole route GROUP with one rule wraps once — [[AuthGate.require]] — so
  * `EditorRoutes` is admin by construction rather than by every route
  * remembering to say so.
  *
  * `accessFor` asks the live registry for one slug's rule, so a reload that
  * changes a dashboard's access takes effect on the next request with nothing
  * to invalidate. `None` means the slug does not exist — treated as the
  * restrictive default rather than as "open", so a probe for dashboards that
  * are not there cannot be done anonymously.
  *
  * Not `final` only so a test fixture can override [[of]] and hand over a fixed
  * identity — the seam [[Identity]] exists for. Nothing in production
  * subclasses it.
  */
open class AuthGate(
    sessions: AuthSessions,
    identifyToken: String => IO[HaUser],
    accessFor: Option[String] => IO[Access]
) extends Identity {

  override def sessionOf(req: Request[IO]): IO[Option[String]] =
    AuthSessions
      .cookieOf(req)
      .flatTraverse(id => sessions.get(id).map(_.as(id)))

  override def of(req: Request[IO]): IO[Option[HaUser]] =
    session(req).flatMap {
      case Some(s) => IO.pure(Some(s.user))
      case None    => bearerUser(req)
    }

  /** Serve `handler` if `requirement` is met, and say no in the shape this
    * caller can read if it is not.
    *
    * `handler` is an ordinary `IO` value, so naming it here does not run it —
    * nothing is served before the check.
    */
  def handleRequirement(req: Request[IO], requirement: Requirement)(
      handler: IO[Response[IO]]
  ): IO[Response[IO]] =
    requirement match {
      case Requirement.Open => handler

      case Requirement.Admin =>
        of(req).flatMap {
          case Some(u) if u.is_admin => handler
          // Never a redirect: every admin route is an API call, a PUT or a
          // websocket, and a 303 to a login page reads as a broken endpoint.
          case Some(_) =>
            IO.pure(forbidden("This needs a Home Assistant admin."))
          case None => IO.pure(unauthorized)
        }

      case Requirement.Page(slug) =>
        permitted(req, slug).flatMap {
          case Right(_)   => handler
          case Left(user) =>
            IO.pure(user.fold(loginRedirectResponse(req))(_ => noAccess))
        }

      case Requirement.Data(slug) =>
        permitted(req, slug).flatMap {
          case Right(_)   => handler
          case Left(user) =>
            IO.pure(user.fold(unauthorized)(_ => noAccess))
        }
    }

  /** [[handleRequirement]] for a route whose response is a LIVE STREAM.
    *
    * Separate rather than sniffed from the path, because the route knows what
    * it is returning and the gate does not. It is the same check plus one thing
    * only a stream needs: admission is not a one-time event when the response
    * lasts for hours, so the body is cut if the rule stops holding
    * ([[AuthSessions.watch]]) — over the SAME `permits` the door used, so the
    * two cannot drift.
    */
  def handleStream(req: Request[IO], slug: Option[String])(
      handler: IO[Response[IO]]
  ): IO[Response[IO]] =
    (accessFor(slug), of(req)).flatMapN { (access, user) =>
      if (!access.permits(user))
        IO.pure(user.fold(unauthorized)(_ => noAccess))
      else
        (handler, sessionOf(req)).flatMapN { (resp, session) =>
          IO.pure(
            resp.withBodyStream(
              resp.body.interruptWhen(
                sessions.watch(session, access.permits).map(ok => !ok)
              )
            )
          )
        }
    }

  /** `Right` when this request may have the dashboard, `Left(who)` when not —
    * carrying who was asking, since that is what decides between "log in" and
    * "not yours".
    */
  private def permitted(
      req: Request[IO],
      slug: Option[String]
  ): IO[Either[Option[HaUser], Unit]] =
    (accessFor(slug), of(req)).mapN { (access, user) =>
      if (access.permits(user)) Right(()) else Left(user)
    }

  private def loginRedirectResponse(req: Request[IO]): Response[IO] =
    Response[IO](Status.SeeOther)
      .putHeaders(Location(AuthGate.loginRedirect(req.uri)))

  private def noAccess: Response[IO] =
    forbidden("You do not have access to this dashboard.")

  private def session(req: Request[IO]): IO[Option[AuthSession]] =
    AuthSessions.cookieOf(req).flatTraverse(sessions.get)

  /** A machine's identity: an HA long-lived token in `Authorization: Bearer`,
    * resolved against HA exactly the way a login is. One identity source, two
    * carriers — `is_admin` comes from HA either way, so `fh` needs no shared
    * secret of its own.
    *
    * A token HA rejects is simply not an identity; the request then fails the
    * requirement like any anonymous one.
    */
  private def bearerUser(req: Request[IO]): IO[Option[HaUser]] =
    req.headers.get[Authorization] match {
      case Some(Authorization(Credentials.Token(scheme, token)))
          if scheme == AuthScheme.Bearer =>
        identifyToken(token).attempt.map(_.toOption)
      case _ => IO.pure(None)
    }

  private def forbidden(message: String): Response[IO] =
    Response[IO](Status.Forbidden).withEntity(message)

  /** A plain 401. No "where to log in" hint: the browser gets that from the
    * page load, which is the request a human is actually waiting on, and
    * nothing reads a hint off this one.
    */
  private def unauthorized: Response[IO] =
    Response[IO](Status.Unauthorized).withEntity("Not logged in.")
}

object AuthGate {

  /** Hold an entire route group to one requirement.
    *
    * For a surface where every route has the same rule — the editor is all
    * admin — this is better than annotating each: a route added to the group
    * later inherits it instead of needing to remember it.
    */
  def require(gate: AuthGate, requirement: Requirement)(
      pf: PartialFunction[Request[IO], IO[Response[IO]]]
  ): HttpRoutes[IO] =
    // Off the route table's own PartialFunction rather than a built
    // `HttpRoutes`: whether a request MATCHES has to be answerable without
    // running the handler, or an unauthorised `PUT /edit/file` would write the
    // file and then be told no. `pf(req)` only BUILDS the `IO`.
    HttpRoutes.of[IO] {
      case req if pf.isDefinedAt(req) =>
        gate.handleRequirement(req, requirement)(pf(req))
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

  /** An `Identity` that says everyone is the same user — for a boot that has no
    * HA to authenticate against, and for tests that are not about auth.
    */
  def fixedIdentity(user: Option[HaUser]): Identity = new Identity {
    def of(req: Request[IO]): IO[Option[HaUser]] = IO.pure(user)
    def sessionOf(req: Request[IO]): IO[Option[String]] = IO.pure(None)
  }
}
