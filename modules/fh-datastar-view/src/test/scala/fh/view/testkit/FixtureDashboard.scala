package fh.view.testkit

import fh.view.model.{CardDef, Dashboard, LayoutNode, SlotSource}
import fh.view.testkit.DashboardBuilders.{col, component, lit}

/** The Tier-B system under test for the functional suite (fast, no Pkl/dump
  * ceremony; a Pkl-built Tier-A dashboard is a separate capstone) — but shaped
  * as a small BUILDER, not one fixed dashboard: the shared card templates plus
  * typed constructors ([[reading]], [[light]]) that bind a card to a
  * [[FixtureEntity]], so a test composes only the cards it exercises and
  * [[build]]s them into a [[Dashboard]] over those templates.
  *
  * The card templates stay here (their exact HTML is what the behaviour tests
  * assert on), while layout composition reuses the shared [[DashboardBuilders]]
  * combinators — the split the testkit convention already draws.
  */
object FixtureDashboard {

  /** The card templates every fixture dashboard is rendered over: a `col`
    * container, a numeric `reading` (state + a unit pulled from `$attr`), and a
    * named on/off `light` tile.
    */
  val cards: Map[String, CardDef] = Map(
    "col" -> CardDef(
      """<div class="col">{{#children}}{{{html}}}{{/children}}</div>"""
    ),
    "reading" -> CardDef(
      """<div class="reading"><span>{{state}}</span> {{unit}}</div>""",
      slots = List("state")
    ),
    "light" -> CardDef(
      """<div class="light">{{name}}: <span>{{state}}</span></div>""",
      slots = List("state")
    )
  )

  /** A `reading` bound to `e`: its `$state` plus its `unit_of_measurement`
    * attribute.
    */
  def reading(e: FixtureEntity): LayoutNode.Component =
    component(
      "reading",
      "state" -> SlotSource(Some(e.entityId)),
      "unit" -> SlotSource(Some(e.entityId), "$attr.unit_of_measurement")
    )

  /** A named `light` tile bound to `e`, labelled `label`. */
  def light(label: String, e: FixtureEntity): LayoutNode.Component =
    component(
      "light",
      "name" -> lit(label),
      "state" -> SlotSource(Some(e.entityId))
    )

  /** Assemble a dashboard from layout `root` over the shared [[cards]]. */
  def build(
      root: LayoutNode,
      slug: String = "home",
      title: String = "Test Home"
  ): Dashboard =
    Dashboard(cards = cards, card = root, slug = slug, title = Some(title))

  /** The full fixture dashboard over both bound entities — the ready-made SUT
    * for the render/live smoke suites, which drive the whole house at once.
    */
  val dashboard: Dashboard =
    build(
      col(
        reading(HouseFixture.outsideTemp),
        light("Kitchen", HouseFixture.kitchenLight)
      )
    )
}
