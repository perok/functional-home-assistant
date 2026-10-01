package fh.view.functional

import fh.view.testkit.{
  DashboardBuilders,
  FixtureDashboard,
  FixtureEntity,
  HouseFixture,
  Scene,
  ServiceCall
}
import io.circe.Json

/** The whole loop against a stubbed HA (snapshot -> `StateStore` -> `Server` ->
  * HTTP/SSE, and control -> `callService`), asserted at the HTTP boundary. Each
  * test builds only its world with the [[scene]] builder.
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

  // The candidates are named up front, which is the point of a candidate set.
  private def onSet(lights: FixtureEntity*) =
    FixtureDashboard.set(
      lights.map(_.entityId).toList,
      Some(FixtureDashboard.stateIs("on"))
    )
  private def lightSet(lights: FixtureEntity*) =
    FixtureDashboard.set(lights.map(_.entityId).toList)

  test("initial page render reflects the seeded snapshot") {
    withServer(
      scene
        .card(FixtureDashboard.reading(outside))
        .card(FixtureDashboard.light("Kitchen", kitchen))
    )(_.page()).map { html =>
      assert(html.contains("12.4"), clue = html)
      assert(html.contains("°C"), clue = html)
      assert(html.contains("Kitchen: "), clue = html)
      assert(html.contains(">on<"), clue = html)
    }
  }

  test("a state change pushes a fragment carrying the new value") {
    withServer(scene.card(FixtureDashboard.reading(outside))) { ts =>
      ts.sentAfter(ts.frame(outside.copy(state = "13.1"))).map(carries("13.1"))
    }
  }

  test("a no-op emit publishes nothing; the next real change is seen first") {
    // StateStore's "publish only on real change", end to end through the fake's
    // queue: the first observed change is the real one.
    withServer(scene.card(FixtureDashboard.reading(outside))) { ts =>
      for {
        // The recorder subscribes on its own, so waiting for one subscriber is
        // answered by it and the emits could land before this fiber subscribes.
        // Wait for the recorder, then for this fiber.
        _ <- ts.awaitChangeSubscribers(1)
        firstChange <- ts.store.changes.take(1).compile.lastOrError.start
        _ <- ts.awaitChangeSubscribers(2)
        // The seeded value, so dropped.
        _ <- ts.fake.emit(outside.entityId, outside.state, outside.attributes)
        _ <- ts.fake.emit(outside.entityId, "13.1", Map.empty)
        change <- firstChange.joinWithNever
      } yield change.head.current.state
    }.assertEquals("13.1")
  }

  // Only a call its dashboard declares gets through (ADR 0023), so the
  // smallest world that records one declares it.
  private def calling(service: String, dataKey: Option[String] = None) =
    scene.card(
      DashboardBuilders.col(
        FixtureDashboard.light("Kitchen", kitchen),
        FixtureDashboard.call(service, kitchen, dataKey)
      )
    )

  test("a control click calls the service back into HA") {
    withServer(calling("light/toggle")) { ts =>
      ts.post(
        s"sse/call/${ts.slug}/light/toggle/entity/light.kitchen"
      ) *> ts.fake.recordedCalls
    }.assertEquals(
      Vector(ServiceCall("light", "toggle", "light.kitchen", Json.obj()))
    )
  }

  test("a value-carrying control passes its data through to HA") {
    withServer(calling("light/turn_on", Some("brightness"))) { ts =>
      ts.post(
        s"sse/call/${ts.slug}/light/turn_on/entity/light.kitchen/brightness/200"
      ) *>
        ts.fake.recordedCalls
    }.assertEquals(
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
    withServer(calling("light/turn_off")) { ts =>
      for {
        _ <- ts.post(s"sse/call/${ts.slug}/light/turn_off/entity/light.kitchen")
        // The fake does not simulate HA, so the resulting state change is
        // emitted explicitly.
        sent <- ts.sentAfter(ts.change(kitchen.entityId, "off"))
        calls <- ts.fake.recordedCalls
      } yield {
        carries("Kitchen: <span>off</span>")(sent)
        assertEquals(
          calls,
          Vector(ServiceCall("light", "turn_off", "light.kitchen", Json.obj()))
        )
      }
    }
  }

  // Candidate sets end to end. The lights are not in the house registry, so
  // they are seeded through the Scene's `.entities(..)` extras.

  test("an entity entering a candidate set streams its card in over SSE") {
    // HA sends full attributes on every state_changed, and the card reads
    // friendly_name, so the emit carries them.
    val alpha = onLight("alpha", "Alpha")
    val beta = offLight("beta", "Beta")
    withServer(scene.card(onSet(alpha, beta)).entities(alpha, beta)) { ts =>
      ts.sentAfter(ts.frame(beta.copy(state = "on")))
        .map(carries("Beta: <span>on</span>"))
    }
  }

  test("an entity leaving a candidate set is removed per-entity over SSE") {
    // Three members, so one departure is minority churn and takes the
    // per-entity path. The first change establishes the group; the second is
    // the one under test.
    val alpha = onLight("alpha", "Alpha")
    val beta = onLight("beta", "Beta")
    val gamma = offLight("gamma", "Gamma")
    withServer(
      scene.card(onSet(alpha, beta, gamma)).entities(alpha, beta, gamma)
    ) { ts =>
      for {
        entered <- ts.sentAfter(ts.frame(gamma.copy(state = "on")))
        left <- ts.sentAfter(ts.frame(beta.copy(state = "off")))
      } yield {
        carries("Gamma: <span>on</span>")(entered)
        // A per-entity remove targeting only beta's cell.
        carries("mode remove")(left)
      }
    }
  }

  test("an in-place state change re-renders a group member's card over SSE") {
    // The light stays a member across on->off, so this is an in-place member
    // tick, never a membership delta.
    withServer(
      scene
        .card(lightSet(kitchen, HouseFixture.livingRoomLight))
        .entities(kitchen, HouseFixture.livingRoomLight)
    ) { ts =>
      ts.sentAfter(ts.frame(kitchen.copy(state = "off")))
        .map(carries("Kitchen: <span>off</span>"))
    }
  }

  test("a state-activated surface flips its baked branch over SSE") {
    // The alarm rides only the activation predicate, so it is a Scene extra;
    // the branch readings are referenced by surfaces, so they are supplied too.
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
      ts.sentAfter(ts.change(alarm.entityId, "armed"))
        .map(carries("Armed: <span>ON</span>"))
    }
  }
}
