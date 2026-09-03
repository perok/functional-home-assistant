package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import fh.view.testkit.{FakeConfig, FakeHomeAssistant}
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*

import scala.concurrent.duration.*

/** What happens when several asks for the SAME entity are in flight at once.
  *
  * This is the baseline for a proposal, and says so rather than pretending to
  * be a requirement: the server could hold one action per (entity, value key),
  * letting only the LATEST through and completing the superseded askers with
  * the winner's result. Today it does not — every ask is its own fiber, calls
  * HA, and answers on its own. That is what is pinned here, so the day
  * coalescing lands this test fails and has to be rewritten deliberately
  * instead of quietly continuing to describe a design that is gone.
  *
  * What this CANNOT settle, and no test in this repo can: whether Home
  * Assistant itself serialises per entity. The fake stands in for the WS API,
  * not for HA's own execution, so the question of whether two overlapping
  * `light.turn_on`s can land out of order INSIDE HA needs the live instance.
  * That is the measurement worth taking before building anything.
  *
  * Note what does NOT generate this case: one control tapped twice. ADR 0019's
  * busy guard makes a second click a no-op while the first is outstanding, and
  * the slider's commit signal additionally `disabled`s the input. It takes two
  * different controls on one entity — a group row and its master, a card and
  * the popup showing the same light — or two clients.
  */
class ActionConcurrencySuite extends ServerHarness {

  override protected def simulateTime: Boolean = false

  private def setBrightness(v: Int): Request[IO] =
    Request[IO](
      Method.POST,
      Uri.unsafeFromString(
        s"/sse/action/dashboard/light/turn_on/sensor.a/brightness/$v"
      )
    )

  test("overlapping asks for one entity ALL reach HA, and each answers itself") {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(liveLeafDash))
      )
      sessions <- Sessions.create
      // The delay is what makes them OVERLAP rather than queue: each call is
      // still held when the next arrives, so all three are outstanding at once.
      fake <- FakeHomeAssistant.create(Nil, FakeConfig(callDelay = 300.millis))
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          val app = server.routes.orNotFound
          for {
            // Started in order and spaced enough that the ORDER is a fact
            // about the test rather than a race: what is being measured is
            // how many survive, not who wins a tie.
            f1 <- app.run(setBrightness(10)).start
            _ <- IO.sleep(50.millis)
            f2 <- app.run(setBrightness(120)).start
            _ <- IO.sleep(50.millis)
            f3 <- app.run(setBrightness(200)).start
            r1 <- f1.joinWithNever
            r2 <- f2.joinWithNever
            r3 <- f3.joinWithNever
            calls <- fake.recordedCalls
          } yield (List(r1.status, r2.status, r3.status), calls)
        }
    } yield out).timeout(30.seconds).map { case (statuses, calls) =>
      // Every ask succeeded on its own terms — none was superseded, none was
      // told anything about the others.
      assertEquals(statuses, List.fill(3)(Status.NoContent))
      // …and every one of them drove the device.
      assertEquals(
        calls.map(_.serviceData.noSpaces).toList,
        List(
          """{"brightness":10}""",
          """{"brightness":120}""",
          """{"brightness":200}"""
        ),
        clue = calls
      )
    }
  }
}
