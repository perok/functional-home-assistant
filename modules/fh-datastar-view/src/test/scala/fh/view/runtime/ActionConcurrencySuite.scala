package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import fh.view.testkit.{FakeConfig, FakeHomeAssistant}
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*

import scala.concurrent.duration.*

/** Several asks for the same entity in flight at once. A baseline for a
  * proposal, not a requirement: the server could hold one action per (entity,
  * value key) and let only the latest through. Today every ask is its own fiber
  * and answers on its own; the day coalescing lands this fails and is rewritten
  * deliberately.
  *
  * No test here can settle whether HA itself serialises per entity; that needs
  * the live instance, and is the measurement to take before building anything.
  * One control tapped twice does not produce this (ADR 0019's busy guard); two
  * controls on one entity, or two clients, do.
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

  test(
    "overlapping asks for one entity ALL reach HA, and each answers itself"
  ) {
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(liveLeafDash))
      )
      sessions <- Sessions.create
      // Each call is still held when the next arrives, so all three overlap.
      fake <- FakeHomeAssistant.create(Nil, FakeConfig(callDelay = 300.millis))
      out <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          val app = server.routes.orNotFound
          for {
            // Arrival order at HA is not asserted; see below.
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
      // None was superseded or told about the others.
      assertEquals(statuses, List.fill(3)(Status.NoContent))
      // A set: asserting order went red under a full-suite run, since
      // overlapping asks 50 ms apart reach HA in any order on a loaded machine.
      // That is the reordering hazard coalescing would remove, and why order
      // would pin a race.
      assertEquals(
        calls.map(_.serviceData.noSpaces).toSet,
        Set(
          """{"brightness":10}""",
          """{"brightness":120}""",
          """{"brightness":200}"""
        ),
        clue = calls
      )
    }
  }
}
