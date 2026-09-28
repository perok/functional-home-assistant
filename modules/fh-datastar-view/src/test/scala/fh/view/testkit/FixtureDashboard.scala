package fh.view.testkit

import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  Op,
  Predicate,
  Region,
  SlotSource,
  Surface
}
import fh.view.testkit.DashboardBuilders.{col, component, lit}
import fh.view.testkit.TestIds.given
import io.circe.Json

/** The Tier-B builder for the functional suite: the card templates, whose exact
  * HTML the behaviour tests assert on, plus typed constructors binding a card
  * to a [[FixtureEntity]]. Layout reuses [[DashboardBuilders]].
  */
object FixtureDashboard {

  val cards: Map[String, CardDef] = Map(
    "col" -> CardDef(
      """<div class="col">{{#children}}{{{html}}}{{/children}}</div>""",
      regions = Map("children" -> Region())
    ),
    "reading" -> CardDef(
      """<div class="reading"><span>{{state}}</span> {{unit}}</div>""",
      slots = List("state")
    ),
    "light" -> CardDef(
      """<div class="light">{{name}}: <span>{{state}}</span></div>""",
      slots = List("state")
    ),
    // Name and state come from the member's own entity, so one card serves
    // every member.
    "member" -> CardDef(
      """<div class="member">{{name}}: <span>{{state}}</span></div>""",
      slots = List("state")
    )
  )

  /** `guard` is every member's presence condition, `None` for always shown. */
  def set(
      candidates: List[String],
      guard: Option[Predicate] = None
  ): LayoutNode.SetNode =
    LayoutNode.SetNode(
      candidates = candidates,
      members = candidates.map { id =>
        id -> LayoutNode.SetMember(
          List(
            LayoutNode.SetClause(
              when = guard,
              node = LayoutNode.Component(
                "member",
                slots = Map(
                  "entity_id" -> SlotSource(literal = Some(id)),
                  "name" -> SlotSource(
                    transform =
                      "('friendly_name' in attr ? attr['friendly_name'] : entity_id)"
                  ),
                  "state" -> SlotSource()
                )
              )
            )
          )
        )
      }.toMap
    )

  def stateIs(s: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString(s))

  /** The unit read is guarded: an absent attribute reads nothing, not an error.
    */
  def reading(e: FixtureEntity): LayoutNode.Component =
    component(
      "reading",
      "state" -> SlotSource(Some(e.entityId)),
      "unit" -> SlotSource(
        Some(e.entityId),
        "('unit_of_measurement' in attr ? attr['unit_of_measurement'] : '')"
      )
    )

  def light(label: String, e: FixtureEntity): LayoutNode.Component =
    component(
      "light",
      "name" -> lit(label),
      "state" -> SlotSource(Some(e.entityId))
    )

  /** It names its entity: a state condition has no subject to supply (ADR
    * 0007).
    */
  private def entityIs(id: String, state: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString(state), entity = Some(id))

  /** An `ifhost` root ("c") whose `then` bakes while `condEntity` holds
    * `activeState`, and whose always-true `else` bakes otherwise (ADR 0007).
    * The condition entity is named in no slot, so a [[Scene]] seeds it with
    * `.entity(..)`.
    */
  def ifElse(
      condEntity: String,
      activeState: String,
      thenBranch: LayoutNode.Component,
      elseBranch: LayoutNode.Component
  ): Dashboard =
    Dashboard(
      cards = cards + ("ifhost" -> CardDef(
        template =
          """<div class="ifhost" id="{{hostId}}">{{#branch}}{{{html}}}{{/branch}}</div>""",
        regions = Map("branch" -> Region(Region.Baked))
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
          activation = Activation.State(Predicate.And(Nil))
        )
      ),
      slug = "ifhome",
      title = Some("If Home")
    )

  def build(
      root: LayoutNode,
      slug: String = "home",
      title: String = "Test Home"
  ): Dashboard =
    Dashboard(cards = cards, card = root, slug = slug, title = Some(title))

  /** The whole house at once, for the render/live smoke suites. */
  val dashboard: Dashboard =
    build(
      col(
        reading(HouseFixture.outsideTemp),
        light("Kitchen", HouseFixture.kitchenLight)
      )
    )
}
