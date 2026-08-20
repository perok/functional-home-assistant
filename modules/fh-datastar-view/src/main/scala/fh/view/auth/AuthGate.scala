package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.Permission
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

  /** Whatever this dashboard's own rule says — its page, its stream, its action
    * POSTs alike. `None` is `/`, which resolves through the site's default
    * dashboard, so it is gated by whatever it actually serves.
    *
    * ONE case, not a page/data pair: the RULE is the same either way, and only
    * the shape of a refusal differs. That is the caller's to choose, through
    * `onInvalid` — see [[AuthGate.handleRequirement]].
    */
  case FromDashboard(slug: Option[String])

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
  * `permissionFor` asks the live registry what may be done on one slug, so a
  * reload that changes a dashboard's access takes effect on the next request
  * with nothing to invalidate. A slug that names nothing answers
  * `Permission.none` rather than an absence, so a probe for dashboards that are
  * not there cannot be done anonymously.
  *
  * Not `final` only so a test fixture can override [[of]] and hand over a fixed
  * identity — the seam [[Identity]] exists for. Nothing in production
  * subclasses it.
  */
open class AuthGate(
    sessions: AuthSessions,
    identifyToken: String => IO[HaUser],
    permissionFor: Option[String] => IO[Permission],
    // Who HA says is behind an ingress request, and from where a request has
    // to arrive for that to be believed. See [[Ingress]] — the default trusts
    // nobody, so a construction that says nothing about ingress gets none of
    // it rather than a hole.
    ingressUsers: IngressUsers = _ => IO.pure(None),
    trustedProxy: Option[Ipv4Address] = None
) extends Identity {

  override def sessionOf(req: Request[IO]): IO[Option[String]] =
    AuthSessions
      .cookieOf(req)
      .flatTraverse(id => sessions.get(id).map(_.as(id)))

  /** Who this request is, by whichever carrier it used.
    *
    * Ingress comes FIRST and needs no cookie: behind the add-on proxy HA has
    * already logged the user in, so making them log in again here would be
    * asking twice. The cookie is the direct port's answer, and the bearer is a
    * machine's.
    */
  override def of(req: Request[IO]): IO[Option[HaUser]] =
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

  /** Serve `handler` if `requirement` is met, and say no in the shape this
    * caller can read if it is not.
    *
    * `handler` is an ordinary `IO` value, so naming it here does not run it —
    * nothing is served before the check.
    */
  def handleRequirement(
      req: Request[IO],
      requirement: Requirement,
      onInvalid: (Status, String) => Response[IO] = AuthGate.saySo
  )(
      handler: IO[Response[IO]]
  ): IO[Response[IO]] =
    requirement match {
      case Requirement.Open => handler

      case Requirement.Admin =>
        of(req)
          .map {
            case Some(u) if u.is_admin => None
            case Some(_)               =>
              Some(Status.Forbidden -> "This needs a Home Assistant admin.")
            case None => Some(Status.Unauthorized -> "Not logged in.")
          }
          .flatMap(refuse(handler, onInvalid))

      case Requirement.FromDashboard(slug) =>
        permitted(req, slug).flatMap(who =>
          refuse(handler, onInvalid)(who.map(denial))
        )
    }

  private def refuse(
      handler: IO[Response[IO]],
      onInvalid: (Status, String) => Response[IO]
  )(problem: Option[(Status, String)]): IO[Response[IO]] =
    problem.fold(handler)((status, message) =>
      IO.pure(onInvalid(status, message))
    )

  /** What is wrong, when a dashboard turns somebody away. Not logged in and
    * logged in as the wrong person are different answers, and the caller needs
    * to be able to tell them apart — only the first is worth a login page.
    */
  private def denial(user: Option[HaUser]): (Status, String) =
    user.fold(Status.Unauthorized -> "Not logged in.")(_ =>
      Status.Forbidden -> "You do not have access to this dashboard."
    )

  /** [[handleRequirement]] for a route whose response is a LIVE STREAM.
    *
    * Admission is not a one-time event when the response lasts for hours, so
    * the handler is given a `Stream[IO, Boolean]` — "does the rule still hold"
    * — over the SAME `permits` the door used, so the two cannot drift.
    *
    * The gate hands that signal over rather than applying it. What to DO on
    * revocation is a stream concern, and at this level a response body is only
    * bytes; the route builds its stream out of `ServerSentEvent`s and can
    * therefore say goodbye properly on the way out (`Server.untilRevoked`).
    */
  def handleStream(req: Request[IO], slug: Option[String])(
      handler: Stream[IO, Boolean] => IO[Response[IO]]
  ): IO[Response[IO]] =
    (permissionFor(slug), of(req)).flatMapN { (permission, user) =>
      if (!permission.mayView(user))
        IO.pure(AuthGate.saySo.tupled(denial(user)))
      else
        sessionOf(req).flatMap(id =>
          handler(sessions.watch(id, permission.mayView))
        )
    }

  /** `None` when this request may have the dashboard, otherwise who was asking
    * — which is what decides between "log in" and "not yours".
    */
  private def permitted(
      req: Request[IO],
      slug: Option[String]
  ): IO[Option[Option[HaUser]]] =
    (permissionFor(slug), of(req)).mapN { (permission, user) =>
      Option.unless(permission.mayView(user))(user)
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

}

object AuthGate {

  /** The default refusal: the status, and the reason in the body. What a script
    * gets, and what a human gets on anything except a page load.
    */
  val saySo: (Status, String) => Response[IO] =
    (status, message) => Response[IO](status).withEntity(message)

  /** The refusal a PAGE load gets: an anonymous visitor is sent to log in,
    * because a page load is the one request with a human waiting on it.
    *
    * Only when nobody is logged in — a `403` means the wrong person, and
    * bouncing them to a login they are already past would loop.
    */
  def orLogIn(req: Request[IO]): (Status, String) => Response[IO] =
    (status, message) =>
      if (status == Status.Unauthorized)
        Response[IO](Status.SeeOther)
          .putHeaders(Location(loginRedirect(req.uri)))
      else saySo(status, message)

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
