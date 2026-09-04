package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{IO, Ref, Resource}
import fh.view.FHError
import fh.view.auth.{AuthSessions, HaAccess, HaOAuth, SessionStore}
import fh.view.testkit.{FakeHomeAssistant, TestAuth}
import io.circe.Json
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri, UrlForm}

import java.time.Instant

/** '''Whose tap is it''' (issue #198).
  *
  * HA decides that from the CONNECTION, in its auth handshake — so the one
  * shared socket this app reads through makes every button press the add-on's,
  * and a house's logbook says Supervisor turned the lights off. These are about
  * the credential a call goes out under, which is the only thing that changes
  * the answer.
  *
  * Driven through the REAL [[HaOAuth]] over a stub HTTP backend, like
  * [[RevalidateSessionsSuite]]: the `/auth/token` contract — a non-200 means
  * the grant is gone — is what the sign-out below hangs on, so it is exercised
  * rather than assumed.
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

  /** The instance's own connection, and a per-token one that records which
    * token opened it. Both are the same fake HA — what a test asserts on is
    * WHICH of them ran the call, and under what.
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
      connectAs = (token: String) =>
        Resource.eval(
          opened.update(_ :+ token).as(HomeAssistantApi.fromWs(perUser))
        )
      calls = ServiceCalls.asUser(
        HomeAssistantApi.fromWs(shared),
        connectAs,
        sessions,
        oauth
      )
    } yield Wiring(calls, sessions, opened, shared, perUser)

  private def req(session: Option[String]): Request[IO] =
    session.foldLeft(
      Request[IO](Method.POST, uri"/sse/action/home/light/toggle/light.a")
    )((r, id) => r.addCookie(AuthSessions.CookieName, id))

  private def toggle(w: Wiring, session: Option[String]): IO[Json] =
    w.calls.call(req(session), "light", "toggle", "light.a", Json.obj())

  /** A stored token that is good for another half hour. */
  private def freshAccess: IO[HaAccess] =
    IO.realTimeInstant.map(now => HaAccess("stored", now.plusSeconds(1800)))

  // ---------------------------------------------------------------------------

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
      // Not merely unused — never even opened. A socket per action is the cost
      // this shape pays, and a request with no session must not pay it.
      assertEquals(opened, Nil, clue = opened)
    }
  }

  test("a logged-in tap opens a connection with THAT person's token") {
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
      // The whole point: the shared connection, which is the add-on's identity,
      // did not run it.
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
      // Minted once, used twice — the storing is what the second tap proves.
      assertEquals(opened, List("minted", "minted"), clue = opened)
      assertEquals(stored.flatMap(_.access).map(_.token), Some("minted"))
      // ...and the refresh token HA rotated in came with it.
      assertEquals(stored.map(_.refresh), Some("r2"))
    }
  }

  test("a token about to expire is replaced rather than raced") {
    for {
      w <- wiring(haStub(renewed))
      now <- IO.realTimeInstant
      // Inside the margin: still valid this instant, and not valid for long
      // enough to survive a connect. A handshake that loses that race is
      // answered `auth_invalid`, which the transport reports as an ordinary
      // connect failure — there is no way to tell it from a dead network
      // afterwards, so the margin is what keeps them apart.
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
      // Both halves, and they belong together: a dead grant is not one refused
      // button, it is this person being logged out. Dropping the session is
      // what cuts their open streams (`AuthSessions.watch`), so the page stops
      // with the tap rather than staying live around a dead control.
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
      // `verifiedAt` means "when HA last confirmed this user's ROLE", and
      // minting a token confirms nothing about it — the periodic sweep
      // re-reads the user, and that is what may move this. Stamping it here
      // would push the sweep further out on every button press, so the
      // busiest dashboard would be the one whose demoted admin kept access
      // longest.
      assertEquals(after, before, clue = (before, after))
  }

  /** Not a behaviour of the code so much as a property of the shape it stores:
    * the session file already holds the refresh token, which mints these on
    * demand and does not expire. Keeping the short-lived one beside it widens
    * nothing.
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
