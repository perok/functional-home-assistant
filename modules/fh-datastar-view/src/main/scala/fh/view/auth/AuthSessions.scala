package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.effect.IO
import cats.effect.std.Mutex
import fs2.Stream
import fs2.concurrent.SignallingRef
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path, PosixPermissions}
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, parser}
import org.http4s.{Request, RequestCookie, ResponseCookie, SameSite, Uri}
import fh.view.telemetry.Logging
import org.typelevel.log4cats.LoggerFactory

import java.time.Instant

final case class HaAccess(token: String, expiresAt: Instant)
    derives Encoder.AsObject,
      Decoder

/** `refresh` is a full-HA-access credential and never reaches the browser: the
  * cookie is an opaque handle. `verifiedAt` is when HA last confirmed the role,
  * not the login time.
  *
  * `clientId` is stored, not re-derived: HA compares it raw against what the
  * refresh token was minted with, and the browser-facing base is per request.
  *
  * `access` lets an action act as this user (issue #198); it adds nothing to a
  * stolen [[SessionStore]] file, which already holds `refresh`.
  */
final case class AuthSession(
    user: HaUser,
    refresh: String,
    verifiedAt: Instant,
    clientId: String,
    access: Option[HaAccess] = None
) derives Encoder.AsObject,
      Decoder

/** Keyed by cookie id: a person, not a tab, so separate from
  * [[fh.view.runtime.Sessions]]. A `SignallingRef` because the map is the
  * liveness signal a stream stops on ([[watch]]). Written through to [[store]]
  * so a restart (every dashboard edit) keeps everyone logged in.
  */
final class AuthSessions private (
    ref: SignallingRef[IO, Map[String, AuthSession]],
    store: SessionStore,
    writing: Mutex[IO]
) {

  def get(id: String): IO[Option[AuthSession]] = ref.get.map(_.get(id))

  def create(
      user: HaUser,
      refresh: String,
      clientId: Uri,
      access: Option[HaAccess] = None
  ): IO[String] =
    for {
      id <- AuthSessions.randomId
      now <- IO.realTimeInstant
      _ <- ref.update(
        _.updated(
          id,
          AuthSession(user, refresh, now, clientId.renderString, access)
        )
      )
      _ <- persist
    } yield id

  def renew(
      id: String,
      user: HaUser,
      refresh: String,
      access: Option[HaAccess] = None
  ): IO[Unit] =
    IO.realTimeInstant.flatMap { now =>
      ref.update { m =>
        // A session evicted mid-renewal must not be resurrected by a late reply.
        m.get(id)
          .fold(m)(s =>
            m.updated(
              id,
              s.copy(
                user = user,
                refresh = refresh,
                verifiedAt = now,
                access = access.orElse(s.access)
              )
            )
          )
      }
    } *> persist

  /** Not [[renew]]: minting does not re-read the role, and stamping
    * `verifiedAt` here would let a busy dashboard's demoted admin keep access
    * longest.
    */
  def tokenMinted(id: String, refresh: String, access: HaAccess): IO[Unit] =
    ref.update { m =>
      m.get(id)
        .fold(m)(s =>
          m.updated(id, s.copy(refresh = refresh, access = Some(access)))
        )
    } *> persist

  def remove(id: String): IO[Unit] = ref.update(_ - id) *> persist

  /** A logout: signing out on the phone ends the tablet's session too. */
  def removeUser(userId: String): IO[Unit] =
    ref.update(_.filterNot { case (_, s) => s.user.id == userId }) *> persist

  def stale(olderThan: Instant): IO[List[(String, AuthSession)]] =
    ref.get.map(_.toList.filter(_._2.verifiedAt.isBefore(olderThan)))

  /** Covers eviction and a role that no longer satisfies the rule alike. */
  def watch(
      id: Option[String],
      permits: Option[HaUser] => Boolean
  ): Stream[IO, Boolean] =
    ref.discrete.map(m => permits(id.flatMap(m.get).map(_.user))).changes

  // The map is read inside the lock, so the last write is of the newest map.
  private def persist: IO[Unit] =
    writing.lock.surround(ref.get.flatMap(store.write))
}

object AuthSessions {

  private def randomId: IO[String] = IO.randomUUID.map(_.toString)

  val CookieName: String = "fh_session"

  def cookieOf(req: Request[IO]): Option[String] =
    req.cookies.collectFirst {
      case RequestCookie(name, content) if name == CookieName => content
    }

  /** `Lax` is the only CSRF control for the action POSTs; not `Strict`, because
    * the OAuth callback is a cross-site GET that needs the cookie. `secure`
    * only over https, or a plain-http LAN browser drops it and login silently
    * fails. 90 days is HA's refresh-token inactivity window.
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

  // Must match name and path, or the browser keeps the original.
  def clearCookie(secure: Boolean): ResponseCookie =
    cookie("", secure).copy(maxAge = Some(0L))

  def create(store: SessionStore): IO[AuthSessions] =
    for {
      restored <- store.read
      ref <- SignallingRef[IO].of(restored)
      writing <- Mutex[IO]
    } yield new AuthSessions(ref, store, writing)
}

/** `.fh/sessions.json`, a snapshot to survive restarts, not the truth. Holds
  * refresh tokens, so `0600`; that it sits in a workspace users keep in git is
  * issue #165.
  */
final class SessionStore(
    path: os.Path,
    loggerFactory: LoggerFactory[IO] = Logging.console
) {

  private val log = loggerFactory.getLoggerFromClass(classOf[SessionStore])

  private val file = Path.fromNioPath(path.toNIO)

  /** Temp file then atomic move: [[read]] refuses to boot on a truncated one.
    */
  def write(sessions: Map[String, AuthSession]): IO[Unit] = {
    val dir = file.parent.getOrElse(Path("."))
    (
      Files[IO].createDirectories(dir) *>
        // Created with the permissions: a later chmod leaves a world-readable
        // window.
        Files[IO]
          .createTempFile(
            Some(dir),
            ".sessions",
            ".tmp",
            Some(SessionStore.OwnerOnly)
          )
          .flatMap { tmp =>
            (Stream
              .emit(sessions.asJson.noSpaces)
              .through(Files[IO].writeUtf8(tmp))
              .compile
              .drain *>
              Files[IO].move(
                tmp,
                file,
                CopyFlags(CopyFlag.AtomicMove, CopyFlag.ReplaceExisting)
              )).onError(_ => Files[IO].deleteIfExists(tmp).attempt.void)
          }
    ).handleErrorWith { e =>
      // Sessions still work; they just will not survive a restart.
      log.warn(s"could not persist sessions to $path: ${e.getMessage}")
    }
  }

  /** A present but undecodable file stops the boot: starting empty would sign
    * the household out on every restart behind a mere warning.
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
          .onError { e =>
            log.error(
              s"""$path exists but cannot be read as sessions: ${e.getMessage}
                 |it may predate the stored-client_id format. Delete $path and log in again.""".stripMargin
            )
          },
        IO.pure(Map.empty)
      )
}

object SessionStore {

  val OwnerOnly: PosixPermissions = PosixPermissions
    .fromString("rw-------")
    .getOrElse(
      throw new IllegalStateException("rw------- is not a permission string")
    )

  def inWorkspace(
      dashboardsDir: os.Path,
      loggerFactory: LoggerFactory[IO] = Logging.console
  ): SessionStore =
    new SessionStore(dashboardsDir / ".fh" / "sessions.json", loggerFactory)

  /** A throwaway temp file, for tests and boots that should not persist. */
  def ephemeral: SessionStore = new SessionStore(
    os.temp.dir() / "sessions.json"
  )
}
