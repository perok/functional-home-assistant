package fh.view.testkit

/** Test-owned entries, not the shipped dashboards, which are free to evolve.
  * They set a dummy theme, so theme CSS is out of scope. Each has a wire
  * snapshot (`PklBuildSuite`) and a golden (`RenderGoldenSuite`).
  */
object FixtureEntries {

  private val header =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
       |import "@fh-home/dump.pkl" as dump
       |import "@fh-dashboard/theme.pkl" as th
       |
       |theme = ${PklFixture.dummyTheme}
       |""".stripMargin

  val features: String =
    header +
      """
        |surfaces {
        |  ["detail"] {
        |    body {
        |      c.title("Detail")
        |      c.entityCard(dump.entities.sensor_outside_temp)
        |      c.button("Close", c.view.closePopup())
        |    }
        |  }
        |}
        |
        |card = (c.column) {
        |  children {
        |    c.title("Features")
        |    c.entityCard(dump.entities.sensor_outside_temp)
        |    c.entityCard(dump.entities.light_kitchen).tapAction(c.tap.call("homeassistant/toggle", dump.entities.light_kitchen))
        |    c.entityCard(dump.entities.light_kitchen) |> c.informative
        |    c.entitySlider(dump.entities.light_kitchen)
        |    q.from(dump.lights.all)
        |      .where(q.eq(q.stateProp, "on"))
        |      .render((e) -> c.entityCard(e))
        |      .build()
        |    c.button("Detail…", c.view.openPopup("detail"))
        |    c.button("Inline…", c.view.openPopupInline(new c.Column {
        |      children {
        |        c.title("Inline")
        |        c.button("Close", c.view.closePopup())
        |      }
        |    }))
        |  }
        |}
        |""".stripMargin

  /** The two inline-surface hoist paths: tab panels and branches. */
  val surfaces: String =
    header +
      """
        |card = (c.column) {
        |  children {
        |    (c.tabs) {
        |      tabs {
        |        ["Temp"] { c.entityCard(dump.entities.sensor_outside_temp) }
        |        ["Light"] { c.entityCard(dump.entities.light_kitchen) }
        |      }
        |    }
        |    c.iff(q.entity(dump.entities.light_kitchen).stateIs("on"))
        |      .then(c.entityCard(dump.entities.light_kitchen))
        |      .`else`(c.entityCard(dump.entities.sensor_outside_temp))
        |  }
        |}
        |""".stripMargin

  /** The slot shapes the other fixtures and the smoke dashboards do not reach:
    * CEL with and without an entity, attribute reads, bool inputs, cell slots,
    * a live count read by name, charts and a node variable's chooser.
    */
  val slots: String =
    header +
      """
        |local kitchen = dump.entities.light_kitchen
        |local temp = dump.entities.sensor_outside_temp
        |local lit = q.from(dump.lights.all).where(q.eq(q.stateProp, "on"))
        |local view = c.variable("view")
        |
        |card = (c.column) {
        |  children {
        |    c.toggle(kitchen)
        |    c.entityButton(kitchen)
        |      .cellClassWhen("warm", c.isOn(kitchen))
        |      .cellStyle("--fh-x", c.attr("brightness"))
        |    c.pill(c.tap.toggle(kitchen)).active(c.stateIn(kitchen, List("on", "unknown")))
        |    c.button("Lights off", c.tap.call("light/turn_off", lit))
        |      .secondary(c.expr(#"string(lit) + ' on'"#))
        |      .disabled(lit.none())
        |      .expressionValues(new Mapping { ["lit"] = lit.count() })
        |    c.entityCard(temp)
        |      .value(c.expr(#"str(math.round(num(value.state) * 10.0) / 10.0) + ' ' + value.attr[?'unit_of_measurement'].orValue('')"#))
        |      .secondary(c.attr("device_class"))
        |    c.entityCard(kitchen).label(c.exprOf(dump.entities.light_living_room, "value.state"))
        |    c.entityCard(kitchen).tapAction(c.view.moreInfo(kitchen))
        |    c.entityCard(dump.entities.climate_hallway)
        |    c.entityCard(dump.entities.binary_sensor_front_door)
        |    c.entityCard(dump.entities.media_player_tv)
        |    c.progress(dump.entities.sensor_washer_remaining)
        |      .total(dump.entities.sensor_washer_program_duration)
        |      .status(dump.entities.sensor_washer_status)
        |    c.history.chart(temp)
        |    (c.history.chooser) { children { c.history.chart(temp).fromChooser() } }
        |    c.examples.historyReadings(temp)
        |    (c.chooser(c.select(view).options(List("simple", "detail")))) {
        |      children {
        |        c.iff(view.valueIs("detail")).then(c.history.chart(temp)).`else`(c.title("Simple"))
        |      }
        |    }
        |  }
        |}
        |""".stripMargin
}
