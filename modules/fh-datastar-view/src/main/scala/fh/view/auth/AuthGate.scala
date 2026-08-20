package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.Access
import org.http4s.headers.{Authorization, Location}
import org.http4s.{
  AuthScheme,
  Credentials,
  Header,
  HttpApp,
  HttpRoutes,
  Request,
  Response,
  Status,
  Uri
}
import org.typelevel.ci.CIString
import org.typelevel.vault.Key

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

  /** Whatever this dashboard's own rule says. `None` is `/`, which resolves
    * through the site's default dashboard, so it is gated by whatever it
    * actually serves.
    */
  case Dashboard(slug: Option[String], redirectOnFailure: Boolean)

  /** An HA admin, by cookie or by bearer token. The authoring surface: it
    * writes `.pkl` source to disk and drives the instance.
    */
  case Admin

object Requirement {

  /** A browser NAVIGATION — a failure is worth a redirect to the login page,
    * because there is a human there to see it.
    */
  def page(slug: Option[String]): Requirement =
    Requirement.Dashboard(slug, redirectOnFailure = true)

  /** Anything a script called: an SSE stream, an action POST, a JSON endpoint.
    * Never redirected — a 303 on a stream reports as a broken connection.
    */
  def data(slug: Option[String]): Requirement =
    Requirement.Dashboard(slug, redirectOnFailure = false)
}

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
  * What it does NOT get right on its own is forgetting: a route added later
  * with no wrapper would serve unauthenticated, silently. [[assertGated]]
  * closes that — every response from here is stamped, and an unstamped one from
  * a route that MATCHED is refused. So forgetting fails closed and loudly.
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
    accessFor: Option[String] => IO[Option[Access]],
    stamp: Key[Unit]
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
    decide(req, requirement, handler).map(_.withAttribute(stamp, ()))

  private def decide(
      req: Request[IO],
      requirement: Requirement,
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

      case Requirement.Dashboard(slug, redirectOnFailure) =>
        (accessFor(slug), of(req)).flatMapN { (access, user) =>
          val required = access.getOrElse(Access.default)
          if (required.permits(user))
            handler.flatMap(keepLive(req, required, _))
          else if (user.isDefined)
            IO.pure(forbidden("You do not have access to this dashboard."))
          else if (redirectOnFailure)
            IO.pure(
              Response[IO](Status.SeeOther)
                .putHeaders(Location(AuthGate.loginRedirect(req.uri)))
            )
          else IO.pure(unauthorized)
        }
    }

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

  /** Keep checking for as long as the response lasts.
    *
    * An SSE stream is admitted once and then runs for hours, so the check at
    * the door is not enough on its own: logging out, being revoked in HA, or
    * losing the admin role has to reach a dashboard that is already open. The
    * session store is a `SignallingRef`, so that is one `interruptWhen` over
    * the same predicate the door used — admission and continued admission
    * cannot drift, because they are the same `required.permits`.
    *
    * Only streams are wrapped. An ordinary page has finished long before any of
    * this could change, and wrapping it would add a subscription per request.
    */
  private def keepLive(
      req: Request[IO],
      required: Access,
      resp: Response[IO]
  ): IO[Response[IO]] =
    if (!isEventStream(req)) IO.pure(resp)
    else
      sessionOf(req).map { id =>
        resp.withBodyStream(
          resp.body.interruptWhen(
            sessions.watch(id, required.permits).map(allowed => !allowed)
          )
        )
      }

  private def isEventStream(req: Request[IO]): Boolean =
    req.uri.path.segments.headOption.exists(_.decoded() == "sse")

  private def forbidden(message: String): Response[IO] =
    Response[IO](Status.Forbidden).withEntity(message)

  /** 401 with the hint a fetch/SSE client needs to know a login would help —
    * the page shell turns this into a reload, which then redirects.
    */
  private def unauthorized: Response[IO] =
    Response[IO](Status.Unauthorized)
      .putHeaders(Header.Raw(CIString("X-FH-Login"), "/auth/login"))
      .withEntity("Not logged in.")
}

object AuthGate {

  /** The stamp [[AuthGate.handleRequirement]] leaves on everything it serves.
    * One per process, created at boot and handed to both the gate and
    * [[assertGated]] — a `Key` is identity-compared, so a second one would not
    * match.
    */
  def stampKey: IO[Key[Unit]] = Key.newKey[IO, Unit]

  /** The backstop: nothing reaches a client from a route that did not declare a
    * requirement.
    *
    * `HttpRoutes` answers `None` when NO route matched, which is an ordinary
    * 404 and not a gap. A route that matched, ran, and produced an unstamped
    * response is a route somebody wrote without wrapping it — served as a 500,
    * because the alternative is serving it to anyone. That is the one failure a
    * per-route rule cannot catch by itself, and it is silent without this.
    */
  def assertGated(stamp: Key[Unit])(routes: HttpRoutes[IO]): HttpApp[IO] =
    HttpApp[IO] { req =>
      routes.run(req).value.flatMap {
        case None => IO.pure(Response[IO](Status.NotFound))
        case Some(resp) if resp.attributes.lookup(stamp).isDefined =>
          IO.pure(resp)
        case Some(_) =>
          IO.consoleForIO
            .errorln(
              s"[BUG] ${req.method} ${req.uri.path} was served by a route that " +
                "declares no Requirement — refusing it. Wrap the handler in " +
                "AuthGate.handleRequirement."
            )
            .as(Response[IO](Status.InternalServerError))
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

  /** An `Identity` that says everyone is the same user — for a boot that has no
    * HA to authenticate against, and for tests that are not about auth.
    */
  def fixedIdentity(user: Option[HaUser]): Identity = new Identity {
    def of(req: Request[IO]): IO[Option[HaUser]] = IO.pure(user)
    def sessionOf(req: Request[IO]): IO[Option[String]] = IO.pure(None)
  }
}
