package fh.view.functional

import cats.effect.IO
import fh.view.model.Access
import fh.view.testkit.{FixtureDashboard, HouseFixture, TestAuth}
import org.http4s.Status
import org.http4s.headers.Location
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** The gate against a real running dashboard (issue #89).
  *
  * [[fh.view.auth.AuthGateSuite]] checks what each route REQUIRES; this checks
  * what the server then does about it — which is a different claim, and the one
  * a browser experiences. Three things it pins that a pure classifier cannot:
  * that the denial SHAPE differs by caller (a page is redirected, a stream is
  * not), that the rule is read from the live dashboard rather than a copy, and
  * that admission is not a one-time event — an SSE stream already running stops
  * when the session behind it does.
  */
class AuthGateBehaviourSuite extends FunctionalSuite {

  private val kitchen = HouseFixture.kitchenLight
  private def house = scene.card(FixtureDashboard.light("Kitchen", kitchen))

  test("an anonymous page request is sent to login, carrying where it was going") {
    withServer(house) { ts =>
      ts.pageResponse(as = None).map { resp =>
        assertEquals(resp.status, Status.SeeOther)
        val to = resp.headers.get[Location].map(_.uri)
        assertEquals(to.map(_.path.renderString), Some("/auth/login"))
        assertEquals(
          to.flatMap(_.query.params.get("next")),
          Some(s"/d/${ts.slug}")
        )
      }
    }
  }

  /** A 303 on a stream reports as a broken connection, not as "log in" — so the
    * page shell needs a status it can act on instead.
    */
  test("an anonymous SSE request is refused, never redirected") {
    withServer(house) { ts =>
      ts.sse(as = None).map { resp =>
        assertEquals(resp.status, Status.Unauthorized)
        assertEquals(
          resp.headers.get(CIString("X-FH-Login")).map(_.head.value),
          Some("/auth/login")
        )
      }
    }
  }

  test("a logged-in user who simply lacks the role gets 403, not a redirect loop") {
    withServer(house, Access.Admin) { ts =>
      for {
        guest <- ts.auth.sessionFor(TestAuth.guest)
        resp <- ts.pageResponse(as = Some(guest))
      } yield assertEquals(resp.status, Status.Forbidden)
    }
  }

  test("a public dashboard needs no login at all — the wall-tablet case") {
    withServer(house, Access.Public) { ts =>
      ts.pageResponse(as = None).map(r => assertEquals(r.status, Status.Ok))
    }
  }

  test("a users rule admits the named user and refuses the admin who is not named") {
    withServer(house, Access.Users(List(TestAuth.guest.id))) { ts =>
      for {
        guest <- ts.auth.sessionFor(TestAuth.guest)
        allowed <- ts.pageResponse(as = Some(guest))
        // The harness default is an admin, and `Users` is deliberately literal.
        refused <- ts.pageResponse()
      } yield {
        assertEquals(allowed.status, Status.Ok)
        assertEquals(refused.status, Status.Forbidden)
      }
    }
  }

  test("an action POST follows its connection's dashboard, not its own URL") {
    withServer(house, Access.Admin) { ts =>
      for {
        guest <- ts.auth.sessionFor(TestAuth.guest)
        status <- ts.post(
          s"sse/action/light/toggle/${kitchen.entityId}",
          as = Some(guest)
        )
      } yield assertEquals(status, Status.Forbidden)
    }
  }

  /** The claim the whole `SignallingRef` design exists for. A stream is
    * admitted once and then runs for hours, so a check only at the door would
    * leave a revoked user watching a live dashboard until they reloaded.
    */
  test("logging out cuts an SSE stream that is already open") {
    withServer(house) { ts =>
      ts.sse().flatMap { resp =>
        for {
          // Wait until the stream is genuinely live before revoking, so the
          // test cannot pass on a connection that never started.
          fiber <- resp.body.compile.drain.start
          _ <- ts.awaitLive()
          _ <- ts.auth.revokeDefault
          // It ENDS rather than erroring: `interruptWhen` completes the stream,
          // which the browser sees as a closed connection and retries — landing
          // on the 401 above.
          _ <- fiber.joinWithNever.timeout(10.seconds)
        } yield ()
      }
    }
  }

  test("a public stream is not cut when some unrelated session ends") {
    withServer(house, Access.Public) { ts =>
      ts.sse(as = None).flatMap { resp =>
        for {
          fiber <- resp.body.compile.drain.start
          _ <- ts.awaitLive()
          _ <- ts.auth.revokeDefault
          still <- fiber.joinWithNever.timeout(1.second).attempt
        } yield assert(still.isLeft, "a public stream must survive a logout")
      }
    }
  }
}
