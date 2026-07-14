package fh.view.functional

import fh.view.model.{Op, Predicate}
import fh.view.testkit.{
  FixtureDashboard,
  FixtureEntity,
  HouseFixture,
  Scene,
  ServiceCall
}
import io.circe.Json

/** End-to-end behaviour of the dashboard against a stubbed Home Assistant: the
  * whole loop — seed snapshot -> `StateStore` -> `Server` -> HTTP/SSE, and
  * control -> `callService` — with no live HA and a scripted timeline. Each
  * test reads as an observable behaviour, asserted at the HTTP boundary.
  *
  * Each test builds only the world it exercises with the [[scene]] builder: the
  * cards it asserts on (each auto-seeds the entity it binds) plus any extra
  * entity it drives directly. A control-only test adds no card at all and just
  * seeds the light it clicks with `.entity(...)` — the smallest world that still
  * records the call.
  */
class DashboardBehaviourSuite extends FunctionalSuite {

  private val outside = HouseFixture.outsideTemp
  private val kitchen = HouseFixture.kitchenLight

  private def onLight(id: String, name: String): FixtureEntity =
    FixtureEntity(
      s"light.$id",
      "on",
      Map("friendly_name" -> Json.fromString(name))
    )
  private def offLight(id: String, name: String): FixtureEntity =
    onLight(id, name).copy(state = "off")

  // A group of the lights currently on, and one of ALL lights (state-agnostic).
  private def onGroup = FixtureDashboard.group(
    Predicate.Cmp("state", Op.Eq, Json.fromString("on"))
  )
  private def lightGroup = FixtureDashboard.group(
    Predicate.Cmp("domain", Op.Eq, Json.fromString("light"))
  )

  test("initial page render reflects the seeded snapshot") {
    val html = withServer(
      scene
        .card(FixtureDashboard.reading(outside))
        .card(FixtureDashboard.light("Kitchen", kitchen))
    )(_.page)
    // The numeric reading and its unit (pulled from $attr) are present...
    assert(html.contains("12.4"), clue = html)
    assert(html.contains("°C"), clue = html)
    // ...and the kitchen light's seeded state.
    assert(html.contains("Kitchen: "), clue = html)
    assert(html.contains(">on<"), clue = html)
  }

  test("a state change pushes a fragment carrying the new value") {
    withServer(scene.card(FixtureDashboard.reading(outside))) { ts =>
      ts.observePatch(
        marker = "13.1",
        trigger = ts.fake.emit(outside.entityId, "13.1", outside.attributes)
      )
    }
  }

  test("turning the kitchen light off pushes its new state over SSE") {
    withServer(scene.card(FixtureDashboard.light("Kitchen", kitchen))) { ts =>
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
    val firstState = withServer(scene.card(FixtureDashboard.reading(outside))) {
      ts =>
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
    val calls = withServer(scene.entity(kitchen)) { ts =>
      ts.post("sse/action/light/toggle/light.kitchen") *> ts.fake.recordedCalls
    }
    assertEquals(
      calls,
      Vector(ServiceCall("light", "toggle", "light.kitchen", Json.obj()))
    )
  }

  test("a value-carrying control passes its data through to HA") {
    val calls = withServer(scene.entity(kitchen)) { ts =>
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
    withServer(scene.card(FixtureDashboard.light("Kitchen", kitchen))) { ts =>
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

  // ---------------------------------------------------------------------------
  // Dynamic groups end-to-end. The group matches by QUERY, so its members are
  // named in no slot — they are seeded through the Scene's `.entities(..)`
  // extras (exactly the case that builder exists for). These pin the membership
  // machinery `RendererSuite` unit-tests (`affectedDynamics`, per-entity render)
  // at the wire: the actual SSE patch a group emits as members enter/leave.
  // ---------------------------------------------------------------------------

  test("an entity entering a dynamic group streams its card in over SSE") {
    // beta is off (outside the `state == on` group); when it turns on the group
    // re-renders and the pushed fragment carries beta's now-visible member card.
    // (The emit carries beta's attributes so its member card keeps its name —
    // HA sends full attributes on every state_changed, and the card reads
    // friendly_name.)
    val alpha = onLight("alpha", "Alpha")
    val beta = offLight("beta", "Beta")
    withServer(scene.card(onGroup).entities(alpha, beta)) { ts =>
      ts.observePatch(
        marker = "Beta: <span>on</span>",
        trigger = ts.fake.emit(beta.entityId, "on", beta.attributes)
      )
    }
  }

  test("an entity leaving a dynamic group is removed per-entity over SSE") {
    // Three members on (a minority-churn group, so a single departure takes the
    // per-entity path, not a whole-group repaint). The first change establishes
    // the group in the diff cache (its first membership change always repaints);
    // the second — dropping one of the now-three members — is 1-of-3 churn, so
    // it emits a `mode remove` patch for exactly that member's cell.
    val alpha = onLight("alpha", "Alpha")
    val beta = onLight("beta", "Beta")
    val gamma = offLight("gamma", "Gamma")
    withServer(scene.card(onGroup).entities(alpha, beta, gamma)) { ts =>
      for {
        // Establish: gamma joins (2 -> 3 members, a boundary repaint). Observing
        // its card confirms the group is now cached before we drive the removal.
        _ <- ts.observePatch(
          marker = "Gamma: <span>on</span>",
          trigger = ts.fake.emit(gamma.entityId, "on", gamma.attributes)
        )
        // Remove: beta leaves the on-group. 1-of-3 churn -> a per-entity remove
        // targeting only beta's cell (id derived from its entity id).
        _ <- ts.observePatch(
          marker = "mode remove",
          trigger = ts.fake.emit(beta.entityId, "off", beta.attributes)
        )
      } yield ()
    }
  }

  test("an in-place state change re-renders a group member's card over SSE") {
    // A `domain == light` group: the kitchen light stays a member across an
    // on->off change (its domain is unchanged), so the change is an in-place
    // member tick — the group morphs just that one member card with the new
    // state, never a membership delta.
    withServer(
      scene
        .card(lightGroup)
        .entities(kitchen, HouseFixture.livingRoomLight)
    ) { ts =>
      ts.observePatch(
        marker = "Kitchen: <span>off</span>",
        trigger = ts.fake.emit(kitchen.entityId, "off", kitchen.attributes)
      )
    }
  }

  test("a state-activated surface flips its baked branch over SSE") {
    // An If/else host: the `else` branch shows while the alarm is disarmed; when
    // it arms, the deciding entity's change flips the host to bake the `then`
    // branch, whose content streams over SSE. The alarm rides only the
    // activation predicate (no slot), so it is seeded as a Scene extra; the two
    // branch readings are referenced by the surfaces, so they must be supplied
    // too (the extras double as the resolution source).
    val alarm = FixtureEntity("alarm.home", "disarmed")
    val armed = FixtureEntity("sensor.armed", "ON")
    val disarmed = FixtureEntity("sensor.disarmed", "OFF")
    val dash = FixtureDashboard.ifElse(
      condEntity = alarm.entityId,
      activeState = "armed",
      thenBranch = FixtureDashboard.light("Armed", armed),
      elseBranch = FixtureDashboard.light("Disarmed", disarmed)
    )
    withServer(Scene.of(dash).entities(alarm, armed, disarmed)) { ts =>
      ts.observePatch(
        marker = "Armed: <span>ON</span>",
        trigger = ts.fake.emit(alarm.entityId, "armed", Map.empty)
      )
    }
  }
}
