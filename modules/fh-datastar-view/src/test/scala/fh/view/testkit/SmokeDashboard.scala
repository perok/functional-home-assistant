package fh.view.testkit

import fh.view.model.Dashboard

/** The Pkl-authored dashboard the browser suites (`fh.view.smoke`) drive:
  * real `theme-beer.pkl` chrome/CSS (unlike [[PklFixture.dummyTheme]] fixtures
  * elsewhere, these tests exist specifically to exercise real CSS/JS in a real
  * browser), plus one of each interaction class a UI/visual smoke test needs
  * something to click — a popup trigger, a tab bar, and a brightness slider —
  * over the [[HouseFixture]] entities, so the served state and the dashboard
  * can never drift (same discipline as [[PklFixture]]).
  */
object SmokeDashboard {

  private val entrySource =
    s"""amends "lib/entry.pkl"
       |
       |import "lib/components.pkl" as c
       |import "lib/dump.pkl" as dump
       |
       |title = "Smoke House"
       |
       |surfaces {
       |  ["detail"] {
       |    body {
       |      c.title("Kitchen Detail")
       |      c.entityCard(dump.entities.${HouseFixture.kitchenLight.dumpKey})
       |      c.button("Close", c.closePopup())
       |    }
       |  }
       |}
       |
       |card = (c.column) {
       |  children {
       |    c.title("Smoke House")
       |    c.entityCard(dump.entities.${HouseFixture.outsideTemp.dumpKey})
       |    c.entityCard(dump.entities.${HouseFixture.kitchenLight.dumpKey}).tap(c.openPopup("detail"))
       |    c.button("Toggle Kitchen", c.serviceTap("light/toggle")).entity(dump.entities.${HouseFixture.kitchenLight.dumpKey})
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

  val dashboard: Dashboard = PklFixture.buildDashboard("smoke-house", entrySource)
}
