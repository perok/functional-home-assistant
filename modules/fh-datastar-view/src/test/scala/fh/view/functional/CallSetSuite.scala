package fh.view.functional

import api.homeassistant.ServiceTarget
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{Access, Dashboard}
import fh.view.runtime.TestServer
import fh.view.testkit.DashboardBuilders.{component, lit}
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
      |    .expressionValues(new Mapping { ["lit"] = lit.count() })""".stripMargin
  )

  private def withHouse[A](states: FixtureEntity*)(
      f: TestServer => IO[A]
  ): IO[A] =
    TestServer
      .resource(dashboard, states.toList, access = Access.Public)
      .use(f)

  /** The route the page renders for the button: the one a browser posts. */
  private def pressUrl(ts: TestServer): IO[String] =
    ts.page(as = None).map { html =>
      val click = """@post\((.*?), \{filterSignals""".r
        .findFirstMatchIn(html)
        .fold(fail(s"no action on the page: $html"))(_.group(1))
      """'([^']*)'""".r.findAllMatchIn(click).map(_.group(1)).mkString
    }

  private def targets(ts: TestServer): IO[Vector[Option[ServiceTarget]]] =
    ts.fake.recordedCalls.map(_.map(_.group))

  test("a press sends one call naming the members present") {
    withHouse(kitchen, living.copy(state = "on"), sun) { ts =>
      for {
        url <- pressUrl(ts)
        status <- ts.post(url, as = None)
        sent <- targets(ts)
      } yield {
        assertEquals(status, Status.NoContent, clue = url)
        assert(url.contains("/set/"), clue = url)
        assertEquals(
          sent,
          Vector(
            Some(
              ServiceTarget.Entities(List(kitchen.entityId, living.entityId))
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
        Vector(Some(ServiceTarget.Entities(List(kitchen.entityId))))
      )
    }
  }

  test("a press on an empty set is refused and calls nothing") {
    withHouse(kitchen.copy(state = "off"), living, sun) { ts =>
      for {
        url <- pressUrl(ts)
        answer <- ts.postResult(url, as = None)
        sent <- targets(ts)
      } yield {
        assertEquals(answer._1, Status.Ok)
        assert(answer._2.contains("nothing in this set matches"), clue = answer)
        assertEquals(sent, Vector.empty)
      }
    }
  }

  test("a set id the dashboard does not declare is refused") {
    withHouse(kitchen, living, sun) { ts =>
      for {
        url <- pressUrl(ts)
        forged = url.replaceAll("/set/[0-9a-f]+", "/set/0000000000000000")
        other = url.replace("light/turn_off", "light/turn_on")
        answers <- List(forged, other).traverse(ts.postResult(_, as = None))
        sent <- targets(ts)
      } yield {
        answers.foreach(a =>
          assert(a._2.contains("no tap on this dashboard calls"), clue = a)
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

  test("a set route whose card dropped its tally does not validate") {
    val bare = component(
      "light",
      "name" -> lit("Av"),
      "service" -> lit("light/turn_off"),
      "targetKind" -> lit("set"),
      "targetId" -> lit("0123456789abcdef")
    )
    val errors = scene.card(bare).dashboard.validate()
    assert(errors.exists(_.contains("without its '__call' value")), errors)
  }
}
