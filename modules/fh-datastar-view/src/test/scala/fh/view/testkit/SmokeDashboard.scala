package fh.view.testkit

import fh.view.model.Dashboard
import io.circe.Json

/** The dashboard the browser suites drive: the real `theme-beer.pkl`, since
  * these exist to exercise real CSS and JS, plus one of each interaction class
  * (a popup trigger, a tab bar, a brightness slider, `c.lock.controls`) over
  * the [[HouseFixture]] entities. The text font is pinned
  * ([[fontPinnedTheme]]), test-only, so the visual baselines are portable.
  */
object SmokeDashboard {

  /** BeerCSS's `--font` stack leads with `Inter` but never loads it, so each
    * machine falls back to a different system sans, enough to blow the
    * [[VisualSnapshot]] budget. `AssetCache` localizes `@fontsource/inter` like
    * the MDI icon font, leaving only sub-pixel antialiasing. The live theme
    * keeps its system stack.
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
       |    c.button("Toggle Kitchen", c.tap.call("light/toggle", dump.entities.${HouseFixture.kitchenLight.dumpKey}))
       |    c.entitySlider(dump.entities.${HouseFixture.kitchenLight.dumpKey})
       |    c.lock.controls(dump.entities.${HouseFixture.frontLock.dumpKey})
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

  /** Its own dashboard so photographing it does not move `full-dashboard.png`
    * too.
    */
  val appliance: Dashboard =
    PklFixture.buildDashboard(
      "smoke-appliance",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |title = "Smoke Appliance"
         |
         |$fontPinnedTheme
         |
         |card = (c.column) {
         |  children {
         |    (c.progress(dump.entities.${HouseFixture.washerRemaining.dumpKey})) {
         |      total = dump.entities.${HouseFixture.washerProgram.dumpKey}
         |      status = dump.entities.${HouseFixture.washerStatus.dumpKey}
         |    }
         |  }
         |}
         |""".stripMargin
    )

  /** The readout a drag must move itself, since it follows the position. Its
    * own dashboard: a card on [[dashboard]] is a new PNG baseline for a
    * behavioural test.
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
          |    c.entitySlider(dump.entities.${HouseFixture.kitchenLight.dumpKey}).readout("percent")
          |  }
          |}
          |""".stripMargin
    )

  /** The power button carries an `i.mdi` icon and the busy binding: the
    * icon-turns-spinner case. Its own dashboard for the baseline reason.
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
         |    c.entitySlider(dump.entities.${HouseFixture.kitchenLight.dumpKey}).tapAction(c.tap.call("light/toggle", dump.entities.${HouseFixture.kitchenLight.dumpKey}))
         |  }
         |}
         |""".stripMargin
    )

  val longName = "Kitchen Ceiling Spotlights Above The Sink"

  /** A plain row, a group's head and a member row, each fitting and
    * overflowing. The squeezed badge and pushed-off readout appear only on
    * overflow and differ per shape (a member's head is a grid item), so one
    * long label proves nothing about the others.
    */
  val longLabelRows: Dashboard =
    PklFixture.buildDashboard(
      "smoke-long-label",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |title = "Smoke Long Label"
         |
         |$fontPinnedTheme
         |
         |card = (c.column) {
         |  children {
         |    (c.entitySlider(dump.entities.${HouseFixture.kitchenLight.dumpKey})) {
         |      label = "Short"
         |      readout = "percent"
         |    }
         |    (c.entitySlider(dump.entities.${HouseFixture.kitchenLight.dumpKey})) {
         |      label = "$longName"
         |      readout = "percent"
         |    }
         |    (c.entitySlider(dump.entities.${HouseFixture.kitchenLight.dumpKey})) {
         |      label = "$longName"
         |      readout = "percent"
         |      members {
         |        (c.entitySlider(dump.entities.${HouseFixture.livingRoomLight.dumpKey})) {
         |          label = "$longName"
         |          readout = "percent"
         |        }
         |        (c.entitySlider(dump.entities.${HouseFixture.kitchenLight.dumpKey})) {
         |          label = "Short"
         |          readout = "percent"
         |        }
         |      }
         |    }
         |  }
         |}
         |""".stripMargin
    )

  /** Its only colour mode is `onoff`: HA's statement that it only switches. Not
    * in the house (see [[HouseFixture.dumpWith]]), so a scene seeds it with
    * `.entity(...)`.
    */
  val switchLight: FixtureEntity = FixtureEntity(
    "light.plug",
    "on",
    Map(
      "friendly_name" -> Json.fromString("Plug"),
      "supported_color_modes" -> Json.arr(Json.fromString("onoff"))
    )
  )

  /** A switch beside the tile it should look like, and one whose label does not
    * fit a phone.
    */
  val switchCards: Dashboard =
    PklFixture.buildDashboard(
      "smoke-switch-cards",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |title = "Smoke Switch Cards"
         |
         |$fontPinnedTheme
         |
         |card = (c.column) {
         |  children {
         |    c.entityCard(dump.entities.${switchLight.dumpKey})
         |    c.toggle(dump.entities.${switchLight.dumpKey})
         |    (c.toggle(dump.entities.${switchLight.dumpKey})) { label = "$longName" }
         |  }
         |}
         |""".stripMargin,
      HouseFixture.dumpWith(switchLight)
    )

  /** Nothing to drag, so the whole track is one button. A second line makes the
    * card taller than a button, the difference that made the target miss.
    */
  val switchSlider: Dashboard =
    PklFixture.buildDashboard(
      "smoke-switch",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |
         |title = "Smoke Switch"
         |
         |$fontPinnedTheme
         |
         |card = (c.column) {
         |  children {
         |    c.entitySlider(dump.entities.${switchLight.dumpKey}).secondary(c.attr("friendly_name"))
         |  }
         |}
         |""".stripMargin,
      HouseFixture.dumpWith(switchLight)
    )
}
