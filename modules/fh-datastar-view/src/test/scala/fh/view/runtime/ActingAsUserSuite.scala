package fh.view.runtime

import api.homeassistant.{HomeAssistantApi, ServiceTarget}
import cats.effect.{IO, Ref}
import fh.view.FHError
import fh.view.auth.{AuthSessions, HaAccess, HaOAuth, SessionStore}
import fh.view.testkit.{FakeHomeAssistant, TestAuth}
import io.circe.Json
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri, UrlForm}

import java.time.Instant

/** '''Whose tap is it''' (issue #198). HA decides from the connection's auth
  * handshake, so the one shared socket made every press the add-on's, and the
  * logbook said Supervisor turned the lights off. Through the real [[HaOAuth]]
  * over a stub backend, since the sign-out below hangs on its contract that a
  * non-200 means the grant is gone.
  */
class ActingAsUserSuite extends munit.CatsEffectSuite {

  private val user = TestAuth.admin
  private val MintedClient = "http://fh.test"

  private def haStub(reply: IO[Response[IO]]): HaOAuth =
    new HaOAuth(
      uri"http://ha.test",
      uri"http://ha.test",
      Client.fromHttpApp(HttpApp[IO] { req =>
        if !req.uri.path.renderString.endsWith("/auth/token") then NotFound()
        else req.as[UrlForm] *> reply
      })
    )

  private val renewed = Ok(
    """{"access_token":"minted","refresh_token":"r2","expires_in":1800}"""
  )
  private val revoked = IO.pure(
    Response[IO](Status.BadRequest).withEntity("""{"error":"invalid_grant"}""")
  )

  /** Both are the same fake HA; tests assert which ran the call, and under
    * what.
    */
  private case class Wiring(
      calls: ServiceCalls,
      sessions: AuthSessions,
      opened: Ref[IO, List[String]],
      shared: FakeHomeAssistant,
      perUser: FakeHomeAssistant
  )

  private def wiring(oauth: HaOAuth): IO[Wiring] =
    for {
      shared <- FakeHomeAssistant.create(Nil)
      perUser <- FakeHomeAssistant.create(Nil)
      opened <- Ref[IO].of(List.empty[String])
      sessions <- AuthSessions.create(SessionStore.ephemeral)
      callAs: ServiceCalls.CallAs = token =>
        (domain, service, target, data) =>
          opened.update(_ :+ token) *>
            HomeAssistantApi
              .fromWs(perUser)
              .callService(domain, service, target, data)
              .void
      calls = ServiceCalls.asUser(
        HomeAssistantApi.fromWs(shared),
        callAs,
        sessions,
        oauth
      )
    } yield Wiring(calls, sessions, opened, shared, perUser)

  private def req(session: Option[String]): Request[IO] =
    session.foldLeft(
      Request[IO](Method.POST, uri"/sse/call/home/light/toggle/entity/light.a")
    )((r, id) => r.addCookie(AuthSessions.CookieName, id))

  private def toggle(w: Wiring, session: Option[String]): IO[Unit] =
    w.calls.call(
      req(session),
      "light",
      "toggle",
      ServiceTarget.Entity("light.a"),
      Json.obj()
    )

  private def freshAccess: IO[HaAccess] =
    IO.realTimeInstant.map(now => HaAccess("stored", now.plusSeconds(1800)))

  test("nobody to be: the call goes out on the instance's own connection") {
    for {
      w <- wiring(haStub(renewed))
      _ <- toggle(w, None)
      shared <- w.shared.recordedCalls
      perUser <- w.perUser.recordedCalls
      opened <- w.opened.get
    } yield {
      assertEquals(shared.map(_.entityId), Vector("light.a"), clue = shared)
      assertEquals(perUser, Vector.empty, clue = perUser)
      // Nobody's token: a request with no session has no user to act as.
      assertEquals(opened, Nil, clue = opened)
    }
  }

  test("a logged-in tap is made with THAT person's token") {
    for {
      w <- wiring(haStub(renewed))
      access <- freshAccess
      id <- w.sessions.create(
        user,
        "r1",
        Uri.unsafeFromString(MintedClient),
        Some(access)
      )
      _ <- toggle(w, Some(id))
      opened <- w.opened.get
      perUser <- w.perUser.recordedCalls
      shared <- w.shared.recordedCalls
    } yield {
      assertEquals(opened, List("stored"), clue = opened)
      assertEquals(perUser.map(_.entityId), Vector("light.a"), clue = perUser)
      // The shared connection, the add-on's identity, did not run it.
      assertEquals(shared, Vector.empty, clue = shared)
    }
  }

  test("no usable token: one is minted, used, and kept for the next tap") {
    for {
      w <- wiring(haStub(renewed))
      id <- w.sessions.create(user, "r1", Uri.unsafeFromString(MintedClient))
      _ <- toggle(w, Some(id))
      _ <- toggle(w, Some(id))
      opened <- w.opened.get
      stored <- w.sessions.get(id)
    } yield {
      // The second tap proves the storing.
      assertEquals(opened, List("minted", "minted"), clue = opened)
      assertEquals(stored.flatMap(_.access).map(_.token), Some("minted"))
      assertEquals(stored.map(_.refresh), Some("r2"))
    }
  }

  test("a token about to expire is replaced rather than raced") {
    for {
      w <- wiring(haStub(renewed))
      now <- IO.realTimeInstant
      // Valid now but not for long enough to survive the call. Losing that
      // race is a 401, which reads as "not allowed", so the margin keeps them
      // apart.
      id <- w.sessions.create(
        user,
        "r1",
        Uri.unsafeFromString(MintedClient),
        Some(HaAccess("nearly-spent", now.plusSeconds(5)))
      )
      _ <- toggle(w, Some(id))
      opened <- w.opened.get
    } yield assertEquals(opened, List("minted"), clue = opened)
  }

  test("HA says the grant is gone: the tap is refused AND the session ends") {
    for {
      w <- wiring(haStub(revoked))
      id <- w.sessions.create(user, "r1", Uri.unsafeFromString(MintedClient))
      outcome <- toggle(w, Some(id)).attempt
      still <- w.sessions.get(id)
      opened <- w.opened.get
    } yield {
      assert(outcome.isLeft, clue = outcome)
      assert(
        outcome.left.exists(_.isInstanceOf[FHError]),
        clue = outcome
      )
      // A dead grant logs this person out: dropping the session cuts their
      // streams (`AuthSessions.watch`), so the page stops rather than living
      // around a dead control.
      assertEquals(still, None, clue = still)
      assertEquals(opened, Nil, clue = opened)
    }
  }

  test("minting a token does not restart the role re-check's clock") {
    for {
      w <- wiring(haStub(renewed))
      id <- w.sessions.create(user, "r1", Uri.unsafeFromString(MintedClient))
      before <- w.sessions.get(id).map(_.map(_.verifiedAt))
      _ <- toggle(w, Some(id))
      after <- w.sessions.get(id).map(_.map(_.verifiedAt))
    } yield
      // `verifiedAt` is when HA last confirmed the role, and minting a token
      // confirms nothing about it. Stamping it here would let the busiest
      // dashboard's demoted admin keep access longest.
      assertEquals(after, before, clue = (before, after))
  }

  /** The session file already holds the refresh token, which mints these on
    * demand and does not expire, so keeping the short-lived one widens nothing.
    */
  test(
    "the stored access token expires; the refresh token it came from does not"
  ) {
    for {
      w <- wiring(haStub(renewed))
      id <- w.sessions.create(user, "r1", Uri.unsafeFromString(MintedClient))
      _ <- toggle(w, Some(id))
      stored <- w.sessions.get(id)
      now <- IO.realTimeInstant
    } yield assert(
      stored.flatMap(_.access).exists(_.expiresAt.isAfter(now)) &&
        stored.flatMap(_.access).exists(_.expiresAt.isBefore(farFuture(now))),
      clue = stored
    )
  }

  private def farFuture(now: Instant): Instant = now.plusSeconds(86400)
}
