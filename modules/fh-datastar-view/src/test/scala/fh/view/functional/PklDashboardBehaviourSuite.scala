package fh.view.functional

import cats.effect.IO
import cats.syntax.all.*
import fh.view.runtime.TestServer
import io.circe.Json
import fh.view.testkit.{FixtureEntity, HouseFixture}

import scala.concurrent.duration.*

/** The Tier-A capstone (ADR 0009): [[DashboardBehaviourSuite]]'s behaviour, but
  * from a real Pkl entry through `TestServer.fromWorkspace`, the sequence
  * production's `run` uses. The dump is fetched from the fake's
  * `render_template`, and it and the seeded state come from one
  * [[FixtureEntity]] set, so they cannot drift.
  */
class PklDashboardBehaviourSuite extends munit.CatsEffectSuite {

  /** The entry's entities, the fake's seed and the source of its dump. */
  private val entities: List[FixtureEntity] =
    List(HouseFixture.outsideTemp, HouseFixture.kitchenLight)

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

  /** The one node with two guarded elements, so the button's `_<id>__busy` and
    * the input's `_<id>__busy_change` must never collide.
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
      assert(html.contains("Outside Temperature"), clue = html)
      assert(html.contains("12.4"), clue = html)
      assert(html.contains("°C"), clue = html)
      assert(html.contains("Kitchen"), clue = html)
      assert(html.contains(">on<"), clue = html)
    }
  }

  test("a navigating button reaches the browser as a real link") {
    withServer(_.page()).map { html =>
      // ADR 0002 end to end: c.navigate ships as an anchor with a relative href
      // against <base href>, which works before Datastar loads.
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
      // The default service tap is guarded (ADR 0016; `tap.pkl`'s `busyGuard`,
      // `busyAttrs`, `busyClass`): a no-op click while in flight, an indicator
      // on `_<id>__busy`, and `fh-disabled` plus `fh-loading` on the same
      // signal.
      assert(html.contains("data-indicator=\"_c_2__busy\""), clue = html)
      assert(
        html.contains("data-class:fh-disabled=\"$_c_2__busy\""),
        clue = html
      )
      assert(
        html.contains("data-class:fh-loading=\"$_c_2__busy\""),
        clue = html
      )
      // Neither guard subsumes the other: busy is this tap's POST in flight,
      // inert is an entity state that refuses the press.
      assert(
        html.contains(
          "data-on:click=\"$_c_2__busy ? '' : $_e.light.kitchen."
        ),
        clue = html
      )
      assert(
        html.contains(
          "? '' : @post('sse/action/fixture-home/' + 'light/toggle'"
        ),
        clue = html
      )
      // The busy signal is created client-side by the indicator, so only
      // no-signals POSTs keep it out of the request body.
      assert(html.contains("{filterSignals:{exclude:'.*'}}"), clue = html)
      // A more-info tap is guarded too: the open answers once the popup's
      // queries have resolved, and a chart's fetch is a wait (issue #412).
      assert(html.contains("data-indicator=\"_c_1__busy\""), clue = html)
      assert(html.contains("data-on:click=\"$_c_1__busy"), clue = html)
      // Navigating is a document load: nothing to guard.
      assert(!html.contains("data-indicator=\"_c_3__busy\""), clue = html)
    }
  }

  test("a slider's value commit carries its own guarded, disabled input") {
    TestServer
      .fromWorkspace("fixture-slider", sliderEntry, entities)
      .use { ts =>
        ts.page().map { html =>
          // The range input commits on `change`, so it owns
          // `_<id>__busy_change` (`tap.pkl`'s `busyGuardChange` and friends).
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
          // The busy look rides the track wrapper and the head badge; the input
          // itself is frozen through `data-attr:disabled` instead.
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
          // The power button is its own node (#151), so it cannot share the
          // commit's signal: a shared name would let one element's `finished`
          // clear the other's busy.
          val button = "_c_0_head_0_actions_0__busy"
          assert(html.contains(s"""data-indicator="$button""""), clue = html)
          assert(
            html.contains(s"""data-on:click="$$$button ? '' : """),
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
          // They differ in the node, so neither name is a prefix of the other.
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
    // The opt-out removes only the look. The guard, indicator and freeze must
    // stay byte-identical, or a fast control would lose its anti-spam when it
    // stopped dimming.
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
              html.contains("data-on:click=\"$_c_0__busy ? '' : "),
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
      ts.sentAfter(ts.frame(HouseFixture.outsideTemp.copy(state = "13.1")))
        .map(sent => assert(sent.contains("13.1"), clue = sent))
    }
  }

  // Tabs inside a conditional branch: state chooses the branch for every
  // viewer, the client chooses the tab. The fixture suites build this by hand,
  // which let a first-paint break through the real `Tabs`/`If` cards slip past
  // them.

  private val light = HouseFixture.kitchenLight // on
  private val temp = HouseFixture.outsideTemp
  private val other = HouseFixture.livingRoomLight

  private val branchEntities: List[FixtureEntity] = List(light, temp, other)

  /** `pkl-if`'s shape, minimised. */
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
      assert(html.contains("Light is on"), clue = html)
      assert(html.contains("Lights"), clue = html)
      assert(html.contains("Sensors"), clue = html)
      // The selected panel is not empty: before any script runs, an empty host
      // is a blank dashboard.
      assert(html.contains("Living Room"), clue = html)
      assert(!html.contains("Outside Temperature"), clue = html)
    }
  }

  /** Written out because it is the contract: the hoist's `bakeInto`, the
    * `ui.<host>` param and the renderer's node id are this one string, and they
    * have silently drifted apart.
    */
  private val tabsHost = "s_c_1_then__c_0_1"

  private def flip(ts: TestServer): IO[Unit] =
    ts.change(light.entityId, "off") *> ts.frame(light)

  test("each tab is guarded on its own busy signal (issue #412)") {
    withBranchServer(_.page()).map { html =>
      val tabs = s"""<a [^>]*open/${tabsHost}_t\\d[^>]*>""".r
        .findAllIn(html)
        .toList
      assertEquals(tabs.size, 2, clue = html)
      // One signal per tab (ADR 0019): a shared one would let one tab's
      // answer clear another's guard.
      val signals = tabs.map { a =>
        val sig = """data-indicator="(_[A-Za-z0-9_]+__busy)"""".r
          .findFirstMatchIn(a)
          .fold(fail("an unguarded tab", clues(a)))(_.group(1))
        assert(a.contains(s"data-on:click=\"$$$sig ? '' : "), clue = a)
        assert(a.contains(s"data-class:fh-busy-after=\"$$${sig}_slow\""), a)
        sig
      }
      assertEquals(signals.distinct.size, 2, clue = signals)
    }
  }

  test("first paint on the second tab: that panel's content, not the default") {
    withBranchServer(_.page(s"?ui.$tabsHost=1")).map { html =>
      assert(html.contains("Outside Temperature"), clue = html)
      assert(!html.contains("Living Room"), clue = html)
    }
  }

  test("a flip re-reveals the client's OWN tab, not the group's default") {
    withBranchServer { ts =>
      // The branch is re-rendered for the slug with no client, so only the fill
      // can put this viewer's panel in it.
      ts.sentAfter(flip(ts), query = s"?ui.$tabsHost=1").map { live =>
        // The branch and this viewer's panel arrive in one patch, so no frame
        // shows an empty tabs card. Not counted: how many flips land after
        // opening is timing.
        val branchPatch = live.linesIterator
          .filter(_.startsWith("elements "))
          .find(_.contains("Light is on"))
        assert(branchPatch.isDefined, clue = live)
        assert(
          branchPatch.exists(_.contains("Outside Temperature")),
          clue = ("the branch must arrive with this viewer's panel", live)
        )
        // The default tab's content must reach nothing this client was sent
        // after opening.
        assert(!live.contains("Living Room"), clue = live)
      }
    }
  }

  test("the OTHER client keeps the default tab across the same flip") {
    withBranchServer { ts =>
      ts.sentAfter(flip(ts)).map { live =>
        val branchPatch = live.linesIterator
          .filter(_.startsWith("elements "))
          .find(_.contains("Light is on"))
        assert(
          branchPatch.exists(_.contains("Living Room")),
          clue = ("the default tab's viewer gets ITS panel", live)
        )
        assert(!live.contains("Outside Temperature"), clue = live)
      }
    }
  }

  /** The tabs host seeds its selection signal from the baked index, so a panel
    * re-revealed with the wrong index, or none, highlights a different tab than
    * the one shown. Invisible to a content check, so asserted on the wire.
    */
  test("a re-revealed panel carries THIS client's selection signal") {
    withBranchServer { ts =>
      ts.sentAfter(flip(ts), query = s"?ui.$tabsHost=1").map { live =>
        // The pending signal follows the committed one in the seed, so the
        // comma pins that a value is present.
        assert(!live.contains(s"ui_$tabsHost: ,"), clue = live)
        assert(live.contains(s"ui_$tabsHost: 1,"), clue = live)
      }
    }
  }

  test("a slider on a light that only switches renders a button, not a range") {
    // One shared template renders two shapes (issue #128), which a Pkl test
    // cannot prove: the inverted section over an absent slot is mustache.java's
    // to evaluate.
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
          // The service is spliced as a quoted literal, the spelling a
          // state-dependent tap fills with a signal read, so one template
          // serves both (ADR 0017).
          assert(
            html.contains(
              "data-on:click=\"$_c_0_head_0__busy_change ? '' : " +
                "$_e.light.plug.t722a9eca ? '' : " +
                "@post('sse/action/fixture-plug/' + 'light/toggle' + " +
                "'/light.plug?node=c_0_head_0'"
            ),
            clue = html
          )
          // The switch-fill token: a switch-only light reports no colour.
          assert(html.contains("--_end: 0%"), clue = html)
          assert(
            html.contains("background:var(--fh-default-color)"),
            clue = html
          )
          // A percentage of an axis it lacks would read 0 % forever.
          assert(html.contains(">on<"), clue = html)
          assert(!html.contains(">0 %<"), clue = html)
        }
      }
      .timeout(60.seconds)
  }

  // `CallByState`: the four domains whose service the live state picks.
  // `components.test.pkl` sees neither `Dashboard.validate` nor rendered bytes,
  // which let a validate rule demanding a var the card no longer places ship
  // green.

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

  private def lockPage(state: String): IO[(String, String)] =
    TestServer
      .fromWorkspace("fixture-lock", lockEntry, List(lockAt(state)))
      .use(_.page().map { html =>
        // By content: the offline banner's reload button carries the page's
        // first `data-on:click`.
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
          // Identical either way, so it stays in the identity cache and a
          // lock/unlock costs a signals frame, not a repaint.
          assertEquals(lockedClick, unlockedClick)
          assert(
            lockedClick.contains("+ $_e.lock.front_door."),
            clue = lockedClick
          )
          assert(!lockedClick.contains("lock/unlock"), clue = lockedClick)
          assert(!lockedClick.contains("lock/lock"), clue = lockedClick)
          // A build-time node id, not a click-time `dataset` read.
          assert(!lockedClick.contains("dataset"), clue = lockedClick)

          // The service is in the seed, correct for the state, so a first paint
          // needs no frame.
          assert(lockedHtml.contains("'lock/unlock'"), clue = lockedHtml)
          assert(unlockedHtml.contains("'lock/lock'"), clue = unlockedHtml)

          // Transitional states bind the inert class (ADR 0016), so a tap
          // mid-move cannot fight the running command.
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
          assert(movingHtml.contains("fh-inert"), clue = movingHtml)
          assert(
            !restHtml.contains("card entity tappable fh-inert"),
            clue = restHtml
          )
        }
      }
      .timeout(60.seconds)
  }

  test(
    "passthrough JSON rides a signal attribute, and a new window re-queries it"
  ) {
    // The provider's JSON is in the node's bytes, escaped, inside the attribute
    // that lifts it into a client-only signal.
    val readingsEntry =
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |card = (c.column) {
         |  children {
         |    (c.windowChooser) {
         |      children {
         |        c.historyReadings(dump.entities.${HouseFixture.outsideTemp.dumpKey}).chosen()
         |      }
         |    }
         |  }
         |}
         |""".stripMargin
    val lifted =
      """data-signals:(_hist_[A-Za-z0-9_]+)="(\{&quot;points&quot;:[^"]*)"""".r
    TestServer
      .fromWorkspace("fixture-readings", readingsEntry, entities)
      .use { ts =>
        for {
          day <- ts.page()
          declarer = """fhUrl\('v\.([A-Za-z0-9_]+)\.window'""".r
            .findFirstMatchIn(day)
            .map(_.group(1))
            .getOrElse(fail("no chooser on the page", clues(day)))
          week <- ts.page(s"?v.$declarer.window=7d")
        } yield {
          val (dayAt, weekAt) =
            (lifted.findFirstMatchIn(day), lifted.findFirstMatchIn(week))
          assert(dayAt.isDefined, clue = day)
          assert(weekAt.isDefined, clue = week)
          // So the series never rides an action or a reconnect.
          assert(dayAt.get.group(1).startsWith("_"), clue = dayAt.get.group(1))
          assertNotEquals(weekAt.get.group(2), dayAt.get.group(2))
        }
      }
      .timeout(60.seconds)
  }

  test("a window chooser reaches the browser addressing its own node") {
    // The bar is composed in Pkl from `{{id}}` tokens, and `{{id}}` is minted
    // by the renderer, the id `Server.setVar` resolves a declarer by. Only an
    // end-to-end page checks that they agree.
    val windowEntry =
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |card = (c.column) {
         |  children {
         |    ((c.windowChooser).starting("7d")) {
         |      children {
         |        c.entityCard(dump.entities.${HouseFixture.outsideTemp.dumpKey})
         |      }
         |    }
         |  }
         |}
         |""".stripMargin
    TestServer
      .fromWorkspace("fixture-windows", windowEntry, entities)
      .use { ts =>
        ts.page().map { html =>
          // An unfilled token ships as a literal: the page looks fine and every
          // press 404s.
          assert(!html.contains("{{id}}"), clue = html)
          // Off the bar's markup: the seed also names the chooser in the
          // more-info popup.
          val ids =
            """fhUrl\('v\.([A-Za-z0-9_]+)\.window', \$_var_\1__window\)""".r
              .findAllMatchIn(html)
              .map(_.group(1))
              .toSet
          assertEquals(ids.size, 1, clue = ids)
          val id = ids.head
          assert(
            html.contains(
              s"@post('sse/var/fixture-windows/$id/window/7d?" +
                s"group=var_${id}__window')"
            ),
            clue = html
          )
          // With no viewer choice the page seeds the declared window, which the
          // charts were drawn at.
          assert(html.contains(s"_var_${id}__window: '7d'"), clue = html)
          List("1h", "24h", "7d", "30d")
            .foreach(w => assert(html.contains(s">$w</a>"), clue = w))
        }
      }
      .timeout(60.seconds)
  }

  test("a dashboard says what happens to a label that does not fit") {
    // The default is a `:root` block in `css`, the override a cell class on the
    // wrapper; neither side sees the other, so only a real page proves they
    // meet.
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
          assert(
            html.contains(":root{--fh-text-lines:nowrap") &&
              html.contains("--fh-text-motion:fh-text-scroll"),
            clue = html
          )
          // It beats the root by being nearer, which is why a custom property
          // carries the mode: a selector would tie and let file order decide.
          assert(html.contains("fh-text-wrap"), clue = html)
          assert(
            html.contains(
              """<span class="fh-text"><span class="fh-text-run">Kitchen"""
            ),
            clue = html
          )
          assert(
            html.contains(
              """<span class="fh-reading fh-text"><span class="fh-text-run" data-text="""
            ),
            clue = html
          )
        }
      }
      .timeout(60.seconds)
  }
}
