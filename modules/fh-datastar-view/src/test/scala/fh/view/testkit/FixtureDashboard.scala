package fh.view.testkit

import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  DynamicCase,
  LayoutNode,
  Op,
  Predicate,
  SlotSource,
  Surface
}
import fh.view.testkit.DashboardBuilders.{col, component, lit}
import io.circe.Json

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
    ),
    // The per-entity card a dynamic group renders each matching member through:
    // the member's own live friendly_name + state (both inherited from the
    // matched entity, so one card serves every member).
    "member" -> CardDef(
      """<div class="member">{{name}}: <span>{{state}}</span></div>""",
      slots = List("state")
    )
  )

  /** A dynamic group over `query`, rendering each matching entity through the
    * `member` card. The single always-matching case names no entity — its slots
    * inherit the matched entity — so a group's members are seeded through the
    * [[Scene]]'s `.entity(..)` extras (a group selects by query, so it never
    * names a member in a slot for the reference scan to find).
    */
  def group(query: Predicate): LayoutNode.Dynamic =
    LayoutNode.Dynamic(
      query = Some(query),
      cases = List(
        DynamicCase(
          when = Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__")),
          card = "member",
          slots = Map(
            "name" -> SlotSource(transform = "$attr.friendly_name"),
            "state" -> SlotSource()
          )
        )
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

  /** "Entity `id` is in state `state`" — the entity_id-pinned condition a
    * state-activated surface flips on (the [[fh.view.model.Activation.State]]
    * idiom from ADR 0007).
    */
  private def entityIs(id: String, state: String): Predicate =
    Predicate.And(
      List(
        Predicate.Cmp("entity_id", Op.Eq, Json.fromString(id)),
        Predicate.Cmp("state", Op.Eq, Json.fromString(state))
      )
    )

  /** An If/else dashboard (ADR 0007's state-activated surfaces): an `ifhost`
    * root (id "c") whose `then` branch is baked while `condEntity` holds
    * `activeState`, and whose always-true `else` branch bakes otherwise. This is
    * the shape a state flip drives end-to-end — the host re-bakes the
    * newly-selected branch on the deciding entity's change. The condition entity
    * rides only the activation predicate (named in no slot), so a [[Scene]]
    * seeds it via `.entity(..)`.
    */
  def ifElse(
      condEntity: String,
      activeState: String,
      thenBranch: LayoutNode.Component,
      elseBranch: LayoutNode.Component
  ): Dashboard =
    Dashboard(
      cards = cards + ("ifhost" -> CardDef(
        """<div class="ifhost" id="{{id}}">{{{branch}}}</div>"""
      )),
      card = LayoutNode.Component("ifhost"),
      surfaces = Map(
        "c_then" -> Surface(
          thenBranch,
          bakeInto = Some("c"),
          bakeAs = Some("branch"),
          bakeIndex = Some(0),
          activation = Activation.State(entityIs(condEntity, activeState))
        ),
        "c_else" -> Surface(
          elseBranch,
          bakeInto = Some("c"),
          bakeAs = Some("branch"),
          bakeIndex = Some(1),
          activation = Activation.State(
            Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__"))
          )
        )
      ),
      slug = "ifhome",
      title = Some("If Home")
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
