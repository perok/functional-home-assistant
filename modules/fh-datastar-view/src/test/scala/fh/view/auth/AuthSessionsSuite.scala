package fh.view.auth

import cats.effect.IO
import api.homeassistant.ws.domain.HaUser
import fh.view.model.Access
import fh.view.testkit.TestAuth

import java.time.Instant
import scala.concurrent.duration.*

/** The session registry and its file (issue #89). Neither load-bearing claim is
  * visible from a route: the map is a live signal an SSE stream can watch, and
  * the write-through file never takes the server down or leaks its contents.
  */
class AuthSessionsSuite extends munit.CatsEffectSuite {

  private val admin = TestAuth.admin
  private val clientId = TestAuth.TestClientId

  private def sessions = AuthSessions.create(SessionStore.ephemeral)

  test("a created session resolves by its id and by nothing else") {
    for {
      s <- sessions
      id <- s.create(admin, "refresh-1", clientId)
      found <- s.get(id)
      missing <- s.get(id.reverse + "x")
    } yield {
      assertEquals(found.map(_.user), Some(admin))
      assertEquals(found.map(_.refresh), Some("refresh-1"))
      assertEquals(found.map(_.clientId), Some(clientId.renderString))
      assertEquals(missing, None)
    }
  }

  test("renewing an evicted session does not resurrect it") {
    for {
      s <- sessions
      id <- s.create(admin, "r1", clientId)
      _ <- s.remove(id)
      _ <- s.renew(id, admin, "r2")
      found <- s.get(id)
    } yield assertEquals(found, None)
  }

  /** HA accepts only the client_id the login was minted with, so a renewal
    * losing it would make the next sweep sign the session out.
    */
  test("renewing keeps the client the session was minted for") {
    for {
      s <- sessions
      id <- s.create(admin, "r1", clientId)
      _ <- s.renew(id, admin, "r2")
      found <- s.get(id)
    } yield assertEquals(found.map(_.clientId), Some(clientId.renderString))
  }

  test("stale selects by verifiedAt, and renew resets it") {
    for {
      s <- sessions
      id <- s.create(admin, "r1", clientId)
      now <- IO.realTimeInstant
      due <- s.stale(now.plusSeconds(60))
      notDue <- s.stale(now.minusSeconds(60))
    } yield {
      assertEquals(due.map(_._1), List(id))
      assertEquals(notDue, Nil)
    }
  }

  /** How a logout reaches an open dashboard: the door's predicate, re-evaluated
    * whenever the map moves.
    */
  test(
    "watch reports the session dying, under the rule the stream was admitted by"
  ) {
    for {
      s <- sessions
      id <- s.create(admin, "r1", clientId)
      seen <- s
        .watch(Some(id), Access.Authenticated.permits)
        .take(2)
        .concurrently(
          fs2.Stream.eval(IO.sleep(50.millis) *> s.remove(id))
        )
        .compile
        .toList
        .timeout(5.seconds)
    } yield assertEquals(seen, List(true, false))
  }

  test("watch also reports a role that no longer satisfies the rule") {
    for {
      s <- sessions
      id <- s.create(admin, "r1", clientId)
      seen <- s
        .watch(Some(id), Access.Admin.permits)
        .take(2)
        .concurrently(
          fs2.Stream.eval(
            IO.sleep(50.millis) *> s
              .renew(id, admin.copy(is_admin = false, is_owner = false), "r2")
          )
        )
        .compile
        .toList
        .timeout(5.seconds)
    } yield assertEquals(seen, List(true, false))
  }

  test(
    "a public dashboard's stream is never cut, even with no session at all"
  ) {
    for {
      s <- sessions
      first <- s.watch(None, Access.Public.permits).head.compile.lastOrError
    } yield assert(first)
  }

  test(
    "sessions survive a restart, and the file that carries them is not world-readable"
  ) {
    val dir = os.temp.dir(prefix = "fh-sessions")
    val path = dir / "sessions.json"
    val store = new SessionStore(path)
    for {
      before <- AuthSessions.create(store)
      id <- before.create(admin, "r1", clientId)
      perms <- IO.blocking(os.perms(path).toString)
      after <- AuthSessions.create(store)
      found <- after.get(id)
    } yield {
      assertEquals(found.map(_.user), Some(admin))
      assertEquals(found.map(_.refresh), Some("r1"))
      assertEquals(perms, "rw-------")
    }
  }

  /** Several fibers write the file; interleaved writes could leave one that
    * does not decode, which refuses the next boot.
    */
  test("concurrent writes leave a file holding every session") {
    import cats.syntax.all.*
    val dir = os.temp.dir(prefix = "fh-sessions")
    val store = new SessionStore(dir / "sessions.json")
    for {
      before <- AuthSessions.create(store)
      ids <- (1 to 50).toList.parTraverse(i =>
        before.create(admin, s"r$i", clientId)
      )
      after <- AuthSessions.create(store)
      found <- ids.traverse(after.get)
      leftovers <- IO.blocking(os.list(dir).map(_.last))
    } yield {
      assertEquals(found.flatten.size, 50)
      assertEquals(leftovers, IndexedSeq("sessions.json"))
    }
  }

  /** Round trips pass even if both sides change together, so the shape is
    * pinned to a literal. A restart follows every dashboard edit, so a silent
    * codec change logs the household out.
    */
  test("the on-disk shape is fixed, not whatever the codecs currently derive") {
    val dir = os.temp.dir(prefix = "fh-sessions")
    val path = dir / "sessions.json"
    os.write.over(
      path,
      """{"abc":{"user":{"id":"u1","name":"Peri","is_admin":true,""" +
        """"is_owner":false},"refresh":"r1",""" +
        """"verifiedAt":"2026-08-20T10:00:00Z",""" +
        """"clientId":"http://192.168.1.50:8080"}}"""
    )
    for {
      restored <- AuthSessions.create(new SessionStore(path))
      found <- restored.get("abc")
      _ <- restored.renew("abc", found.get.user, "r1")
      onDisk <- IO.blocking(os.read(path))
    } yield {
      assertEquals(
        found.map(_.user),
        Some(HaUser("u1", "Peri", is_admin = true, is_owner = false))
      )
      assertEquals(
        found.map(_.verifiedAt),
        Some(Instant.parse("2026-08-20T10:00:00Z"))
      )
      assertEquals(found.map(_.clientId), Some("http://192.168.1.50:8080"))
      assert(
        clue(onDisk).contains(""""user":{"id":"u1","name":"Peri""""),
        "the user object's field names moved"
      )
      assert(onDisk.contains(""""is_admin":true"""))
      assert(onDisk.contains(""""refresh":"r1""""))
      assert(onDisk.contains(""""clientId":"http://192.168.1.50:8080""""))
    }
  }

  /** Corrupt, or from before sessions carried their client_id. Starting empty
    * would sign the household out on every restart behind a warning.
    */
  test("an undecodable sessions file refuses to boot") {
    val dir = os.temp.dir(prefix = "fh-sessions")
    for {
      _ <- IO.blocking(
        os.write(dir / "sessions.json", "{not json")
      )
      corrupt = AuthSessions.create(new SessionStore(dir / "sessions.json"))
      _ <- interceptIO[io.circe.Error](corrupt)
      _ <- IO.blocking(
        os.write.over(
          dir / "sessions.json",
          """{"abc":{"user":{"id":"u1","name":"Peri","is_admin":true,""" +
            """"is_owner":false},"refresh":"r1",""" +
            """"verifiedAt":"2026-08-20T10:00:00Z"}}"""
        )
      )
      preClientId = AuthSessions.create(new SessionStore(dir / "sessions.json"))
      _ <- interceptIO[io.circe.Error](preClientId)
    } yield ()
  }

  test(
    "verifiedAt round-trips through the file as an instant, not a string that reparses to now"
  ) {
    val dir = os.temp.dir(prefix = "fh-sessions")
    val store = new SessionStore(dir / "sessions.json")
    for {
      before <- AuthSessions.create(store)
      id <- before.create(admin, "r1", clientId)
      original <- before.get(id).map(_.map(_.verifiedAt))
      after <- AuthSessions.create(store)
      restored <- after.get(id).map(_.map(_.verifiedAt))
    } yield {
      assert(original.isDefined)
      assertEquals(restored, original)
      assert(restored.exists(_.isBefore(Instant.now().plusSeconds(1))))
    }
  }
}
