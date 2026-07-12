package fh.view.testkit

import fh.view.model.{CardDef, Dashboard, LayoutNode, SlotSource}

/** A small hand-built dashboard over the [[HouseFixture]] entities — the
  * Tier-B system under test for the functional suite (fast, no Pkl/dump
  * ceremony; a Pkl-built Tier-A dashboard is a separate capstone).
  *
  * It binds enough of the fixture to exercise the live paths: a numeric reading
  * with a unit (from `$attr`), and a light whose state changes over time.
  */
object FixtureDashboard {

  private def component(
      card: String,
      slots: (String, SlotSource)*
  ): LayoutNode.Component =
    LayoutNode.Component(card, slots.toMap)

  private val cards = Map(
    "col" -> CardDef("""<div class="col">{{#children}}{{{html}}}{{/children}}</div>"""),
    // A value reading: state plus a unit pulled from the entity's attributes.
    "reading" -> CardDef(
      """<div class="reading"><span>{{state}}</span> {{unit}}</div>""",
      slots = List("state")
    ),
    // A named on/off tile.
    "light" -> CardDef(
      """<div class="light">{{name}}: <span>{{state}}</span></div>""",
      slots = List("state")
    )
  )

  val dashboard: Dashboard = Dashboard(
    cards = cards,
    card = LayoutNode.Component(
      "col",
      children = List(
        component(
          "reading",
          "state" -> SlotSource(Some(HouseFixture.outsideTemp.entityId)),
          "unit" -> SlotSource(
            Some(HouseFixture.outsideTemp.entityId),
            "$attr.unit_of_measurement"
          )
        ),
        component(
          "light",
          "name" -> SlotSource(literal = Some("Kitchen")),
          "state" -> SlotSource(Some(HouseFixture.kitchenLight.entityId))
        )
      )
    ),
    slug = "home",
    title = Some("Test Home")
  )
}
