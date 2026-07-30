package fh.view.functional

import cats.effect.IO
import fh.view.runtime.TestServer
import fh.view.testkit.{FixtureEntity, HouseFixture}

import scala.concurrent.duration.*

/** '''Tabs inside a conditional branch''', through the REAL authoring stack.
  *
  * The shape `pkl-if` demonstrates: `c.iff(...).then(c.tabs { ... })`. It is
  * the one place two selection mechanisms meet — the branch is chosen by entity
  * state (server truth, identical for every viewer) while the tab inside it is
  * chosen by the client — and it is exactly where a shared render can go wrong
  * without anything erroring.
  *
  * The fixture suites build the equivalent dashboard by hand, which is what let
  * a first-paint break through the real `Tabs`/`If` cards slip past them. This
  * one goes through `TestServer.fromWorkspace`: the entry is evaluated by
  * pkl-core against the shipped `@fh-dashboard` library, so the CARDS are the
  * ones a user actually gets.
  */
class PklTabsInBranchSuite extends munit.CatsEffectSuite {

  private val light = HouseFixture.kitchenLight // on
  private val temp = HouseFixture.outsideTemp
  private val other = HouseFixture.livingRoomLight

  private val entities: List[FixtureEntity] = List(light, temp, other)

  /** `pkl-if`'s shape, minimised: while the kitchen light is on, show a tabs
    * card with two panels; otherwise show a single card.
    */
  private val entrySource =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children {
       |    c.title("Branch")
       |    c
       |      .iff(c.entityIs(dump.entities.${light.dumpKey}.entity_id).and(c.stateIs("on")))
       |      .then((c.column) {
       |        children {
       |          c.title("Light is on")
       |          (c.tabs) {
       |            tabs {
       |              ["Lights"] {
       |                c.entityCard(dump.entities.${other.dumpKey})
       |              }
       |              ["Sensors"] {
       |                c.entityCard(dump.entities.${temp.dumpKey})
       |              }
       |            }
       |          }
       |        }
       |      })
       |      .`else`(c.title("Light is off"))
       |  }
       |}
       |""".stripMargin

  private def withServer[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("branch-tabs", entrySource, entities)
      .use(f)
      .timeout(60.seconds)

  test("first paint: the branch's tab panel carries its content") {
    withServer(_.page()).map { html =>
      // The branch is active (the light is on), so its content is baked...
      assert(html.contains("Light is on"), clue = html)
      // ...tab bar included...
      assert(html.contains("Lights"), clue = html)
      assert(html.contains("Sensors"), clue = html)
      // ...and — the actual claim — the SELECTED panel is not empty. This is
      // what a user sees before any script runs, so an empty mount here is a
      // blank dashboard, not a flicker.
      assert(html.contains("Living Room"), clue = html)
      // The unselected panel is not rendered at all (hidden-branch silence).
      assert(!html.contains("Outside Temperature"), clue = html)
    }
  }

  /** The tabs host's generated id — inside the `then` branch's content tree,
    * hence the surface prefix. Written out because it IS the contract: the
    * hoist's `bakeInto`, the `ui.<host>` selection param and the renderer's
    * node id are all this one string, and they silently drifted apart once.
    */
  private val tabsHost = "s_c_1_then__c_0_1"

  test("first paint on the second tab: that panel's content, not the default") {
    withServer(_.page(s"?ui.$tabsHost=1")).map { html =>
      assert(html.contains("Outside Temperature"), clue = html)
      assert(!html.contains("Living Room"), clue = html)
    }
  }

  test("a flip re-reveals the client's OWN tab, not the group's default") {
    withServer { ts =>
      ts.observeLive(
        // Only the fill can produce this: the branch is re-rendered for the
        // slug with no client, so its tab mount arrives EMPTY.
        marker = "Outside Temperature",
        query = s"?ui.$tabsHost=1",
        // Off, then on: the branch leaves and comes back, which is what
        // re-creates the tabs mount this client has to have refilled.
        trigger = ts.fake.emit(light.entityId, "off") *>
          ts.fake.emit(light.entityId, "on", light.attributes)
      ).map { live =>
        // The branch came back...
        assert(live.contains("Light is on"), clue = live)
        // ...and this viewer got ITS panel back.
        assert(live.contains("Outside Temperature"), clue = live)
        // The silent regression this guards: the default tab's content reaching
        // a client that is not on the default tab. Not "not in the last patch"
        // — nowhere in anything this connection was sent after opening.
        assert(!live.contains("Living Room"), clue = live)
      }
    }
  }

  test("the OTHER client keeps the default tab across the same flip") {
    withServer { ts =>
      ts.observeLive(
        marker = "Living Room",
        trigger = ts.fake.emit(light.entityId, "off") *>
          ts.fake.emit(light.entityId, "on", light.attributes)
      ).map { live =>
        assert(live.contains("Light is on"), clue = live)
        assert(live.contains("Living Room"), clue = live)
        assert(!live.contains("Outside Temperature"), clue = live)
      }
    }
  }

  /** A mount carries client-dependent ATTRIBUTES, not only children. The tabs
    * mount seeds its selection signal from the baked index, so a re-revealed
    * panel that arrives with the wrong index — or with none, which is not even
    * valid — leaves the bar highlighting a different tab than the one on
    * screen. Asserted on the wire because it is invisible to a content check.
    */
  test("a re-revealed panel carries THIS client's selection signal") {
    withServer { ts =>
      ts.observeLive(
        marker = "Outside Temperature",
        query = s"?ui.$tabsHost=1",
        trigger = ts.fake.emit(light.entityId, "off") *>
          ts.fake.emit(light.entityId, "on", light.attributes)
      ).map { live =>
        // Never a signal expression with an absent value.
        assert(!live.contains(s"ui_$tabsHost:  }"), clue = live)
        // The fill replaces the mount ELEMENT, so the index that lands is this
        // client's tab, not the group's default.
        assert(live.contains(s"ui_$tabsHost: 1 }"), clue = live)
      }
    }
  }
}
