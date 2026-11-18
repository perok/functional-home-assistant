package fh.view.model

import fh.view.testkit.Sets

import fh.view.testkit.Slots

import fh.view.testkit.TestCard
import fh.view.testkit.Conds

import io.circe.Json

/** [[Dashboard.watchedEntities]], what the live subscription asks HA for, and
  * its gap to [[Dashboard.referencedEntities]]: entities that decide something
  * but render nowhere. Subscribing to the narrower set paints right and then
  * silently stops reacting, invisible to any rendering test.
  */
class WatchedEntitiesSuite extends munit.FunSuite {

  private val tile = Map(
    "tile" -> TestCard("<b>{{state}}</b>")
  )

  private def tileNode(id: String): LayoutNode.Component =
    LayoutNode.Component(
      "tile",
      Slots.values(
        Map(
          "entity_id" -> TemplateSlot(ValueExpr.literal(id)),
          "state" -> TemplateSlot(ValueExpr(Value.Entity(None)))
        )
      )
    )

  private val whileOn: ValueExpr =
    Conds.cmp("state", Op.Eq, Json.fromString("on"))

  private def onEntity(id: String): ValueExpr =
    Conds.cmp("state", Op.Eq, Json.fromString("on"), entity = Some(id))

  test("a clause guard naming another entity is watched, not referenced") {
    // "Show the banner while the hall sensor is on." The sensor is not a
    // candidate and appears in no slot, so nothing that is RENDERED names it.
    val hall = "binary_sensor.hall"
    val dash = Dashboard(
      cards = Sets.cards ++ tile,
      card = Sets.node(
        candidates = List("light.banner"),
        members = Map(
          "light.banner" -> Sets.Member(
            List(
              Sets.Clause(
                Some(onEntity(hall)),
                tileNode("light.banner")
              )
            )
          )
        )
      )
    )

    assert(dash.watchedEntities.contains(hall), clue = dash.watchedEntities)
    assert(dash.watchedEntities.contains("light.banner"))
    // The contrast IS the test: the variable-write bound must not grow, or a
    // viewer's value could chart an entity the dashboard only decides on
    // (ADR 0023).
    assert(
      !dash.referencedEntities.contains(hall),
      clue = dash.referencedEntities
    )
  }

  test("the entities a Count reads are watched") {
    val counted = List("light.x", "light.y", "light.z")
    val dash = Dashboard(
      cards = Sets.cards ++ tile,
      card = Sets.node(
        candidates = List("light.banner"),
        members = Map(
          "light.banner" -> Sets.Member(
            List(
              Sets.Clause(
                Some(
                  Conds.count(
                    candidates = counted,
                    when = counted.map(_ -> whileOn).toMap,
                    op = Op.Gt,
                    value = Json.fromInt(1)
                  )
                ),
                tileNode("light.banner")
              )
            )
          )
        )
      )
    )

    counted.foreach(e =>
      assert(dash.watchedEntities.contains(e), clue = s"$e missing")
    )
  }

  private val host = Map(
    "host" -> TestCard(
      """<div id="{{hostId}}">{{{armed}}}</div>""",
      Set("armed")
    )
  )

  private def hostOf(cond: ValueExpr, shown: LayoutNode) =
    LayoutNode.Component("host", Map("armed" -> Slots.iff(cond, shown)))

  test("a choice's deciding entity is watched") {
    // It chooses which case is shown and is rendered by nothing at all.
    val alarm = "alarm_control_panel.house"
    val dash = Dashboard(
      cards = Sets.cards ++ tile ++ host,
      card = hostOf(onEntity(alarm), tileNode("light.b"))
    ).lowered

    assert(dash.watchedEntities.contains(alarm), clue = dash.watchedEntities)
    assert(!dash.referencedEntities.contains(alarm))
    // A case's CONTENT is still watched — this widens the set, it does not
    // move it.
    assert(dash.watchedEntities.contains("light.b"))
  }

  test("watchedEntities is a superset of referencedEntities") {
    val dash = Dashboard(
      cards = Sets.cards ++ tile ++ host ++ Map(
        "col" -> TestCard("{{{children}}}", Set("children"))
      ),
      card = LayoutNode.Component(
        "col",
        Slots.kids(
          Sets.node(
            candidates = List("light.a", "light.b"),
            members = Map(
              "light.a" -> Sets.Member(
                List(Sets.Clause(Some(whileOn), tileNode("light.a")))
              ),
              "light.b" -> Sets.Member(
                List(
                  Sets.Clause(
                    Some(onEntity("binary_sensor.h")),
                    tileNode("light.b")
                  )
                )
              )
            )
          ),
          hostOf(onEntity("alarm_control_panel.p"), tileNode("light.c"))
        )
      )
    ).lowered

    // The relationship, not a hand-written list: whatever the walks grow to
    // cover, subscribing to `watchedEntities` must never drop an entity the
    // dashboard is allowed to address.
    assert(
      dash.referencedEntities.subsetOf(dash.watchedEntities),
      clue = dash.referencedEntities -- dash.watchedEntities
    )
  }
}
