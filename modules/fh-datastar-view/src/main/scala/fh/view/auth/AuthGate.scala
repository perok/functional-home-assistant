package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{Access, Permission}
import com.comcast.ip4s.Ipv4Address
import fs2.Stream
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

/** What a request must satisfy (issue #89). No `Open` case: a public route
  * simply does not go through the gate. How a refusal is shaped (login redirect
  * or plain status) is the route's choice via `onInvalid`, since a redirected
  * SSE stream or action POST fails unreadably.
  */
enum Requirement derives CanEqual:

  /** `None` is `/`, gated by whatever the default dashboard is. */
  case FromDashboard(slug: Option[String])

  /** For a surface no dashboard owns; the same `Access.permits` decides both.
    */
  case FromAccess(access: Access)

object Requirement:

  val Admin: Requirement = Requirement.FromAccess(Access.Admin)

/** Each route declares its requirement where it is written (issue #89).
  * `permissionFor` reads the live registry, so a reload that changes access
  * applies on the next request; an unknown slug answers `Permission.none`, so
  * probing for dashboards needs a login.
  *
  * Open only so `TestAuth.openGate` can override [[of]].
  */
open class AuthGate(
    sessions: AuthSessions,
    identifyToken: String => IO[HaUser],
    permissionFor: Option[String] => IO[Permission],
    // The default trusts nobody, so saying nothing about ingress grants none.
    ingressUsers: IngressUsers = _ => IO.pure(None),
    trustedProxy: Option[Ipv4Address] = None
) {

  def sessionOf(req: Request[IO]): IO[Option[String]] =
    AuthSessions
      .cookieOf(req)
      .flatTraverse(id => sessions.get(id).map(_.as(id)))

  /** Ingress first: HA has already logged the user in. Then the cookie (the
    * direct port), then a bearer token (a machine).
    */
  def of(req: Request[IO]): IO[Option[HaUser]] =
    ingressUser(req).flatMap {
      case some @ Some(_) => IO.pure(some)
      case None           =>
        session(req).flatMap {
          case Some(s) => IO.pure(Some(s.user))
          case None    => bearerUser(req)
        }
    }

  private def ingressUser(req: Request[IO]): IO[Option[HaUser]] =
    trustedProxy
      .flatMap(Ingress.userIdOf(req, _))
      .fold(IO.pure(None))(ingressUsers)

  def handleRequirement(
      req: Request[IO],
      requirement: Requirement,
      onInvalid: (Status, String) => Response[IO] = AuthGate.saySo
  )(
      handler: IO[Response[IO]]
  ): IO[Response[IO]] =
    (accessFor(requirement), of(req)).flatMapN { (access, user) =>
      if (access.permits(user)) handler
      else IO.pure(onInvalid.tupled(denial(access, user)))
    }

  private def accessFor(requirement: Requirement): IO[Access] =
    requirement match {
      case Requirement.FromAccess(access)  => IO.pure(access)
      case Requirement.FromDashboard(slug) => permissionFor(slug).map(_.access)
    }

  /** 401 and 403 apart: only the first is worth a login page. */
  private def denial(
      access: Access,
      user: Option[HaUser]
  ): (Status, String) =
    user.fold(Status.Unauthorized -> "Not logged in.") { _ =>
      Status.Forbidden -> (access match {
        case Access.Admin => "This needs a Home Assistant admin."
        case _            => "You do not have access to this dashboard."
      })
    }

  /** The handler gets "does the rule still hold" over the same `permits` the
    * door used, and decides itself how to say goodbye (`Server.untilRevoked`).
    */
  def handleStream(req: Request[IO], slug: Option[String])(
      handler: Stream[IO, Boolean] => IO[Response[IO]]
  ): IO[Response[IO]] =
    (permissionFor(slug), of(req)).flatMapN { (permission, user) =>
      if (!permission.mayView(user))
        IO.pure(AuthGate.saySo.tupled(denial(permission.access, user)))
      else revocationOf(req, permission).flatMap(handler)
    }

  /** Only a cookie session can be revoked. Watching the store for an ingress or
    * bearer request answers false on the first element, and behind ingress that
    * was an endless `_reload` loop. Ingress wins over a stale cookie, as in
    * [[of]].
    *
    * `Stream.never`, not `emit(true)`: `untilRevoked` halts when either side
    * ends.
    */
  private def revocationOf(
      req: Request[IO],
      permission: Permission
  ): IO[Stream[IO, Boolean]] =
    ingressUser(req).flatMap {
      case Some(_) => IO.pure(Stream.never[IO])
      case None    =>
        sessionOf(req).map {
          case Some(id) => sessions.watch(Some(id), permission.mayView)
          case None     => Stream.never[IO]
        }
    }

  private def session(req: Request[IO]): IO[Option[AuthSession]] =
    AuthSessions.cookieOf(req).flatTraverse(sessions.get)

  /** Resolved against HA like a login, so `fh` needs no secret of its own. A
    * rejected token is just anonymous.
    */
  private def bearerUser(req: Request[IO]): IO[Option[HaUser]] =
    req.headers.get[Authorization] match {
      case Some(Authorization(Credentials.Token(scheme, token)))
          if scheme == AuthScheme.Bearer =>
        identifyToken(token).attempt.map(_.toOption)
      case _ => IO.pure(None)
    }

}

object AuthGate {

  val saySo: (Status, String) => Response[IO] =
    (status, message) => Response[IO](status).withEntity(message)

  /** For page loads only; a 403 is not redirected, or a logged-in user loops.
    */
  def orLogIn(req: Request[IO]): (Status, String) => Response[IO] =
    (status, message) =>
      if (status == Status.Unauthorized)
        Response[IO](Status.SeeOther)
          .putHeaders(Location(loginRedirect(req.uri)))
      else saySo(status, message)

  /** A route added to the group later inherits the rule. */
  def require(gate: AuthGate, requirement: Requirement)(
      pf: PartialFunction[Request[IO], IO[Response[IO]]]
  ): HttpRoutes[IO] =
    // Off the PartialFunction, not built `HttpRoutes`: matching must not run
    // the handler, or an unauthorised `PUT /edit/file` writes before the no.
    HttpRoutes.of[IO] {
      case req if pf.isDefinedAt(req) =>
        gate.handleRequirement(req, requirement)(pf(req))
    }

  def loginRedirect(target: Uri): Uri =
    Uri(path = Uri.Path.Root / "auth" / "login")
      .withQueryParam("next", nextOf(target))

  /** A path, never a full URI, so `next` cannot be an open redirect. */
  def nextOf(target: Uri): String = {
    val p = target.path.renderString
    val q = target.query.renderString
    val path = if (p.isEmpty) "/" else p
    if (q.isEmpty) path else s"$path?$q"
  }

  /** Backslashes and control characters are refused anywhere: a browser turns
    * `/\host` and `/<tab>/host` into `//host`.
    */
  def safeNext(raw: Option[String]): String =
    raw
      .filter(s =>
        s.startsWith("/") && !s.startsWith("//") && !s.contains(":") &&
          !s.exists(c => c == '\\' || c.isControl)
      )
      .getOrElse("/")
}
