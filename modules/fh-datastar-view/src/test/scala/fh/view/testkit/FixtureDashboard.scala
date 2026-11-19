package fh.view.testkit

import fh.view.model.{
  Part,
  CallDecl,
  CardDef,
  Dashboard,
  IdKind,
  LayoutNode,
  Op,
  Step,
  Value,
  ValueExpr,
  ValueType,
  TemplateSlot,
  Slot
}
import fh.view.testkit.DashboardBuilders.{col, component, lit}
import io.circe.Json

import scala.collection.immutable.ListMap

/** The Tier-B builder for the functional suite: the card templates, whose exact
  * HTML the behaviour tests assert on, plus typed constructors binding a card
  * to a [[FixtureEntity]]. Layout reuses [[DashboardBuilders]].
  */
object FixtureDashboard {

  val cards: Map[String, CardDef] = Map(
    "col" -> TestCard(
      """<div class="col">{{{children}}}</div>""",
      regions = Set("children")
    ),
    "reading" -> TestCard(
      """<div class="reading"><span>{{state}}</span> {{#unit}}{{unit}}{{/unit}}</div>"""
    ),
    "light" -> TestCard(
      """<div class="light">{{name}}: <span>{{state}}</span></div>"""
    ),
    // Name and state come from the member's own entity, so one card serves
    // every member.
    "member" -> TestCard(
      """<div class="member">{{name}}: <span>{{state}}</span></div>"""
    ),
    "call" -> TestCard(
      """<button data-call="{{{tap.callId}}}" data-seen="{{{tap.seen}}}"></button>"""
    ),
    // A call beside the state it is about, which the press then confirms.
    "callShowing" -> TestCard(
      """<button data-call="{{{tap.callId}}}" data-seen="{{{tap.seen}}}">{{state}}</button>"""
    )
  )

  /** A declared call on `e` and nothing else: what makes a call one this
    * dashboard declares, and so one the server lets through (ADR 0023). Each of
    * `posted` is a data key read from the press under its own name.
    */
  def call(
      service: String,
      e: FixtureEntity,
      posted: (String, ValueType)*
  ): LayoutNode.Component =
    LayoutNode.Component(
      "call",
      calls = Map(
        "tap" -> CallDecl.Service(
          ValueExpr.literal(service),
          ListMap(IdKind.Entity -> List(ValueExpr.literal(e.entityId))),
          ListMap.from(posted.map((k, t) => k -> ValueExpr(Value.Posted(k, t))))
        )
      )
    )

  /** Where the page posts `node`'s call: its id, and the node it is on. */
  def pressOf(slug: String, node: LayoutNode.Component, id: String): String =
    s"sse/call/$slug/${node.calls("tap").asService.id}?node=$id"

  /** `guard` is every member's presence condition, `None` for always shown. */
  def set(
      candidates: List[String],
      guard: Option[ValueExpr] = None
  ): LayoutNode.Component =
    Sets.node(
      candidates = candidates,
      members = candidates.map { id =>
        id -> Sets.Member(
          List(
            Sets.Clause(
              when = guard,
              node = LayoutNode.Component(
                "member",
                slots = Slots.values(
                  Map(
                    "entity_id" -> TemplateSlot(ValueExpr.literal(id)),
                    "name" -> TemplateSlot(
                      ValueExpr(
                        Value.Entity(Some(id), Part.Whole),
                        List(
                          Step.Cel(
                            "('friendly_name' in value.attr ? value.attr['friendly_name'] : value.entity_id)"
                          )
                        )
                      )
                    ),
                    "state" -> TemplateSlot(
                      ValueExpr(Value.Entity(Some(id)))
                    )
                  )
                )
              )
            )
          )
        )
      }.toMap
    )

  def stateIs(s: String): ValueExpr =
    Conds.cmp("state", Op.Eq, Json.fromString(s))

  /** The unit read is guarded: an absent attribute reads nothing, not an error.
    */
  def reading(e: FixtureEntity): LayoutNode.Component =
    component(
      "reading",
      "state" -> TemplateSlot(ValueExpr(Value.Entity(Some(e.entityId)))),
      "unit" -> TemplateSlot(
        ValueExpr(
          Value.Entity(Some(e.entityId), Part.Whole),
          List(
            Step.Cel(
              "('unit_of_measurement' in value.attr ? value.attr['unit_of_measurement'] : '')"
            )
          )
        )
      )
    )

  def light(label: String, e: FixtureEntity): LayoutNode.Component =
    component(
      "light",
      "name" -> lit(label),
      "state" -> TemplateSlot(ValueExpr(Value.Entity(Some(e.entityId))))
    )

  /** It names its entity: a state condition has no subject to supply (ADR
    * 0007).
    */
  private def entityIs(id: String, state: String): ValueExpr =
    Conds.cmp("state", Op.Eq, Json.fromString(state), entity = Some(id))

  /** An `ifhost` root ("c") showing `c_t0` while `condEntity` holds
    * `activeState`, and `c_else` otherwise (ADR 0007). The condition entity is
    * named in no slot, so a [[Scene]] seeds it with `.entity(..)`.
    */
  def ifElse(
      condEntity: String,
      activeState: String,
      thenBranch: LayoutNode.Component,
      elseBranch: LayoutNode.Component
  ): Dashboard =
    Dashboard(
      cards = Sets.cards ++ cards + ("ifhost" -> TestCard(
        template = """{{{branch}}}""",
        regions = Set("branch")
      )),
      card = LayoutNode.Component(
        "ifhost",
        Map(
          "branch" -> Slots.iff(
            entityIs(condEntity, activeState),
            thenBranch,
            Some(elseBranch)
          )
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
    Dashboard(
      cards = Sets.cards ++ cards,
      card = root,
      slug = slug,
      title = Some(title)
    )

  /** The whole house at once, for the render/live smoke suites. */
  val dashboard: Dashboard =
    build(
      col(
        reading(HouseFixture.outsideTemp),
        light("Kitchen", HouseFixture.kitchenLight)
      )
    )
}
