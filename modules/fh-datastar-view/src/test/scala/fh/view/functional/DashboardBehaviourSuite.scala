package fh.view.functional

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.runtime.TestServer
import fh.view.testkit.{FixtureDashboard, HouseFixture, ServiceCall}
import io.circe.Json

import scala.concurrent.duration.*

/** End-to-end behaviour of the dashboard against a stubbed Home Assistant: the
  * whole loop — seed snapshot -> `StateStore` -> `Server` -> HTTP/SSE, and
  * control -> `callService` — with no live HA and a scripted timeline. Each test
  * reads as an observable behaviour, asserted at the HTTP boundary.
  */
class DashboardBehaviourSuite extends munit.FunSuite {

  private val dashboard = FixtureDashboard.dashboard
  private val house = HouseFixture.all

  /** Run `f` against a freshly-wired [[TestServer]], with a global timeout so a
    * missed SSE fragment fails fast rather than hanging.
    */
  private def withServer[A](f: TestServer => IO[A]): A =
    TestServer
      .resource(dashboard, house)
      .use(f)
      .timeout(45.seconds)
      .unsafeRunSync()

  test("initial page render reflects the seeded snapshot") {
    val html = withServer(_.page)
    // The numeric reading and its unit (pulled from $attr) are present...
    assert(html.contains("12.4"), clue = html)
    assert(html.contains("°C"), clue = html)
    // ...and the kitchen light's seeded state.
    assert(html.contains("Kitchen: "), clue = html)
    assert(html.contains(">on<"), clue = html)
  }

  test("a state change pushes a fragment carrying the new value") {
    withServer { ts =>
      ts.observePatch(
        marker = "13.1",
        trigger = ts.fake.emit(
          HouseFixture.outsideTemp.entityId,
          "13.1",
          HouseFixture.outsideTemp.attributes
        )
      )
    }
  }

  test("turning the kitchen light off pushes its new state over SSE") {
    withServer { ts =>
      // A distinctive marker only the OFF light fragment can contain.
      ts.observePatch(
        marker = "Kitchen: <span>off</span>",
        trigger = ts.fake.emit(HouseFixture.kitchenLight.entityId, "off", Map.empty)
      )
    }
  }

  test("a no-op emit publishes nothing; the next real change is seen first") {
    // Pins StateStore's "publish only on real change" contract end-to-end: an
    // emit of the current value is dropped, so the FIRST observed change is the
    // subsequent real one — driven through the fake's queue, not a private seam.
    val firstState = withServer { ts =>
      for {
        firstChange <- ts.store.changes.take(1).compile.lastOrError.start
        _ <- ts.awaitChangeSubscribers(1)
        // No-op: same value the fixture already seeded -> dropped by update.
        _ <- ts.fake.emit(
          HouseFixture.outsideTemp.entityId,
          HouseFixture.outsideTemp.state,
          HouseFixture.outsideTemp.attributes
        )
        // A real change -> published.
        _ <- ts.fake.emit(HouseFixture.outsideTemp.entityId, "13.1", Map.empty)
        change <- firstChange.joinWithNever
      } yield change.current.state
    }
    assertEquals(firstState, "13.1")
  }

  test("a control click calls the service back into HA") {
    val calls = withServer { ts =>
      ts.post("sse/action/light/toggle/light.kitchen") *> ts.fake.recordedCalls
    }
    assertEquals(
      calls,
      Vector(ServiceCall("light", "toggle", "light.kitchen", Json.obj()))
    )
  }

  test("a value-carrying control passes its data through to HA") {
    val calls = withServer { ts =>
      ts.post("sse/action/light/turn_on/light.kitchen/brightness/200") *>
        ts.fake.recordedCalls
    }
    assertEquals(
      calls,
      Vector(
        ServiceCall(
          "light",
          "turn_on",
          "light.kitchen",
          Json.obj("brightness" -> Json.fromInt(200))
        )
      )
    )
  }

  test("round-trip: act on HA, then the consequent state reaches the browser") {
    withServer { ts =>
      for {
        _ <- ts.post("sse/action/light/turn_off/light.kitchen")
        _ <- ts.observePatch(
          marker = "Kitchen: <span>off</span>",
          // The fake records the call; HA's resulting state change is emitted
          // explicitly (the fake does not simulate HA semantics).
          trigger = ts.fake.emit(HouseFixture.kitchenLight.entityId, "off", Map.empty)
        )
        calls <- ts.fake.recordedCalls
      } yield assertEquals(
        calls,
        Vector(ServiceCall("light", "turn_off", "light.kitchen", Json.obj()))
      )
    }
  }
}
