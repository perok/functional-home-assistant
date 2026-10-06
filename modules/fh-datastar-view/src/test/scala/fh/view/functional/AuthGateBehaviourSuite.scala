package fh.view.functional

import cats.effect.IO
import fh.view.model.Access
import fh.view.testkit.{
  DashboardBuilders,
  FixtureDashboard,
  HouseFixture,
  TestAuth
}
import org.http4s.Status
import org.http4s.headers.Location

import scala.concurrent.duration.*

/** The gate against a running dashboard (issue #89).
  * [[fh.view.auth.AuthGateSuite]] checks what each route requires; this checks
  * what the server does: the denial shape differs by caller, the rule is read
  * from the live dashboard, and a running SSE stream stops when its session
  * does.
  */
class AuthGateBehaviourSuite extends FunctionalSuite {

  private val kitchen = HouseFixture.kitchenLight
  private def house = scene.card(
    DashboardBuilders.col(
      FixtureDashboard.light("Kitchen", kitchen),
      FixtureDashboard.call("light/toggle", kitchen)
    )
  )

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

  /** A stream's page was already admitted, so a refusal means the session died;
    * the redirect belongs on the page load, where a human is waiting.
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
        // The harness default is an admin, and `Users` is literal.
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
          s"sse/call/${ts.slug}/light/toggle/entity/${kitchen.entityId}",
          as = Some(guest)
        )
      } yield assertEquals(status, Status.Forbidden)
    }
  }

  /** Otherwise `Public` plus a call route forwarding any `entity_id` would put
    * a wall tablet's front door one URL edit from the street.
    */
  test("an action may not make a call its dashboard does not declare") {
    withServer(house, Access.Public) { ts =>
      for {
        onDashboard <- ts.post(
          s"sse/call/${ts.slug}/light/toggle/entity/${kitchen.entityId}",
          as = None
        )
        elsewhere <- ts.postResult(
          s"sse/call/${ts.slug}/lock/unlock/entity/lock.front_door?node=c_0",
          as = None
        )
        calls <- ts.fake.recordedCalls
      } yield {
        assertEquals(onDashboard, Status.NoContent)
        // 200 carrying signals (ADR 0024), so the message is asserted.
        assertEquals(elsewhere._1, Status.Ok)
        assert(
          elsewhere._2.contains("_c_0__error") &&
            elsewhere._2.contains(
              "no tap on this dashboard calls lock/unlock on entity lock.front_door"
            ),
          s"the refusal said nothing the page can show: ${elsewhere._2}"
        )
        // Refused before HA hears about it.
        assert(
          !calls.exists(_.toString.contains("front_door")),
          s"the refused action still reached HA: $calls"
        )
      }
    }
  }

  /** The restrictive default, not whichever dashboard is public, so inventing a
    * slug is no way around the check.
    */
  test("an action naming a dashboard that does not exist is refused") {
    withServer(house, Access.Public) { ts =>
      ts.post(
        s"sse/call/nosuch/light/toggle/entity/${kitchen.entityId}",
        as = None
      ).map(assertEquals(_, Status.Unauthorized))
    }
  }

  /** A stream runs for hours, so a check only at the door would leave a revoked
    * user watching. A cut stream stops updating but the tab still shows what it
    * had, so the last thing sent is `_reload`, which every page declares an
    * effect for.
    */
  test("logging out ends the stream, and says so on the way out") {
    withServer(house) { ts =>
      ts.sse().flatMap { resp =>
        for {
          // Genuinely live first, so it cannot pass on a stream that never
          // started.
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
