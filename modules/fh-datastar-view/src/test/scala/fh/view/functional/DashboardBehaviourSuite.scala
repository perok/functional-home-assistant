package fh.view.functional

import fh.view.testkit.DashboardBuilders.col
import fh.view.testkit.{FixtureDashboard, HouseFixture, ServiceCall}
import io.circe.Json

/** End-to-end behaviour of the dashboard against a stubbed Home Assistant: the
  * whole loop — seed snapshot -> `StateStore` -> `Server` -> HTTP/SSE, and
  * control -> `callService` — with no live HA and a scripted timeline. Each
  * test reads as an observable behaviour, asserted at the HTTP boundary.
  *
  * Each test declares only the world it exercises: the entities the fake is
  * seeded from, and a dashboard built (via [[FixtureDashboard]]) over just the
  * cards that bind them. A control-only test seeds only the light it clicks,
  * with an empty layout — the smallest world that still records the call.
  */
class DashboardBehaviourSuite extends FunctionalSuite {

  private val outside = HouseFixture.outsideTemp
  private val kitchen = HouseFixture.kitchenLight

  test("initial page render reflects the seeded snapshot") {
    val html = withServer(
      FixtureDashboard.build(
        col(
          FixtureDashboard.reading(outside),
          FixtureDashboard.light("Kitchen", kitchen)
        )
      ),
      List(outside, kitchen)
    )(_.page)
    // The numeric reading and its unit (pulled from $attr) are present...
    assert(html.contains("12.4"), clue = html)
    assert(html.contains("°C"), clue = html)
    // ...and the kitchen light's seeded state.
    assert(html.contains("Kitchen: "), clue = html)
    assert(html.contains(">on<"), clue = html)
  }

  test("a state change pushes a fragment carrying the new value") {
    withServer(
      FixtureDashboard.build(col(FixtureDashboard.reading(outside))),
      List(outside)
    ) { ts =>
      ts.observePatch(
        marker = "13.1",
        trigger = ts.fake.emit(outside.entityId, "13.1", outside.attributes)
      )
    }
  }

  test("turning the kitchen light off pushes its new state over SSE") {
    withServer(
      FixtureDashboard.build(col(FixtureDashboard.light("Kitchen", kitchen))),
      List(kitchen)
    ) { ts =>
      // A distinctive marker only the OFF light fragment can contain.
      ts.observePatch(
        marker = "Kitchen: <span>off</span>",
        trigger = ts.fake.emit(kitchen.entityId, "off", Map.empty)
      )
    }
  }

  test("a no-op emit publishes nothing; the next real change is seen first") {
    // Pins StateStore's "publish only on real change" contract end-to-end: an
    // emit of the current value is dropped, so the FIRST observed change is the
    // subsequent real one — driven through the fake's queue, not a private seam.
    val firstState = withServer(
      FixtureDashboard.build(col(FixtureDashboard.reading(outside))),
      List(outside)
    ) { ts =>
      for {
        firstChange <- ts.store.changes.take(1).compile.lastOrError.start
        _ <- ts.awaitChangeSubscribers(1)
        // No-op: same value the fixture already seeded -> dropped by update.
        _ <- ts.fake.emit(outside.entityId, outside.state, outside.attributes)
        // A real change -> published.
        _ <- ts.fake.emit(outside.entityId, "13.1", Map.empty)
        change <- firstChange.joinWithNever
      } yield change.current.state
    }
    assertEquals(firstState, "13.1")
  }

  test("a control click calls the service back into HA") {
    val calls = withServer(
      FixtureDashboard.build(col()),
      List(kitchen)
    ) { ts =>
      ts.post("sse/action/light/toggle/light.kitchen") *> ts.fake.recordedCalls
    }
    assertEquals(
      calls,
      Vector(ServiceCall("light", "toggle", "light.kitchen", Json.obj()))
    )
  }

  test("a value-carrying control passes its data through to HA") {
    val calls = withServer(
      FixtureDashboard.build(col()),
      List(kitchen)
    ) { ts =>
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
    withServer(
      FixtureDashboard.build(col(FixtureDashboard.light("Kitchen", kitchen))),
      List(kitchen)
    ) { ts =>
      for {
        _ <- ts.post("sse/action/light/turn_off/light.kitchen")
        _ <- ts.observePatch(
          marker = "Kitchen: <span>off</span>",
          // The fake records the call; HA's resulting state change is emitted
          // explicitly (the fake does not simulate HA semantics).
          trigger = ts.fake.emit(kitchen.entityId, "off", Map.empty)
        )
        calls <- ts.fake.recordedCalls
      } yield assertEquals(
        calls,
        Vector(ServiceCall("light", "turn_off", "light.kitchen", Json.obj()))
      )
    }
  }
}
