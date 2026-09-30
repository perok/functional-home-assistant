package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*

import scala.concurrent.duration.*

class BearerUsersSuite extends munit.CatsEffectSuite {

  private val peri = HaUser("u1", "Peri", true, true)
  private val guest = HaUser("u2", "Heidi", false, false)

  private val household: String => IO[HaUser] = {
    case "peri"  => IO.pure(peri)
    case "guest" => IO.pure(guest)
    case _       => IO.raiseError(new Exception("auth_invalid"))
  }

  private def counting(calls: Ref[IO, Int]): String => IO[HaUser] =
    token => calls.update(_ + 1) *> household(token)

  test("a machine polling with one token asks HA once, not once per request") {
    IO.ref(0).flatMap { calls =>
      BearerUsers
        .cached(counting(calls), 1.hour)
        .flatMap(users =>
          (users("peri"), users("peri"), users("guest"), users("peri"))
            .mapN((_, _, _, _))
            .product(calls.get)
        )
        .map { case (users, count) =>
          assertEquals(users, (peri, peri, guest, peri))
          assertEquals(count, 2)
        }
    }
  }

  test("a rejected token is not remembered — each attempt is asked again") {
    IO.ref(0).flatMap { calls =>
      BearerUsers
        .cached(counting(calls), 1.hour)
        .flatMap(users =>
          (users("junk").attempt, users("junk").attempt).tupled
            .product(calls.get)
        )
        .map { case ((first, second), count) =>
          assert(first.isLeft && second.isLeft)
          assertEquals(count, 2)
        }
    }
  }

  test("a resolved token is asked again once its answer is stale") {
    IO.ref(0).flatMap { calls =>
      BearerUsers
        .cached(counting(calls), Duration.Zero)
        .flatMap(users => users("peri") *> users("peri") *> calls.get)
        .map(assertEquals(_, 2))
    }
  }

  test("junk tokens open at most `maxLookups` lookups against HA at once") {
    (IO.ref(0), IO.ref(0), Deferred[IO, Unit])
      .flatMapN { (inFlight, peak, release) =>
        val slowHa: String => IO[HaUser] = _ =>
          inFlight
            .updateAndGet(_ + 1)
            .flatMap(n => peak.update(_.max(n))) *>
            release.get *>
            inFlight.update(_ - 1) *>
            IO.raiseError(new Exception("auth_invalid"))
        BearerUsers.cached(slowHa, 1.hour, maxLookups = 2).flatMap { users =>
          (
            (1 to 6).toList.parTraverse(i => users(s"junk-$i").attempt),
            // Room for a third to start if the cap did not hold.
            inFlight.get.iterateUntil(_ == 2) *> IO.sleep(50.millis) *>
              release.complete(())
          ).parTupled *> peak.get
        }
      }
      .map(assertEquals(_, 2))
  }
}
