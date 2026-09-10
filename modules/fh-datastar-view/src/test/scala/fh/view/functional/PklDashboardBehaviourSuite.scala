package fh.view.functional

import cats.effect.IO
import cats.syntax.all.*
import fh.view.runtime.TestServer
import io.circe.Json
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
          """<a class="button card" href="d/other">""" +
            """<span class="fh-text"><span class="fh-text-run">Elsewhere</span></span></a>"""
        ),
        clue = html
      )
    }
  }

  test(
    "a guarded tap renders its busy guard, indicator and class; unguarded taps do not"
  ) {
    withServer(_.page()).map { html =>
      // The light entity card's default service tap is guarded (ADR 0016), and
      // every guarded element carries the pieces the frontend contract names
      // (see `tap.pkl`'s `busyGuard`/`busyAttrs`/`busyClass` and the
      // action-feedback plan): the guard makes the click expression a no-op
      // while the call is in flight, the indicator drives the `_<id>__busy`
      // signal, and the class binds both `fh-disabled` (instant dim) and
      // `fh-loading` (CSS-delayed spinner) on the same signal.
      assert(html.contains("data-indicator=\"_c_2__busy\""), clue = html)
      assert(
        html.contains("data-class:fh-disabled=\"$_c_2__busy\""),
        clue = html
      )
      assert(
        html.contains("data-class:fh-loading=\"$_c_2__busy\""),
        clue = html
      )
      // Two guards, in order: INERT first (the entity is in a state where the
      // press is meaningless — for a plain `light/toggle` that is availability
      // alone), then BUSY (this tap's own POST is in flight). They answer
      // different questions and neither subsumes the other.
      assert(
        html.contains("$_c_2__busy ? '' : @post('sse/action/"),
        clue = html
      )
      assert(
        html.contains(
          "data-on:click=\"$_e.light.kitchen."
        ) && html.contains("? '' : $_c_2__busy ? '' :"),
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
          assert(
            html.contains("data-indicator=\"_c_0_head_0__busy_change\""),
            clue = html
          )
          assert(
            html.contains("data-attr:disabled=\"$_c_0_head_0__busy_change\""),
            clue = html
          )
          assert(
            html.contains(
              "data-on:change=\"$_c_0_head_0__busy_change ? '' : @post('sse/action/fixture-slider/light/turn_on/"
            ),
            clue = html
          )
          // The busy LOOK (`fh-disabled` + `fh-loading`, both immediate, on the
          // same signal) rides on the track wrapper AND the head badge (which
          // also carries the delayed spinner splice, so its icon spins once the
          // commit runs long); the input itself stays free of the class — it is
          // frozen via `data-attr:disabled` instead.
          assert(
            html
              .contains("data-class:fh-disabled=\"$_c_0_head_0__busy_change\""),
            clue = html
          )
          assert(
            html
              .contains("data-class:fh-loading=\"$_c_0_head_0__busy_change\""),
            clue = html
          )
          assert(
            html.contains(
              "class=\"slider-icon\" data-class:fh-disabled=\"$_c_0_head_0__busy_change\" data-class:fh-loading=\"$_c_0_head_0__busy_change\" "
            ),
            clue = html
          )
          // The power button is its OWN node now (#151), so the two guarded
          // elements cannot share a signal even in principle — where before
          // they were one node kept apart by a `_change` suffix, and a shared
          // name would have let one element's `finished` clear the other's
          // in-flight busy. The suffix survives for the commit; the button
          // takes the plain name under the action's own id.
          val button = "_c_0_head_0_actions_0__busy"
          assert(html.contains(s"""data-indicator="$button""""), clue = html)
          assert(
            html.contains(s"""data-on:click="$$$button ? '' : @post("""),
            clue = html
          )
          assert(
            html.contains(s"""data-class:fh-disabled="$$$button""""),
            clue = html
          )
          assert(
            html.contains(s"""data-class:fh-loading="$$$button""""),
            clue = html
          )
          // Stated as the separation it is: the commit's signal and the
          // button's differ in the NODE, not merely in a suffix, so neither
          // name is a prefix of the other.
          assert(
            !button.startsWith("_c_0_head_0__busy"),
            clue = button
          )
        }
      }
      .timeout(60.seconds)
  }

  test(
    "busyVisual = false drops the busy look but keeps the guard and the freeze"
  ) {
    // The whole-look opt-out (`TapAction.busyVisual`, and the slider's own
    // `busyVisual`) removes both `data-class:fh-disabled` and
    // `data-class:fh-loading` bindings: the guard, the indicator and the input
    // freeze are signal-driven and must stay byte-identical, or a
    // fast-answering control would lose its anti-spam the moment it stopped
    // dimming.
    val buttonEntry =
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |card = (c.column) {
         |  children {
         |    c.button("Toggle", (c.tap.service("light/toggle")) { busyVisual = false })
         |  }
         |}
         |""".stripMargin
    val quietSliderEntry =
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |card = (c.column) {
         |  children {
         |    (c.slider(dump.entities.${HouseFixture.kitchenLight.dumpKey})) {
         |      busyVisual = false
         |    }
         |  }
         |}
         |""".stripMargin
    for {
      _ <- TestServer
        .fromWorkspace("fixture-quiet-button", buttonEntry, entities)
        .use { ts =>
          ts.page().map { html =>
            assert(html.contains("data-indicator=\"_c_0__busy\""), clue = html)
            assert(
              html.contains(
                "data-on:click=\"$_c_0__busy ? '' : @post('sse/action/"
              ),
              clue = html
            )
            assert(!html.contains("data-class:fh-disabled"), clue = html)
            assert(!html.contains("data-class:fh-loading"), clue = html)
          }
        }
        .timeout(60.seconds)
      _ <- TestServer
        .fromWorkspace("fixture-quiet-slider", quietSliderEntry, entities)
        .use { ts =>
          ts.page().map { html =>
            // The commit's guard, indicator and input freeze survive; only the
            // wrapper/badge `data-class:fh-disabled` and `data-class:fh-loading`
            // bindings are gone.
            assert(
              html.contains("data-indicator=\"_c_0_head_0__busy_change\""),
              clue = html
            )
            assert(
              html.contains("data-attr:disabled=\"$_c_0_head_0__busy_change\""),
              clue = html
            )
            assert(
              html.contains(
                "data-on:change=\"$_c_0_head_0__busy_change ? '' : @post('sse/action/fixture-quiet-slider/light/turn_on/"
              ),
              clue = html
            )
            assert(!html.contains("data-class:fh-disabled"), clue = html)
            assert(!html.contains("data-class:fh-loading"), clue = html)
          }
        }
        .timeout(60.seconds)
    } yield ()
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
      // what a user sees before any script runs, so an empty host here is a
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
        // slug with no client, so its tab host arrives EMPTY.
        marker = "Outside Temperature",
        query = s"?ui.$tabsHost=1",
        // Off, then on: the branch leaves and comes back, which is what
        // re-creates the tabs host this client has to have refilled.
        trigger = ts.fake.emit(light.entityId, "off") *>
          ts.fake.emit(light.entityId, "on", light.attributes)
      ).map { live =>
        // ONE patch, not a hollow host followed by a fill: the branch and the
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

  /** A host carries client-dependent ATTRIBUTES, not only children. The tabs
    * host seeds its selection signal from the baked index, so a re-revealed
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
        // Never a signal expression with an absent value. The committed signal
        // is no longer last in the seed object (the pending one follows it), so
        // the delimiter that pins "a value is present" is the comma.
        assert(!live.contains(s"ui_$tabsHost: ,"), clue = live)
        // The fill replaces the host ELEMENT, so the index that lands is this
        // client's tab, not the group's default.
        assert(live.contains(s"ui_$tabsHost: 1,"), clue = live)
      }
    }
  }

  test("a slider on a light that only switches renders a button, not a range") {
    // The card carries the variant (issue #128), so the ONE shared template has
    // to render two shapes — which is the half a Pkl test cannot prove: the
    // sections are jmustache's to evaluate, and an inverted section over an
    // absent slot is exactly the mechanism in question.
    val plug = FixtureEntity(
      "light.plug",
      "on",
      Map(
        "friendly_name" -> Json.fromString("Plug"),
        "supported_color_modes" -> Json.arr(Json.fromString("onoff"))
      )
    )
    val plugEntry =
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |card = (c.column) {
         |  children {
         |    c.slider(dump.entities.${plug.dumpKey}).readout("percent")
         |  }
         |}
         |""".stripMargin
    TestServer
      .fromWorkspace("fixture-plug", plugEntry, List(plug))
      .use { ts =>
        ts.page().map { html =>
          assert(html.contains("class=\"slider-toggle\""), clue = html)
          assert(!html.contains("type=\"range\""), clue = html)
          // The whole track posts the light's own toggle, under the same
          // commit signal the drag would have used. The service is spliced as a
          // quoted literal — the same spelling a state-dependent tap fills with
          // a signal read, which is why one template serves both (ADR 0017) —
          // and the node id is a build-time constant, not a `dataset` read.
          assert(
            html.contains(
              "data-on:click=\"$_c_0_head_0__busy_change ? '' : " +
                "@post('sse/action/fixture-plug/' + 'light/toggle' + " +
                "'/light.plug?node=c_0_head_0'"
            ),
            clue = html
          )
          // Filled, because it is on — and in the switch-fill token, since a
          // light that only switches reports no colour of its own.
          assert(html.contains("--_end: 0%"), clue = html)
          assert(
            html.contains("background:var(--fh-default-color)"),
            clue = html
          )
          // A percentage of an axis it does not have would read 0 % forever.
          assert(html.contains(">on<"), clue = html)
          assert(!html.contains(">0 %<"), clue = html)
        }
      }
      .timeout(60.seconds)
  }

  // ---------------------------------------------------------------------------
  // `CallByState` — the four domains whose service the live state picks
  //
  // This path had NO Scala coverage: every fact about it lived in
  // `components.test.pkl`, which evaluates Pkl and can see neither
  // `Dashboard.validate` nor a rendered byte. The gap let a validate rule ship
  // WRONG and green — it demanded a template var the card no longer places, and
  // nothing on this side ever rendered a lock for it to reject.
  // ---------------------------------------------------------------------------

  private def lockAt(state: String) = FixtureEntity(
    "lock.front_door",
    state,
    Map("friendly_name" -> Json.fromString("Front Door"))
  )

  private val lockEntry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children {
       |    c.entityCard(dump.entities.${lockAt("locked").dumpKey})
       |  }
       |}
       |""".stripMargin

  /** The rendered page, and the one attribute this is about. */
  private def lockPage(state: String): IO[(String, String)] =
    TestServer
      .fromWorkspace("fixture-lock", lockEntry, List(lockAt(state)))
      .use(_.page().map { html =>
        // BY CONTENT, not by position: the offline banner's reload button
        // carries the page's first `data-on:click`, long before any card.
        val click = html
          .split("data-on:click=\"")
          .toList
          .map(_.takeWhile(_ != '"'))
          .find(_.contains("sse/action"))
        (html, click.getOrElse(fail(s"no action click in: $html")))
      })

  test("a lock's tap reads its service from a signal, not from the markup") {
    (lockPage("locked"), lockPage("unlocked"))
      .mapN {
        case ((lockedHtml, lockedClick), (unlockedHtml, unlockedClick)) => {
          // THE property: the element is identical whichever way the lock is
          // turned, so it stays in the renderer's identity cache and a
          // lock/unlock costs a signals frame instead of a repaint.
          assertEquals(lockedClick, unlockedClick)
          // It reads the service rather than naming one...
          assert(
            lockedClick.contains("+ $_e.lock.front_door."),
            clue = lockedClick
          )
          // ...so neither service appears in the markup at all.
          assert(!lockedClick.contains("lock/unlock"), clue = lockedClick)
          assert(!lockedClick.contains("lock/lock"), clue = lockedClick)
          // The node id is a build-time constant, not a click-time `dataset`
          // read — which is what an UNGUARDED tap could not have done.
          assert(!lockedClick.contains("dataset"), clue = lockedClick)

          // The service itself is in the SEED, which is what makes a first
          // paint correct with no frame behind it — and it is the right one for
          // the state, which is the whole `CallByState` table doing its job.
          assert(lockedHtml.contains("'lock/unlock'"), clue = lockedHtml)
          assert(unlockedHtml.contains("'lock/lock'"), clue = unlockedHtml)

          // And the domain's transitional states are bound as the inert class
          // (ADR 0016), so a tap mid-move cannot fight the command running.
          assert(
            lockedHtml.contains("data-class:fh-inert"),
            clue = lockedHtml
          )
        }
      }
      .timeout(60.seconds)
  }

  test("a lock mid-move is inert, and a lock at rest is not") {
    (lockPage("locked"), lockPage("unlocking"))
      .mapN {
        case ((restHtml, _), (movingHtml, _)) => {
          // The class is the VALUE of a signal slot, so the document form paints
          // it inline: present while the lock is moving, empty at rest.
          assert(movingHtml.contains("fh-inert"), clue = movingHtml)
          assert(
            !restHtml.contains("card entity tappable fh-inert"),
            clue = restHtml
          )
        }
      }
      .timeout(60.seconds)
  }

  test("a dashboard says what happens to a label that does not fit") {
    // The knob is authored in two places at once — the entry's default and one
    // card that differs — and only a real page proves they meet: the default is
    // a `:root` block the entry composes into `css`, and the override is a cell
    // class the RENDERER puts on the wrapper. Neither side sees the other.
    val textEntry =
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |textOverflow = "scroll"
         |
         |card = (c.column) {
         |  children {
         |    c.entityCard(dump.entities.${HouseFixture.outsideTemp.dumpKey})
         |    c.entityCard(dump.entities.${HouseFixture.kitchenLight.dumpKey})
         |      .textOverflow("wrap")
         |  }
         |}
         |""".stripMargin
    TestServer
      .fromWorkspace("fixture-text", textEntry, entities)
      .use { ts =>
        ts.page().map { html =>
          // The dashboard's answer, at the root, so a card that says nothing
          // inherits it instead of carrying a copy.
          assert(
            html.contains(":root{--fh-text-lines:nowrap") &&
              html.contains("--fh-text-motion:fh-text-scroll"),
            clue = html
          )
          // The one card that differs, on its own wrapper. It beats the root by
          // being NEARER, which is the property the custom-property carrier was
          // chosen for — a selector would have tied and let file order decide.
          assert(html.contains("fh-text-wrap"), clue = html)
          // And the boxes the modes act on are really in the markup — the label
          // and the live reading both.
          assert(
            html.contains(
              """<span class="fh-text"><span class="fh-text-run">Kitchen"""
            ),
            clue = html
          )
          assert(
            html.contains(
              """<span class="state fh-text"><span class="fh-text-run" data-text="""
            ),
            clue = html
          )
        }
      }
      .timeout(60.seconds)
  }
}
