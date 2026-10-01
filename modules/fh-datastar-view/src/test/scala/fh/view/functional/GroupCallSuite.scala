package fh.view.functional

import api.homeassistant.ServiceTarget
import cats.syntax.all.*
import fh.view.model.{Access, LayoutNode, SlotSource, Transform}
import fh.view.testkit.DashboardBuilders.{component, lit}
import fh.view.runtime.TestServer
import fh.view.testkit.{FixtureDashboard, HouseFixture, PklFixture, ServiceCall}
import io.circe.Json
import org.http4s.Status

/** The built dashboard's own taps are the allowlist, for every target kind:
  * anything else is one URL edit from unlocking the front door, and an area or
  * floor call reaches entities the dashboard never names (issue #389, ADR
  * 0023).
  */
class GroupCallSuite extends FunctionalSuite {

  /** What `c.tap.lightsOff(dump.areas.stue)` leaves on its node. */
  private val lightsOff: LayoutNode.Component =
    component(
      "light",
      "name" -> lit("Stue"),
      "state" -> SlotSource(Some(HouseFixture.kitchenLight.entityId)),
      "service" -> lit("light/turn_off"),
      "targetKind" -> lit("area"),
      "targetId" -> lit("stue")
    )

  private def house = scene
    .card(FixtureDashboard.light("Kitchen", HouseFixture.kitchenLight))
    .card(lightsOff)

  test("a declared area call goes to HA with the area as its target") {
    withServer(house, Access.Public) { ts =>
      for {
        status <- ts.post(
          s"sse/call/${ts.slug}/light/turn_off/area/stue",
          as = None
        )
        calls <- ts.fake.recordedCalls
      } yield {
        assertEquals(status, Status.NoContent)
        assertEquals(
          calls,
          Vector(
            ServiceCall(
              "light",
              "turn_off",
              "",
              Json.obj(),
              Some(ServiceTarget.Area("stue"))
            )
          )
        )
      }
    }
  }

  test("the URL a Pkl area tap renders is the call the server allows") {
    val dump = HouseFixture.transformedDump.mapObject(
      _.add(
        "areas",
        Json.obj(
          "stue" -> Json.obj(
            "area_id" -> Json.fromString("stue"),
            "area_name" -> Json.fromString("Stue")
          )
        )
      )
    )
    val entry =
      """amends "@fh-dashboard/entry.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |import "@fh-home/dump.pkl" as dump
        |card = (c.column) { children { c.button("Stue av", c.tap.lightsOff(dump.areas.stue)) } }
        |""".stripMargin
    TestServer
      .resource(
        PklFixture.buildDashboard("home", entry, dump),
        Nil,
        access = Access.Public
      )
      .use { ts =>
        for {
          html <- ts.page(as = None)
          click = """@post\((.*?), \{filterSignals""".r
            .findFirstMatchIn(html)
            .fold(fail(s"no action on the page: $html"))(_.group(1))
          url = """'([^']*)'""".r.findAllMatchIn(click).map(_.group(1)).mkString
          status <- ts.post(url, as = None)
          calls <- ts.fake.recordedCalls
        } yield {
          assertEquals(status, Status.NoContent, clue = url)
          assertEquals(
            calls.map(c => (c.domain, c.service, c.group)),
            Vector(("light", "turn_off", Some(ServiceTarget.Area("stue"))))
          )
        }
      }
  }

  /** What a lock's default tap leaves on its node: the service its state picks.
    */
  private val lockTap: LayoutNode.Component =
    component(
      "light",
      "name" -> lit("Door"),
      "state" -> SlotSource(Some(HouseFixture.frontLock.entityId)),
      "service" -> SlotSource(transform =
        Transform.Simple.Match(Map("locked" -> "lock/unlock"), "lock/lock")
      ),
      "targetKind" -> lit("entity"),
      "targetId" -> lit(HouseFixture.frontLock.entityId)
    )

  test("a service the state picks declares every arm it can post") {
    withServer(scene.card(lockTap), Access.Public) { ts =>
      val door = HouseFixture.frontLock.entityId
      for {
        unlock <- ts.post(s"sse/call/${ts.slug}/lock/unlock/entity/$door", None)
        lock <- ts.post(s"sse/call/${ts.slug}/lock/lock/entity/$door", None)
        open <- ts.postResult(
          s"sse/call/${ts.slug}/lock/open/entity/$door",
          None
        )
        calls <- ts.fake.recordedCalls
      } yield {
        assertEquals((unlock, lock), (Status.NoContent, Status.NoContent))
        assert(open._2.contains("no tap on this dashboard calls"), clue = open)
        assertEquals(calls.map(_.service), Vector("unlock", "lock"))
      }
    }
  }

  test("every part of the call is checked, not only the area") {
    val undeclared = List(
      // Named by the dashboard, but no tap calls this on it.
      s"light/turn_off/entity/${HouseFixture.kitchenLight.entityId}",
      "lock/unlock/area/stue",
      "light/turn_off/area/kjokken",
      "light/turn_off/floor/stue",
      "light/turn_off/device/stue",
      "light/turn_off/area/stue/brightness/100"
    )
    withServer(house, Access.Public) { ts =>
      for {
        answers <- undeclared.traverse(p =>
          ts.postResult(s"sse/call/${ts.slug}/$p?node=c_1", as = None)
        )
        calls <- ts.fake.recordedCalls
      } yield {
        answers.zip(undeclared).foreach { case ((status, body), path) =>
          assertEquals(status, Status.Ok, clue = path)
          assert(body.contains("no tap on this dashboard calls"), clue = body)
        }
        assertEquals(calls, Vector.empty)
      }
    }
  }
}
