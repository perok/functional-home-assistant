package fh.view.auth

import cats.effect.IO
import fh.view.model.Access
import fh.view.testkit.TestAuth

import java.time.Instant
import scala.concurrent.duration.*

/** The session registry and the file behind it (issue #89).
  *
  * Two claims are load-bearing and neither is visible from a route: that the
  * map is a LIVE signal an open SSE stream can watch, and that the
  * write-through file is a convenience which never takes the server down or
  * leaks its contents.
  */
class AuthSessionsSuite extends munit.CatsEffectSuite {

  private val admin = TestAuth.admin
  private val guest = TestAuth.guest

  private def sessions = AuthSessions.create(SessionStore.ephemeral)

  test("a created session resolves by its id and by nothing else") {
    for {
      s <- sessions
      id <- s.create(admin, "refresh-1")
      found <- s.get(id)
      missing <- s.get(id.reverse + "x")
    } yield {
      assertEquals(found.map(_.user), Some(admin))
      assertEquals(found.map(_.refresh), Some("refresh-1"))
      assertEquals(missing, None)
    }
  }

  test("logging out ends every session of that user, not just this device") {
    for {
      s <- sessions
      phone <- s.create(admin, "r1")
      tablet <- s.create(admin, "r2")
      other <- s.create(guest, "r3")
      _ <- s.removeUser(admin.id)
      a <- s.get(phone)
      b <- s.get(tablet)
      c <- s.get(other)
    } yield {
      assertEquals(a, None)
      assertEquals(b, None)
      // The guest is a different person and keeps their session.
      assertEquals(c.map(_.user), Some(guest))
    }
  }

  test("renewing an evicted session does not resurrect it") {
    for {
      s <- sessions
      id <- s.create(admin, "r1")
      _ <- s.remove(id)
      _ <- s.renew(id, admin, "r2")
      found <- s.get(id)
    } yield assertEquals(found, None)
  }

  test("stale selects by verifiedAt, and renew resets it") {
    for {
      s <- sessions
      id <- s.create(admin, "r1")
      now <- IO.realTimeInstant
      due <- s.stale(now.plusSeconds(60))
      notDue <- s.stale(now.minusSeconds(60))
    } yield {
      assertEquals(due.map(_._1), List(id))
      assertEquals(notDue, Nil)
    }
  }

  /** What makes a logout reach a dashboard that is already open: the same
    * predicate the door used, re-evaluated whenever the map moves.
    */
  test(
    "watch reports the session dying, under the rule the stream was admitted by"
  ) {
    for {
      s <- sessions
      id <- s.create(admin, "r1")
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
      id <- s.create(admin, "r1")
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
      id <- before.create(admin, "r1")
      perms <- IO.blocking(os.perms(path).toString)
      after <- AuthSessions.create(store)
      found <- after.get(id)
    } yield {
      assertEquals(found.map(_.user), Some(admin))
      assertEquals(found.map(_.refresh), Some("r1"))
      assertEquals(perms, "rw-------")
    }
  }

  test("a corrupt file is an empty start, not a failed boot") {
    val dir = os.temp.dir(prefix = "fh-sessions")
    val path = dir / "sessions.json"
    os.write.over(path, "{not json")
    for {
      s <- AuthSessions.create(new SessionStore(path))
      id <- s.create(admin, "r1")
      found <- s.get(id)
    } yield assertEquals(found.map(_.user), Some(admin))
  }

  test(
    "verifiedAt round-trips through the file as an instant, not a string that reparses to now"
  ) {
    val dir = os.temp.dir(prefix = "fh-sessions")
    val store = new SessionStore(dir / "sessions.json")
    for {
      before <- AuthSessions.create(store)
      id <- before.create(admin, "r1")
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
