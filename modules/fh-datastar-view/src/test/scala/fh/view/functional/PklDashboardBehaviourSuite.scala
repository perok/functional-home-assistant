package fh.view.functional

import cats.effect.IO
import fh.view.runtime.TestServer
import fh.view.testkit.{FixtureEntity, HouseFixture}

import scala.concurrent.duration.*

/** The Tier-A capstone (ADR 0009): the SAME end-to-end behaviour as
  * [[DashboardBehaviourSuite]], but the dashboard is a real Pkl entry evaluated
  * through the GENUINE server build path — `TestServer.fromWorkspace` runs
  * `ServerApp.prepareRenderers` (discover -> `prepareDumps` -> `buildEntry`)
  * and `liveServer`, the exact sequence production's `run` uses. Nothing is
  * stubbed but the HA socket: the dump is FETCHED from the fake's
  * `render_template` (same fixtures `get_states` serves), so the Pkl track and
  * the runtime track meet with no shortcut through a pre-built `Dashboard`.
  *
  * The entry is authored against `dump.entities.<key>` for the fixture
  * entities; because the served dump and the seeded state both derive from the
  * SAME [[FixtureEntity]] set, the two cannot drift.
  */
class PklDashboardBehaviourSuite extends munit.CatsEffectSuite {

  /** Every entity the entry references — also the fake's seed and the source of
    * the dump it serves. One declaration feeds all three.
    */
  private val entities: List[FixtureEntity] =
    List(HouseFixture.outsideTemp, HouseFixture.kitchenLight)

  /** A minimal real entry over two fixture entities: a numeric sensor (whose
    * `entityCard` value auto-appends the unit) and the kitchen light. Authored
    * exactly as a hand-written dashboard would be — `amends
    * "@fh-dashboard/entry.pkl"`, referencing entities by their generated dump
    * keys.
    */
  private val entrySource =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |title = "Fixture Home"
       |
       |card = (c.column) {
       |  children {
       |    c.title("Fixture Home")
       |    c.entityCard(dump.entities.${HouseFixture.outsideTemp.dumpKey})
       |    c.entityCard(dump.entities.${HouseFixture.kitchenLight.dumpKey})
       |    c.button("Elsewhere", c.tap.navigate("other"))
       |  }
       |}
       |""".stripMargin

  /** A slider that ALSO carries a power button — the one node with two guarded
    * elements — so the test can prove the button's `_<id>__busy` and the
    * input's `_<id>__busy_change` are distinct names that never collide.
    */
  private val sliderEntry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children {
       |    (c.slider(dump.entities.${HouseFixture.kitchenLight.dumpKey})) {
       |      tapAction = c.tap.service("light/toggle")
       |    }
       |  }
       |}
       |""".stripMargin

  private def withServer[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("fixture-home", entrySource, entities)
      .use(f)
      .timeout(60.seconds)

  test("a Pkl-built dashboard renders the seeded live state") {
    withServer(_.page()).map { html =>
      // entityCard label = the live friendly_name; value = $state + unit.
      assert(html.contains("Outside Temperature"), clue = html)
      assert(html.contains("12.4"), clue = html)
      assert(html.contains("°C"), clue = html)
      // The kitchen light card: its friendly_name label and its "on" state.
      assert(html.contains("Kitchen"), clue = html)
      assert(html.contains(">on<"), clue = html)
    }
  }

  test("a navigating button reaches the browser as a real link") {
    withServer(_.page()).map { html =>
      // The whole point of ADR 0002's navigation decision, end to end: the
      // author wrote c.navigate, and what ships is an anchor the browser can
      // middle-click, with a relative href resolved against <base href> — no
      // script, so it works before Datastar loads.
      assert(
        html.contains(
          """<a class="button card" href="d/other">Elsewhere</a>"""
        ),
        clue = html
      )
    }
  }

  test("a guarded tap renders its busy guard, indicator and class; unguarded taps do not") {
    withServer(_.page()).map { html =>
      // The light entity card's default service tap is guarded (ADR 0016), and
      // every guarded element carries the THREE pieces the frontend contract
      // names (see `tap.pkl`'s `busyGuard`/`busyAttrs` and the action-feedback
      // plan): the guard makes the click expression a no-op while the call is
      // in flight, the indicator drives the `_<id>__busy` signal, and the
      // class shows the theme's busy state.
      assert(html.contains("data-indicator=\"_c_2__busy\""), clue = html)
      assert(html.contains("data-class:fh-busy=\"$_c_2__busy\""), clue = html)
      assert(
        html.contains("data-on:click=\"$_c_2__busy ? '' : @post('sse/action/"),
        clue = html
      )
      // Every guarded POST is no-signals, so the `_<id>__busy` signal —
      // created client-side by the indicator, and therefore invisible to this
      // HTML — can never reach an action request body.
      assert(html.contains("{filterSignals:{exclude:'.*'}}"), clue = html)
      // The sensor card's more-info tap is not guarded (it opens a popup, no
      // in-flight service POST worth a busy state)...
      assert(!html.contains("data-indicator=\"_c_1__busy\""), clue = html)
      assert(!html.contains("data-on:click=\"$_c_1__busy"), clue = html)
      // ...and a navigating button is an anchor; anchors are never guarded.
      assert(!html.contains("data-indicator=\"_c_3__busy\""), clue = html)
    }
  }

  test("a slider's value commit carries its own guarded, disabled input") {
    TestServer
      .fromWorkspace("fixture-slider", sliderEntry, entities)
      .use { ts =>
        ts.page().map { html =>
          // The slider's range input commits its value on `change`, so it is
          // the node's SECOND guarded element — the power button owns
          // `_<id>__busy`, this owns the element-suffixed `_<id>__busy_change`
          // (see `tap.pkl`'s `busyGuardChange`/`busyAttrsChange`/`busyClassChange`).
          // The three pieces: the indicator arms the signal, the attr freezes
          // the control while it is set, and the guard swallows a second commit.
          assert(html.contains("data-indicator=\"_c_0__busy_change\""), clue = html)
          assert(html.contains("data-attr:disabled=\"$_c_0__busy_change\""), clue = html)
          assert(
            html.contains(
              "data-on:change=\"$_c_0__busy_change ? '' : @post('sse/action/light/turn_on/"
            ),
            clue = html
          )
          // The busy visual rides on the track wrapper, not the input.
          assert(html.contains("data-class:fh-busy=\"$_c_0__busy_change\""), clue = html)
          // The power button on the same node keeps its own unsuffixed name —
          // the two guarded elements never share a signal (a shared one would
          // let one element's `finished` clear the other's in-flight busy).
          assert(html.contains("data-indicator=\"_c_0__busy\""), clue = html)
          assert(html.contains("data-on:click=\"$_c_0__busy ? '' : @post("), clue = html)
        }
      }
      .timeout(60.seconds)
  }

  test("a state change streams a fragment through the Pkl-built dashboard") {
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

  // ---------------------------------------------------------------------------
  // Tabs inside a conditional branch
  //
  // The one place two selection mechanisms meet: the branch is chosen by entity
  // state (server truth, identical for every viewer) while the tab inside it is
  // chosen by the client. The fixture suites build the equivalent by hand, which
  // is what let a first-paint break through the real `Tabs`/`If` cards slip past
  // them — so this shape earns its place at Tier A, where the CARDS are the ones
  // a user actually gets.
  // ---------------------------------------------------------------------------

  private val light = HouseFixture.kitchenLight // on
  private val temp = HouseFixture.outsideTemp
  private val other = HouseFixture.livingRoomLight

  private val branchEntities: List[FixtureEntity] = List(light, temp, other)

  /** `pkl-if`'s shape, minimised: while the kitchen light is on, show a tabs
    * card with two panels; otherwise show a single card.
    */
  private val branchEntry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children {
       |    c.title("Branch")
       |    c
       |      .iff(q.entity(dump.entities.${light.dumpKey}).stateIs("on"))
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

  private def withBranchServer[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("branch-tabs", branchEntry, branchEntities)
      .use(f)
      .timeout(60.seconds)

  test("first paint: the branch's tab panel carries its content") {
    withBranchServer(_.page()).map { html =>
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
    withBranchServer(_.page(s"?ui.$tabsHost=1")).map { html =>
      assert(html.contains("Outside Temperature"), clue = html)
      assert(!html.contains("Living Room"), clue = html)
    }
  }

  test("a flip re-reveals the client's OWN tab, not the group's default") {
    withBranchServer { ts =>
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
        // ONE patch, not a hollow mount followed by a fill: the branch and the
        // panel this viewer chose arrive TOGETHER, so there is no frame in which
        // the tabs card exists with nothing in it.
        //
        // Asserted as "the patch carrying the branch also carries the panel"
        // rather than by counting patches — how many flips land after the
        // opening block depends on when the connection finished opening, which
        // is timing, not behaviour.
        val branchPatch = live.linesIterator
          .filter(_.startsWith("data: elements "))
          .find(_.contains("Light is on"))
        assert(branchPatch.isDefined, clue = live)
        assert(
          branchPatch.exists(_.contains("Outside Temperature")),
          clue = ("the branch must arrive with this viewer's panel", live)
        )
        // The silent regression this guards: the default tab's content reaching
        // a client that is not on the default tab. Not "not in the last patch"
        // — nowhere in anything this connection was sent after opening.
        assert(!live.contains("Living Room"), clue = live)
      }
    }
  }

  test("the OTHER client keeps the default tab across the same flip") {
    withBranchServer { ts =>
      ts.observeLive(
        marker = "Living Room",
        trigger = ts.fake.emit(light.entityId, "off") *>
          ts.fake.emit(light.entityId, "on", light.attributes)
      ).map { live =>
        val branchPatch = live.linesIterator
          .filter(_.startsWith("data: elements "))
          .find(_.contains("Light is on"))
        assert(
          branchPatch.exists(_.contains("Living Room")),
          clue = ("the default tab's viewer gets ITS panel", live)
        )
        assert(!live.contains("Outside Temperature"), clue = live)
      }
    }
  }

  /** A mount carries client-dependent ATTRIBUTES, not only children. The tabs
    * mount seeds its selection signal from the baked index, so a re-revealed
    * panel that arrives with the wrong index — or with none, which is not even
    * valid — leaves the bar highlighting a different tab than the one on
    * screen. Asserted on the wire because it is invisible to a content check.
    *
    * Since the branch is rendered for its viewer, the index is right by
    * construction rather than corrected afterwards.
    */
  test("a re-revealed panel carries THIS client's selection signal") {
    withBranchServer { ts =>
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
