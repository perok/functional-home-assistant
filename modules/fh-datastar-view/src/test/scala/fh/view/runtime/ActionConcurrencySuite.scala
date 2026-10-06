package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.testkit.{
  DashboardBuilders,
  FakeConfig,
  FixtureDashboard,
  FixtureEntity
}
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

  private val sensor = FixtureEntity("sensor.a", "warm")

  /** Declares the call, or the server refuses it (ADR 0023). */
  private val dash = liveLeafDash.copy(
    cards = liveLeafDash.cards + ("call" -> FixtureDashboard.cards("call")),
    card = DashboardBuilders.col(
      liveLeafDash.card,
      FixtureDashboard.call("light/turn_on", sensor, Some("brightness"))
    )
  )

  test(
    "overlapping asks for one entity ALL reach HA, and each answers itself"
  ) {
    TestServer
      .resource(
        dash,
        List(sensor),
        // Each call is still held when the next arrives, so all three overlap.
        config = FakeConfig(callDelay = 300.millis)
      )
      .use { ts =>
        def setBrightness(v: Int) =
          ts.post(
            s"sse/call/${ts.slug}/light/turn_on/entity/sensor.a/brightness/$v"
          )
        for {
          // Arrival order at HA is not asserted; see below.
          f1 <- setBrightness(10).start
          _ <- IO.sleep(50.millis)
          f2 <- setBrightness(120).start
          _ <- IO.sleep(50.millis)
          f3 <- setBrightness(200).start
          statuses <- List(f1, f2, f3).traverse(_.joinWithNever)
          calls <- ts.fake.recordedCalls
        } yield (statuses, calls)
      }
      .timeout(30.seconds)
      .map { (statuses, calls) =>
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
