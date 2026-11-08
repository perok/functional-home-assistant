package fh.view.testkit

import io.circe.Json

/** Test-owned dashboards, not the shipped ones, which are free to evolve: one
  * concern each, so a change to one shape moves one file. Each has a wire
  * snapshot (`PklBuildSuite`) and a golden (`RenderGoldenSuite`); all but
  * [[theme]] set a dummy theme, so theme CSS is out of scope.
  */
object FixtureEntries {

  /** A test dashboard, built over the house plus the entities only it needs. */
  final case class Entry(
      slug: String,
      source: String,
      extras: List[FixtureEntity] = Nil
  ):
    def dump: Json = HouseFixture.dumpWith(extras*)
    def entities: List[FixtureEntity] = HouseFixture.all ++ extras

  private val imports =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/query.pkl" as q
      |import "@fh-home/dump.pkl" as dump
      |""".stripMargin

  private val header =
    s"""$imports
       |import "@fh-dashboard/theme.pkl" as th
       |
       |theme = ${PklFixture.dummyTheme}
       |
       |local kitchen = dump.entities.light_kitchen
       |local temp = dump.entities.sensor_outside_temp
       |""".stripMargin

  /** Each card's own default render, one per kind of entity, on and off. */
  val cards: Entry = Entry(
    "cards",
    header +
      s"""
        |card = (c.column) {
        |  children {
        |    c.entityCard(temp)
        |    c.entityCard(kitchen)
        |    c.entityCard(dump.entities.light_living_room)
        |    c.entityCard(dump.entities.climate_hallway)
        |    c.entityCard(dump.entities.binary_sensor_front_door)
        |    c.entityCard(dump.entities.media_player_tv)
        |    c.entityCard(dump.entities.lock_front_door)
        |    c.toggle(kitchen)
        |    c.toggle(dump.entities.light_living_room)
        |    c.toggle(dump.entities.light_plug)
        |    c.entityButton(kitchen)
        |    c.entityCard(dump.entities.light_spotlights)
        |    c.entityButton(dump.entities.light_spotlights).secondary("on")
        |  }
        |}
        |""".stripMargin,
    List(SmokeDashboard.switchLight, SmokeDashboard.longNameLight)
  )

  /** What a tap does: a call, on an entity and on a set, more-info, a popup and
    * an inline popup, a navigation, and a lock's own buttons.
    */
  val taps: Entry = Entry(
    "taps",
    header +
      """
        |surfaces {
        |  ["detail"] {
        |    body {
        |      c.title("Detail")
        |      c.button("Close", c.view.closePopup())
        |    }
        |  }
        |}
        |
        |card = (c.column) {
        |  children {
        |    c.entityCard(kitchen).tapAction(c.tap.call("homeassistant/toggle", kitchen))
        |    c.entityCard(kitchen) |> c.informative
        |    c.entityCard(kitchen).tapAction(c.view.moreInfo(kitchen))
        |    c.entityCard(kitchen).tapAction(c.view.openPopup("detail"))
        |    c.button("Lights off", c.tap.call("light/turn_off", q.from(dump.lights.all).where(q.eq(q.stateProp, "on"))))
        |    c.button("Inline…", c.view.openPopupInline(new c.Column {
        |      children {
        |        c.title("Inline")
        |        c.button("Close", c.view.closePopup())
        |      }
        |    }))
        |    c.lock.controls(dump.entities.lock_front_door)
        |    c.button("Home", c.view.navigate("home"))
        |  }
        |}
        |""".stripMargin
  )

  /** The two inline-surface hoist paths, tab panels and branches, and a branch
    * a node variable picks.
    */
  val branches: Entry = Entry(
    "branches",
    header +
      """
        |local view = c.variable("view")
        |
        |card = (c.column) {
        |  children {
        |    (c.tabs) {
        |      tabs {
        |        ["Light"] { c.toggle(kitchen) }
        |        ["Text"] { c.title("Text") }
        |      }
        |    }
        |    c.iff(q.entity(kitchen).stateIs("on"))
        |      .then(c.toggle(kitchen))
        |      .`else`(c.title("Off"))
        |    (c.chooser(c.select(view).options(List("simple", "detail")))) {
        |      children {
        |        c.iff(view.valueIs("detail")).then(c.title("Detail")).`else`(c.title("Simple"))
        |      }
        |    }
        |  }
        |}
        |""".stripMargin
  )

  /** A candidate set: filtered, ordered and cut, and its leader as a condition.
    */
  val sets: Entry = Entry(
    "sets",
    header +
      """
        |local lights = q.from(dump.lights.all)
        |local brightest = lights.orderBy(q.desc(q.attr("brightness")))
        |
        |card = (c.column) {
        |  children {
        |    lights.where(q.eq(q.stateProp, "on")).render((e) -> c.toggle(e)).build()
        |    brightest.limit(1).render((e) -> c.toggle(e)).build()
        |    c.pill(c.tap.toggle(kitchen)).active(brightest.first().eq(kitchen))
        |  }
        |}
        |""".stripMargin
  )

  /** The slot shapes: CEL with and without an entity, attribute reads, another
    * entity's state, a live count read by name, the bool inputs and the cell
    * slots.
    */
  val readings: Entry = Entry(
    "readings",
    header +
      """
        |local lit = q.from(dump.lights.all).where(q.eq(q.stateProp, "on"))
        |
        |card = (c.column) {
        |  children {
        |    c.entityButton(kitchen)
        |      .cellClassWhen("warm", c.isOn(kitchen))
        |      .cellStyle("--fh-x", c.attr("brightness"))
        |    c.pill(c.tap.toggle(kitchen)).active(c.stateIn(kitchen, List("on", "unknown")))
        |    c.button("Lights off", c.tap.call("light/turn_off", lit))
        |      .secondary(c.expr(#"string(lit) + ' on'"#))
        |      .disabled(lit.none())
        |      .inputs(new Mapping { ["lit"] = lit.count() })
        |    c.entityCard(temp)
        |      .value(c.expr(#"str(math.round(num(value.state) * 10.0) / 10.0) + ' ' + value.attr[?'unit_of_measurement'].orValue('')"#))
        |      .secondary(c.attr("device_class"))
        |    c.entityCard(kitchen).label(c.exprOf(dump.entities.light_living_room, "value.state"))
        |  }
        |}
        |""".stripMargin
  )

  /** A slot that has a value only while a condition holds, and a second line
    * read from another entity.
    */
  val progress: Entry = Entry(
    "progress",
    header +
      """
        |card = (c.column) {
        |  children {
        |    c.progress(dump.entities.sensor_washer_remaining)
        |      .total(dump.entities.sensor_washer_program_duration)
        |      .status(dump.entities.sensor_washer_status)
        |  }
        |}
        |""".stripMargin
  )

  /** A fetch: a chart, one whose window a chooser picks, and readings of a
    * history by name.
    */
  val charts: Entry = Entry(
    "charts",
    header +
      """
        |card = (c.column) {
        |  children {
        |    c.history.chart(temp)
        |    (c.history.chooser) { children { c.history.chart(temp).fromChooser() } }
        |    c.examples.historyReadings(temp)
        |  }
        |}
        |""".stripMargin
  )

  /** One light's slider: its readouts, a button in its pill, and a switch with
    * nothing to drag.
    */
  val sliders: Entry = Entry(
    "sliders",
    header +
      """
        |card = (c.column) {
        |  children {
        |    c.entitySlider(kitchen)
        |    c.entitySlider(kitchen).readout("percent")
        |    c.entitySlider(kitchen).buttons(List(c.pill(c.tap.call("light/toggle", kitchen)).icon("mdi:power")))
        |    c.entitySlider(dump.entities.light_plug).secondary(c.attr("friendly_name"))
        |  }
        |}
        |""".stripMargin,
    List(SmokeDashboard.switchLight)
  )

  /** A colour light's main controls: opened on its mode, and switched from a
    * dropdown beside the colour wheel.
    */
  val lightControls: Entry = Entry(
    "light-controls",
    header +
      """
        |local l = dump.entities.light_lamp
        |
        |card = (c.grid) {
        |  children {
        |    c.entitySlider(l).opensOn(List(l.colourTemp, l.colour))
        |    c.entitySlider(l)
        |      .buttons(List(c.tap.toggle(l), c.its.mainControl.style("dropdown"), c.view.colourWheel(l.colour)))
        |  }
        |}
        |""".stripMargin,
    List(SmokeDashboard.colourLamp)
  )

  /** A slider's rows: following the head's chooser, each with its own pill, one
    * too long to fit, and a card among them.
    */
  val sliderGroups: Entry = Entry(
    "slider-groups",
    header +
      s"""
        |local lamp = dump.entities.light_lamp
        |local lamp2 = dump.entities.light_lamp2
        |local dimmer = dump.entities.light_dimmer
        |
        |card = (c.column) {
        |  children {
        |    c.entitySlider(lamp).buttons(List(c.tap.toggle(lamp), c.its.mainControl)).withSubSliders(List(
        |      c.entitySlider(lamp2).readout("percent").icon(null).fromChooser(),
        |      c.entitySlider(dimmer).readout("percent").icon(null).fromChooser()
        |    ))
        |    c.entitySlider(dimmer).buttons(List(c.tap.toggle(dimmer))).secondary("Kitchen").withSubSliders(List(
        |      (c.entitySlider(lamp).readout("percent").icon(null)) { label = "${SmokeDashboard.longName}" },
        |      c.entitySlider(lamp2).readout("percent").icon(null)
        |    ))
        |    (c.entitySlider(kitchen)) { members { c.entityCard(dump.entities.light_living_room) } }
        |  }
        |}
        |""".stripMargin,
    List(
      SmokeDashboard.colourLamp,
      SmokeDashboard.colourLamp2,
      SmokeDashboard.groupDimmer
    )
  )

  /** The containers, nested: a row of pills in a column, and a grid. */
  val layout: Entry = Entry(
    "layout",
    header +
      """
        |card = (c.column) {
        |  children {
        |    (c.row) {
        |      children {
        |        c.pill(c.tap.toggle(kitchen)).label("Kveld")
        |        c.pill(c.tap.toggle(kitchen)).label("Morgen")
        |      }
        |    }
        |    (c.grid) {
        |      children {
        |        c.button("A", c.tap.toggle(kitchen))
        |        c.button("B", c.tap.toggle(kitchen))
        |      }
        |    }
        |  }
        |}
        |""".stripMargin
  )

  /** The shipped theme's classes on the cards that carry them. Its wire
    * snapshot would repeat [[cards]]': the snapshot strips the theme.
    */
  val theme: Entry = Entry(
    "theme",
    imports +
      """
        |local kitchen = dump.entities.light_kitchen
        |
        |card = (c.column) {
        |  children {
        |    c.title("Theme")
        |    c.entityCard(kitchen)
        |    c.toggle(kitchen)
        |    c.entitySlider(kitchen)
        |    c.pill(c.tap.toggle(kitchen))
        |    c.button("Toggle", c.tap.toggle(kitchen))
        |    (c.tabs) {
        |      tabs {
        |        ["One"] { c.title("One") }
        |        ["Two"] { c.title("Two") }
        |      }
        |    }
        |  }
        |}
        |""".stripMargin
  )

  val all: List[Entry] = List(
    cards,
    taps,
    branches,
    sets,
    readings,
    progress,
    charts,
    sliders,
    lightControls,
    sliderGroups,
    layout,
    theme
  )

  /** Those with a wire snapshot. */
  val wired: List[Entry] = all.filterNot(_ == theme)
}
