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
    // The per-entity card a candidate set renders each matching member through:
    // the member's own live friendly_name + state (both inherited from the
    // matched entity, so one card serves every member).
    "member" -> CardDef(
      """<div class="member">{{name}}: <span>{{state}}</span></div>""",
      slots = List("state")
    )
  )

  /** A candidate set over `candidates`, rendering each through the `member`
    * card. `guard` is the presence condition every member carries — `None` for
    * "always shown". A member's slots name its own entity, because the build
    * knows the candidate.
    */
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
                  "name" -> SlotSource(transform = "$attr.friendly_name"),
                  "state" -> SlotSource()
                )
              )
            )
          )
        )
      }.toMap
    )

  /** The `state == s` guard a set's members are usually presence-tested on. */
  def stateIs(s: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString(s))

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

  /** "Entity `id` is in state `state`" — the condition a state-activated
    * surface flips on (the [[fh.view.model.Activation.State]] idiom from ADR
    * 0007). It NAMES its entity: a state condition has no subject to supply.
    */
  private def entityIs(id: String, state: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString(state), entity = Some(id))

  /** An If/else dashboard (ADR 0007's state-activated surfaces): an `ifhost`
    * root (id "c") whose `then` branch is baked while `condEntity` holds
    * `activeState`, and whose always-true `else` branch bakes otherwise. This
    * is the shape a state flip drives end-to-end — the host re-bakes the
    * newly-selected branch on the deciding entity's change. The condition
    * entity rides only the activation predicate (named in no slot), so a
    * [[Scene]] seeds it via `.entity(..)`.
    */
  def ifElse(
      condEntity: String,
      activeState: String,
      thenBranch: LayoutNode.Component,
      elseBranch: LayoutNode.Component
  ): Dashboard =
    Dashboard(
      cards = cards + ("ifhost" -> CardDef(
        template = """<div class="ifhost" id="{{hostId}}">{{{branch}}}</div>""",
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
          // An empty conjunction is vacuously true and reads no entity.
          activation = Activation.State(Predicate.And(Nil))
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
