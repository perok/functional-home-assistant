package fh.view.functional

import api.homeassistant.ServiceTarget
import cats.syntax.all.*
import fh.view.model.{
  Access,
  CallDecl,
  LayoutNode,
  Lit,
  Step,
  Value,
  ValueExpr,
  TemplateSlot
}
import fh.view.runtime.TestServer
import fh.view.testkit.{FixtureDashboard, HouseFixture, PklFixture, ServiceCall}
import io.circe.Json
import org.http4s.Status

import scala.collection.immutable.ListMap

/** The built dashboard's own taps are the allowlist, for every target kind:
  * anything else is one URL edit from unlocking the front door, and an area or
  * floor call reaches entities the dashboard never names (issue #389, ADR
  * 0023).
  */
class GroupCallSuite extends FunctionalSuite {

  private def decl(service: ValueExpr, key: String, id: String) =
    CallDecl(service, ListMap(key -> List(ValueExpr.literal(id))))

  /** What `c.tap.lights.off(dump.areas.stue)` leaves on its node. */
  private val lightsOff: LayoutNode.Component =
    LayoutNode.Component(
      "call",
      calls = Map(
        "tap" -> decl(ValueExpr.literal("light/turn_off"), "area_id", "stue")
      )
    )

  private def house = scene
    .card(FixtureDashboard.light("Kitchen", HouseFixture.kitchenLight))
    .card(lightsOff)

  private def offAt(ts: TestServer) =
    FixtureDashboard.pressOf(ts.slug, lightsOff, "c_1")

  test("a declared area call goes to HA with the area as its target") {
    withServer(house, Access.Public) { ts =>
      for {
        status <- ts.post(offAt(ts), as = None)
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

  test("the call a Pkl area tap renders is the call the server allows") {
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
        |card = (c.column) { children { c.button("Stue av", c.tap.lights.off(dump.areas.stue)) } }
        |""".stripMargin
    TestServer
      .resource(
        PklFixture.buildDashboard("home", entry, dump),
        Nil,
        access = Access.Public
      )
      .use { ts =>
        for {
          url <- ts.pressOf("c_0", as = None)
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

  private val door = HouseFixture.frontLock.entityId

  /** What a lock's tap leaves on a card that shows the lock: the service its
    * state picks, which the card sees, since it paints that state.
    */
  private val lockTap: LayoutNode.Component =
    LayoutNode.Component(
      "callShowing",
      templateSlots = Map(
        "state" -> TemplateSlot(ValueExpr(Value.Entity(Some(door))))
      ),
      calls = Map(
        "tap" -> decl(
          ValueExpr(
            Value.Entity(Some(door)),
            List(
              Step.Match(
                Map("locked" -> Lit.Text("lock/unlock")),
                Value.Literal(Lit.Text("lock/lock"))
              )
            )
          ),
          "entity_id",
          door
        )
      )
    )

  test("a service the state picks is acted on only as the page saw it") {
    withServer(scene.card(lockTap), Access.Public) { ts =>
      val at = FixtureDashboard.pressOf(ts.slug, lockTap, "c_0")
      def seen(json: String) = s"""{"seen":$json}"""
      for {
        stale <- ts.postResult(at, None, seen("""{"service":"lock/lock"}"""))
        unseen <- ts.postResult(at, None, seen("{}"))
        unlock <- ts.post(at, None, seen("""{"service":"lock/unlock"}"""))
        _ <- ts.record(HouseFixture.frontLock.copy(state = "unlocked"))
        lock <- ts.post(at, None, seen("""{"service":"lock/lock"}"""))
        calls <- ts.fake.recordedCalls
      } yield {
        assert(
          stale._2.contains(
            """service: \"lock/lock\" shown, \"lock/unlock\" now"""
          ),
          clue = stale
        )
        assert(unseen._2.contains("service: not shown"), clue = unseen)
        assertEquals((unlock, lock), (Status.NoContent, Status.NoContent))
        assertEquals(calls.map(_.service), Vector("unlock", "lock"))
      }
    }
  }

  test("only the declared call, on a node that places it, reaches HA") {
    withServer(house, Access.Public) { ts =>
      val at = offAt(ts)
      val refused = List(
        at.replaceAll("/[0-9a-f]{16}\\?", "/0000000000000000?") -> "",
        at.replace("node=c_1", "node=c_0") -> "",
        at -> """{"posted":{"brightness":100}}""",
        at -> """{"seen":{"service":"light/turn_off"}}"""
      )
      for {
        answers <- refused.traverse((p, body) =>
          ts.postResult(p, as = None, body = body)
        )
        calls <- ts.fake.recordedCalls
      } yield {
        answers.zip(refused).foreach { case ((status, body), press) =>
          assertEquals(status, Status.Ok, clue = press)
          assert(body.contains("_toast"), clue = body)
        }
        assertEquals(calls, Vector.empty)
      }
    }
  }
}
