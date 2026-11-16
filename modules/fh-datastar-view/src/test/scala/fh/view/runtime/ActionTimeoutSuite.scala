package fh.view.runtime

import cats.effect.IO
import fh.view.testkit.{DashboardBuilders, FixtureDashboard, FixtureEntity}
import fh.view.testkit.DashboardBuilders.st
import io.circe.Json
import org.http4s.Status

import scala.concurrent.duration.*

/** A press is answered within [[Server.CallTimeout]] however long HA takes
  * (#152): a hung call is a refusal the toast reports, not a control left busy.
  * Simulated time, so the deadline costs no wall clock.
  */
class ActionTimeoutSuite extends ServerHarness {

  override protected def simulateTime: Boolean = true

  private val sensor = FixtureEntity("sensor.a", "warm")

  private val callNode = FixtureDashboard.call("light/toggle", sensor)

  private val renderer = Renderer.create(
    liveLeafDash.copy(
      cards = liveLeafDash.cards + ("call" -> FixtureDashboard.cards("call")),
      card = DashboardBuilders.col(liveLeafDash.card, callNode)
    )
  )

  /** A press against an HA whose call does `ha`: the answer and how long it
    * took.
    */
  private def press(ha: IO[Unit]): IO[(Status, Json, FiniteDuration)] = {
    val calls: ServiceCalls = (_, _, _, _, _) => ha
    IO.ref(Option.empty[(Status, String, FiniteDuration)]).flatMap { out =>
      liveWorldOf(
        renderer,
        Map("sensor.a" -> st("sensor.a", "warm")),
        Some(calls)
      )(world =>
        world
          .post(FixtureDashboard.pressOf("dashboard", callNode, "c_1"))
          .timed
          .flatMap { case (took, (status, body)) =>
            out.set(Some((status, body, took)))
          }
      ) *> out.get.map {
        case Some((status, body, took)) =>
          val json =
            if body.isEmpty then Json.Null
            else io.circe.parser.parse(body).fold(throw _, identity)
          (status, json, took)
        case None => fail("the press was never answered")
      }
    }
  }

  private def signal(json: Json, name: String): Option[String] =
    json.hcursor.downField(name).as[String].toOption

  test("a call HA never answers is refused at the deadline, with the toast") {
    press(IO.never).map { (status, json, took) =>
      assertEquals(status, Status.Ok)
      assertEquals(took, Server.CallTimeout)
      assertEquals(
        signal(json, Server.ToastSignal),
        Some(Server.CallTimeoutMessage)
      )
      assertEquals(signal(json, "_c_1__error"), Some(Server.CallTimeoutMessage))
    }
  }

  test("a call answered just inside the deadline is a plain success") {
    press(IO.sleep(Server.CallTimeout - 1.milli)).map { (status, _, _) =>
      assertEquals(status, Status.NoContent)
    }
  }

  test("a call HA refuses in time keeps HA's own message") {
    press(IO.raiseError(new RuntimeException("entity is unavailable"))).map {
      (status, json, _) =>
        assertEquals(status, Status.Ok)
        assertEquals(
          signal(json, Server.ToastSignal),
          Some("entity is unavailable")
        )
    }
  }
}
