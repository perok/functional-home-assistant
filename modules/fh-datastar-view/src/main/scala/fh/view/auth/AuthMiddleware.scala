package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.data.OptionT
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.Access
import fs2.Stream
import io.circe.parser
import org.http4s.headers.{Authorization, Location}
import org.http4s.{
  AuthScheme,
  Credentials,
  Header,
  HttpApp,
  Request,
  Response,
  Status
}
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets

/** What the rest of the server needs from the gate: who this request is, and
  * whether it may proceed.
  *
  * A trait so `Server`/`ServerApp` depend on the question rather than on the
  * OAuth machinery that answers it — and so a test can hand over a fixed
  * identity without an HA to log in against.
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
  * Wraps the WHOLE app rather than annotating individual routes, for the reason
  * spelled out in [[AuthGate]]: a gate assembled from the route table protects
  * only what someone remembered to annotate, and forgetting is silent. Composed
  * beside `FHError.handle` at the one existing seam in `ServerApp`.
  *
  * `accessFor` asks the live registry for one slug's rule, so a reload that
  * changes a dashboard's access takes effect on the next request with nothing
  * to invalidate. `None` means the slug does not exist — treated as the
  * restrictive default rather than as "open", so a probe for dashboards that
  * are not there cannot be done anonymously.
  */
final class AuthMiddleware(
    sessions: AuthSessions,
    identifyToken: String => IO[HaUser],
    accessFor: Option[String] => IO[Option[Access]],
    // conn -> the dashboard that connection is viewing.
    connToSlug: String => IO[Option[String]],
    // The Datastar signal name carrying `conn` (`Server.ConnSignal`), passed in
    // rather than imported so this package does not depend on the runtime one.
    connSignal: String
) extends Identity {

  /** The slug an action POST is for, read out of its buffered body. */
  private def sessionSlug(body: Array[Byte]): IO[Option[String]] =
    parser
      .parse(new String(body, StandardCharsets.UTF_8))
      .toOption
      .flatMap(_.hcursor.get[String](connSignal).toOption)
      .flatTraverse(connToSlug)

  def sessionOf(req: Request[IO]): IO[Option[String]] =
    AuthSessions
      .cookieOf(req)
      .flatTraverse(id => sessions.get(id).map(_.as(id)))

  def of(req: Request[IO]): IO[Option[HaUser]] =
    session(req).flatMap {
      case Some(s) => IO.pure(Some(s.user))
      case None    => bearerUser(req)
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

  def apply(app: HttpApp[IO]): HttpApp[IO] = HttpApp[IO] { req =>
    AuthGate.requirementFor(req) match {
      case Requirement.Open => app.run(req)

      case Requirement.Admin =>
        of(req).flatMap {
          case Some(u) if u.is_admin => app.run(req)
          // Never a redirect: every admin route is an API call, a PUT or a
          // websocket, and a 303 to a login page reads as a broken endpoint.
          case Some(_) =>
            IO.pure(forbidden("This needs a Home Assistant admin."))
          case None => IO.pure(unauthorized)
        }

      case Requirement.Dashboard(slug, redirectOnFailure) =>
        check(req, accessFor(slug), redirectOnFailure, app)

      case Requirement.SessionDashboard =>
        // The action POSTs name no slug; the `conn` in their body does. Reading
        // a request body CONSUMES it, so it is buffered and put back — these
        // are a few dozen bytes of Datastar signals, and the alternative is
        // letting each route check itself, which is the arrangement
        // `requirementFor` exists to avoid.
        //
        // A POST whose connection is unknown gets the restrictive default
        // rather than a pass: an unroutable action is not a reason to skip the
        // check.
        req.body.compile.to(Array).flatMap { bytes =>
          val replayable = req.withBodyStream(Stream.emits(bytes).covary[IO])
          check(
            replayable,
            sessionSlug(bytes).flatMap(accessFor),
            redirectOnFailure = false,
            app
          )
        }
    }
  }

  private def check(
      req: Request[IO],
      rule: IO[Option[Access]],
      redirectOnFailure: Boolean,
      app: HttpApp[IO]
  ): IO[Response[IO]] =
    (rule, of(req)).flatMapN { (access, user) =>
      val required = access.getOrElse(Access.default)
      if (required.permits(user))
        app.run(req).flatMap(keepLive(req, required, _))
      else if (user.isDefined)
        IO.pure(forbidden("You do not have access to this dashboard."))
      else if (redirectOnFailure)
        IO.pure(
          Response[IO](Status.SeeOther)
            .putHeaders(Location(AuthGate.loginRedirect(req.uri)))
        )
      else IO.pure(unauthorized)
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

object AuthMiddleware {

  /** An `Identity` that says everyone is the same user — for a boot that has no
    * HA to authenticate against, and for tests that are not about auth.
    */
  def fixed(user: Option[HaUser]): Identity = new Identity {
    def of(req: Request[IO]): IO[Option[HaUser]] = IO.pure(user)
    def sessionOf(req: Request[IO]): IO[Option[String]] = IO.pure(None)
  }

  /** Lift an `HttpRoutes`-shaped fallthrough into the app the gate wraps. */
  def orNotFound(routes: org.http4s.HttpRoutes[IO]): HttpApp[IO] =
    HttpApp[IO](req =>
      OptionT(routes.run(req).value).getOrElse(Response[IO](Status.NotFound))
    )
}
