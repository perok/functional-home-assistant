package fh.view.functional

import api.homeassistant.ServiceTarget
import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{Access, Dashboard}
import fh.view.runtime.TestServer
import fh.view.testkit.{FixtureEntity, HouseFixture, PklFixture}
import org.http4s.Status

/** A call over a query set (#583): a press acts on the candidates present at
  * the press, read off the same tally a label counts, in one call; the browser
  * names only the set.
  */
class CallSetSuite extends FunctionalSuite {

  private val kitchen = HouseFixture.kitchenLight
  private val living = HouseFixture.livingRoomLight
  private val sun = FixtureEntity("sun.sun", "above_horizon")

  // The living room light stays on after dark; the sun is read only by that
  // guard and by the label counting the set.
  private def entry(button: String) =
    s"""amends "@fh-dashboard/entry.pkl"
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/query.pkl" as q
      |import "@fh-home/dump.pkl" as dump
      |local lit = q.from(List(dump.entities.light_kitchen, dump.entities.light_living_room))
      |  .where(q.eq(q.stateProp, "on"))
      |  .where((e) -> if (e == dump.entities.light_living_room)
      |    q.not(q.entity(dump.entities.sun_sun).stateIs("below_horizon")) else true)
      |card = (c.column) { children { $button } }
      |""".stripMargin

  private def build(button: String): Dashboard =
    PklFixture.buildDashboard("home", entry(button), HouseFixture.dumpWith(sun))

  private def dashboard: Dashboard = build(
    """c.button("Av", c.tap.call("light/turn_off", lit))
      |    .secondary(c.expr(#"string(lit) + ' on'"#))
      |    .inputs(new Mapping { ["lit"] = lit.count() })""".stripMargin
  )

  private def withHouse[A](states: FixtureEntity*)(
      f: TestServer => IO[A]
  ): IO[A] =
    TestServer
      .resource(dashboard, states.toList, access = Access.Public)
      .use(f)

  /** The call the page renders for the button: the one a browser posts. */
  private def pressUrl(ts: TestServer): IO[String] =
    ts.pressOf("c_0", as = None)

  private def targets(ts: TestServer): IO[Vector[Option[ServiceTarget]]] =
    ts.fake.recordedCalls.map(_.map { c =>
      c.group.orElse(
        Option.when(c.entityId.nonEmpty)(ServiceTarget.Entity(c.entityId))
      )
    })

  test("a press sends one call naming the members present") {
    withHouse(kitchen, living.copy(state = "on"), sun) { ts =>
      for {
        url <- pressUrl(ts)
        status <- ts.post(url, as = None)
        sent <- targets(ts)
      } yield {
        assertEquals(status, Status.NoContent, clue = url)
        assertEquals(
          sent,
          Vector(
            Some(
              ServiceTarget.Entities(
                NonEmptyList.of(kitchen.entityId, living.entityId)
              )
            )
          )
        )
      }
    }
  }

  test("a guard's entity moving changes what the next press reaches") {
    withHouse(kitchen, living.copy(state = "on"), sun) { ts =>
      for {
        url <- pressUrl(ts)
        _ <- ts.record(sun.copy(state = "below_horizon"))
        _ <- ts.post(url, as = None)
        sent <- targets(ts)
      } yield assertEquals(
        sent,
        Vector(Some(ServiceTarget.Entities(NonEmptyList.one(kitchen.entityId))))
      )
    }
  }

  test("a press on an empty set succeeds without calling HA") {
    withHouse(kitchen.copy(state = "off"), living, sun) { ts =>
      for {
        url <- pressUrl(ts)
        status <- ts.post(url, as = None)
        sent <- targets(ts)
      } yield {
        assertEquals(status, Status.NoContent)
        assertEquals(sent, Vector.empty)
      }
    }
  }

  test(
    "a call id the dashboard does not declare, or another node's, is refused"
  ) {
    withHouse(kitchen, living, sun) { ts =>
      for {
        url <- pressUrl(ts)
        forged = url.replaceAll("/[0-9a-f]{16}\\?", "/0000000000000000?")
        elsewhere = url.replace("node=c_0", "node=c")
        answers <- List(forged, elsewhere).traverse(ts.postResult(_, as = None))
        sent <- targets(ts)
      } yield {
        assert(
          answers(0)._2.contains("no tap on this dashboard makes the call"),
          clue = answers(0)
        )
        assert(
          answers(1)._2.contains("does not place the call"),
          clue = answers(1)
        )
        assertEquals(sent, Vector.empty)
      }
    }
  }

  test("the label counts the members a press would reach") {
    withHouse(kitchen, living.copy(state = "on"), sun) { ts =>
      for {
        before <- ts.page(as = None)
        _ <- ts.record(sun.copy(state = "below_horizon"))
        after <- ts.page(as = None)
      } yield {
        assert(before.contains("2 on"), clue = before)
        assert(after.contains("1 on"), clue = after)
      }
    }
  }

  test("an entity only a set's guard reads is subscribed") {
    val unlabelled =
      build("""c.button("Av", c.tap.call("light/turn_off", lit))""")
    assert(!unlabelled.referencedEntities.contains(sun.entityId))
    assert(unlabelled.watchedEntities.contains(sun.entityId))
  }
}
