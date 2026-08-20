package fh.view.functional

import cats.effect.IO
import fh.view.model.Access
import fh.view.testkit.{FixtureDashboard, HouseFixture, TestAuth}
import org.http4s.Status
import org.http4s.headers.Location

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

  test(
    "an anonymous page request is sent to login, carrying where it was going"
  ) {
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

  /** A stream is opened by a page that was already admitted, so a refusal here
    * is a genuine error — the session died — not a "you should log in". The
    * redirect belongs on the page load, where a human is waiting.
    */
  test("an anonymous SSE request is refused, never redirected") {
    withServer(house) { ts =>
      ts.sse(as = None)
        .map(resp => assertEquals(resp.status, Status.Unauthorized))
    }
  }

  test(
    "a logged-in user who simply lacks the role gets 403, not a redirect loop"
  ) {
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

  test(
    "a users rule admits the named user and refuses the admin who is not named"
  ) {
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

  test("an action POST is held to the rule of the dashboard it names") {
    withServer(house, Access.Admin) { ts =>
      for {
        guest <- ts.auth.sessionFor(TestAuth.guest)
        status <- ts.post(
          s"sse/action/${ts.slug}/light/toggle/${kitchen.entityId}",
          as = Some(guest)
        )
      } yield assertEquals(status, Status.Forbidden)
    }
  }

  /** The escalation `Public` would otherwise open: no login, and an action
    * route that forwards any `entity_id` straight to `call_service`. A wall
    * tablet would put the front door one URL edit away from the street.
    */
  test("an action may not touch an entity its dashboard does not name") {
    withServer(house, Access.Public) { ts =>
      for {
        onDashboard <- ts.post(
          s"sse/action/${ts.slug}/light/toggle/${kitchen.entityId}",
          as = None
        )
        elsewhere <- ts.post(
          s"sse/action/${ts.slug}/lock/unlock/lock.front_door",
          as = None
        )
        calls <- ts.fake.recordedCalls
      } yield {
        assertEquals(onDashboard, Status.NoContent)
        assertEquals(elsewhere, Status.Forbidden)
        // Refused BEFORE Home Assistant hears about it, not merely reported as
        // an error afterwards.
        assert(
          !calls.exists(_.toString.contains("front_door")),
          s"the refused action still reached HA: $calls"
        )
      }
    }
  }

  /** A slug that names nothing gets the RESTRICTIVE default rather than the
    * rule of whatever dashboard happens to be public — so inventing a slug is
    * not a way around the check.
    */
  test("an action naming a dashboard that does not exist is refused") {
    withServer(house, Access.Public) { ts =>
      ts.post(s"sse/action/nosuch/light/toggle/${kitchen.entityId}", as = None)
        .map(assertEquals(_, Status.Unauthorized))
    }
  }

  /** The claim the whole `SignallingRef` design exists for. A stream is
    * admitted once and then runs for hours, so a check only at the door would
    * leave a revoked user watching a live dashboard until they reloaded.
    */
  /** Cutting the stream stops the dashboard UPDATING; the tab still SHOWS what
    * it last received. So the last thing the stream sends is the `_reload`
    * signal every page already declares an effect for — that is what actually
    * takes the house off a signed-out screen.
    */
  test("logging out ends the stream, and says so on the way out") {
    withServer(house) { ts =>
      ts.sse().flatMap { resp =>
        for {
          // Wait until the stream is genuinely live before revoking, so the
          // test cannot pass on a connection that never started.
          seen <- resp.body
            .through(fs2.text.utf8.decode)
            .compile
            .string
            .start
          _ <- ts.awaitLive()
          _ <- ts.auth.revokeDefault
          text <- seen.joinWithNever.timeout(10.seconds)
        } yield assert(
          clue(text).contains("_reload"),
          "the stream closed without telling the client to reload"
        )
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
