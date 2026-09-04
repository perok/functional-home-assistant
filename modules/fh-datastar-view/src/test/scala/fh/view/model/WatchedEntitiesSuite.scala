package fh.view.model

import io.circe.Json

/** [[Dashboard.watchedEntities]] — what the live subscription asks HA for.
  *
  * Every test here is about the gap between it and [[Dashboard.referencedEntities]],
  * because that gap is the only reason the two are separate values. The
  * entities in question DECIDE something and are rendered nowhere, so a
  * subscription built from the narrower set would produce a dashboard that
  * paints correctly and then silently stops reacting — the failure mode no
  * rendering test can see, since the first paint is right.
  */
class WatchedEntitiesSuite extends munit.FunSuite {

  private val tile = Map(
    "tile" -> CardDef("<b>{{state}}</b>", slots = List("state"))
  )

  private def tileNode(id: String): LayoutNode.Component =
    LayoutNode.Component(
      "tile",
      Map(
        "entity_id" -> SlotSource(literal = Some(id)),
        "state" -> SlotSource()
      )
    )

  private val whileOn: Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString("on"))

  private def onEntity(id: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString("on"), entity = Some(id))

  test("a clause guard naming another entity is watched, not referenced") {
    // "Show the banner while the hall sensor is on." The sensor is not a
    // candidate and appears in no slot, so nothing that is RENDERED names it.
    val hall = "binary_sensor.hall"
    val dash = Dashboard(
      cards = tile,
      card = LayoutNode.SetNode(
        candidates = List("light.banner"),
        members = Map(
          "light.banner" -> LayoutNode.SetMember(
            List(LayoutNode.SetClause(Some(onEntity(hall)), tileNode("light.banner")))
          )
        )
      )
    )

    assert(dash.watchedEntities.contains(hall), clue = dash.watchedEntities)
    assert(dash.watchedEntities.contains("light.banner"))
    // The contrast IS the test: the action bound must not grow, or a dashboard
    // that merely reads an entity could act on it (ADR 0023).
    assert(!dash.referencedEntities.contains(hall), clue = dash.referencedEntities)
  }

  test("the entities a Count reads are watched") {
    val counted = List("light.x", "light.y", "light.z")
    val dash = Dashboard(
      cards = tile,
      card = LayoutNode.SetNode(
        candidates = List("light.banner"),
        members = Map(
          "light.banner" -> LayoutNode.SetMember(
            List(
              LayoutNode.SetClause(
                Some(
                  Predicate.Count(
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

  test("a surface's state activation is watched") {
    // The flip's deciding entity: it chooses which panel is baked in and is
    // rendered by nothing at all.
    val alarm = "alarm_control_panel.house"
    val dash = Dashboard(
      cards = tile,
      card = tileNode("light.a"),
      surfaces = Map(
        "armed" -> Surface(
          content = tileNode("light.b"),
          bakeInto = Some(NodeId.derived("host")),
          bakeAs = Some("armed"),
          bakeIndex = Some(0),
          activation = Activation.State(onEntity(alarm))
        )
      )
    )

    assert(dash.watchedEntities.contains(alarm), clue = dash.watchedEntities)
    assert(!dash.referencedEntities.contains(alarm))
    // A surface's CONTENT is still watched — this widens the set, it does not
    // move it.
    assert(dash.watchedEntities.contains("light.b"))
  }

  test("watchedEntities is a superset of referencedEntities") {
    val dash = Dashboard(
      cards = tile,
      card = LayoutNode.SetNode(
        candidates = List("light.a", "light.b"),
        members = Map(
          "light.a" -> LayoutNode.SetMember(
            List(LayoutNode.SetClause(Some(whileOn), tileNode("light.a")))
          ),
          "light.b" -> LayoutNode.SetMember(
            List(
              LayoutNode.SetClause(
                Some(onEntity("binary_sensor.h")),
                tileNode("light.b")
              )
            )
          )
        )
      ),
      surfaces = Map(
        "s" -> Surface(
          content = tileNode("light.c"),
          activation = Activation.State(onEntity("alarm_control_panel.p"))
        )
      )
    )

    // The relationship, not a hand-written list: whatever the walks grow to
    // cover, subscribing to `watchedEntities` must never drop an entity the
    // dashboard is allowed to address.
    assert(
      dash.referencedEntities.subsetOf(dash.watchedEntities),
      clue = dash.referencedEntities -- dash.watchedEntities
    )
  }
}
