package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import fs2.Stream
import fs2.concurrent.SignallingRef
import fs2.io.file.{Files, Path, PosixPermissions}
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, parser}
import org.http4s.{Request, RequestCookie, ResponseCookie, SameSite, Uri}

import java.nio.file.FileAlreadyExistsException
import java.time.Instant

/** One logged-in person, as the server knows them.
  *
  * `refresh` is a Home Assistant refresh token, i.e. a full-HA-access
  * credential. It is kept for exactly one purpose: the periodic re-check that
  * this user still exists and still holds this role. It is never sent to the
  * browser — the cookie is an opaque handle, so a stolen cookie is a session
  * rather than an HA credential.
  *
  * `verifiedAt` is when HA last confirmed the above, not when the user logged
  * in.
  *
  * `clientId` is the exact `client_id` string the login that minted this
  * session sent to HA. HA compares it RAW against what it stored on the refresh
  * token (`_async_handle_refresh_token`), so only the value login actually sent
  * can renew — and since the browser-facing base is derived per request, it
  * cannot be re-derived at refresh time. Stored, not guessed.
  */
final case class AuthSession(
    user: HaUser,
    refresh: String,
    verifiedAt: Instant,
    clientId: String
) derives Encoder.AsObject,
      Decoder

/** Live auth sessions, keyed by the opaque id their cookie carries.
  *
  * Deliberately a SEPARATE registry from [[fh.view.runtime.Sessions]], which is
  * keyed by `conn`: that one is a per-tab connection handle and this one is a
  * person. One browser holds one auth session across many tabs, and each tab
  * has its own `conn` — merging them would fake one fact with the other.
  *
  * A `SignallingRef` rather than a `Ref` because the map IS the liveness
  * signal: an SSE stream watches it and stops when its id leaves ([[watch]]).
  * That is what makes a logout or an HA-side revocation cut an open dashboard
  * without the browser having to poll anything.
  *
  * The map in memory is the truth; [[store]] is a write-through copy so a
  * restart — which happens on every dashboard edit — does not log everyone out.
  */
final class AuthSessions(
    ref: SignallingRef[IO, Map[String, AuthSession]],
    store: SessionStore
) {

  def get(id: String): IO[Option[AuthSession]] = ref.get.map(_.get(id))

  /** Mint a session for a freshly-authenticated user; returns its cookie id.
    * `clientId` is the base the login itself went out under — see
    * [[AuthSession.clientId]] for why it is stored rather than re-derived.
    */
  def create(user: HaUser, refresh: String, clientId: Uri): IO[String] =
    for {
      id <- AuthSessions.randomId
      now <- IO.realTimeInstant
      _ <- ref.update(
        _.updated(
          id,
          AuthSession(user, refresh, now, clientId.renderString)
        )
      )
      _ <- persist
    } yield id

  /** Record a completed re-check: same session, fresh role, fresh clock — and
    * the client it was minted for, unchanged.
    */
  def renew(id: String, user: HaUser, refresh: String): IO[Unit] =
    IO.realTimeInstant.flatMap { now =>
      ref.update { m =>
        // Only if it is still there — a session evicted while its renewal was
        // in flight must not be resurrected by the reply arriving late.
        m.get(id)
          .fold(m)(s =>
            m.updated(
              id,
              s.copy(user = user, refresh = refresh, verifiedAt = now)
            )
          )
      }
    } *> persist

  def remove(id: String): IO[Unit] = ref.update(_ - id) *> persist

  /** Drop every session belonging to one HA user — what a logout does, so
    * signing out on the phone also ends the tablet's session.
    */
  def removeUser(userId: String): IO[Unit] =
    ref.update(_.filterNot { case (_, s) => s.user.id == userId }) *> persist

  /** Sessions due a re-check, oldest first. */
  def stale(olderThan: Instant): IO[List[(String, AuthSession)]] =
    ref.get.map(_.toList.filter(_._2.verifiedAt.isBefore(olderThan)))

  /** Whether this session id still names a session `permits` accepts.
    *
    * The predicate an SSE stream interrupts on. One signal covers all three
    * ways a live dashboard should stop: the session was evicted (logout, or HA
    * said the grant is dead), or the user is still logged in but their role no
    * longer satisfies this dashboard's rule — a demoted admin watching an
    * admin-only page.
    */
  def watch(
      id: Option[String],
      permits: Option[HaUser] => Boolean
  ): Stream[IO, Boolean] =
    ref.discrete.map(m => permits(id.flatMap(m.get).map(_.user))).changes

  private def persist: IO[Unit] = ref.get.flatMap(store.write)
}

object AuthSessions {

  /** The cookie's whole content, so it has to be unguessable; it carries no
    * meaning, so it needs nothing else.
    *
    * A v4 UUID: 122 random bits from `UUID.randomUUID`, which the JDK documents
    * as using a cryptographically strong PRNG. Plenty for a session id, and it
    * keeps the hand-rolled `SecureRandom` plumbing out of here — this already
    * ran inside `IO`.
    */
  private def randomId: IO[String] = IO.randomUUID.map(_.toString)

  val CookieName: String = "fh_session"

  /** The cookie carrying [[CookieName]], if the request has one. */
  def cookieOf(req: Request[IO]): Option[String] =
    req.cookies.collectFirst {
      case RequestCookie(name, content) if name == CookieName => content
    }

  /** The `Set-Cookie` for a session id.
    *
    * `httpOnly` keeps it out of reach of any script on the page. `SameSite.Lax`
    * is the CSRF control for the action POSTs — it is the only thing standing
    * between a cookie-authenticated `POST /sse/action/...` and any other site,
    * and `Lax` (not `Strict`) because the OAuth callback is a cross-site
    * top-level GET that must arrive with the cookie.
    *
    * `secure` is set only when the request actually arrived over https: a
    * `Secure` cookie on a plain-http LAN instance is simply dropped by the
    * browser, which would make login silently fail.
    *
    * The 90-day `maxAge` mirrors HA's own refresh-token inactivity window;
    * there is no point outliving the credential the session is built on.
    */
  def cookie(id: String, secure: Boolean): ResponseCookie =
    ResponseCookie(
      name = CookieName,
      content = id,
      httpOnly = true,
      secure = secure,
      sameSite = Some(SameSite.Lax),
      path = Some("/"),
      maxAge = Some(90L * 24 * 60 * 60)
    )

  /** The cookie that clears it. Must match `name`/`path` or the browser keeps
    * the original.
    */
  def clearCookie(secure: Boolean): ResponseCookie =
    cookie("", secure).copy(maxAge = Some(0L))

  /** Load whatever the last run persisted, then keep writing through to it. */
  def create(store: SessionStore): IO[AuthSessions] =
    for {
      restored <- store.read
      ref <- SignallingRef[IO].of(restored)
    } yield new AuthSessions(ref, store)
}

/** The write-through file behind [[AuthSessions]] (`.fh/sessions.json`).
  *
  * Not a database and not the source of truth — purely a way to survive a
  * restart. Every mutation rewrites the whole map, which is fine at the scale
  * of "people in one household" and keeps the file a plain snapshot rather than
  * a log that could disagree with memory.
  *
  * It holds HA refresh tokens, so it is written `0600`. That it lives inside a
  * workspace users keep in git is a known problem, tracked in issue #165.
  */
final class SessionStore(path: os.Path) {

  private val file = Path.fromNioPath(path.toNIO)

  def write(sessions: Map[String, AuthSession]): IO[Unit] =
    (
      Files[IO].createDirectories(file.parent.getOrElse(file)) *>
        // Created with the permissions already on it rather than fixed up
        // afterwards: a chmod after the write leaves a window where the
        // refresh tokens are world-readable. Existing is the ordinary case —
        // this rewrites the whole map on every change.
        Files[IO]
          .createFile(file, Some(SessionStore.OwnerOnly))
          .recover { case _: FileAlreadyExistsException => () } *>
        Stream
          .emit(sessions.asJson.noSpaces)
          .through(Files[IO].writeUtf8(file))
          .compile
          .drain
    ).handleErrorWith { e =>
      // A workspace we cannot write to must not take the server down: the
      // sessions still work, they just will not survive a restart.
      IO.consoleForIO.errorln(
        s"[warn] could not persist sessions to $path: ${e.getMessage}"
      )
    }

  /** What the last run left.
    *
    * A missing file starts empty — there is nothing to be wrong. A file that IS
    * there and does not decode stops the boot: quietly starting empty instead
    * would sign the whole household out on every restart while reading as a
    * mere warning, and a session from an unreadable file is one we cannot vouch
    * for. The message names the recovery, which is real: delete the file, log
    * in again once.
    */
  def read: IO[Map[String, AuthSession]] =
    Files[IO]
      .exists(file)
      .ifM(
        Files[IO]
          .readUtf8(file)
          .compile
          .string
          .flatMap(raw =>
            IO.fromEither(parser.decode[Map[String, AuthSession]](raw))
          )
          .handleErrorWith { e =>
            IO.consoleForIO.errorln(
              s"""[fatal] $path exists but cannot be read as sessions: ${e.getMessage}
                 |[fatal] it may predate the stored-client_id format. Delete $path and log in again.""".stripMargin
            ) *> IO.raiseError(e)
          },
        IO.pure(Map.empty)
      )
}

object SessionStore {

  /** `rw-------`. This file holds Home Assistant refresh tokens. */
  val OwnerOnly: PosixPermissions = PosixPermissions
    .fromString("rw-------")
    .getOrElse(
      throw new IllegalStateException("rw------- is not a permission string")
    )

  /** `.fh/sessions.json` under the workspace — beside `machine.json` and
    * `pins.json`, the directory this instance already owns.
    */
  def inWorkspace(dashboardsDir: os.Path): SessionStore =
    new SessionStore(dashboardsDir / ".fh" / "sessions.json")

  /** For tests and for a workspace that has no business persisting (a throwaway
    * boot): keeps everything in memory.
    */
  def ephemeral: SessionStore = new SessionStore(
    os.temp.dir() / "sessions.json"
  )
}
