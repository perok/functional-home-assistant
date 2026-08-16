package fh.view.testkit

import fh.view.model.Dashboard

/** The Pkl-authored dashboard the browser suites (`fh.view.smoke`) drive: real
  * `theme-beer.pkl` chrome/CSS (unlike [[PklFixture.dummyTheme]] fixtures
  * elsewhere, these tests exist specifically to exercise real CSS/JS in a real
  * browser), plus one of each interaction class a UI/visual smoke test needs
  * something to click — a popup trigger, a tab bar, and a brightness slider —
  * over the [[HouseFixture]] entities, so the served state and the dashboard
  * can never drift (same discipline as [[PklFixture]]).
  *
  * The theme amend pins the text font (see [[fontPinnedTheme]]) — TEST-ONLY, so
  * [[fh.view.smoke.ComponentVisualSuite]]'s baselines are portable between a
  * local machine and CI's `ubuntu-latest`.
  */
object SmokeDashboard {

  /** Amend the inherited BeerTheme to bundle a text webfont, appending nothing
    * else. BeerCSS's `--font` stack already LEADS with `Inter` but never loads
    * it, so each machine falls back to a different system sans — a difference
    * dramatic at heading size and enough to blow the [[VisualSnapshot]] budget
    * on every component. `@fontsource/inter`'s relative `url(...)` woff2 refs
    * are localized by `AssetCache` exactly like the Material Symbols icon font,
    * so both environments render byte-identical glyphs and only sub-pixel
    * FreeType antialiasing is left — which the perceptual diff already
    * forgives. This makes [[VisualSnapshot]]'s "every asset (fonts included) is
    * pinned" claim actually true. TEST-ONLY: the live `theme-beer.pkl` keeps
    * its system stack (a real user's browser picks the first font it has).
    */
  private val fontPinnedTheme =
    """theme {
      |  stylesheets {
      |    "https://cdn.jsdelivr.net/npm/@fontsource/inter@5/latin.css"
      |  }
      |}""".stripMargin

  private val entrySource =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |title = "Smoke House"
       |
       |$fontPinnedTheme
       |
       |surfaces {
       |  ["detail"] {
       |    body {
       |      c.title("Kitchen Detail")
       |      c.entityCard(dump.entities.${HouseFixture.kitchenLight.dumpKey})
       |      c.button("Close", c.tap.closePopup())
       |    }
       |  }
       |}
       |
       |card = (c.column) {
       |  children {
       |    c.title("Smoke House")
       |    c.entityCard(dump.entities.${HouseFixture.outsideTemp.dumpKey})
       |    c.entityCard(dump.entities.${HouseFixture.kitchenLight.dumpKey}).tapAction(c.tap.openPopup("detail"))
       |    c.button("Toggle Kitchen", c.tap.service("light/toggle")).entity(dump.entities.${HouseFixture.kitchenLight.dumpKey})
       |    c.slider(dump.entities.${HouseFixture.kitchenLight.dumpKey})
       |    (c.tabs) {
       |      tabs {
       |        ["Lights"] { c.entityCard(dump.entities.${HouseFixture.livingRoomLight.dumpKey}) }
       |        ["Climate"] { c.entityCard(dump.entities.${HouseFixture.hallwayClimate.dumpKey}) }
       |      }
       |    }
       |  }
       |}
       |""".stripMargin

  val dashboard: Dashboard =
    PklFixture.buildDashboard("smoke-house", entrySource)

  /** A slider whose line reads out its LEVEL — the readout a drag has to move
    * itself, since it is a function of the position rather than of the state.
    * Its own dashboard rather than a sixth card on [[dashboard]], because that
    * one is what [[fh.view.smoke.ComponentVisualSuite]] photographs and a new
    * card there is a new PNG baseline for a behavioural test.
    */
  val percentSlider: Dashboard =
    PklFixture.buildDashboard(
      "smoke-percent",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |title = "Smoke Percent"
         |
          |$fontPinnedTheme
          |
          |card = (c.column) {
          |  children {
          |    c.slider(dump.entities.${HouseFixture.kitchenLight.dumpKey}).readout("percent")
          |  }
          |}
          |""".stripMargin
    )

  /** A slider whose POWER BUTTON is a busy-guarded tap: the button carries
    * both an `i.mdi` icon and `data-class:fh-busy`, which is the "icon turns
    * into a spinner while the call is in flight" case ([[fh.view.smoke.
    * ControlSmokeSuite]] drives it). Its own dashboard rather than a card on
    * [[dashboard]], for the same PNG-baseline reason [[percentSlider]] has
    * its own.
    */
  val busyIcon: Dashboard =
    PklFixture.buildDashboard(
      "smoke-busy-icon",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |title = "Smoke Busy Icon"
         |
         |$fontPinnedTheme
         |
         |card = (c.column) {
         |  children {
         |    c.slider(dump.entities.${HouseFixture.kitchenLight.dumpKey}).tapAction(c.tap.service("light/toggle"))
         |  }
         |}
         |""".stripMargin
    )
}
